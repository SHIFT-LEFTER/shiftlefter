(ns shiftlefter.sieve.nested-perception-test
  "Proof of record for SIEVE nested/collection perception (sl-wbn): headless,
   no ChromeDriver, over the regenerated 043 web-catalog snapshots. Asserts the
   deterministic typed-candidate structure — collection / component / widget /
   element linked by :parent/:children — that the reconcile bead diffs and that
   maps onto the live intent model (examples/06 product-card.edn). This is the
   go/no-go signal; the gated live capture only regenerates the substrate."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.sieve.contract :as contract]
            [shiftlefter.sieve.web :as web]
            [shiftlefter.sieve.store :as store]))

(def fixture-a "test/fixtures/sieve/web-catalog-a-snapshot.edn")
(def fixture-b "test/fixtures/sieve/web-catalog-b-snapshot.edn")

(defn- analysis [path]
  (web/analyze-web-evidence
    (contract/with-evidence-identity (store/load-fixture path))
    {:provider-config {:mode :fixture}}))

(defn- anchor [c]
  (or (get-in c [:payload :locators :testid])
      (get-in c [:payload :locators :id])))

(defn- by-anchor [an a]
  (->> (:candidates an) (filter #(= a (anchor %))) first))

(defn- by-kind [an k]
  (filterv #(= k (:kind %)) (:candidates an)))

(defn- by-id [an id]
  (->> (:candidates an) (filter #(= id (:candidate/id %))) first))

(defn- children-of [an c]
  (mapv #(by-id an %) (:children c)))

;; -----------------------------------------------------------------------------
;; State A — the repeated collection + the nested reusable widget
;; -----------------------------------------------------------------------------

(deftest three-cards-form-a-parent-anchored-collection
  (let [a (analysis fixture-a)
        coll (first (by-kind a :collection))]
    (is (some? coll) "a collection node is synthesized")
    (is (= :many (:cardinality coll)))
    (is (= 3 (:observed (:count coll))))
    (is (= {:web {:css ".results .card"}} (:selector coll))
        "parent-anchored selector, mirroring examples/06")
    (is (= ["card-1001" "card-1002" "card-1003"]
           (mapv anchor (children-of a coll)))
        "the 3 cards are members — not flat siblings")
    (is (every? #(= :component (:kind %)) (children-of a coll)))
    (is (= "results" (anchor (by-id a (:parent coll))))
        "the collection hangs under the results section")))

(deftest rating-is-a-widget-nested-under-card-1001
  (let [a (analysis fixture-a)
        card (by-anchor a "card-1001")
        kids (children-of a card)
        widget (first (filter #(= :widget (:kind %)) kids))]
    (is (= :component (:kind card)))
    (is (some? widget) "card-1001 carries a nested widget")
    (is (= {:max 1} (:count widget)))
    (is (= {:web {:css ".rating"}} (:selector widget)))
    (is (= ["rating-1001"] (mapv anchor (children-of a widget)))
        "the rating widget nests the stars element")
    (testing "the card's other children are plain elements (title, price)"
      (is (= #{:element}
             (set (map :kind (remove #(= :widget (:kind %)) kids))))))))

;; -----------------------------------------------------------------------------
;; State B — disappearance, ambiguity surfaced, a new grouping
;; -----------------------------------------------------------------------------

(deftest b-collection-shrinks-and-ambiguity-is-surfaced
  (let [b (analysis fixture-b)
        coll (first (by-kind b :collection))
        qv (by-anchor b "quickview-card-1001")]
    (testing "card-1003 disappeared — the collection now has two members"
      (is (= 2 (:observed (:count coll))))
      (is (= ["card-1001" "card-1002"] (mapv anchor (children-of b coll))))
      (is (nil? (by-anchor b "card-1003"))))
    (testing "the lone reused card is flagged, never silently flattened (dde AC6)"
      (is (= :component (:kind qv)))
      (is (= {:reason :isolated-collection-member} (:ambiguous qv))))
    (testing "the add-to-cart form is a widget grouping its controls"
      (let [w (by-anchor b "add-to-cart")]
        (is (= :widget (:kind w)))
        (is (= #{"qty-input" "add-to-cart-btn"}
               (set (map anchor (children-of b w)))))))))

;; -----------------------------------------------------------------------------
;; Determinism + the model is no longer flat-only
;; -----------------------------------------------------------------------------

(deftest analysis-is-deterministic-and-typed
  (doseq [path [fixture-a fixture-b]]
    (let [an (analysis path)]
      (is (= an (analysis path)) (str path " analyzes deterministically"))
      (is (s/valid? ::contract/analysis-result an))
      (is (seq (by-kind an :collection)))
      (is (seq (by-kind an :widget)))
      (is (seq (by-kind an :component)))
      (is (not= #{:element} (set (map :kind (:candidates an))))
          "the candidate model is no longer flat-only"))))
