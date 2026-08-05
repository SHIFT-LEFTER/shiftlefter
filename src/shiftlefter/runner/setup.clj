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
   - `:start`    — `(fn [config])` → `{:adapter-registry ..., :stop ...}`,
                   both keys optional. Called once per group, before that
                   group's scenarios run.
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

(s/def ::setup-entry
  (s/keys :req-un [::start ::features]
          :opt-un [::label]))

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
     {:errors (into (lint-empty-features setups names expansions)
                    (lint-duplicate-labels setups))
      :warnings (lint-shared-features names expansions)})))
