(ns shiftlefter.project-projection
  "Read-only accepted project knowledge projection.

   The projection is a fresh immutable value built from the current working tree.
   It is not a cache, not a SIEVE session, and not a runtime provisioning path."
  (:require [babashka.fs :as fs]
            [shiftlefter.adapters.registry :as adapters]
            [shiftlefter.intent.loader :as intent-loader]
            [shiftlefter.intent.resolve :as intent-resolve]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.runner.config :as config]
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

(defn- resolve-glossary-config [project-context glossary-config]
  (when glossary-config
    (cond-> glossary-config
      (:subjects glossary-config)
      (update :subjects #(project-context/resolve-config-path project-context %))

      (:intents glossary-config)
      (update :intents #(project-context/resolve-config-path project-context %))

      (:verbs glossary-config)
      (update :verbs (fn [verbs]
                       (into {}
                             (map (fn [[interface-type path]]
                                    [interface-type
                                     (project-context/resolve-config-path project-context path)]))
                             verbs))))))

(defn- resolve-config-declared-paths [project-context cfg]
  (cond-> cfg
    (seq (get-in cfg [:runner :step-paths]))
    (update-in [:runner :step-paths]
               #(project-context/resolve-config-paths project-context %))

    (seq (get-in cfg [:runner :macros :registry-paths]))
    (update-in [:runner :macros :registry-paths]
               #(project-context/resolve-config-paths project-context %))

    (:glossaries cfg)
    (update :glossaries #(resolve-glossary-config project-context %))))

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

(defn- interface-projection [adapter-registry [interface-name interface-def]]
  (let [adapter-name (:adapter interface-def)
        adapter-entry (adapters/get-adapter adapter-name adapter-registry)]
    {:name interface-name
     :type (:type interface-def)
     :adapter adapter-name
     :shared-impl? (boolean (:shared-impl? interface-def))
     :config (:config interface-def)
     :adapter-metadata (if (:error adapter-entry)
                         {:known? false
                          :diagnostic (:error adapter-entry)}
                         {:known? true
                          :impl-key (:impl-key adapter-entry)
                          :provides (vec (:provides adapter-entry))
                          :has-on-provision? (boolean (:on-provision adapter-entry))})}))

(defn- subjects-projection [g]
  (->> (:subjects g)
       (mapv (fn [[subject-type entry]]
               {:type subject-type
                :description (:desc entry)
                :instances (vec (:instances entry))
                :singleton? (nil? (:instances entry))
                :wears (:wears entry)}))))

(defn- instances-projection [g]
  (->> (:instance-index g)
       (mapv (fn [[instance subject-type]]
               {:instance instance
                :type subject-type
                :qualified (keyword (name subject-type) (name instance))}))))

(defn- verbs-projection [g]
  (->> (:verbs g)
       (mapv (fn [[interface-type verbs]]
               {:interface-type interface-type
                :verbs (->> verbs
                            (mapv (fn [[verb entry]]
                                    {:verb verb
                                     :description (:desc entry)
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
                    :root (:root region)
                    :reusable? (boolean (:reusable region))
                    :elements (vec (sort (:elements region)))
                    :collections (->> (:collections region)
                                      (mapv (fn [[coll-name coll-def]]
                                              (assoc coll-def :name coll-name))))
                    :bindings (->> (:lookup intents)
                                   (keep (fn [[[i element] entry]]
                                           (when (= i intent-name)
                                             {:element element
                                              :bindings (:bindings entry)})))
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

(defn- load-glossary-projection [cfg]
  (let [result (glossary/load-all-glossaries-strict (:glossaries cfg))]
    (if (:error result)
      {:error (:error result)}
      {:ok (:ok result)})))

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
      (conj (str "./bin/sl run features --dry-run --config "
                 (:config-path project-context))))
    ;; Consumer/installed form: the `sl` on PATH. Append --config only for an
    ;; explicit config path; a discovered shiftlefter.edn is the runner default.
    (let [cfg-flag (when (= :explicit (:config-source project-context))
                     (str " --config " (:config-path project-context)))]
      [(str "sl run features" cfg-flag)
       (str "sl run features --dry-run" cfg-flag)])))

(defn- projection-inputs [project-context cfg step-load]
  (let [glossaries (:glossaries cfg)
        subject-paths (keep identity [(:subjects glossaries)])
        verb-paths (vals (:verbs glossaries))
        intent-files (glob-files (:intents glossaries) "*.edn")
        macro-paths (config/get-macro-registry-paths cfg)
        step-files (mapcat step-files-under (config/get-step-paths cfg))]
    (vec (concat
          (keep identity [(when-let [p (:config-path project-context)]
                            (input-entry :config p))])
          (map #(input-entry :glossary/subjects %) subject-paths)
          (map #(input-entry :glossary/verbs %) verb-paths)
          (map #(input-entry :glossary/intents %) intent-files)
          (map #(input-entry :stepdef %) (or (:loaded step-load) step-files))
          (map #(input-entry :macro %) macro-paths)))))

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

(defn- config-mode
  "Mode of a loaded config: :shifted iff :svo is present, else :vanilla.
   Post sl-ieie, `runner.config/load-config` includes :svo iff the user's
   config had it, so this reflects user intent."
  [cfg]
  (if (contains? cfg :svo) :shifted :vanilla))

(defn- complete-projection [project-context source cfg glossary-map intents step-load macro-load diagnostics]
  (let [adapter-registry adapters/default-registry
        mode (config-mode cfg)
        stepdefs (:stepdefs step-load)
        interfaces (:interfaces cfg)
        configured-interface-names (set (keys interfaces))
        lintable-stepdefs (filter #(let [iface (get-in % [:metadata :interface])]
                                     (or (nil? iface)
                                         (contains? configured-interface-names iface)))
                                  stepdefs)
        ;; Suite-lint is runner-real in BOTH modes (compile-suite gates it
        ;; only on :interfaces), so it stays unconditional here.
        suite-lint (suite-lint/lint-suite lintable-stepdefs
                                          (keys (get-in cfg [:glossaries :verbs]))
                                          interfaces
                                          adapter-registry)
        ;; Tier-2 stepdef/glossary validation is Shifted-only in the runner
        ;; (compile-suite runs it only when binding opts exist) — the
        ;; projection must not surface diagnostics the runner won't (sl-hjnp).
        stepdef-issues (when (= :shifted mode)
                         (svo-validate/validate-stepdefs-against-glossary
                          stepdefs glossary-map))
        all-diagnostics (vec (concat diagnostics
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
              :mode mode
              :config cfg
              :subjects (subjects-projection glossary-map)
              :instances (instances-projection glossary-map)
              :interfaces (mapv #(interface-projection adapter-registry %) interfaces)
              :verbs (verbs-projection glossary-map)
              :intents (intents-projection intents interfaces)
              :stepdefs stepdefs
              :macros (:macros macro-load)
              :diagnostics all-diagnostics
              :validation-commands (validation-commands project-context)}
        with-inputs (assoc base :inputs (projection-inputs project-context cfg step-load))]
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
                 mode (config-mode cfg)
                 config-diagnostics (concat
                                     (map #(diagnostic :config :error %)
                                          (-> config-result :ok :diagnostics))
                                     ;; sl-hlkz config lints: :warn severity —
                                     ;; rides like glossary-warnings, never
                                     ;; flips projection :status.
                                     (map #(diagnostic :config :warn %)
                                          (-> config-result :ok :lints)))
                 glossary-result (load-glossary-projection cfg)
                 ;; Vanilla honesty (sl-hjnp): the runner never loads project
                 ;; glossaries in Vanilla mode, so a broken :glossaries config
                 ;; must not fail orientation there. Fall back to framework
                 ;; defaults and demote the failure to a :warn diagnostic.
                 [glossary-result glossary-warnings]
                 (if (and (= :vanilla mode) (:error glossary-result))
                   [(load-glossary-projection (dissoc cfg :glossaries))
                    [(diagnostic :glossary :warn
                                 (update (:error glossary-result) :message str
                                         " (ignored in Vanilla mode — the runner loads"
                                         " glossaries only in Shifted mode; showing"
                                         " framework defaults)"))]]
                   [glossary-result nil])
                 intents-result (load-intents-projection cfg)
                 step-load (load-stepdefs-projection! (config/get-step-paths cfg))
                 macro-load (load-macros-projection cfg)
                 blocking (vec (concat config-diagnostics
                                       glossary-warnings
                                       (when (:error glossary-result)
                                         [(diagnostic :glossary :error (:error glossary-result))])
                                       (when (:error intents-result)
                                         [(diagnostic :intents :error (:error intents-result))])))]
             (if (or (:error glossary-result) (:error intents-result))
               {:projection/version projection-version
                :project-context project-context
                :source source
                :status :error
                :mode mode
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
             :mode (:mode projection)
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
