(ns shiftlefter.stepengine.exec-bindings-test
  "Engine integration of the scenario data plane (sl-yh7): capture
   normalization at invoke, provenance in run evidence, hook mirroring."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.stepengine.bindings :as b]
            [shiftlefter.stepengine.exec :as exec]
            [shiftlefter.stepengine.exec.hooks :as hooks]))

;; -- helpers (same shapes as exec-test) ---------------------------------------

(defn- make-step []
  {:step/id (java.util.UUID/randomUUID)
   :step/text "test step"
   :step/arguments []})

(defn- make-bound-step
  ([f arity captures] (make-bound-step f arity captures nil))
  ([f arity captures slot-kinds]
   {:status :matched
    :step (make-step)
    :binding (cond-> {:fn f
                      :arity arity
                      :captures captures
                      :stepdef/id "test-step"
                      :pattern-src "test pattern"}
               slot-kinds (assoc :slot-kinds slot-kinds))}))

(defn- make-plan [bound-steps]
  {:plan/id (java.util.UUID/randomUUID)
   :plan/pickle {:pickle/id (java.util.UUID/randomUUID)
                 :pickle/name "test pickle"}
   :plan/steps (vec bound-steps)
   :plan/runnable? true})

;; -- invoke-step normalization ------------------------------------------------

(deftest value-slot-resolves-whole-token
  (let [f (fn [ctx _loc value] (assoc ctx :filled value))
        binding {:fn f :arity 3 :slot-kinds [nil :value]}
        ctx {:step (make-step) :scenario {b/bindings-key {:code "987654"}}}
        result (exec/invoke-step binding ["Login.code" "{code}"] ctx)]
    (is (= :passed (:status result)))
    (is (= "987654" (:filled (:scenario result))))))

(deftest value-slot-strips-quotes-from-literals
  (let [f (fn [ctx _loc value] (assoc ctx :filled value))
        binding {:fn f :arity 3 :slot-kinds [nil :value]}
        ctx {:step (make-step) :scenario {}}]
    (is (= "plain text"
           (:filled (:scenario (exec/invoke-step
                                binding ["Login.code" "'plain text'"] ctx)))))
    (testing "quoted is ALWAYS literal — a quoted token-lookalike stays text"
      (is (= "{code}"
             (:filled (:scenario (exec/invoke-step
                                  binding ["Login.code" "'{code}'"]
                                  {:step (make-step)
                                   :scenario {b/bindings-key {:code "111"}}}))))))))

(deftest location-slot-token-is-quote-wrapped
  (let [f (fn [ctx url] (assoc ctx :nav url))
        binding {:fn f :arity 2 :slot-kinds [:location]}
        ctx {:step (make-step)
             :scenario {b/bindings-key {:resetLink "https://x.io/reset?t=1"}}}
        result (exec/invoke-step binding ["{resetLink}"] ctx)]
    (is (= "'https://x.io/reset?t=1'" (:nav (:scenario result)))
        "wrapped so the stepdef's literal/ref classifier takes the literal path")))

(deftest unresolved-token-fails-the-step-structurally
  (let [f (fn [ctx _loc value] (assoc ctx :filled value))
        binding {:fn f :arity 3 :slot-kinds [nil :value]}
        ctx {:step (make-step) :scenario {b/bindings-key {:code "1"}}}
        result (exec/invoke-step binding ["Login.code" "{coed}"] ctx)]
    (is (= :failed (:status result)))
    (is (= :bindings/unresolved (-> result :error :type)))
    (is (= ["code"] (-> result :error :known)))))

(deftest plain-slots-untouched
  (let [f (fn [ctx locator] (assoc ctx :got locator))
        binding {:fn f :arity 2 :slot-kinds [nil]}
        ctx {:step (make-step) :scenario {}}]
    (is (= "{:id \"code\"}"
           (:got (:scenario (exec/invoke-step binding ["{:id \"code\"}" ] ctx))))
        "raw EDN locators pass through — token check never EDN-reads")))

;; -- execute-scenario: produce → consume + provenance -------------------------

(deftest scenario-produce-then-consume-with-provenance
  (let [producer (fn [ctx] (b/capture! ctx "code: (?<code>\\d+)" "code: 4242"))
        consumer (fn [ctx value] (assoc ctx :typed value))
        plan (make-plan
              [(make-bound-step producer 1 [])
               (make-bound-step consumer 2 ["{code}"] [:value])])
        result (exec/execute-scenario plan {})]
    (is (= :passed (:status result)))
    (is (= "4242" (:typed (:scenario-ctx result))))
    (testing "provenance rides the step record, not the map"
      (is (= [:code] (-> result :steps (nth 0) :bindings/produced)))
      (is (nil? (-> result :steps (nth 1) :bindings/produced))))))

(deftest scenario-rebinding-last-write-wins
  (let [p1 (fn [ctx] (b/capture! ctx "(?<code>\\d+)" "111"))
        p2 (fn [ctx] (b/capture! ctx "(?<code>\\d+)" "222"))
        consumer (fn [ctx value] (assoc ctx :typed value))
        plan (make-plan
              [(make-bound-step p1 1 [])
               (make-bound-step p2 1 [])
               (make-bound-step consumer 2 ["{code}"] [:value])])
        result (exec/execute-scenario plan {})]
    (is (= "222" (:typed (:scenario-ctx result))))
    (is (= [:code] (-> result :steps (nth 1) :bindings/produced))
        "the rebinding step records production too")))

;; -- hook mirroring -----------------------------------------------------------

(deftest before-hook-mirrors-conforming-keys
  (let [entries [{:name "seed"
                  :before (fn [_payload]
                            {:sessionToken "tok-1"
                             :seed {:userId 42}
                             :seed/raw 99
                             :not-camel 7})}]
        {:keys [ctx records error]} (hooks/run-befores! entries {:name "s"} {})]
    (is (nil? error))
    (testing "root merge unchanged — open ctx (sl-esq)"
      (is (= "tok-1" (:sessionToken ctx)))
      (is (= 99 (:seed/raw ctx)))
      (is (= 7 (:not-camel ctx))))
    (testing "only conforming bare lowerCamel keys mirror into the data plane"
      (is (= {:sessionToken "tok-1" :seed {:userId 42}}
             (b/bindings-key ctx))))
    (testing ":contributed record unchanged (all returned keys)"
      (is (= #{:sessionToken :seed :seed/raw :not-camel}
             (set (:contributed (first records))))))))

(deftest mirrored-map-contribution-supports-dotted-descent
  (let [entries [{:name "seed" :before (fn [_] {:seed {:userId 42}})}]
        {:keys [ctx]} (hooks/run-befores! entries {:name "s"} {})]
    (is (= 42 (b/resolve-token ctx "{seed.userId}")))))
