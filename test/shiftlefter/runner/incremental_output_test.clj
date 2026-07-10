(ns shiftlefter.runner.incremental-output-test
  "sl-dgk: `sl run` must emit each scenario's console line as it COMPLETES, not
   in a post-hoc pass after every scenario has run. The golden test proves the
   final bytes; this proves the TIMING — that dispatch is interleaved with
   execution, which is the whole point (a minutes-to-hours e2e suite showing
   progress instead of silence-then-flood).

   Liveness can't be observed from final output alone, so these tests instrument
   the private per-scenario execution fn to stamp a marker into the same stream
   the console writes to, and assert the interleaving of the two."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.core :as rc]
            [shiftlefter.runner.reporter :as reporter]
            [shiftlefter.stepengine.exec.cleanup :as cleanup]))

(def ^:private step-paths ["test/fixtures/steps/"])
(def ^:private two-scenarios
  ["test/fixtures/features/basic.feature"      ; "Basic passing scenario"
   "test/fixtures/features/failing.feature"])  ; "Step throws exception"

(defn- run-capturing-interleaved-markers
  "Run with each scenario's execution stamping `<<EXEC-DONE name>>` to *err*
   right as it finishes, so the console lines and the execution markers land in
   one ordered buffer. Returns that buffer."
  [opts]
  (let [real @#'cleanup/execute-scenario-with-cleanup
        err (java.io.StringWriter.)]
    (binding [*out* (java.io.StringWriter.) *err* err]
      (with-redefs [cleanup/execute-scenario-with-cleanup
                    (fn [plan o]
                      (let [r (real plan o)]
                        (binding [*out* *err*]
                          (println (str "<<EXEC-DONE " (-> plan :plan/pickle :pickle/name) ">>")))
                        r))]
        (rc/execute! (merge {:step-paths step-paths} opts))))
    (str err)))

(deftest non-verbose-output-is-live-per-scenario
  (testing "each scenario's status line lands right after that scenario finishes"
    (let [out (run-capturing-interleaved-markers {:paths two-scenarios :no-color true})
          idx #(str/index-of out %)]
      (is (< (idx "<<EXEC-DONE Basic passing scenario>>")
             (idx "PASSED Basic passing scenario")
             (idx "<<EXEC-DONE Step throws exception>>")
             (idx "FAILED Step throws exception"))
          "expected exec1 < print1 < exec2 < print2 (interleaved, not batched)"))))

(deftest verbose-output-is-live-per-scenario
  (testing "verbose per-scenario blocks are also emitted as scenarios finish"
    (let [out (run-capturing-interleaved-markers {:paths two-scenarios :no-color true :verbose true})
          idx #(str/index-of out %)]
      (is (< (idx "<<EXEC-DONE Basic passing scenario>>")
             (idx "PASSED Basic passing scenario")
             (idx "<<EXEC-DONE Step throws exception>>")
             (idx "FAILED Step throws exception"))))))

(deftest aggregate-passes-stay-at-end
  (testing "the summary lands AFTER every scenario has executed"
    (let [out (run-capturing-interleaved-markers {:paths two-scenarios :no-color true})]
      (is (< (str/index-of out "<<EXEC-DONE Step throws exception>>")
             (str/index-of out "Failures:")
             (str/index-of out "2 scenario(s):"))
          "failure section and summary need aggregate data, so they follow execution"))))

(defn- run-err [opts]
  (let [err (java.io.StringWriter.)]
    (binding [*out* (java.io.StringWriter.) *err* err]
      (rc/execute! (merge {:step-paths step-paths} opts)))
    (str err)))

(deftest single-release-point-drives-report-plane
  (testing "reporters are driven ONLY through the release point, not the bus"
    ;; A reporter whose on-scenario-complete throws must abort the run loudly —
    ;; proving reporter dispatch runs synchronously on the execution thread
    ;; (an async bus handler would swallow the throw and the run would pass).
    (let [threw-synchronously (atom false)
          probe (reify reporter/Reporter
                  (on-run-start [_ _] nil)
                  (on-scenario-complete [_ _]
                    (reset! threw-synchronously true)
                    (throw (ex-info "reporter on the execution thread" {})))
                  (on-diagnostics [_ _] nil)
                  (on-run-end [_ _] nil))]
      (with-redefs [rc/make-reporters (fn [& _] [probe])]
        (let [result (run-err {:paths ["test/fixtures/features/basic.feature"] :no-color true})]
          (is @threw-synchronously "on-scenario-complete ran")
          (is (str/includes? result "Runner crash")
              "a synchronous throw surfaces as a loud crash, not a swallowed bus error"))))))

(deftest edn-output-remains-non-incremental
  (testing "--edn is a single summary map at end; per-scenario liveness is console-only"
    (let [out (java.io.StringWriter.)]
      (binding [*out* out *err* (java.io.StringWriter.)]
        (rc/execute! {:paths two-scenarios :step-paths step-paths :edn true}))
      (is (= 1 (count (filter #(= \newline %) (str out))))
          "exactly one line: the EDN summary map, printed once at run end"))))
