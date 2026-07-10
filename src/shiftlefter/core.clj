(ns shiftlefter.core
  "Main CLI for ShiftLefter."
  (:require
   [babashka.fs :as fs]
   [clojure.main]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [shiftlefter.agent-doc :as agent-doc]
   [shiftlefter.costume :as costume]
   [shiftlefter.costume.wardrobe :as wardrobe]
   [shiftlefter.gherkin.api :as api]
   [shiftlefter.gherkin.ddmin :as ddmin]
   [shiftlefter.gherkin.diagnostics :as diag]
   [shiftlefter.gherkin.fuzz :as fuzz]
   [shiftlefter.gherkin.io :as io]
   [shiftlefter.gherkin.verify :as verify]
   [shiftlefter.orient :as orient]
   [shiftlefter.project-context :as project-context]
   [shiftlefter.runner.core :as runner]
   [shiftlefter.runner.tag-disposition :as tagd]
   [shiftlefter.version :as version])
  (:gen-class))

;; -----------------------------------------------------------------------------
;; Path Resolution (WI-032.013)
;; -----------------------------------------------------------------------------
;; When sl is run from PATH, relative paths should resolve against the user's
;; working directory, not the project directory. The bin/sl script captures
;; the user's CWD in SL_USER_CWD before any cd operations.
;;
;; IMPORTANT FOR FUTURE PATH-BASED OPTIONS:
;; When adding new CLI options that take file/directory paths (e.g., glossaries,
;; interfaces, intent regions), use these helpers to resolve them:
;;
;;   (resolve-user-path path)      ; single path
;;   (resolve-user-paths paths)    ; multiple paths
;;
;; This ensures paths work correctly whether the user runs:
;;   - ./bin/sl from project root (relative to project)
;;   - sl from PATH in any directory (relative to user's CWD)
;;
;; See: run-cmd, fmt-cmd, ddmin-cmd for usage examples.
;;
;; `shiftlefter.paths/*user-cwd*` remains the daemon/request input seam. Command
;; path classes are resolved through `shiftlefter.project-context`.

(def cli-options
  [[nil "--check" "Check mode (verify without modifying)"]
   ["-w" "--write" "Format files in place"]
   [nil "--canonical" "Format to canonical style (stdout)"]
   [nil "--step-paths PATHS" "Step definition paths (comma-separated)"
    :parse-fn #(str/split % #",")]
   ["-c" "--config FILE" "Config file path (default: shiftlefter.edn)"]
   [nil "--tags TAGS" "Run only scenarios with any of these tags (comma-separated; repeatable; '@' optional)"
    :parse-fn tagd/parse-tag-list
    :validate [tagd/valid-tag-set? "must be one or more non-empty tags without whitespace"]
    :assoc-fn (fn [m k v] (update m k (fnil into #{}) v))]
   [nil "--skip-tags TAGS" "Skip scenarios with any of these tags (comma-separated; repeatable; exclude wins over --tags)"
    :parse-fn tagd/parse-tag-list
    :validate [tagd/valid-tag-set? "must be one or more non-empty tags without whitespace"]
    :assoc-fn (fn [m k v] (update m k (fnil into #{}) v))]
   [nil "--dry-run" "Bind steps without executing (verify binding only)"]
   [nil "--max-parallel N" "Run up to N scenarios concurrently (default 1; @serial and auto-serialized scenarios run alone)"
    :parse-fn #(Integer/parseInt %)
    :validate [pos-int? "must be a positive integer"]]
   ["-s" "--seed SEED" "Random seed for fuzz"
    :parse-fn #(Long/parseLong %)]
   ["-t" "--trials N" "Number of fuzz trials"
    :parse-fn #(Integer/parseInt %)]
   ["-p" "--preset PRESET" "Fuzz preset (smoke/quick/nightly)"
    :parse-fn keyword]
   [nil "--save PATH" "Directory to save fuzz failures"
    :default "fuzz/artifacts"]
   [nil "--mutation" "Enable mutation fuzzing mode (FZ3)"]
   [nil "--sources SOURCES" "Mutation source: generated, corpus, or both"
    :default "generated"]
   [nil "--corpus-dir PATH" "Path to corpus directory"
    :default "compliance/gherkin/testdata/good"]
   [nil "--timeout-ms MS" "Per-parse timeout in milliseconds"
    :parse-fn #(Integer/parseInt %)
    :default 200]
   [nil "--combos N" "Number of combo mutations per source"
    :parse-fn #(Integer/parseInt %)
    :default 1]
   [nil "--strategy STRATEGY" "ddmin strategy: structured or raw-lines"
    :default "structured"]
   [nil "--budget-ms MS" "Global time budget for ddmin"
    :parse-fn #(Integer/parseInt %)
    :default 30000]
   [nil "--ci" "CI mode: run full test suite (kaocha, compliance, fuzz smoke)"]
   [nil "--fuzzed" "Check fuzz artifact integrity (slow with many artifacts)"]
   [nil "--edn" "Output in EDN format (machine-readable)"]
   [nil "--junit-xml PATH" "Write JUnit XML results to PATH (see ERRATA E009)"]
   [nil "--html PATH" "Write a self-contained HTML run report to PATH"]
   [nil "--list" "List available items for commands that support listing"]
   [nil "--no-color" "Disable ANSI color output"]
   [nil "--mode MODE" "ddmin mode: parse, pickles, lex, or auto"]
   [nil "--nrepl" "Start nREPL server (for IDE integration)"]
   [nil "--port PORT" "nREPL port (default: auto-select)"
    :parse-fn #(Integer/parseInt %)]
   [nil "--chrome-path PATH" "Explicit Chrome binary path (sl costume init)"]
   ;; sl-rju: low-profile, deliberately absent from --help. SL_NO_DAEMON is the
   ;; "don't daemon at all" escape, so this never needs a disable sentinel.
   [nil "--idle-timeout-min N" "Daemon idle timeout in minutes (default: 60)"
    :parse-fn #(Integer/parseInt %)]
   ["-v" "--verbose" "Verbose output"]
   [nil "--version" "Print version"]
   ["-h" "--help"]])

(defn- normalize-cli-options [opts]
  (cond-> opts
    (:config opts) (assoc :config-path (:config opts))))

(defn- tag-filter-opt
  "Build the :tag-filter execute! opt from parsed --tags/--skip-tags sets,
   or nil when neither flag was given (sl-i608)."
  [opts]
  (when (or (seq (:tags opts)) (seq (:skip-tags opts)))
    (cond-> {}
      (seq (:tags opts)) (assoc :include (:tags opts))
      (seq (:skip-tags opts)) (assoc :exclude (:skip-tags opts)))))

(defn run-cmd
  "Run Gherkin scenarios.
   Arguments:
   - paths: space-separated paths/globs to feature files
   - opts: CLI options including :step-paths, :dry-run, :edn, :verbose

   Note: If --step-paths not specified, runner uses config or defaults to steps/"
  ([paths opts]
   (run-cmd paths opts (project-context/resolve
                        (cond-> {}
                          (:config-path opts) (assoc :config-path (:config-path opts))))))
  ([paths opts project-context]
   (let [path-list (if (string? paths) [paths] paths)
         result (runner/execute! (cond-> {:paths path-list
                                          :project-context project-context
                                          :dry-run (:dry-run opts)
                                          :edn (:edn opts)
                                          :verbose (:verbose opts)
                                          :no-color (:no-color opts)}
                                   (:step-paths opts) (assoc :step-paths (:step-paths opts))
                                   (:junit-xml opts) (assoc :junit-xml (:junit-xml opts))
                                   (:html opts) (assoc :html (:html opts))
                                   (:max-parallel opts) (assoc :max-parallel (:max-parallel opts))
                                   (tag-filter-opt opts) (assoc :tag-filter (tag-filter-opt opts))
                                   (:config-path opts) (assoc :config-path (:config-path opts))))]
     (:exit-code result))))


(defn- print-parse-errors
  "Print parse errors in standard diagnostic format."
  [path errors]
  (diag/print-errors path errors))

(defn- print-io-error
  "Print I/O error in a consistent format."
  [result]
  (println "ERROR:" (:message result)))

;; --- Multi-file validation helpers ---

(defn- find-feature-files
  "Given a seq of paths, return all .feature files.
   Files are returned as-is, directories are globbed recursively."
  [paths]
  (mapcat
   (fn [path]
     (cond
       (fs/directory? path)
       (map str (fs/glob path "**.feature"))

       (and (fs/exists? path) (str/ends-with? (str path) ".feature"))
       [(str path)]

       (fs/exists? path)
       [] ;; exists but not a .feature file - skip silently

       :else
       nil)) ;; doesn't exist - will be handled as error
   paths))

(defn- check-single-file
  "Check if a single file is in canonical format.
   Returns {:path :status (:ok/:error/:not-found) :reason? :details?}"
  [path]
  (let [read-result (io/read-file-utf8 path)]
    (if (= :error (:status read-result))
      {:path path :status :not-found :message (:message read-result)}
      (let [result (api/fmt-check (:content read-result))]
        (case (:status result)
          :ok {:path path :status :ok}
          :comments {:path path
                     :status :comments
                     :comments (:comments result)}
          :error {:path path
                  :status :error
                  :reason (:reason result)
                  :details (:details result)
                  :message (:message result)})))))

(defn- path-exists?
  "Check if a path exists (file or directory)."
  [path]
  (fs/exists? path))

(defn- check-files
  "Check multiple paths for roundtrip validity.
   Returns {:results [...] :valid N :invalid N :not-found N :exit-code N}"
  [paths]
  (let [;; First check for non-existent paths
        missing (remove path-exists? paths)
        _ (when (seq missing)
            (doseq [p missing]
              (println (str "Checking " p "... NOT FOUND"))))

        ;; Find all .feature files from existing paths
        existing-paths (filter path-exists? paths)
        files (find-feature-files existing-paths)

        ;; Check each file
        results (mapv check-single-file files)

        ;; Categorize
        valid (count (filter #(= :ok (:status %)) results))
        invalid (count (filter #(= :error (:status %)) results))
        with-comments (count (filter #(= :comments (:status %)) results))
        not-found (+ (count missing)
                     (count (filter #(= :not-found (:status %)) results)))

        total (+ valid invalid with-comments)]
    {:results results
     :valid valid
     :invalid invalid
     :with-comments with-comments
     :not-found not-found
     :total total
     ;; Comment-bearing files do NOT fail the check (sl-q2uj): --write
     ;; refuses to reformat them, so a red check would be unfixable.
     :exit-code (cond
                  (seq missing) 2              ;; path doesn't exist
                  (zero? total) 2              ;; no .feature files found
                  (pos? invalid) 1             ;; one or more invalid
                  :else 0)}))                  ;; all valid

(defn- comment-lines-phrase
  "Human phrase for lost-comment maps: '3 comment lines (10, 15, 18)'."
  [comments]
  (let [n (count comments)]
    (str n " comment line" (when (not= 1 n) "s")
         " (" (str/join ", " (map :line comments)) ")")))

(defn- print-check-result
  "Print the result of checking a single file."
  [{:keys [path status reason details message comments]}]
  (case status
    :ok (println (str "Checking " path "... OK"))
    :not-found (println (str "Checking " path "... NOT FOUND"))
    :comments (do
                (println (str "Checking " path "... CONTAINS COMMENTS"))
                (println (str "  canonical formatting is not yet comment-safe; "
                              (comment-lines-phrase comments)
                              " would be lost — skipped, not counted as a failure")))
    :error (do
             (println (str "Checking " path "... NEEDS FORMATTING"))
             (case reason
               :parse-errors (diag/print-errors-indented details)
               :needs-formatting nil  ;; message already says "NEEDS FORMATTING"
               (println (str "  " message))))))

(defn- print-check-summary
  "Print summary of check results."
  [{:keys [total valid invalid with-comments]}]
  (println)
  (println (str total " file" (when (not= 1 total) "s")
                " checked: " valid " valid, " invalid " invalid"
                (when (pos? with-comments)
                  (str ", " with-comments " with comments (skipped)")))))

;; --- In-place formatting helpers ---

(defn- format-single-file
  "Format a single file in place using canonical formatting.
   Returns {:path :status (:reformatted/:unchanged/:error/:not-found) :reason? :message?}"
  [path]
  (let [read-result (io/read-file-utf8 path)]
    (if (= :error (:status read-result))
      {:path path :status :not-found :message (:message read-result)}
      (let [original (:content read-result)
            result (api/fmt-canonical original)]
        (case (:status result)
          :ok
          (let [formatted (:output result)]
            (cond
              ;; sl-q2uj guard: never silently destroy comments — refuse
              ;; the write and leave the file untouched.
              (seq (:lost-comments result))
              {:path path
               :status :skipped-comments
               :comments (:lost-comments result)}

              (= original formatted)
              {:path path :status :unchanged}

              :else
              (do
                (spit path formatted)
                {:path path :status :reformatted})))

          :error
          {:path path
           :status :error
           :reason (:reason result)
           :message (:message result)
           :details (:details result)})))))

(defn- format-files
  "Format multiple paths in place.
   Returns {:results [...] :reformatted N :unchanged N :errors N :exit-code N}"
  [paths]
  (let [;; Check for non-existent paths
        missing (remove path-exists? paths)
        _ (when (seq missing)
            (doseq [p missing]
              (println (str "Formatting " p "... NOT FOUND"))))

        ;; Find all .feature files from existing paths
        existing-paths (filter path-exists? paths)
        files (find-feature-files existing-paths)

        ;; Format each file
        results (mapv format-single-file files)

        ;; Categorize
        reformatted (count (filter #(= :reformatted (:status %)) results))
        unchanged (count (filter #(= :unchanged (:status %)) results))
        skipped-comments (count (filter #(= :skipped-comments (:status %)) results))
        errors (count (filter #(= :error (:status %)) results))
        not-found (+ (count missing)
                     (count (filter #(= :not-found (:status %)) results)))

        total (+ reformatted unchanged skipped-comments errors)]
    {:results results
     :reformatted reformatted
     :unchanged unchanged
     :skipped-comments skipped-comments
     :errors errors
     :not-found not-found
     :total total
     ;; Refused comment-bearing files exit 0 (sl-q2uj): the skip is loud
     ;; but expected, so bulk `fmt --write` stays usable.
     :exit-code (cond
                  (seq missing) 2              ;; path doesn't exist
                  (zero? total) 2              ;; no .feature files found
                  (pos? errors) 1              ;; one or more had errors
                  :else 0)}))                  ;; all processed successfully

(defn- print-format-result
  "Print the result of formatting a single file."
  [{:keys [path status reason message details comments]}]
  (case status
    :reformatted (println (str "Formatting " path "... reformatted"))
    :unchanged (println (str "Formatting " path "... unchanged"))
    :not-found (println (str "Formatting " path "... NOT FOUND"))
    :skipped-comments
    (do
      (println (str "Formatting " path "... SKIPPED (contains comments)"))
      (println (str "  refusing to reformat: canonical formatting is not yet"
                    " comment-safe and would delete " (comment-lines-phrase comments)))
      (println "  file left untouched"))
    :error (do
             (println (str "Formatting " path "... ERROR"))
             (case reason
               :parse-errors (diag/print-errors-indented details)
               (println (str "  " message))))))

(defn- print-format-summary
  "Print summary of format results."
  [{:keys [total reformatted unchanged skipped-comments errors]}]
  (println)
  (println (str total " file" (when (not= 1 total) "s")
                " processed: " reformatted " reformatted, "
                unchanged " unchanged"
                (when (pos? skipped-comments)
                  (str ", " skipped-comments " skipped (comments)"))
                (when (pos? errors) (str ", " errors " error" (when (not= 1 errors) "s"))))))

(defn- format-check-results-edn
  "Format check-files results as EDN."
  [{:keys [results valid invalid with-comments total exit-code]}]
  {:status (if (zero? invalid) :ok :fail)
   :files (mapv (fn [{:keys [path status reason details comments]}]
                  (cond-> {:path path :status status}
                    reason (assoc :reason reason)
                    (seq comments) (assoc :comments comments)
                    (seq details) (assoc :errors (mapv diag/format-error-edn details))))
                results)
   :summary {:total total :valid valid :invalid invalid :with-comments with-comments}
   :exit-code exit-code})

(defn- format-format-results-edn
  "Format format-files results as EDN."
  [{:keys [results reformatted unchanged skipped-comments errors total exit-code]}]
  {:status (if (zero? errors) :ok :fail)
   :files (mapv (fn [{:keys [path status reason details comments]}]
                  (cond-> {:path path :status status}
                    reason (assoc :reason reason)
                    (seq comments) (assoc :comments comments)
                    (seq details) (assoc :errors (mapv diag/format-error-edn details))))
                results)
   :summary {:total total :reformatted reformatted :unchanged unchanged
             :skipped-comments skipped-comments :errors errors}
   :exit-code exit-code})

(defn fmt-cmd
  "Format command: verify roundtrip fidelity or reformat.
   With --check: verify file(s) roundtrip without modification.
   With --write: format file(s) in place.
   With --canonical: format to canonical style and print to stdout.
   With --edn: output results in EDN format.
   Returns exit code 0 for success, 1 for failure, 2 for no files/path error."
  ([paths opts]
   (fmt-cmd paths opts (project-context/resolve
                        (cond-> {}
                          (:config-path opts) (assoc :config-path (:config-path opts))))))
  ([paths {:keys [check write canonical edn]} project-context]
  ;; Resolve paths against user's CWD (for running from PATH)
  (let [resolved-paths (project-context/resolve-cli-paths project-context paths)]
    (cond
      ;; --check mode: verify roundtrip via API
      check
      (if (empty? resolved-paths)
        (do (when-not edn
              (println "Error: No paths specified")
              (println "Usage: sl fmt --check <path> [<path2> ...]"))
            (when edn
              (println (pr-str {:status :error :reason :no-paths})))
            2)
        (let [{:keys [exit-code] :as summary} (check-files resolved-paths)]
          (if edn
            (println (pr-str (format-check-results-edn summary)))
            (do
              (doseq [r (:results summary)]
                (print-check-result r))
              (when (> (count (:results summary)) 1)
                (print-check-summary summary))))
          exit-code))

      ;; --write mode: format files in place
      write
      (if (empty? resolved-paths)
        (do (when-not edn
              (println "Error: No paths specified")
              (println "Usage: sl fmt --write <path> [<path2> ...]"))
            (when edn
              (println (pr-str {:status :error :reason :no-paths})))
            2)
        (let [{:keys [exit-code] :as summary} (format-files resolved-paths)]
          (if edn
            (println (pr-str (format-format-results-edn summary)))
            (do
              (doseq [r (:results summary)]
                (print-format-result r))
              (when (> (count (:results summary)) 1)
                (print-format-summary summary))))
          exit-code))

      ;; --canonical mode: format via API and print (single file only)
      canonical
      (let [path (first resolved-paths)]
        (when (and (> (count resolved-paths) 1) (not edn))
          (println "Warning: --canonical only processes first file"))
        (if (nil? path)
          (do (if edn
                (println (pr-str {:status :error :reason :no-path}))
                (do (println "Error: No path specified")
                    (println "Usage: sl fmt --canonical <path>")))
              2)
          (let [read-result (io/read-file-utf8 path)]
            (if (= :error (:status read-result))
              (do (if edn
                    (println (pr-str {:status :error :reason :io-error :message (:message read-result)}))
                    (print-io-error read-result))
                  1)
              (let [result (api/fmt-canonical (:content read-result))]
                (case (:status result)
                  :ok
                  (do
                    ;; stdout output is not destructive, but warn on stderr so
                    ;; piped `--canonical > file` flows can't lose comments
                    ;; silently (sl-q2uj).
                    (when-let [lost (seq (:lost-comments result))]
                      (binding [*out* *err*]
                        (println (str "WARNING: canonical output drops "
                                      (comment-lines-phrase lost)
                                      " — canonical formatting is not yet comment-safe"))))
                    (if edn
                      (do (println (pr-str {:status :ok
                                            :output (:output result)}))
                          0)
                      (do (print (:output result)) (flush) 0)))

                  :error
                  (do (if edn
                        (println (pr-str {:status :error
                                          :reason (:reason result)
                                          :errors (mapv diag/format-error-edn (:details result))}))
                        (case (:reason result)
                          :parse-errors (print-parse-errors path (:details result))
                          (println "ERROR:" (:message result))))
                      1)))))))

      ;; No mode specified
      :else
      (do (println "Usage: sl fmt --check <path> [<path2> ...]   (verify roundtrip)")
          (println "       sl fmt --write <path> [<path2> ...]   (format in place)")
          (println "       sl fmt --canonical <path>             (format to stdout)")
          1)))))

(defn fuzz-cmd
  "Fuzz command: run randomized testing on parser.
   Returns exit code 0 for all pass, 1 for any failures."
  [{:keys [seed trials preset save verbose mutation sources corpus-dir timeout-ms combos]}]
  (if mutation
    ;; Mutation fuzzing mode (FZ3)
    (try
      (let [opts (cond-> {:verbose (boolean verbose)
                          :save save
                          :sources (keyword sources)
                          :corpus-dir corpus-dir
                          :timeout-ms timeout-ms
                          :combos combos}
                   seed (assoc :seed seed)
                   trials (assoc :trials trials)
                   preset (assoc :preset preset))
            result (fuzz/run-mutations opts)]
        (println)
        (println (format "Mutation fuzz complete: %d mutations (%d graceful, %d survived, %d timeout, %d exception)"
                         (:mutations/total result)
                         (:mutations/graceful result)
                         (:mutations/survived result)
                         (:mutations/timeout result)
                         (:mutations/exception result)))
        (println (format "  Unique signatures: %d, Artifacts saved: %d (seed=%d)"
                         (:signatures/unique result)
                         (:artifacts/saved result)
                         (:seed result)))
        (when (seq (:failures result))
          (println "Failures saved to:")
          (doseq [path (:failures result)]
            (println "  " path)))
        (if (= :ok (:status result)) 0 1))
      (catch clojure.lang.ExceptionInfo e
        (if (= :corpus-not-found (:reason (ex-data e)))
          (do
            (println "Error:" (ex-message e))
            2)
          (throw e))))

    ;; Valid generation mode (FZ1/FZ2)
    (let [opts (cond-> {:verbose (boolean verbose)
                        :save save}
                 seed (assoc :seed seed)
                 trials (assoc :trials trials)
                 preset (assoc :preset preset))
          result (fuzz/run opts)]
      (println)
      (println (str "Fuzz complete: " (:passed result) " passed, "
                    (:failed result) " failed"
                    " (seed=" (:seed result)
                    ", generator=" (:generator-version result) ")"))
      (when (seq (:failures result))
        (println "Failures saved to:")
        (doseq [path (:failures result)]
          (println "  " path)))
      (if (= :ok (:status result)) 0 1))))

(defn ddmin-cmd
  "Delta-debugging minimizer command.
   Returns exit code 0 for success, 1 for failure."
  ([path opts]
   (ddmin-cmd path opts (project-context/resolve
                         (cond-> {}
                           (:config-path opts) (assoc :config-path (:config-path opts))))))
  ([path {:keys [mode strategy timeout-ms budget-ms verbose]} project-context]
  ;; Resolve path against user's CWD (for running from PATH)
  (let [resolved-path (project-context/resolve-cli-path project-context path)
        ;; Check if path is a file or artifact directory
        is-artifact-dir? (and (fs/directory? resolved-path)
                              (fs/exists? (fs/path resolved-path "case.feature"))
                              (fs/exists? (fs/path resolved-path "result.edn")))

        ;; Build opts, filtering out nil values to let ddmin use defaults
        opts (cond-> {}
               mode (assoc :mode (keyword mode))
               strategy (assoc :strategy (keyword strategy))
               timeout-ms (assoc :timeout-ms timeout-ms)
               budget-ms (assoc :budget-ms budget-ms))]

    (try
      (if is-artifact-dir?
        ;; Run on fuzz artifact
        (let [result (ddmin/ddmin-artifact resolved-path opts)]
          (println (format "Minimized %s" resolved-path))
          (println (format "  Original: %d bytes -> Minimized: %d bytes (%.0f%% reduction)"
                           (count (:original result))
                           (count (:minimized result))
                           (* 100 (- 1 (:reduction-ratio result)))))
          (println (format "  Steps: %d, Removed: %d %s"
                           (:steps result)
                           (:removed result)
                           (if (= (:strategy result) :raw-lines) "lines" "units")))
          (println (format "  Output: %s" (:min-file result)))
          (if (:signatures-match? result) 0 1))

        ;; Run on single file
        (let [content (io/slurp-utf8 resolved-path)
              result (ddmin/ddmin content opts)]
          (if (= :no-failure (:reason (:baseline-sig result)))
            (do
              (println (format "Error: %s does not produce a failure" resolved-path))
              1)
            (do
              (println (format "Minimized %s" resolved-path))
              (println (format "  Original: %d bytes -> Minimized: %d bytes (%.0f%% reduction)"
                               (count (:original result))
                               (count (:minimized result))
                               (* 100 (- 1 (:reduction-ratio result)))))
              (println (format "  Steps: %d, Removed: %d %s"
                               (:steps result)
                               (:removed result)
                               (if (= (:strategy result) :raw-lines) "lines" "units")))
              (println (format "  Mode: %s, Strategy: %s"
                               (name (:mode result))
                               (name (:strategy result))))
              (when verbose
                (println)
                (println "Minimized content:")
                (println (:minimized result)))
              ;; Write output
              (let [out-path (str resolved-path ".min")]
                (spit out-path (:minimized result))
                (println (format "  Output: %s" out-path)))
              (if (:signatures-match? result) 0 1)))))

      (catch clojure.lang.ExceptionInfo e
        (println (format "Error: %s" (ex-message e)))
        1)

      (catch Exception e
        (println (format "Error: %s" (.getMessage e)))
        1)))))

(defn verify-cmd
  "Verify command: run validator and optionally CI checks.
   Returns exit code 0 for success, 1 for failures, 2 for verify errors or
   :skip (not in the framework repo — verify is a framework-dev tool; the
   un-runnable-invocation code, so scripting `sl verify` into a consumer
   project's CI can never pass silently, sl-5b18). In human mode the skip
   notice goes to stderr; --edn keeps the EDN result on stdout either way."
  [{:keys [ci fuzzed edn]}]
  (try
    (let [result (verify/run-checks {:ci ci :fuzzed fuzzed})]
      (if edn
        (println (verify/format-edn result))
        (if (= :skip (:status result))
          (binding [*out* *err*]
            (println (:message result)))
          (verify/print-human result)))
      (case (:status result)
        :ok 0
        :skip 2
        :fail 1))
    (catch Exception e
      (if edn
        (println (pr-str {:status :error :message (.getMessage e)}))
        (println (str "Verify error: " (.getMessage e))))
      2)))

;; -----------------------------------------------------------------------------
;; Costume Command (sl-rnm)
;; -----------------------------------------------------------------------------
;; Costumes are persistent, authenticated browser profiles. `init` launches the
;; costume's plain Chrome and leaves it open for a one-time human login; the
;; profile then persists so `sl run` can attach to it via a subject's :wears
;; binding ("bring your own authenticated session"). No flags, no anti-detection.

(defn- print-costume-error!
  "Print a costume command's structured error to stderr."
  [result]
  (binding [*out* *err*]
    (println (or (get-in result [:error :message]) "Costume command failed"))))

(def ^:private costume-usage
  (str "Usage: sl costume init <name> [--chrome-path PATH]\n"
       "       sl costume list\n"
       "       sl costume destroy <name>"))

(defn- costume-init-cmd
  [name-str {:keys [chrome-path]}]
  (if name-str
    (let [opts   (cond-> {} chrome-path (assoc :chrome-path chrome-path))
          result (costume/init-costume! (keyword name-str) opts)]
      (if (:error result)
        (do (print-costume-error! result) 1)
        (let [costume (:costume result)]
          (println (format "Costume :%s launched on port %d (pid %d) -- log in, then it's ready."
                           (name costume) (:port result) (:pid result)))
          (println (format "  wardrobe: %s" (str (fs/absolutize (wardrobe/costume-dir costume)))))
          0)))
    (do (println "Usage: sl costume init <name> [--chrome-path PATH]") 1)))

(defn- costume-list-cmd
  []
  (let [costumes (costume/list-costumes)]
    (if (empty? costumes)
      (println "No costumes. Create one with: sl costume init <name>")
      (doseq [c costumes]
        (println (format "  %-16s %-8s port %s  pid %s"
                         (str (:name c)) (name (:status c))
                         (or (:port c) "-") (or (:pid c) "-")))))
    0))

(defn- costume-destroy-cmd
  [name-str]
  (if name-str
    (let [result (costume/destroy-costume! (keyword name-str))]
      (if (:error result)
        (do (print-costume-error! result) 1)
        (do (println (format "Costume :%s destroyed." name-str)) 0)))
    (do (println "Usage: sl costume destroy <name>") 1)))

(defn costume-cmd
  "Manage costumes (sl-rnm). Dispatches on the subcommand (second arguments).
   Returns exit code 0 on success, 1 on error or usage."
  ([arguments options]
   (costume-cmd arguments options (project-context/resolve
                                   (cond-> {}
                                     (:config-path options) (assoc :config-path (:config-path options))))))
  ([arguments options project-context]
   (binding [wardrobe/*project-context* project-context]
     (let [name-str (nth arguments 2 nil)]
       (case (second arguments)
         "init"    (costume-init-cmd name-str options)
         "list"    (costume-list-cmd)
         "destroy" (costume-destroy-cmd name-str)
         (do (println costume-usage) 1))))))

;; -----------------------------------------------------------------------------
;; Daemon Command (sl-rju)
;; -----------------------------------------------------------------------------

(defn daemon-cmd
  "Warm-execution-path daemon controls (sl-rju). `serve` starts the long-lived
   JVM and BLOCKS until idle-reaped or stopped. `status`/`stop` are the wrapper's
   job (bin/sl, sl-x6r), not wired here.

   Runtime-requires shiftlefter.daemon (same lazy pattern as repl-cmd's nREPL
   require) so the cold path never loads the daemon ns. Returns an exit code."
  ([arguments options]
   (daemon-cmd arguments options (project-context/resolve
                                  (cond-> {}
                                    (:config-path options) (assoc :config-path (:config-path options))))))
  ([arguments options project-context]
  (case (second arguments)
    "serve" (do
              (require 'shiftlefter.daemon)
              ;; Anchor via instance-root, NOT :project-root — the two differ in
              ;; the no-config case (:defaults ⇒ cwd), where the wrapper waits at
              ;; the git toplevel instead. The rules must agree or every call
              ;; stalls cold and the daemon leaks unreachable (sl-v7l6).
              (let [root (project-context/instance-root project-context)]
                ((resolve 'shiftlefter.daemon/serve!)
                 (cond-> {}
                   root (assoc :root root)
                   (:idle-timeout-min options) (assoc :idle-timeout-min (:idle-timeout-min options))))))
    (do (binding [*out* *err*]
          (println "Usage: sl daemon serve [--idle-timeout-min N]"))
        1))))

;; -----------------------------------------------------------------------------
;; REPL Command (WI-033.017)
;; -----------------------------------------------------------------------------

(defn repl-cmd
  "Start an interactive REPL or nREPL server with ShiftLefter loaded.

   Modes:
   - Interactive (default): launches clojure.main/repl with ShiftLefter available
   - nREPL (--nrepl): starts nREPL server with CIDER middleware for IDE integration

   In nREPL mode, writes .nrepl-port file and blocks until interrupted.
   In interactive mode, runs until the user exits (Ctrl-D or (exit))."
  [{:keys [nrepl port]}]
  (if nrepl
    ;; nREPL server mode
    (let [nrepl-port (or port 0)]
      (require 'nrepl.server 'cider.nrepl)
      (let [start-server (resolve 'nrepl.server/start-server)
            default-handler (resolve 'nrepl.server/default-handler)
            ;; cider-middleware is a vector of middleware vars — spread with apply
            cider-mw-vec @(resolve 'cider.nrepl/cider-middleware)
            handler (apply default-handler cider-mw-vec)
            server (start-server :port nrepl-port :handler handler)
            actual-port (:port server)
            port-file (java.io.File. ".nrepl-port")]
        (spit port-file (str actual-port))
        (.deleteOnExit port-file)
        (println (str "nREPL server started on port " actual-port))
        (println "  .nrepl-port file written")
        (println)
        (println "Connect your IDE to this port, then try:")
        (println "  (require '[shiftlefter.repl :refer :all])")
        ;; Block until interrupted
        (try
          @(promise)
          (catch Exception _
            (.delete port-file)))))
    ;; Interactive REPL mode
    (do
      (println "ShiftLefter REPL")
      (println "Try: (require '[shiftlefter.repl :refer :all])")
      (println)
      (clojure.main/repl
       :init (fn []
               (in-ns 'user)
               (refer-clojure))))))

(defn dispatch
  "Run one CLI command and RETURN its exit code (0/1/2/3) without touching the
   JVM. Returns the sentinel `:repl` for the repl branch, which blocks and has
   no exit code — `-main` handles that.

   This is the single command-dispatch entry point shared by the cold CLI
   (`-main`) and the warm daemon (sl-rju). No branch here calls `System/exit`;
   the daemon serializes calls in one long-lived JVM, so exiting must stay in
   `-main`."
  [args]
  ;; Internal test hook (sl-7wv): deliberately throw so the standing warm-path
  ;; suite can assert crash->exit-3 parity across a real process boundary (cold
  ;; -main and warm dispatch! both catch Throwable -> 3). Checked on raw args
  ;; before parse-opts, which would otherwise reject it as an unknown option.
  ;; Undocumented in --help; mirrors the --sl-internal-stop convention.
  (when (some #{"--sl-internal-crash"} args)
    (throw (ex-info "sl-internal-crash: deliberate crash for exit-3 parity testing (sl-7wv)" {})))
  (let [parsed (parse-opts args cli-options)
        options (normalize-cli-options (:options parsed))
        arguments (:arguments parsed)
        errors (:errors parsed)
        needs-project-context? (not (or (seq errors)
                                        (:help options)
                                        (:version options)
                                        (= (first arguments) "agent-doc")))
        project-context (when needs-project-context?
                          (project-context/resolve
                           (cond-> {}
                             (:config-path options) (assoc :config-path (:config-path options)))))]
    (cond
      (seq errors)
      (do
        (binding [*out* *err*]
          (doseq [e errors] (println e))
          (println "\nUse --help for usage."))
        1)

      (:help options)
      (do
        (println "Usage:
  sl run <path> [<path2> ...] [--step-paths p1,p2] [-c FILE|--config FILE] [--tags t1,t2] [--skip-tags t1,t2] [--max-parallel N] [--dry-run] [--edn] [--junit-xml PATH] [--html PATH] [-v]
        (--junit-xml writes a CI-ingestible report; no file on planning error — see ERRATA E009)
        (--html writes a self-contained HTML run report; open it in any browser, no server needed)
        (--max-parallel N runs up to N scenarios concurrently; @serial-tagged and auto-serialized scenarios run alone)
  sl fmt --check <path> [<path2> ...]   (validate files/directories)
  sl fmt --write <path> [<path2> ...]   (format in place)
  sl fmt --canonical <path>             (format to stdout)
  sl repl [--nrepl] [--port PORT] [--clj]   (interactive REPL or nREPL server; --clj merges your deps.edn)
  sl agent-doc [topic]                  (print packaged agent doctrine)
  sl agent-doc --list                   (list packaged agent doctrine topics)
  sl orient [--edn] [-c FILE]           (project orientation; --edn dumps the full projection)
  sl gherkin fuzz [--preset smoke|quick|nightly] [--seed N] [--trials N] [-v]
  sl gherkin fuzz --mutation [--sources generated|corpus|both] [--timeout-ms N]
  sl gherkin ddmin <path> [--mode parse|pickles|lex|auto] [--strategy structured|raw-lines]
  sl verify [--ci] [--fuzzed] [--edn]   (validator checks, optionally full CI; framework-dev tool — exits 2 outside the framework repo)
  sl costume init <name> [--chrome-path PATH]   (launch a costume for one-time login)
  sl costume list                               (list costumes + status)
  sl costume destroy <name>                     (remove a costume)
  sl daemon serve [--idle-timeout-min N]        (start the warm-path daemon; normally auto-spawned)
  sl daemon status                              (show this project's daemon)
  sl daemon stop                                (stop this project's daemon)
  sl --version                                  (print version)")
        0)

      (:version options)
      (do
        (println (version/version))
        0)

      (= (first arguments) "run")
      (run-cmd (rest arguments) options project-context)

      (= (first arguments) "fmt")
      (fmt-cmd (rest arguments) options project-context)

      (and (= (first arguments) "gherkin")
           (= (second arguments) "fuzz"))
      (fuzz-cmd options)

      (and (= (first arguments) "gherkin")
           (= (second arguments) "ddmin"))
      (let [path (nth arguments 2 nil)]
        (if path
          (ddmin-cmd path options project-context)
          (do
            (println "Usage: sl gherkin ddmin <path>")
            1)))

      (= (first arguments) "verify")
      (verify-cmd options)

      (= (first arguments) "agent-doc")
      (agent-doc/agent-doc-cmd arguments options)

      (= (first arguments) "orient")
      (orient/orient-cmd arguments options project-context)

      (= (first arguments) "costume")
      (costume-cmd arguments options project-context)

      (= (first arguments) "daemon")
      (daemon-cmd arguments options project-context)

      (= (first arguments) "repl")
      :repl

      :else
      ;; Unknown command: usage/CLI error (sl-von). Hint → stderr (stdout stays
      ;; clean for machine consumers, matching orient/agent-doc discipline);
      ;; exit 2 per the locked contract (daemon.clj: 0=pass 1=fail 2=planning
      ;; 3=crash — the un-runnable-invocation code, as used for repl-rejection
      ;; and ambiguous config). Was exit 0 + stdout (pre-de-exit parity). Warm
      ;; parity is automatic: daemon/dispatch! delegates unknown commands here.
      (do
        (binding [*out* *err*]
          (println "Unknown command. Use --help"))
        2))))

(defn -main [& args]
  ;; Crash parity (sl-rju): an uncaught Throwable becomes exit 3, the locked
  ;; "crash" code — matching shiftlefter.daemon/dispatch!'s contract so cold and
  ;; warm agree on all four exit codes (0/1/2/3). The catch wraps only `dispatch`;
  ;; the :repl branch must stay outside it (repl-cmd blocks and owns the process).
  (let [result (try
                 (dispatch args)
                 (catch Throwable t
                   (.printStackTrace t)
                   3))]
    (if (= :repl result)
      ;; repl-cmd blocks (interactive REPL / nREPL server) and never returns an
      ;; exit code; let it own the process, exiting naturally (0) when done.
      (repl-cmd (:options (parse-opts args cli-options)))
      (System/exit result))))

;;(defn run [& args]
  ;;(apply -main args))
