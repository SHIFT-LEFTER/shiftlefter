(ns shiftlefter.core
  "Main CLI for ShiftLefter."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [shiftlefter.gherkin.api :as api]
   [shiftlefter.gherkin.ddmin :as ddmin]
   [shiftlefter.gherkin.diagnostics :as diag]
   [shiftlefter.gherkin.fuzz :as fuzz]
   [shiftlefter.gherkin.io :as io]
   [shiftlefter.gherkin.verify :as verify]
   [shiftlefter.runner.core :as runner])
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

(defn- get-user-cwd
  "Get the user's working directory from SL_USER_CWD env var.
   Falls back to current directory if not set (e.g., running via clj directly)."
  []
  (or (System/getenv "SL_USER_CWD")
      (System/getProperty "user.dir")))

(defn- resolve-user-path
  "Resolve a path relative to the user's working directory.
   Absolute paths are returned unchanged.
   Relative paths are resolved against SL_USER_CWD."
  [path]
  (if (fs/absolute? path)
    (str path)
    (str (fs/path (get-user-cwd) path))))

(defn- resolve-user-paths
  "Resolve multiple paths relative to the user's working directory."
  [paths]
  (mapv resolve-user-path paths))

(def cli-options
  [["-c" "--check" "Check mode (verify without modifying)"]
   ["-w" "--write" "Format files in place"]
   [nil "--canonical" "Format to canonical style (stdout)"]
   [nil "--step-paths PATHS" "Step definition paths (comma-separated)"
    :parse-fn #(str/split % #",")]
   [nil "--config-path PATH" "Config file path (default: shiftlefter.edn)"]
   [nil "--dry-run" "Bind steps without executing (verify binding only)"]
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
   ["-v" "--verbose" "Verbose output"]
   ["-h" "--help"]])

(defn run-cmd
  "Run Gherkin scenarios.
   Arguments:
   - paths: space-separated paths/globs to feature files
   - opts: CLI options including :step-paths, :dry-run, :edn, :verbose

   Note: If --step-paths not specified, runner uses config or defaults to steps/"
  [paths opts]
  (let [;; Parse paths (could be single path or multiple)
        path-list (if (string? paths) [paths] paths)
        ;; Resolve paths against user's CWD (for running from PATH)
        resolved-paths (resolve-user-paths path-list)
        resolved-step-paths (when (:step-paths opts)
                              (resolve-user-paths (:step-paths opts)))
        resolved-config-path (when (:config-path opts)
                               (resolve-user-path (:config-path opts)))
        ;; Pass step-paths only if CLI specified; let runner fall back to config
        result (runner/execute! (cond-> {:paths resolved-paths
                                         :dry-run (:dry-run opts)
                                         :edn (:edn opts)
                                         :verbose (:verbose opts)}
                                  resolved-step-paths (assoc :step-paths resolved-step-paths)
                                  resolved-config-path (assoc :config-path resolved-config-path)))]
    (:exit-code result)))


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
        not-found (+ (count missing)
                     (count (filter #(= :not-found (:status %)) results)))

        total (+ valid invalid)]
    {:results results
     :valid valid
     :invalid invalid
     :not-found not-found
     :total total
     :exit-code (cond
                  (seq missing) 2              ;; path doesn't exist
                  (zero? total) 2              ;; no .feature files found
                  (pos? invalid) 1             ;; one or more invalid
                  :else 0)}))                  ;; all valid

(defn- print-check-result
  "Print the result of checking a single file."
  [{:keys [path status reason details message]}]
  (case status
    :ok (println (str "Checking " path "... OK"))
    :not-found (println (str "Checking " path "... NOT FOUND"))
    :error (do
             (println (str "Checking " path "... NEEDS FORMATTING"))
             (case reason
               :parse-errors (diag/print-errors-indented details)
               :needs-formatting nil  ;; message already says "NEEDS FORMATTING"
               (println (str "  " message))))))

(defn- print-check-summary
  "Print summary of check results."
  [{:keys [total valid invalid]}]
  (println)
  (println (str total " file" (when (not= 1 total) "s")
                " checked: " valid " valid, " invalid " invalid")))

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
            (if (= original formatted)
              {:path path :status :unchanged}
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
        errors (count (filter #(= :error (:status %)) results))
        not-found (+ (count missing)
                     (count (filter #(= :not-found (:status %)) results)))

        total (+ reformatted unchanged errors)]
    {:results results
     :reformatted reformatted
     :unchanged unchanged
     :errors errors
     :not-found not-found
     :total total
     :exit-code (cond
                  (seq missing) 2              ;; path doesn't exist
                  (zero? total) 2              ;; no .feature files found
                  (pos? errors) 1              ;; one or more had errors
                  :else 0)}))                  ;; all processed successfully

(defn- print-format-result
  "Print the result of formatting a single file."
  [{:keys [path status reason message details]}]
  (case status
    :reformatted (println (str "Formatting " path "... reformatted"))
    :unchanged (println (str "Formatting " path "... unchanged"))
    :not-found (println (str "Formatting " path "... NOT FOUND"))
    :error (do
             (println (str "Formatting " path "... ERROR"))
             (case reason
               :parse-errors (diag/print-errors-indented details)
               (println (str "  " message))))))

(defn- print-format-summary
  "Print summary of format results."
  [{:keys [total reformatted unchanged errors]}]
  (println)
  (println (str total " file" (when (not= 1 total) "s")
                " processed: " reformatted " reformatted, "
                unchanged " unchanged"
                (when (pos? errors) (str ", " errors " error" (when (not= 1 errors) "s"))))))

(defn- format-check-results-edn
  "Format check-files results as EDN."
  [{:keys [results valid invalid total exit-code]}]
  {:status (if (zero? invalid) :ok :fail)
   :files (mapv (fn [{:keys [path status reason details]}]
                  (cond-> {:path path :status status}
                    reason (assoc :reason reason)
                    (seq details) (assoc :errors (mapv diag/format-error-edn details))))
                results)
   :summary {:total total :valid valid :invalid invalid}
   :exit-code exit-code})

(defn- format-format-results-edn
  "Format format-files results as EDN."
  [{:keys [results reformatted unchanged errors total exit-code]}]
  {:status (if (zero? errors) :ok :fail)
   :files (mapv (fn [{:keys [path status reason details]}]
                  (cond-> {:path path :status status}
                    reason (assoc :reason reason)
                    (seq details) (assoc :errors (mapv diag/format-error-edn details))))
                results)
   :summary {:total total :reformatted reformatted :unchanged unchanged :errors errors}
   :exit-code exit-code})

(defn fmt-cmd
  "Format command: verify roundtrip fidelity or reformat.
   With --check: verify file(s) roundtrip without modification.
   With --write: format file(s) in place.
   With --canonical: format to canonical style and print to stdout.
   With --edn: output results in EDN format.
   Returns exit code 0 for success, 1 for failure, 2 for no files/path error."
  [paths {:keys [check write canonical edn]}]
  ;; Resolve paths against user's CWD (for running from PATH)
  (let [resolved-paths (resolve-user-paths paths)]
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
                  (if edn
                    (do (println (pr-str {:status :ok :output (:output result)})) 0)
                    (do (print (:output result)) (flush) 0))

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
          1))))

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
  [path {:keys [mode strategy timeout-ms budget-ms verbose]}]
  ;; Resolve path against user's CWD (for running from PATH)
  (let [resolved-path (resolve-user-path path)
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
        (let [content (slurp resolved-path)
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
        1))))

(defn verify-cmd
  "Verify command: run validator and optionally CI checks.
   Returns exit code 0 for success/skip, 1 for failures, 2 for verify errors."
  [{:keys [ci fuzzed edn]}]
  (try
    (let [result (verify/run-checks {:ci ci :fuzzed fuzzed})]
      (if edn
        (println (verify/format-edn result))
        (if (= :skip (:status result))
          (println (:message result))
          (verify/print-human result)))
      (case (:status result)
        :ok 0
        :skip 0
        :fail 1))
    (catch Exception e
      (if edn
        (println (pr-str {:status :error :message (.getMessage e)}))
        (println (str "Verify error: " (.getMessage e))))
      2)))

(defn -main [& args]
  (let [parsed (parse-opts args cli-options)
        options (:options parsed)
        arguments (:arguments parsed)]
    (cond
      (:help options)
      (println "Usage:
  sl run <path> [<path2> ...] [--step-paths p1,p2] [--config-path FILE] [--dry-run] [--edn] [-v]
  sl fmt --check <path> [<path2> ...]   (validate files/directories)
  sl fmt --write <path> [<path2> ...]   (format in place)
  sl fmt --canonical <path>             (format to stdout)
  sl repl [--nrepl] [--port PORT]       (interactive REPL, requires clj)
  sl gherkin fuzz [--preset smoke|quick|nightly] [--seed N] [--trials N] [-v]
  sl gherkin fuzz --mutation [--sources generated|corpus|both] [--timeout-ms N]
  sl gherkin ddmin <path> [--mode parse|pickles|lex|auto] [--strategy structured|raw-lines]
  sl verify [--ci] [--fuzzed] [--edn]   (validator checks, optionally full CI)")

      (= (first arguments) "run")
      (let [paths (rest arguments)]
        (System/exit (run-cmd paths options)))

      (= (first arguments) "fmt")
      (let [paths (rest arguments)]
        (System/exit (fmt-cmd paths options)))

      (and (= (first arguments) "gherkin")
           (= (second arguments) "fuzz"))
      (System/exit (fuzz-cmd options))

      (and (= (first arguments) "gherkin")
           (= (second arguments) "ddmin"))
      (let [path (nth arguments 2 nil)]
        (if path
          (System/exit (ddmin-cmd path options))
          (do
            (println "Usage: sl gherkin ddmin <path>")
            (System/exit 1))))

      (= (first arguments) "verify")
      (System/exit (verify-cmd options))

      :else
      (println "Unknown command. Use --help"))))

;;(defn run [& args]
  ;;(apply -main args))