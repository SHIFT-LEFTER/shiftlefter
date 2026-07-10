(ns shiftlefter.stepengine.exec-parallel-test
  "Unit tests for the sl-q9wp two-phase scheduler (execute-suite at
   :max-parallel > 1) and the plan-order release buffer.

   Timing in these tests is used only to make completion order differ from
   plan order (generous gaps, no upper-bound assertions), never to assert
   how long anything took."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.core :as runner-core]
            [shiftlefter.runner.schedule :as schedule]
            [shiftlefter.stepengine.exec :as exec]
            [shiftlefter.stepengine.exec-test :as et]
            [shiftlefter.stepengine.exec.step-loop :as step-loop]))

(defn- sleeping-plan
  "A plan whose single step sleeps `ms` then records `tag` into `log`
   (as [:begin tag] / [:end tag] pairs)."
  [log tag ms & {:keys [serial?]}]
  (cond-> (et/make-plan
           [(et/make-bound-step
             (fn []
               (swap! log conj [:begin tag])
               (Thread/sleep (long ms))
               (swap! log conj [:end tag])
               {:tag tag})
             0 [])])
    serial? (assoc :plan/schedule {:serial? true :reason :tag})))

(defn- plan-tag
  "Recover the tag a sleeping-plan's step recorded, from a raw result."
  [result]
  (-> result :scenario-ctx :tag))

;; -----------------------------------------------------------------------------
;; Pool semantics
;; -----------------------------------------------------------------------------

(deftest parallel-results-in-plan-order
  (testing ":scenarios is in plan order even when completion order reverses"
    (let [log (atom [])
          ;; Plan-first scenario is slowest: completions arrive reversed.
          plans [(sleeping-plan log :a 300)
                 (sleeping-plan log :b 150)
                 (sleeping-plan log :c 10)]
          result (exec/execute-suite plans {:max-parallel 3})]
      (is (= :passed (:status result)))
      (is (= {:passed 3 :failed 0 :pending 0 :skipped 0} (:counts result)))
      (is (= [:a :b :c] (mapv plan-tag (:scenarios result)))
          "Plan order, not completion order"))))

(deftest parallel-on-complete-actual-order-coordinator-thread
  (testing ":on-scenario-complete fires at actual completion order, on the
            coordinator thread"
    (let [log (atom [])
          completions (atom [])
          coordinator (Thread/currentThread)
          threads (atom #{})
          plans [(sleeping-plan log :slow 400)
                 (sleeping-plan log :fast 10)]
          on-complete (fn [result]
                        (swap! threads conj (Thread/currentThread))
                        (swap! completions conj (plan-tag result)))
          result (exec/execute-suite plans {:max-parallel 2
                                            :on-scenario-complete on-complete})]
      (is (= [:fast :slow] @completions)
          "Actual completion order (fast finishes first)")
      (is (= #{coordinator} @threads)
          "Every callback on the coordinator thread (invariant 1)")
      (is (= [:slow :fast] (mapv plan-tag (:scenarios result)))
          "...while :scenarios stays in plan order"))))

(deftest parallel-mixed-statuses-aggregate-as-sequential
  (testing "counts/status aggregation identical to a sequential run"
    (let [plans [(et/make-plan [(et/make-bound-step (fn [] {:ok 1}) 0 [])])
                 (et/make-plan [(et/make-bound-step
                                 (fn [] (throw (Exception. "boom"))) 0 [])])
                 (et/make-plan [(et/make-bound-step (fn [] :pending) 0 [])])
                 (et/make-plan [(et/make-bound-step (fn [] {:d 4}) 0 [])]
                               :runnable? false)]
          sequential (exec/execute-suite plans {})
          parallel (exec/execute-suite plans {:max-parallel 4})]
      (is (= (:counts sequential) (:counts parallel)))
      (is (= (:status sequential) (:status parallel)))
      (is (= (mapv :status (:scenarios sequential))
             (mapv :status (:scenarios parallel)))))))

;; -----------------------------------------------------------------------------
;; @serial semantics: exclusivity, phase 2, one lane
;; -----------------------------------------------------------------------------

(deftest serial-plans-run-after-pool-drains-in-one-lane
  (testing "serial scenarios wait for the pool, then run one at a time in
            plan order"
    (let [log (atom [])
          plans [(sleeping-plan log :s1 20 :serial? true)  ; plan-EARLY serial
                 (sleeping-plan log :p1 150)
                 (sleeping-plan log :p2 80)
                 (sleeping-plan log :s2 20 :serial? true)
                 (sleeping-plan log :p3 10)]
          result (exec/execute-suite plans {:max-parallel 3})
          events @log
          index-of (fn [ev] (.indexOf ^java.util.List events ev))]
      (is (= :passed (:status result)))
      (is (= [:s1 :p1 :p2 :s2 :p3] (mapv plan-tag (:scenarios result)))
          "Output order never reveals phasing")
      (testing "phase 2: no serial scenario begins before every pool scenario ends"
        (doseq [pool-tag [:p1 :p2 :p3]
                serial-tag [:s1 :s2]]
          (is (< (index-of [:end pool-tag]) (index-of [:begin serial-tag]))
              (str pool-tag " must end before " serial-tag " begins"))))
      (testing "one lane: serial scenarios are mutually exclusive, plan order"
        (is (< (index-of [:begin :s1])
               (index-of [:end :s1])
               (index-of [:begin :s2])
               (index-of [:end :s2])))))))

(deftest all-serial-and-empty-edges
  (testing "all plans serial at N>1: pure phase 2, plan order"
    (let [log (atom [])
          plans [(sleeping-plan log :x 5 :serial? true)
                 (sleeping-plan log :y 5 :serial? true)]
          result (exec/execute-suite plans {:max-parallel 4})]
      (is (= [:x :y] (mapv plan-tag (:scenarios result))))
      (is (= [[:begin :x] [:end :x] [:begin :y] [:end :y]] @log))))
  (testing "no plans at N>1"
    (let [result (exec/execute-suite [] {:max-parallel 4})]
      (is (= [] (:scenarios result)))
      (is (= :passed (:status result))))))

;; -----------------------------------------------------------------------------
;; Binding conveyance (R6)
;; -----------------------------------------------------------------------------

(def ^:dynamic *conveyed* :unbound)

(deftest pool-tasks-convey-dynamic-bindings
  (testing "workers inherit the coordinator's dynamic bindings (bound-fn*)"
    (let [seen (atom #{})
          plans [(et/make-plan [(et/make-bound-step
                                 (fn [] (swap! seen conj *conveyed*) {:ok 1})
                                 0 [])])
                 (et/make-plan [(et/make-bound-step
                                 (fn [] (swap! seen conj *conveyed*) {:ok 2})
                                 0 [])])]
          result (binding [*conveyed* :warm-daemon-out]
                   (exec/execute-suite plans {:max-parallel 2}))]
      (is (= :passed (:status result)))
      (is (= #{:warm-daemon-out} @seen)
          "A raw (unconveyed) executor thread would see :unbound"))))

;; -----------------------------------------------------------------------------
;; Catastrophic escape: coordinator must never hang
;; -----------------------------------------------------------------------------

(deftest escaped-throwable-becomes-failed-result
  (testing "a Throwable escaping scenario execution converts to a failed
            result instead of swallowing the completion"
    (let [plans [(et/make-plan [(et/make-bound-step (fn [] {:ok 1}) 0 [])])]]
      (with-redefs [step-loop/execute-scenario
                    (fn [& _] (throw (OutOfMemoryError. "simulated harness crash")))]
        (let [result (exec/execute-suite plans {:max-parallel 2})]
          (is (= :failed (:status result)) "Suite completes; no hang")
          (is (= :scenario/harness-crash
                 (-> result :scenarios first :steps first :error :type))
              "First step bears the harness-crash error"))))))

;; -----------------------------------------------------------------------------
;; Costume auto-serial gate, end to end (acceptance 4)
;; -----------------------------------------------------------------------------

(deftest costume-scenarios-never-overlap
  (testing "two costume-provisioning plans ride gate -> facet -> scheduler and
            never overlap at N>1"
    ;; Steps carry :wears in their bound SVO — the plan-visible signal the
    ;; auto-serial gate keys on. Execution runs with NO :interfaces config,
    ;; so provisioning no-ops (ensure-capability-for-svo's nil-interfaces
    ;; branch) and no real costume machinery is touched: this test is about
    ;; scheduling, not attaching.
    (let [log (atom [])
          costume-plan
          (fn [tag]
            (-> (et/make-plan
                 [(et/make-bound-step
                   (fn [_ctx]
                     (swap! log conj [:begin tag])
                     (Thread/sleep 60)
                     (swap! log conj [:end tag])
                     nil)
                   1 [])])
                (update-in [:plan/steps 0 :binding]
                           assoc :svo {:interface :web :subject :alice
                                       :wears "shared-chrome"})))
          plans (schedule/attach-schedules
                 [(costume-plan :c1)
                  (sleeping-plan log :filler 120)
                  (costume-plan :c2)]
                 {:web {:type :web :adapter :etaoin}})
          result (exec/execute-suite plans {:max-parallel 3})
          events @log
          index-of (fn [ev] (.indexOf ^java.util.List events ev))]
      (is (= :passed (:status result)))
      (is (= [{:serial? true :reason :costume} nil {:serial? true :reason :costume}]
             (mapv :plan/schedule plans))
          "the auto gate produced the facet, filler untouched")
      (is (< (index-of [:begin :c1])
             (index-of [:end :c1])
             (index-of [:begin :c2])
             (index-of [:end :c2]))
          "costume scenarios are mutually exclusive, in plan order")
      (is (< (index-of [:end :filler]) (index-of [:begin :c1]))
          "and they wait for the pool to drain (phase 2)"))))

;; -----------------------------------------------------------------------------
;; Ordered release buffer (R1)
;; -----------------------------------------------------------------------------

(defn- env-for [plan]
  {:status :passed
   :scenario/id (get-in plan [:plan/pickle :pickle/id])})

(deftest ordered-release-buffer
  (let [plans (vec (repeatedly 5 #(et/make-plan
                                   [(et/make-bound-step (fn [] {}) 0 [])])))
        envs (mapv env-for plans)]
    (testing "in-order arrivals pass straight through"
      (let [released (atom [])
            release! (runner-core/make-ordered-release plans #(swap! released conj %))]
        (doseq [env envs] (release! env))
        (is (= envs @released))))
    (testing "out-of-order arrivals buffer until the hole fills"
      (let [released (atom [])
            release! (runner-core/make-ordered-release plans #(swap! released conj %))]
        (release! (envs 2))
        (release! (envs 1))
        (is (= [] @released) "Nothing releases while plan-0 is outstanding")
        (release! (envs 0))
        (is (= [(envs 0) (envs 1) (envs 2)] @released)
            "Contiguous prefix flushes in plan order")
        (release! (envs 4))
        (is (= 3 (count @released)) "Plan-3 hole holds plan-4 back")
        (release! (envs 3))
        (is (= envs @released))))
    (testing "deep hold: a plan-early scenario completing last (phase 2)"
      (let [released (atom [])
            release! (runner-core/make-ordered-release plans #(swap! released conj %))]
        (doseq [env (rest envs)] (release! env))
        (is (= [] @released) "Everything buffers behind plan-0")
        (release! (envs 0))
        (is (= envs @released) "One arrival flushes the whole buffer")))
    (testing "an envelope with no known :scenario/id releases immediately"
      (let [released (atom [])
            release! (runner-core/make-ordered-release plans #(swap! released conj %))
            stray {:status :passed}]
        (release! stray)
        (is (= [stray] @released))))))
