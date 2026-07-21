(ns shiftlefter.svo.bindings-lint-test
  "Static producer/consumer check for the scenario data plane (sl-yh7)."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.svo.bindings-lint :as lint]))

(defn- mk-step [text captures kinds]
  {:status :matched
   :step {:step/id (java.util.UUID/randomUUID)
          :step/text text
          :step/location {:line 7 :column 3}}
   :binding {:captures captures :slot-kinds kinds}})

(defn- mk-plan
  ([steps] (mk-plan steps nil))
  ([steps hooks]
   (cond-> {:plan/pickle {:pickle/source-file "features/f.feature"}
            :plan/steps (vec steps)
            :plan/runnable? true}
     hooks (assoc :plan/hooks hooks))))

(deftest clean-produce-then-consume
  (is (empty? (lint/check-plans
               [(mk-plan [(mk-step "receive" ["'p'" "(?<code>\\d+)"] [:value :matcher])
                          (mk-step "fill" ["Login.code" "{code}"] [nil :value])])]))))

(deftest consumed-without-producer-errors-with-did-you-mean
  (let [issues (lint/check-plans
                [(mk-plan [(mk-step "receive" ["'p'" "(?<code>\\d+)"] [:value :matcher])
                           (mk-step "fill" ["Login.code" "{coed}"] [nil :value])])])
        err (first (filter #(= :bindings/consumed-without-producer (:type %)) issues))]
    (is (some? err))
    (is (= :error (:severity err)))
    (is (= :code (:suggestion err)))
    (is (= [:code] (:known err)))
    (is (= "features/f.feature" (-> err :location :uri)))
    (is (lint/blocking? issues))))

(deftest consumption-is-forward-only
  (testing "a later producer does not satisfy an earlier consumer"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step "fill" ["Login.code" "{code}"] [nil :value])
                             (mk-step "receive" ["'p'" "(?<code>\\d+)"] [:value :matcher])])])]
      (is (some #(= :bindings/consumed-without-producer (:type %)) issues)))))

(deftest hook-provides-count-as-producers
  (is (empty? (lint/check-plans
               [(mk-plan [(mk-step "fill" ["Login.code" "{sessionToken}"] [nil :value])]
                         [{:name "seed" :provides [:sessionToken]}])]))))

(deftest hook-provides-are-exempt-from-never-consumed
  (is (empty? (lint/check-plans
               [(mk-plan [(mk-step "noop" [] [])]
                         [{:name "seed" :provides [:sessionToken]}])]))))

(deftest embedded-matcher-tokens-are-consumers
  (let [issues (lint/check-plans
                [(mk-plan [(mk-step "receive" ["'p'" "(?<n>\\d+) {order}"] [:value :matcher])])])]
    (is (= [:order] (map :name (filter #(= :bindings/consumed-without-producer (:type %))
                                       issues))))))

(deftest location-slot-tokens-are-consumers
  (let [issues (lint/check-plans
                [(mk-plan [(mk-step "navigate" ["{resetLink}"] [:location])])])]
    (is (some #(= :bindings/consumed-without-producer (:type %)) issues))))

(deftest quoted-location-capture-is-not-a-consumer
  (testing "a '{x}' literal (quotes intact, sl-ka80) never registers as consumption"
    (is (empty? (lint/check-plans
                 [(mk-plan [(mk-step "be-on-exactly" ["'{x}'"] [:location])])])))))

(deftest duplicate-group-names-are-a-planning-error
  (let [issues (lint/check-plans
                [(mk-plan [(mk-step "r" ["'p'" "(?<a>x)|(?<a>y)"] [:value :matcher])])])]
    (is (= [:bindings/invalid-pattern] (map :type issues)))
    (is (lint/blocking? issues))))

(deftest unnamed-only-groups-notice
  (let [issues (lint/check-plans
                [(mk-plan [(mk-step "r" ["'p'" "code: (\\d+)"] [:value :matcher])])])]
    (is (= [[:bindings/unnamed-only-groups :warn]]
           (map (juxt :type :severity) issues)))
    (is (not (lint/blocking? issues)))))

(deftest produced-never-consumed-notice
  (let [issues (lint/check-plans
                [(mk-plan [(mk-step "r" ["'p'" "(?<code>\\d+)"] [:value :matcher])])])]
    (is (= [[:bindings/produced-never-consumed :code :warn]]
           (map (juxt :type :name :severity) issues)))))

(deftest steps-without-slot-kinds-are-invisible
  (testing "undeclared stepdefs don't trip the check (declaring the frame
            is what buys the static guarantees)"
    (is (empty? (lint/check-plans
                 [(mk-plan [(mk-step "custom" ["{code}"] nil)])])))))

(deftest scenarios-are-independent
  (testing "bindings are scenario-scoped — a producer in plan A does not
            satisfy plan B"
    (let [producer (mk-plan [(mk-step "r" ["'p'" "(?<code>\\d+)"] [:value :matcher])
                             (mk-step "f" ["L" "{code}"] [nil :value])])
          orphan (mk-plan [(mk-step "f" ["L" "{code}"] [nil :value])])]
      (is (some #(= :bindings/consumed-without-producer (:type %))
                (lint/check-plans [producer orphan]))))))
