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
  {:project-name "demo" :version "0.5.6" :mode :vanilla
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
