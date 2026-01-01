(ns shiftlefter.gherkin.fuzz
  "Fuzz testing harness for Gherkin parser.

   Generates random Gherkin files, runs parse+pickle, catches failures.
   Deterministic given seed + generator-version.

   Usage:
     (run {:seed 12345 :trials 100})
     (run {:preset :smoke})  ; 10 trials, random seed
     (run {:preset :quick})  ; 100 trials
     (run {:preset :nightly}) ; 10000 trials

   FZ2 adds test.check generators with shrinking for:
   - Tags, tables, docstrings, scenario outlines"
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [shiftlefter.gherkin.api :as api])
  (:import [java.util Random]
           [java.time Instant]))

;; -----------------------------------------------------------------------------
;; Generator Version (bump when generator logic changes)
;; -----------------------------------------------------------------------------

(def generator-version
  "Bump this when generator logic changes to preserve reproducibility.
   Format: [major minor] where major = breaking change, minor = additions."
  [2 0])  ; FZ2: Added tags, tables, docstrings, outlines

;; -----------------------------------------------------------------------------
;; Presets
;; -----------------------------------------------------------------------------

(def presets
  {:smoke   {:trials 10}
   :quick   {:trials 100}
   :nightly {:trials 10000}})

;; -----------------------------------------------------------------------------
;; Random Gherkin Generator (minimal for FZ1, expanded in FZ2)
;; -----------------------------------------------------------------------------

;; Valid Gherkin step keywords only
(def keywords-given ["Given"])
(def keywords-when ["When"])
(def keywords-then ["Then"])
(def keywords-conjunction ["And" "But" "*"])

(defn- rand-nth-seeded
  "Pick random element from coll using Random instance."
  [^Random rng coll]
  (nth coll (.nextInt rng (count coll))))

(defn- rand-int-seeded
  "Random int in [0, n) using Random instance."
  [^Random rng n]
  (.nextInt rng n))

(defn- rand-string
  "Generate random alphanumeric string of given length."
  [^Random rng len]
  (let [chars "abcdefghijklmnopqrstuvwxyz0123456789"]
    (apply str (repeatedly len #(nth chars (rand-int-seeded rng (count chars)))))))

(defn- generate-step
  "Generate a random step line."
  [^Random rng keyword-type step-idx]
  (let [;; First step of each type uses the keyword, subsequent use And/But/*
        keywords (if (zero? step-idx)
                   (case keyword-type
                     :given keywords-given
                     :when keywords-when
                     :then keywords-then)
                   keywords-conjunction)
        kw (rand-nth-seeded rng keywords)
        action (rand-string rng (+ 5 (rand-int-seeded rng 20)))]
    (str "    " kw " " action)))

(defn- generate-scenario
  "Generate a random scenario with 1-5 steps.
   Uses Given/When/Then pattern with And/But for continuations."
  [^Random rng idx]
  (let [name (str "Scenario " idx " " (rand-string rng 8))
        num-steps (+ 1 (rand-int-seeded rng 5))
        ;; Generate steps: Given, When, Then pattern
        steps (for [i (range num-steps)]
                (let [keyword-type (case (mod i 3)
                                     0 :given
                                     1 :when
                                     2 :then)
                      ;; step-idx within that keyword type for determining And/But
                      type-idx (quot i 3)]
                  (generate-step rng keyword-type type-idx)))]
    (str "  Scenario: " name "\n"
         (str/join "\n" steps))))

(defn generate-feature
  "Generate a random feature file.
   Returns {:content string :meta map}."
  [^Random rng trial-idx]
  (let [feature-name (str "Feature " trial-idx " " (rand-string rng 10))
        num-scenarios (+ 1 (rand-int-seeded rng 3))
        scenarios (for [i (range num-scenarios)]
                    (generate-scenario rng i))
        content (str "Feature: " feature-name "\n\n"
                     (str/join "\n\n" scenarios) "\n")]
    {:content content
     :meta {:trial-idx trial-idx
            :num-scenarios num-scenarios}}))

;; -----------------------------------------------------------------------------
;; test.check Generators (FZ2: with shrinking support)
;; -----------------------------------------------------------------------------

(def gen-identifier
  "Generator for valid Gherkin identifiers (alphanumeric, no spaces)."
  (gen/fmap #(apply str %)
            (gen/vector (gen/elements "abcdefghijklmnopqrstuvwxyz0123456789") 3 15)))

(def gen-text
  "Generator for step/description text (alphanumeric with spaces)."
  (gen/fmap #(str/join " " %)
            (gen/vector gen-identifier 1 5)))

(def gen-tag
  "Generator for a single tag."
  (gen/fmap #(str "@" %) gen-identifier))

(def gen-tags
  "Generator for a tag line (0-4 tags)."
  (gen/fmap #(when (seq %) (str (str/join " " %) "\n"))
            (gen/vector gen-tag 0 4)))

(def gen-table-cell
  "Generator for a table cell value."
  (gen/one-of [gen-identifier
               (gen/return "")
               (gen/fmap str (gen/choose 0 999))]))

(defn gen-table-row-with-count
  "Generator for a table row with exactly n cells."
  [n]
  (gen/fmap #(str "      | " (str/join " | " %) " |")
            (gen/vector gen-table-cell n)))

(def gen-table-row
  "Generator for a table row (2-4 cells). For standalone testing."
  (gen/let [n (gen/choose 2 4)
            cells (gen/vector gen-table-cell n)]
    (str "      | " (str/join " | " cells) " |")))

(def gen-data-table
  "Generator for a data table (1-3 rows, or nil for no table).
   All rows have consistent cell count."
  (gen/one-of
   [(gen/return nil)
    (gen/return nil)  ; 2/3 chance of no table
    (gen/let [num-cols (gen/choose 2 4)
              rows (gen/vector (gen-table-row-with-count num-cols) 1 3)]
      (str/join "\n" rows))]))

(def gen-docstring
  "Generator for a docstring (or nil for no docstring)."
  (gen/one-of
   [(gen/return nil)
    (gen/return nil)  ; 2/3 chance of no docstring
    (gen/fmap #(str "      \"\"\"\n      " % "\n      \"\"\"")
              gen-text)]))

(def gen-step-keyword
  "Generator for step keywords."
  (gen/elements ["Given" "When" "Then" "And" "But" "*"]))

(def gen-step
  "Generator for a complete step with optional table or docstring."
  (gen/let [kw gen-step-keyword
            text gen-text
            arg (gen/one-of [gen-data-table gen-docstring])]
    (str "    " kw " " text
         (when arg (str "\n" arg)))))

(def gen-scenario
  "Generator for a scenario with tags and steps."
  (gen/let [tags gen-tags
            name gen-identifier
            steps (gen/vector gen-step 1 5)]
    (str (or tags "")
         "  Scenario: " name "\n"
         (str/join "\n" steps))))

(defn gen-example-row-with-count
  "Generator for an Examples table row with exactly n cells."
  [n]
  (gen/fmap #(str "      | " (str/join " | " %) " |")
            (gen/vector gen-identifier n)))

(def gen-examples
  "Generator for an Examples block with consistent cell counts."
  (gen/let [num-cols (gen/choose 2 3)
            header (gen-example-row-with-count num-cols)
            rows (gen/vector (gen-example-row-with-count num-cols) 1 3)]
    (str "    Examples:\n" header "\n" (str/join "\n" rows))))

(def gen-scenario-outline
  "Generator for a scenario outline with examples."
  (gen/let [tags gen-tags
            name gen-identifier
            steps (gen/vector gen-step 1 3)
            examples gen-examples]
    (str (or tags "")
         "  Scenario Outline: " name "\n"
         (str/join "\n" steps) "\n\n"
         examples)))

(def gen-scenario-or-outline
  "Generator for either a scenario or scenario outline."
  (gen/one-of [gen-scenario
               gen-scenario
               gen-scenario-outline]))  ; 2/3 scenarios, 1/3 outlines

(def gen-feature
  "Generator for a complete feature file."
  (gen/let [tags gen-tags
            name gen-identifier
            scenarios (gen/vector gen-scenario-or-outline 1 3)]
    (str (or tags "")
         "Feature: " name "\n\n"
         (str/join "\n\n" scenarios) "\n")))

(defn generate-from-gen
  "Generate a value from a test.check generator with given seed and size."
  [g seed size]
  (gen/generate g size seed))

;; -----------------------------------------------------------------------------
;; Invariant Checks
;; -----------------------------------------------------------------------------

(defn- check-invariants
  "Run parse + pickle and check invariants.
   Returns {:status :ok} or {:status :fail :reason kw :details map}.

   Invariants checked:
   1. Parse succeeds (no errors)
   2. Pickle succeeds (no errors)
   3. Lossless roundtrip (print-tokens(lex(content)) == content)
   4. Canonical formatting is stable (format(format(x)) == format(x))"
  [content]
  (try
    ;; Parse
    (let [{:keys [ast errors]} (api/parse-string content)]
      (when (seq errors)
        (throw (ex-info "Parse errors" {:reason :parse-errors :errors errors})))

      ;; Pickle
      (let [{:keys [pickles errors]} (api/pickles ast "fuzz.feature")]
        (when (seq errors)
          (throw (ex-info "Pickle errors" {:reason :pickle-errors :errors errors})))

        ;; Roundtrip check
        (when-not (api/roundtrip-ok? content)
          (throw (ex-info "Roundtrip mismatch" {:reason :roundtrip-mismatch})))

        ;; Canonical formatting stability (FZ2)
        ;; Only check if no Rules (canonical doesn't support them)
        (let [canonical-result (api/fmt-canonical content)]
          (when (= :ok (:status canonical-result))
            (let [formatted (:output canonical-result)
                  reformat-result (api/fmt-canonical formatted)]
              (when (= :ok (:status reformat-result))
                (when-not (= formatted (:output reformat-result))
                  (throw (ex-info "Canonical not idempotent"
                                  {:reason :canonical-not-idempotent
                                   :first-format formatted
                                   :second-format (:output reformat-result)})))))))

        {:status :ok
         :ast-count (count ast)
         :pickle-count (count pickles)}))

    (catch clojure.lang.ExceptionInfo e
      {:status :fail
       :reason (:reason (ex-data e) :exception)
       :message (ex-message e)
       :details (ex-data e)})

    (catch Throwable t
      {:status :fail
       :reason :uncaught-exception
       :message (.getMessage t)
       :exception-class (str (class t))})))

;; -----------------------------------------------------------------------------
;; Artifact Saving
;; -----------------------------------------------------------------------------

(defn- git-sha
  "Get current git SHA (short), or nil if not in git repo."
  []
  (try
    (let [result (shell/sh "git" "rev-parse" "--short" "HEAD")]
      (when (zero? (:exit result))
        (str/trim (:out result))))
    (catch Exception _ nil)))

(defn- save-failure!
  "Save failure artifacts to fuzz/artifacts/<timestamp>-<trial>/."
  [base-dir seed trial-idx content result opts]
  (let [timestamp (str (Instant/now))
        dir-name (str (subs timestamp 0 19) "-trial-" trial-idx)
        dir (fs/path base-dir dir-name)]
    (fs/create-dirs dir)

    ;; case.feature
    (spit (str (fs/path dir "case.feature")) content)

    ;; meta.edn
    (spit (str (fs/path dir "meta.edn"))
          (pr-str {:seed seed
                   :trial-idx trial-idx
                   :generator-version generator-version
                   :git-sha (git-sha)
                   :timestamp timestamp
                   :opts (dissoc opts :save)}))

    ;; result.edn
    (spit (str (fs/path dir "result.edn"))
          (pr-str result))

    (str dir)))

;; -----------------------------------------------------------------------------
;; Main Runner
;; -----------------------------------------------------------------------------

(defn run
  "Run fuzz tests.

   Options:
   - :seed    — random seed (default: current time millis)
   - :trials  — number of trials (default: 100)
   - :preset  — :smoke (10), :quick (100), or :nightly (10000)
   - :save    — directory to save failures (default: fuzz/artifacts)
   - :verbose — print progress (default: false)

   Returns:
   {:status :ok/:fail
    :trials N
    :passed N
    :failed N
    :failures [...]}  ; list of saved artifact paths"
  [opts]
  (let [preset-opts (get presets (:preset opts) {})
        merged (merge {:seed (System/currentTimeMillis)
                       :trials 100
                       :save "fuzz/artifacts"
                       :verbose false}
                      preset-opts
                      opts)
        {:keys [seed trials save verbose]} merged
        rng (Random. seed)
        results (atom {:passed 0 :failed 0 :failures []})]

    (when verbose
      (println (str "Fuzzing: " trials " trials, seed=" seed ", generator=" generator-version)))

    (doseq [trial-idx (range trials)]
      (let [{:keys [content]} (generate-feature rng trial-idx)
            result (check-invariants content)]
        (if (= :ok (:status result))
          (swap! results update :passed inc)
          (do
            (swap! results update :failed inc)
            (let [path (save-failure! save seed trial-idx content result merged)]
              (swap! results update :failures conj path)
              (when verbose
                (println (str "FAIL trial " trial-idx ": " (:reason result) " -> " path))))))))

    (let [{:keys [passed failed failures]} @results]
      (when verbose
        (println (str "\nResults: " passed " passed, " failed " failed")))
      {:status (if (zero? failed) :ok :fail)
       :trials trials
       :seed seed
       :generator-version generator-version
       :passed passed
       :failed failed
       :failures failures})))
