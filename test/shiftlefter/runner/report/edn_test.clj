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
