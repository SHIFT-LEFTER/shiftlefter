(ns shiftlefter.gherkin.verify
  "Validator and CI trust checks for ShiftLefter.

   Three modes:
   - Default (validator-only): Fast checks for local dev
   - --ci: Full umbrella including kaocha, compliance, fuzz smoke
   - --fuzzed: Include fuzz artifact integrity checks (can be slow)

   Usage:
     (run-checks {})                    ; validator-only (fast)
     (run-checks {:ci true})            ; full CI mode
     (run-checks {:fuzzed true})        ; include artifact checks"
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as shell]
            [shiftlefter.gherkin.api :as api]
            [shiftlefter.gherkin.io :as io]
            ;; Required for reading tagged literals in result.edn
            [shiftlefter.gherkin.location]
            [shiftlefter.gherkin.tokens]))

;; -----------------------------------------------------------------------------
;; Check result structure
;; -----------------------------------------------------------------------------

(defn- make-check
  "Create a check result map."
  [id status & {:keys [message details]}]
  (cond-> {:id id :status status}
    message (assoc :message message)
    details (assoc :details details)))

(defn- check-passed? [{:keys [status]}]
  (= :ok status))

;; -----------------------------------------------------------------------------
;; Validator-only checks
;; -----------------------------------------------------------------------------

(defn check-cli-wiring
  "Check that bin/sl exists, is executable, and --help exits 0."
  []
  (let [sl-path "bin/sl"]
    (cond
      (not (fs/exists? sl-path))
      (make-check :cli-wiring :fail
                  :message "bin/sl does not exist")

      (not (fs/executable? sl-path))
      (make-check :cli-wiring :fail
                  :message "bin/sl is not executable")

      :else
      (let [{:keys [exit]} (shell/sh "bin/sl" "--help")]
        (if (zero? exit)
          (make-check :cli-wiring :ok
                      :message "bin/sl --help exits 0")
          (make-check :cli-wiring :fail
                      :message (str "bin/sl --help exited with code " exit)))))))

(defn check-smoke-parse
  "Check that a smoke fixture parses successfully."
  [path]
  (let [read-result (io/read-file-utf8 path)]
    (if (= :error (:status read-result))
      (make-check :smoke-parse :fail
                  :message (str "Cannot read " path)
                  :details {:path path :error (:message read-result)})
      (let [{:keys [errors]} (api/parse-string (:content read-result))]
        (if (empty? errors)
          (make-check :smoke-parse :ok
                      :message (str "Parse OK: " path))
          (make-check :smoke-parse :fail
                      :message (str "Parse failed: " path)
                      :details {:path path :errors errors}))))))

(defn check-smoke-fmt
  "Check that a smoke fixture is in canonical format (fmt --check)."
  [path]
  (let [read-result (io/read-file-utf8 path)]
    (if (= :error (:status read-result))
      (make-check :smoke-fmt :fail
                  :message (str "Cannot read " path)
                  :details {:path path :error (:message read-result)})
      (let [result (api/fmt-check (:content read-result))]
        (if (= :ok (:status result))
          (make-check :smoke-fmt :ok
                      :message (str "Canonical format OK: " path))
          (make-check :smoke-fmt :fail
                      :message (str "Needs formatting: " path)
                      :details {:path path
                                :reason (:reason result)}))))))

(defn check-smoke-roundtrip
  "Check that a smoke fixture passes api/roundtrip-ok?."
  [path]
  (let [read-result (io/read-file-utf8 path)]
    (if (= :error (:status read-result))
      (make-check :smoke-roundtrip :fail
                  :message (str "Cannot read " path)
                  :details {:path path :error (:message read-result)})
      (if (api/roundtrip-ok? (:content read-result))
        (make-check :smoke-roundtrip :ok
                    :message (str "Roundtrip OK: " path))
        (make-check :smoke-roundtrip :fail
                    :message (str "Roundtrip mismatch: " path)
                    :details {:path path})))))

(defn- validate-edn-file
  "Read and validate an EDN file has required keys.
   Uses read-string (not edn/read-string) to handle tagged literals like records."
  [path required-keys]
  (try
    (let [content (read-string (slurp path))
          missing (remove #(contains? content %) required-keys)]
      (if (empty? missing)
        {:valid true :content content}
        {:valid false :missing missing}))
    (catch Exception e
      {:valid false :error (.getMessage e)})))

(defn check-artifact-integrity
  "Check integrity of a single fuzz artifact directory."
  [artifact-dir]
  (let [dir-name (fs/file-name artifact-dir)
        case-file (str artifact-dir "/case.feature")
        meta-file (str artifact-dir "/meta.edn")
        result-file (str artifact-dir "/result.edn")
        min-file (str artifact-dir "/min.feature")
        ddmin-file (str artifact-dir "/ddmin.edn")]

    (cond
      ;; Check required files exist
      (not (fs/exists? case-file))
      (make-check :artifact-integrity :fail
                  :message (str "Missing case.feature in " dir-name)
                  :details {:artifact artifact-dir})

      (not (fs/exists? meta-file))
      (make-check :artifact-integrity :fail
                  :message (str "Missing meta.edn in " dir-name)
                  :details {:artifact artifact-dir})

      (not (fs/exists? result-file))
      (make-check :artifact-integrity :fail
                  :message (str "Missing result.edn in " dir-name)
                  :details {:artifact artifact-dir})

      :else
      ;; Validate meta.edn
      (let [meta-result (validate-edn-file meta-file
                                           [:seed :trial-idx :generator-version :timestamp :opts])]
        (cond
          (not (:valid meta-result))
          (make-check :artifact-integrity :fail
                      :message (str "Invalid meta.edn in " dir-name)
                      :details {:artifact artifact-dir
                                :missing (:missing meta-result)
                                :error (:error meta-result)})

          ;; Validate result.edn
          :else
          (let [result-result (validate-edn-file result-file [:status :reason])]
            (cond
              (not (:valid result-result))
              (make-check :artifact-integrity :fail
                          :message (str "Invalid result.edn in " dir-name)
                          :details {:artifact artifact-dir
                                    :missing (:missing result-result)
                                    :error (:error result-result)})

              ;; If min.feature exists, ddmin.edn must also exist
              (and (fs/exists? min-file) (not (fs/exists? ddmin-file)))
              (make-check :artifact-integrity :fail
                          :message (str "min.feature exists but ddmin.edn missing in " dir-name)
                          :details {:artifact artifact-dir})

              ;; If ddmin.edn exists, validate it
              (fs/exists? ddmin-file)
              (let [ddmin-result (validate-edn-file ddmin-file [:baseline-sig :signatures-match?])]
                (if (:valid ddmin-result)
                  (make-check :artifact-integrity :ok
                              :message (str "Artifact OK: " dir-name))
                  (make-check :artifact-integrity :fail
                              :message (str "Invalid ddmin.edn in " dir-name)
                              :details {:artifact artifact-dir
                                        :missing (:missing ddmin-result)
                                        :error (:error ddmin-result)})))

              :else
              (make-check :artifact-integrity :ok
                          :message (str "Artifact OK: " dir-name)))))))))

(defn check-all-artifacts
  "Check all fuzz artifacts if the directory exists."
  []
  (let [artifacts-dir "fuzz/artifacts"]
    (if (not (fs/exists? artifacts-dir))
      [] ;; No artifacts directory - skip silently
      (let [artifact-dirs (->> (fs/list-dir artifacts-dir)
                               (filter fs/directory?)
                               (map str))]
        (if (empty? artifact-dirs)
          [] ;; Empty directory - skip silently
          (mapv check-artifact-integrity artifact-dirs))))))

;; -----------------------------------------------------------------------------
;; CI mode checks
;; -----------------------------------------------------------------------------

(defn run-external-command
  "Run an external command and return check result."
  [id cmd args description]
  (try
    (let [{:keys [exit err]} (apply shell/sh cmd args)]
      (if (zero? exit)
        (make-check id :ok
                    :message (str description " passed"))
        (make-check id :fail
                    :message (str description " failed (exit " exit ")")
                    :details {:exit exit :stderr (when (seq err) err)})))
    (catch Exception e
      (make-check id :fail
                  :message (str description " error: " (.getMessage e))))))

(defn check-kaocha
  "Run ./bin/kaocha and check it passes."
  []
  (run-external-command :kaocha "./bin/kaocha" [] "Kaocha tests"))

(defn check-compliance
  "Run ./bin/compliance and check it passes."
  []
  (run-external-command :compliance "./bin/compliance" [] "Compliance suite"))

(defn check-fuzz-smoke
  "Run sl gherkin fuzz --preset smoke and check it passes."
  []
  (run-external-command :fuzz-smoke "bin/sl" ["gherkin" "fuzz" "--preset" "smoke"]
                        "Fuzz smoke test"))

;; -----------------------------------------------------------------------------
;; Main runner
;; -----------------------------------------------------------------------------

(def smoke-fixtures
  "List of smoke fixture paths to validate."
  ["examples/01-validate-and-format/login.feature"])

(defn run-validator-checks
  "Run validator-only checks (fast, default mode).
   Options:
   - :fuzzed - Include fuzz artifact integrity checks (can be slow)"
  [{:keys [fuzzed]}]
  (let [checks (atom [])]
    ;; CLI wiring
    (swap! checks conj (check-cli-wiring))

    ;; Smoke fixtures
    (doseq [path smoke-fixtures]
      (when (fs/exists? path)
        (swap! checks conj (check-smoke-parse path))
        (swap! checks conj (check-smoke-fmt path))))

    ;; Artifact integrity (only when --fuzzed flag is set)
    (when fuzzed
      (swap! checks into (check-all-artifacts)))

    @checks))

(defn run-ci-checks
  "Run CI mode checks (includes kaocha, compliance, fuzz)."
  []
  [(check-kaocha)
   (check-compliance)
   (check-fuzz-smoke)])

(defn in-project-context?
  "Check if we're running from the ShiftLefter project directory."
  []
  (and (fs/exists? "bin/sl")
       (fs/exists? "deps.edn")))

(defn run-checks
  "Run all verification checks.
   Options:
   - :ci - Include CI mode checks (kaocha, compliance, fuzz smoke)
   - :fuzzed - Include fuzz artifact integrity checks (can be slow)

   Returns:
   {:status :ok/:fail/:skip
    :checks [...]
    :failures [...]
    :summary {:total N :passed N :failed N}}"
  [{:keys [ci fuzzed]}]
  (if-not (in-project-context?)
    {:status :skip
     :message "Not in ShiftLefter project directory. sl verify is a development tool."
     :checks []
     :failures []
     :summary {:total 0 :passed 0 :failed 0}}
    (let [validator-checks (run-validator-checks {:fuzzed fuzzed})
          ci-checks (when ci (run-ci-checks))
          all-checks (vec (concat validator-checks ci-checks))
          failures (filterv #(not (check-passed? %)) all-checks)
          passed (count (filter check-passed? all-checks))
          failed (count failures)]
      {:status (if (empty? failures) :ok :fail)
       :checks all-checks
       :failures failures
       :summary {:total (count all-checks)
                 :passed passed
                 :failed failed}})))

;; -----------------------------------------------------------------------------
;; Output formatting
;; -----------------------------------------------------------------------------

(defn format-check-human
  "Format a single check result for human output."
  [{:keys [id status message]}]
  (let [status-str (case status
                     :ok "OK"
                     :fail "FAIL")]
    (format "  [%s] %s: %s" status-str (name id) (or message ""))))

(defn print-human
  "Print results in human-readable format."
  [{:keys [status checks summary]}]
  (println "ShiftLefter Verify")
  (println (str (apply str (repeat 40 "-"))))
  (println)

  (doseq [check checks]
    (println (format-check-human check)))

  (println)
  (println (str (apply str (repeat 40 "-"))))
  (println (format "Result: %s (%d passed, %d failed)"
                   (if (= :ok status) "OK" "FAIL")
                   (:passed summary)
                   (:failed summary))))

(defn format-edn
  "Format results as EDN."
  [result]
  (pr-str result))
