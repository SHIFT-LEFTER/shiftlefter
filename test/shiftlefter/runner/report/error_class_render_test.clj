(ns shiftlefter.runner.report.error-class-render-test
  "Rendering of the sl-kowi error class across the report plane: the
   envelope carries `:error/class`; console renders UNOBSERVED / FAILED
   (SUT error) / the class line / the halt line; JUnit prefixes the
   message; the EDN summary carries :unobserved and :halted."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.xml :as xml]
            [shiftlefter.runner.report.console :as console]
            [shiftlefter.runner.report.edn :as report-edn]
            [shiftlefter.runner.report.junit :as ju]
            [shiftlefter.runner.reporter :as reporter]))

(defmacro ^:private with-err-str [& body]
  `(let [sw# (java.io.StringWriter.)]
     (binding [*err* sw#] ~@body)
     (str sw#)))

(def ^:private run-ctx
  {:project-name "demo" :version "0.5.6"
   :started-at "2026-08-30T18:00:00" :hostname "testhost"
   :project-root "/proj" :allow-pending? false})

(defn- pickle [name]
  {:pickle/id (java.util.UUID/randomUUID)
   :pickle/name name
   :pickle/feature-name "F"
   :pickle/source-file "/proj/features/x.feature"
   :pickle/location {:line 2}})

(def ^:private dark-error
  {:type :step/exception :message "connection refused"
   :exception-class "clojure.lang.ExceptionInfo"
   :data {:error/class :observation}
   :error/class :observation})

(def ^:private sut-error
  {:type :step/exception :message "HTTP 500"
   :exception-class "clojure.lang.ExceptionInfo"
   :data {:status 500 :error/class :sut}
   :error/class :sut})

(defn- unobserved-scenario
  "An observation-class scenario through the REAL envelope seam: step
   :failed carrying the class, scenario :error with the same map."
  []
  (reporter/scenario-envelope
   {:status :error
    :duration-ms 2.0
    :error dark-error
    :plan {:plan/pickle (pickle "Dark")}
    :steps [{:step {:step/keyword "Given" :step/text "the service answers"}
             :status :failed :duration-ms 1.0 :error dark-error}]}))

(defn- sut-scenario []
  (reporter/scenario-envelope
   {:status :failed
    :duration-ms 2.0
    :plan {:plan/pickle (pickle "Five hundred")}
    :steps [{:step {:step/keyword "Given" :step/text "the checkout completes"}
             :status :failed :duration-ms 1.0 :error sut-error}]}))

(deftest envelope-carries-the-class
  (let [s (unobserved-scenario)]
    (is (= :observation (-> s :error :error/class)) "scenario-level attribution")
    (is (= :observation (-> s :steps first :error :error/class)) "step-level error"))
  (is (= :sut (-> (sut-scenario) :steps first :error :error/class))))

(deftest console-status-tokens
  (testing "UNOBSERVED is distinct from ERROR and FAILED"
    (let [out (with-err-str (console/print-scenario! (unobserved-scenario) {:no-color true}))]
      (is (str/starts-with? out "UNOBSERVED Dark"))
      (is (not (str/includes? out "ERROR")))))
  (testing "a :sut failure names the class on the status line"
    (let [out (with-err-str (console/print-scenario! (sut-scenario) {:no-color true}))]
      (is (str/starts-with? out "FAILED (SUT error) Five hundred"))))
  (testing "an unclassified scenario line is byte-identical to before"
    (let [plain (assoc-in (sut-scenario) [:steps 0 :error] (dissoc sut-error :error/class))
          out (with-err-str (console/print-scenario! plain {:no-color true}))]
      (is (= "FAILED Five hundred\n" out)))))

(deftest console-failure-block-carries-the-class-line
  (let [out (with-err-str (console/print-failures! [(unobserved-scenario) (sut-scenario)]
                                                   {:no-color true}))]
    (is (str/includes? out "class: observation — no answer existed"))
    (is (str/includes? out "class: sut — the app answered with an error"))
    (is (not (str/includes? out "Scenario error"))
        "the step block carries it once; the identical scenario map is suppressed")))

(deftest console-summary-unobserved-and-halt-line
  (testing "the unobserved segment sits between failed and error; the total includes it"
    (let [out (with-err-str
                (console/print-summary! {:counts {:passed 2 :failed 1 :unobserved 3 :error 1
                                                  :pending 0 :skipped 0}}
                                        {:no-color true}))]
      (is (str/includes? out "7 scenario(s): 2 passed, 1 failed, 3 unobserved, 1 error"))
      (is (not (str/includes? out "halted")))))
  (testing "the halt line renders only when the record is present"
    (let [out (with-err-str
                (console/print-summary! {:counts {:passed 1 :failed 0 :unobserved 1
                                                  :pending 0 :skipped 3}
                                         :halted {:after-scenario 1 :scenario "A"}}
                                        {:no-color true}))]
      (is (str/includes? out "5 scenario(s): 1 passed, 1 unobserved, 3 skipped"))
      (is (str/includes? out "halted after scenario 1 (A): observation failure")))))

;; -----------------------------------------------------------------------------
;; sl-617t: the abort vocabulary, every surface — HELD rows, the abort
;; line, the JUnit held: prefix, the EDN :halted record with its reason.
;; -----------------------------------------------------------------------------

(defn- held-scenario
  "A scenario the abort held — before it ran (`after-step` nil) or at a
   step boundary — through the REAL envelope seam."
  [name after-step]
  (reporter/scenario-envelope
   {:status :skipped
    :held (cond-> {:reason :aborted} after-step (assoc :after-step after-step))
    :plan {:plan/pickle (pickle name)}
    :steps [{:step {:step/keyword "Given" :step/text "the first thing"}
             :status (if after-step :passed :skipped)}
            {:step {:step/keyword "And" :step/text "the second thing"}
             :status :skipped}]}))

(deftest console-held-tokens-and-abort-line
  (testing "HELD (aborted) / HELD (aborted after step k) — not plain SKIPPED"
    (is (str/starts-with?
         (with-err-str (console/print-scenario! (held-scenario "Never ran" nil) {:no-color true}))
         "HELD (aborted) Never ran"))
    (is (str/starts-with?
         (with-err-str (console/print-scenario! (held-scenario "Cut short" 1) {:no-color true}))
         "HELD (aborted after step 1) Cut short")))
  (testing "the summary: the held segment and the abort line in the halt slot"
    (let [out (with-err-str
                (console/print-summary! {:counts {:passed 2 :failed 0 :pending 0
                                                  :skipped 0 :held 3}
                                         :halted {:reason :aborted :after-scenario 2}}
                                        {:no-color true}))]
      (is (str/includes? out "5 scenario(s): 2 passed, 3 held"))
      (is (str/includes? out "aborted after scenario 2: 3 scenario(s) held"))
      (is (not (str/includes? out "observation failure"))))))

(deftest junit-held-messages
  (let [xml-str (ju/build-document [(held-scenario "Never ran" nil)
                                    (held-scenario "Cut short" 1)]
                                   run-ctx)]
    (is (str/includes? xml-str "<skipped message=\"held: aborted before this scenario ran\""))
    (is (str/includes? xml-str "<skipped message=\"held: aborted after step 1 of 2\""))
    (is (str/includes? xml-str "skipped=\"2\"") "held scenarios ride the skipped tally")))

(deftest edn-summary-carries-the-abort-record
  (let [result {:scenarios [(held-scenario "Never ran" nil)]
                :counts {:passed 1 :failed 0 :pending 0 :skipped 0 :held 1}}
        summary (report-edn/build-summary "run-a" 4 result
                                          {:halted {:reason :aborted :after-scenario 1}})]
    (is (= {:reason :aborted :after-scenario 1} (:halted summary)))
    (is (= 1 (-> summary :counts :held)))
    (is (= :degraded (:run/status summary)))))

(deftest junit-message-prefixes
  (let [xml-str (ju/build-document [(unobserved-scenario) (sut-scenario)] run-ctx)
        parsed (xml/parse (java.io.ByteArrayInputStream. (.getBytes ^String xml-str "UTF-8")))]
    (is (str/includes? xml-str "message=\"[unobserved] connection refused\"")
        "an observation-class scenario error is prefixed")
    (is (str/includes? xml-str "<error type=\"step/exception\"")
        "the type attribute keeps the error type")
    (is (str/includes? xml-str "message=\"[SUT error] the checkout completes -- HTTP 500\"")
        "a :sut failed step is prefixed")
    (is (= :testsuites (:tag parsed)) "still parses")))

(deftest edn-summary-carries-unobserved-and-halted
  (let [scenarios [(unobserved-scenario)]
        result {:scenarios scenarios
                :counts {:passed 0 :failed 0 :pending 0 :skipped 2 :unobserved 1}}]
    (testing "the halt record rides the executed summary only when present"
      (let [summary (report-edn/build-summary "run-h" 4 result
                                              {:halted {:after-scenario 1 :scenario "Dark"}})]
        (is (= 4 (:run/exit-code summary)))
        (is (= 1 (-> summary :counts :unobserved)))
        (is (= {:after-scenario 1 :scenario "Dark"} (:halted summary)))
        (is (= :observation (-> summary :failures first :error :error/class))
            "the failure entry carries the class")))
    (testing "absent-when-not: no :halted key without the record"
      (let [summary (report-edn/build-summary "run-n" 4 result {})]
        (is (not (contains? summary :halted)))))))

;; -----------------------------------------------------------------------------
;; sl-190x: the hold-family vocabulary on every surface — HELD (blocked …)
;; rows with the MEASURED outcome (C2), the XPASS row + failure, the held
;; breakdown in the summary, JUnit's skipped/failure, the EDN record.
;; -----------------------------------------------------------------------------

(def ^:private blocked-hold
  {:reason :blocked :tag "@blocked=PROJ-1" :ref "PROJ-1"
   :ref-url "https://tracker/PROJ-1"})

(defn- blocked-held-scenario
  "A @blocked scenario that failed as claimed — status as measured
   (:failed here; :error for an unobserved one), :held with :outcome."
  [name outcome-status]
  (reporter/scenario-envelope
   (if (= :unobserved outcome-status)
     {:status :error
      :error dark-error
      :held (assoc blocked-hold :outcome {:status :unobserved :at-step 1
                                          :message "connection refused"})
      :plan {:plan/pickle (pickle name)}
      :steps [{:step {:step/keyword "Given" :step/text "the service answers"}
               :status :failed :error dark-error}]}
     {:status :failed
      :held (assoc blocked-hold :outcome {:status :failed :at-step 2
                                          :message "count mismatch"})
      :plan {:plan/pickle (pickle name)}
      :steps [{:step {:step/keyword "Given" :step/text "one"} :status :passed}
              {:step {:step/keyword "Then" :step/text "two"} :status :failed
               :error {:type :step/exception :message "count mismatch"}}]})))

(defn- xpass-scenario [name]
  (reporter/scenario-envelope
   {:status :failed
    :xpass (assoc blocked-hold :message "blocked scenario passed: remove @blocked=PROJ-1 or reopen PROJ-1")
    :plan {:plan/pickle (pickle name)}
    :steps [{:step {:step/keyword "Given" :step/text "one"} :status :passed}]}))

(deftest console-blocked-tokens
  (testing "HELD (blocked by REF — <measured>) — the row says what was measured"
    (is (str/starts-with?
         (with-err-str (console/print-scenario! (blocked-held-scenario "Tracked" :failed) {:no-color true}))
         "HELD (blocked by PROJ-1 — failed at step 2: count mismatch) Tracked"))
    (is (str/starts-with?
         (with-err-str (console/print-scenario! (blocked-held-scenario "Dark" :unobserved) {:no-color true}))
         "HELD (blocked by PROJ-1 — unobserved at step 1: connection refused) Dark")))
  (testing "XPASS is a FAILED row with the reason, and the failure block is the imperative + link"
    (is (str/starts-with?
         (with-err-str (console/print-scenario! (xpass-scenario "Fixed") {:no-color true}))
         "FAILED (blocked scenario passed) Fixed"))
    (let [out (with-err-str (console/print-failures! [(xpass-scenario "Fixed")
                                                      (blocked-held-scenario "Tracked" :failed)]
                                                     {:no-color true}))]
      (is (str/includes? out "Scenario: Fixed\n  blocked scenario passed: remove @blocked=PROJ-1 or reopen PROJ-1\n  https://tracker/PROJ-1"))
      (is (not (str/includes? out "Tracked")) "a held scenario is not a failure of this run")))
  (testing "the summary: the held breakdown by kind, the XPASS count on failed"
    (let [out (with-err-str
                (console/print-summary! {:counts {:passed 2 :failed 1 :pending 0 :skipped 0
                                                  :held 4 :xpass 1}
                                         :held [{:scenario "a" :reason :wip :tag "@wip"}
                                                {:scenario "b" :reason :skip :tag "@skip=x" :note "x"}
                                                {:scenario "c" :reason :skip :tag "@skip"}
                                                {:scenario "d" :reason :blocked :tag "@blocked=P" :ref "P"
                                                 :outcome {:status :failed}}]}
                                        {:no-color true}))]
      (is (str/includes? out "7 scenario(s): 2 passed, 1 failed (1 XPASS), 4 held (1 wip, 2 skip, 1 blocked)")))))

(deftest junit-hold-family-messages
  (let [xml-str (ju/build-document [(blocked-held-scenario "Tracked" :failed)
                                    (blocked-held-scenario "Dark" :unobserved)
                                    (xpass-scenario "Fixed")]
                                   run-ctx
                                   [{:scenario "Draft" :feature "F" :uri "/proj/features/x.feature"
                                     :line 9 :reason :wip :tag "@wip"}
                                    {:scenario "Flaky" :feature "F" :uri "/proj/features/x.feature"
                                     :line 12 :reason :skip :tag "@skip=flaky-on-ci" :note "flaky-on-ci"}])
        parsed (xml/parse (java.io.ByteArrayInputStream. (.getBytes ^String xml-str "UTF-8")))]
    (is (str/includes? xml-str "<skipped message=\"held: blocked by PROJ-1 — failed at step 2: count mismatch\""))
    (is (str/includes? xml-str "<skipped message=\"held: blocked by PROJ-1 — unobserved at step 1: connection refused\""))
    (is (str/includes? xml-str "<failure type=\"xpass\" message=\"blocked scenario passed: remove @blocked=PROJ-1 or reopen PROJ-1\""))
    (is (str/includes? xml-str "<skipped message=\"held: @wip\""))
    (is (str/includes? xml-str "<skipped message=\"held: @skip=flaky-on-ci\""))
    (is (str/includes? xml-str "tests=\"5\""))
    (is (str/includes? xml-str "skipped=\"4\""))
    (is (str/includes? xml-str "failures=\"1\""))
    (is (= :testsuites (:tag parsed)) "still parses")))

(deftest edn-summary-carries-the-hold-family
  (let [held-vec [{:scenario "Draft" :feature "F" :uri "x" :line 9 :reason :wip :tag "@wip"}
                  {:scenario "Tracked" :feature "F" :uri "x" :line 20 :reason :blocked
                   :tag "@blocked=PROJ-1" :ref "PROJ-1" :ref-url "https://tracker/PROJ-1"
                   :outcome {:status :failed :at-step 2 :message "count mismatch"}}]
        result {:scenarios [(blocked-held-scenario "Tracked" :failed) (xpass-scenario "Fixed")]
                :counts {:passed 0 :failed 1 :pending 0 :skipped 0 :held 2 :xpass 1}}
        summary (report-edn/build-summary "run-x" 1 result {:held held-vec})]
    (is (= 1 (:run/exit-code summary)) "XPASS is exit 1")
    (is (= held-vec (:held summary)) "the one :held record, every kind with its facts")
    (is (= 1 (-> summary :counts :xpass)))
    (is (= [{:type :xpass :tag "@blocked=PROJ-1" :ref "PROJ-1" :ref-url "https://tracker/PROJ-1"
             :message "blocked scenario passed: remove @blocked=PROJ-1 or reopen PROJ-1"}]
           (mapv #(dissoc % :scenario/name :scenario/id) (:failures summary)))
        "the XPASS failure entry carries the imperative; the held scenario contributes none")
    (is (= "Fixed" (-> summary :failures first :scenario/name)))))
