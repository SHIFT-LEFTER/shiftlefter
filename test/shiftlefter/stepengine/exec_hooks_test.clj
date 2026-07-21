(ns shiftlefter.stepengine.exec-hooks-test
  "The scenario lifecycle hook weave (sl-esq, ACs 4-7): ordering + LIFO
   unwind, payloads and ctx contribution, failure semantics, execution
   sequence against provisioning/cleanup, and the envelope :hooks stamp.

   Fake hooks over atoms; no browser anywhere. Plans get :plan/hooks
   hand-assoc'd (planning-half resolution is covered in
   runner/hooks_test.clj and runner/core_hooks_test.clj)."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.reporter :as reporter]
            [shiftlefter.stepengine.exec :as exec]
            [shiftlefter.test-helpers.adapter-registry :as mock]))

;; -----------------------------------------------------------------------------
;; Helpers (same shapes as exec_test.clj)
;; -----------------------------------------------------------------------------

(defn- make-binding
  ([f arity] (make-binding f arity []))
  ([f arity captures]
   {:fn f :arity arity :captures captures
    :stepdef/id "test-step" :pattern-src "test pattern"}))

(defn- make-bound-step
  ([f] (make-bound-step f 0 []))
  ([f arity captures]
   {:status :matched
    :step {:step/id (java.util.UUID/randomUUID)
           :step/text "test step"
           :step/arguments []}
    :binding (make-binding f arity captures)}))

(defn- make-svo-bound-step [f]
  {:status :matched
   :step {:step/id (java.util.UUID/randomUUID)
          :step/text "svo step"
          :step/arguments []
          :step/location {:uri "test.feature" :line 10}}
   :binding (merge (make-binding f 1 [])
                   {:svo {:subject :alice :verb :click :interface :web}})})

(defn- make-plan
  [bound-steps & {:keys [hooks runnable?] :or {runnable? true}}]
  (cond-> {:plan/id (java.util.UUID/randomUUID)
           :plan/pickle {:pickle/id (java.util.UUID/randomUUID)
                         :pickle/name "hooked pickle"
                         :pickle/source-file "features/h.feature"
                         :pickle/tags [{:name "@hook=x" :location {:line 1 :column 1}}]}
           :plan/steps (vec bound-steps)
           :plan/runnable? runnable?}
    hooks (assoc :plan/hooks hooks)))

(defn- hook
  "A registry-resolved hook entry logging [phase name] tuples to `events`."
  [events name & {:keys [before after before-fn after-fn]}]
  (cond-> {:name name
           :registration {:path "/proj/hooks.clj"}
           :tag-source {:file "features/h.feature" :line 1}}
    (or before before-fn)
    (assoc :before (or before-fn
                       (fn [_payload] (swap! events conj [:before name]) nil)))
    (or after after-fn)
    (assoc :after (or after-fn
                      (fn [_payload] (swap! events conj [:after name]) nil)))))

(defn- run-suite [plans & [opts]]
  (exec/execute-suite plans (or opts {})))

(defn- first-scenario [suite-result] (-> suite-result :scenarios first))

;; -----------------------------------------------------------------------------
;; AC3/AC4 — ordering, LIFO unwind, after-only frames
;; -----------------------------------------------------------------------------

(deftest befores-in-order-afters-strict-lifo
  (let [events (atom [])
        step (make-bound-step (fn [] (swap! events conj [:step]) nil))
        plan (make-plan [step]
                        :hooks [(hook events "a" :before true :after true)
                                (hook events "b" :before true :after true)
                                (hook events "c" :before true :after true)])
        result (first-scenario (run-suite [plan]))]
    (is (= :passed (:status result)))
    (is (= [[:before "a"] [:before "b"] [:before "c"]
            [:step]
            [:after "c"] [:after "b"] [:after "a"]]
           @events)
        "Befores outermost-first; Afters strict LIFO")
    (is (= [["a" :before] ["b" :before] ["c" :before]
            ["c" :after] ["b" :after] ["a" :after]]
           (mapv (juxt :name :phase) (:hooks result)))
        "every hook that ran is recorded, in run order")
    (is (every? #(and (= :ok (:status %)) (number? (:duration-ms %)))
                (:hooks result))
        "hooks are lifecycle: timed and stamped")))

(deftest after-only-entry-always-unwinds
  (let [events (atom [])
        plan (make-plan [(make-bound-step (fn [] (swap! events conj [:step]) nil))]
                        :hooks [(hook events "a" :before true :after true)
                                (hook events "b" :after true)])
        result (first-scenario (run-suite [plan]))]
    (is (= :passed (:status result)))
    (is (= [[:before "a"] [:step] [:after "b"] [:after "a"]] @events)
        "an entry with no :before still gets an unwind frame")
    (is (= [["a" :before] ["b" :after] ["a" :after]]
           (mapv (juxt :name :phase) (:hooks result)))
        "after-only entries record only their :after")))

;; -----------------------------------------------------------------------------
;; AC6 — payloads and ctx contribution
;; -----------------------------------------------------------------------------

(deftest before-contribution-merges-into-ctx
  (let [seen (atom nil)
        plan (make-plan
              [(make-bound-step (fn [ctx] (assoc ctx :saw (:seed/user-id ctx))) 1 [])]
              :hooks [{:name "seed"
                       :before (fn [{:keys [ctx scenario]}]
                                 (is (= {} ctx) "Befores run against the fresh ctx")
                                 (is (= "hooked pickle" (:name scenario)))
                                 {:seed/user-id 42})}
                      {:name "quiet" :before (fn [_] (reset! seen :ran) nil)}])
        result (first-scenario (run-suite [plan]))]
    (is (= :passed (:status result)))
    (is (= 42 (:saw (:scenario-ctx result)))
        "a step reads the Before's contributed key")
    (is (= :ran @seen))
    (let [[seed quiet] (:hooks result)]
      (is (= [:seed/user-id] (:contributed seed))
          ":contributed records exactly the returned map's keys")
      (is (not (contains? quiet :contributed))
          "nil return = no contribution, no :contributed key"))))

(deftest after-payload-carries-inflight-result
  (let [captured (atom nil)
        plan (make-plan
              [(make-bound-step (fn [] (throw (ex-info "step boom" {}))))]
              :hooks [{:name "watcher"
                       :before (fn [_] {:seed 1})
                       :after (fn [{:keys [result] :as payload}]
                                (reset! captured payload)
                                ;; on-failure conditionality is a plain when
                                (when (= :failed (:status result))
                                  (swap! captured assoc ::on-failure-fired true))
                                nil)}])
        result (first-scenario (run-suite [plan]))]
    (is (= :failed (:status result)) "step failure stays :failed — no hook threw")
    (is (= 1 (:seed (:ctx @captured))) ":after sees the final threaded ctx")
    (is (= :failed (-> @captured :result :status)))
    (is (some? (-> @captured :result :steps first :error))
        "in-flight result carries step results with errors")
    (is (true? (::on-failure-fired @captured)))
    (is (= "hooked pickle" (-> @captured :scenario :name)))))

(deftest after-return-is-ignored
  (let [plan (make-plan [(make-bound-step (fn [] nil))]
                        :hooks [{:name "chatty"
                                 :after (fn [_] {:ctx-smuggling :attempt})}])
        result (first-scenario (run-suite [plan]))]
    (is (= :passed (:status result)))
    (is (not (contains? (:scenario-ctx result) :ctx-smuggling))
        ":after return is IGNORED (reserved for the attachments era)")))

(deftest invalid-before-return-is-hook-error
  (let [plan (make-plan [(make-bound-step (fn [] nil))]
                        :hooks [{:name "bad" :before (fn [_] :not-a-map)}])
        result (first-scenario (run-suite [plan]))]
    (is (= :error (:status result)))
    (is (= :hook/invalid-return (-> result :error :type)))))

;; -----------------------------------------------------------------------------
;; AC4/AC5 — failure semantics + execution sequence
;; -----------------------------------------------------------------------------

(deftest before-throw-errors-scenario-skips-steps-no-provisioning
  (let [events (atom [])
        factory-calls (atom 0)
        registry (mock/registry {:mock-web {:factory (fn [_]
                                                       (swap! factory-calls inc)
                                                       {:ok {:type :fake}})}})
        interfaces (mock/interfaces {:web {:adapter :mock-web}})
        step-ran (atom false)
        plan (make-plan
              [(make-svo-bound-step (fn [ctx] (reset! step-ran true) ctx))]
              :hooks [(hook events "a" :before true :after true)
                      (hook events "boom"
                            :before-fn (fn [_] (throw (ex-info "seed failed" {:db :down}))))
                      (hook events "never" :before true :after true)])
        result (first-scenario (run-suite [plan] {:interfaces interfaces
                                                  :adapter-registry registry
                                                  :provisioning :eager}))]
    (is (= :error (:status result)) "infrastructure failure — never :failed")
    (is (zero? @factory-calls) "NO provisioning cost paid — fail-fast")
    (is (false? @step-ran))
    (is (every? #(= :skipped (:status %)) (:steps result))
        "all steps reported skipped")
    (is (= [[:before "a"] [:after "a"]] @events)
        "later hooks never ran; only succeeded frames unwound (LIFO)")
    (let [err (:error result)]
      (is (= :hook/before-failed (:type err)))
      (is (= "boom" (:hook err)))
      (is (= "/proj/hooks.clj" (-> err :registration :path)))
      (is (= {:file "features/h.feature" :line 1} (:tag-source err))
          "dual attribution: registration home + tag file:line"))
    (is (= :failed (-> (filter #(= "boom" (:name %)) (:hooks result))
                       first :status))
        "the failing hook's own record is stamped :failed")))

(deftest provisioning-failure-unwinds-all-afters-without-capabilities
  (let [after-saw (atom nil)
        registry (mock/registry {:mock-web {:fail? true}})
        interfaces (mock/interfaces {:web {:adapter :mock-web}})
        plan (make-plan
              [(make-svo-bound-step (fn [ctx] ctx))]
              :hooks [{:name "seed" :before (fn [_] {:seed 1})}
                      {:name "screenshot"
                       :after (fn [{:keys [ctx]}]
                                ;; tolerate-and-run: check, don't assume
                                (reset! after-saw
                                        {:seed (:seed ctx)
                                         :caps (filterv #(= "cap" (namespace %)) (keys ctx))})
                                nil)}])
        result (first-scenario (run-suite [plan] {:interfaces interfaces
                                                  :adapter-registry registry
                                                  :provisioning :eager}))]
    (is (= :failed (:status result))
        "provisioning failure stays :failed — no hook threw")
    (is (= {:seed 1 :caps []} @after-saw)
        "ALL Afters unwound, seeing Before contributions but NO capabilities")
    (is (every? #(= :ok (:status %))
                (filter #(= :after (:phase %)) (:hooks result))))))

(deftest partial-provisioning-failure-afters-see-live-capabilities
  ;; sl-uu7x: when provisioning fails PARTWAY, the error carries the
  ;; partially-provisioned ctx. Afters (which run before capability
  ;; cleanup) now see the live capabilities — e.g. screenshot-on-failure
  ;; can reach Alice's browser when Bob's provision failed.
  (let [after-saw (atom nil)
        calls (atom 0)
        registry (mock/registry
                  {:mock-web {:factory (fn [_]
                                         (if (= 1 (swap! calls inc))
                                           {:ok {:type :test-impl}}
                                           {:error {:type :adapter/test-failure
                                                    :message "bob fails"}}))}})
        interfaces (mock/interfaces {:web {:adapter :mock-web}})
        svo-step (fn [subj]
                   (-> (make-svo-bound-step (fn [ctx] ctx))
                       (assoc-in [:binding :svo :subject] subj)))
        plan (make-plan
              [(svo-step :alice) (svo-step :bob)]
              :hooks [{:name "seed" :before (fn [_] {:seed 1})}
                      {:name "screenshot"
                       :after (fn [{:keys [ctx]}]
                                (reset! after-saw
                                        {:seed (:seed ctx)
                                         :caps (filterv #(= "cap" (namespace %)) (keys ctx))})
                                nil)}])
        result (first-scenario (run-suite [plan] {:interfaces interfaces
                                                  :adapter-registry registry
                                                  :provisioning :eager}))]
    (is (= :failed (:status result)))
    (is (= {:seed 1 :caps [:cap/web.alice]} @after-saw)
        "Afters see Before contributions AND the partially-provisioned capability")
    (is (= [{:action :closed :interface :web.alice :adapter :mock-web}]
           (-> result :capability-cleanup :cleaned))
        "…and cleanup still closes it afterwards")))

(deftest after-throw-errors-scenario-even-when-steps-passed
  (let [events (atom [])
        plan (make-plan
              [(make-bound-step (fn [] nil))]
              :hooks [(hook events "a" :before true :after true)
                      (hook events "broken-cleanup"
                            :after-fn (fn [_] (throw (ex-info "cleanup boom" {}))))])
        result (first-scenario (run-suite [plan]))]
    (is (= :error (:status result))
        "a suite with broken cleanup is lying about its health")
    (is (= :hook/after-failed (-> result :error :type)))
    (is (= "broken-cleanup" (-> result :error :hook)))
    (is (= [[:before "a"] [:after "a"]] @events)
        "one :after failing does NOT stop the unwind — a's after still ran")))

(deftest multiple-after-failures-all-collected-first-attributed
  (let [plan (make-plan
              [(make-bound-step (fn [] nil))]
              :hooks [{:name "outer" :after (fn [_] (throw (ex-info "outer boom" {})))}
                      {:name "inner" :after (fn [_] (throw (ex-info "inner boom" {})))}])
        result (first-scenario (run-suite [plan]))]
    (is (= :error (:status result)))
    (is (= ["inner" "outer"]
           (mapv :name (filter #(= :failed (:status %)) (:hooks result))))
        "unwind completed finally-style; both failures collected in records")
    (is (= "inner" (-> result :error :hook))
        "the FIRST failure in unwind (LIFO) order carries attribution")))

(deftest after-throw-plus-step-failure-keeps-step-record
  (let [plan (make-plan
              [(make-bound-step (fn [] (throw (ex-info "step boom" {}))))]
              :hooks [{:name "shaky" :after (fn [_] (throw (ex-info "after boom" {})))}])
        result (first-scenario (run-suite [plan]))]
    (is (= :error (:status result)) ":error overrides :failed")
    (is (= :failed (-> result :steps first :status))
        "the failed step record survives intact")
    (is (some? (-> result :steps first :error)))))

(deftest afters-run-before-capability-cleanup
  (let [events (atom [])
        registry (mock/registry {:mock-web {:impl {:kind :fake} :events events}})
        interfaces (mock/interfaces {:web {:adapter :mock-web}})
        plan (make-plan
              [(make-svo-bound-step (fn [ctx] ctx))]
              :hooks [{:name "console-scrape"
                       :after (fn [_] (swap! events conj [:after :console-scrape]) nil)}])
        result (first-scenario (run-suite [plan] {:interfaces interfaces
                                                  :adapter-registry registry
                                                  :provisioning :eager}))]
    (is (= :passed (:status result)))
    (let [order (mapv first @events)]
      (is (< (.indexOf ^java.util.List order :after)
             (.indexOf ^java.util.List order :cleanup))
          "the :after ran BEFORE capability cleanup — live caps reachable"))))

(deftest non-runnable-plans-pay-no-hook-code
  (let [events (atom [])
        plan (make-plan [(make-bound-step (fn [] nil))]
                        :hooks [(hook events "g" :before true :after true)]
                        :runnable? false)
        result (first-scenario (run-suite [plan]))]
    (is (= :skipped (:status result)))
    (is (= [] @events) "binding-failed scenarios run no user lifecycle code")
    (is (nil? (:hooks result)))))

;; -----------------------------------------------------------------------------
;; AC7 — envelope stamp + suite counts
;; -----------------------------------------------------------------------------

(deftest envelope-carries-hook-records-edn-safe
  (let [plan (make-plan [(make-bound-step (fn [] nil))]
                        :hooks [{:name "seed" :before (fn [_] {:seed/id 7})}
                                {:name "shot" :after (fn [_] nil)}])
        raw (first-scenario (run-suite [plan]))
        envelope (reporter/scenario-envelope raw)]
    (is (reporter/edn-safe? envelope))
    (is (= [{:name "seed" :phase :before :status :ok :contributed [:seed/id]}
            {:name "shot" :phase :after :status :ok}]
           (mapv #(dissoc % :duration-ms) (:hooks envelope))))
    (is (every? #(number? (:duration-ms %)) (:hooks envelope))))
  (testing "hook-less scenario: no :hooks key on the envelope"
    (let [raw (first-scenario (run-suite [(make-plan [(make-bound-step (fn [] nil))])]))]
      (is (not (contains? (reporter/scenario-envelope raw) :hooks))))))

(deftest envelope-carries-hook-failure-error
  (let [plan (make-plan [(make-bound-step (fn [] nil))]
                        :hooks [{:name "boom"
                                 :registration {:path "/proj/hooks.clj"}
                                 :before (fn [_] (throw (ex-info "x" {:why :because})))}])
        envelope (reporter/scenario-envelope (first-scenario (run-suite [plan])))]
    (is (reporter/edn-safe? envelope))
    (is (= :error (:status envelope)))
    (is (= "boom" (-> envelope :error :hook)))
    (is (= {:why :because} (-> envelope :error :data)))
    (is (= :failed (-> envelope :hooks first :status)))
    (is (= "x" (-> envelope :hooks first :error :message)))))

(deftest suite-counts-error-scenarios-conditionally
  (let [boom-plan (make-plan [(make-bound-step (fn [] nil))]
                             :hooks [{:name "b" :before (fn [_] (throw (ex-info "x" {})))}])
        ok-plan (make-plan [(make-bound-step (fn [] nil))])]
    (testing "an :error scenario shows up in counts; suite is :failed"
      (let [result (run-suite [ok-plan boom-plan])]
        (is (= {:passed 1 :failed 0 :pending 0 :skipped 0 :error 1} (:counts result)))
        (is (= :failed (:status result)))))
    (testing "without :error scenarios the historical four-key map is untouched"
      (is (= {:passed 1 :failed 0 :pending 0 :skipped 0}
             (:counts (run-suite [ok-plan])))))))
