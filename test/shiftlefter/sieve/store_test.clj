(ns shiftlefter.sieve.store-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [shiftlefter.sieve.contract :as contract]
            [shiftlefter.sieve.store :as store]))

(defn- with-temp-root [f]
  (let [root (fs/create-temp-dir)]
    (try
      (f root)
      (finally
        (fs/delete-tree root)))))

(defn- snapshot []
  (contract/make-evidence-snapshot
    {:interface {:name :web :type :web}
     :source {:kind :fixture}
     :capture {:captured-at "2026-06-16T12:00:00Z"
               :mechanism :etaoin-sieve-js
               :best-effort? true
               :deterministic? false
               :warnings []}
     :environment {}
     :project {:fingerprint "projection-fp"}
     :payload-schema :shiftlefter.sieve.web/evidence.v1
     :payload {:html "<html></html>" :inventory {:elements []}}
     :warnings []}))

(defn- analysis [evidence]
  (contract/make-analysis-result
    {:evidence (contract/evidence-ref evidence)
     :provider (contract/provider-ref {:id "provider"
                                       :version "1"
                                       :config {}})
     :projection {:fingerprint "projection-fp"}
     :candidates []
     :provider-inventory {}
     :warnings []
     :confidence {}
     :alternatives []
     :completeness {}}))

(deftest saves-and-loads-separable-sieve-artifacts
  (with-temp-root
    (fn [root]
      (let [evidence (store/save-evidence-snapshot! root (snapshot))
            analysis (store/save-analysis-result! root (analysis evidence))
            interpretation (store/save-interpretation!
                             root
                             (contract/make-interpretation
                               {:session-id "session-1"
                                :analysis (contract/analysis-ref analysis)
                                :target {:candidate/id "cand-1"}
                                :claims []
                                :status :draft}))
            proposal (store/save-proposal-result!
                       root
                       (contract/make-proposal-result
                         {:session-id "session-1"
                          :interpretations [(select-keys interpretation
                                                          [:interpretation/id])]
                          :selected []
                          :rejected []
                          :unresolved []
                          :conflicting []
                          :intended-writes []
                          :diagnostics []
                          :status :draft}))
            session (store/save-session!
                      root
                      (store/make-session
                        {:id "session-1"
                         :evidence-snapshots [(contract/evidence-ref evidence)]
                         :analysis-results [(contract/analysis-ref analysis)]
                         :interpretations [(select-keys interpretation
                                                         [:interpretation/id])]
                         :proposal-results [(select-keys proposal
                                                          [:proposal/id])]}))]
        (is (= evidence
               (store/load-evidence-snapshot root (:evidence/id evidence))))
        (is (= analysis
               (store/load-analysis-result root (:analysis/id analysis))))
        (is (= interpretation
               (store/load-interpretation root (:interpretation/id interpretation))))
        (is (= proposal
               (store/load-proposal-result root (:proposal/id proposal))))
        (is (= (:evidence-snapshots session)
               (:evidence-snapshots (store/load-session root "session-1"))))
        (is (fs/exists? (fs/path root "evidence-snapshots"
                                 (str (:evidence/id evidence) ".edn"))))
        (is (fs/exists? (fs/path root "analysis-results"
                                 (str (:analysis/id analysis) ".edn"))))))))

(deftest default-root-is-ignored-project-local-state
  (is (= ".shiftlefter/sieve" (str (store/root-path {})))))
