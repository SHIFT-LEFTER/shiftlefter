(ns shiftlefter.runner.config
  "Configuration loading for ShiftLefter runner.

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
                 :verbs {:web \"glossary/verbs/web.edn\"}}
    :interfaces {:web {:type :web
                       :adapter :etaoin
                       :config {:headless true}}}
    :svo {:unknown-subject :warn
          :unknown-verb :warn
          :unknown-interface :error
          :unknown-object :off}}   ; :warn when an :intents glossary is
                                   ; configured (sl-femd); :error blocks
                                   ; (:strict = deprecated alias)
   ```

   ## Usage

   ```clojure
   (load-config {:config-path \"my-config.edn\"})
   ;=> {:parser {:dialect \"en\"} :runner {...} ...}
   ```"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [shiftlefter.gherkin.io :as io]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.svo.validate :as svo-validate]))

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
(s/def ::runner (s/keys :req-un [::step-paths ::allow-pending?]
                        :opt-un [::features ::macros ::provisioning ::report
                                 ::max-parallel ::capture ::seed]))

;; Timing sub-config (sl-99u2) — the deadline substrate. Appears at the top
;; level (global rung) and as a sibling key on interface entries (instance/
;; interface rungs). Resolution ladder + framework defaults live in
;; shiftlefter.step/timing; deliberately ABSENT from default-config so a
;; :global source in a timeout record always means the user configured it.
(s/def ::deadline-ms pos-int?)
(s/def ::interval-ms pos-int?)
(s/def ::timing (s/keys :opt-un [::deadline-ms ::interval-ms]))

;; Interface sub-config
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

;; SVO enforcement sub-config
(s/def ::unknown-subject #{:warn :error :off})
(s/def ::unknown-verb #{:warn :error :off})
(s/def ::unknown-interface #{:warn :error :off})
;; :error is the canonical blocking level (sl-femd, harmonized with the
;; siblings above); :strict is a deprecated alias kept for existing configs.
(s/def ::unknown-object #{:error :strict :warn :off})
(s/def ::svo (s/keys :opt-un [::unknown-subject ::unknown-verb ::unknown-interface ::unknown-object]))

;; Glossaries sub-config
(s/def ::subjects (s/nilable string?))
(s/def ::verbs (s/nilable (s/map-of keyword? string?)))
(s/def ::intents (s/nilable string?))
(s/def ::glossaries (s/nilable (s/keys :opt-un [::subjects ::verbs ::intents])))

;; Top-level config
(s/def ::config
  (s/keys :opt-un [::parser ::runner ::glossaries ::interfaces ::costumes
                   ::svo ::timing]))

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

(def default-config
  "Built-in default configuration.

   NOTE: the :svo block documents the enforcement defaults that apply once
   a user OPTS IN to Shifted mode by putting an :svo key in their config.
   `load-config` strips :svo from its result when the user's config lacks
   the key — presence of :svo in a loaded config therefore means the user
   asked for Shifted mode (sl-ieie; ARCHITECTURE.md ':svo absent = Vanilla')."
  {:parser {:dialect "en"}
   :runner {:step-paths ["steps/"]
            :features ["features/"]
            :allow-pending? false
            :macros {:enabled? false}}
   ;; Glossary paths — nil means use framework defaults only
   :glossaries nil
   ;; Interface definitions — name -> {:type :adapter :config}
   :interfaces {:web {:type :web
                      :adapter :etaoin
                      :config {}}}
   ;; SVO enforcement settings (Shifted mode opt-in defaults — see docstring).
   ;; :unknown-object's :off here is the NO-INTENTS default only: load-config
   ;; flips the effective default to :warn when an :intents glossary is
   ;; configured and the user didn't set the key (sl-femd,
   ;; apply-conditional-object-default).
   :svo {:unknown-subject :warn
         :unknown-verb :warn
         :unknown-interface :error
         :unknown-object :off}})

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
   - glossary/verbs/<type>.edn for each known interface type
   - glossary/intents/ (directory)

   Returns a :glossaries-shaped map of the RELATIVE paths that exist, or nil
   when none do. Whole-map semantics (Chair ruling 2026-08-03): this runs only
   when the user's config has no :glossaries key at all — any explicit
   :glossaries value, including nil, is used as written."
  [context]
  (let [root (:config-root context)
        probe (fn [rel] (when (fs/exists? (fs/path root rel)) rel))
        subjects (probe "glossary/subjects.edn")
        verbs (into {}
                    (keep (fn [t]
                            (when-let [rel (probe (str "glossary/verbs/" (name t) ".edn"))]
                              [t rel])))
                    known-interface-types)
        intents (when (fs/directory? (fs/path root "glossary" "intents"))
                  "glossary/intents")]
    (when (or subjects (seq verbs) intents)
      (cond-> {}
        subjects (assoc :subjects subjects)
        (seq verbs) (assoc :verbs verbs)
        intents (assoc :intents intents)))))

(defn- apply-conditional-object-default
  "sl-femd: :unknown-object's default is CONDITIONAL — :warn when an :intents
   glossary is configured (there is something to validate against), :off
   otherwise (default-config's static value). Applies only when the user
   didn't set the key themselves; evaluated on the MERGED config so
   conventionally probed glossaries (sl-fcjm) count as configured.
   Shifted-mode-only by construction: the caller only reaches this when the
   user's config carries :svo (sl-ieie)."
  [merged user-config]
  (let [user-svo (:svo user-config)]
    (if (and (get-in merged [:glossaries :intents])
             (not (and (map? user-svo) (contains? user-svo :unknown-object))))
      (assoc-in merged [:svo :unknown-object] :warn)
      merged)))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn load-config
  "Load configuration with precedence.

   Options:
   - :config-path - explicit config file path (highest precedence)

   Precedence:
   1. Explicit --config path
   2. Discovered sl/shiftlefter.edn (if exists)
   3. Built-in defaults

   Returns merged config map.

   Mode invariant (sl-ieie): the result contains :svo iff the USER's config
   contained :svo. Shifted mode is explicit opt-in — when the user's config
   has :svo, the default enforcement levels are deep-merged under it; when
   it doesn't (or no config file exists), :svo is absent from the result and
   the run is Vanilla. `stepengine.compile/shifted-mode?` keys on this.

   Conditional default (sl-femd): in Shifted mode, :unknown-object defaults
   :warn when an :intents glossary is configured (explicit or conventionally
   probed) and :off otherwise; an explicit user value always wins. This is
   the ONE place the effective default is computed — downstream :off
   fallbacks (stepengine.bind, svo.validate) are nothing-configured safety
   nets, not defaults.

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
                 ;; user's config has NO :glossaries key — same contains?-on-
                 ;; user-config pattern as :svo below. Real-config path only;
                 ;; the defaults context never probes.
                 merged (if (contains? user-config :glossaries)
                          merged
                          (assoc merged :glossaries
                                 (probe-conventional-glossaries context)))]
             ;; Shifted mode is user opt-in: keep :svo (with defaults merged
             ;; under it) only when the user's config has the key (sl-ieie).
             (if (contains? user-config :svo)
               (apply-conditional-object-default merged user-config)
               (dissoc merged :svo)))
           (throw (ex-info (:message result)
                           {:type (:type result)
                            :path config-path}))))

       ;; No config file, use defaults — no user :svo, so Vanilla (sl-ieie)
       :else
       (dissoc default-config :svo)))))

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
  #{:parser :runner :glossaries :interfaces :costumes :svo :timing})

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
   :seed              [:runner :seed]
   :capture           [:runner :capture]
   :junit-xml         [:runner :report :junit-xml]
   :html              [:runner :report :html]
   :dialect           [:parser :dialect]
   :subjects          [:glossaries :subjects]
   :verbs             [:glossaries :verbs]
   :intents           [:glossaries :intents]
   :deadline-ms       [:timing :deadline-ms]
   :interval-ms       [:timing :interval-ms]
   :unknown-subject   [:svo :unknown-subject]
   :unknown-verb      [:svo :unknown-verb]
   :unknown-interface [:svo :unknown-interface]
   :unknown-object    [:svo :unknown-object]})

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
          suggestion (svo-validate/suggest-similar
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
   Returns vector of errors (may be empty)."
  [config]
  (let [interfaces (:interfaces config)]
    (when (map? interfaces)
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
          suggestion (svo-validate/suggest-similar declared (keys interfaces) 2)]
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
   - Each interface must have :type and :adapter
   - Interface :type must be known (:web, :api, :sms, :email)
   - :timing (top-level or per-interface), when present, must conform to
     ::timing (sl-99u2)
   - Empty config is valid

   Top-level :webdriver/:webdriver-url are NOT validated or normalized here —
   `sl run` ignores them and lint-config warns on them (sl-7kct); the
   vestigial normalization was removed in sl-w9wp."
  [config]
  (let [interface-errors (validate-interfaces-config config)
        costume-errors (validate-costumes-config config)
        errors (concat (keep identity [(validate-macro-config config)
                                       (validate-timing-config config)])
                       interface-errors
                       costume-errors)]
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
   - :verbs — map of interface-type -> path (or nil)

   Example return:
   {:subjects \"glossary/subjects.edn\"
    :verbs {:web \"glossary/verbs/web.edn\"}}"
  [config]
  (:glossaries config))

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
;; SVO Config Accessors
;; -----------------------------------------------------------------------------

(defn get-svo-settings
  "Get SVO enforcement settings.

   Returns map with enforcement levels:
   - :unknown-subject — :warn, :error, or :off
   - :unknown-verb — :warn, :error, or :off
   - :unknown-interface — :warn, :error, or :off
   - :unknown-object — :error, :warn, or :off (:strict = deprecated alias
     for :error; effective default computed by load-config — :warn when an
     :intents glossary is configured, :off otherwise, sl-femd)"
  [config]
  (:svo config))

(defn svo-enforcement
  "Get the enforcement level for a specific SVO check.

   check should be one of:
   - :unknown-subject — :warn, :error, or :off
   - :unknown-verb — :warn, :error, or :off
   - :unknown-interface — :warn, :error, or :off
   - :unknown-object — :error, :warn, or :off (:strict = deprecated alias)

   Returns the configured enforcement level."
  [config check]
  (get-in config [:svo check]))
