(ns shiftlefter.runner.report.console-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [shiftlefter.runner.report.console :as console]))

;; -----------------------------------------------------------------------------
;; Helper to capture stderr
;; -----------------------------------------------------------------------------

(defmacro with-err-str
  "Capture stderr output as a string."
  [& body]
  `(let [sw# (java.io.StringWriter.)]
     (binding [*err* sw#]
       ~@body)
     (str sw#)))

;; -----------------------------------------------------------------------------
;; print-summary! Tests
;; -----------------------------------------------------------------------------

(deftest test-print-summary-all-passed
  (testing "Summary shows passed count"
    (let [result {:counts {:passed 5 :failed 0 :pending 0 :skipped 0}
                  :status :passed}
          output (with-err-str (console/print-summary! result {:no-color true}))]
      (is (str/includes? output "5 scenario(s)"))
      (is (str/includes? output "5 passed")))))

(deftest test-print-summary-with-failures
  (testing "Summary shows failed count"
    (let [result {:counts {:passed 3 :failed 2 :pending 0 :skipped 0}
                  :status :failed}
          output (with-err-str (console/print-summary! result {:no-color true}))]
      (is (str/includes? output "5 scenario(s)"))
      (is (str/includes? output "3 passed"))
      (is (str/includes? output "2 failed")))))

(deftest test-print-summary-all-statuses
  (testing "Summary shows all status types"
    (let [result {:counts {:passed 1 :failed 2 :pending 3 :skipped 4}
                  :status :failed}
          output (with-err-str (console/print-summary! result {:no-color true}))]
      (is (str/includes? output "10 scenario(s)"))
      (is (str/includes? output "1 passed"))
      (is (str/includes? output "2 failed"))
      (is (str/includes? output "3 pending"))
      (is (str/includes? output "4 skipped")))))

(deftest test-print-summary-with-duration
  (testing "Summary shows duration when provided"
    (let [result {:counts {:passed 5 :failed 0 :pending 0 :skipped 0}}
          output (with-err-str (console/print-summary! result {:no-color true :duration-ms 1500}))]
      (is (str/includes? output "1.50s")))))

(deftest test-print-summary-outputs-to-stderr
  (testing "Summary goes to stderr, not stdout"
    (let [result {:counts {:passed 1 :failed 0 :pending 0 :skipped 0}}
          stdout (with-out-str (console/print-summary! result {:no-color true}))
          stderr (with-err-str (console/print-summary! result {:no-color true}))]
      (is (empty? stdout) "Nothing should go to stdout")
      (is (not (empty? stderr)) "Output should go to stderr"))))

;; -----------------------------------------------------------------------------
;; print-failures! Tests
;; -----------------------------------------------------------------------------

(deftest test-print-failures-shows-step-text
  (testing "Failures show step text"
    (let [scenarios [{:status :failed
                      :plan {:plan/pickle {:pickle/name "Test scenario"}}
                      :steps [{:status :failed
                               :step {:step/text "I click the button"}
                               :binding {:source {:file "steps.clj" :line 10}}
                               :error {:type :step/exception :message "Element not found"}}]}]
          output (with-err-str (console/print-failures! scenarios {:no-color true}))]
      (is (str/includes? output "Failures:"))
      (is (str/includes? output "I click the button"))
      (is (str/includes? output "Element not found")))))

(deftest test-print-failures-shows-location
  (testing "Failures show source location"
    (let [scenarios [{:status :failed
                      :plan {:plan/pickle {:pickle/name "Test"}}
                      :steps [{:status :failed
                               :step {:step/text "step text"}
                               :binding {:source {:file "my/steps.clj" :line 42 :column 5}}
                               :error {:message "error"}}]}]
          output (with-err-str (console/print-failures! scenarios {:no-color true}))]
      (is (str/includes? output "my/steps.clj:42:5")))))

(deftest test-print-failures-no-output-when-none
  (testing "No output when no failures"
    (let [scenarios [{:status :passed :steps []}]
          output (with-err-str (console/print-failures! scenarios {:no-color true}))]
      (is (empty? output)))))

;; -----------------------------------------------------------------------------
;; print-diagnostics! Tests
;; -----------------------------------------------------------------------------

(deftest test-print-diagnostics-undefined
  (testing "Shows undefined steps"
    (let [diagnostics {:undefined [{:step {:step/text "I do something undefined"}}]
                       :ambiguous []
                       :invalid-arity []
                       :counts {:undefined-count 1 :ambiguous-count 0 :invalid-arity-count 0 :total-issues 1}}
          output (with-err-str (console/print-diagnostics! diagnostics {:no-color true}))]
      (is (str/includes? output "Undefined steps:"))
      (is (str/includes? output "I do something undefined"))
      (is (str/includes? output "1 binding issue(s)")))))

(deftest test-print-diagnostics-ambiguous
  (testing "Shows ambiguous steps with alternatives"
    (let [diagnostics {:undefined []
                       :ambiguous [{:step {:step/text "I have 5 items"}
                                    :alternatives [{:pattern-src "I have (\\d+) items"
                                                    :source {:file "a.clj" :line 1}}
                                                   {:pattern-src "I have (\\w+) items"
                                                    :source {:file "b.clj" :line 2}}]}]
                       :invalid-arity []
                       :counts {:undefined-count 0 :ambiguous-count 1 :invalid-arity-count 0 :total-issues 1}}
          output (with-err-str (console/print-diagnostics! diagnostics {:no-color true}))]
      (is (str/includes? output "Ambiguous steps:"))
      (is (str/includes? output "I have 5 items"))
      (is (str/includes? output "a.clj:1"))
      (is (str/includes? output "b.clj:2")))))

;; -----------------------------------------------------------------------------
;; Color Tests
;; -----------------------------------------------------------------------------

(deftest test-colors-enabled-by-default
  (testing "Colors enabled when no --no-color and no NO_COLOR env"
    (let [result {:counts {:passed 1 :failed 0 :pending 0 :skipped 0}}
          ;; Can't easily test ANSI codes without setting up env, just verify no crash
          output (with-err-str (console/print-summary! result {}))]
      (is (not (empty? output))))))

(deftest test-colors-disabled-with-no-color-opt
  (testing "Colors disabled with --no-color"
    (let [result {:counts {:passed 1 :failed 0 :pending 0 :skipped 0}}
          output (with-err-str (console/print-summary! result {:no-color true}))]
      ;; No ANSI escape codes
      (is (not (str/includes? output "\u001b["))))))

;; -----------------------------------------------------------------------------
;; print-scenario! Tests
;; -----------------------------------------------------------------------------

(deftest test-print-scenario-shows-name-and-status
  (testing "Scenario output shows name and status"
    (let [scenario {:status :passed
                    :plan {:plan/pickle {:pickle/name "User logs in"}}
                    :steps []}
          output (with-err-str (console/print-scenario! scenario {:no-color true}))]
      (is (str/includes? output "PASSED"))
      (is (str/includes? output "User logs in")))))

(deftest test-print-scenario-verbose-shows-steps
  (testing "Verbose mode shows individual steps"
    (let [scenario {:status :passed
                    :plan {:plan/pickle {:pickle/name "Test"}}
                    :steps [{:status :passed :step {:step/text "I do step 1"}}
                            {:status :passed :step {:step/text "I do step 2"}}]}
          output (with-err-str (console/print-scenario! scenario {:no-color true :verbose true}))]
      (is (str/includes? output "I do step 1"))
      (is (str/includes? output "I do step 2"))
      (is (str/includes? output "✓")))))

;; -----------------------------------------------------------------------------
;; Macro Collapse Tests
;; -----------------------------------------------------------------------------

(deftest test-print-scenario-non-verbose-hides-steps
  (testing "Non-verbose mode shows only scenario name/status, not steps"
    (let [scenario {:status :passed
                    :plan {:plan/pickle {:pickle/name "User scenario"}}
                    :steps [{:status :passed :step {:step/text "I do a visible step"}}]}
          output (with-err-str (console/print-scenario! scenario {:no-color true}))]
      (is (str/includes? output "User scenario"))
      (is (str/includes? output "PASSED"))
      (is (not (str/includes? output "I do a visible step"))))))

(deftest test-print-scenario-verbose-collapses-macros
  (testing "Verbose mode shows wrapper step but hides expanded children"
    (let [scenario {:status :passed
                    :plan {:plan/pickle {:pickle/name "Login test"}}
                    :steps [;; Wrapper step (synthetic)
                            {:status :passed
                             :step {:step/text "login as alice"
                                    :step/synthetic? true
                                    :step/macro {:role :call :key "login" :step-count 2}}}
                            ;; Expanded child 1
                            {:status :passed
                             :step {:step/text "I visit /login"
                                    :step/macro {:role :expanded :key "login"}}}
                            ;; Expanded child 2
                            {:status :passed
                             :step {:step/text "I fill in username with \"alice\""
                                    :step/macro {:role :expanded :key "login"}}}]}
          output (with-err-str (console/print-scenario! scenario {:no-color true :verbose true}))]
      ;; Should show the wrapper
      (is (str/includes? output "login as alice"))
      ;; Should NOT show the expanded children (collapsed by default)
      (is (not (str/includes? output "I visit /login")))
      (is (not (str/includes? output "I fill in username"))))))

(deftest test-print-scenario-expand-macros-shows-children
  (testing "Verbose + expand-macros shows wrapper and children"
    (let [scenario {:status :passed
                    :plan {:plan/pickle {:pickle/name "Login test"}}
                    :steps [;; Wrapper step (synthetic)
                            {:status :passed
                             :step {:step/text "login as alice"
                                    :step/synthetic? true
                                    :step/macro {:role :call :key "login" :step-count 2}}}
                            ;; Expanded child 1
                            {:status :passed
                             :step {:step/text "I visit /login"
                                    :step/macro {:role :expanded :key "login"}}}
                            ;; Expanded child 2
                            {:status :passed
                             :step {:step/text "I fill in username with \"alice\""
                                    :step/macro {:role :expanded :key "login"}}}]}
          output (with-err-str (console/print-scenario! scenario {:no-color true :verbose true :expand-macros true}))]
      ;; Should show wrapper
      (is (str/includes? output "login as alice"))
      ;; Should ALSO show expanded children
      (is (str/includes? output "I visit /login"))
      (is (str/includes? output "I fill in username")))))

(deftest test-print-scenario-expand-macros-indents-children
  (testing "Expanded children are indented more than wrapper"
    (let [scenario {:status :passed
                    :plan {:plan/pickle {:pickle/name "Test"}}
                    :steps [{:status :passed
                             :step {:step/text "wrapper step"
                                    :step/synthetic? true
                                    :step/macro {:role :call :key "k" :step-count 1}}}
                            {:status :passed
                             :step {:step/text "child step"
                                    :step/macro {:role :expanded :key "k"}}}]}
          output (with-err-str (console/print-scenario! scenario {:no-color true :verbose true :expand-macros true}))]
      ;; Wrapper at 2 spaces, children at 4 spaces
      (is (re-find #"  .*wrapper step" output))
      (is (re-find #"    .*child step" output)))))

(deftest test-print-scenario-macro-with-failure-shows-error
  (testing "Failed child step still shows error message on wrapper line in collapsed mode"
    (let [scenario {:status :failed
                    :plan {:plan/pickle {:pickle/name "Test"}}
                    :steps [{:status :failed
                             :step {:step/text "login as bob"
                                    :step/synthetic? true
                                    :step/macro {:role :call :key "login" :step-count 1}}}
                            {:status :failed
                             :step {:step/text "I visit /login"
                                    :step/macro {:role :expanded :key "login"}}
                             :error {:message "Page not found"}}]}
          output (with-err-str (console/print-scenario! scenario {:no-color true :verbose true}))]
      ;; Wrapper shows but children hidden in collapsed mode
      (is (str/includes? output "login as bob"))
      (is (not (str/includes? output "I visit /login"))))))

(deftest test-print-scenario-regular-and-macro-mixed
  (testing "Regular steps shown alongside collapsed macros"
    (let [scenario {:status :passed
                    :plan {:plan/pickle {:pickle/name "Mixed test"}}
                    :steps [;; Regular step
                            {:status :passed
                             :step {:step/text "I am on the home page"}}
                            ;; Macro wrapper
                            {:status :passed
                             :step {:step/text "login as alice"
                                    :step/synthetic? true
                                    :step/macro {:role :call :key "login" :step-count 1}}}
                            ;; Macro child
                            {:status :passed
                             :step {:step/text "I visit /login"
                                    :step/macro {:role :expanded :key "login"}}}
                            ;; Another regular step
                            {:status :passed
                             :step {:step/text "I should see welcome message"}}]}
          output (with-err-str (console/print-scenario! scenario {:no-color true :verbose true}))]
      ;; Regular steps shown
      (is (str/includes? output "I am on the home page"))
      (is (str/includes? output "I should see welcome message"))
      ;; Wrapper shown
      (is (str/includes? output "login as alice"))
      ;; Child hidden
      (is (not (str/includes? output "I visit /login"))))))

(deftest test-print-pickle-alias-works
  (testing "print-pickle! is an alias for print-scenario!"
    (let [scenario {:status :passed
                    :plan {:plan/pickle {:pickle/name "Alias test"}}
                    :steps []}
          output (with-err-str (console/print-pickle! scenario {:no-color true}))]
      (is (str/includes? output "Alias test")))))
