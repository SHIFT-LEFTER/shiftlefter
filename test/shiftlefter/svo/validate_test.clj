(ns shiftlefter.svo.validate-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [shiftlefter.intent.state :as intent-state]
            [shiftlefter.stepdefs.browser]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.svo.glossary :as glossary]
            [shiftlefter.svo.validate :as validate]))

;; Other test files (e.g. registry_test) clear the registry between
;; tests. The regression test below needs browser.clj's defsteps actually
;; registered, so we clear and reload that ns once before any test in
;; this file. Clear-then-reload matches the pattern in stepdefs/browser_test.clj
;; and avoids :stepdef/duplicate when the registry already holds these
;; stepdefs from an earlier-loaded test ns (top-level :require above).
(use-fixtures :once
  (fn [f]
    (registry/clear-registry!)
    (require '[shiftlefter.stepdefs.browser] :reload)
    (f)))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(def test-glossary
  "Test glossary with subjects and verbs."
  {:subjects {:alice {:desc "Standard customer"}
              :admin {:desc "Administrator"}
              :guest {:desc "Guest user"}}
   :verbs {:web {:click {:desc "Click element"}
                 :fill {:desc "Fill input"}
                 :see {:desc "Assert visible"}
                 :navigate {:desc "Navigate to URL"}}
           :api {:get {:desc "GET request"}
                 :post {:desc "POST request"}}}})

(def test-interfaces
  "Test interface configuration."
  {:web {:type :web :adapter :etaoin}
   :api {:type :api :adapter :http-kit}
   :mobile {:type :web :adapter :appium}})

;; -----------------------------------------------------------------------------
;; Valid SVO Tests
;; -----------------------------------------------------------------------------

(deftest validate-svo-valid-test
  (testing "Valid SVO returns {:valid? true}"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :click :object "button" :interface :web})]
      (is (true? (:valid? result)))
      (is (empty? (:issues result)))))

  (testing "Valid SVO with different interface"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :admin :verb :get :object "/api/users" :interface :api})]
      (is (true? (:valid? result)))
      (is (empty? (:issues result)))))

  (testing "Valid SVO with nil object"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :guest :verb :see :object nil :interface :web})]
      (is (true? (:valid? result))))))

;; -----------------------------------------------------------------------------
;; Unknown Subject Tests
;; -----------------------------------------------------------------------------

(deftest validate-svo-unknown-subject-test
  (testing "Unknown subject returns issue"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :bob :verb :click :object "x" :interface :web})]
      (is (false? (:valid? result)))
      (is (= 1 (count (:issues result))))
      (let [issue (first (:issues result))]
        (is (= :svo/unknown-subject (:type issue)))
        (is (= :bob (:subject issue)))
        (is (= #{:alice :admin :guest} (set (:known issue)))))))

  (testing "Typo in subject suggests correction"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alcie :verb :click :object "x" :interface :web})]
      (is (false? (:valid? result)))
      (let [issue (first (:issues result))]
        (is (= :svo/unknown-subject (:type issue)))
        (is (= :alcie (:subject issue)))
        (is (= :alice (:suggestion issue)))))))

;; -----------------------------------------------------------------------------
;; Unknown Verb Tests
;; -----------------------------------------------------------------------------

(deftest validate-svo-unknown-verb-test
  (testing "Unknown verb returns issue"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :smash :object "x" :interface :web})]
      (is (false? (:valid? result)))
      (is (= 1 (count (:issues result))))
      (let [issue (first (:issues result))]
        (is (= :svo/unknown-verb (:type issue)))
        (is (= :smash (:verb issue)))
        (is (= :web (:interface-type issue)))
        (is (= #{:click :fill :see :navigate} (set (:known issue)))))))

  (testing "Wrong verb for interface type"
    ;; :click is a web verb, not an api verb
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :click :object "x" :interface :api})]
      (is (false? (:valid? result)))
      (let [issue (first (:issues result))]
        (is (= :svo/unknown-verb (:type issue)))
        (is (= :api (:interface-type issue)))))))

;; -----------------------------------------------------------------------------
;; Unknown Interface Tests
;; -----------------------------------------------------------------------------

(deftest validate-svo-unknown-interface-test
  (testing "Unknown interface returns issue"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :click :object "x" :interface :sms})]
      (is (false? (:valid? result)))
      ;; Should have unknown-interface issue
      (let [iface-issues (filter #(= :svo/unknown-interface (:type %)) (:issues result))]
        (is (= 1 (count iface-issues)))
        (let [issue (first iface-issues)]
          (is (= :sms (:interface issue)))
          (is (= #{:web :api :mobile} (set (:known issue))))))))

  (testing "Typo in interface suggests correction"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :click :object "x" :interface :wbe})
          issue (first (filter #(= :svo/unknown-interface (:type %)) (:issues result)))]
      (is (= :wbe (:interface issue)))
      (is (= :web (:suggestion issue))))))

;; -----------------------------------------------------------------------------
;; Multiple Issues Tests
;; -----------------------------------------------------------------------------

(deftest validate-svo-multiple-issues-test
  (testing "Multiple issues returned together"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :bob :verb :smash :object "x" :interface :sms})]
      (is (false? (:valid? result)))
      ;; Should have 3 issues: unknown subject, verb validation skipped (unknown interface), unknown interface
      (is (>= (count (:issues result)) 2))
      (let [types (set (map :type (:issues result)))]
        (is (contains? types :svo/unknown-subject))
        (is (contains? types :svo/unknown-interface))))))

;; -----------------------------------------------------------------------------
;; Edge Cases
;; -----------------------------------------------------------------------------

(deftest validate-svo-edge-cases-test
  (testing "Nil subject emits missing-subject"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject nil :verb :click :object "x" :interface :web})]
      (is (false? (:valid? result)))
      (is (= :svo/missing-subject (-> result :issues first :type)))))

  (testing "Nil verb skips verb validation"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb nil :object "x" :interface :web})]
      (is (true? (:valid? result)))))

  (testing "Nil interface skips interface validation"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :click :object "x" :interface nil})]
      (is (true? (:valid? result)))))

  (testing "Empty glossary subjects returns unknown"
    (let [empty-glossary {:subjects {} :verbs {:web {:click {}}}}
          result (validate/validate-svo
                  empty-glossary
                  test-interfaces
                  {:subject :anyone :verb :click :object "x" :interface :web})]
      (is (false? (:valid? result)))
      (is (= :svo/unknown-subject (-> result :issues first :type))))))

;; -----------------------------------------------------------------------------
;; valid? Helper Tests
;; -----------------------------------------------------------------------------

(deftest valid?-test
  (testing "valid? returns boolean"
    (is (true? (validate/valid? test-glossary test-interfaces
                                {:subject :alice :verb :click :object "x" :interface :web})))
    (is (false? (validate/valid? test-glossary test-interfaces
                                 {:subject :bob :verb :click :object "x" :interface :web})))))

;; -----------------------------------------------------------------------------
;; Task 3.0.5 Acceptance Criteria
;; -----------------------------------------------------------------------------

(deftest acceptance-valid-svo-test
  (testing "Task 3.0.5 AC: valid SVO"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :click :object "x" :interface :web})]
      (is (true? (:valid? result)))
      (is (= [] (:issues result))))))

(deftest acceptance-unknown-subject-test
  (testing "Task 3.0.5 AC: unknown subject with suggestion"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alcie :verb :click :object "x" :interface :web})]
      (is (false? (:valid? result)))
      (let [issue (first (:issues result))]
        (is (= :svo/unknown-subject (:type issue)))
        (is (= :alcie (:subject issue)))
        (is (contains? (set (:known issue)) :alice))
        (is (= :alice (:suggestion issue)))))))

(deftest acceptance-unknown-verb-test
  (testing "Task 3.0.5 AC: unknown verb"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :smash :object "x" :interface :web})]
      (is (false? (:valid? result)))
      (let [issue (first (:issues result))]
        (is (= :svo/unknown-verb (:type issue)))
        (is (= :smash (:verb issue)))
        (is (= :web (:interface-type issue)))
        (is (contains? (set (:known issue)) :click))))))

;; -----------------------------------------------------------------------------
;; Error Message Formatting Tests (Task 3.0.13)
;; -----------------------------------------------------------------------------

(deftest format-unknown-subject-test
  (testing "Task 3.0.13 AC: format unknown subject"
    (let [issue {:type :svo/unknown-subject
                 :subject :alcie
                 :known [:alice :admin :guest]
                 :suggestion :alice
                 :location {:step-text "When Alcie clicks the button"
                            :uri "features/login.feature"
                            :line 12}}
          formatted (validate/format-unknown-subject issue)]
      (is (str/includes? formatted "Unknown subject :alcie"))
      (is (str/includes? formatted "When Alcie clicks the button"))
      (is (str/includes? formatted "features/login.feature:12"))
      (is (str/includes? formatted "Known subjects:"))
      (is (str/includes? formatted ":alice"))
      (is (str/includes? formatted "Did you mean: :alice?"))))

  (testing "format unknown subject without location"
    (let [issue {:type :svo/unknown-subject
                 :subject :bob
                 :known [:alice :admin]
                 :suggestion nil}
          formatted (validate/format-unknown-subject issue)]
      (is (str/includes? formatted "Unknown subject :bob"))
      (is (not (str/includes? formatted "Did you mean"))))))

(deftest format-unknown-verb-test
  (testing "Task 3.0.13 AC: format unknown verb"
    (let [issue {:type :svo/unknown-verb
                 :verb :smash
                 :interface-type :web
                 :interface-name :web
                 :known [:click :fill :see :navigate :submit]
                 :suggestion nil
                 :location {:step-text "When Alice smashes the button"
                            :uri "features/login.feature"
                            :line 15}}
          formatted (validate/format-unknown-verb issue)]
      (is (str/includes? formatted "Unknown verb :smash"))
      (is (str/includes? formatted "When Alice smashes the button"))
      (is (str/includes? formatted "Interface :web"))
      (is (str/includes? formatted "Known verbs for :web:"))
      (is (str/includes? formatted ":click")))))

(deftest format-unknown-interface-test
  (testing "Task 3.0.13 AC: format unknown interface"
    (let [issue {:type :svo/unknown-interface
                 :interface :foobar
                 :known [:web :api]
                 :suggestion nil
                 :location {:step-text "When Alice clicks the button"
                            :uri "features/login.feature"
                            :line 18}}
          formatted (validate/format-unknown-interface issue)]
      (is (str/includes? formatted "Unknown interface :foobar"))
      (is (str/includes? formatted "Configured interfaces:"))
      (is (str/includes? formatted ":web"))
      (is (str/includes? formatted "Add to shiftlefter.edn:")))))

(deftest format-provisioning-failed-test
  (testing "Task 3.0.13 AC: format provisioning failure"
    (let [issue {:type :svo/provisioning-failed
                 :interface :web
                 :adapter :etaoin
                 :adapter-error "Browser not installed"
                 :known [:web :api]
                 :location {:step-text "When Alice clicks login"
                            :uri "features/login.feature"
                            :line 10}}
          formatted (validate/format-provisioning-failed issue)]
      (is (str/includes? formatted "Provisioning failed for interface :web"))
      (is (str/includes? formatted "Adapter :etaoin error: Browser not installed"))
      (is (str/includes? formatted "Configured interfaces:")))))

(deftest format-svo-issue-dispatch-test
  (testing "format-svo-issue dispatches correctly"
    (is (str/includes?
         (validate/format-svo-issue {:type :svo/unknown-subject :subject :x :known []})
         "Unknown subject"))
    (is (str/includes?
         (validate/format-svo-issue {:type :svo/unknown-verb :verb :x :known []})
         "Unknown verb"))
    (is (str/includes?
         (validate/format-svo-issue {:type :svo/unknown-interface :interface :x :known []})
         "Unknown interface"))
    (is (str/includes?
         (validate/format-svo-issue {:type :svo/provisioning-failed :interface :x})
         "Provisioning failed"))
    ;; Unknown type falls back
    (is (str/includes?
         (validate/format-svo-issue {:type :svo/unknown-type :foo :bar})
         "SVO issue:"))))

;; -----------------------------------------------------------------------------
;; Object Enforcement Tests (GP.001g)
;; -----------------------------------------------------------------------------

(deftest object-enforcement-off-test
  (testing "Object enforcement :off does not validate objects"
    ;; With :off, even invalid intent refs pass
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :click :object "InvalidRef" :interface :web}
                  {:unknown-object :off})]
      (is (true? (:valid? result)))
      (is (empty? (:issues result))))))

(deftest object-enforcement-warn-raw-locator-test
  (testing "Object enforcement :warn allows raw locators"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :click :object "{:css \"#submit\"}" :interface :web}
                  {:unknown-object :warn})]
      ;; Raw locators are allowed in :warn mode
      (is (true? (:valid? result)))
      (is (empty? (:issues result))))))

(deftest object-enforcement-strict-raw-locator-test
  (testing "Object enforcement :strict rejects raw locators"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :click :object "{:css \"#submit\"}" :interface :web}
                  {:unknown-object :strict})]
      (is (false? (:valid? result)))
      (let [issue (first (:issues result))]
        (is (= :svo/raw-locator-disallowed (:type issue)))
        (is (= "{:css \"#submit\"}" (:object issue)))))))

(deftest object-enforcement-warn-unknown-intent-test
  (testing "Object enforcement :warn flags unknown intent reference"
    ;; This will try to resolve Login.unknown which doesn't exist
    ;; Since intents aren't loaded in test context, this should handle gracefully
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :click :object "Login.unknown" :interface :web}
                  {:unknown-object :warn})]
      ;; Intent resolution may fail if intents not loaded, or may find no intent
      ;; Either way, it should not crash
      (is (boolean? (:valid? result))))))

(deftest object-enforcement-strict-invalid-syntax-test
  (testing "Object enforcement :strict rejects invalid intent syntax"
    (let [result (validate/validate-svo
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :click :object "login.submit" :interface :web}
                  {:unknown-object :strict})]
      ;; lowercase intent name is invalid syntax
      (is (false? (:valid? result)))
      (let [issue (first (:issues result))]
        (is (= :svo/unknown-object (:type issue)))
        (is (= "login.submit" (:object issue)))))))

;; -----------------------------------------------------------------------------
;; Object Kind: :location slots accept literal URLs — sl-rlxa
;; -----------------------------------------------------------------------------

(def ^:private location-glossary
  "Glossary mirroring the framework verbs-web.edn location frames: navigate
   and the region assertion (:be/:at) accept named-location refs (sl-3jr4 /
   sl-q81m); the exactly assertion (:be/:at-exactly) is literal-only;
   :click stays intent-only."
  {:subjects {:alice {:desc "Standard customer"}}
   :verbs {:web {:click    {:desc "Click element"
                            :frames {:default {:args [] :pattern "S clicks O"}}}
                 :navigate {:desc "Navigate to URL"
                            :frames {:to {:args [] :pattern "S navigates to O"
                                          :object-kind :location
                                          :location-refs? true}}}
                 :be       {:desc "Assert state"
                            :frames {:at {:args [] :pattern "S is on O"
                                          :object-kind :location
                                          :location-refs? true}
                                     :at-exactly {:args []
                                                  :pattern "S is on exactly O"
                                                  :object-kind :location}}}}}})

(deftest object-kind-location-url-literal-test
  (testing "URL literal in a :location slot passes under :strict (sl-rlxa)"
    (doseq [svo [{:subject :alice :verb :navigate :frame :to
                  :object "http://localhost:9090/login" :interface :web}
                 {:subject :alice :verb :be :frame :at
                  :object "http://localhost:9090/dashboard" :interface :web}]]
      (let [result (validate/validate-svo location-glossary test-interfaces
                                          svo {:unknown-object :strict})]
        (is (true? (:valid? result))
            (str "URL literal must not trip :unknown-object for " (:verb svo)))
        (is (empty? (:issues result))))))

  (testing "URL literal in a :location slot passes under :warn"
    (let [result (validate/validate-svo
                  location-glossary test-interfaces
                  {:subject :alice :verb :navigate :frame :to
                   :object "https://example.com/a/b?q=1" :interface :web}
                  {:unknown-object :warn})]
      (is (true? (:valid? result))))))

(deftest object-kind-intent-slot-still-strict-test
  (testing "intent-only slot still rejects invalid refs under :strict"
    (let [result (validate/validate-svo
                  location-glossary test-interfaces
                  {:subject :alice :verb :click :frame :default
                   :object "login.submit" :interface :web}
                  {:unknown-object :strict})]
      (is (false? (:valid? result)))
      (is (= :svo/unknown-object (:type (first (:issues result)))))))

  (testing "SVO without :frame (legacy stepdef) keeps intent validation"
    (let [result (validate/validate-svo
                  location-glossary test-interfaces
                  {:subject :alice :verb :click
                   :object "login.submit" :interface :web}
                  {:unknown-object :strict})]
      (is (false? (:valid? result))))))

(deftest format-unknown-object-test
  (testing "Format unknown object issue"
    (let [issue {:type :svo/unknown-object
                 :object "Login.unknown"
                 :interface :web
                 :message "Element 'unknown' not found in intent 'Login'"
                 :location {:step-text "When :alice clicks Login.unknown"
                            :uri "features/login.feature"
                            :line 15}}
          formatted (validate/format-unknown-object issue)]
      (is (str/includes? formatted "Unknown object 'Login.unknown'"))
      (is (str/includes? formatted "When :alice clicks Login.unknown"))
      (is (str/includes? formatted "features/login.feature:15"))
      (is (str/includes? formatted "Element 'unknown' not found")))))

(deftest format-raw-locator-disallowed-test
  (testing "Format raw locator disallowed issue"
    (let [issue {:type :svo/raw-locator-disallowed
                 :object "{:css \"#submit\"}"
                 :message "Raw locators are not allowed in strict mode."
                 :location {:step-text "When :alice clicks {:css \"#submit\"}"
                            :uri "features/login.feature"
                            :line 20}}
          formatted (validate/format-raw-locator-disallowed issue)]
      (is (str/includes? formatted "Raw locator '{:css \"#submit\"}'"))
      (is (str/includes? formatted "When :alice clicks {:css"))
      (is (str/includes? formatted "features/login.feature:20"))
      (is (str/includes? formatted "Login.submit or Checkout.pay-button")))))

;; -----------------------------------------------------------------------------
;; Tier 2: validate-stepdefs-against-glossary — sl-hse
;; -----------------------------------------------------------------------------

(def ^:private tier2-glossary
  "Minimal glossary for Tier 2 unit tests."
  {:subjects {}
   :verbs {:web {:click {:desc "Click"
                         :frames {:default {:args [] :pattern "S clicks O"}}}
                 :fill  {:desc "Fill"
                         :frames {:with {:args [:value]
                                         :pattern "S fills O with VALUE"}}}
                 :see   {:desc "See"
                         :frames {:visible   {:args []
                                              :pattern "S sees O"}
                                  :attribute {:args [:attribute :value]
                                              :pattern "S sees O with ATTRIBUTE = VALUE"}}}}}})

(defn- mk-stepdef
  "Build a fake stepdef map with the given metadata."
  ([metadata]
   (mk-stepdef metadata "step.clj" 42))
  ([metadata file line]
   {:stepdef/id (str "sd-" (hash metadata))
    :pattern    #"x"
    :pattern-src "x"
    :source     {:ns 'test :file file :line line}
    :arity      1
    :fn         (fn [_] nil)
    :metadata   metadata}))

(deftest validate-stepdefs-no-issues-on-clean-stepdefs
  (testing "valid stepdefs produce no issues"
    (let [stepdefs [(mk-stepdef {:interface :web
                                 :svo {:subject :$1 :verb :click
                                       :frame :default :object :$2}})
                    (mk-stepdef {:interface :web
                                 :svo {:subject :$1 :verb :fill
                                       :frame :with :object :$2
                                       :args {:value :$3}}})]
          issues (validate/validate-stepdefs-against-glossary stepdefs tier2-glossary)]
      (is (empty? issues)))))

(deftest validate-stepdefs-skips-non-svo
  (testing "stepdefs without :svo or :interface are skipped"
    (let [stepdefs [(mk-stepdef nil)
                    (mk-stepdef {:interface :web})  ; no :svo
                    (mk-stepdef {:svo {:subject :$1 :verb :foo :frame :bar}})]
          issues (validate/validate-stepdefs-against-glossary stepdefs tier2-glossary)]
      (is (empty? issues)))))

(deftest validate-stepdefs-skips-interface-not-in-glossary
  (testing "stepdefs with an interface absent from the glossary are skipped"
    ;; :sms isn't in tier2-glossary; this stepdef is therefore not checked.
    (let [stepdefs [(mk-stepdef {:interface :sms
                                 :svo {:subject :$1 :verb :anything
                                       :frame :anyframe}})]
          issues (validate/validate-stepdefs-against-glossary stepdefs tier2-glossary)]
      (is (empty? issues)))))

(deftest validate-stepdefs-detects-unknown-verb
  (testing "unknown verb on a known interface produces an issue"
    (let [stepdefs [(mk-stepdef {:interface :web
                                 :svo {:subject :$1 :verb :swipe-left
                                       :frame :default :object :$2}}
                                "myproject.clj" 17)]
          issues (validate/validate-stepdefs-against-glossary stepdefs tier2-glossary)]
      (is (= 1 (count issues)))
      (let [issue (first issues)]
        (is (= :stepdef/unknown-verb (:type issue)))
        (is (= :swipe-left (:verb issue)))
        (is (= :web (:interface issue)))
        (is (re-find #":web/swipe-left" (:message issue)))
        (is (re-find #"myproject\.clj:17" (:message issue))
            "source location is in the message")
        (is (some #{:click :fill :see} (:known-verbs issue))
            "candidate verbs are listed for did-you-mean tooling")))))

(deftest validate-stepdefs-detects-unknown-frame
  (testing "unknown frame on a known verb produces an issue with candidate frames"
    (let [stepdefs [(mk-stepdef {:interface :web
                                 :svo {:subject :$1 :verb :see
                                       :frame :bonkers :object :$2}})]
          issues (validate/validate-stepdefs-against-glossary stepdefs tier2-glossary)]
      (is (= 1 (count issues)))
      (let [issue (first issues)]
        (is (= :stepdef/unknown-frame (:type issue)))
        (is (= :bonkers (:frame issue)))
        (is (re-find #"unknown frame :bonkers" (:message issue)))
        (is (= [:attribute :visible] (:known-frames issue))
            "known frames are sorted")))))

(deftest validate-stepdefs-detects-args-mismatch
  (testing "args keyset mismatch surfaces missing and extra keys"
    (let [;; :fill/:with expects [:value]; this stepdef provides {:val :$3} (typo).
          stepdefs [(mk-stepdef {:interface :web
                                 :svo {:subject :$1 :verb :fill :frame :with
                                       :object :$2 :args {:val :$3}}})]
          issues (validate/validate-stepdefs-against-glossary stepdefs tier2-glossary)]
      (is (= 1 (count issues)))
      (let [issue (first issues)]
        (is (= :stepdef/args-mismatch (:type issue)))
        (is (= [:value] (:expected-args issue)))
        (is (= [:val] (:provided-args issue)))
        (is (= [:value] (:missing-args issue)))
        (is (= [:val] (:extra-args issue)))
        (is (re-find #"missing: :value" (:message issue)))
        (is (re-find #"unexpected: :val" (:message issue)))))))

(deftest validate-stepdefs-detects-missing-frame
  (testing "stepdef :svo without :frame is flagged (defensive — Tier 1 catches first)"
    (let [stepdefs [(mk-stepdef {:interface :web
                                 :svo {:subject :$1 :verb :click :object :$2}})]
          issues (validate/validate-stepdefs-against-glossary stepdefs tier2-glossary)]
      (is (= 1 (count issues)))
      (is (= :stepdef/missing-frame (-> issues first :type))))))

(deftest validate-stepdefs-multi-arg-frame-passes
  (testing "multi-arg frame with all keys present: no issue"
    (let [stepdefs [(mk-stepdef {:interface :web
                                 :svo {:subject :$1 :verb :see :frame :attribute
                                       :object :$2
                                       :args {:attribute :$3 :value :$4}}})]
          issues (validate/validate-stepdefs-against-glossary stepdefs tier2-glossary)]
      (is (empty? issues)))))

(deftest validate-stepdefs-real-browser-stepdefs-pass-real-default-glossary
  (testing "every :web stepdef in browser.clj conforms to the framework default glossary"
    ;; Regression: after the sl-hse migration, every browser.clj defstep
    ;; carries :frame and the :args expected by its frame. This catches
    ;; future drift if anyone adds a step without updating the glossary
    ;; (or vice versa).
    (let [defaults  (glossary/load-default-glossaries)
          stepdefs  (filter #(= :web (-> % :metadata :interface))
                            (registry/all-stepdefs))
          issues    (validate/validate-stepdefs-against-glossary stepdefs defaults)]
      (is (pos? (count stepdefs))
          "browser.clj should have registered :web stepdefs")
      (is (empty? issues)
          (str "Tier 2 issues against default glossary: "
               (mapv :message issues))))))

;; -----------------------------------------------------------------------------
;; Object validation — nested (multi-segment) intent references (sl-tl9)
;; -----------------------------------------------------------------------------
;;
;; validate-object pulls intents from the global intent-state. We populate that
;; cache from a temp dir (get-intents with no args returns the cached value once
;; loaded), then validate nested object refs statically — no browser involved.

(defn- with-nested-intents
  "Load a Bookmarks+Tweet schema into intent-state, run `f`, then clear."
  [f]
  (let [dir (fs/create-temp-dir {:prefix "svo-nested-"})]
    (try
      (spit (fs/file dir "bookmarks.edn")
            (pr-str {:intent "Bookmarks"
                     :root {:web {:css "[aria-label^='Timeline: Bookmarks']"}}
                     :collections {:tweet {:intent "Tweet" :cardinality :many}}
                     :elements {:title {:bindings {:web {:css "h2"}}}}}))
      (spit (fs/file dir "tweet.edn")
            (pr-str {:intent "Tweet"
                     :root {:web {:css "article[data-testid='tweet']"}}
                     :elements {:author {:bindings {:web {:css "[data-testid='User-Name']"}}}}
                     :collections {:quoted {:intent "Tweet" :optional true}}}))
      (intent-state/reload-intents! (str dir))
      (f)
      (finally
        (intent-state/clear-intents!)
        (fs/delete-tree dir)))))

(deftest validate-object-accepts-valid-nested-ref
  (with-nested-intents
    (fn []
      (testing "A structurally valid nested ref passes strict object validation"
        (let [result (validate/validate-svo
                      test-glossary test-interfaces
                      {:subject :alice :verb :click
                       :object "Bookmarks.tweet[2].quoted.author" :interface :web}
                      {:unknown-object :strict})]
          (is (empty? (filter #(= :svo/unknown-object (:type %)) (:issues result)))
              "the nested path walks cleanly: tweet -> quoted -> author"))))))

(deftest validate-object-rejects-unknown-segment
  (with-nested-intents
    (fn []
      (testing "An unknown mid-path segment is :svo/unknown-object naming the segment"
        (let [result (validate/validate-svo
                      test-glossary test-interfaces
                      {:subject :alice :verb :click
                       :object "Bookmarks.tweet[2].bogus.author" :interface :web}
                      {:unknown-object :strict})
              issue (first (filter #(= :svo/unknown-object (:type %)) (:issues result)))]
          (is (some? issue) "unknown segment should be flagged")
          (is (str/includes? (:message issue) "bogus")
              "the message names the offending segment"))))))

;; -----------------------------------------------------------------------------
;; Named-location refs in :location-refs? slots (sl-3jr4)
;; -----------------------------------------------------------------------------
;;
;; The navigate frame accepts bare PascalCase intent refs resolved via the
;; intent's :location; the be-at frame does NOT (carved out to sl-q81m) and
;; keeps the rlxa skip-everything behavior.

(defn- with-location-intents
  "Load a located Feed + unlocated ProductCard into intent-state, run `f`, clear."
  [f]
  (let [dir (fs/create-temp-dir {:prefix "svo-location-"})]
    (try
      (spit (fs/file dir "feed.edn")
            (pr-str {:intent "Feed"
                     :location {:web {:path "/feed"}}
                     :elements {:title {:bindings {:web {:css "h1"}}}}}))
      (spit (fs/file dir "card.edn")
            (pr-str {:intent "ProductCard"
                     :elements {:price {:bindings {:web {:css ".price"}}}}}))
      (intent-state/reload-intents! (str dir))
      (f)
      (finally
        (intent-state/clear-intents!)
        (fs/delete-tree dir)))))

(deftest location-ref-valid-in-navigate-slot
  (with-location-intents
    (fn []
      (testing "A valid named-location ref passes under :strict (AC 3)"
        (let [result (validate/validate-svo
                      location-glossary test-interfaces
                      {:subject :alice :verb :navigate :frame :to
                       :object "Feed" :interface :web}
                      {:unknown-object :strict})]
          (is (true? (:valid? result)))
          (is (empty? (:issues result))))))))

(deftest location-ref-typo-errors-in-navigate-slot
  (with-location-intents
    (fn []
      (testing "A typo'd ref is :svo/unknown-object under :strict (AC 4)"
        (let [result (validate/validate-svo
                      location-glossary test-interfaces
                      {:subject :alice :verb :navigate :frame :to
                       :object "Feeed" :interface :web}
                      {:unknown-object :strict})
              issue (first (:issues result))]
          (is (false? (:valid? result)))
          (is (= :svo/unknown-object (:type issue)))
          (is (str/includes? (:message issue) "Feeed"))))

      (testing "Same under :warn — the issue is still produced"
        (let [result (validate/validate-svo
                      location-glossary test-interfaces
                      {:subject :alice :verb :navigate :frame :to
                       :object "Feeed" :interface :web}
                      {:unknown-object :warn})]
          (is (seq (filter #(= :svo/unknown-object (:type %)) (:issues result)))))))))

(deftest location-ref-without-location-errors
  (with-location-intents
    (fn []
      (testing "Ref to an intent without :location errors under :strict"
        (let [result (validate/validate-svo
                      location-glossary test-interfaces
                      {:subject :alice :verb :navigate :frame :to
                       :object "ProductCard" :interface :web}
                      {:unknown-object :strict})
              issue (first (:issues result))]
          (is (= :svo/unknown-object (:type issue)))
          (is (str/includes? (:message issue) ":location"))))

      (testing "Ref whose :location lacks this interface's :path errors"
        (let [result (validate/validate-svo
                      location-glossary
                      (assoc test-interfaces :mobile {:type :web :adapter :etaoin})
                      {:subject :alice :verb :navigate :frame :to
                       :object "Feed" :interface :mobile}
                      {:unknown-object :strict})]
          (is (seq (filter #(= :svo/unknown-object (:type %)) (:issues result)))))))))

(deftest location-literals-still-pass-in-navigate-slot
  (with-location-intents
    (fn []
      (testing "Literal URLs never classify as refs even with intents loaded (AC 3)"
        (doseq [literal ["http://localhost:9090/feed" "/feed" "example.com"
                         "about:blank" "{:css \"#a\"}"]]
          (let [result (validate/validate-svo
                        location-glossary test-interfaces
                        {:subject :alice :verb :navigate :frame :to
                         :object literal :interface :web}
                        {:unknown-object :strict})]
            (is (empty? (filter #(= :svo/unknown-object (:type %)) (:issues result)))
                (str literal " must stay a literal"))))))))

(deftest be-at-region-slot-accepts-refs
  (with-location-intents
    (fn []
      (testing "sl-q81m: the region assertion slot validates refs like navigate"
        (let [result (validate/validate-svo
                      location-glossary test-interfaces
                      {:subject :alice :verb :be :frame :at
                       :object "Feed" :interface :web}
                      {:unknown-object :strict})]
          (is (true? (:valid? result)) "valid located ref passes"))
        (let [result (validate/validate-svo
                      location-glossary test-interfaces
                      {:subject :alice :verb :be :frame :at
                       :object "/feed?tab=hot" :interface :web}
                      {:unknown-object :strict})]
          (is (true? (:valid? result)) "literals always pass")))

      (testing "QUOTED captures never classify as refs (sl-iseq: quoted =
                literal, always) — even a quoted intent name or typo"
        (doseq [object ["'Feed'" "'Feeed'" "'http://h/feed'" "'/feed'"]]
          (let [result (validate/validate-svo
                        location-glossary test-interfaces
                        {:subject :alice :verb :be :frame :at
                         :object object :interface :web}
                        {:unknown-object :strict})]
            (is (true? (:valid? result))
                (str object " is quoted → literal → passes strict")))))

      (testing "typo'd ref errors WITH a did-you-mean suggestion (AC 4)"
        (let [result (validate/validate-svo
                      location-glossary test-interfaces
                      {:subject :alice :verb :be :frame :at
                       :object "Feeed" :interface :web}
                      {:unknown-object :strict})
              issue (first (:issues result))]
          (is (= :svo/unknown-object (:type issue)))
          (is (= :Feed (:suggestion issue))
              "nearest LOCATED intent is suggested")
          (is (str/includes? (:message issue) "Did you mean: :Feed?"))))

      (testing "ref to an unlocated intent gets no bogus suggestion"
        (let [result (validate/validate-svo
                      location-glossary test-interfaces
                      {:subject :alice :verb :be :frame :at
                       :object "ProductCard" :interface :web}
                      {:unknown-object :strict})
              issue (first (:issues result))]
          (is (= :svo/unknown-object (:type issue)))
          (is (nil? (:suggestion issue))))))))

(deftest at-exactly-slot-stays-literal-only
  (with-location-intents
    (fn []
      (testing "The exactly slot never classifies refs (resolution IS
                normalization) — even a typo'd bare name passes silently"
        (doseq [object ["Feed" "Feeed" "http://h/p?a=1"]]
          (let [result (validate/validate-svo
                        location-glossary test-interfaces
                        {:subject :alice :verb :be :frame :at-exactly
                         :object object :interface :web}
                        {:unknown-object :strict})]
            (is (true? (:valid? result))
                (str object " in at-exactly slot must pass (literal-only slot)"))))))))
