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

   ## Pure-mode invariant

   When `setup.clj` is present, **the union of all `:features` vectors
   defines the test plan.** Feature files on disk that aren't declared
   are ignored. CLI paths (`sl run path/to/foo.feature`) must be a
   subset of the declared union; otherwise it's a planning error.

   This is strict-by-construction (not strict-by-validation): there's
   only one source of truth, so drift can't happen.

   When `setup.clj` is absent, the runner uses today's behavior
   (CLI paths drive discovery, no orchestration, no groups)."
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

;; -----------------------------------------------------------------------------
;; File Resolution
;; -----------------------------------------------------------------------------

(defn find-setup-file
  "Locate setup.clj as a sibling of the active config file.

   `config-path` is the path to the resolved `shiftlefter.edn` (whatever
   `load-config` actually loaded). If nil (built-in defaults, no config
   file present), there is no project root and therefore no setup.clj.

   Returns absolute path string or nil."
  [config-path]
  (when config-path
    (let [base   (or (some-> config-path fs/parent str) ".")
          setup  (str (fs/path base "setup.clj"))]
      (when (fs/exists? setup)
        (str (fs/absolutize setup))))))

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
