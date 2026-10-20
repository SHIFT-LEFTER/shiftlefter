(ns shiftlefter.stepengine.exec-abort-test
  "The run abort at the engine (sl-617t): the process-level request is
   read at run-or-skip's gate check and at every step boundary.

   (a) the registry/state machine: idle without a run, idempotent request,
       cold two-strike, resets.
   (b) the step boundary: a scenario whose step requests the abort stops
       BEFORE its next step — never inside one — keeps its executed steps,
       is held `{:reason :aborted :after-step k}`, and its After hooks run.
   (c) execute-suite, sequential and parallel: scenarios after the request
       are held without starting, the suite's :halted record is
       `{:reason :aborted :after-scenario N}`, :counts carves :held out of
       :skipped, in-flight scenarios finish (no interrupt), and the crash
       finally is never taken (the gate stays nil; no shutdownNow)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.stepengine.exec :as exec]
            [shiftlefter.stepengine.exec-test :as et]
            [shiftlefter.stepengine.exec.abort :as abort]))

;; Every test runs inside a registered run and leaves the registry clean —
;; the abort state is JVM-wide by design.
(use-fixtures :each (fn [f] (abort/begin-run!) (try (f) (finally (abort/end-run!)))))

(defn- step-plan
  "A plan of `fns`, one 0-arity step each; the plan's :plan/hooks carry an
   After that records into `log` when given."
  [fns & {:keys [log after]}]
  (cond-> (et/make-plan (mapv #(et/make-bound-step % 0 []) fns))
    after (assoc :plan/hooks
                 [{:name "trace"
                   :registration {:path "/proj/hooks.clj"}
                   :tag-source {:file "features/h.feature" :line 1}
                   :after (fn [_payload] (swap! log conj [:after after]) nil)}])))

;; -----------------------------------------------------------------------------
;; (a) the registry
;; -----------------------------------------------------------------------------

(deftest registry-state-machine
  (testing "no run registered => idle, nothing recorded"
    (abort/end-run!)
    (is (= {:status :idle} (abort/request!)))
    (is (not (abort/requested?)))
    (is (= {:outcome :no-run} (abort/cold-sigint!))))
  (testing "a registered run accepts exactly one request; the second re-reports"
    (abort/begin-run!)
    (abort/register-results-dir! "/tmp/run-1")
    (abort/scenario-started!)
    (is (= {:status :aborting :in-flight 1 :results-dir "/tmp/run-1"} (abort/request!)))
    (is (abort/requested?))
    (is (= :already-aborting (:status (abort/request!))))
    (abort/scenario-finished!)
    (is (= 0 (:in-flight (abort/request!))) "in-flight counts down, never below zero"))
  (testing "begin-run! forgets a previous run's request"
    (abort/begin-run!)
    (is (not (abort/requested?)))
    (is (nil? (abort/results-dir)))))

(deftest cold-two-strike
  (let [epilogue (fn [_t] :ran)]
    (abort/register-crash-epilogue! epilogue)
    (abort/register-results-dir! "/tmp/run-2")
    (testing "strike one requests the abort and carries the line's inputs"
      (let [r (abort/cold-sigint!)]
        (is (= :aborting (:outcome r)))
        (is (= "/tmp/run-2" (:results-dir r)))
        (is (abort/requested?))))
    (testing "strike two is the crash row, carrying the epilogue and the C1 flag"
      (let [r (abort/cold-sigint!)]
        (is (= :crash (:outcome r)))
        (is (false? (:finalizing? r)))
        (is (identical? epilogue (:crash-epilogue r)))))
    (testing "strike three and later are ignored"
      (is (= {:outcome :ignored} (abort/cold-sigint!)))
      (is (= {:outcome :ignored} (abort/cold-sigint!))))
    (testing "finalizing! flips the C1 flag a later strike two would see"
      (abort/begin-run!)
      (abort/cold-sigint!)
      (abort/finalizing!)
      (is (true? (:finalizing? (abort/cold-sigint!)))))))

(deftest announce-line-shape
  (is (= (str "aborting: 2 scenario(s) finishing their current step, then Afters,"
              " cleanup, :stop; reports will land at /r")
         (abort/announce-line {:in-flight 2 :results-dir "/r"})))
  (is (re-find #"reports will land at the configured report paths$"
               (abort/announce-line {:in-flight 0 :results-dir nil}))
      "no results dir yet: the line still says where to look"))

;; -----------------------------------------------------------------------------
;; (b) the step boundary
;; -----------------------------------------------------------------------------

(deftest abort-stops-a-running-scenario-at-its-next-step-boundary
  (let [log (atom [])
        plan (step-plan [(fn [] (swap! log conj :s1) nil)
                         (fn [] (swap! log conj :s2) (abort/request!) nil)
                         (fn [] (swap! log conj :s3) nil)]
                        :log log :after :cleanup)
        result (exec/execute-suite [plan] {})
        scenario (-> result :scenarios first)]
    (testing "the step that asked finished; the next never started; Afters ran"
      (is (= [:s1 :s2 [:after :cleanup]] @log)))
    (testing "the scenario has no verdict: :skipped and held after step 2"
      (is (= :skipped (:status scenario)))
      (is (= {:reason :aborted :after-step 2} (:held scenario)))
      (is (= [:passed :passed :skipped] (mapv :status (:steps scenario)))
          "executed steps keep their results; the rest are :skipped"))
    (testing "the suite carries the abort record and the carved counts"
      (is (= {:reason :aborted :after-scenario 0} (:halted result))
          "zero scenarios reached a verdict")
      (is (= {:passed 0 :failed 0 :pending 0 :skipped 0 :held 1} (:counts result))))))

(deftest abort-leaves-an-already-red-scenario-its-verdict
  (let [plan (step-plan [(fn [] (throw (ex-info "designed failure" {})))
                         (fn [] (abort/request!) nil)])
        _ (abort/request!)
        result (exec/execute-suite [(step-plan [(fn [] nil)])] {})]
    (testing "a request before the first step holds the scenario at step 0"
      (is (= {:reason :aborted} (-> result :scenarios first :held))
          "run-or-skip's gate check held it before it started"))
    (abort/begin-run!)
    (let [result (exec/execute-suite [plan] {})
          scenario (-> result :scenarios first)]
      (is (= :failed (:status scenario)) "fail-fast already decided; the abort changes nothing")
      (is (nil? (:held scenario))))))

;; -----------------------------------------------------------------------------
;; (c) execute-suite
;; -----------------------------------------------------------------------------

(deftest sequential-abort-holds-the-rest-and-records-the-halt
  (let [log (atom [])
        plans [(step-plan [(fn [] (swap! log conj :a) nil)])
               (step-plan [(fn [] (swap! log conj :b) (abort/request!) nil)
                           (fn [] (swap! log conj :b2) nil)])
               (step-plan [(fn [] (swap! log conj :c) nil)])
               (step-plan [(fn [] (swap! log conj :d) nil)])]
        result (exec/execute-suite plans {})]
    (is (= [:a :b] @log) "B stopped at its boundary; C and D never started")
    (is (= [:passed :skipped :skipped :skipped] (mapv :status (:scenarios result))))
    (is (= [nil {:reason :aborted :after-step 1} {:reason :aborted} {:reason :aborted}]
           (mapv :held (:scenarios result)))
        "the cut scenario says after which step; the unstarted ones just :aborted")
    (is (= {:reason :aborted :after-scenario 1} (:halted result)) "A reached a verdict")
    (is (= {:passed 1 :failed 0 :pending 0 :skipped 0 :held 3} (:counts result)))
    (is (= :failed (:status result)) "an aborted suite is not :passed")))

(deftest abort-wins-over-an-observation-halt-record
  (let [gate (atom nil)
        plans [(step-plan [(fn [] (throw (ex-info "dark" {:error/class :observation})))])
               (step-plan [(fn [] nil)])]
        _ (abort/request!)
        result (exec/execute-suite plans {:halt-on-observation-failure true
                                          :halt-gate gate})]
    (is (nil? @gate) "the gate never armed: nothing ran to trigger it")
    (is (= {:reason :aborted :after-scenario 0} (:halted result)))))

(defn- settle
  "Poll `pred` for up to `ms`; true as soon as it holds."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 25) (recur))))))

(deftest parallel-abort-lets-in-flight-finish-and-holds-the-queue
  (testing "under :max-parallel 2 the abort holds every queued scenario, the
            in-flight ones finish their current step (no interrupt), the
            gate stays unarmed (the crash finally is never taken), and the
            record counts only verdicts"
    (let [log (atom [])
          gate (atom nil)
          release (promise)
          ;; A blocks until released (in flight when the abort lands); B
          ;; requests the abort on its step; C..F are queued behind them.
          plans (into [(step-plan [(fn []
                                     (swap! log conj :a-start)
                                     (deref release 10000 :timeout)
                                     (swap! log conj :a-end)
                                     nil)])
                       (step-plan [(fn []
                                     (settle #(some #{:a-start} @log) 5000)
                                     (swap! log conj :b)
                                     (abort/request!)
                                     (deliver release :go)
                                     nil)])]
                      (map (fn [tag] (step-plan [(fn [] (swap! log conj tag) nil)]))
                           [:c :d :e :f]))
          result (exec/execute-suite plans {:max-parallel 2 :halt-gate gate})]
      (is (some #{:a-end} @log) "A, in flight at the request, ran to completion")
      (is (not-any? #{:c :d :e :f} @log) "no queued scenario ever started")
      (is (nil? @gate) "no crash arming: the abort went through the normal drain")
      (is (= [:passed :passed :skipped :skipped :skipped :skipped]
             (mapv :status (:scenarios result))))
      (is (= {:reason :aborted :after-scenario 2} (:halted result))
          "A and B both reached verdicts")
      (is (= {:passed 2 :failed 0 :pending 0 :skipped 0 :held 4} (:counts result))))))
