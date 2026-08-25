(ns shiftlefter.runner.setup
  "Test orchestration via a `setup.clj` convention file.

   ## What setup.clj is for

   Some tests need a SUT (HTTP server, queue, mocked external service)
   to be standing in a specific configuration before the runner exercises
   it — and the SUT may need to share state with the framework's
   capabilities (e.g. a MockSMS log that both a fixture HTTP server and
   the `:sms` interface read from). `sl run` alone has no place to put
   that orchestration. `setup.clj` is that place.

   ## The contract

   When a `setup.clj` sits next to the active `shiftlefter.edn`, the
   runner load-files it and invokes `setup/setups` (var or 1-arg fn).

   `setups` returns a vector of group entries:

   ```clojure
   (defn setups [_config]
     [{:label    \"multi-user-web\"
       :start    multi-user-web      ;; (fn [config]) → {:adapter-registry r? :stop fn?}
       :features [\"features/multi-user/two-users.feature\"
                  \"features/multi-user/alice-bob-chat.feature\"]}
      {:label    \"sms-2fa\"
       :start    sms-2fa
       :features [\"features/password_reset_sms.feature\"]}])
   ```

   Each entry:
   - `:label`    — human-readable name (optional, used in reporters)
   - `:adapters` — OPTIONAL vector of pure-data adapter declarations
                   (sl-e0ml): the plan-time face of the custom registry
                   `:start` will return. Each declaration is
                   `{:name :kw, :type :kw?, :provides [qualified-kw ...]?,
                   :capture {kind data-map}?, :impl-key kw-or-nil?,
                   :on-provision? bool?}` — data only, no functions
                   (`:impl-key nil` declares deliberate absence;
                   `:on-provision?` is the boolean face of the hook).
                   Declarations cover the DELTA vs the default registry;
                   bundled adapters used unmodified need no declaration.
                   Dry-run binds against the declarations (preview-honest);
                   at `:start` the live registry is VERIFIED to match them
                   (mismatch = planning error, `:setup/registry-mismatch`).
                   A novel adapter name (absent from the default registry)
                   MUST declare `:provides` — an empty-provides novel
                   adapter would re-create the preview blindness this key
                   exists to kill (static lint error). `:type` names the
                   verb-vocabulary family for novel-typed adapters —
                   informational; the `:interfaces` config already carries
                   the plan-time `:type` data.
   - `:start`    — `(fn [config])` → `{:adapter-registry ..., :stop ...}`,
                   both keys optional. Called once per group, before that
                   group's scenarios run. Factories in the returned registry
                   may close over runtime state (the 04-sms-2fa shared-mock
                   pattern) — binding stays runtime; only the data half is
                   declared. A returned registry is EXTERNAL INPUT: its
                   entries are validated against the registry's own spec at
                   install (sl-wntk; `adapters.registry/registry-problems`)
                   — a malformed entry is a planning error
                   `:setup/invalid-adapter-registry` with the s/explain
                   output, before any factory runs. DEPRECATED SHAPE: returning `:adapter-registry`
                   from a group with no `:adapters` declaration still works
                   this release with a preview-blind warning
                   (`:setup/undeclared-adapter-registry`); it becomes an
                   error in a future release.
   - `:features` — vector of feature paths/globs (anything `discover` accepts).
                   Vector position drives intra-group order; group position
                   in the outer vector drives between-group order.

   The `config` argument (to `setups` and to each `:start`) is the RESOLVED
   config map (`::config`) — the same map every group receives. Treat it as
   read-only: nothing you assoc onto it is seen by the framework.

   ## Pure-mode invariant

   When `setup.clj` is present, **the union of all `:features` vectors
   defines the test plan.** Feature files on disk that aren't declared
   are ignored. CLI paths (`sl run path/to/foo.feature`) must be a
   subset of the declared union; otherwise it's a planning error.

   This is strict-by-construction (not strict-by-validation): there's
   only one source of truth, so drift can't happen.

   When `setup.clj` is absent, the runner uses today's behavior
   (CLI paths drive discovery, no orchestration, no groups).

   ## Plan-shape lints

   The declared plan is linted statically before any group runs (and
   before CLI narrowing — dry-run sees the same verdicts). A group whose
   `:features` match no files is a planning ERROR (a dead group is a
   config lie), as is a `:label` shared by two groups (labels are
   reporting-load-bearing — group = run-scope). The same feature file
   declared by two groups is a WARNING, not an error: it runs once per
   group, and same-feature-under-two-registries is a conceivable
   legitimate matrix pattern. See `lint-setups`."
  (:require [babashka.fs :as fs]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [shiftlefter.adapters.registry :as adapters]
            [shiftlefter.runner.discover :as discover]))

;; -----------------------------------------------------------------------------
;; Specs — Setup Entry Shape
;; -----------------------------------------------------------------------------

(s/def ::label string?)
;; ifn? has no built-in generator; provide a no-op so spec-health
;; (test/shiftlefter/spec_health_test.clj) can exercise these specs.
(s/def ::start (s/with-gen ifn? #(gen/return (fn [_config] {}))))
(s/def ::stop  (s/with-gen ifn? #(gen/return (fn [] nil))))
(s/def ::features (s/coll-of string? :min-count 1))
(s/def ::adapter-registry (s/nilable map?))

;; Adapter declarations (sl-e0ml): the pure-data plan-time face of a custom
;; registry. :name is the registry key; :provides is the protocol list the
;; adapter's impl satisfies (qualified keywords — already data in registry
;; entries); :capture maps capture kinds to their data half
;; ({:scenario-scoped? bool} — never :fn); :type names the verb-vocabulary
;; family for novel-typed adapters (informational). NO FUNCTIONS anywhere in
;; a declaration — that is the point.
(s/def :shiftlefter.runner.setup.decl/name keyword?)
(s/def :shiftlefter.runner.setup.decl/type keyword?)
;; sl-xjx0 (Warden W20): the runtime-only entry facts get pure-data
;; declaration faces. :impl-key is nilable ON PURPOSE — `:impl-key nil`
;; DECLARES deliberate absence (a factory returning the impl directly),
;; which is how a builtin-name override records dropping the default's
;; key as intended (see lossy-override-warnings). :on-provision? is the
;; boolean face of "the live entry carries a provision hook" — never the
;; fn itself.
(s/def :shiftlefter.runner.setup.decl/impl-key (s/nilable keyword?))
(s/def :shiftlefter.runner.setup.decl/on-provision? boolean?)
;; Explicitly-empty :provides is legal and meaningful — "this adapter
;; provides no protocols" is a truthful declaration (steps may consume its
;; impl without :requires-protocols). What the novel-adapter lint requires
;; is the KEY's presence, never a minimum count.
(s/def ::provides (s/coll-of qualified-keyword?))
(s/def ::capture (s/map-of keyword? map?))

(s/def ::adapter-decl
  (s/keys :req-un [:shiftlefter.runner.setup.decl/name]
          :opt-un [:shiftlefter.runner.setup.decl/type ::provides ::capture
                   :shiftlefter.runner.setup.decl/impl-key
                   :shiftlefter.runner.setup.decl/on-provision?]))

(s/def ::adapters (s/coll-of ::adapter-decl :min-count 1))

(s/def ::setup-entry
  (s/keys :req-un [::start ::features]
          :opt-un [::label ::adapters]))

(s/def ::setup-list
  (s/coll-of ::setup-entry :min-count 1))

(s/def ::start-result
  (s/keys :opt-un [::adapter-registry ::stop]))

;; The config argument (:start receives it, and `setups` may) is the RESOLVED
;; config map — whatever load-config produced from shiftlefter.edn plus
;; defaults. Treat it as READ-ONLY: it is the same map every group receives,
;; and nothing you assoc onto it is seen by the framework. Deliberately
;; permissive (s/keys, all optional): user configs carry open keys, and the
;; setup boundary's promise is "you get the resolved map", not a schema.
;; NOTE: ::start stays `ifn?`, never s/fspec — fspec instrumentation would
;; generatively INVOKE side-effectful user lifecycle fns at spec-check time
;; (a miniature of the G7 dry-run-runs-:start bug; Chair-ratified, sl-yvaz).
(s/def ::config
  (s/with-gen map? #(gen/return {})))

(s/def ::ok ::setup-list)
(s/def ::error map?)

;; Plan-shape lint issues (sl-lkla). Same open style as discover's error
;; maps: :type + :message required, locating keys (:group, :label, :groups,
;; :features, :feature) ride along per issue type.
(s/def ::type keyword?)
(s/def ::message string?)
(s/def ::lint-issue (s/keys :req-un [::type ::message]))
(s/def ::errors (s/coll-of ::lint-issue))
(s/def ::warnings (s/coll-of ::lint-issue))
(s/def ::lint-result (s/keys :req-un [::errors ::warnings]))

(s/fdef load-setup
  :args (s/cat :setup-path string? :config ::config)
  :ret (s/or :ok (s/keys :req-un [::ok])
             :error (s/keys :req-un [::error])))

(s/fdef lint-setups
  :args (s/cat :setups ::setup-list :base-dir (s/? (s/nilable string?)))
  :ret ::lint-result)

;; -----------------------------------------------------------------------------
;; File Resolution
;; -----------------------------------------------------------------------------

(defn find-setup-file
  "Locate setup.clj as a sibling of the active config file.

   `config-path` is the path to the resolved `shiftlefter.edn` (whatever
   `load-config` actually loaded). If nil (built-in defaults, no config
   file present), there is no project root and therefore no setup.clj.

   Returns absolute path string or nil."
  [config-or-path]
  (let [config-path (if (map? config-or-path)
                      (:config-path config-or-path)
                      config-or-path)]
    (when config-path
      (let [base   (or (some-> config-path fs/parent str) ".")
            setup  (str (fs/path base "setup.clj"))]
        (when (fs/exists? setup)
          (str (fs/real-path setup)))))))

;; -----------------------------------------------------------------------------
;; Loader
;; -----------------------------------------------------------------------------

(defn- resolve-setups-var
  "After load-file, resolve the `setup/setups` var. Setup files declare
   `(ns setup ...)` by convention; we look the symbol up there."
  []
  (or (ns-resolve 'setup 'setups)
      (throw (ex-info "setup.clj must declare (ns setup ...) and define `setups`"
                      {:type :setup/missing-setups-var}))))

(defn load-setup
  "Load setup.clj and produce its setups list.

   1. Force a clean reload by remove-ns'ing 'setup before load-file.
   2. load-file the path (raises on syntax/eval errors).
   3. Resolve `setup/setups` — may be a var holding a vector or a fn
      that takes config. Both shapes are accepted.
   4. Validate the result against ::setup-list spec.

   Returns:
   - {:ok [setup-entry ...]} on success
   - {:error {:type ... :message ...}} on any failure"
  [setup-path config]
  (try
    (remove-ns 'setup)
    (load-file setup-path)
    (let [setups-var (resolve-setups-var)
          raw        (var-get setups-var)
          setups     (if (fn? raw) (raw config) raw)]
      (if (s/valid? ::setup-list setups)
        {:ok (vec setups)}
        {:error {:type     :setup/invalid-shape
                 :message  (str "setup.clj `setups` failed validation: "
                                (s/explain-str ::setup-list setups))
                 :explain  (s/explain-data ::setup-list setups)
                 :path     setup-path}}))
    (catch Throwable t
      {:error {:type    :setup/load-failed
               :message (str "Failed to load setup.clj: " (ex-message t))
               :path    setup-path
               :cause   (.getName (class t))}})))

;; -----------------------------------------------------------------------------
;; Adapter Declarations (sl-e0ml) — declare / verify
;; -----------------------------------------------------------------------------

(defn- entry-data-half
  "The comparable pure-data projection of one registry entry (or one
   declaration — both carry :provides and a :capture map keyed by kind):
   {:provides #{...} :capture-kinds {kind scenario-scoped?}}. Factories,
   cleanup fns, and every other runtime key are out of frame by design —
   the declared contract covers exactly what plan time consumes."
  [entry]
  {:provides (set (:provides entry))
   :capture-kinds (into {}
                        (map (fn [[kind v]] [kind (boolean (:scenario-scoped? v))]))
                        (:capture entry))})

(defn registry-data-half
  "Project a whole adapter registry to its comparable data half:
   {adapter-name (entry-data-half entry)}."
  [registry]
  (into {} (map (fn [[k e]] [k (entry-data-half e)])) registry))

(defn declared-preview-registry
  "The registry preview binds against (sl-e0ml part a): the default registry
   overlaid with each declaration's data. An existing name keeps its default
   entry with the declared keys overriding; a novel name becomes a data-only
   entry. Sufficient for every plan-time consumer (suite-lint capability
   gating reads :provides; nothing at plan time calls :factory)."
  [decls]
  (reduce (fn [reg decl]
            (update reg (:name decl)
                    (fn [entry]
                      (cond-> (or entry {})
                        (contains? decl :provides) (assoc :provides (vec (:provides decl)))
                        (contains? decl :capture)  (assoc :capture (:capture decl))))))
          adapters/default-registry
          decls))

(defn verify-declared-registry
  "Verify the LIVE registry (:start's return, or the default registry when it
   returned none) against the group's :adapters declarations (sl-e0ml part c:
   declared contract, runtime-checked). Returns nil on match, else a
   planning-class error map {:type :setup/registry-mismatch :message ...
   :mismatches [...]}.

   Match = data-half equality against the declared preview registry:
   - every declared name must exist in the live registry with the same
     :provides set and capture kinds/flags as the preview entry (a
     declaration that omits :provides/:capture therefore pins the default
     entry's data — silence is not a wildcard);
   - every live entry whose data half differs from the default registry's
     must be declared (an undeclared delta is exactly the preview blindness
     this contract kills)."
  [decls live-registry]
  (let [live          (or live-registry adapters/default-registry)
        live-half     (registry-data-half live)
        default-half  (registry-data-half adapters/default-registry)
        expected-half (registry-data-half (declared-preview-registry decls))
        declared?     (set (map :name decls))
        mismatches
        (vec
         (concat
          (mapcat (fn [decl]
                    (let [nm (:name decl)
                          lh (get live-half nm)
                          eh (get expected-half nm)]
                      (cond
                        (nil? lh)
                        [{:mismatch :declared-adapter-missing
                          :adapter nm
                          :message (str "declared adapter " nm
                                        " is absent from the live registry")}]

                        :else
                        (concat
                         (when (not= lh eh)
                           [(cond-> {:mismatch :declared-data-differs
                                     :adapter nm
                                     :message (str "adapter " nm
                                                   " differs from its declaration")}
                              (not= (:provides lh) (:provides eh))
                              (assoc :declared-provides (vec (sort (:provides eh)))
                                     :live-provides (vec (sort (:provides lh))))
                              (not= (:capture-kinds lh) (:capture-kinds eh))
                              (assoc :declared-capture-kinds (:capture-kinds eh)
                                     :live-capture-kinds (:capture-kinds lh)))])
                         ;; sl-xjx0 (Warden W20): the OPTIONAL runtime-field
                         ;; declarations, compared only when present.
                         ;; :impl-key nil declares deliberate absence.
                         (let [live-entry (get live nm)]
                           (concat
                            (when (and (contains? decl :impl-key)
                                       (not= (:impl-key decl)
                                             (:impl-key live-entry)))
                              [{:mismatch :declared-impl-key-differs
                                :adapter nm
                                :declared-impl-key (:impl-key decl)
                                :live-impl-key (:impl-key live-entry)
                                :message (str "adapter " nm " declares :impl-key "
                                              (pr-str (:impl-key decl))
                                              " but the live entry has "
                                              (pr-str (:impl-key live-entry)))}])
                            (when (and (contains? decl :on-provision?)
                                       (not= (:on-provision? decl)
                                             (boolean (:on-provision live-entry))))
                              [{:mismatch :declared-on-provision-differs
                                :adapter nm
                                :declared-on-provision? (:on-provision? decl)
                                :live-on-provision? (boolean (:on-provision live-entry))
                                :message (str "adapter " nm " declares :on-provision? "
                                              (:on-provision? decl)
                                              " but the live entry "
                                              (if (:on-provision live-entry)
                                                "carries a hook"
                                                "has none"))}])))))))
                  decls)
          (keep (fn [[nm lh]]
                  (when (and (not (declared? nm))
                             (not= lh (get default-half nm)))
                    {:mismatch :undeclared-adapter
                     :adapter nm
                     :message (str "adapter " nm " differs from the default"
                                   " registry but is not declared in :adapters")}))
                (sort-by key live-half))))]
    (when (seq mismatches)
      {:type :setup/registry-mismatch
       :mismatches mismatches
       :message (str "live adapter registry does not match the :adapters"
                     " declaration: "
                     (str/join "; " (map :message mismatches)))})))

(defn lossy-override-warnings
  "Warn-class check beside `verify-declared-registry` (sl-xjx0, Warden
   W20 — the uv3r/W22 accident class): a live entry that OVERRIDES a
   builtin name and silently drops runtime keys the default entry carries
   (:impl-key, :on-provision). Those keys are outside the data-half
   contract, so verify cannot error on them — but dropping them is almost
   always an accident (rebuilding the entry instead of updating it), and
   the loss is invisible until provisioning misbehaves (a hook that never
   fires, an impl extracted from the wrong shape).

   A drop the declaration ADDRESSES is silent: `:impl-key nil` or
   `:on-provision? false` on the adapter's declaration records the drop
   as deliberate (and `verify-declared-registry` then enforces it).

   Returns a vector of warning maps (empty when clean) — the caller
   surfaces them on the non-exit-flipping setup-lints channel."
  [decls live-registry referenced-adapters]
  (let [live (or live-registry adapters/default-registry)
        decl-by-name (into {} (map (juxt :name identity)) decls)
        ;; sl-xjx0 (Warden W21): the extreme lossy case — a default adapter
        ;; ABSENT from the live registry entirely. Removal is a delta the
        ;; declaration grammar cannot express this release and verify's
        ;; live-half iteration cannot see, so an empty registry used to
        ;; pass every check while first provisioning failed live with
        ;; :adapter/unknown. Scoped to `referenced-adapters` (the config's
        ;; :interfaces adapter set): a minimal registry that covers
        ;; everything the config uses is an established, harmless shape —
        ;; the warn fires exactly when the :adapter/unknown failure is
        ;; reachable. Since sl-kkoh this arm is the PLANS-NOT-IN-HAND
        ;; fallback only: the run seam passes no referenced adapters and
        ;; uses the exact plan-aware `bound-adapter-errors` instead
        ;; (config-referenced-but-unbound removed defaults are certainly
        ;; safe for that invocation, so the run stays silent about them).
        removed (vec
                 (for [nm (sort (keys adapters/default-registry))
                       :when (and (contains? (set referenced-adapters) nm)
                                  (not (contains? live nm)))]
                   {:type :setup/default-adapter-removed
                    :adapter nm
                    :message (str "default adapter " nm " is absent from the"
                                  " returned registry but a configured"
                                  " interface uses it — dry-run still binds"
                                  " against it and its first provisioning"
                                  " will fail live with :adapter/unknown;"
                                  " build on the defaults ((assoc"
                                  " reg/default-registry ...)) unless the"
                                  " removal is deliberate")}))]
    (into
     removed
     (for [[nm entry] (sort-by key live)
           :let [default-entry (get adapters/default-registry nm)
                 decl (get decl-by-name nm)
                 dropped (vec
                          (for [[k decl-k] [[:impl-key :impl-key]
                                            [:on-provision :on-provision?]]
                                :when (and default-entry
                                           (not (identical? entry default-entry))
                                           (contains? default-entry k)
                                           (not (contains? entry k))
                                           (not (contains? decl decl-k)))]
                            k))]
           :when (seq dropped)]
       {:type :setup/lossy-adapter-override
        :adapter nm
        :dropped dropped
        :message (str "adapter " nm " overrides a builtin entry but drops "
                      (str/join " and " (map pr-str dropped))
                      " that the default entry carries — deliberate? If so,"
                      " record it on the declaration ("
                      (str/join ", " (keep {:impl-key ":impl-key nil"
                                            :on-provision ":on-provision? false"}
                                           dropped))
                      "); if not, build the entry with update-dont-rebuild"
                      " ((update reg/default-registry " nm " merge ...)) so"
                      " the default keys survive")}))))

(defn bound-adapter-errors
  "The plan-aware EXACT registry check (sl-kkoh, the W21 precision
   refinement): every adapter a BOUND, SELECTED scenario references must
   exist in the live registry, or its first provisioning certainly dies
   with :adapter/unknown. Exactness cuts both ways: an adapter the plans
   never touch is certainly safe for this invocation and stays silent
   (the config-referenced approximation lives on only in
   `lossy-override-warnings`' removal arm, for callers without plans).

   `touched-by-adapter` is {adapter-name #{interface-instances}} — built
   by the caller from the compiled plans (collect-provisioning-targets +
   the config's interface→:adapter lookup), so this fn stays plans-free.
   Selection-sensitive BY DESIGN: tag filtering precedes compile, so a
   filtered-out scenario cannot make this error.

   Returns a vector of planning-class error maps (empty when clean):
   {:type :setup/bound-adapter-missing :adapter nm :interfaces [...]}.
   Not scoped to default-registry names — a bound novel adapter missing
   from the live registry dies identically; default names additionally
   get the build-on-defaults teaching tail."
  [touched-by-adapter live-registry]
  (let [live (or live-registry adapters/default-registry)]
    (vec
     (for [[nm instances] (sort-by key touched-by-adapter)
           :when (not (contains? live nm))]
       {:type :setup/bound-adapter-missing
        :adapter nm
        :interfaces (vec (sort instances))
        ;; :data mirrors the identifying keys: --edn's serialize-error is
        ;; a key projection that passes :data through but drops novel
        ;; top-level keys, and the machine consumer needs the adapter.
        :data {:adapter nm :interfaces (vec (sort instances))}
        :message (str "bound scenarios on interface"
                      (when (next instances) "s") " "
                      (str/join ", " (map str (sort instances)))
                      " reference adapter " nm ", which is absent from the"
                      " live registry — first provisioning would fail with"
                      " :adapter/unknown"
                      (when (contains? adapters/default-registry nm)
                        (str "; build on the defaults ((assoc"
                             " reg/default-registry ...)) unless the"
                             " removal is deliberate")))}))))

;; -----------------------------------------------------------------------------
;; Feature Resolution
;; -----------------------------------------------------------------------------

(defn- absolutize-against
  "Resolve `path-or-glob` against `base-dir`. Absolute paths pass through;
   relative paths are joined with the base."
  [base-dir path-or-glob]
  (if (or (nil? base-dir)
          (.isAbsolute (java.io.File. ^String path-or-glob)))
    path-or-glob
    (str (fs/path base-dir path-or-glob))))

(defn expand-group-features
  "Expand a group's :features vector via discover-feature-files.

   Relative paths in :features resolve against `base-dir` (typically the
   directory containing setup.clj — passing nil falls back to cwd-relative,
   matching pre-setup behavior).

   Preserves the user-declared order across multiple :features entries
   (within a single glob, sub-order is discover's stable sort). Returns
   absolute paths."
  ([setup-entry]
   (expand-group-features setup-entry nil))
  ([setup-entry base-dir]
   (->> (:features setup-entry)
        (mapcat (fn [path-or-glob]
                  (discover/discover-feature-files
                   [(absolutize-against base-dir path-or-glob)])))
        vec)))

(defn declared-feature-set
  "Union of all features declared across all groups, as a set of absolute
   paths. The runner's source of truth for what's runnable when setup.clj
   is present.

   `base-dir` (optional) — resolve setup-entry :features paths against it."
  ([setups]
   (declared-feature-set setups nil))
  ([setups base-dir]
   (->> setups
        (mapcat #(expand-group-features % base-dir))
        set)))

(defn validate-cli-paths
  "When the user passes explicit `sl run path/...` paths AND a setup.clj
   is in effect, every CLI path must be a subset of the declared feature
   union. Otherwise it's a planning error.

   `base-dir` (optional) — used to resolve setup-entry relative :features.

   Returns:
   - {:ok cli-paths-as-absolute} when every CLI path is declared
   - {:error {...}} otherwise"
  ([cli-paths setups]
   (validate-cli-paths cli-paths setups nil))
  ([cli-paths setups base-dir]
   (let [declared (declared-feature-set setups base-dir)
         ;; Expand CLI paths the same way discover does, so a directory
         ;; or glob CLI arg is matched against the declared set.
         expanded (vec (discover/discover-feature-files (or cli-paths [])))
         unknown  (vec (remove declared expanded))]
     (cond
       (seq unknown)
       {:error {:type      :setup/cli-path-not-declared
                :message   (str "These feature paths aren't declared in any setup entry: "
                                (str/join ", " unknown))
                :unknown   unknown
                :declared  (vec declared)}}

       :else
       {:ok expanded}))))

;; -----------------------------------------------------------------------------
;; Group Filtering for Partial Runs
;; -----------------------------------------------------------------------------

(defn filter-setups-by-cli-paths
  "When CLI paths are given (and validated), narrow each group to only
   the declared features that intersect the CLI selection. Drop groups
   that end up empty.

   When no CLI paths are given, mark every group's resolved features
   for downstream consumption (preserves declared order).

   `base-dir` (optional) — used to resolve setup-entry relative :features."
  ([setups cli-abs-paths]
   (filter-setups-by-cli-paths setups cli-abs-paths nil))
  ([setups cli-abs-paths base-dir]
   (if (empty? cli-abs-paths)
     ;; Full run — pre-resolve so resolve-group-features doesn't re-glob.
     (mapv (fn [entry]
             (assoc entry ::resolved-features (expand-group-features entry base-dir)))
           setups)
     (let [selected (set cli-abs-paths)]
       (->> setups
            (map (fn [entry]
                   (assoc entry
                          ::resolved-features
                          (->> (expand-group-features entry base-dir)
                               (filter selected)
                               vec))))
            (filter #(seq (::resolved-features %)))
            vec)))))

(defn resolve-group-features
  "Get the absolute paths to actually run for this group.
   Uses ::resolved-features if filter-setups-by-cli-paths set it,
   otherwise expands :features cwd-relative (legacy)."
  [setup-entry]
  (or (::resolved-features setup-entry)
      (expand-group-features setup-entry)))

;; -----------------------------------------------------------------------------
;; Plan-Shape Lints (sl-lkla)
;; -----------------------------------------------------------------------------

(defn- group-name
  "Reporting name for a group: its :label, or a positional fallback."
  [entry idx]
  (or (:label entry) (str "group " idx)))

(defn- empty-features-issue
  [entry name]
  {:type :setup/empty-features
   :group name
   :features (vec (:features entry))
   :message (str "Setup group " (pr-str name)
                 " declares :features that match no feature files ("
                 (str/join ", " (:features entry))
                 ") — a dead group would report green while testing nothing")})

(defn- lint-empty-features
  [setups names expansions]
  (vec (keep (fn [idx]
               (when (empty? (nth expansions idx))
                 (empty-features-issue (nth setups idx) (nth names idx))))
             (range (count setups)))))

(defn- duplicate-label-issue
  [label idxs]
  {:type :setup/duplicate-label
   :label label
   :groups (vec idxs)
   :message (str "Setup label " (pr-str label)
                 " is declared by groups at positions " (str/join " and " idxs)
                 " — labels are reporting-load-bearing (group = run-scope)"
                 " and must be unique")})

(defn- lint-duplicate-labels
  [setups]
  (->> (map-indexed (fn [idx entry] [(:label entry) idx]) setups)
       (filter (comp some? first))
       (group-by first)
       (filter (fn [[_ pairs]] (> (count pairs) 1)))
       (sort-by key)
       (mapv (fn [[label pairs]] (duplicate-label-issue label (map second pairs))))))

(defn- shared-feature-issue
  [feature names]
  {:type :setup/feature-in-multiple-groups
   :feature feature
   :groups (vec names)
   :message (str "Feature " feature " is declared by groups "
                 (str/join " and " (map pr-str names))
                 " — it will run once per group; if this is a deliberate"
                 " matrix, this warning is expected")})

(defn- adapter-decl-issues
  "Static lint over one group's :adapters declarations (sl-e0ml). Errors:
   - :setup/adapter-decl-missing-provides — a NOVEL adapter name (absent
     from the default registry) declared without :provides; preview would
     bind it blind, re-creating the disease the declaration exists to kill.
   - :setup/duplicate-adapter-decl — the same :name declared twice in one
     group; the declaration is a map-shaped contract, duplicates are a lie."
  [entry group-name]
  (let [decls (:adapters entry)
        dupes (->> (map :name decls)
                   frequencies
                   (filter (fn [[_ n]] (> n 1)))
                   (map key)
                   sort)
        novel-blind (->> decls
                         (filter #(and (not (contains? adapters/default-registry (:name %)))
                                       (not (contains? % :provides))))
                         (map :name))]
    (vec
     (concat
      (map (fn [nm]
             {:type :setup/duplicate-adapter-decl
              :group group-name
              :adapter nm
              :message (str "Setup group " (pr-str group-name)
                            " declares adapter " nm " more than once in"
                            " :adapters — declarations must be unique per name")})
           dupes)
      (map (fn [nm]
             {:type :setup/adapter-decl-missing-provides
              :group group-name
              :adapter nm
              :message (str "Setup group " (pr-str group-name)
                            " declares novel adapter " nm " without :provides —"
                            " preview would bind it blind; declare the protocol"
                            " list its impl satisfies (an explicit [] is legal:"
                            " it truthfully declares no protocols)")})
           novel-blind)))))

(defn- lint-adapter-decls
  [setups names]
  (vec (mapcat (fn [idx]
                 (adapter-decl-issues (nth setups idx) (nth names idx)))
               (range (count setups)))))

(defn- lint-shared-features
  [names expansions]
  (->> (range (count expansions))
       ;; distinct WITHIN a group: the same file declared twice by one group
       ;; must not masquerade as a cross-group share.
       (mapcat (fn [idx] (map vector
                              (distinct (nth expansions idx))
                              (repeat (nth names idx)))))
       (group-by first)
       (filter (fn [[_ pairs]] (> (count pairs) 1)))
       (sort-by key)
       (mapv (fn [[feature pairs]] (shared-feature-issue feature (map second pairs))))))

(defn lint-setups
  "Static plan-shape lints over the DECLARED setups (mmfy G4, severities
   Chair-ruled 08-02). Runs on the full plan before any CLI narrowing —
   plan-shape is static, so dry-run and filtered runs see the same verdicts.

   Errors (planning failures, exit 2):
   - :setup/empty-features  — a group's :features match no feature files on
     disk; a dead group would report green while testing nothing. (A literal
     empty :features vector is already rejected at load by ::features.)
   - :setup/duplicate-label — two groups share a :label; labels are
     reporting-load-bearing (group = run-scope) and must be unique.
   - :setup/adapter-decl-missing-provides / :setup/duplicate-adapter-decl —
     malformed :adapters declarations (sl-e0ml; see adapter-decl-issues).

   Warnings (surfaced, never exit-flipping):
   - :setup/feature-in-multiple-groups — the same feature file is declared
     by more than one group. It runs once per group; same-feature-under-two-
     registries is a conceivable legitimate matrix pattern, so this warns
     loudly instead of erroring.

   `base-dir` resolves relative :features paths (directory containing
   setup.clj). Returns {:errors [...] :warnings [...]} (::lint-result)."
  ([setups] (lint-setups setups nil))
  ([setups base-dir]
   (let [names      (vec (map-indexed (fn [idx entry] (group-name entry idx)) setups))
         expansions (mapv #(expand-group-features % base-dir) setups)]
     {:errors (-> (lint-empty-features setups names expansions)
                  (into (lint-duplicate-labels setups))
                  (into (lint-adapter-decls setups names)))
      :warnings (lint-shared-features names expansions)})))
