(ns shiftlefter.sieve.reconcile-test
  "Proof of record for SIEVE two-observation reconciliation (sl-bun): headless,
   no ChromeDriver, over the committed 043 web-catalog A/B snapshots. Asserts a
   deterministic reconciled claim set — retained / new / disappeared / changed —
   matched by the derived cross-observation correspondence key (decisions/
   sieve.md 2026-06-19), with structural deltas distinguished from leaf deltas.
   This is the go/no-go-relevant proof (-> sl-sieve-0-5-go-no-go-pgd)."
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.sieve.contract :as contract]
            [shiftlefter.sieve.reconcile :as reconcile]
            [shiftlefter.sieve.shell :as shell]
            [shiftlefter.sieve.store :as store]
            [shiftlefter.sieve.web :as web]))

(def fixture-a "test/fixtures/sieve/web-catalog-a-snapshot.edn")
(def fixture-b "test/fixtures/sieve/web-catalog-b-snapshot.edn")

(defn- analysis [path]
  (web/analyze-web-evidence
    (contract/with-evidence-identity (store/load-fixture path))
    {:provider-config {:mode :fixture}}))

(def a (delay (analysis fixture-a)))
(def b (delay (analysis fixture-b)))
(def diff (delay (reconcile/reconcile @a @b)))

(defn- by-anchor [claims anc]
  (->> claims (filter #(= anc (:anchor %))) first))

(defn- delta-for [claim field]
  (->> (:deltas claim) (filter #(= field (:field %))) first))

;; -----------------------------------------------------------------------------
;; Determinism
;; -----------------------------------------------------------------------------

(deftest reconcile-is-deterministic
  (is (= @diff (reconcile/reconcile @a @b))
      "same two analyses -> identical reconciled claim set")
  (testing "every bucket present, claims sorted by correspondence key"
    (doseq [bucket [:retained :new :disappeared :changed]]
      (is (vector? (bucket @diff)))
      (is (= (bucket @diff)
             (vec (sort-by (comp pr-str :corr-key) (bucket @diff))))))))

;; -----------------------------------------------------------------------------
;; Structural diff case 1: the collection shrinks (card-1003 leaves A->B)
;; -----------------------------------------------------------------------------

(deftest collection-membership-shrink-is-structural
  (testing "card-1003 disappeared as its own claim"
    (let [gone (by-anchor (:disappeared @diff) "card-1003")]
      (is (some? gone) "card-1003 is in the disappeared bucket")
      (is (= :component (:kind gone)))))
  (testing "the SAME event also surfaces as a structural change on the collection"
    (let [coll (->> (:changed @diff) (filter #(= :collection (:kind %))) first)]
      (is (some? coll) "the collection node was matched, not disappeared+new")
      (is (= [:struct [:anchor "results"] :collection {:web {:css ".results .card"}}]
             (:corr-key coll))
          "matched by membership-independent correspondence key")
      (is (= :structural (:delta-kind (delta-for coll :children-corrs)))
          "children-corrs shrink is a structural delta")
      (is (= [[:anchor "card-1001"] [:anchor "card-1002"] [:anchor "card-1003"]]
             (:from (delta-for coll :children-corrs))))
      (is (= [[:anchor "card-1001"] [:anchor "card-1002"]]
             (:to (delta-for coll :children-corrs))))
      (is (= :structural (:delta-kind (delta-for coll :count)))
          "the observed count is also a structural delta")))
  (testing "the two correlated claims are kept, not deduped"
    (is (and (by-anchor (:disappeared @diff) "card-1003")
             (->> (:changed @diff) (some #(= :collection (:kind %))))))))

;; -----------------------------------------------------------------------------
;; Structural diff case 2: the quickview NEW subtree
;; -----------------------------------------------------------------------------

(deftest quickview-subtree-is-new
  (let [new-anchors (set (keep :anchor (:new @diff)))]
    (testing "the whole quickview subtree appears as new (aside + reused card + form)"
      (is (set/subset?
            #{"quickview" "quickview-card-1001" "quickview-rating-1001"
              "add-to-cart" "qty-input" "add-to-cart-btn"}
            new-anchors)))
    (testing "the reused card's wbn ambiguity is carried through, not flattened"
      (let [card (by-anchor (:new @diff) "quickview-card-1001")]
        (is (= {:reason :isolated-collection-member} (:ambiguous card))))))
  (testing "none of the quickview anchors existed in A (honest: reuse detection is post-0.5)"
    (let [a-anchors (set (keep :anchor (concat (:retained @diff)
                                               (:changed @diff)
                                               (:disappeared @diff))))]
      (is (not (contains? a-anchors "quickview-card-1001"))))))

;; -----------------------------------------------------------------------------
;; Leaf diff case: card-1002 price change, distinguished from structural
;; -----------------------------------------------------------------------------

(deftest card-1002-price-change-is-a-leaf-delta
  (let [card (by-anchor (:changed @diff) "card-1002")]
    (is (some? card) "card-1002 was matched as changed, not disappeared+new")
    (is (= :component (:kind card)))
    (let [d (delta-for card :label)]
      (is (= :leaf (:delta-kind d)) "a label change is a leaf delta, not structural")
      (is (= "Beacon Clock$28" (:from d)))
      (is (= "Beacon Clock$22 sale" (:to d))))
    (is (every? #(= :leaf (:delta-kind %)) (:deltas card))
        "card-1002 changed only at the leaf, no structural delta")))

(deftest retained-nodes-are-unchanged-in-both
  (testing "stable anchors with no content/structure change are retained"
    (is (by-anchor (:retained @diff) "card-1001"))
    (is (by-anchor (:retained @diff) "site-nav"))
    (is (by-anchor (:retained @diff) "nav-catalog"))))

;; -----------------------------------------------------------------------------
;; The diff rides the contract's Proposal Result (AC4) and the shell builds it
;; -----------------------------------------------------------------------------

(deftest reconciled-proposal-carries-the-diff
  (let [claims [{:corr-key [:anchor "add-to-cart"] :decision "accept"
                 :name "Add to cart" :intent "AddToCart"}
                {:corr-key [:anchor "card-1003"] :decision "reject"}]
        proposal (shell/build-reconciled-proposal
                   @a @b claims {:session-id "sess-bun" :proposal-id "prop-bun"})]
    (is (s/valid? ::contract/proposal-result proposal)
        (s/explain-str ::contract/proposal-result proposal))
    (testing "the Proposal Result carries the four reconciliation buckets (AC4)"
      (is (= #{:retained :new :disappeared :changed}
             (set (keys (:reconciliation proposal)))))
      (is (= @diff (:reconciliation proposal))))
    (testing "human refinement of the shared vocabulary spans both observations"
      (is (= 1 (count (:selected proposal))))
      (is (= 1 (count (:rejected proposal))))
      (is (= [:anchor "add-to-cart"] (:corr-key (first (:selected proposal)))))
      (is (= "AddToCart" (:intent (first (:selected proposal))))))
    (testing "the interpretation references both analyses, mutating neither"
      (let [interp (first (:interpretations proposal))]
        (is (contains? (:analysis interp) :a))
        (is (contains? (:analysis interp) :b))))
    (is (= [] (:intended-writes proposal))
        "Apply Proposal stays out of scope — no glossary/intent writes")))

(deftest single-observation-proposal-defaults-reconciliation-empty
  (testing "the :reconciliation key is additive — single-observation stays valid"
    (let [proposal (shell/build-proposal-result @a [] {:session-id "s" :proposal-id "p"})]
      (is (s/valid? ::contract/proposal-result proposal))
      (is (= contract/empty-reconciliation (:reconciliation proposal))))))
