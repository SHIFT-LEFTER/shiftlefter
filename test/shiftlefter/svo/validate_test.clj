(ns shiftlefter.svo.validate-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [shiftlefter.svo.validate :as validate]))

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
;; Valid SVOI Tests
;; -----------------------------------------------------------------------------

(deftest validate-svoi-valid-test
  (testing "Valid SVOI returns {:valid? true}"
    (let [result (validate/validate-svoi
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :click :object "button" :interface :web})]
      (is (true? (:valid? result)))
      (is (empty? (:issues result)))))

  (testing "Valid SVOI with different interface"
    (let [result (validate/validate-svoi
                  test-glossary
                  test-interfaces
                  {:subject :admin :verb :get :object "/api/users" :interface :api})]
      (is (true? (:valid? result)))
      (is (empty? (:issues result)))))

  (testing "Valid SVOI with nil object"
    (let [result (validate/validate-svoi
                  test-glossary
                  test-interfaces
                  {:subject :guest :verb :see :object nil :interface :web})]
      (is (true? (:valid? result))))))

;; -----------------------------------------------------------------------------
;; Unknown Subject Tests
;; -----------------------------------------------------------------------------

(deftest validate-svoi-unknown-subject-test
  (testing "Unknown subject returns issue"
    (let [result (validate/validate-svoi
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
    (let [result (validate/validate-svoi
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

(deftest validate-svoi-unknown-verb-test
  (testing "Unknown verb returns issue"
    (let [result (validate/validate-svoi
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
    (let [result (validate/validate-svoi
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

(deftest validate-svoi-unknown-interface-test
  (testing "Unknown interface returns issue"
    (let [result (validate/validate-svoi
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
    (let [result (validate/validate-svoi
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :click :object "x" :interface :wbe})
          issue (first (filter #(= :svo/unknown-interface (:type %)) (:issues result)))]
      (is (= :wbe (:interface issue)))
      (is (= :web (:suggestion issue))))))

;; -----------------------------------------------------------------------------
;; Multiple Issues Tests
;; -----------------------------------------------------------------------------

(deftest validate-svoi-multiple-issues-test
  (testing "Multiple issues returned together"
    (let [result (validate/validate-svoi
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

(deftest validate-svoi-edge-cases-test
  (testing "Nil subject skips subject validation"
    (let [result (validate/validate-svoi
                  test-glossary
                  test-interfaces
                  {:subject nil :verb :click :object "x" :interface :web})]
      (is (true? (:valid? result)))))

  (testing "Nil verb skips verb validation"
    (let [result (validate/validate-svoi
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb nil :object "x" :interface :web})]
      (is (true? (:valid? result)))))

  (testing "Nil interface skips interface validation"
    (let [result (validate/validate-svoi
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :click :object "x" :interface nil})]
      (is (true? (:valid? result)))))

  (testing "Empty glossary subjects returns unknown"
    (let [empty-glossary {:subjects {} :verbs {:web {:click {}}}}
          result (validate/validate-svoi
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

(deftest acceptance-valid-svoi-test
  (testing "Task 3.0.5 AC: valid SVOI"
    (let [result (validate/validate-svoi
                  test-glossary
                  test-interfaces
                  {:subject :alice :verb :click :object "x" :interface :web})]
      (is (true? (:valid? result)))
      (is (= [] (:issues result))))))

(deftest acceptance-unknown-subject-test
  (testing "Task 3.0.5 AC: unknown subject with suggestion"
    (let [result (validate/validate-svoi
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
    (let [result (validate/validate-svoi
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
