(ns shiftlefter.stepengine.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.stepengine.registry :as registry]))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(defn clean-registry-fixture [f]
  (registry/clear-registry!)
  (f)
  (registry/clear-registry!))

(use-fixtures :each clean-registry-fixture)

;; -----------------------------------------------------------------------------
;; Basic Registration Tests
;; -----------------------------------------------------------------------------

(deftest test-register-basic
  (testing "Basic step registration"
    (let [stepdef (registry/register! #"the user exists"
                                      (fn [ctx] ctx)
                                      {:ns 'test :file "test.clj" :line 1})]
      (is (map? stepdef))
      (is (string? (:stepdef/id stepdef)))
      (is (= "the user exists" (:pattern-src stepdef)))
      (is (= {:ns 'test :file "test.clj" :line 1} (:source stepdef)))
      (is (= 1 (:arity stepdef)))
      (is (fn? (:fn stepdef))))))

(deftest test-register-multiple
  (testing "Multiple step registrations"
    (registry/register! #"step one" (fn [] nil) {:ns 'a :file "a.clj" :line 1})
    (registry/register! #"step two" (fn [] nil) {:ns 'b :file "b.clj" :line 2})
    (registry/register! #"step three" (fn [] nil) {:ns 'c :file "c.clj" :line 3})
    (is (= 3 (count (registry/all-stepdefs))))))

(deftest test-all-stepdefs-returns-seq
  (testing "all-stepdefs returns a sequence of stepdef maps"
    (registry/register! #"test step" (fn [] nil) {:ns 'x :file "x.clj" :line 1})
    (let [stepdefs (registry/all-stepdefs)]
      (is (seq? stepdefs) "Should be a sequence")
      (is (= 1 (count stepdefs)))
      (is (every? map? stepdefs)))))

(deftest test-clear-registry
  (testing "clear-registry! removes all registrations"
    (registry/register! #"step a" (fn [] nil) {:ns 'a :file "a.clj" :line 1})
    (registry/register! #"step b" (fn [] nil) {:ns 'b :file "b.clj" :line 2})
    (is (= 2 (count (registry/all-stepdefs))))
    (registry/clear-registry!)
    (is (= 0 (count (registry/all-stepdefs))))))

;; -----------------------------------------------------------------------------
;; Duplicate Detection Tests (BIRDSONG acceptance criteria)
;; -----------------------------------------------------------------------------

(deftest test-duplicate-detection
  (testing "Duplicate pattern throws with both source locations"
    (registry/register! #"the user exists"
                        (fn [ctx] ctx)
                        {:ns 'x :file "x.clj" :line 1})
    (is (= 1 (count (registry/all-stepdefs))))

    ;; Same pattern from different location should throw
    (try
      (registry/register! #"the user exists"
                          (fn [ctx] ctx)
                          {:ns 'y :file "y.clj" :line 9})
      (is false "Should have thrown on duplicate")
      (catch Exception e
        (let [msg (.getMessage e)]
          (is (re-find #"(?i)duplicate" msg) "Message should mention 'duplicate'")
          (is (re-find #"x\.clj:1" msg) "Message should include first location")
          (is (re-find #"y\.clj:9" msg) "Message should include duplicate location"))))))

(deftest test-duplicate-detection-same-pattern-object
  (testing "Duplicate detection works even with same regex literal evaluated twice"
    ;; This simulates what happens when a file is reloaded
    (let [pattern-str "I have (\\d+) items"]
      (registry/register! (re-pattern pattern-str)
                          (fn [n] n)
                          {:ns 'first :file "first.clj" :line 10})
      (try
        ;; Create a NEW Pattern object with same content
        (registry/register! (re-pattern pattern-str)
                            (fn [n] n)
                            {:ns 'second :file "second.clj" :line 20})
        (is false "Should have thrown on duplicate")
        (catch Exception e
          (is (re-find #"(?i)duplicate" (.getMessage e))))))))

;; -----------------------------------------------------------------------------
;; Variadic Rejection Tests (BIRDSONG acceptance criteria)
;; -----------------------------------------------------------------------------

(deftest test-variadic-rejection
  (testing "Variadic functions are rejected at registration"
    (try
      (registry/register! #"foo"
                          (fn [& xs] xs)
                          {:ns 'z :file "z.clj" :line 2})
      (is false "Should have thrown on variadic")
      (catch Exception e
        (is (re-find #"(?i)variadic" (.getMessage e))
            "Message should mention 'variadic'")))))

(deftest test-variadic-rejection-with-required-args
  (testing "Variadic with required args also rejected"
    (try
      (registry/register! #"bar"
                          (fn [a b & rest] [a b rest])
                          {:ns 'v :file "v.clj" :line 5})
      (is false "Should have thrown on variadic")
      (catch Exception e
        (is (re-find #"(?i)variadic" (.getMessage e)))))))

;; -----------------------------------------------------------------------------
;; Arity Detection Tests
;; -----------------------------------------------------------------------------

(deftest test-arity-detection
  (testing "Arity is correctly detected for various function arities"
    ;; Zero-arg
    (let [sd0 (registry/register! #"zero args" (fn [] nil) {:ns 't :file "t.clj" :line 1})]
      (is (= 0 (:arity sd0))))

    ;; One-arg
    (let [sd1 (registry/register! #"one arg" (fn [x] x) {:ns 't :file "t.clj" :line 2})]
      (is (= 1 (:arity sd1))))

    ;; Two-args
    (let [sd2 (registry/register! #"two args" (fn [x y] [x y]) {:ns 't :file "t.clj" :line 3})]
      (is (= 2 (:arity sd2))))

    ;; Three-args
    (let [sd3 (registry/register! #"three args" (fn [a b c] [a b c]) {:ns 't :file "t.clj" :line 4})]
      (is (= 3 (:arity sd3))))))

;; -----------------------------------------------------------------------------
;; Deterministic ID Tests
;; -----------------------------------------------------------------------------

(deftest test-stepdef-id-deterministic
  (testing "Stepdef IDs are deterministic based on pattern"
    (let [sd1 (registry/register! #"pattern one" (fn [] nil) {:ns 'a :file "a.clj" :line 1})]
      (registry/clear-registry!)
      (let [sd2 (registry/register! #"pattern one" (fn [] nil) {:ns 'b :file "b.clj" :line 99})]
        ;; Same pattern should produce same ID even with different source
        (is (= (:stepdef/id sd1) (:stepdef/id sd2)))))))

(deftest test-stepdef-id-format
  (testing "Stepdef ID has expected format"
    (let [sd (registry/register! #"test pattern" (fn [] nil) {:ns 't :file "t.clj" :line 1})]
      (is (string? (:stepdef/id sd)))
      (is (re-matches #"sd-[a-f0-9]{16}" (:stepdef/id sd))
          "ID should be 'sd-' followed by 16 hex chars"))))

(deftest test-different-patterns-different-ids
  (testing "Different patterns produce different IDs"
    (let [sd1 (registry/register! #"first pattern" (fn [] nil) {:ns 'a :file "a.clj" :line 1})
          sd2 (registry/register! #"second pattern" (fn [] nil) {:ns 'b :file "b.clj" :line 2})]
      (is (not= (:stepdef/id sd1) (:stepdef/id sd2))))))

;; -----------------------------------------------------------------------------
;; defstep Macro Tests
;; -----------------------------------------------------------------------------

(deftest test-defstep-macro
  (testing "defstep macro registers step with source info"
    (registry/defstep #"I click \"([^\"]+)\""
      [element]
      {:clicked element})

    (let [stepdefs (registry/all-stepdefs)]
      (is (= 1 (count stepdefs)))
      (let [sd (first stepdefs)]
        (is (= "I click \\\"([^\\\"]+)\\\"" (:pattern-src sd)))
        (is (= 1 (:arity sd)))
        (is (map? (:source sd)))
        (is (:file (:source sd)))
        (is (:line (:source sd)))))))

(deftest test-defstep-execution
  (testing "Step registered via defstep is callable"
    (registry/defstep #"I have (\d+) items"
      [count-str]
      (Integer/parseInt count-str))

    (let [sd (first (registry/all-stepdefs))
          step-fn (:fn sd)]
      (is (= 42 (step-fn "42"))))))

;; -----------------------------------------------------------------------------
;; find-by-pattern Tests
;; -----------------------------------------------------------------------------

(deftest test-find-by-pattern
  (testing "find-by-pattern returns stepdef or nil"
    (registry/register! #"findable step" (fn [] nil) {:ns 't :file "t.clj" :line 1})

    (let [found (registry/find-by-pattern "findable step")]
      (is (map? found))
      (is (= "findable step" (:pattern-src found))))

    (let [not-found (registry/find-by-pattern "nonexistent")]
      (is (nil? not-found)))))

;; -----------------------------------------------------------------------------
;; Task 3.0.4: Metadata Support Tests
;; -----------------------------------------------------------------------------

(deftest test-register-with-metadata
  (testing "register! accepts optional metadata parameter"
    (let [metadata {:interface :web
                    :svo {:subject :$1 :verb :click :object :$2}}
          stepdef (registry/register! #"(.*) clicks (.*)"
                                      (fn [ctx _s _t] ctx)
                                      {:ns 'test :file "test.clj" :line 1}
                                      metadata)]
      (is (= metadata (:metadata stepdef)))))

  (testing "register! without metadata sets :metadata to nil"
    (let [stepdef (registry/register! #"legacy step"
                                      (fn [ctx] ctx)
                                      {:ns 'test :file "test.clj" :line 2})]
      (is (nil? (:metadata stepdef))))))

(deftest test-register-metadata-with-nil
  (testing "Explicit nil metadata is stored as nil"
    (let [stepdef (registry/register! #"explicit nil"
                                      (fn [] nil)
                                      {:ns 't :file "t.clj" :line 1}
                                      nil)]
      (is (nil? (:metadata stepdef))))))

(deftest test-defstep-with-metadata
  (testing "defstep with metadata map stores it correctly"
    (registry/defstep #"(.*) fills (.*)"
      {:interface :web
       :svo {:subject :$1 :verb :fill :object :$2}}
      [ctx subject field]
      ctx)

    (let [sd (first (registry/all-stepdefs))]
      (is (map? (:metadata sd)))
      (is (= :web (get-in sd [:metadata :interface])))
      (is (= :$1 (get-in sd [:metadata :svo :subject])))
      (is (= :fill (get-in sd [:metadata :svo :verb])))
      (is (= :$2 (get-in sd [:metadata :svo :object]))))))

(deftest test-defstep-without-metadata-legacy
  (testing "defstep without metadata (legacy form) still works"
    (registry/defstep #"I do something"
      [ctx]
      ctx)

    (let [sd (first (registry/all-stepdefs))]
      (is (nil? (:metadata sd)))
      (is (= "I do something" (:pattern-src sd)))
      (is (= 1 (:arity sd))))))

(deftest test-defstep-metadata-execution
  (testing "Step with metadata is still callable"
    (registry/defstep #"(.*) sees (.*)"
      {:interface :web
       :svo {:subject :$1 :verb :see :object :$2}}
      [ctx subject element]
      {:subject subject :element element})

    (let [sd (first (registry/all-stepdefs))
          step-fn (:fn sd)]
      (is (= {:subject "Alice" :element "button"}
             (step-fn {} "Alice" "button"))))))

(deftest test-defstep-metadata-empty-body
  (testing "defstep with metadata and minimal body"
    (registry/defstep #"(.*) waits"
      {:interface :web
       :svo {:subject :$1 :verb :wait :object nil}}
      [ctx subject]
      nil)

    (let [sd (first (registry/all-stepdefs))]
      (is (= :web (get-in sd [:metadata :interface])))
      (is (nil? ((:fn sd) {} "Alice"))))))

(deftest test-metadata-warning-interface-without-svo
  (testing "Warning is printed when :interface without :svo"
    (let [warning-output (with-out-str
                           (binding [*err* *out*]
                             (registry/register! #"bad metadata step"
                                                 (fn [] nil)
                                                 {:ns 't :file "t.clj" :line 1}
                                                 {:interface :web})))]
      (is (re-find #"(?i)warning" warning-output))
      (is (re-find #":interface without :svo" warning-output)))))

(deftest test-metadata-no-warning-complete
  (testing "No warning when metadata has both :interface and :svo"
    (let [warning-output (with-out-str
                           (binding [*err* *out*]
                             (registry/register! #"good metadata step"
                                                 (fn [] nil)
                                                 {:ns 't :file "t.clj" :line 1}
                                                 {:interface :web
                                                  :svo {:subject :$1 :verb :do}})))]
      (is (= "" warning-output)))))

;; -----------------------------------------------------------------------------
;; Task 3.0.4 Acceptance Criteria
;; -----------------------------------------------------------------------------

(deftest test-acceptance-legacy-still-works
  (testing "Task 3.0.4 AC: Legacy defstep still works"
    (registry/defstep #"legacy clicks"
      [ctx]
      ctx)
    (let [sd (first (registry/all-stepdefs))]
      (is (nil? (:metadata sd)))
      (is (= "legacy clicks" (:pattern-src sd))))))

(deftest test-acceptance-with-metadata
  (testing "Task 3.0.4 AC: defstep with metadata"
    (registry/defstep #"(.*) clicks (.*)"
      {:interface :web
       :svo {:subject :$1 :verb :click :object :$2}}
      [ctx s t]
      ctx)
    (let [sd (first (registry/all-stepdefs))]
      (is (map? (:metadata sd)))
      (is (= :web (:interface (:metadata sd))))
      (is (= {:subject :$1 :verb :click :object :$2} (:svo (:metadata sd)))))))

(deftest test-acceptance-lookup-includes-metadata
  (testing "Task 3.0.4 AC: Registry lookup includes metadata"
    (registry/defstep #"(.*) navigates to (.*)"
      {:interface :web
       :svo {:subject :$1 :verb :navigate :object :$2}}
      [ctx s url]
      ctx)
    (let [sd (registry/find-by-pattern "(.*) navigates to (.*)")]
      (is (some? sd))
      (is (= :web (get-in sd [:metadata :interface])))
      (is (= :navigate (get-in sd [:metadata :svo :verb]))))))
