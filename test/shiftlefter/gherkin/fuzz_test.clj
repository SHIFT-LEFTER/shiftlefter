(ns shiftlefter.gherkin.fuzz-test
  "Tests for the fuzz harness."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [babashka.fs :as fs]
            [shiftlefter.gherkin.api :as api]
            [shiftlefter.gherkin.fuzz :as fuzz]))

;; -----------------------------------------------------------------------------
;; Generator version
;; -----------------------------------------------------------------------------

(deftest generator-version-is-vector
  (testing "generator-version is a [major minor] vector"
    (is (vector? fuzz/generator-version))
    (is (= 2 (count fuzz/generator-version)))
    (is (every? integer? fuzz/generator-version))))

;; -----------------------------------------------------------------------------
;; Feature generation
;; -----------------------------------------------------------------------------

(deftest generate-feature-produces-valid-gherkin
  (testing "generated feature is parseable"
    (let [rng (java.util.Random. 12345)
          {:keys [content]} (fuzz/generate-feature rng 0)]
      (is (string? content))
      (is (clojure.string/starts-with? content "Feature:"))
      (is (clojure.string/includes? content "Scenario:")))))

(deftest generate-feature-is-deterministic
  (testing "same seed produces same content"
    (let [rng1 (java.util.Random. 99999)
          rng2 (java.util.Random. 99999)
          f1 (fuzz/generate-feature rng1 0)
          f2 (fuzz/generate-feature rng2 0)]
      (is (= (:content f1) (:content f2))))))

;; -----------------------------------------------------------------------------
;; Fuzz runner
;; -----------------------------------------------------------------------------

(deftest run-smoke-preset-passes
  (testing "smoke preset (10 trials) runs without failures"
    (let [result (fuzz/run {:preset :smoke :seed 12345})]
      (is (= :ok (:status result)))
      (is (= 10 (:trials result)))
      (is (= 10 (:passed result)))
      (is (= 0 (:failed result)))
      (is (empty? (:failures result))))))

(deftest run-is-deterministic
  (testing "same seed produces same results"
    (let [r1 (fuzz/run {:seed 54321 :trials 20})
          r2 (fuzz/run {:seed 54321 :trials 20})]
      (is (= (:passed r1) (:passed r2)))
      (is (= (:failed r1) (:failed r2))))))

(deftest run-returns-generator-version
  (testing "result includes generator version"
    (let [result (fuzz/run {:trials 1 :seed 1})]
      (is (= fuzz/generator-version (:generator-version result))))))

;; -----------------------------------------------------------------------------
;; Artifact saving (integration test)
;; -----------------------------------------------------------------------------

(deftest run-saves-failures-to-artifacts
  (testing "failures are saved when they occur"
    ;; This test uses a temp dir to avoid polluting fuzz/artifacts
    (let [temp-dir (str (fs/create-temp-dir {:prefix "fuzz-test-"}))
          ;; Run with valid content - should have no failures
          result (fuzz/run {:trials 5 :seed 42 :save temp-dir})]
      ;; Clean up
      (fs/delete-tree temp-dir)
      ;; With the current generator, all should pass
      (is (= :ok (:status result)))
      (is (empty? (:failures result))))))

;; -----------------------------------------------------------------------------
;; FZ2: test.check generators
;; -----------------------------------------------------------------------------

(deftest gen-identifier-produces-valid-strings
  (testing "gen-identifier produces alphanumeric strings 3-15 chars"
    (let [samples (gen/sample fuzz/gen-identifier 20)]
      (is (every? string? samples))
      (is (every? #(re-matches #"[a-z0-9]+" %) samples))
      (is (every? #(<= 3 (count %) 15) samples)))))

(deftest gen-tag-produces-valid-tags
  (testing "gen-tag produces tags starting with @"
    (let [samples (gen/sample fuzz/gen-tag 20)]
      (is (every? #(str/starts-with? % "@") samples))
      (is (every? #(re-matches #"@[a-z0-9]+" %) samples)))))

(deftest gen-tags-produces-optional-tag-line
  (testing "gen-tags produces nil or a line of space-separated tags"
    (let [samples (gen/sample fuzz/gen-tags 50)]
      ;; Should have mix of nil and tag lines
      (is (some nil? samples))
      (is (some string? samples))
      ;; Non-nil samples should be valid tag lines
      (doseq [s (remove nil? samples)]
        (is (str/ends-with? s "\n"))
        (is (every? #(str/starts-with? % "@") (str/split (str/trim s) #"\s+")))))))

(deftest gen-table-row-produces-valid-rows
  (testing "gen-table-row produces pipe-delimited rows"
    (let [samples (gen/sample fuzz/gen-table-row 20)]
      (is (every? #(str/starts-with? % "      |") samples))
      (is (every? #(str/ends-with? % "|") samples)))))

(deftest gen-step-produces-valid-steps
  (testing "gen-step produces steps with valid keywords"
    (let [samples (gen/sample fuzz/gen-step 20)
          valid-keywords #{"Given" "When" "Then" "And" "But" "*"}]
      (is (every? #(str/starts-with? % "    ") samples))
      (is (every? (fn [s]
                    (let [kw (second (re-find #"^\s+(\w+|\*)" s))]
                      (contains? valid-keywords kw)))
                  samples)))))

(deftest gen-scenario-produces-parseable-scenarios
  (testing "gen-scenario produces valid scenario blocks"
    (let [samples (gen/sample fuzz/gen-scenario 10)]
      (is (every? #(str/includes? % "Scenario:") samples))
      ;; Each should have at least one step
      (is (every? #(re-find #"(Given|When|Then|And|But|\*)" %) samples)))))

(deftest gen-scenario-outline-produces-examples
  (testing "gen-scenario-outline includes Examples block"
    (let [samples (gen/sample fuzz/gen-scenario-outline 10)]
      (is (every? #(str/includes? % "Scenario Outline:") samples))
      (is (every? #(str/includes? % "Examples:") samples)))))

(deftest gen-feature-produces-parseable-features
  (testing "gen-feature produces valid feature files that parse without errors"
    (let [samples (gen/sample fuzz/gen-feature 10)]
      (is (every? string? samples))
      (is (every? #(str/starts-with? (str/trim %) "Feature:")
                  (map #(str/replace % #"^@[^\n]+\n" "") samples)))
      ;; All should parse without errors
      (doseq [s samples]
        (let [{:keys [errors]} (api/parse-string s)]
          (is (empty? errors) (str "Parse errors for:\n" s "\nErrors: " errors)))))))

(deftest gen-feature-uses-size-parameter
  (testing "gen-feature respects size parameter for output variation"
    ;; Generate features at different sizes and verify they're valid
    ;; Size affects vector lengths in test.check generators
    (let [sizes [5 15 30 50]
          samples (for [size sizes]
                    {:size size
                     :content (fuzz/generate-from-gen fuzz/gen-feature (+ 10000 size) size)})]
      ;; All should be valid features
      (doseq [{:keys [content]} samples]
        (is (string? content))
        (is (str/includes? content "Feature:")))
      ;; Should produce variety in output lengths
      (let [lengths (map #(count (:content %)) samples)]
        (is (> (count (distinct lengths)) 1)
            "Different sizes should produce different output lengths")))))

(deftest generate-from-gen-is-deterministic
  (testing "generate-from-gen produces same output for same seed"
    (let [v1 (fuzz/generate-from-gen fuzz/gen-feature 99999 20)
          v2 (fuzz/generate-from-gen fuzz/gen-feature 99999 20)]
      (is (= v1 v2)))))

;; -----------------------------------------------------------------------------
;; FZ2: Canonical formatting stability
;; -----------------------------------------------------------------------------

(deftest canonical-formatting-is-idempotent
  (testing "canonical formatting applied twice produces same output"
    ;; Generate some features and verify canonical is stable
    (doseq [seed [1 42 999 12345]]
      (let [content (fuzz/generate-from-gen fuzz/gen-feature seed 20)
            result1 (api/fmt-canonical content)]
        (when (= :ok (:status result1))
          (let [result2 (api/fmt-canonical (:output result1))]
            (is (= :ok (:status result2))
                (str "Second format failed for seed " seed))
            (is (= (:output result1) (:output result2))
                (str "Canonical not idempotent for seed " seed))))))))

(deftest run-with-generated-features-passes
  (testing "fuzz run with test.check generators passes"
    ;; Run a small fuzz with the new generators
    (let [result (fuzz/run {:trials 20 :seed 42})]
      (is (= :ok (:status result)))
      (is (= 20 (:passed result)))
      (is (= 0 (:failed result))))))

;; =============================================================================
;; FZ3: Mutation Fuzzing Tests
;; =============================================================================

;; -----------------------------------------------------------------------------
;; Mutator version
;; -----------------------------------------------------------------------------

(deftest mutator-version-is-vector
  (testing "mutator-version is a [major minor] vector"
    (is (vector? fuzz/mutator-version))
    (is (= 2 (count fuzz/mutator-version)))
    (is (every? integer? fuzz/mutator-version))))

;; -----------------------------------------------------------------------------
;; Individual mutators
;; -----------------------------------------------------------------------------

(def test-feature-with-table
  "Feature: Test
  Scenario: Has table
    Given a step
      | a | b | c |
      | 1 | 2 | 3 |
    When another step
    Then done
")

(def test-feature-with-docstring
  "Feature: Test
  Scenario: Has docstring
    Given a step
      \"\"\"
      some content
      \"\"\"
    Then done
")

(def test-feature-simple
  "Feature: Test
  Scenario: Simple
    Given a step
    When another
    Then done
")

(deftest mutate-indent-damage-changes-indentation
  (testing "indent damage mutator changes whitespace"
    (let [rng (java.util.Random. 42)
          result (fuzz/apply-mutator
                  (-> fuzz/mutators first :fn)
                  test-feature-simple
                  rng)]
      ;; Should change something (indented lines exist)
      (is (:changed? result))
      (is (not= test-feature-simple (:mutated result))))))

(deftest mutate-keyword-perturb-introduces-typos
  (testing "keyword perturb introduces typos"
    (let [rng (java.util.Random. 42)
          mutator-fn (-> (filter #(= :mut/keyword-perturb (:id %)) fuzz/mutators)
                         first :fn)
          result (fuzz/apply-mutator mutator-fn test-feature-simple rng)]
      (is (:changed? result))
      ;; Should have a typo
      (is (or (str/includes? (:mutated result) "Feture")
              (str/includes? (:mutated result) "Scenaro")
              (str/includes? (:mutated result) "Givn")
              (str/includes? (:mutated result) "Whn")
              (str/includes? (:mutated result) "Thn"))))))

(deftest mutate-colon-perturb-removes-colon
  (testing "colon perturb removes colon from keyword"
    (let [rng (java.util.Random. 42)
          mutator-fn (-> (filter #(= :mut/colon-perturb (:id %)) fuzz/mutators)
                         first :fn)
          result (fuzz/apply-mutator mutator-fn test-feature-simple rng)]
      (is (:changed? result))
      ;; Should have fewer colons after keywords
      (let [orig-colons (count (re-seq #"(Feature|Scenario|Background|Examples):" test-feature-simple))
            mut-colons (count (re-seq #"(Feature|Scenario|Background|Examples):" (:mutated result)))]
        (is (< mut-colons orig-colons))))))

(deftest mutate-delimiter-removal-removes-pipe-or-docstring
  (testing "delimiter removal removes | or \"\"\""
    (let [rng (java.util.Random. 42)
          mutator-fn (-> (filter #(= :mut/delimiter-removal (:id %)) fuzz/mutators)
                         first :fn)]
      ;; Test with table
      (let [result (fuzz/apply-mutator mutator-fn test-feature-with-table rng)]
        (is (:changed? result))
        (is (< (count (re-seq #"\|" (:mutated result)))
               (count (re-seq #"\|" test-feature-with-table)))))
      ;; Test with docstring
      (let [rng2 (java.util.Random. 42)
            result (fuzz/apply-mutator mutator-fn test-feature-with-docstring rng2)]
        (is (:changed? result))))))

(deftest mutate-table-corrupt-changes-cell-count
  (testing "table corrupt adds or removes cells"
    (let [rng (java.util.Random. 42)
          mutator-fn (-> (filter #(= :mut/table-corrupt (:id %)) fuzz/mutators)
                         first :fn)
          result (fuzz/apply-mutator mutator-fn test-feature-with-table rng)]
      (is (:changed? result))
      ;; Structure should be different
      (is (not= test-feature-with-table (:mutated result))))))

(deftest mutators-return-unchanged-when-not-applicable
  (testing "mutators return unchanged content when nothing to mutate"
    (let [minimal "Feature: X\n"
          rng (java.util.Random. 42)]
      ;; Docstring delim mutator on content without docstrings
      #_{:clj-kondo/ignore [:redundant-let]}
      (let [mutator-fn (-> (filter #(= :mut/docstring-delim (:id %)) fuzz/mutators)
                           first :fn)
            result (fuzz/apply-mutator mutator-fn minimal rng)]
        (is (not (:changed? result)))
        (is (= minimal (:mutated result)))))))

;; -----------------------------------------------------------------------------
;; check-mutation-invariants
;; -----------------------------------------------------------------------------

(deftest check-mutation-invariants-returns-graceful-errors
  (testing "mutated input with parse errors returns :graceful-errors"
    (let [bad-content "Feture: Test\n"  ; typo should cause parse error
          mutation-info {:mutator/type :mut/keyword-perturb :idx 0}
          result (fuzz/check-mutation-invariants bad-content mutation-info 200)]
      (is (= :ok (:status result)))
      (is (= :graceful-errors (:reason result)))
      (is (= :parse (:phase result)))
      (is (map? (:signature result)))
      (is (contains? (:signature result) :mutator/type))
      (is (contains? (:signature result) :phase))
      (is (contains? (:signature result) :error/type)))))

(deftest check-mutation-invariants-returns-survived-for-valid
  (testing "valid content returns :mutation-survived"
    (let [mutation-info {:mutator/type :mut/indent-damage :idx 0}
          result (fuzz/check-mutation-invariants test-feature-simple mutation-info 200)]
      (is (= :ok (:status result)))
      (is (= :mutation-survived (:reason result))))))

(deftest check-mutation-invariants-includes-timing
  (testing "result includes timing information"
    (let [mutation-info {:mutator/type :mut/test :idx 0}
          result (fuzz/check-mutation-invariants test-feature-simple mutation-info 200)]
      (is (map? (:timing result)))
      (is (contains? (:timing result) :parse-ms))
      (is (contains? (:timing result) :total-ms)))))

;; -----------------------------------------------------------------------------
;; run-mutations
;; -----------------------------------------------------------------------------

(deftest run-mutations-smoke-test
  (testing "run-mutations with smoke preset runs without exceptions"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "fuzz-mut-test-"}))
          result (fuzz/run-mutations {:preset :smoke :seed 12345 :save temp-dir})]
      ;; Clean up
      (fs/delete-tree temp-dir)
      ;; Should complete without timeout/exception failures
      (is (= :ok (:status result)))
      (is (= 0 (:mutations/timeout result)))
      (is (= 0 (:mutations/exception result)))
      ;; Should have some mutations
      (is (pos? (:mutations/total result)))
      ;; Should have tracked graceful + survived
      (is (= (:mutations/total result)
             (+ (:mutations/graceful result)
                (:mutations/survived result)
                (:mutations/timeout result)
                (:mutations/exception result)))))))

(deftest run-mutations-is-deterministic
  (testing "same seed produces same results"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "fuzz-det-test-"}))
          r1 (fuzz/run-mutations {:seed 54321 :trials 5 :save temp-dir})
          _ (fs/delete-tree temp-dir)
          temp-dir2 (str (fs/create-temp-dir {:prefix "fuzz-det-test2-"}))
          r2 (fuzz/run-mutations {:seed 54321 :trials 5 :save temp-dir2})]
      (fs/delete-tree temp-dir2)
      (is (= (:mutations/total r1) (:mutations/total r2)))
      (is (= (:mutations/graceful r1) (:mutations/graceful r2)))
      (is (= (:mutations/survived r1) (:mutations/survived r2))))))

(deftest run-mutations-returns-mutator-version
  (testing "result includes mutator version"
    (let [result (fuzz/run-mutations {:trials 1 :seed 1})]
      (is (= fuzz/mutator-version (:mutator-version result))))))

(deftest run-mutations-tracks-unique-signatures
  (testing "unique signatures are tracked correctly"
    (let [result (fuzz/run-mutations {:trials 10 :seed 42})]
      ;; Should have some unique signatures
      (is (pos? (:signatures/unique result)))
      ;; Unique count should not exceed graceful count
      (is (<= (:signatures/unique result) (:mutations/graceful result))))))

(deftest run-mutations-with-corpus-works
  (testing "corpus source loads and mutates compliance files"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "fuzz-corpus-test-"}))
          result (fuzz/run-mutations {:trials 3
                                      :seed 42
                                      :sources :corpus
                                      :save temp-dir})]
      (fs/delete-tree temp-dir)
      (is (= :ok (:status result)))
      (is (pos? (:mutations/total result))))))
