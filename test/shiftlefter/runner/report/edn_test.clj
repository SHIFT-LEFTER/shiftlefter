(ns shiftlefter.runner.report.edn-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [shiftlefter.runner.report.edn :as report-edn]))

;; -----------------------------------------------------------------------------
;; build-summary Tests
;; -----------------------------------------------------------------------------

(deftest test-build-summary-exit-0
  (testing "Exit 0 (all passed) includes counts"
    (let [result {:scenarios [{:status :passed :steps [{:status :passed}]}
                              {:status :passed :steps [{:status :passed}]}]
                  :counts {:passed 2 :failed 0 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run-123" 0 result {})]
      (is (= "run-123" (:run/id summary)))
      (is (= 0 (:run/exit-code summary)))
      (is (= :passed (:run/status summary)))
      (is (= 2 (-> summary :counts :passed)))
      (is (= 2 (-> summary :counts :scenarios)))
      (is (nil? (:failures summary))))))

(deftest test-build-summary-exit-1
  (testing "Exit 1 (failures) includes failures list"
    (let [result {:scenarios [{:status :passed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000001"
                                                    :pickle/name "Passing"}}
                               :steps [{:status :passed}]}
                              {:status :failed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000002"
                                                    :pickle/name "Failing"}}
                               :steps [{:status :failed
                                        :step {:step/id #uuid "00000000-0000-0000-0000-000000000003"
                                               :step/text "I click button"}
                                        :binding {:source {:file "s.clj" :line 10}}
                                        :error {:type :step/exception
                                                :message "Not found"}}]}]
                  :counts {:passed 1 :failed 1 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run-456" 1 result {})]
      (is (= 1 (:run/exit-code summary)))
      (is (= :failed (:run/status summary)))
      (is (= 1 (count (:failures summary))))
      (let [failure (first (:failures summary))]
        (is (= "I click button" (:step/text failure)))
        (is (= "Failing" (:scenario/name failure)))
        (is (= "Not found" (-> failure :error :message)))))))

(deftest test-build-summary-exit-2
  (testing "Exit 2 (planning failure) includes diagnostics"
    (let [diagnostics {:undefined [{:step {:step/id #uuid "00000000-0000-0000-0000-000000000001"
                                           :step/text "undefined step"}}]
                       :ambiguous []
                       :invalid-arity []
                       :counts {:undefined-count 1 :ambiguous-count 0
                                :invalid-arity-count 0 :total-issues 1}}
          summary (report-edn/build-summary "run-789" 2 nil {:diagnostics diagnostics})]
      (is (= 2 (:run/exit-code summary)))
      (is (= :planning-failed (:run/status summary)))
      (is (some? (:planning summary)))
      (is (= 1 (count (-> summary :planning :issues))))
      (is (= :undefined (-> summary :planning :issues first :type))))))

(deftest test-build-summary-exit-3
  (testing "Exit 3 (crash) includes error"
    (let [error {:type :runner/crash
                 :message "Unexpected error"
                 :exception-class "java.lang.RuntimeException"}
          summary (report-edn/build-summary "run-crash" 3 nil {:error error})]
      (is (= 3 (:run/exit-code summary)))
      (is (= :crashed (:run/status summary)))
      (is (= "Unexpected error" (-> summary :error :message))))))

;; -----------------------------------------------------------------------------
;; prn-summary Tests
;; -----------------------------------------------------------------------------

(deftest test-prn-summary-outputs-to-stdout
  (testing "prn-summary outputs to stdout, not stderr"
    (let [summary {:run/id "x" :run/exit-code 0}
          stdout (with-out-str (report-edn/prn-summary summary))
          stderr (let [sw (java.io.StringWriter.)]
                   (binding [*err* sw]
                     (report-edn/prn-summary summary))
                   (str sw))]
      (is (not (empty? stdout)) "Should output to stdout")
      (is (empty? stderr) "Should not output to stderr"))))

(deftest test-prn-summary-is-valid-edn
  (testing "prn-summary output is valid EDN"
    (let [summary {:run/id "test-run"
                   :run/exit-code 1
                   :run/status :failed
                   :counts {:passed 3 :failed 2 :pending 0 :skipped 0
                            :scenarios 5 :steps 15}
                   :failures [{:step/text "I click" :error {:message "error"}}]}
          stdout (with-out-str (report-edn/prn-summary summary))
          parsed (edn/read-string stdout)]
      (is (= "test-run" (:run/id parsed)))
      (is (= 1 (:run/exit-code parsed)))
      (is (= :failed (:run/status parsed))))))

;; -----------------------------------------------------------------------------
;; Serialization Tests
;; -----------------------------------------------------------------------------

(deftest test-no-throwable-in-output
  (testing "Errors are serialized to maps, no Throwables"
    (let [result {:scenarios [{:status :failed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000001"
                                                    :pickle/name "Test"}}
                               :steps [{:status :failed
                                        :step {:step/id #uuid "00000000-0000-0000-0000-000000000002"
                                               :step/text "step"}
                                        :binding {}
                                        :error {:type :step/exception
                                                :message "Error message"
                                                :exception-class "clojure.lang.ExceptionInfo"
                                                :data {:foo 1}}}]}]
                  :counts {:passed 0 :failed 1 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run" 1 result {})
          stdout (with-out-str (report-edn/prn-summary summary))]
      ;; Should be readable as EDN
      (is (edn/read-string stdout))
      ;; Error should be a map, not a Throwable
      (is (map? (-> summary :failures first :error))))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria from Spec
;; -----------------------------------------------------------------------------

(deftest test-acceptance-edn-on-stdout-console-on-stderr
  (testing "Spec: EDN on stdout, human on stderr, never mixed"
    (let [summary {:run/id "x" :run/exit-code 1 :counts {:passed 0 :failed 1}}
          stdout (with-out-str (report-edn/prn-summary summary))]
      ;; EDN output contains :run/id
      (is (str/includes? stdout ":run/id"))
      ;; EDN can be parsed
      (is (= "x" (:run/id (edn/read-string stdout)))))))

(deftest test-planning-diagnostics-structure
  (testing "Planning diagnostics include issue details"
    (let [diagnostics {:undefined [{:step {:step/id #uuid "00000000-0000-0000-0000-000000000001"
                                           :step/text "I do undefined thing"}}]
                       :ambiguous [{:step {:step/id #uuid "00000000-0000-0000-0000-000000000002"
                                           :step/text "I have 5 items"}
                                    :alternatives [{:stepdef/id "sd-1" :pattern-src ".*" :source {:file "a.clj"}}
                                                   {:stepdef/id "sd-2" :pattern-src ".*" :source {:file "b.clj"}}]}]
                       :invalid-arity [{:step {:step/id #uuid "00000000-0000-0000-0000-000000000003"
                                               :step/text "bad arity step"}
                                        :binding {:expected #{2 3} :actual 5}}]
                       :counts {:undefined-count 1 :ambiguous-count 1
                                :invalid-arity-count 1 :total-issues 3}}
          summary (report-edn/build-summary "run" 2 nil {:diagnostics diagnostics})
          issues (-> summary :planning :issues)]
      (is (= 3 (count issues)))
      (is (some #(= :undefined (:type %)) issues))
      (is (some #(= :ambiguous (:type %)) issues))
      (is (some #(= :invalid-arity (:type %)) issues))
      ;; Ambiguous includes alternatives
      (let [ambig (first (filter #(= :ambiguous (:type %)) issues))]
        (is (= 2 (count (:alternatives ambig)))))
      ;; Invalid arity includes arity info
      (let [bad-arity (first (filter #(= :invalid-arity (:type %)) issues))]
        (is (= 5 (-> bad-arity :arity :actual)))))))

(deftest test-planning-diagnostics-full-location
  (testing "Planning diagnostics include full location (uri, line, column)"
    (let [diagnostics {:undefined [{:step {:step/id #uuid "00000000-0000-0000-0000-000000000001"
                                           :step/text "I do undefined thing"
                                           :step/location {:line 15 :column 7}}
                                    :source-file "features/test.feature"}]
                       :ambiguous []
                       :invalid-arity []
                       :counts {:undefined-count 1 :ambiguous-count 0
                                :invalid-arity-count 0 :total-issues 1}}
          summary (report-edn/build-summary "run" 2 nil {:diagnostics diagnostics})
          issue (-> summary :planning :issues first)]
      (is (= :undefined (:type issue)))
      (is (= "I do undefined thing" (:step/text issue)))
      ;; Full location fields (WI-031.012)
      (is (= "features/test.feature" (:uri issue)))
      (is (= 15 (:line issue)))
      (is (= 7 (:column issue))))))

;; -----------------------------------------------------------------------------
;; SVO Diagnostics Tests (WI-031.002)
;; -----------------------------------------------------------------------------

(deftest test-svo-issues-in-exit-2
  (testing "Exit 2 (planning failure) includes SVO issues alongside binding issues"
    (let [diagnostics {:undefined []
                       :ambiguous []
                       :invalid-arity []
                       :svo-issues [{:type :svo/unknown-subject
                                     :subject :alcie
                                     :known [:alice :admin :guest]
                                     :suggestion :alice
                                     :location {:step-text "When Alcie clicks the button"
                                                :step-id #uuid "00000000-0000-0000-0000-000000000001"}}]
                       :counts {:undefined-count 0
                                :ambiguous-count 0
                                :invalid-arity-count 0
                                :svo-issue-count 1
                                :total-issues 0}}
          summary (report-edn/build-summary "run" 2 nil {:diagnostics diagnostics})]
      (is (= 2 (:run/exit-code summary)))
      (is (= :planning-failed (:run/status summary)))
      (is (some? (:planning summary)))
      ;; SVO issues included
      (is (= 1 (count (-> summary :planning :svo-issues))))
      (is (= :svo/unknown-subject (-> summary :planning :svo-issues first :type)))
      (is (= :alcie (-> summary :planning :svo-issues first :subject))))))

(deftest test-svo-issues-in-exit-0
  (testing "Exit 0 (passed) includes :diagnostics key when SVO issues present"
    (let [result {:scenarios [{:status :passed :steps [{:status :passed}]}]
                  :counts {:passed 1 :failed 0 :pending 0 :skipped 0}}
          diagnostics {:undefined []
                       :ambiguous []
                       :invalid-arity []
                       :svo-issues [{:type :svo/unknown-verb
                                     :verb :smash
                                     :known [:click :fill :see]
                                     :location {:step-text "When Alice smashes the button"
                                                :step-id #uuid "00000000-0000-0000-0000-000000000002"}}]
                       :counts {:undefined-count 0
                                :ambiguous-count 0
                                :invalid-arity-count 0
                                :svo-issue-count 1
                                :total-issues 0}}
          summary (report-edn/build-summary "run" 0 result {:diagnostics diagnostics})]
      (is (= 0 (:run/exit-code summary)))
      (is (= :passed (:run/status summary)))
      ;; :diagnostics key present with SVO issues
      (is (some? (:diagnostics summary)))
      (is (= 1 (count (-> summary :diagnostics :svo-issues))))
      (is (= :svo/unknown-verb (-> summary :diagnostics :svo-issues first :type)))
      (is (= 1 (-> summary :diagnostics :counts :svo-issue-count))))))

(deftest test-no-diagnostics-key-when-empty
  (testing "Exit 0 (passed) omits :diagnostics key when no SVO issues"
    (let [result {:scenarios [{:status :passed :steps [{:status :passed}]}]
                  :counts {:passed 1 :failed 0 :pending 0 :skipped 0}}
          ;; Empty diagnostics (vanilla mode or no issues)
          diagnostics {:undefined []
                       :ambiguous []
                       :invalid-arity []
                       :svo-issues []
                       :counts {:undefined-count 0
                                :ambiguous-count 0
                                :invalid-arity-count 0
                                :svo-issue-count 0
                                :total-issues 0}}
          summary (report-edn/build-summary "run" 0 result {:diagnostics diagnostics})]
      (is (= 0 (:run/exit-code summary)))
      ;; No :diagnostics key (no noise on clean runs)
      (is (nil? (:diagnostics summary))))))

(deftest test-no-diagnostics-key-vanilla-mode
  (testing "Exit 0 (vanilla mode) omits :diagnostics key entirely"
    (let [result {:scenarios [{:status :passed :steps [{:status :passed}]}]
                  :counts {:passed 1 :failed 0 :pending 0 :skipped 0}}
          ;; No diagnostics passed (vanilla mode)
          summary (report-edn/build-summary "run" 0 result {})]
      (is (= 0 (:run/exit-code summary)))
      ;; No :diagnostics key
      (is (nil? (:diagnostics summary))))))

(deftest test-svo-issues-in-exit-1
  (testing "Exit 1 (failures) includes both failures and diagnostics"
    (let [result {:scenarios [{:status :failed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000001"
                                                    :pickle/name "Test"}}
                               :steps [{:status :failed
                                        :step {:step/id #uuid "00000000-0000-0000-0000-000000000002"
                                               :step/text "When Alice clicks"}
                                        :binding {:source {:file "s.clj" :line 10}}
                                        :error {:type :step/exception :message "Not found"}}]}]
                  :counts {:passed 0 :failed 1 :pending 0 :skipped 0}}
          diagnostics {:undefined []
                       :ambiguous []
                       :invalid-arity []
                       :svo-issues [{:type :svo/unknown-interface
                                     :interface :foobar
                                     :known [:web :api]
                                     :location {:step-text "When Alice clicks"
                                                :step-id #uuid "00000000-0000-0000-0000-000000000002"}}]
                       :counts {:undefined-count 0
                                :ambiguous-count 0
                                :invalid-arity-count 0
                                :svo-issue-count 1
                                :total-issues 0}}
          summary (report-edn/build-summary "run" 1 result {:diagnostics diagnostics})]
      (is (= 1 (:run/exit-code summary)))
      (is (= :failed (:run/status summary)))
      ;; Has both failures and diagnostics
      (is (= 1 (count (:failures summary))))
      (is (some? (:diagnostics summary)))
      (is (= 1 (count (-> summary :diagnostics :svo-issues)))))))

;; -----------------------------------------------------------------------------
;; Macro Provenance Tests (WI-031.003)
;; -----------------------------------------------------------------------------

(deftest test-edn-failure-includes-macro-provenance
  (testing "Failed macro step includes :macro field with provenance"
    (let [result {:scenarios [{:status :failed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000001"
                                                    :pickle/name "Login test"
                                                    :pickle/source-file "features/login.feature"}}
                               :steps [{:status :failed
                                        :step {:step/id #uuid "00000000-0000-0000-0000-000000000002"
                                               :step/text "I click login button"
                                               :step/macro {:role :expanded
                                                            :key "login as alice"
                                                            :call-site {:line 12 :column 5}
                                                            :definition-step {:file "macros/auth.ini"
                                                                              :line 5
                                                                              :column 3}}}
                                        :binding {:source {:file "src/steps/auth.clj" :line 42}}
                                        :error {:type :step/exception :message "Not found"}}]}]
                  :counts {:passed 0 :failed 1 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run" 1 result {})]
      (is (= 1 (count (:failures summary))))
      (let [failure (first (:failures summary))]
        ;; Has :macro key
        (is (some? (:macro failure)))
        ;; Macro key value
        (is (= "login as alice" (-> failure :macro :key)))
        ;; Call-site with assembled file path
        (is (= "features/login.feature" (-> failure :macro :call-site :file)))
        (is (= 12 (-> failure :macro :call-site :line)))
        (is (= 5 (-> failure :macro :call-site :column)))
        ;; Definition-step (for expanded role)
        (is (= "macros/auth.ini" (-> failure :macro :definition-step :file)))
        (is (= 5 (-> failure :macro :definition-step :line)))))))

(deftest test-edn-failure-wrapper-has-call-site-only
  (testing "Failed wrapper step has call-site but no definition-step"
    (let [result {:scenarios [{:status :failed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000001"
                                                    :pickle/name "Test"
                                                    :pickle/source-file "features/test.feature"}}
                               :steps [{:status :failed
                                        :step {:step/id #uuid "00000000-0000-0000-0000-000000000002"
                                               :step/text "login as alice"
                                               :step/synthetic? true
                                               :step/macro {:role :call
                                                            :key "login as alice"
                                                            :call-site {:line 8 :column 5}}}
                                        :binding {}
                                        :error {:type :step/exception :message "Failed"}}]}]
                  :counts {:passed 0 :failed 1 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run" 1 result {})]
      #_{:clj-kondo/ignore [:redundant-let]}
      (let [failure (first (:failures summary))]
        ;; Has :macro with call-site
        (is (some? (:macro failure)))
        (is (= "login as alice" (-> failure :macro :key)))
        (is (some? (-> failure :macro :call-site)))
        ;; No definition-step for :call role
        (is (nil? (-> failure :macro :definition-step)))))))

(deftest test-edn-failure-non-macro-no-macro-key
  (testing "Non-macro step failure has no :macro key"
    (let [result {:scenarios [{:status :failed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000001"
                                                    :pickle/name "Basic test"
                                                    :pickle/source-file "features/basic.feature"}}
                               :steps [{:status :failed
                                        :step {:step/id #uuid "00000000-0000-0000-0000-000000000002"
                                               :step/text "I do something"}
                                        :binding {:source {:file "steps.clj" :line 10}}
                                        :error {:type :step/exception :message "Error"}}]}]
                  :counts {:passed 0 :failed 1 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run" 1 result {})]
      #_{:clj-kondo/ignore [:redundant-let]}
      (let [failure (first (:failures summary))]
        ;; No :macro key
        (is (nil? (:macro failure)))
        ;; Still has other fields
        (is (= "I do something" (:step/text failure)))
        (is (some? (:location failure)))))))
