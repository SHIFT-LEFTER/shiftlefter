(ns shiftlefter.config
  "The config-language module for `shiftlefter.edn`: specs, loading/merge,
   lint, validation, and accessors. Substrate, not runner machinery —
   consumed by the invocation surfaces (core/repl/doctor/orient), the
   runner, the stepengine, and the projections alike; the runner's
   orchestration stages (runner/core `load-config-stage`) FEED it, they
   don't own it. Sits beside `shiftlefter.config.user` (machine config).
   Extracted from `shiftlefter.runner.config` at sl-raf4 (the F5 deep
   fix); the sibling did-you-mean helpers live in `shiftlefter.text`.

   ## Config Precedence

   1. `--config <path>` (explicit CLI flag)
   2. Discovered `sl/shiftlefter.edn` (nearest ancestor, sl-fcjm)
   3. Built-in defaults

   ## Config Shape

   ```clojure
   {:parser {:dialect \"en\"}
    :runner {:step-paths [\"steps/\"]
             :allow-pending? false
             :macros {:enabled? false}}
    :glossaries {:subjects \"glossary/subjects.edn\"
                 :verbs [\"glossary/verbs/web.edn\"]} ; each file's :type is
                                                    ; its mount key (sl-67dj)
    :interfaces {:web {:type :web        ; presence of :interfaces = the
                       :adapter :etaoin  ; COMPLETE set, replacing the builtin
                       :config {:headless true}}} ; defaults wholesale; an
                                         ; entry value :default re-includes
                                         ; the builtin def verbatim (sl-g2lj)
    :enforcement {:all :warn            ; optional umbrella: sets every kind;
                  :unknown-subject :warn ; a named key overrides it;
                  :unknown-verb :warn    ; :unknown-interface ignores it
                  :unknown-interface :error
                  :unknown-object :off   ; :warn once an :intents glossary is
                  :untyped-stepdef :warn ; configured (sl-femd); :raw-locator
                  :raw-locator :off}}    ; follows the same conditional rule
   ```

   There is ONE mode (sl-6wh9): the planner binds every step; the
   :enforcement block holds nothing but levels and is always present in a
   loaded config — its absence means the defaults, its presence means
   nothing.

   ## Usage

   ```clojure
   (load-config {:config-path \"my-config.edn\"})
   ;=> {:parser {:dialect \"en\"} :runner {...} ...}
   ```"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [shiftlefter.gherkin.io :as io]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.text :as text]))

;; -----------------------------------------------------------------------------
;; Specs — Config Shape
;; -----------------------------------------------------------------------------

;; Parser sub-config
(s/def ::dialect string?)
(s/def ::parser (s/keys :req-un [::dialect]))

;; Runner sub-config
(s/def ::step-paths (s/coll-of string?))
;; sl-fcjm: feature corpus default. Config-root-relative (sl/features/ in the
;; discovered layout); consumed by bare `sl run` in the classic (non-setup)
;; pipeline only. CLI paths win; setup.clj plan ownership is unaffected.
(s/def ::features (s/coll-of string?))
(s/def ::allow-pending? boolean?)
(s/def ::enabled? boolean?)
(s/def ::registry-paths (s/coll-of string?))
(s/def ::macros (s/keys :req-un [::enabled?]
                        :opt-un [::registry-paths]))
;; sl-aa5: capability provisioning strategy. :eager (default, scoped-eager
;; — provision every interface the scenario touches at scenario start)
;; or :lazy (provision on first step that needs it). :lazy exists because
;; bare callers (REPL step invocation) have NO PLAN to scan — eager is not
;; declined for them but IMPOSSIBLE, so lazy is their only coherent mode;
;; that REPL path was the primary driver. Eager was always the intended
;; suite default.
(s/def ::provisioning #{:eager :lazy})
;; Reporter output config (sl-40to). [:runner :report :junit-xml] is the
;; config mirror for the --junit-xml flag; a nil/absent value = off. The flag
;; wins when both are present (resolved in runner/core resolve-junit-path).
(s/def ::junit-xml (s/nilable string?))
;; [:runner :report :html] is the config mirror for the --html flag (sl-muq9);
;; same rules as :junit-xml — nil/absent = off, flag wins.
(s/def ::html (s/nilable string?))
(s/def ::report (s/keys :opt-un [::junit-xml ::html]))
;; sl-q9wp: scenario parallelism bound. [:runner :max-parallel] is the config
;; mirror for the --max-parallel flag; absent/1 = today's sequential
;; execution. The flag wins when both are present (resolved in runner/core
;; resolve-max-parallel).
(s/def ::max-parallel pos-int?)
;; sl-3q9r: declarative capture policy. [:runner :capture] is the config
;; mirror for the --capture flag; the flag wins AS A WHOLE VALUE when both
;; are set (runner/core resolve-capture — never merged key-by-key, matching
;; every other mirror). KINDS are open keywords (adapter-contributed —
;; browser declares :screenshot/:console/:page-source; other adapters may
;; declare their own); POLICIES are the closed forever-surface. Default is
;; all-kinds-:never (D2 — opt-in; goldens and behavior unchanged).
(s/def ::capture-value #{:never :on-failure :every-step})
(s/def ::capture (s/map-of keyword? ::capture-value))
;; sl-08f7: the run seed. [:runner :seed] is the config mirror for the
;; --seed flag (CLI wins); absent = a fresh seed is generated per
;; invocation. Positive 64-bit long, decimal-printed; recorded in the run
;; record (:run/seed) and handed down to hooks/steps/:start.
(s/def ::seed pos-int?)
;; sl-kowi (Ruling 4): the halt policy. [:runner :halt-on-observation-failure]
;; is run-level and config-only (no CLI flag); absent/false = grind on (the
;; honest default — skipping is a form of not-running what was asked, and
;; the environment can recover mid-run). true = after any scenario
;; finalizes observation-class, no further scenario STARTS (in-flight ones
;; finish and keep their verdicts; the rest are :skipped). Exit is 4 either
;; way — the halt changes wall-clock and report noise, never the verdict.
;; TRI-STATE since sl-78u4 (Warden W3): false | :group | :run, with true
;; reading as :run. The key names the group TOPOLOGY: :run = the groups are
;; partitions of ONE environment (a dark channel in one says the rest would
;; time out too — unstarted groups are skipped entirely, their :start never
;; runs); :group = the groups are DIFFERENT environments (a dark channel in
;; one says nothing about the others — siblings run to completion).
(s/def ::halt-on-observation-failure
  (s/or :off false? :on true? :scope #{:group :run}))
;; sl-190x: the tracker link template for @blocked=REF holds —
;; [:runner :ref-url "https://tracker/{ref}"]. `{ref}` is substituted into
;; the :held record (:ref-url) and rendered as a link in the HTML report;
;; nothing is ever fetched (no tracker integration — a CI script reads
;; the record and asks the tracker itself). Absent = no link.
(s/def ::ref-url
  (s/with-gen (s/and string? #(str/includes? % "{ref}"))
    #(gen/fmap (fn [host] (str "https://" host "/browse/{ref}"))
               (gen/not-empty (gen/string-alphanumeric)))))
(s/def ::runner (s/keys :req-un [::step-paths ::allow-pending?]
                        :opt-un [::features ::macros ::provisioning ::report
                                 ::max-parallel ::capture ::seed
                                 ::halt-on-observation-failure ::ref-url]))

;; Timing sub-config (sl-99u2) — the deadline substrate. Appears at the top
;; level (global rung) and as a sibling key on interface entries (instance/
;; interface rungs). Resolution ladder + framework defaults live in
;; shiftlefter.step/timing; deliberately ABSENT from default-config so a
;; :global source in a timeout record always means the user configured it.
(s/def ::deadline-ms pos-int?)
(s/def ::interval-ms pos-int?)
(s/def ::timing (s/keys :opt-un [::deadline-ms ::interval-ms]))

;; Interface sub-config. ::interfaces is the LOADED (post-expansion) shape:
;; in the user's file an entry value may also be the :default sentinel
;; (= include the builtin def of that name verbatim), which load-config
;; expands via expand-interface-defaults before anything consumes the map
;; (sl-g2lj).
(s/def ::type keyword?)
(s/def ::adapter keyword?)
;; :shared-impl? true means a single adapter impl is shared across all
;; subject-keyed entries (:cap/<iface>.alice, :cap/<iface>.bob both
;; reference the same impl). Default false: each subject gets its own
;; impl (browser pattern). Used for interfaces where one resource per
;; scenario suffices, e.g. SMS Twilio account, future shared queues.
(s/def ::shared-impl? boolean?)
;; Per-interface :config is an OPEN map — adapter keys (:headless,
;; :adapter-opts, :webdriver-url, ...) pass through untyped. :base-url is the
;; environmental home of a named location's HOST half (sl-3jr4); the semantic
;; PATH half lives on the intent's :location.
(s/def :interface/base-url string?)
(s/def :interface/config (s/keys :opt-un [:interface/base-url]))
(s/def ::interface-def (s/keys :req-un [::type ::adapter]
                               :opt-un [:interface/config ::shared-impl?
                                        ::timing]))
(s/def ::interfaces (s/map-of keyword? ::interface-def))

;; Costumes sub-config (sl-1206): a costume declares the interface INSTANCE
;; it belongs to — committed config, so wrong-world :wears fails statically
;; (stepengine.costume-lint) instead of only at the runtime guard (sl-xbm).
(s/def ::interface keyword?)
(s/def ::costume-def (s/keys :req-un [::interface]))
(s/def ::costumes (s/map-of keyword? ::costume-def))

;; Enforcement sub-config (sl-6wh9: renamed from :svo — that word names the
;; triple on stepdef metadata; this block holds nothing but levels). One
;; closed level set for every kind; the :strict alias retired with the rename.
(s/def ::level #{:warn :error :off})
(s/def ::all ::level)
(s/def ::unknown-subject ::level)
(s/def ::unknown-verb ::level)
(s/def ::unknown-interface ::level)
(s/def ::unknown-object ::level)
;; sl-6wh9: a matched user defstep with no :svo metadata at all — nothing
;; ran through the type system for that step.
(s/def ::untyped-stepdef ::level)
;; sl-6wh9: the knob for a '{'-prefixed raw locator in an intent slot. The
;; key and its conditional default ship here; the issue itself grooms at
;; sl-u2r0 (today the check keys on :unknown-object :error).
(s/def ::raw-locator ::level)
(s/def ::enforcement (s/keys :opt-un [::all ::unknown-subject ::unknown-verb
                                      ::unknown-interface ::unknown-object
                                      ::untyped-stepdef ::raw-locator]))

;; Glossaries sub-config
(s/def ::subjects (s/nilable string?))
;; sl-67dj: a LIST of file paths — each file's own :type is its mount key
;; (file-authoritative; the old type-keyed map restated what the file already
;; declared). :kind sequential? (sl-a7sx, Warden W33): a list literal loads
;; exactly like a vector — the spec matches the shipped loader, and the
;; shape is enforced by normalize's :config/invalid-glossaries arm.
(s/def ::verbs (s/nilable (s/coll-of string? :kind sequential?)))
(s/def ::intents (s/nilable string?))
(s/def ::glossaries (s/nilable (s/keys :opt-un [::subjects ::verbs ::intents])))

;; Top-level config
(s/def ::config
  (s/keys :opt-un [::parser ::runner ::glossaries ::interfaces ::costumes
                   ::enforcement ::timing]))

;; load-config-safe result
(s/def ::status #{:ok :error})
(s/def ::message string?)
(s/def ::path string?)
(s/def ::config-ok (s/keys :req-un [::status ::config]))
(s/def ::config-error (s/keys :req-un [::status ::type ::message]
                              :opt-un [::path]))
(s/def ::config-result (s/or :ok ::config-ok :error ::config-error))

;; Config lint (sl-hlkz) — warning shape for silently-ignored top-level keys
(s/def ::key keyword?)
(s/def ::suggested-path (s/coll-of keyword? :kind vector? :min-count 1))
(s/def ::lint-warning (s/keys :req-un [::type ::key ::message]
                              :opt-un [::suggested-path]))
(s/def ::lint-warnings (s/coll-of ::lint-warning :kind vector?))

;; -----------------------------------------------------------------------------
;; Default Configuration
;; -----------------------------------------------------------------------------

;; Known interface types (verb vocabulary domains)
;; NOTE: closed set — gates :unknown-interface SVO validation. Add new
;; interface types here when introducing them (e.g., :cli, :queue).
(def known-interface-types
  "Set of known interface types that have verb glossaries."
  #{:web :api :sms :email})

(def enforcement-kinds
  "The enforcement kinds, in display order. `:all` is the umbrella, not a
   kind (sl-6wh9)."
  [:unknown-subject :unknown-verb :unknown-interface :unknown-object
   :untyped-stepdef :raw-locator])

(def default-config
  "Built-in default configuration.

   The :enforcement block is the built-in level of every kind (sl-6wh9).
   `load-config` always carries the block through — see `resolve-enforcement`
   for how a user's named keys, the :all umbrella, and the conditional
   defaults combine."
  {:parser {:dialect "en"}
   :runner {:step-paths ["steps/"]
            :features ["features/"]
            :allow-pending? false
            :macros {:enabled? false}}
   ;; Glossary paths — nil means use framework defaults only
   :glossaries nil
   ;; Interface definitions — name -> {:type :adapter :config}. Mounted only
   ;; when the user's config has NO :interfaces key: a user :interfaces map
   ;; replaces this set WHOLESALE (sl-g2lj, Design A); a per-entry :default
   ;; re-includes a builtin def from here verbatim (expand-interface-defaults).
   :interfaces {:web {:type :web
                      :adapter :etaoin
                      :config {}}}
   ;; Enforcement levels. :unknown-object's and :raw-locator's :off here are
   ;; the NO-INTENTS defaults only: load-config flips the effective default
   ;; to :warn when an :intents glossary is configured and the user set
   ;; neither the key nor :all (sl-femd, resolve-enforcement).
   :enforcement {:unknown-subject :warn
                 :unknown-verb :warn
                 :unknown-interface :error
                 :unknown-object :off
                 :untyped-stepdef :warn
                 :raw-locator :off}})

;; -----------------------------------------------------------------------------
;; Config Loading
;; -----------------------------------------------------------------------------

(defn- deep-merge
  "Deep merge maps. Later values override earlier ones.
   For non-map values, later wins."
  [& maps]
  (reduce (fn [acc m]
            (reduce-kv (fn [a k v]
                         (if (and (map? (get a k)) (map? v))
                           (assoc a k (deep-merge (get a k) v))
                           (assoc a k v)))
                       acc
                       m))
          {}
          maps))

(defn- read-config-file
  "Read and parse an EDN config file.
   Returns {:status :ok :config {...}} or {:status :error ...}"
  [path]
  (try
    (let [content (io/slurp-utf8 path)]
      (if (:status content)
        ;; slurp-utf8 returned an error
        content
        {:status :ok
         :config (edn/read-string content)}))
    (catch Exception e
      {:status :error
       :type :config/parse-failed
       :message (str "Failed to parse config: " (ex-message e))
       :path path})))

(defn find-default-config
  "Find the discovered config file `sl/shiftlefter.edn` if it exists.

   Resolved against `shiftlefter.paths/user-cwd` (the project the user ran `sl`
   from), NOT the raw process CWD — which is the install dir when `sl` runs from
   PATH. Returns the resolved absolute path string or nil. Public so the runner
   can use it to locate sibling files (notably setup.clj, see runner/setup.clj)."
  []
  (let [context (project-context/resolve)]
    (when (= :discovered (:config-source context))
      (:config-path context))))

(defn resolve-config-path
  "Resolve which shiftlefter.edn would actually be loaded for the given opts.

   Mirrors load-config's precedence:
     1. Explicit --config path
     2. Discovered sl/shiftlefter.edn
     3. nil (built-in defaults, no config file)"
  [opts]
  (:config-path (or (:project-context opts)
                    (project-context/resolve
                     (cond-> {}
                       (:config-path opts) (assoc :config-path (:config-path opts)))))))

;; -----------------------------------------------------------------------------
;; Conventional glossary defaults (sl-fcjm / sl-hivf item 1)
;; -----------------------------------------------------------------------------

(defn- probe-conventional-glossaries
  "Existence-probed conventional glossary defaults, resolved against the
   context's :config-root (sl/glossary/... in the discovered layout):

   - glossary/subjects.edn
   - glossary/verbs/*.edn (every EDN file in the directory — sl-67dj: each
     file's own :type is its mount key, so discovery no longer enumerates a
     closed type set; type identifiers are an open namespaced space)
   - glossary/intents/ (directory)

   Returns a :glossaries-shaped map of the RELATIVE paths that exist (verb
   paths sorted for determinism), or nil when none do. Whole-map semantics
   (Chair ruling 2026-08-03): this runs only when the user's config has no
   :glossaries key at all — any explicit :glossaries value, including nil,
   is used as written."
  [context]
  (let [root (:config-root context)
        probe (fn [rel] (when (fs/exists? (fs/path root rel)) rel))
        subjects (probe "glossary/subjects.edn")
        verbs-dir (fs/path root "glossary" "verbs")
        verbs (when (fs/directory? verbs-dir)
                (->> (fs/glob verbs-dir "*.edn")
                     (mapv #(str "glossary/verbs/" (fs/file-name %)))
                     sort
                     vec))
        intents (when (fs/directory? (fs/path root "glossary" "intents"))
                  "glossary/intents")]
    (when (or subjects (seq verbs) intents)
      (cond-> {}
        subjects (assoc :subjects subjects)
        (seq verbs) (assoc :verbs verbs)
        intents (assoc :intents intents)))))

(def ^:private intents-conditional-kinds
  "Kinds whose built-in default is CONDITIONAL on an :intents glossary
   (sl-femd, extended to :raw-locator at sl-6wh9): :warn when one is
   configured — there is something to validate against — and :off
   otherwise (default-config's static value)."
  #{:unknown-object :raw-locator})

(def ^:private umbrella-exempt-kinds
  "Kinds the :all umbrella never touches (sl-6wh9 ruling #1/#6):
   :unknown-interface is infrastructure — nothing can be provisioned for an
   unknown instance — so it stays :error under any umbrella. A NAMED key
   still sets it (the spec is unchanged)."
  #{:unknown-interface})

(defn- level? [v] (s/valid? ::level v))

(defn- resolve-enforcement
  "The effective :enforcement block (sl-6wh9). Per kind, first hit wins:
   the user's named key → the :all umbrella (exempt kinds skip it; an
   invalid :all value is ignored here so `normalize` can name it) → the
   conditional default (:warn for the intents-conditional kinds when an
   :intents glossary is configured, explicit or conventionally probed —
   evaluated on the MERGED config) → default-config's built-in level. The
   user's :all is carried through verbatim so `normalize` validates it."
  [merged user-enforcement]
  (let [user-enf (when (map? user-enforcement) user-enforcement)
        all (:all user-enf)
        intents? (some? (get-in merged [:glossaries :intents]))
        effective (into {}
                        (map (fn [k]
                               [k (or (get user-enf k)
                                      (when (and (level? all)
                                                 (not (umbrella-exempt-kinds k)))
                                        all)
                                      (when (and intents? (intents-conditional-kinds k))
                                        :warn)
                                      (get-in default-config [:enforcement k]))]))
                        enforcement-kinds)]
    (assoc merged :enforcement
           (cond-> effective
             (contains? user-enf :all) (assoc :all all)))))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn expand-interface-defaults
  "Expand `:default` sentinel entries in a user `:interfaces` map (sl-g2lj,
   Design A). A user `:interfaces` map is the COMPLETE interface set —
   `load-config` replaces the builtin defaults with it wholesale — and an
   entry value of `:default` re-includes the shipped builtin def of that
   NAME verbatim. No overrides travel through the sentinel, and a full-map
   entry is used as-is under any name (including builtin names): user
   definitions and builtin defs are NEVER merged. A `:default` under a name
   with no builtin is left in place for `normalize` to reject
   (`:config/unknown-default-interface` — planning error, never
   silent-empty). Non-map input (including nil = explicitly zero
   interfaces) is returned as written. Public for direct tests; production
   callers go through `load-config`."
  [user-interfaces]
  (if (map? user-interfaces)
    (reduce-kv (fn [m iface-name entry]
                 (assoc m iface-name
                        (if (= :default entry)
                          (get-in default-config [:interfaces iface-name] :default)
                          entry)))
               {}
               user-interfaces)
    user-interfaces))

(defn load-config
  "Load configuration with precedence.

   Options:
   - :config-path - explicit config file path (highest precedence)

   Precedence:
   1. Explicit --config path
   2. Discovered sl/shiftlefter.edn (if exists)
   3. Built-in defaults

   Returns merged config map.

   Enforcement invariant (sl-6wh9): the result ALWAYS carries a complete
   :enforcement block — one level per kind in `enforcement-kinds` — whether
   or not the user's config had the key. There is no mode: presence means
   nothing, absence means the defaults. `resolve-enforcement` combines the
   user's named keys, the :all umbrella, and the conditional defaults.

   Conditional default (sl-femd): :unknown-object and :raw-locator default
   :warn when an :intents glossary is configured (explicit or conventionally
   probed) and :off otherwise; an explicit user value or :all always wins.
   This is the ONE place the effective default is computed — downstream
   :off fallbacks (stepengine.bind, svo.validate) are nothing-configured
   safety nets, not defaults.

   Interface invariant (sl-g2lj, Design A): the result's :interfaces is the
   builtin default set iff the USER's config has no :interfaces key. When the
   key is present the user's map is the COMPLETE set (wholesale replace —
   the explicit-:glossaries precedent): entry value :default re-includes the
   builtin def of that name verbatim (expand-interface-defaults; no overrides
   through the sentinel), a full-map entry is used as-is under any name
   (never merged with a builtin), and an explicit nil means zero interfaces
   (normalized to {} at load — sl-a7sx — so nil and {} are one shape
   downstream).

   Throws on:
   - Explicit config path doesn't exist
   - Config file parse error"
  ([] (load-config {}))
  ([opts]
   (let [context (or (:project-context opts)
                     (project-context/resolve
                      (cond-> {}
                        (:config-path opts) (assoc :config-path (:config-path opts)))))
         config-path (:config-path context)
         diagnostic (first (:diagnostics context))]
     (cond
       (= :config/not-found (:type diagnostic))
       (throw (ex-info (:message diagnostic)
                       {:type :config/not-found
                        :path (:path diagnostic)}))

       (= :project-context/ambiguous-config (:type diagnostic))
       (throw (ex-info (:message diagnostic)
                       {:type (:type diagnostic)
                        :paths (:paths diagnostic)}))

       config-path
       (let [result (read-config-file config-path)]
         (if (= :ok (:status result))
           (let [user-config (:config result)
                 merged (deep-merge default-config user-config)
                 ;; Conventional glossary defaults (sl-fcjm): only when the
                 ;; user's config has NO :glossaries key (contains?-on-
                 ;; user-config: an explicit nil is "empty on purpose").
                 ;; Real-config path only; the defaults context never probes.
                 merged (if (contains? user-config :glossaries)
                          merged
                          (assoc merged :glossaries
                                 (probe-conventional-glossaries context)))
                 ;; User :interfaces replaces the builtin set WHOLESALE
                 ;; (sl-g2lj, Design A — the :glossaries whole-key
                 ;; pattern): presence of the key makes the user's map the
                 ;; complete set. :default entries expand to the builtin defs
                 ;; verbatim; an explicit nil means zero interfaces and is
                 ;; normalized to {} HERE (sl-a7sx, Warden W-B1) so every
                 ;; downstream consumer sees ONE zero-interfaces shape —
                 ;; the suite-lint truthiness gate (stepengine.compile) reads
                 ;; {} as "present, complete, empty" and nil as "exempt"; the
                 ;; projection's zero-interfaces WARN (sl-a55s) keys on it.
                 merged (if (contains? user-config :interfaces)
                          (assoc merged :interfaces
                                 (expand-interface-defaults
                                  (let [ifaces (:interfaces user-config)]
                                    (if (nil? ifaces) {} ifaces))))
                          merged)]
             ;; The effective enforcement block is computed from the USER's
             ;; block (not the deep-merge, whose per-key merge would let a
             ;; named key shadow :all incorrectly) — sl-6wh9.
             (resolve-enforcement merged (:enforcement user-config)))
           (throw (ex-info (:message result)
                           {:type (:type result)
                            :path config-path}))))

       ;; No config file: the built-in defaults, enforcement block included
       ;; (sl-6wh9 — the no-config run is an ordinary run).
       :else
       (resolve-enforcement default-config nil)))))

(defn load-config-safe
  "Like load-config but returns error map instead of throwing.

   Returns:
   - {:status :ok :config {...}}
   - {:status :error :type ... :message ...}"
  ([] (load-config-safe {}))
  ([opts]
   (try
     {:status :ok
      :config (load-config opts)}
     (catch Exception e
       {:status :error
        :type (or (:type (ex-data e)) :config/error)
        :message (ex-message e)
        :path (:path (ex-data e))}))))

(defn get-dialect
  "Get the parser dialect from config."
  [config]
  (get-in config [:parser :dialect] "en"))

(defn get-step-paths
  "Get the step paths from config."
  [config]
  (get-in config [:runner :step-paths] ["steps/"]))

(defn get-features
  "Get the feature corpus paths from config (sl-fcjm). Config-root-relative;
   the bare-`sl run` fallback in the classic pipeline. Default [\"features/\"]
   (sl/features/ in the discovered layout)."
  [config]
  (get-in config [:runner :features] ["features/"]))

(defn allow-pending?
  "Check if pending steps are allowed (don't cause failure)."
  [config]
  (get-in config [:runner :allow-pending?] false))

(defn macros-enabled?
  "Check if macros are enabled in config."
  [config]
  (get-in config [:runner :macros :enabled?] false))

(defn provisioning-mode
  "Return the capability-provisioning strategy: `:eager` (scoped-eager,
   default) or `:lazy` (per-step on first touch). Drives the eager
   phase in `stepengine.exec/execute-scenario`. See sl-aa5."
  [config]
  (get-in config [:runner :provisioning] :eager))

(defn junit-xml-path
  "Return the config-declared JUnit-XML output path, or nil (off). Config
   mirror for --junit-xml (sl-40to); the flag wins when both are set. Paths
   here are resolved against :config-root by resolve-config-declared-paths."
  [config]
  (get-in config [:runner :report :junit-xml]))

(defn html-path
  "Return the config-declared HTML report output path, or nil (off). Config
   mirror for --html (sl-muq9); the flag wins when both are set. Paths here
   are resolved against :config-root by resolve-config-declared-paths."
  [config]
  (get-in config [:runner :report :html]))

(defn max-parallel
  "Return the config-declared scenario parallelism bound, default 1
   (sequential). Config mirror for --max-parallel (sl-q9wp); the flag wins
   when both are set (runner/core resolve-max-parallel)."
  [config]
  (get-in config [:runner :max-parallel] 1))

(defn halt-policy
  "The config-declared halt policy as a tri-state (sl-78u4, Warden W3):
   `false` (default — grind on), `:group` (halt the group whose scenario
   went dark; siblings run), or `:run` (halt the whole invocation; groups
   not yet started are skipped entirely). `true` reads as `:run` — every
   pre-tri-state doc sentence stays true. Config-only, no CLI flag."
  [config]
  (let [v (get-in config [:runner :halt-on-observation-failure] false)]
    (cond
      (true? v) :run
      (contains? #{:group :run} v) v
      :else false)))

(defn halt-on-observation-failure?
  "The engine's boolean (sl-kowi, Ruling 4): true when ANY halt scope is
   on. Threaded to the engine as the :halt-on-observation-failure exec-opt;
   the SCOPE is the runner's business (`halt-policy` — under :run the
   runner threads one shared gate atom across the setup groups)."
  [config]
  (boolean (halt-policy config)))

(defn capture-policy
  "Return the config-declared capture policy map ({kind policy}), default {}
   (every kind :never). Config mirror for --capture (sl-3q9r); the flag wins
   AS A WHOLE VALUE when both are set (runner/core resolve-capture)."
  [config]
  (get-in config [:runner :capture] {}))

(defn parse-capture-spec
  "Parse a --capture flag value: \"screenshot=on-failure,console=never\" ->
   {:screenshot :on-failure :console :never}. A leading ':' on either side
   is tolerated (agents write keywords). Returns nil on ANY malformed
   segment — empty segment, missing '=', empty kind, unknown policy word —
   so the tools.cli :validate pair rejects the whole flag (usage error,
   exit 1). Unknown KINDS are legal here (the set is open,
   adapter-contributed); the unknown-kind warn happens at capture
   resolution against the live registry."
  [s]
  (let [segments (str/split (str s) #",")
        parse-segment (fn [seg]
                        (let [[k v :as parts] (str/split seg #"=" -1)
                              strip #(str/replace % #"^:" "")]
                          (when (and (= 2 (count parts))
                                     (seq (strip k)) (seq (strip v)))
                            (let [policy (keyword (strip v))]
                              (when (s/valid? ::capture-value policy)
                                [(keyword (strip k)) policy])))))
        pairs (map parse-segment segments)]
    (when (and (seq pairs) (every? some? pairs))
      (into {} pairs))))

(s/fdef parse-capture-spec
  :args (s/cat :s (s/nilable string?))
  :ret (s/nilable ::capture))

(defn valid-capture-spec?
  "tools.cli :validate predicate for a parsed --capture value."
  [m]
  (s/valid? ::capture m))

(defn get-macro-registry-paths
  "Get macro registry paths from config."
  [config]
  (get-in config [:runner :macros :registry-paths] []))

;; -----------------------------------------------------------------------------
;; Config Lint (sl-hlkz)
;; -----------------------------------------------------------------------------

(def known-top-level-keys
  "Top-level keys config consumers actually read: the `::config` spec set.
   Anything else at the top level is silently ignored by every reader —
   exactly the defect family sl-hlkz warns about (example 03 carried a
   top-level :step-paths for its whole life and only worked by coinciding
   with the default). :webdriver/:webdriver-url are deliberately NOT here
   (sl-7kct): `normalize` validates them on the projection path but nothing
   consumes the result, so to `sl run` they are ignored keys like any other."
  #{:parser :runner :glossaries :interfaces :costumes :enforcement :timing})

(def ^:private misplaced-key-paths
  ;; Known NESTED keys users plausibly write at the top level, mapped to the
  ;; path the runner actually reads. Drives the did-you-mean suggestion;
  ;; top-level keys absent here get the plain unknown-key warning.
  {:step-paths        [:runner :step-paths]
   :features          [:runner :features]
   :allow-pending?    [:runner :allow-pending?]
   :macros            [:runner :macros]
   :provisioning      [:runner :provisioning]
   :report            [:runner :report]
   :max-parallel      [:runner :max-parallel]
   :halt-on-observation-failure [:runner :halt-on-observation-failure]
   :seed              [:runner :seed]
   :capture           [:runner :capture]
   :ref-url           [:runner :ref-url]
   :junit-xml         [:runner :report :junit-xml]
   :html              [:runner :report :html]
   :dialect           [:parser :dialect]
   :subjects          [:glossaries :subjects]
   :verbs             [:glossaries :verbs]
   :intents           [:glossaries :intents]
   :deadline-ms       [:timing :deadline-ms]
   :interval-ms       [:timing :interval-ms]
   :unknown-subject   [:enforcement :unknown-subject]
   :unknown-verb      [:enforcement :unknown-verb]
   :unknown-interface [:enforcement :unknown-interface]
   :unknown-object    [:enforcement :unknown-object]
   :untyped-stepdef   [:enforcement :untyped-stepdef]
   :raw-locator       [:enforcement :raw-locator]
   ;; sl-6wh9: the retired block name. No alias — an old :svo block is
   ;; ignored and this did-you-mean names the rename.
   :svo               [:enforcement]})

(defn lint-config
  "Lint a loaded (merged) config map for silently-ignored TOP-LEVEL keys
   (sl-hlkz). Returns a sorted vector of `::lint-warning` maps — empty when
   clean.

   Works on the MERGED map because `deep-merge` only contributes known keys
   from `default-config`: any unknown/misplaced top-level key in the result
   came from the user's file. WARNINGS ONLY, never errors — a newer config
   running on an older version must still work (forward compat, bead
   ruling). Top-level only by scope: nested maps (:interfaces entries,
   adapter :config blocks) are open by design — EXCEPT :timing, whose
   inner keys are closed and validated as ERRORS at both rungs (sl-d3cz:
   an unknown key there means the timing config is silently dead — see
   `timing-problem`). Lint BEFORE `normalize`,
   whose synthetic :errors key would otherwise self-trigger."
  [config]
  (-> (->> (keys config)
           (remove known-top-level-keys)
           sort
           (mapv (fn [k]
                   (if-let [path (get misplaced-key-paths k)]
                     {:type :config/misplaced-key
                      :key k
                      :suggested-path path
                      :message (str "config key " k " is not read at the top level"
                                    " and was ignored — did you mean "
                                    (pr-str path) "?")}
                     {:type :config/unknown-key
                      :key k
                      :message (str "unknown top-level config key " k
                                    " was ignored")}))))
      ;; sl-xjx0 (Warden W9): the REVERSE direction of misplaced-key-paths —
      ;; the release's two newest TOP-LEVEL keys, plausibly nested under
      ;; :runner where they silently do nothing. Only these two: the rest of
      ;; :runner's contents are its own vocabulary.
      (into (for [k [:timing :costumes]
                  :when (contains? (:runner config) k)]
              {:type :config/misplaced-key
               :key k
               :found-path [:runner k]
               :suggested-path [k]
               :message (str "config key " k " is read at the TOP level, not"
                             " under :runner — [:runner " k "] was ignored")}))
      ;; sl-q4cb (Warden W36): the :default SENTINEL is an entry VALUE
      ;; ({:web :default}); as a KEY inside a full interface map it reads
      ;; like the merge form the g2lj design deliberately excluded, and
      ;; today it is honored by being ignored. Name it.
      (into (for [[iface-name idef] (when (map? (:interfaces config)) (:interfaces config))
                  :when (and (map? idef) (contains? idef :default))]
              {:type :config/misplaced-key
               :key :default
               :found-path [:interfaces iface-name :default]
               :message (str "config key :default inside [:interfaces " iface-name
                             "] was ignored — :default is an entry VALUE"
                             " ({:interfaces {" iface-name " :default}} re-includes the"
                             " builtin verbatim); a full map is used as-is and is never"
                             " merged with the builtin")}))
      ;; sl-xjx0 (Warden W14, cheap warn — the real adapter-awareness is
      ;; sl-bbqb): costume attach is hardwired etaoin this release; a costume
      ;; declared for an interface whose adapter is anything else passes
      ;; every lint and then hands an etaoin CostumeBrowser to that adapter
      ;; at runtime. Warn at declaration time, run+projection channels alike.
      (into (for [[cname cdef] (when (map? (:costumes config)) (:costumes config))
                  :when (map? cdef)   ; malformed entries are the validator's job
                  :let [iface (:interface cdef)
                        adapter (get-in config [:interfaces iface :adapter])]
                  :when (and adapter (not= :etaoin adapter))]
              {:type :config/costume-adapter-unsupported
               :key cname
               :message (str "costume " (pr-str cname) " is declared for"
                             " interface " (pr-str iface) " whose adapter is "
                             (pr-str adapter) " — costume attach is"
                             " etaoin-only this release: wearing it would hand"
                             " an etaoin Chrome session to that adapter at"
                             " runtime (adapter-aware costumes are sl-bbqb)")}))))

(s/fdef lint-config
  :args (s/cat :config map?)
  :ret ::lint-warnings)

;; -----------------------------------------------------------------------------
;; Config Normalization / Validation
;; -----------------------------------------------------------------------------

(defn- validate-macro-config
  "Validate macro configuration.
   Returns nil if valid, or error map if invalid."
  [config]
  (let [macros (get-in config [:runner :macros])
        enabled? (:enabled? macros)]
    (when enabled?
      (let [paths (:registry-paths macros)]
        (when (or (nil? paths) (empty? paths))
          {:type :macro/config-missing-registry-paths
           :message "Macros enabled but :registry-paths is missing or empty"})))))

(def known-timing-keys
  "The whole :timing inner vocabulary (sl-99u2). Closed set, validated as
   an ERROR at both rungs (sl-d3cz): ::timing is open s/keys, so a typo'd
   inner key would otherwise pass while the timing config sat silently
   dead — {:timing {:interval 250}} ran on the framework default."
  #{:deadline-ms :interval-ms})

(defn- timing-problem
  "Why a :timing map is invalid, or nil when it's fine. Checks the shape
   (::timing) and then rejects unknown inner keys with a did-you-mean."
  [timing]
  (cond
    (not (s/valid? ::timing timing))
    (str "must be a map with positive-integer"
         " :deadline-ms / :interval-ms, got: " (pr-str timing))

    (seq (remove known-timing-keys (keys timing)))
    (let [unknown (vec (remove known-timing-keys (keys timing)))
          suggestion (text/suggest-similar
                      (first unknown) (vec known-timing-keys) 3)]
      (str "has unknown key" (when (next unknown) "s") " " (pr-str unknown)
           " — known: " (pr-str (vec (sort known-timing-keys)))
           (when suggestion
             (str "; did you mean " (pr-str suggestion) "?"))))))

(defn- validate-timing-config
  "Validate the top-level :timing map (sl-99u2, global rung; inner keys
   closed at sl-d3cz). Returns nil if valid or absent, error map if invalid."
  [config]
  (let [timing (:timing config)]
    (when-let [problem (and (some? timing) (timing-problem timing))]
      {:type :config/invalid-timing
       :message (str ":timing " problem)
       :data {:timing timing}})))

(defn- validate-single-interface
  "Validate a single interface definition.
   Returns nil if valid, or error map if invalid."
  [interface-name interface-def]
  (cond
    ;; sl-g2lj RIDER: the sl/ namespace is reserved for builtin interface
    ;; NAMES — rejected now so future builtin aliasing (sl/web, the bundle
    ;; bead sl-interface-bundles-wj9c) is not foreclosed. Checked first: it
    ;; applies to :default-sentinel entries too.
    (and (keyword? interface-name) (= "sl" (namespace interface-name)))
    {:type :config/reserved-interface-name
     :message (str "Interface " (pr-str interface-name) " uses the sl/"
                   " namespace, which is reserved for builtin interfaces")
     :data {:interface interface-name
            :definition interface-def}}

    ;; sl-g2lj: an unexpanded :default sentinel — load-config expands every
    ;; sentinel that names a builtin, so one surviving to validation names
    ;; none. Planning error, never silent-empty.
    (= :default interface-def)
    {:type :config/unknown-default-interface
     :message (str "Interface " (pr-str interface-name) " declares :default,"
                   " but there is no builtin interface of that name —"
                   " builtins: "
                   (pr-str (vec (sort (keys (:interfaces default-config))))))
     :data {:interface interface-name
            :builtins (vec (sort (keys (:interfaces default-config))))}}

    ;; sl-g2lj: an entry is a definition map or the :default sentinel,
    ;; nothing else.
    (not (map? interface-def))
    {:type :config/invalid-interface
     :message (str "Interface " (pr-str interface-name) " must be a"
                   " definition map or :default (include the builtin def"
                   " verbatim), got: " (pr-str interface-def))
     :data {:interface interface-name
            :definition interface-def}}

    ;; Must have :type
    (nil? (:type interface-def))
    {:type :config/invalid-interface
     :message (str "Interface :" (name interface-name) " missing required :type key")
     :data {:interface interface-name
            :definition interface-def}}

    ;; Must have :adapter
    (nil? (:adapter interface-def))
    {:type :config/invalid-interface
     :message (str "Interface :" (name interface-name) " missing required :adapter key")
     :data {:interface interface-name
            :definition interface-def}}

    ;; sl-g2lj RIDER: sl/-namespaced TYPES are reserved for builtins —
    ;; ordered BEFORE the closed-set check so the reservation keeps its own
    ;; message when the type space opens (sl-interface-bundles-wj9c).
    (and (keyword? (:type interface-def))
         (= "sl" (namespace (:type interface-def))))
    {:type :config/reserved-interface-type
     :message (str "Interface " (pr-str interface-name) " declares :type "
                   (pr-str (:type interface-def)) " — the sl/ namespace is"
                   " reserved for builtin interface types")
     :data {:interface interface-name
            :type (:type interface-def)}}

    ;; :type must be known
    (not (contains? known-interface-types (:type interface-def)))
    {:type :config/unknown-interface-type
     :message (str "Interface :" (name interface-name) " has unknown type :"
                   (name (:type interface-def))
                   ". Known types: " (pr-str known-interface-types))
     :data {:interface interface-name
            :type (:type interface-def)
            :known-types known-interface-types}}

    ;; :timing, when present, must be a CLOSED map of pos-int deadline/
    ;; interval (sl-99u2 — the per-interface rung; inner keys closed at
    ;; sl-d3cz, same rule as the global rung)
    (let [timing (:timing interface-def)]
      (and (some? timing) (some? (timing-problem timing))))
    {:type :config/invalid-interface
     :message (str "Interface :" (name interface-name)
                   " :timing " (timing-problem (:timing interface-def)))
     :data {:interface interface-name
            :definition interface-def}}

    ;; :base-url, when present, must be a non-blank string (sl-3jr4)
    (let [base-url (get-in interface-def [:config :base-url])]
      (and (some? base-url)
           (or (not (string? base-url)) (str/blank? base-url))))
    {:type :config/invalid-interface
     :message (str "Interface :" (name interface-name)
                   " :config :base-url must be a non-blank string, got: "
                   (pr-str (get-in interface-def [:config :base-url])))
     :data {:interface interface-name
            :definition interface-def}}

    :else nil))

(defn- validate-interfaces-config
  "Validate all interface definitions.
   Returns a seq of errors (may be empty/nil).

   sl-g2lj: :interfaces presence is meaningful (wholesale replace), so a
   present-but-non-map value is an ERROR rather than silently ignored —
   except nil, the explicit zero-interfaces value. load-config normalizes
   that nil to {} (sl-a7sx), so the nil arm here only serves direct
   `normalize` callers handing in a raw map."
  [config]
  (let [interfaces (:interfaces config)]
    (cond
      (nil? interfaces) nil

      (not (map? interfaces))
      [{:type :config/invalid-interfaces
        :message (str ":interfaces must be a map of interface name ->"
                      " definition map (or :default), got: "
                      (pr-str interfaces))
        :data {:definition interfaces}}]

      :else
      (keep (fn [[name def]]
              (validate-single-interface name def))
            interfaces))))

(defn- validate-single-costume
  "Validate one :costumes entry (sl-1206). Returns error map or nil."
  [interfaces costume-name costume-def]
  (cond
    (not (s/valid? ::costume-def costume-def))
    {:type :config/invalid-costume
     :message (str "Costume :" (name costume-name)
                   " must be a map with a keyword :interface naming the"
                   " interface instance it belongs to, got: "
                   (pr-str costume-def))
     :data {:costume costume-name
            :definition costume-def}}

    (and (map? interfaces)
         (not (contains? interfaces (:interface costume-def))))
    (let [declared (:interface costume-def)
          suggestion (text/suggest-similar declared (keys interfaces) 2)]
      {:type :config/unknown-costume-interface
       :message (str "Costume :" (name costume-name) " declares :interface "
                     (pr-str declared) ", which is not a configured interface"
                     " instance (known: " (pr-str (vec (sort (keys interfaces))))
                     ")"
                     (when suggestion (str " — did you mean " (pr-str suggestion) "?")))
       :data (cond-> {:costume costume-name
                      :declared-interface declared
                      :known-interfaces (vec (sort (keys interfaces)))}
               suggestion (assoc :suggestion suggestion))})

    :else nil))

(defn validate-costumes-config
  "Validate the :costumes declaration map (sl-1206).
   Returns a seq of errors (nil/empty when clean): :config/invalid-costume
   for a malformed entry, :config/unknown-costume-interface (with
   did-you-mean) for an entry naming no configured instance.

   History: sl-st01 (Warden W2) made this public and wired it into
   runner.core/load-config-stage directly, because `sl run` never reached
   `normalize` and a malformed declaration silently disabled costume
   enforcement. Since sl-74w8 load-config-stage runs `normalize` itself,
   which subsumes that direct call — every normalize validator is now
   reachable from both channels. Stays public for direct tests."
  [config]
  (let [costumes (:costumes config)]
    (cond
      (nil? costumes) nil
      (not (map? costumes))
      [{:type :config/invalid-costume
        :message (str ":costumes must be a map of costume name -> "
                      "{:interface <instance>}, got: " (pr-str costumes))
        :data {:definition costumes}}]
      :else
      (keep (fn [[name def]]
              (validate-single-costume (:interfaces config) name def))
            costumes))))

(def enforcement-levels
  "The framework-owned CLOSED set of enforcement levels (sl-3bqv, doctrine
   bullet 9: a closed set has no leniency knob — it errors, it never
   ignores). The :strict alias retired with the :enforcement rename
   (sl-6wh9)."
  #{:warn :error :off})

(def ^:private enforcement-keys
  (into [:all] enforcement-kinds))

(defn- validate-enforcement-config
  "Validate the :enforcement LEVELS at load (sl-3bqv). The enum specs sat
   under a ::config spec nothing gated, so a typo'd level (:warm) flowed
   through compute-severity as a non-:error value — silently non-blocking.
   Every non-nil :enforcement map's known keys (the kinds and :all) must
   hold a level from `enforcement-levels`; anything else is a HARD planning
   error with a did-you-mean over the enum. Unknown keys inside the block
   stay lint-config's business (top-level scope) — this validator is about
   the VALUES. Returns a seq of errors (nil when clean)."
  [config]
  (let [enf (:enforcement config)
        allowed (vec (sort enforcement-levels))]
    (cond
      (nil? enf) nil

      (not (map? enf))
      [{:type :config/invalid-enforcement-level
        :message (str ":enforcement must be a map of enforcement levels, got: "
                      (pr-str enf))
        :data {:definition enf}}]

      :else
      (keep (fn [k]
              (let [level (get enf k)]
                (when (and (contains? enf k) (not (contains? enforcement-levels level)))
                  (let [suggestion (text/suggest-similar level allowed 2)]
                    {:type :config/invalid-enforcement-level
                     :message (str "[:enforcement " k "] must be one of "
                                   (pr-str allowed)
                                   ", got: " (pr-str level)
                                   (when suggestion
                                     (str " — did you mean " (pr-str suggestion) "?")))
                     :data (cond-> {:key k :level level :allowed allowed}
                             suggestion (assoc :suggestion suggestion))}))))
            enforcement-keys))))

(def ^:private glossaries-shape-hint
  "The one sentence every :config/invalid-glossaries error carries — names
   the path-LIST form so the retired type-keyed map is actionable."
  (str ":glossaries is a map — :subjects and :intents are single file paths,"
       " :verbs is a LIST of verb-glossary file paths whose own :type is each"
       " file's mount key (since 0.5.6; the type-keyed {:web \"path\"} map is"
       " retired), e.g. {:verbs [\"glossary/verbs/web.edn\"]}"))

(defn- invalid-glossaries-error [what found]
  {:type :config/invalid-glossaries
   :message (str what " — " glossaries-shape-hint "; got: " (pr-str found))
   :data {:definition found}})

(defn- validate-glossaries-config
  "Validate the :glossaries SHAPE (sl-a7sx, Warden W33). The ::config spec
   was documentation-only: the retired type-keyed :verbs map, a string
   :verbs, or a non-string :subjects/:intents crashed the path resolver
   (exit 3) instead of reaching a planning error. Returns a seq of
   :config/invalid-glossaries errors (nil/empty when clean). File
   existence and content stay the loaders' business."
  [config]
  (let [glossaries (:glossaries config)]
    (cond
      (nil? glossaries) nil

      (not (map? glossaries))
      [(invalid-glossaries-error ":glossaries must be a map" glossaries)]

      :else
      (let [{:keys [verbs subjects intents]} glossaries]
        (keep identity
              [(when (and (some? verbs)
                          (not (and (sequential? verbs) (every? string? verbs))))
                 (invalid-glossaries-error
                  ":glossaries :verbs must be a list of file paths" verbs))
               (when (and (some? subjects) (not (string? subjects)))
                 (invalid-glossaries-error
                  ":glossaries :subjects must be a single file path" subjects))
               (when (and (some? intents) (not (string? intents)))
                 (invalid-glossaries-error
                  ":glossaries :intents must be a single file path" intents))])))))

(defn normalize
  "Normalize and validate configuration.

   Takes a raw config map (already merged with defaults) and validates it.
   Runs on BOTH channels: the projection (orient/explain, as :diagnostics)
   and the run path (load-config-stage feeds :errors through
   report-planning-error!, exit 2 — sl-74w8).

   Returns:
   - On success: the config map unchanged
   - On failure: the config map with :errors vector added

   Validation rules:
   - If [:runner :macros :enabled?] is true, [:runner :macros :registry-paths]
     must be a non-empty vector, else error :macro/config-missing-registry-paths
   - :interfaces, when present and non-nil, must be a map; each entry a
     definition map or an (expanded-by-load-config) :default sentinel —
     an unexpanded sentinel names no builtin and errors (sl-g2lj)
   - Interface names and types must not use the reserved sl/ namespace
     (sl-g2lj rider)
   - Each interface must have :type and :adapter
   - Interface :type must be known (:web, :api, :sms, :email)
   - :timing (top-level or per-interface), when present, must conform to
     ::timing (sl-99u2)
   - :glossaries, when present and non-nil, must be a map whose :verbs is
     a list of path strings and whose :subjects/:intents are single path
     strings (:config/invalid-glossaries, sl-a7sx)
   - :enforcement levels (every kind and :all) must be from the closed set
     `enforcement-levels` (:config/invalid-enforcement-level with
     did-you-mean, sl-3bqv)
   - Empty config is valid

   Top-level :webdriver/:webdriver-url are NOT validated or normalized here —
   `sl run` ignores them and lint-config warns on them (sl-7kct); the
   vestigial normalization was removed in sl-w9wp."
  [config]
  (let [interface-errors (validate-interfaces-config config)
        costume-errors (validate-costumes-config config)
        glossary-errors (validate-glossaries-config config)
        enforcement-errors (validate-enforcement-config config)
        errors (concat (keep identity [(validate-macro-config config)
                                       (validate-timing-config config)])
                       interface-errors
                       costume-errors
                       glossary-errors
                       enforcement-errors)]
    (if (seq errors)
      (assoc config :errors (vec errors))
      config)))

;; -----------------------------------------------------------------------------
;; Glossary Config Accessors
;; -----------------------------------------------------------------------------

(defn get-glossary-config
  "Get the glossary paths configuration.

   Returns map with:
   - :subjects — path to subjects glossary (or nil)
   - :verbs — vector of verb glossary file paths (or nil); each file's own
     :type is its mount key (sl-67dj, file-authoritative)

   Example return:
   {:subjects \"glossary/subjects.edn\"
    :verbs [\"glossary/verbs/web.edn\"]}"
  [config]
  (:glossaries config))

(defn resolve-glossary-config-paths
  "Resolve every path in a :glossaries config value against the project
   context (one shared implementation, sl-67dj — previously duplicated
   verbatim in the projection and the runner). :verbs is a path LIST;
   :subjects/:intents are single paths. Returns nil for a nil config.

   Shape-safe (sl-a7sx, Warden W33): this runs BEFORE normalize on both
   channels, so it resolves only the values that ARE paths (strings) and
   passes anything else through untouched for normalize's
   :config/invalid-glossaries arm to name — it never throws on the retired
   type-keyed map, a string :verbs, or a non-string :subjects."
  [project-context glossary-config]
  (when glossary-config
    (let [resolve-path #(project-context/resolve-config-path project-context %)]
      (cond-> glossary-config
        (string? (:subjects glossary-config))
        (update :subjects resolve-path)

        (string? (:intents glossary-config))
        (update :intents resolve-path)

        (sequential? (:verbs glossary-config))
        (update :verbs (fn [paths]
                         (mapv #(if (string? %) (resolve-path %) %) paths)))))))

;; -----------------------------------------------------------------------------
;; Interface Config Accessors
;; -----------------------------------------------------------------------------

(defn get-interfaces
  "Get all interface definitions.

   Returns map of interface-name -> {:type :adapter :config}."
  [config]
  (:interfaces config))

(defn get-interface
  "Get a specific interface definition by name.

   Returns {:type :adapter :config} or nil if not found."
  [config interface-name]
  (get-in config [:interfaces interface-name]))

(defn get-interface-type
  "Get the type of an interface.

   Returns the :type keyword (e.g., :web, :api) or nil."
  [config interface-name]
  (get-in config [:interfaces interface-name :type]))

(defn get-interface-adapter
  "Get the adapter for an interface.

   Returns the :adapter keyword (e.g., :etaoin) or nil."
  [config interface-name]
  (get-in config [:interfaces interface-name :adapter]))

(defn get-base-url
  "Get the :base-url configured for an interface — the environmental HOST half
   of named-location resolution (sl-3jr4); the semantic PATH half lives on the
   intent's :location.

   Returns the string or nil when unset."
  [config interface-name]
  (get-in config [:interfaces interface-name :config :base-url]))

;; -----------------------------------------------------------------------------
;; Costume Config Accessors (sl-1206)
;; -----------------------------------------------------------------------------

(defn get-costumes
  "Get the costume declaration map: costume name -> {:interface <instance>}.
   Returns {} when the :costumes key is absent."
  [config]
  (get config :costumes {}))

;; -----------------------------------------------------------------------------
;; Enforcement Config Accessors
;; -----------------------------------------------------------------------------

(defn get-enforcement
  "The effective enforcement levels of a LOADED config: one entry per kind
   in `enforcement-kinds` (the :all umbrella is already folded in by
   load-config and is not returned). Each value is :warn, :error, or :off.
   Falls back to default-config's block for a raw (unloaded) map."
  [config]
  (select-keys (or (:enforcement config) (:enforcement default-config))
               enforcement-kinds))

(defn enforcement-level
  "The effective level for one enforcement kind (see `enforcement-kinds`)."
  [config kind]
  (get (get-enforcement config) kind))
