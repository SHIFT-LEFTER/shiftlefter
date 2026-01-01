(ns shiftlefter.core
  "Main CLI for ShiftLefter."
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :refer [<! chan go]]
   [clojure.tools.cli :refer [parse-opts]]
   [shiftlefter.gherkin.api :as api]
   [shiftlefter.gherkin.fuzz :as fuzz]
   [shiftlefter.gherkin.io :as io]
   [shiftlefter.runner :as runner])
  (:gen-class))

(def cli-options
  [["-m" "--mode MODE" "Execution mode (vanilla)" :default "vanilla"]
   ["-c" "--check" "Check mode (verify without modifying)"]
   [nil "--canonical" "Format to canonical style"]
   ["-s" "--seed SEED" "Random seed for fuzz"
    :parse-fn #(Long/parseLong %)]
   ["-t" "--trials N" "Number of fuzz trials"
    :parse-fn #(Integer/parseInt %)]
   ["-p" "--preset PRESET" "Fuzz preset (smoke/quick/nightly)"
    :parse-fn keyword]
   [nil "--save PATH" "Directory to save fuzz failures"
    :default "fuzz/artifacts"]
   ["-v" "--verbose" "Verbose output"]
   ["-h" "--help"]])

(defn run-cmd [paths mode]
  (when-not (= mode "vanilla")
    (println "Only vanilla mode supported")
    (System/exit 1))
  (let [event-chan (chan 100)]
    ;; Define toy steps
    (runner/defstep #"I am on the login page" [] (runner/see "login page" :pass))
    (runner/defstep #"I type \"([^\"]+)\" into \"([^\"]+)\"" [value field] (runner/mock-type field value :pass))
    (runner/defstep #"I click \"([^\"]+)\"" [element] (runner/click element :pass))
    (runner/defstep #"I see \"([^\"]+)\"" [text] (runner/see text :pass))
    ;; Glob files and parse via API facade
    (let [files (fs/glob "." (str paths "/**/*.feature"))
          all-pickles (flatten
                       (for [f files]
                         (let [content (io/slurp-utf8 (str (fs/file f)))
                               {:keys [ast]} (api/parse-string content)
                               {:keys [pickles]} (api/pickles ast (str f))]
                           pickles)))]
      ;; Consume events
      (go (loop []
            (when-let [event (<! event-chan)]
              (println "Event:" event)
              (recur))))
      ;; Exec
      (let [results (runner/exec all-pickles event-chan)]
        (runner/report results)))))


(defn- print-parse-errors
  "Print parse errors in a consistent format."
  [path errors]
  (println "ERROR: Parse errors in" path)
  (doseq [e errors]
    (println "  -" (:type e) "at line" (get-in e [:location :line]) "-" (:message e))))

(defn- print-io-error
  "Print I/O error in a consistent format."
  [result]
  (println "ERROR:" (:message result)))

(defn fmt-cmd
  "Format command: verify roundtrip fidelity or reformat.
   With --check: verify file roundtrips without modification.
   With --canonical: format to canonical style and print to stdout.
   Returns exit code 0 for success, 1 for failure."
  [path {:keys [check canonical]}]
  (cond
    ;; --check mode: verify roundtrip via API
    check
    (let [read-result (io/read-file-utf8 path)]
      (if (= :error (:status read-result))
        (do (print-io-error read-result) 1)
        (let [result (api/fmt-check (:content read-result))]
          (case (:status result)
            :ok
            (do (println "OK:" path) 0)

            :error
            (do (case (:reason result)
                  :parse-errors (print-parse-errors path (:details result))
                  :mismatch (println "ERROR: Roundtrip mismatch in" path
                                     "(original:" (:original-length result)
                                     "bytes, reconstructed:" (:reconstructed-length result) "bytes)"))
                1)))))

    ;; --canonical mode: format via API and print
    canonical
    (let [read-result (io/read-file-utf8 path)]
      (if (= :error (:status read-result))
        (do (print-io-error read-result) 1)
        (let [result (api/fmt-canonical (:content read-result))]
          (case (:status result)
            :ok
            (do (print (:output result)) (flush) 0)

            :error
            (do (case (:reason result)
                  :parse-errors (print-parse-errors path (:details result))
                  :canonical/rules-unsupported (println "ERROR:" (:message result))
                  (println "ERROR:" (:message result)))
                1)))))

    ;; No mode specified
    :else
    (do (println "Usage: sl fmt --check <path>   (verify roundtrip)")
        (println "       sl fmt --canonical <path>  (format to stdout)")
        1)))

(defn fuzz-cmd
  "Fuzz command: run randomized testing on parser.
   Returns exit code 0 for all pass, 1 for any failures."
  [{:keys [seed trials preset save verbose]}]
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
    (if (= :ok (:status result)) 0 1)))

(defn -main [& args]
  (let [parsed (parse-opts args cli-options)
        options (:options parsed)
        arguments (:arguments parsed)]
    (cond
      (:help options)
      (println "Usage:
  sl run <paths> [--mode vanilla]
  sl fmt --check <path>
  sl fmt --canonical <path>
  sl gherkin fuzz [--preset smoke|quick|nightly] [--seed N] [--trials N] [-v]")

      (= (first arguments) "run")
      (let [paths (second arguments)]
        (run-cmd paths (:mode options)))

      (= (first arguments) "fmt")
      (let [path (second arguments)]
        (System/exit (fmt-cmd path options)))

      (and (= (first arguments) "gherkin")
           (= (second arguments) "fuzz"))
      (System/exit (fuzz-cmd options))

      :else
      (println "Unknown command. Use --help"))))

;;(defn run [& args]
  ;;(apply -main args))