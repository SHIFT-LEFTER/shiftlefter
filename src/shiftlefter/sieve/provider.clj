(ns shiftlefter.sieve.provider
  "SIEVE provider boundary.

   Capture may be impure and best-effort. Analysis must be deterministic over
   immutable evidence, accepted projection identity, provider version, and
   provider config."
  (:require [clojure.set :as set]
            [shiftlefter.sieve.contract :as contract]))

(defprotocol ISieveProvider
  (capture-evidence [provider request]
    "Capture immutable evidence from a live or offline source.")
  (analyze-evidence [provider request]
    "Analyze an Evidence Snapshot and return an Analysis Result."))

(defn- candidate-key [candidate]
  (or (:candidate/id candidate)
      (:id candidate)
      (:locator candidate)
      (:label candidate)
      (contract/fingerprint candidate)))

(defn- indexed-candidates [analysis]
  (into {}
        (map (fn [candidate] [(candidate-key candidate) candidate]))
        (:candidates analysis)))

(defn compare-analysis-results
  "Compare two Analysis Results for provider-upgrade or config-drift review."
  [before after]
  (let [before-candidates (indexed-candidates before)
        after-candidates (indexed-candidates after)
        before-keys (set (keys before-candidates))
        after-keys (set (keys after-candidates))
        retained (set/intersection before-keys after-keys)]
    {:before (contract/analysis-ref before)
     :after (contract/analysis-ref after)
     :candidate-diff {:added (mapv after-candidates
                                   (sort-by pr-str
                                            (set/difference after-keys before-keys)))
                      :removed (mapv before-candidates
                                     (sort-by pr-str
                                              (set/difference before-keys after-keys)))
                      :changed (->> retained
                                    (keep (fn [k]
                                            (let [before-candidate (before-candidates k)
                                                  after-candidate (after-candidates k)]
                                              (when (not= before-candidate after-candidate)
                                                {:key k
                                                 :before before-candidate
                                                 :after after-candidate}))))
                                    vec)}
     :warning-diff {:before-only (vec (remove (set (:warnings after))
                                              (:warnings before)))
                    :after-only (vec (remove (set (:warnings before))
                                             (:warnings after)))}}))
