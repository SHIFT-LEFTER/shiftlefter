(ns shiftlefter.sieve.fixture-ab-test
  "Headless, deterministic proof for the SIEVE A/B fixture (sl-043).

   Loads both committed Evidence Snapshots, analyzes them with no live browser,
   and asserts the analysis is deterministic and carries the raw material for
   every reconcile claim — retained / new / disappeared / changed — keyed off
   the stable anchors authored into the fixture. The reconcile diff itself is
   out of scope here (-> sl-sieve-two-observation-reconcile-bun)."
  (:require [babashka.fs :as fs]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.demo.fixture.handler :as handler]
            [shiftlefter.demo.fixture.sieve-catalog]
            [shiftlefter.sieve.contract :as contract]
            [shiftlefter.sieve.store :as store]
            [shiftlefter.sieve.web :as web]))

(def fixture-a "test/fixtures/sieve/web-catalog-a-snapshot.edn")
(def fixture-b "test/fixtures/sieve/web-catalog-b-snapshot.edn")

(def projection
  {:projection/id "proj-043-test"
   :projection/version 1
   :fingerprint "fixed-043-projection"
   :source :working-tree
   :status :ok})

(def analyze-opts {:projection projection :provider-config {:mode :fixture}})

(defn- snapshot [path]
  (contract/with-evidence-identity (store/load-fixture path)))

(defn- analyze [snap]
  (web/analyze-web-evidence snap analyze-opts))

(defn- anchor
  "The stable cross-observation anchor for a candidate: its data-testid, else
   its id. Candidates anchored only by css-class return nil."
  [candidate]
  (or (get-in candidate [:payload :locators :testid])
      (get-in candidate [:payload :locators :id])))

(defn- anchor-set [analysis]
  (->> (:candidates analysis) (keep anchor) set))

(defn- by-anchor [analysis a]
  (->> (:candidates analysis) (filter #(= a (anchor %))) first))

;; -----------------------------------------------------------------------------
;; Snapshots are valid, deterministic web evidence
;; -----------------------------------------------------------------------------

(deftest both-snapshots-are-valid-web-evidence
  (doseq [path [fixture-a fixture-b]]
    (let [snap (snapshot path)]
      (is (s/valid? ::contract/evidence-snapshot snap)
          (str path " conforms to evidence-snapshot"))
      (is (= :shiftlefter.sieve.web/evidence.v1 (:payload-schema snap)))
      (is (string? (get-in snap [:payload :html])))
      (is (seq (get-in snap [:payload :inventory :elements]))))))

(deftest analysis-is-deterministic-without-live-browser
  (doseq [path [fixture-a fixture-b]]
    (let [snap (snapshot path)]
      (is (= (analyze snap) (analyze snap))
          (str path " analyzes deterministically"))
      (is (s/valid? ::contract/analysis-result (analyze snap)))
      (is (false? (get-in (analyze snap) [:completeness :live-capture?]))))))

(deftest candidate-shape-matches-the-authored-collection
  (let [a (analyze (snapshot fixture-a))
        b (analyze (snapshot fixture-b))]
    ;; Counts/kinds reflect the nested typed-candidate shape (sl-wbn): element
    ;; leaves + synthesized component/collection/widget nodes.
    (is (= 18 (count (:candidates a))))
    (is (= 24 (count (:candidates b))))
    (is (= #{:element :component :collection :widget}
           (set (map :kind (:candidates a)))))
    (is (= #{:element :component :collection :widget}
           (set (map :kind (:candidates b)))))))

;; -----------------------------------------------------------------------------
;; Raw material for the four reconcile cases (the diff itself is the next bead)
;; -----------------------------------------------------------------------------

(deftest fixture-sets-up-the-four-reconcile-cases
  (let [a (analyze (snapshot fixture-a))
        b (analyze (snapshot fixture-b))
        a-anchors (anchor-set a)
        b-anchors (anchor-set b)]
    (testing "retained — same anchors present in both observations"
      (is (set/subset? #{"site-nav" "nav-catalog" "nav-about" "results"
                         "card-1001" "rating-1001" "card-1002"}
                       (set/intersection a-anchors b-anchors))))
    (testing "disappeared — present in A, absent in B"
      (is (contains? a-anchors "card-1003"))
      (is (not (contains? b-anchors "card-1003"))))
    (testing "new — present in B, absent in A"
      (is (set/subset? #{"quickview" "quickview-card-1001" "quickview-rating-1001"
                         "qty-input" "add-to-cart-btn"}
                       (set/difference b-anchors a-anchors))))
    (testing "changed — same retained anchor, different content"
      (is (= "Beacon Clock$28" (:label (by-anchor a "card-1002"))))
      (is (= "Beacon Clock$22 sale" (:label (by-anchor b "card-1002"))))
      (is (not= (:label (by-anchor a "card-1002"))
                (:label (by-anchor b "card-1002")))))
    (testing "the reused widget appears in a new container in B"
      (is (= :chrome (:category (by-anchor b "quickview-card-1001")))))))

;; -----------------------------------------------------------------------------
;; Persisted via the contract store (no repo pollution — temp root)
;; -----------------------------------------------------------------------------

(deftest snapshots-round-trip-through-the-store
  (let [root (fs/create-temp-dir)]
    (try
      (doseq [path [fixture-a fixture-b]]
        (let [direct (snapshot path)
              saved (store/save-evidence-snapshot! root direct)
              reloaded (store/load-evidence-snapshot root (:evidence/id saved))]
          (is (= direct reloaded) (str path " survives a store round-trip"))
          (is (= (analyze direct) (analyze reloaded)))))
      (finally
        (fs/delete-tree root)))))

;; -----------------------------------------------------------------------------
;; The states are served via the demo page-registry (no browser)
;; -----------------------------------------------------------------------------

(deftest states-are-served-via-the-page-registry
  (let [app (handler/build-handler {:pages [:sieve-catalog]})
        get-page (fn [uri] (app {:request-method :get :uri uri :headers {}}))
        a-resp (get-page "/sieve/catalog")
        b-resp (get-page "/sieve/catalog/quickview")]
    (is (= 200 (:status a-resp)))
    (is (= 200 (:status b-resp)))
    (testing "state A body carries the collection + nested-widget anchors"
      (doseq [tid ["site-nav" "results" "card-1001" "card-1002" "card-1003"
                   "rating-1001"]]
        (is (str/includes? (:body a-resp) (str "\"" tid "\"")))))
    (testing "state B body carries the retained + new anchors"
      (doseq [tid ["site-nav" "card-1001" "card-1002" "quickview"
                   "quickview-card-1001" "add-to-cart"]]
        (is (str/includes? (:body b-resp) (str "\"" tid "\"")))))))
