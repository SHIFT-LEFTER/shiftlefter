(ns shiftlefter.project-projection
  "Read-only accepted project knowledge projection.

   The projection is a fresh immutable value built from the current working tree.
   It is not a cache, not a SIEVE session, and not a runtime provisioning path."
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [shiftlefter.adapters.registry :as adapters]
            [shiftlefter.intent.loader :as intent-loader]
            [shiftlefter.intent.resolve :as intent-resolve]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.config :as config]
            [shiftlefter.runner.setup :as setup]
            [shiftlefter.runner.step-loader :as step-loader]
            [shiftlefter.stepengine.macros :as macros]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.stepengine.suite-lint :as suite-lint]
            [shiftlefter.svo.glossary :as glossary]
            [shiftlefter.svo.validate :as svo-validate])
  (:import [java.security MessageDigest]
           [java.time Instant]))

(def projection-version 1)

(def supported-sources #{:working-tree})

(defn- sha256-hex [bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest digest bytes)]
    (apply str (map #(format "%02x" %) hash-bytes))))

(defn- digest-string [s]
  (sha256-hex (.getBytes (str s) "UTF-8")))

(defn- file-digest [path]
  (when (and path (fs/exists? path) (not (fs/directory? path)))
    (sha256-hex (fs/read-all-bytes path))))

(defn- normalize-existing-path [path]
  (try
    (str (fs/real-path path))
    (catch Exception _
      (str (fs/absolutize path)))))

(defn- input-entry [kind path]
  (let [path (str path)]
    (cond-> {:kind kind
             :path (normalize-existing-path path)
             :exists? (fs/exists? path)}
      (file-digest path) (assoc :digest (file-digest path)))))

(defn- glob-files [dir pattern]
  (if (and dir (fs/exists? dir))
    (mapv str (sort (fs/glob dir pattern)))
    []))

(defn- step-files-under [path]
  (let [p (str path)]
    (cond
      (fs/directory? p) (glob-files p "**.{clj,cljc}")
      (fs/exists? p) [p]
      :else [])))

(defn- resolve-config-declared-paths [project-context cfg]
  (cond-> cfg
    (seq (get-in cfg [:runner :step-paths]))
    (update-in [:runner :step-paths]
               #(project-context/resolve-config-paths project-context %))

    (seq (get-in cfg [:runner :macros :registry-paths]))
    (update-in [:runner :macros :registry-paths]
               #(project-context/resolve-config-paths project-context %))

    (:glossaries cfg)
    (update :glossaries #(config/resolve-glossary-config-paths project-context %))))

(defn- load-config-projection [project-context opts]
  (if-let [required (and (not (:allow-defaults? opts))
                         (project-context/require-real-config project-context "project projection"))]
    {:error required}
    (let [result (config/load-config-safe {:project-context project-context})]
      (if (= :error (:status result))
        {:error (select-keys result [:type :message :path])}
        (let [resolved (resolve-config-declared-paths project-context (:config result))
              normalized (config/normalize resolved)]
          {:ok {:config normalized
                :diagnostics (vec (:errors normalized))
                ;; sl-hlkz: lint PRE-normalize output (normalize's synthetic
                ;; :errors key would self-trigger the unknown-key warning).
                ;; Kept separate from :diagnostics — the caller wraps those
                ;; as :error; lints are :warn (runner parity: the same lints
                ;; print as stderr notices in load-config-stage).
                :lints (config/lint-config resolved)}})))))

(defn- adapter-metadata
  "Provenance-honest plan-time adapter metadata (sl-hyey, Warden W4;
   declaration upgrade sl-kkoh).

   `adapter-registry` is the EFFECTIVE plan-time registry — the default
   registry, or the e0ml declared-preview overlay when setup.clj declares
   adapters (the runner's own dry-run model). `decl-by-name` maps adapter
   name → its :adapters declaration (last-wins across groups, matching
   the overlay's flattening).

   - Undeclared builtin entry: full fields, :provenance :builtin-default.
   - Declared entry (novel name OR builtin-name override): the
     declaration's data half (:provides, :capture-kinds) plus — since
     sl-kkoh — the W20 runtime-field declarations where the declaration
     SPEAKS: `:impl-key` when declared (INCLUDING declared-nil, the
     deliberate-absence fact) and `:has-on-provision?` from the
     declaration's `:on-provision?`. Fields the declaration is silent
     about stay named in :runtime-unknown, never guessed — the key
     disappears entirely when nothing is unknown. (These are read from
     the DECLS, not the preview registry, which deliberately carries
     only the data half — `:impl-key nil` would violate ::adapter-entry.)
   - Unknown adapter: {:known? false :diagnostic …} unchanged."
  [adapter-registry decl-by-name adapter-name]
  (let [adapter-entry (adapters/get-adapter adapter-name adapter-registry)]
    (cond
      (:error adapter-entry)
      {:known? false
       :diagnostic (:error adapter-entry)}

      (contains? decl-by-name adapter-name)
      (let [decl (get decl-by-name adapter-name)
            unknown (cond-> []
                      (not (contains? decl :impl-key))
                      (conj :impl-key)
                      (not (contains? decl :on-provision?))
                      (conj :has-on-provision?))]
        (cond-> {:known? true
                 :provenance :declared
                 :provides (vec (:provides adapter-entry))
                 :capture-kinds (vec (sort (keys (:capture adapter-entry))))}
          (contains? decl :impl-key)
          (assoc :impl-key (:impl-key decl))
          (contains? decl :on-provision?)
          (assoc :has-on-provision? (:on-provision? decl))
          (seq unknown)
          (assoc :runtime-unknown unknown)))

      :else
      {:known? true
       :provenance :builtin-default
       :impl-key (:impl-key adapter-entry)
       :provides (vec (:provides adapter-entry))
       :capture-kinds (vec (sort (keys (:capture adapter-entry))))
       :has-on-provision? (boolean (:on-provision adapter-entry))})))

(defn- interface-projection [adapter-registry decl-by-name [interface-name interface-def]]
  (let [adapter-name (:adapter interface-def)]
    {:name interface-name
     :type (:type interface-def)
     :adapter adapter-name
     :shared-impl? (boolean (:shared-impl? interface-def))
     :config (:config interface-def)
     :adapter-metadata (adapter-metadata adapter-registry decl-by-name adapter-name)}))

(defn- subjects-projection [g]
  (->> (:subjects g)
       (mapv (fn [[subject-type entry]]
               {:type subject-type
                :description (:desc entry)
                :instances (vec (:instances entry))
                :singleton? (nil? (:instances entry))
                :wears (:wears entry)
                ;; Per-persona meta (sl-4e91): {inst-kw {:desc ... :wears ...}}
                :instance-meta (:instance-meta entry)
                ;; Merge-time provenance (sl-0gfc); no default subjects ship,
                ;; so absent marker data can only mean :user-file.
                :provenance (get-in g [:provenance :subjects subject-type]
                                    :user-file)}))))

(defn- instances-projection [g]
  (->> (:instance-index g)
       (mapv (fn [[instance subject-type]]
               (let [qualified (keyword (name subject-type) (name instance))
                     imeta (glossary/instance-meta g qualified)
                     worn (glossary/effective-wears g qualified)]
                 (cond-> {:instance instance
                          :type subject-type
                          :qualified qualified
                          :provenance (get-in g [:provenance :subjects subject-type]
                                              :user-file)}
                   ;; Persona docstring + effective costume (sl-4e91)
                   (:desc imeta) (assoc :description (:desc imeta))
                   worn (assoc :wears (:wears worn)
                               :wears-source (:source worn))))))))

(defn- verbs-projection [g]
  (->> (:verbs g)
       (mapv (fn [[interface-type verbs]]
               {:interface-type interface-type
                :source (get-in g [:sources :verbs interface-type])
                :verbs (->> verbs
                            (mapv (fn [[verb entry]]
                                    {:verb verb
                                     :description (:desc entry)
                                     :provenance (get-in g [:provenance :verbs
                                                            interface-type verb]
                                                         :builtin)
                                     :frames (->> (:frames entry)
                                                  (mapv (fn [[frame frame-def]]
                                                          {:frame frame
                                                           :args (vec (:args frame-def))
                                                           :pattern (:pattern frame-def)
                                                           :implicit-object (:implicit-object frame-def)})))})))}))))

(defn- representative-addresses [intents intent-name region]
  (let [elements (sort (:elements region))
        collections (sort-by (comp name key) (:collections region))
        direct (mapv #(str intent-name "." %) elements)
        terminal-colls (mapv (fn [[coll-name _]]
                               (str intent-name "." (name coll-name) "[1]"))
                             collections)
        nested (mapcat
                (fn [[coll-name coll-def]]
                  (let [component (:intent coll-def)
                        component-elements (sort (get-in intents [:regions component :elements]))]
                    (map #(str intent-name "." (name coll-name) "[1]." %)
                         component-elements)))
                collections)]
    (vec (concat direct terminal-colls nested))))

(defn- validate-representative-address [intents interfaces address]
  (let [parse-result (intent-resolve/parse-intent-ref address)]
    (if (:error parse-result)
      (:error parse-result)
      (let [interface-types (->> interfaces vals (keep :type) distinct)
            issues (keep (fn [interface-type]
                           (when-let [issue (intent-resolve/validate-path-static
                                             intents (:ok parse-result) interface-type)]
                             (assoc issue :interface-type interface-type)))
                         interface-types)]
        (when (seq issues)
          {:type :intent/invalid-address
           :address address
           :issues (vec issues)})))))

(defn- intents-projection [intents interfaces]
  (let [regions (:regions intents)]
    (->> regions
         (mapv (fn [[intent-name region]]
                 (let [addresses (representative-addresses intents intent-name region)]
                   {:intent intent-name
                    :provenance :user-file
                    :root (:root region)
                    :reusable? (boolean (:reusable region))
                    :elements (vec (sort (:elements region)))
                    :collections (->> (:collections region)
                                      (mapv (fn [[coll-name coll-def]]
                                              (assoc coll-def :name coll-name))))
                    :bindings (->> (:lookup intents)
                                   (keep (fn [[[i element] entry]]
                                           (when (= i intent-name)
                                             (cond-> {:element element
                                                      :bindings (:bindings entry)}
                                               ;; sl-ectw: the declared content
                                               ;; pattern, as authored.
                                               (:shape entry)
                                               (assoc :shape (get-in entry [:shape :source]))))))
                                   vec)
                    :representative-addresses addresses
                    :address-diagnostics (vec (keep #(validate-representative-address
                                                      intents interfaces %)
                                                    addresses))}))))))

(defn- location-map [loc]
  (when loc
    {:line (:line loc)
     :column (:column loc)}))

(defn- macro-step-projection [step]
  {:keyword (:step/keyword step)
   :text (:step/text step)
   :location (location-map (:step/location step))
   :arguments (:step/arguments step)})

(defn- macro-projection [macro]
  {:id (digest-string (:macro/key macro))
   :kind :text-expansion
   :representation-version 1
   :source {:file (-> macro :macro/definition :file)
            :location (location-map (-> macro :macro/definition :location))}
   :invocation {:key (:macro/key macro)
                :suffix " +"}
   :expansion (mapv macro-step-projection (:macro/steps macro))
   :raw (select-keys macro [:macro/key :macro/description :macro/definition :macro/steps])
   :diagnostics []})

(defn- stepdef-projection [stepdef]
  {:id (:stepdef/id stepdef)
   :pattern-src (:pattern-src stepdef)
   :source (:source stepdef)
   :arity (:arity stepdef)
   :interface (get-in stepdef [:metadata :interface])
   :requires-protocols (vec (get-in stepdef [:metadata :requires-protocols]))
   :svo (get-in stepdef [:metadata :svo])
   :metadata (:metadata stepdef)})

(defn- load-built-in-steps! []
  (require 'shiftlefter.stepdefs.browser :reload)
  (require 'shiftlefter.stepdefs.sms :reload))

(defn- load-stepdefs-projection! [step-paths]
  (let [load-result (step-loader/load-step-paths! step-paths)]
    (if (= :error (:status load-result))
      {:stepdefs (mapv stepdef-projection (registry/all-stepdefs))
       :loaded (:loaded load-result)
       :diagnostics (mapv (fn [err]
                            (assoc (:error err)
                                   :path (:path err)))
                          (:errors load-result))}
      (do
        (load-built-in-steps!)
        {:stepdefs (mapv stepdef-projection (registry/all-stepdefs))
         :loaded (:loaded load-result)
         :diagnostics []}))))

(defn- load-glossary-projection
  "Strict glossary load for the projection. Returns {:ok glossary
   :warnings [...]} or {:error ...} — :warnings are the structured
   glossary-load lints (sl-67dj, Warden P3-13) the caller renders as
   :glossary :warn diagnostics, the config-lints pattern."
  [cfg]
  (let [result (glossary/load-all-glossaries-strict (:glossaries cfg))]
    (if (:error result)
      {:error (:error result)}
      {:ok (:ok result) :warnings (:warnings result)})))

(defn- load-intents-projection [cfg]
  (let [intents-path (get-in cfg [:glossaries :intents])
        result (intent-loader/load-all-intents intents-path)]
    (if (:errors result)
      {:error {:type :intent/load-failed
               :message (str "Failed to load intents from " intents-path)
               :errors (:errors result)}}
      {:ok (:ok result)})))

(defn- load-macros-projection [cfg]
  (if-not (config/macros-enabled? cfg)
    {:macros [] :diagnostics []}
    (let [result (macros/load-registries (config/get-macro-registry-paths cfg))]
      {:macros (mapv macro-projection (vals (:registry result)))
       :diagnostics (:errors result)})))

(defn- repo-context?
  "True when the projection is being built inside the ShiftLefter source repo —
   its own `bin/sl` wrapper sits at the project root. A jar-installed consumer
   project never has it, so this distinguishes the two without any config flag."
  [project-context]
  (boolean (when-let [root (:project-root project-context)]
             (fs/exists? (fs/path root "bin" "sl")))))

(defn- validation-commands
  "Commands an agent runs to validate the project.

   Default is the CONSUMER form — the installed `sl` on PATH (sl-10s). A cold
   agent on a consumer project has no `./bin/sl` or `./bin/kaocha` (those are
   ShiftLefter's own repo wrappers), so emitting them is a false-negative. The
   repo form survives only when building inside the source repo itself."
  [project-context]
  (if (repo-context? project-context)
    ;; Repo form (unchanged): the framework's own wrappers.
    (cond-> ["./bin/kaocha unit"]
      (:config-path project-context)
      (conj (str "./bin/sl run --dry-run --config "
                 (:config-path project-context))))
    ;; Consumer/installed form: the `sl` on PATH. Bare `sl run` is
    ;; well-defined post-fcjm (the config feature corpus, default
    ;; sl/features/). Append --config only for an explicit config path; a
    ;; discovered sl/shiftlefter.edn is the runner default.
    (let [cfg-flag (when (= :explicit (:config-source project-context))
                     (str " --config " (:config-path project-context)))]
      [(str "sl run" cfg-flag)
       (str "sl run --dry-run" cfg-flag)])))

(defn- projection-inputs [project-context cfg step-load setup-path]
  (let [glossaries (:glossaries cfg)
        subject-paths (keep identity [(:subjects glossaries)])
        verb-paths (:verbs glossaries)
        intent-files (glob-files (:intents glossaries) "*.edn")
        macro-paths (config/get-macro-registry-paths cfg)
        step-files (mapcat step-files-under (config/get-step-paths cfg))]
    (vec (concat
          (keep identity [(when-let [p (:config-path project-context)]
                            (input-entry :config p))
                          ;; sl-hyey: setup.clj's declarations shape
                          ;; :adapter-metadata, so the fingerprint sees it.
                          (when setup-path
                            (input-entry :setup setup-path))])
          (map #(input-entry :glossary/subjects %) subject-paths)
          (map #(input-entry :glossary/verbs %) verb-paths)
          (map #(input-entry :glossary/intents %) intent-files)
          (map #(input-entry :stepdef %) (or (:loaded step-load) step-files))
          (map #(input-entry :macro %) macro-paths)))))

(defn- git-state
  "Substrate git state for a directory: {:git-sha \"abc1234\" :git-dirty? bool},
   or {:git-sha nil} when the directory is not a git repository (or git is
   unavailable). Always passes :dir — a projection can be built from any cwd
   (the fuzz.clj sha helper's missing-:dir is the mistake this avoids)."
  [dir]
  (if-not dir
    {:git-sha nil}
    (try
      (let [sha (shell/sh "git" "rev-parse" "--short" "HEAD" :dir (str dir))]
        (if (zero? (:exit sha))
          {:git-sha (str/trim (:out sha))
           :git-dirty? (let [st (shell/sh "git" "status" "--porcelain"
                                          :dir (str dir))]
                         (boolean (and (zero? (:exit st))
                                       (seq (str/trim (:out st))))))}
          {:git-sha nil}))
      (catch Exception _
        {:git-sha nil}))))

(defn- substrate
  "Substrate provenance (sl-0gfc AC addendum 3 + 08-09 ruling 3): the config
   and tree a resolved-state output was computed against. Deliberately OUTSIDE
   the fingerprint key-set — the fingerprint is content identity; substrate is
   the citation."
  [project-context]
  (merge {:config-path (:config-path project-context)
          :config-source (:config-source project-context)}
         (git-state (:project-root project-context))))

(defn- enforcement-effective
  "The EFFECTIVE :enforcement levels from a LOADED config — one level per
   kind, the :all umbrella and the conditional defaults already folded in
   by `shiftlefter.config/load-config` (sl-6wh9). Always a map."
  [cfg]
  (config/get-enforcement cfg))

(defn- fingerprint [projection-without-fingerprint]
  (digest-string (pr-str (select-keys projection-without-fingerprint
                                      [:projection/version
                                       :project-context
                                       :source
                                       :inputs
                                       :config
                                       :subjects
                                       :instances
                                       :interfaces
                                       :verbs
                                       :intents
                                       :stepdefs
                                       :macros
                                       :diagnostics]))))

(defn- diagnostic [stage severity issue]
  (assoc issue :stage stage :severity severity))

(defn- zero-interfaces-diagnostics
  "sl-a55s: the ONE interface fact that is true without reading a feature —
   zero interface instances are configured (an explicit `:interfaces` nil
   or {}; an ABSENT key gets the builtin set in config, and a non-map is
   already `:config/invalid-interfaces`), so no interface-typed step can
   plan. :warn, in the config-lints pattern: a project being set up sits
   here legitimately, so it never flips :status or orient's exit code."
  [cfg]
  (let [interfaces (:interfaces cfg)]
    (when (or (nil? interfaces)
              (and (map? interfaces) (empty? interfaces)))
      [(diagnostic
        :config :warn
        {:type :config/zero-interfaces
         :message (str ":interfaces is empty: zero interface instances are"
                       " configured, so no interface-typed step can plan."
                       " Add one (e.g. {:interfaces {:web :default}}) before"
                       " running features.")})])))

(defn- load-setup-declarations
  "Load setup.clj's e0ml :adapters declarations for plan-time adapter
   metadata (sl-hyey, Warden W4). Pure data by construction — declarations
   carry no functions. Loading setup.clj here is not a new execution class:
   the projection already load-files stepdef user code, and the runner
   load-files setup.clj on every --dry-run (layout doctrine: keep its top
   level pure).

   Returns {:setup-path p-or-nil :declarations [decl …] :error e-or-nil}:
   - no setup.clj → {:setup-path nil :declarations []}
   - loaded       → decls from ALL groups, declaration order (a name
                    declared twice resolves last-wins in the preview
                    overlay — whole-project projection; per-group
                    refinement is future work)
   - load/shape failure → :error (runner parity: that project's run dies
     at planning, so the projection reports :error and adapter metadata
     falls back to the default registry — what is still known)."
  [project-context cfg]
  (if-let [setup-path (setup/find-setup-file (:config-path project-context))]
    (let [result (setup/load-setup setup-path cfg)]
      (if (:error result)
        {:setup-path setup-path :declarations [] :error (:error result)}
        {:setup-path setup-path
         :declarations (vec (mapcat :adapters (:ok result)))
         :error nil}))
    {:setup-path nil :declarations []}))

(defn- complete-projection [project-context source cfg glossary-map intents step-load macro-load setup-decls diagnostics]
  (let [decls (:declarations setup-decls)
        ;; sl-hyey (Warden W4): the plan-time registry is the runner's own
        ;; dry-run model — the default registry overlaid with the e0ml
        ;; declarations when setup.clj carries any. Never a parallel path.
        adapter-registry (if (seq decls)
                           (setup/declared-preview-registry decls)
                           adapters/default-registry)
        ;; sl-kkoh: name → declaration (not just the name set) so
        ;; adapter-metadata can read the W20 runtime-field declarations.
        ;; Last-wins across groups, matching the overlay's flattening.
        decl-by-name (into {} (map (juxt :name identity)) decls)
        stepdefs (:stepdefs step-load)
        ;; sl-a7sx (Warden W-B2): a non-map :interfaces is already a
        ;; :config/invalid-interfaces diagnostic in `blocking` (the
        ;; projection continues past config errors so orient can show
        ;; them); render the rest against zero interfaces instead of
        ;; crashing in interface-projection.
        interfaces (let [i (:interfaces cfg)] (if (map? i) i {}))
        ;; sl-a55s (Chair 2026-09-21): the projection describes the project
        ;; WITHOUT the features, so it runs only the corpus-free part of
        ;; suite-lint (:glossary/orphan-type). :stepdef/undefined-interface
        ;; and :stepdef/missing-capability are defined over a MATCHED
        ;; stepdef — facts about the features, computed by the run path
        ;; alone (compile-suite over used-stepdef-infos). Running them here
        ;; over every REGISTERED stepdef raised false alarms for unused
        ;; builtins, and the filter that hid those hid everything in the one
        ;; case that mattered (zero interfaces). `sl run --dry-run` is the
        ;; gate for features; this is not a second one.
        ;; sl-67dj: project-declared glossary types come from the LOADED
        ;; glossary's actual mounts (:sources = declared-type → path, built
        ;; by the loader), not from config keys — config is a path list now.
        suite-lint (suite-lint/lint-project
                    (keys (get-in glossary-map [:sources :verbs]))
                    interfaces)
        ;; Tier-2 stepdef/glossary validation needs no features either: it
        ;; judges every loaded stepdef against the glossary, as the runner
        ;; does on every compile (sl-6wh9, sl-hjnp).
        stepdef-issues (svo-validate/validate-stepdefs-against-glossary
                        stepdefs glossary-map interfaces)
        all-diagnostics (vec (concat diagnostics
                                     (when-let [e (:error setup-decls)]
                                       [(diagnostic :setup :error e)])
                                     (map #(diagnostic :suite-lint :error %) suite-lint)
                                     (map #(diagnostic :stepdefs :error %) stepdef-issues)
                                     (map #(diagnostic :steps :error %) (:diagnostics step-load))
                                     (map #(diagnostic :macros :error %) (:diagnostics macro-load))))
        base {:projection/id (str "proj-" (java.util.UUID/randomUUID))
              :projection/version projection-version
              :built-at (str (Instant/now))
              :project-context project-context
              :source source
              :status (if (some #(= :error (:severity %)) all-diagnostics)
                        :error
                        :ok)
              :config cfg
              :substrate (substrate project-context)
              :enforcement-effective (enforcement-effective cfg)
              :subjects (subjects-projection glossary-map)
              :instances (instances-projection glossary-map)
              :interfaces (mapv #(interface-projection adapter-registry decl-by-name %) interfaces)
              ;; Pure DATA only — the preview registry itself holds factory
              ;; fns (default-registry overlay) and must never enter the
              ;; EDN-dumped projection value. Consumers (usage-index corpus
              ;; bind) recompute the preview registry from these.
              :adapter-declarations (vec decls)
              :verbs (verbs-projection glossary-map)
              :intents (intents-projection intents interfaces)
              :stepdefs stepdefs
              :macros (:macros macro-load)
              :diagnostics all-diagnostics
              :validation-commands (validation-commands project-context)}
        with-inputs (assoc base :inputs (projection-inputs project-context cfg step-load
                                                           (:setup-path setup-decls)))]
    (assoc with-inputs :fingerprint (fingerprint with-inputs))))

(defn build-projection
  "Build a fresh read-only project projection from a canonical project context.

   Options:
   - :source must be :working-tree for 0.5.
   - :allow-defaults? explicitly permits default-only config mode."
  ([project-context]
   (build-projection project-context {:source :working-tree}))
  ([project-context opts]
   (let [source (or (:source opts) :working-tree)]
     (cond
       (not (contains? supported-sources source))
       {:projection/version projection-version
        :source source
        :status :error
        :diagnostics [(diagnostic :source :error
                                  {:type :projection/unsupported-source
                                   :message (str "Unsupported projection source: " source)})]}

       :else
       (let [config-result (load-config-projection project-context opts)]
         (if (:error config-result)
           {:projection/version projection-version
            :project-context project-context
            :source source
            :status :error
            :diagnostics [(diagnostic :config :error (:error config-result))]}
           (let [cfg (-> config-result :ok :config)
                 config-diagnostics (concat
                                     (map #(diagnostic :config :error %)
                                          (-> config-result :ok :diagnostics))
                                     ;; sl-hlkz config lints: :warn severity —
                                     ;; rides like glossary-warnings, never
                                     ;; flips projection :status.
                                     (map #(diagnostic :config :warn %)
                                          (-> config-result :ok :lints))
                                     (zero-interfaces-diagnostics cfg))
                 ;; The runner loads project glossaries on every run
                 ;; (sl-6wh9), so a broken :glossaries config fails the
                 ;; projection exactly as it fails the run (sl-hjnp honesty).
                 glossary-result (load-glossary-projection cfg)
                 ;; sl-67dj (Warden P3-13): structured glossary-load warnings
                 ;; (e.g. :glossary/override-flag-ignored) ride as :warn
                 ;; diagnostics — the config-lints pattern; never flip :status.
                 glossary-lints (map #(diagnostic :glossary :warn %)
                                     (:warnings glossary-result))
                 intents-result (load-intents-projection cfg)
                 step-load (load-stepdefs-projection! (config/get-step-paths cfg))
                 macro-load (load-macros-projection cfg)
                 setup-decls (load-setup-declarations project-context cfg)
                 blocking (vec (concat config-diagnostics
                                       glossary-lints
                                       (when (:error glossary-result)
                                         [(diagnostic :glossary :error (:error glossary-result))])
                                       (when (:error intents-result)
                                         [(diagnostic :intents :error (:error intents-result))])))]
             (if (or (:error glossary-result) (:error intents-result))
               {:projection/version projection-version
                :project-context project-context
                :source source
                :status :error
                :config cfg
                :stepdefs (:stepdefs step-load)
                :macros (:macros macro-load)
                :diagnostics (vec (concat blocking
                                          (map #(diagnostic :steps :error %)
                                               (:diagnostics step-load))
                                          (map #(diagnostic :macros :error %)
                                               (:diagnostics macro-load))))}
               (complete-projection project-context
                                    source
                                    cfg
                                    (:ok glossary-result)
                                    (:ok intents-result)
                                    step-load
                                    macro-load
                                    setup-decls
                                    blocking)))))))))

(defn- include-section? [include section]
  (or (nil? include)
      (contains? include section)))

(defn- detail-items [detail items]
  (if (= :summary detail)
    []
    (vec items)))

(defn- byte-count [value]
  (count (.getBytes (pr-str value) "UTF-8")))

(defn- take-within-bytes [items max-bytes]
  (loop [remaining items
         kept []
         bytes 0]
    (if-let [item (first remaining)]
      (let [item-bytes (byte-count item)
            next-bytes (+ bytes item-bytes)]
        (if (and (seq kept) (> next-bytes max-bytes))
          [kept remaining]
          (recur (rest remaining) (conj kept item) next-bytes)))
      [kept []])))

(defn- bounded [items budget]
  (let [items (vec items)
        max-items (:max-items budget)
        [items item-omission] (if (and max-items (> (count items) max-items))
                                [(vec (take max-items items))
                                 {:omitted-count (- (count items) max-items)
                                  :reason :max-items}]
                                [items nil])
        max-bytes (:max-bytes budget)
        [items byte-omission] (if (and max-bytes (> (byte-count items) max-bytes))
                                (let [[kept omitted] (take-within-bytes items max-bytes)]
                                  [(vec kept)
                                   {:omitted-count (count omitted)
                                    :reason :max-bytes
                                    :max-bytes max-bytes}])
                                [items nil])]
    [items (or byte-omission item-omission)]))

(defn- section-view [_projection detail budget items]
  (let [[bounded-items omission] (bounded (detail-items detail items) budget)]
    (cond-> {:count (count items)
             :items bounded-items}
      omission (assoc :omission omission))))

(defn project-view
  "Derive a deterministic bounded view from a complete projection.

   Options:
   - :interfaces filters interface names for interface entries.
   - :include limits sections.
   - :detail is :summary or :full.
   - :budget currently supports :max-items per section."
  [projection {:keys [interfaces include detail budget]
               :or {detail :summary
                    budget {}}}]
  (let [include (some-> include set)
        interface-set (some-> interfaces set)
        interface-items (cond->> (:interfaces projection)
                          interface-set (filter #(contains? interface-set (:name %))))
        interface-types (when interface-set
                          (set (keep :type interface-items)))
        verb-items (cond->> (:verbs projection)
                     interface-types (filter #(contains? interface-types (:interface-type %))))
        stepdef-items (cond->> (:stepdefs projection)
                        interface-set (filter #(or (nil? (:interface %))
                                                   (contains? interface-set (:interface %)))))]
    (cond-> {:projection/id (:projection/id projection)
             :projection/version (:projection/version projection)
             :fingerprint (:fingerprint projection)
             :source (:source projection)
             :status (:status projection)
             :counts {:subjects (count (:subjects projection))
                      :instances (count (:instances projection))
                      :interfaces (count interface-items)
                      :verbs (reduce + (map #(count (:verbs %)) verb-items))
                      :intents (count (:intents projection))
                      :stepdefs (count stepdef-items)
                      :macros (count (:macros projection))
                      :diagnostics (count (:diagnostics projection))}}
      (include-section? include :subjects)
      (assoc :subjects (section-view projection detail budget (:subjects projection)))

      (include-section? include :interfaces)
      (assoc :interfaces (section-view projection detail budget interface-items))

      (include-section? include :verbs)
      (assoc :verbs (section-view projection detail budget verb-items))

      (include-section? include :intents)
      (assoc :intents (section-view projection detail budget (:intents projection)))

      (include-section? include :stepdefs)
      (assoc :stepdefs (section-view projection detail budget stepdef-items))

      (include-section? include :macros)
      (assoc :macros (section-view projection detail budget (:macros projection)))

      (include-section? include :diagnostics)
      (assoc :diagnostics (section-view projection detail budget (:diagnostics projection))))))
