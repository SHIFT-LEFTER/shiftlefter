(ns shiftlefter.sieve.web-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.sieve.contract :as contract]
            [shiftlefter.sieve.provider :as provider]
            [shiftlefter.sieve.store :as store]
            [shiftlefter.sieve.web :as web]))

(def fixture-path "test/fixtures/sieve/web-login-snapshot.edn")

(def projection
  {:projection/id "proj-web-test"
   :projection/version 1
   :fingerprint "accepted-projection-fingerprint"
   :source :working-tree
   :status :ok})

(defn fixture-snapshot []
  (contract/with-evidence-identity
    (store/load-fixture fixture-path)))

(deftest fixture-is-a-web-evidence-snapshot
  (let [snapshot (fixture-snapshot)]
    (is (s/valid? ::contract/evidence-snapshot snapshot))
    (is (= :shiftlefter.sieve.web/evidence.v1 (:payload-schema snapshot)))
    (is (true? (get-in snapshot [:capture :best-effort?])))
    (is (false? (get-in snapshot [:capture :deterministic?])))
    (is (string? (get-in snapshot [:payload :html])))
    (is (seq (get-in snapshot [:payload :inventory :elements])))
    (testing "web fields stay in web payloads"
      (is (nil? (:html snapshot)))
      (is (nil? (:rect snapshot)))
      (is (nil? (:locators snapshot))))))

(deftest saved-web-evidence-analyzes-without-live-browser
  (let [snapshot (fixture-snapshot)
        a1 (web/analyze-web-evidence snapshot {:projection projection
                                               :provider-config {:mode :fixture}})
        a2 (web/analyze-web-evidence snapshot {:projection projection
                                               :provider-config {:mode :fixture}})]
    (is (s/valid? ::contract/analysis-result a1))
    (is (= a1 a2))
    (is (= "accepted-projection-fingerprint"
           (get-in a1 [:projection :fingerprint])))
    (is (= 4 (count (:candidates a1))))
    (is (= #{:chrome :typable :clickable}
           (set (map :category (:candidates a1)))))
    (is (every? #(= :shiftlefter.sieve.web/candidate.v1
                    (:payload-schema %))
                (:candidates a1)))
    (is (some #(= "email" (get-in % [:payload :locators :id]))
              (:candidates a1)))
    (is (every? #(nil? (:rect %)) (:candidates a1)))
    (is (every? #(nil? (:locators %)) (:candidates a1)))))

(deftest one-snapshot-supports-provider-upgrade-comparison
  (let [snapshot (fixture-snapshot)
        before (web/analyze-web-evidence snapshot {:projection projection
                                                  :provider-version "1"
                                                  :provider-config {:mode :fixture}})
        after (web/analyze-web-evidence snapshot {:projection projection
                                                 :provider-version "2"
                                                 :provider-config {:mode :fixture
                                                                   :new-pass true}})
        changed-after (update after :candidates conj
                              {:candidate/id "cand-added"
                               :kind :semantic-object-candidate
                               :label "Forgot password"
                               :payload-schema :shiftlefter.sieve.web/candidate.v1
                               :payload {}})
        changed-after (contract/with-analysis-identity changed-after)
        diff (provider/compare-analysis-results before changed-after)]
    (is (= (:evidence before) (:evidence after)))
    (is (not= (:analysis/id before) (:analysis/id after)))
    (is (= ["Forgot password"]
           (mapv :label (get-in diff [:candidate-diff :added]))))))
