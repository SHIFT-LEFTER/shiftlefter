(ns shiftlefter.examples.nested-self-rooted-e2e-test
  "Internal validator for examples/05-nested-self-rooted. Setup and the
   user-facing artifacts live in that directory; this asserts the self-rooted
   addressing surface against the live fixture DOM. See e2e-helpers for why this
   exists rather than the example's .feature file carrying every assertion.

   Gated on SHIFTLEFTER_LIVE_WEBDRIVER (^:e2e suite).

   NOTE: `self-rooted-recursion-sl-h7h-acceptance` below asserts the CORRECT
   §8.1 (nearest-enclosing-instance) semantics and is the acceptance test for
   bead sl-h7h. It is EXPECTED RED until that resolver fix lands — today the
   resolver matches across nested instance boundaries, so the post's own author
   is ambiguous and the top-level post count includes the quoted post. The
   flat-timeline test is green today."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.browser.protocol :as bp]
            [shiftlefter.browser.url-match :as url-match]
            [shiftlefter.examples.e2e-helpers :as h]
            [shiftlefter.intent.resolve :as intent-resolve]
            ;; Side-effect: registers the :feed fixture page.
            [shiftlefter.demo.fixture.feed]))

(def ^:private intents-dir "examples/05-nested-self-rooted/sl/glossary/intents")

(defn- with-feed [f]
  (h/run-against {:intents-dir intents-dir :pages [:feed] :path "/feed"} f))

;; -----------------------------------------------------------------------------
;; Flat timeline — green today (no self-nesting, so no §8.1 dependency)
;; -----------------------------------------------------------------------------

(defn- flat-timeline-assertions
  "Shared by the etaoin validator and the playwright × strategy variants
   (sl-tnx) — same assertions, same live DOM, whichever backend resolves."
  [browser resolve]
  (testing "self-rooted Post indexes via :root with no parent :selector"
    (is (= "Alice" (h/text-of browser (resolve "Feed.post[1].author"))))
    (is (= "Bob"   (h/text-of browser (resolve "Feed.post[2].author")))))

  (testing "heterogeneous-cell exclusion (§7.7): post[3] is Carol, not the ad between"
    (is (= "Carol" (h/text-of browser (resolve "Feed.post[3].author")))))

  (testing "[-1] last and [*] whole-collection fan-out"
    (is (= "Dave" (h/text-of browser (resolve "Feed.post[-1].author"))))
    (is (= ["Alice" "Bob" "Carol" "Dave"]
           (h/texts-of browser (resolve "Feed.post[*].author")))))

  (testing "out-of-range is a loud error, never nil (§5)"
    (is (= :intent/index-out-of-range
           (h/error-type (resolve "Feed.post[9].author"))))))

(deftest ^:e2e self-rooted-flat-timeline
  (if-not h/live?
    (is true "skipped — SHIFTLEFTER_LIVE_WEBDRIVER not set")
    (with-feed
      (fn [browser _intents resolve]
        (flat-timeline-assertions browser resolve)))))

;; -----------------------------------------------------------------------------
;; Named-location navigation (sl-3jr4) — green today
;; -----------------------------------------------------------------------------

(deftest ^:e2e named-location-navigation
  (if-not h/live?
    (is true "skipped — SHIFTLEFTER_LIVE_WEBDRIVER not set")
    (h/run-against*
     {:intents-dir intents-dir :pages [:feed] :path "/feed"}
     (fn [{:keys [browser intents base-url]}]
       (testing "'Feed' resolves via its :location :path + the fixture base-url"
         (let [r (intent-resolve/resolve-location intents "Feed" :web base-url)]
           (is (= (str base-url "/feed") (:ok r))
               "resolve-location is the single URL-assembly point")
           (bp/open-to! browser (:ok r))
           (is (nil? (url-match/region-match (:ok r) (str (bp/get-url browser))))
               "the region assertion (sl-q81m) matches the landed URL")
           (is (some? (url-match/region-match "/elsewhere"
                                              (str (bp/get-url browser))))
               "and a different region does not")))))))

;; -----------------------------------------------------------------------------
;; Recursion + cross-type descent — green today
;; -----------------------------------------------------------------------------

(defn- nested-descent-assertions
  [browser resolve]
  (testing "descent into a quoted post (recursion: Post inside Post)"
    (is (= "Zoe" (h/text-of browser (resolve "Thread.post[1].quoted.author")))))

  (testing "cross-type descent into comments, fanned out and indexed"
    (is (= "Bob"   (h/text-of browser (resolve "Thread.post[1].comment[1].author"))))
    (is (= "Carol" (h/text-of browser (resolve "Thread.post[1].comment[2].author"))))
    (is (= ["Bob" "Carol"]
           (h/texts-of browser (resolve "Thread.post[1].comment[*].author"))))))

(deftest ^:e2e self-rooted-nested-descent
  (if-not h/live?
    (is true "skipped — SHIFTLEFTER_LIVE_WEBDRIVER not set")
    (with-feed
      (fn [browser _intents resolve]
        (nested-descent-assertions browser resolve)))))

;; -----------------------------------------------------------------------------
;; §8.1 nearest-enclosing-instance — ACCEPTANCE TEST FOR sl-h7h (red until fix)
;; -----------------------------------------------------------------------------

(defn- nearest-enclosing-instance-assertions
  "The §8.1 pruning semantics proper — the assertions most directly exercised
   by query-all-pruned, and the reason the playwright variants exist (sl-tnx:
   the Playwright pruning filter had never run against a live DOM)."
  [browser resolve]
  (testing "the post's OWN author is unique — not the quote's or a comment's (§8.1)"
    (is (= "Alice" (h/text-or-error browser (resolve "Thread.post[1].author")))))

  (testing "the top-level post collection excludes the nested quoted post (§8.1)"
    (let [posts (resolve "Thread.post[*]")]
      (is (and (sequential? posts) (= 1 (count posts)))))))

(deftest ^:e2e ^:sl-h7h self-rooted-recursion-sl-h7h-acceptance
  (if-not h/live?
    (is true "skipped — SHIFTLEFTER_LIVE_WEBDRIVER not set")
    (with-feed
      (fn [browser _intents resolve]
        (nearest-enclosing-instance-assertions browser resolve)))))

;; -----------------------------------------------------------------------------
;; Playwright × pruning-strategy variants (sl-tnx): the same assertion bodies
;; against the same live DOM, resolved by the Playwright backend under both
;; strategies — :fast (fused evaluateHandle, ElementHandle snapshots) and
;; :safe (retained per-candidate Locator filter). Neither path had ever been
;; verified against a real DOM before this bead.
;; -----------------------------------------------------------------------------

(deftest ^:e2e self-rooted-resolution-playwright
  (if-not h/live-playwright?
    (is true "skipped — SHIFTLEFTER_LIVE_PLAYWRIGHT not set")
    (doseq [pruning [:fast :safe]]
      (testing (str "pruning strategy " pruning)
        (h/run-against
         {:intents-dir intents-dir :pages [:feed] :path "/feed"
          :adapter :playwright :pruning pruning}
         (fn [browser _intents resolve]
           (flat-timeline-assertions browser resolve)
           (nested-descent-assertions browser resolve)
           (nearest-enclosing-instance-assertions browser resolve)))))))
