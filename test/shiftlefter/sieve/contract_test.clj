(ns shiftlefter.sieve.contract-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.sieve.contract :as contract]))

(def sample-projection
  {:projection/id "proj-test"
   :projection/version 1
   :fingerprint "projection-fp"
   :source :working-tree
   :status :ok})

(defn sample-snapshot []
  (contract/make-evidence-snapshot
    {:interface {:name :web :type :web}
     :source {:kind :fixture :url "https://example.test"}
     :capture {:captured-at "2026-06-16T12:00:00Z"
               :mechanism :etaoin-sieve-js
               :best-effort? true
               :deterministic? false
               :warnings []}
     :environment {:viewport {:w 1280 :h 720}}
     :project (contract/projection-ref sample-projection)
     :payload-schema :shiftlefter.sieve.web/evidence.v1
     :payload {:html "<html><body><button>Save</button></body></html>"
               :inventory {:elements []}}
     :warnings []}))

(deftest evidence-snapshot-schema-is-interface-neutral
  (let [snapshot (sample-snapshot)]
    (is (s/valid? ::contract/evidence-snapshot snapshot))
    (is (= :shiftlefter.sieve.web/evidence.v1 (:payload-schema snapshot)))
    (is (nil? (:html snapshot)))
    (is (nil? (:rect snapshot)))
    (is (nil? (:locators snapshot)))
    (is (= "<html><body><button>Save</button></body></html>"
           (get-in snapshot [:payload :html])))))

(deftest analysis-result-is-deterministic-for-same-inputs
  (let [snapshot (sample-snapshot)
        base {:evidence (contract/evidence-ref snapshot)
              :provider (contract/provider-ref {:id "test-provider"
                                                :version "1"
                                                :config {:mode :default}})
              :projection (contract/projection-ref sample-projection)
              :candidates [{:candidate/id "cand-1"
                            :label "Save"}]
              :provider-inventory {:payload-schema :test/inventory.v1
                                   :payload {}}
              :warnings []
              :confidence {:basis :test}
              :alternatives []
              :completeness {:done? true}}
        a1 (contract/make-analysis-result base)
        a2 (contract/make-analysis-result base)]
    (is (s/valid? ::contract/analysis-result a1))
    (is (= a1 a2))
    (is (= (:analysis/id a1) (:analysis/id a2)))
    (is (= (:content/fingerprint a1) (:content/fingerprint a2)))))

(deftest one-snapshot-can-have-multiple-analysis-results
  (let [snapshot (sample-snapshot)
        analysis-for (fn [provider-version]
                       (contract/make-analysis-result
                         {:evidence (contract/evidence-ref snapshot)
                          :provider (contract/provider-ref {:id "test-provider"
                                                            :version provider-version
                                                            :config {}})
                          :projection (contract/projection-ref sample-projection)
                          :candidates []
                          :provider-inventory {}
                          :warnings []
                          :confidence {}
                          :alternatives []
                          :completeness {}}))
        a1 (analysis-for "1")
        a2 (analysis-for "2")]
    (is (= (:evidence a1) (:evidence a2)))
    (is (not= (:analysis/id a1) (:analysis/id a2)))))

(deftest interpretation-and-proposal-shapes-reference-analysis-without-mutation
  (let [snapshot (sample-snapshot)
        analysis (contract/make-analysis-result
                   {:evidence (contract/evidence-ref snapshot)
                    :provider (contract/provider-ref {:id "test-provider"
                                                      :version "1"
                                                      :config {}})
                    :projection (contract/projection-ref sample-projection)
                    :candidates [{:candidate/id "cand-1" :label "Save"}]
                    :provider-inventory {}
                    :warnings []
                    :confidence {}
                    :alternatives []
                    :completeness {}})
        interp (contract/make-interpretation
                 {:session-id "session-1"
                  :analysis (contract/analysis-ref analysis)
                  :target {:candidate/id "cand-1"}
                  :claims [{:claim/id "claim-1"
                            :kind :name
                            :value "Dashboard.save"}]
                  :status :accepted})
        proposal (contract/make-proposal-result
                   {:session-id "session-1"
                    :interpretations [(select-keys interp [:interpretation/id])]
                    :selected [{:claim/id "claim-1"}]
                    :rejected []
                    :unresolved []
                    :conflicting []
                    :intended-writes [{:path "glossary/intents/Dashboard.edn"
                                       :operation :upsert-binding}]
                    :diagnostics []
                    :status :draft})]
    (testing "interpretations and proposals are separate records"
      (is (s/valid? ::contract/interpretation interp))
      (is (s/valid? ::contract/proposal-result proposal))
      (is (= (contract/analysis-ref analysis) (:analysis interp)))
      (is (nil? (:claims analysis))))))
