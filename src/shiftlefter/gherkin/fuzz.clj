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
  "Run fuzz tests (valid generation mode - FZ1/FZ2).

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

;; =============================================================================
;; FZ3: Mutation Fuzzing
;; =============================================================================
;;
;; Takes valid Gherkin, corrupts it with mutators, verifies parser fails gracefully.
;; A mutation trial PASSES when the parser returns structured errors (not throws/hangs).

(def mutator-version
  "Bump when mutator logic changes."
  [1 0])

;; -----------------------------------------------------------------------------
;; Mutators
;; -----------------------------------------------------------------------------
;;
;; Each mutator: (fn [content ^Random rng] -> mutated-content)
;; Mutators should produce syntactically different output that likely breaks parsing.

(defn- split-lines-keep-endings
  "Split content into lines, preserving line endings."
  [content]
  (vec (re-seq #"[^\n]*\n?" content)))

(defn- join-lines
  "Join lines back into content."
  [lines]
  (apply str lines))

(defn- re-seq-pos
  "Like re-seq but returns [{:start N :match S} ...]"
  [re s]
  (let [m (re-matcher re s)]
    (loop [matches []]
      (if (.find m)
        (recur (conj matches {:start (.start m) :match (.group m)}))
        matches))))

(defn- mutate-indent-damage
  "Remove or double leading whitespace on a random non-empty line."
  [content ^Random rng]
  (let [lines (split-lines-keep-endings content)
        ;; Find lines with leading whitespace
        candidates (keep-indexed
                    (fn [i line]
                      (when (re-find #"^\s+" line) i))
                    lines)]
    (if (empty? candidates)
      content  ; No indented lines to damage
      (let [idx (rand-nth-seeded rng (vec candidates))
            line (nth lines idx)
            ;; 50% remove indent, 50% double it
            damaged (if (< (.nextDouble rng) 0.5)
                      (str/replace line #"^\s+" "")
                      (str/replace line #"^(\s+)" "$1$1"))]
        (join-lines (assoc lines idx damaged))))))

(defn- mutate-delimiter-removal
  "Remove a pipe | or docstring delimiter \"\"\"."
  [content ^Random rng]
  (let [;; Find all | and """ positions
        pipe-matches (re-seq-pos #"\|" content)
        doc-matches (re-seq-pos #"\"\"\"" content)
        all-matches (concat
                     (map #(assoc % :type :pipe :len 1) pipe-matches)
                     (map #(assoc % :type :docstring :len 3) doc-matches))]
    (if (empty? all-matches)
      content  ; Nothing to remove
      (let [{:keys [start len]} (rand-nth-seeded rng (vec all-matches))]
        (str (subs content 0 start) (subs content (+ start len)))))))

(defn- mutate-table-corrupt
  "Add or remove a cell from a random table row (breaks cell count consistency)."
  [content ^Random rng]
  (let [lines (split-lines-keep-endings content)
        ;; Find table row lines (start with whitespace + |)
        row-indices (keep-indexed
                     (fn [i line]
                       (when (re-find #"^\s*\|" line) i))
                     lines)]
    (if (empty? row-indices)
      content  ; No table rows
      (let [idx (rand-nth-seeded rng (vec row-indices))
            line (nth lines idx)
            ;; 50% add extra cell, 50% remove one
            damaged (if (< (.nextDouble rng) 0.5)
                      ;; Add cell: insert "| extra " before final |
                      (str/replace line #"\|(\s*)$" "| extra |$1")
                      ;; Remove cell: remove first "| content " segment
                      (str/replace-first line #"\|\s*[^|]*\s*\|" "|"))]
        (join-lines (assoc lines idx damaged))))))

(defn- mutate-docstring-delim
  "Remove or mismatch docstring delimiters."
  [content ^Random rng]
  (let [matches (re-seq-pos #"\"\"\"" content)]
    (if (< (count matches) 2)
      content  ; Not enough delimiters
      (let [;; Pick opening or closing (even = opening, odd = closing)
            idx (rand-int-seeded rng (count matches))
            {:keys [start]} (nth matches idx)]
        ;; Remove this delimiter
        (str (subs content 0 start) (subs content (+ start 3)))))))

(defn- mutate-keyword-perturb
  "Introduce typos in Gherkin keywords."
  [content ^Random rng]
  (let [typos {"Feature:" "Feture:"
               "Scenario:" "Scenaro:"
               "Scenario Outline:" "Scenario Outlin:"
               "Background:" "Backgrund:"
               "Examples:" "Exampels:"
               "Given " "Givn "
               "When " "Whn "
               "Then " "Thn "
               "And " "Annd "
               "But " "Btu "}
        ;; Find which keywords exist in content
        present (filter #(str/includes? content (key %)) typos)]
    (if (empty? present)
      content
      (let [[original typo] (rand-nth-seeded rng (vec present))]
        (str/replace-first content original typo)))))

(defn- mutate-colon-perturb
  "Remove colon from keyword lines."
  [content ^Random rng]
  (let [;; Match keyword lines with colons
        keywords-with-colon #"(Feature|Scenario|Background|Examples|Rule):"
        matches (re-seq-pos keywords-with-colon content)]
    (if (empty? matches)
      content
      (let [{:keys [start match]} (rand-nth-seeded rng (vec matches))
            ;; Remove the colon (last char of match)
            without-colon (subs match 0 (dec (count match)))]
        (str (subs content 0 start)
             without-colon
             (subs content (+ start (count match))))))))

(def mutators
  "All available mutators with metadata."
  [{:id :mut/indent-damage   :fn mutate-indent-damage   :name "indent-damage"}
   {:id :mut/delimiter-removal :fn mutate-delimiter-removal :name "delimiter-removal"}
   {:id :mut/table-corrupt   :fn mutate-table-corrupt   :name "table-corrupt"}
   {:id :mut/docstring-delim :fn mutate-docstring-delim :name "docstring-delim"}
   {:id :mut/keyword-perturb :fn mutate-keyword-perturb :name "keyword-perturb"}
   {:id :mut/colon-perturb   :fn mutate-colon-perturb   :name "colon-perturb"}])

(defn apply-mutator
  "Apply a mutator to content. Returns {:mutated string :changed? bool}."
  [mutator-fn content ^Random rng]
  (let [result (mutator-fn content rng)]
    {:mutated result
     :changed? (not= result content)}))

;; -----------------------------------------------------------------------------
;; Timeout-wrapped parsing
;; -----------------------------------------------------------------------------

(defn parse-with-timeout
  "Parse content with timeout. Returns:
   {:status :ok/:timeout/:exception
    :result map  ; parse result if :ok
    :elapsed-ms long
    :exception e  ; if :exception}"
  [content timeout-ms]
  (let [start (System/currentTimeMillis)
        fut (future
              (try
                {:status :ok :result (api/parse-string content)}
                (catch Throwable t
                  {:status :exception :exception t})))
        result (deref fut timeout-ms ::timeout)
        elapsed (- (System/currentTimeMillis) start)]
    (if (= result ::timeout)
      (do
        (future-cancel fut)
        {:status :timeout :elapsed-ms elapsed})
      (assoc result :elapsed-ms elapsed))))

(defn pickles-with-timeout
  "Run pickles with timeout."
  [ast uri timeout-ms]
  (let [start (System/currentTimeMillis)
        fut (future
              (try
                {:status :ok :result (api/pickles ast uri)}
                (catch Throwable t
                  {:status :exception :exception t})))
        result (deref fut timeout-ms ::timeout)
        elapsed (- (System/currentTimeMillis) start)]
    (if (= result ::timeout)
      (do
        (future-cancel fut)
        {:status :timeout :elapsed-ms elapsed})
      (assoc result :elapsed-ms elapsed))))

;; -----------------------------------------------------------------------------
;; Mutation Invariant Checker
;; -----------------------------------------------------------------------------

(defn- make-signature
  "Create dedup signature from mutator type, phase, and error type."
  [mutator-id phase errors]
  {:mutator/type mutator-id
   :phase phase
   :error/type (-> errors first :type)})

(defn check-mutation-invariants
  "Check that parser handles mutated input gracefully.

   Returns:
   {:status :ok/:fail
    :reason :graceful-errors/:mutation-survived/:timeout/:uncaught-exception
    :phase :parse/:pickles/:unknown
    :signature map  ; for dedup
    :details map
    :timing {:parse-ms N :pickles-ms N :total-ms N}}"
  [content mutation-info timeout-ms]
  (let [start (System/currentTimeMillis)
        mutator-id (:mutator/type mutation-info)

        ;; Phase 1: Parse with timeout
        parse-result (parse-with-timeout content timeout-ms)]

    (case (:status parse-result)
      ;; Timeout during parse
      :timeout
      {:status :fail
       :reason :timeout
       :phase :parse
       :mutation mutation-info
       :signature {:mutator/type mutator-id :phase :parse :error/type nil}
       :details {:timeout-ms timeout-ms}
       :timing {:parse-ms (:elapsed-ms parse-result)
                :total-ms (- (System/currentTimeMillis) start)}}

      ;; Exception during parse
      :exception
      {:status :fail
       :reason :uncaught-exception
       :phase :parse
       :mutation mutation-info
       :signature {:mutator/type mutator-id :phase :parse :error/type nil}
       :details {:exception-class (str (class (:exception parse-result)))
                 :message (.getMessage ^Throwable (:exception parse-result))}
       :timing {:parse-ms (:elapsed-ms parse-result)
                :total-ms (- (System/currentTimeMillis) start)}}

      ;; Parse completed
      :ok
      (let [{:keys [ast errors]} (:result parse-result)
            parse-ms (:elapsed-ms parse-result)]

        (if (seq errors)
          ;; Parse errors = graceful failure (PASS)
          {:status :ok
           :reason :graceful-errors
           :phase :parse
           :mutation mutation-info
           :signature (make-signature mutator-id :parse errors)
           :details {:error-count (count errors)
                     :errors (take 3 errors)
                     :first-location (-> errors first :location)}
           :timing {:parse-ms parse-ms
                    :total-ms (- (System/currentTimeMillis) start)}}

          ;; Parse succeeded, try pickles
          (let [pickle-result (pickles-with-timeout ast "mutation.feature" timeout-ms)]
            (case (:status pickle-result)
              :timeout
              {:status :fail
               :reason :timeout
               :phase :pickles
               :mutation mutation-info
               :signature {:mutator/type mutator-id :phase :pickles :error/type nil}
               :details {:timeout-ms timeout-ms}
               :timing {:parse-ms parse-ms
                        :pickles-ms (:elapsed-ms pickle-result)
                        :total-ms (- (System/currentTimeMillis) start)}}

              :exception
              {:status :fail
               :reason :uncaught-exception
               :phase :pickles
               :mutation mutation-info
               :signature {:mutator/type mutator-id :phase :pickles :error/type nil}
               :details {:exception-class (str (class (:exception pickle-result)))
                         :message (.getMessage ^Throwable (:exception pickle-result))}
               :timing {:parse-ms parse-ms
                        :pickles-ms (:elapsed-ms pickle-result)
                        :total-ms (- (System/currentTimeMillis) start)}}

              :ok
              (let [{:keys [errors]} (:result pickle-result)]
                (if (seq errors)
                  ;; Pickle errors = graceful failure (PASS)
                  {:status :ok
                   :reason :graceful-errors
                   :phase :pickles
                   :mutation mutation-info
                   :signature (make-signature mutator-id :pickles errors)
                   :details {:error-count (count errors)
                             :errors (take 3 errors)}
                   :timing {:parse-ms parse-ms
                            :pickles-ms (:elapsed-ms pickle-result)
                            :total-ms (- (System/currentTimeMillis) start)}}

                  ;; Mutation survived (not a failure, but tracked)
                  {:status :ok
                   :reason :mutation-survived
                   :phase :pickles
                   :mutation mutation-info
                   :signature {:mutator/type mutator-id :phase :survived :error/type nil}
                   :details {:ast-count (count ast)
                             :pickle-count (count (:pickles (:result pickle-result)))}
                   :timing {:parse-ms parse-ms
                            :pickles-ms (:elapsed-ms pickle-result)
                            :total-ms (- (System/currentTimeMillis) start)}})))))))))

;; -----------------------------------------------------------------------------
;; Mutation Artifact Saving
;; -----------------------------------------------------------------------------

(defn- save-mutation-artifact!
  "Save mutation artifact. Returns path."
  [base-dir seed trial-idx mutation-idx content result opts]
  (let [timestamp (str (Instant/now))
        mutator-name (name (get-in result [:mutation :mutator/type]))
        dir-name (format "%s-trial-%d-mut-%d-%s"
                         (subs timestamp 0 19)
                         trial-idx
                         mutation-idx
                         mutator-name)
        dir (fs/path base-dir dir-name)]
    (fs/create-dirs dir)

    ;; case.feature
    (spit (str (fs/path dir "case.feature")) content)

    ;; meta.edn
    (spit (str (fs/path dir "meta.edn"))
          (pr-str {:seed seed
                   :trial-idx trial-idx
                   :mutation-idx mutation-idx
                   :mutator-version mutator-version
                   :generator-version generator-version
                   :git-sha (git-sha)
                   :timestamp timestamp
                   :fuzz/mode :mutation
                   :fuzz/timeout-ms (:timeout-ms opts)
                   :opts (dissoc opts :save)}))

    ;; result.edn
    (spit (str (fs/path dir "result.edn"))
          (pr-str result))

    (str dir)))

;; -----------------------------------------------------------------------------
;; Corpus Loading
;; -----------------------------------------------------------------------------

(defn- load-corpus
  "Load .feature files from directory."
  [corpus-dir]
  (->> (fs/glob corpus-dir "**.feature")
       (map str)
       (map (fn [path] {:path path :content (slurp path)}))
       vec))

;; -----------------------------------------------------------------------------
;; Mutation Runner
;; -----------------------------------------------------------------------------

(defn run-mutations
  "Run mutation fuzzing.

   Takes valid Gherkin (generated or from corpus), corrupts it with mutators,
   verifies parser fails gracefully. A trial PASSES when parser returns
   structured errors (not throws/hangs).

   Options:
   - :seed        — random seed (default: current time millis)
   - :trials      — number of source inputs to mutate (default: 100)
   - :preset      — :smoke (10), :quick (100), or :nightly (10000)
   - :sources     — :generated, :corpus, or :both (default: :generated)
   - :corpus-dir  — path to corpus directory (default: compliance/gherkin/testdata/good)
   - :timeout-ms  — per-parse timeout (default: 200)
   - :combos      — number of combo mutations per source (default: 1)
   - :save        — directory to save artifacts (default: fuzz/artifacts)
   - :verbose     — print progress (default: false)

   Returns:
   {:status :ok/:fail
    :trials N
    :mutations/total N
    :mutations/graceful N
    :mutations/survived N
    :mutations/timeout N
    :mutations/exception N
    :signatures/unique N
    :artifacts/saved N
    :failures [...]}"
  [opts]
  (let [preset-opts (get presets (:preset opts) {})
        merged (merge {:seed (System/currentTimeMillis)
                       :trials 100
                       :sources :generated
                       :corpus-dir "compliance/gherkin/testdata/good"
                       :timeout-ms 200
                       :combos 1
                       :save "fuzz/artifacts"
                       :verbose false}
                      preset-opts
                      opts)
        {:keys [seed trials sources corpus-dir timeout-ms combos save verbose]} merged
        rng (Random. seed)

        ;; Load corpus if needed
        corpus (when (#{:corpus :both} sources)
                 (load-corpus corpus-dir))

        ;; Stats
        stats (atom {:mutations/total 0
                     :mutations/graceful 0
                     :mutations/survived 0
                     :mutations/timeout 0
                     :mutations/exception 0
                     :artifacts/saved 0})
        seen-signatures (atom #{})
        failures (atom [])

        ;; Helper to get source content
        get-source (fn [trial-idx]
                     (case sources
                       :generated
                       {:kind :generated
                        :content (:content (generate-feature rng trial-idx))}

                       :corpus
                       (let [entry (rand-nth-seeded rng corpus)]
                         {:kind :corpus
                          :path (:path entry)
                          :content (:content entry)})

                       :both
                       (if (< (.nextDouble rng) 0.5)
                         {:kind :generated
                          :content (:content (generate-feature rng trial-idx))}
                         (let [entry (rand-nth-seeded rng corpus)]
                           {:kind :corpus
                            :path (:path entry)
                            :content (:content entry)}))))]

    (when verbose
      (println (format "Mutation fuzzing: %d trials, seed=%d, timeout=%dms, sources=%s"
                       trials seed timeout-ms sources))
      (when corpus
        (println (format "  Corpus: %d files from %s" (count corpus) corpus-dir))))

    ;; Main loop
    (doseq [trial-idx (range trials)]
      (let [source (get-source trial-idx)
            source-content (:content source)]

        ;; Apply each mutator
        (doseq [[mut-idx {:keys [id fn]}] (map-indexed vector mutators)]
          (let [{:keys [mutated changed?]} (apply-mutator fn source-content rng)]
            (when changed?
              (swap! stats update :mutations/total inc)

              (let [mutation-info {:mutator/type id
                                   :source {:kind (:kind source)
                                            :path (:path source)}
                                   :idx mut-idx}
                    result (check-mutation-invariants mutated mutation-info timeout-ms)
                    sig (:signature result)]

                ;; Update stats
                (case (:reason result)
                  :graceful-errors (swap! stats update :mutations/graceful inc)
                  :mutation-survived (swap! stats update :mutations/survived inc)
                  :timeout (swap! stats update :mutations/timeout inc)
                  :uncaught-exception (swap! stats update :mutations/exception inc)
                  nil)

                ;; Determine if we should save
                (let [is-fail? (= :fail (:status result))
                      new-sig? (and (= :ok (:status result))
                                    (= :graceful-errors (:reason result))
                                    (not (contains? @seen-signatures sig)))
                      should-save? (or is-fail? new-sig?)]

                  (when new-sig?
                    (swap! seen-signatures conj sig))

                  (when should-save?
                    (let [path (save-mutation-artifact! save seed trial-idx mut-idx
                                                        mutated result merged)]
                      (swap! stats update :artifacts/saved inc)
                      (when is-fail?
                        (swap! failures conj path))
                      (when verbose
                        (println (format "  %s trial %d mut %d (%s): %s -> %s"
                                         (if is-fail? "FAIL" "NEW ")
                                         trial-idx mut-idx (name id)
                                         (name (:reason result)) path))))))))))

        ;; Combo mutations (apply 2 mutators in sequence)
        (when (pos? combos)
          (doseq [combo-idx (range combos)]
            (let [;; Pick 2 different mutators
                  mut1 (rand-nth-seeded rng mutators)
                  mut2 (rand-nth-seeded rng (remove #(= (:id %) (:id mut1)) mutators))
                  {:keys [mutated]} (apply-mutator (:fn mut1) source-content rng)
                  {:keys [mutated changed?]} (apply-mutator (:fn mut2) mutated rng)]
              (when changed?
                (swap! stats update :mutations/total inc)

                (let [mutation-info {:mutator/type :mut/combo
                                     :combo [(:id mut1) (:id mut2)]
                                     :source {:kind (:kind source)
                                              :path (:path source)}
                                     :idx (+ (count mutators) combo-idx)}
                      result (check-mutation-invariants mutated mutation-info timeout-ms)
                      sig (:signature result)]

                  (case (:reason result)
                    :graceful-errors (swap! stats update :mutations/graceful inc)
                    :mutation-survived (swap! stats update :mutations/survived inc)
                    :timeout (swap! stats update :mutations/timeout inc)
                    :uncaught-exception (swap! stats update :mutations/exception inc)
                    nil)

                  (let [is-fail? (= :fail (:status result))
                        new-sig? (and (= :ok (:status result))
                                      (= :graceful-errors (:reason result))
                                      (not (contains? @seen-signatures sig)))
                        should-save? (or is-fail? new-sig?)]

                    (when new-sig?
                      (swap! seen-signatures conj sig))

                    (when should-save?
                      (let [path (save-mutation-artifact! save seed trial-idx
                                                          (+ (count mutators) combo-idx)
                                                          mutated result merged)]
                        (swap! stats update :artifacts/saved inc)
                        (when is-fail?
                          (swap! failures conj path))
                        (when verbose
                          (println (format "  %s trial %d combo (%s+%s): %s -> %s"
                                           (if is-fail? "FAIL" "NEW ")
                                           trial-idx
                                           (name (:id mut1)) (name (:id mut2))
                                           (name (:reason result)) path)))))))))))))

    ;; Summary
    (let [{:keys [mutations/total mutations/graceful mutations/survived
                  mutations/timeout mutations/exception artifacts/saved]} @stats
          unique-sigs (count @seen-signatures)
          failed? (or (pos? timeout) (pos? exception))]

      (when verbose
        (println)
        (println (format "Results: %d mutations (%d graceful, %d survived, %d timeout, %d exception)"
                         total graceful survived timeout exception))
        (println (format "  Unique signatures: %d, Artifacts saved: %d"
                         unique-sigs saved)))

      {:status (if failed? :fail :ok)
       :trials trials
       :seed seed
       :mutator-version mutator-version
       :mutations/total total
       :mutations/graceful graceful
       :mutations/survived survived
       :mutations/timeout timeout
       :mutations/exception exception
       :signatures/unique unique-sigs
       :artifacts/saved saved
       :failures @failures})))
