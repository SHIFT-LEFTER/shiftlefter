(ns shiftlefter.sieve.shell
  "Walking-skeleton authoring shell (sl-toddler-shell-adaptation-o4x).

   Maps the browser UI's per-element interpretation — classify, rename, accept,
   reject, flag-ambiguity — onto the jl2 contract's Interpretation and Proposal
   Result records. Pure and deterministic over a loaded Analysis Result and a
   claim list: the live HTTP path and the headless round-trip test drive it the
   same way.

   Apply Proposal is out of scope here — proposals carry no intended writes, and
   nothing in this namespace touches glossary/intent files."
  (:require [shiftlefter.sieve.contract :as contract]
            [shiftlefter.sieve.reconcile :as reconcile]))

(defn- candidates-by-element-index
  "Index analysis candidates by the web element index they were derived from."
  [analysis]
  (into {}
        (keep (fn [candidate]
                (when-let [idx (get-in candidate [:source :element-index])]
                  [idx candidate])))
        (:candidates analysis)))

(def ^:private decision->bucket
  {:accept :selected :reject :rejected :ambiguous :unresolved})

(defn- normalize-claim
  "Turn one UI claim into an interpretation claim referencing its candidate.

   The claim never mutates the candidate; it carries a stable reference plus the
   human's classification, name, intent, notes, and decision."
  [candidates-by-idx claim]
  (let [idx (:element-index claim)
        candidate (get candidates-by-idx idx)]
    (cond-> {:element-index idx
             :candidate (when candidate (select-keys candidate [:candidate/id :kind]))
             :classification (some-> (:category claim) keyword)
             :decision (some-> (:decision claim) keyword)}
      (seq (:name claim)) (assoc :name (:name claim))
      (seq (:intent claim)) (assoc :intent (:intent claim))
      (seq (:notes claim)) (assoc :notes (:notes claim)))))

(defn build-proposal-result
  "Build a Proposal Result from a loaded Analysis Result and the UI's claims.

   Each claim is {:element-index int, :category str?, :name str?, :intent str?,
   :notes str?, :decision str?}. Claims partition into selected/rejected/
   unresolved by decision; undecided claims stay in the interpretation but enter
   no bucket.

   opts (all optional): :session-id, :interpretation-id, :proposal-id, :page-url.
   Pinning the ids makes the result fully deterministic for assertion; otherwise
   the contract assigns opaque ids per write."
  [analysis claims {:keys [session-id interpretation-id proposal-id page-url]}]
  (let [by-idx (candidates-by-element-index analysis)
        interp-claims (mapv #(normalize-claim by-idx %) claims)
        interpretation (contract/make-interpretation
                         {:id interpretation-id
                          :session-id session-id
                          :analysis (contract/analysis-ref analysis)
                          :target (cond-> {:kind :web}
                                    page-url (assoc :page-url page-url))
                          :claims interp-claims
                          :status :draft})
        bucketed (group-by #(decision->bucket (:decision %)) interp-claims)]
    (contract/make-proposal-result
      {:id proposal-id
       :session-id session-id
       :interpretations [interpretation]
       :selected (vec (:selected bucketed))
       :rejected (vec (:rejected bucketed))
       :unresolved (vec (:unresolved bucketed))
       :conflicting []
       :intended-writes []          ;; Apply Proposal stays out of scope (o4x)
       :diagnostics []
       :status :draft})))

(defn- normalize-reconcile-claim
  "Turn one human refinement of the SHARED vocabulary into an interpretation
   claim. Keyed by the cross-observation correspondence key (not a per-
   observation element index), so one decision spans both observations."
  [claim]
  (cond-> {:corr-key (:corr-key claim)
           :decision (some-> (:decision claim) keyword)}
    (seq (:name claim)) (assoc :name (:name claim))
    (seq (:intent claim)) (assoc :intent (:intent claim))
    (seq (:notes claim)) (assoc :notes (:notes claim))))

(defn build-reconciled-proposal
  "Build a Proposal Result over TWO Analysis Results.

   Carries the deterministic reconciliation diff (retained/new/disappeared/
   changed) under :reconciliation, plus any human refinements of the one shared
   vocabulary. Each claim is {:corr-key _, :decision str?, :name str?,
   :intent str?, :notes str?}, partitioned into selected/rejected/unresolved by
   decision. Refinement targets correspondence keys, so it spans both views
   without mutating raw evidence or analysis. Apply Proposal stays out of scope.

   opts (all optional): :session-id, :interpretation-id, :proposal-id."
  [analysis-a analysis-b claims {:keys [session-id interpretation-id proposal-id]}]
  (let [diff (reconcile/reconcile analysis-a analysis-b)
        interp-claims (mapv normalize-reconcile-claim claims)
        interpretation (contract/make-interpretation
                         {:id interpretation-id
                          :session-id session-id
                          :analysis {:a (contract/analysis-ref analysis-a)
                                     :b (contract/analysis-ref analysis-b)}
                          :target {:kind :web :observations 2}
                          :claims interp-claims
                          :status :draft})
        bucketed (group-by #(decision->bucket (:decision %)) interp-claims)]
    (contract/make-proposal-result
      {:id proposal-id
       :session-id session-id
       :interpretations [interpretation]
       :selected (vec (:selected bucketed))
       :rejected (vec (:rejected bucketed))
       :unresolved (vec (:unresolved bucketed))
       :conflicting []
       :intended-writes []
       :diagnostics []
       :reconciliation diff
       :status :draft})))
