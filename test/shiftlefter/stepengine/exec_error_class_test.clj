(ns shiftlefter.stepengine.exec-error-class-test
  "Engine-level pins for the sl-kowi error-class contract: invoke-step
   lifts a recognized `:error/class` onto the step error map, and
   finalize flips ONLY the :observation class to a scenario :error — the
   step stays :failed, the step's error map is the scenario attribution.
   The runner-level twins live in runner/error_class_test.clj."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.stepengine.exec :as exec]))

(defn- bound-step [f]
  {:status :matched
   :step {:step/id (java.util.UUID/randomUUID)
          :step/text "a step"
          :step/arguments []}
   :binding {:fn f :arity 0 :captures []
             :stepdef/id "kowi-step" :pattern-src "a step"}})

(defn- plan [& fs]
  {:plan/id (java.util.UUID/randomUUID)
   :plan/pickle {:pickle/id (java.util.UUID/randomUUID)
                 :pickle/name "kowi pickle"}
   :plan/steps (mapv bound-step fs)
   :plan/runnable? true})

(defn- thrower [data]
  (fn [] (throw (ex-info "boom" data))))

(deftest invoke-step-lifts-a-recognized-class
  (testing "each contract value rides the step error map; the data is kept"
    (doseq [c [:assertion :sut :observation]]
      (let [result (exec/invoke-step (:binding (bound-step (thrower {:error/class c :extra 1})))
                                     [] {:step {} :scenario {}})]
        (is (= :failed (:status result)))
        (is (= c (-> result :error :error/class)) (str c " is lifted"))
        (is (= {:error/class c :extra 1} (-> result :error :data))))))
  (testing "absent or unrecognized → no key on the error map (today's behavior)"
    (doseq [data [{} {:error/class :bogus} {:error/class "observation"}]]
      (let [result (exec/invoke-step (:binding (bound-step (thrower data)))
                                     [] {:step {} :scenario {}})]
        (is (= :failed (:status result)))
        (is (not (contains? (:error result) :error/class)) (pr-str data))))))

(deftest finalize-flips-only-the-observation-class
  (testing ":observation → scenario :error, step :failed, attribution = the step's error"
    (let [result (exec/execute-scenario (plan (thrower {:error/class :observation})) {})
          step (-> result :steps first)]
      (is (= :error (:status result)))
      (is (= :failed (:status step)))
      (is (= :observation (-> step :error :error/class)))
      (is (= (:error step) (:error result)))))
  (testing ":sut and :assertion → scenario :failed, no scenario-level :error"
    (doseq [c [:sut :assertion]]
      (let [result (exec/execute-scenario (plan (thrower {:error/class c})) {})]
        (is (= :failed (:status result)) (str c))
        (is (not (contains? result :error)))
        (is (= c (-> result :steps first :error :error/class))))))
  (testing "fail-fast still holds: steps after the dark one are :skipped"
    (let [result (exec/execute-scenario
                  (plan (fn [] nil) (thrower {:error/class :observation}) (fn [] nil)) {})]
      (is (= :error (:status result)))
      (is (= [:passed :failed :skipped] (mapv :status (:steps result)))))))
