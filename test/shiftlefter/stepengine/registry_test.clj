(ns shiftlefter.stepengine.registry-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.test-helpers.log-capture :as log-capture]))

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
  (testing "Warning is emitted when :interface without :svo (sl-5wj: via tools.logging)"
    (let [logs (log-capture/with-captured-logs
                 #(registry/register! #"bad metadata step"
                                      (fn [] nil)
                                      {:ns 't :file "t.clj" :line 1}
                                      {:interface :web}))]
      (is (some (log-capture/level-msg? :warn ":interface without :svo") logs)
          "warn-level log should mention :interface without :svo"))))

(deftest test-metadata-no-warning-complete
  (testing "No warning when metadata has both :interface and :svo"
    (let [logs (log-capture/with-captured-logs
                 #(registry/register! #"good metadata step"
                                      (fn [] nil)
                                      {:ns 't :file "t.clj" :line 1}
                                      {:interface :web
                                       :svo {:subject :$1 :verb :do}}))]
      (is (empty? (filter (fn [[lvl _]] (= :warn lvl)) logs))
          "no warn-level log should be emitted for complete metadata"))))

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

;; -----------------------------------------------------------------------------
;; GP.001c: step-meta Injection Tests
;; -----------------------------------------------------------------------------

(deftest test-step-meta-available-with-metadata
  (testing "step-meta is available inside step body when metadata present"
    (registry/defstep #"step with meta"
      {:interface :web
       :svo {:subject :$1 :verb :test}}
      [ctx]
      ;; step-meta should be bound to the metadata map
      step-meta)

    (let [sd (first (registry/all-stepdefs))
          step-fn (:fn sd)
          result (step-fn {})]
      (is (map? result) "step-meta should be a map")
      (is (= :web (:interface result)))
      (is (= {:subject :$1 :verb :test} (:svo result))))))

(deftest test-step-meta-nil-without-metadata
  (testing "step-meta is nil for legacy steps without metadata"
    (registry/defstep #"legacy step no meta"
      [ctx]
      step-meta)

    (let [sd (first (registry/all-stepdefs))
          step-fn (:fn sd)
          result (step-fn {})]
      (is (nil? result) "step-meta should be nil for legacy steps"))))

(deftest test-step-meta-interface-access
  (testing "step-meta :interface is accessible"
    (registry/defstep #"interface access test"
      {:interface :mobile
       :svo {:subject :$1 :verb :tap :object :$2}}
      [ctx]
      (:interface step-meta))

    (let [sd (first (registry/all-stepdefs))
          step-fn (:fn sd)
          result (step-fn {})]
      (is (= :mobile result)))))

(deftest test-step-meta-does-not-affect-execution
  (testing "step-meta injection doesn't break step execution"
    (registry/defstep #"compute with meta (\d+)"
      {:interface :web :svo {:subject :$1 :verb :compute}}
      [ctx n-str]
      {:computed (* 2 (parse-long n-str))
       :interface (:interface step-meta)})

    (let [sd (first (registry/all-stepdefs))
          step-fn (:fn sd)
          result (step-fn {} "21")]
      (is (= 42 (:computed result)))
      (is (= :web (:interface result))))))

(deftest test-step-meta-with-multiple-captures
  (testing "step-meta works with multiple regex captures"
    (registry/defstep #"(.*) gives (.*) to (.*)"
      {:interface :api
       :svo {:subject :$1 :verb :give :object :$2}}
      [ctx giver gift receiver]
      {:giver giver
       :gift gift
       :receiver receiver
       :interface (:interface step-meta)})

    (let [sd (first (registry/all-stepdefs))
          step-fn (:fn sd)
          result (step-fn {} "Alice" "book" "Bob")]
      (is (= "Alice" (:giver result)))
      (is (= "book" (:gift result)))
      (is (= "Bob" (:receiver result)))
      (is (= :api (:interface result))))))

;; -----------------------------------------------------------------------------
;; Interface-Keyed Registration (sl-86d — vocabulary symmetry)
;; -----------------------------------------------------------------------------

(deftest test-same-pattern-different-interfaces-ok
  (testing "Same regex under different interfaces registers cleanly — no duplicate error"
    (registry/register! #"(\S+) receives a message"
                        (fn [_s] nil)
                        {:ns 's :file "s.clj" :line 1}
                        {:interface :sms
                         :svo {:subject :$1 :verb :receive :object "message"}})
    (registry/register! #"(\S+) receives a message"
                        (fn [_s] nil)
                        {:ns 's :file "s.clj" :line 2}
                        {:interface :whatsapp
                         :svo {:subject :$1 :verb :receive :object "message"}})
    (registry/register! #"(\S+) receives a message"
                        (fn [_s] nil)
                        {:ns 's :file "s.clj" :line 3}
                        {:interface :email
                         :svo {:subject :$1 :verb :receive :object "message"}})
    (is (= 3 (count (registry/all-stepdefs))))
    (is (= #{:sms :whatsapp :email}
           (into #{} (map #(-> % :metadata :interface) (registry/all-stepdefs)))))))

(deftest test-same-pattern-same-interface-still-duplicate
  (testing "Same regex + same interface still rejects as duplicate"
    (registry/register! #"(\S+) receives a message"
                        (fn [_s] nil)
                        {:ns 's :file "first.clj" :line 1}
                        {:interface :sms
                         :svo {:subject :$1 :verb :receive :object "message"}})
    (try
      (registry/register! #"(\S+) receives a message"
                          (fn [_s] nil)
                          {:ns 's :file "second.clj" :line 42}
                          {:interface :sms
                           :svo {:subject :$1 :verb :receive :object "message"}})
      (is false "Should have thrown on same-pattern-same-interface duplicate")
      (catch Exception e
        (let [msg (.getMessage e)
              data (ex-data e)]
          (is (= :stepdef/duplicate (:type data)))
          (is (= :sms (:interface data)))
          (is (re-find #"(?i)duplicate" msg))
          (is (re-find #":sms" msg) "Error message mentions the interface")
          (is (re-find #"first\.clj:1" msg))
          (is (re-find #"second\.clj:42" msg)))))))

(deftest test-interface-less-stepdef-keyed-independently
  (testing "Stepdef with no :interface metadata coexists with interface-tagged versions"
    ;; Interface-less — legacy "escape hatch" like `pauses for N seconds`
    (registry/register! #"(\S+) receives a message"
                        (fn [_s] nil)
                        {:ns 's :file "legacy.clj" :line 1})
    (registry/register! #"(\S+) receives a message"
                        (fn [_s] nil)
                        {:ns 's :file "sms.clj" :line 1}
                        {:interface :sms
                         :svo {:subject :$1 :verb :receive :object "message"}})
    (is (= 2 (count (registry/all-stepdefs))))
    ;; Two stepdefs, one with interface, one without
    (is (= #{nil :sms}
           (into #{} (map #(-> % :metadata :interface) (registry/all-stepdefs)))))))

(deftest test-two-interface-less-same-pattern-still-duplicate
  (testing "Two interface-less stepdefs with same regex still collide (backward compat)"
    (registry/register! #"pauses for (\d+) seconds"
                        (fn [_n] nil)
                        {:ns 's :file "first.clj" :line 1})
    (try
      (registry/register! #"pauses for (\d+) seconds"
                          (fn [_n] nil)
                          {:ns 's :file "second.clj" :line 1})
      (is false "Should have thrown — both interface-less key to [sig nil]")
      (catch Exception e
        (is (= :stepdef/duplicate (:type (ex-data e))))))))

;; -----------------------------------------------------------------------------
;; Step :svo Metadata Spec Tests (Tier 1) — sl-hse
;; -----------------------------------------------------------------------------
;;
;; A step's :svo metadata declares how regex captures map to subject,
;; verb, frame, object, and per-frame args. The Tier 1 spec validates
;; structural correctness (capture refs look like :$N, keys are
;; well-typed) without consulting the glossary. Glossary cross-checks
;; are Tier 2 (validate.clj). Instrumentation and s/fdef on register!
;; are wired in a later checkpoint; these tests exercise the spec
;; definitions directly.

(deftest capture-ref-spec-test
  (testing ":$N is a valid capture-ref"
    (is (true?  (s/valid? :shiftlefter.stepengine.registry/capture-ref :$1)))
    (is (true?  (s/valid? :shiftlefter.stepengine.registry/capture-ref :$10))))

  (testing "non-:$N keywords are rejected"
    (is (false? (s/valid? :shiftlefter.stepengine.registry/capture-ref :foo)))
    (is (false? (s/valid? :shiftlefter.stepengine.registry/capture-ref :$foo)))
    (is (false? (s/valid? :shiftlefter.stepengine.registry/capture-ref :user/alice)))
    (is (false? (s/valid? :shiftlefter.stepengine.registry/capture-ref "$1"))
        "strings are rejected — capture-ref must be a keyword")))

(deftest stepdef-svo-spec-test
  (testing "valid :svo metadata for a multi-arg frame"
    (is (true? (s/valid? :shiftlefter.stepengine.registry/stepdef-svo
                         {:subject :$1
                          :verb :see
                          :frame :attribute
                          :object :$2
                          :args {:attribute :$3 :value :$4}}))))

  (testing "valid :svo metadata for a zero-arg frame"
    (is (true? (s/valid? :shiftlefter.stepengine.registry/stepdef-svo
                         {:subject :$1 :verb :click :frame :default :object :$2}))))

  (testing "valid :svo metadata for an implicit-object frame (no :object key)"
    (is (true? (s/valid? :shiftlefter.stepengine.registry/stepdef-svo
                         {:subject :$1
                          :verb :resize
                          :frame :dimensions
                          :args {:width :$2 :height :$3}}))
        "implicit-object frames omit :object; the verb's frame-entry supplies it"))

  (testing ":frame is required (this is the migration teeth)"
    (is (false? (s/valid? :shiftlefter.stepengine.registry/stepdef-svo
                          {:subject :$1 :verb :click :object :$2}))
        "old-shape :svo without :frame is rejected"))

  (testing ":subject must be a capture-ref"
    (is (false? (s/valid? :shiftlefter.stepengine.registry/stepdef-svo
                          {:subject :alice :verb :click :frame :default}))
        "literal subject keywords are rejected — must come from a capture")
    (is (false? (s/valid? :shiftlefter.stepengine.registry/stepdef-svo
                          {:subject "$1" :verb :click :frame :default}))
        "string subject is rejected"))

  (testing ":args values must be capture-refs"
    (is (false? (s/valid? :shiftlefter.stepengine.registry/stepdef-svo
                          {:subject :$1 :verb :fill :frame :with
                           :object :$2 :args {:value "literal"}}))
        "literal arg values are rejected — args must reference captures"))

  (testing ":object accepts nil (for verbs whose frame supplies an implicit object)"
    (is (true? (s/valid? :shiftlefter.stepengine.registry/stepdef-svo
                         {:subject :$1 :verb :refresh :frame :default :object nil})))))

(deftest stepdef-svo-explain-test
  (testing "explain-data identifies the missing :frame key"
    (let [explain (s/explain-data :shiftlefter.stepengine.registry/stepdef-svo
                                  {:subject :$1 :verb :click :object :$2})
          problems (:clojure.spec.alpha/problems explain)]
      (is (some? explain))
      (is (some #(re-find #":frame" (pr-str (:pred %))) problems)
          "predicate names :frame as the missing key"))))
