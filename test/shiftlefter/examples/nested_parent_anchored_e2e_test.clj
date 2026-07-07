(ns shiftlefter.examples.nested-parent-anchored-e2e-test
  "Internal validator for examples/06-nested-parent-anchored. Setup and the
   user-facing artifacts live in that directory; this asserts the parent-anchored
   addressing surface against the live fixture DOM. See e2e-helpers for why this
   exists rather than the example's .feature file carrying every assertion.

   The live cases are gated on SHIFTLEFTER_LIVE_WEBDRIVER (^:e2e suite). The
   §7.5 load-time validation case needs no browser and runs in the default suite."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.examples.e2e-helpers :as h]
            [shiftlefter.intent.loader :as loader]
            ;; Side-effect: registers the :catalog fixture page.
            [shiftlefter.demo.fixture.catalog]))

(def ^:private intents-dir "examples/06-nested-parent-anchored/glossary/intents")

;; -----------------------------------------------------------------------------
;; §7.5 fallback chain — load-time, no browser (sl-1ps acceptance #1)
;; -----------------------------------------------------------------------------

(deftest example-intents-load-clean
  (testing "the example's own parent-anchored glossary passes load validation"
    (is (nil? (:errors (loader/load-all-intents intents-dir))))))

(deftest missing-anchor-is-a-loud-load-error
  (testing "a collection with no :selector referencing a component with no :root → §7.5 error"
    (let [dir (str (fs/create-temp-dir))]
      (spit (str (fs/path dir "dash.edn"))
            (pr-str {:intent "Dash" :elements {}
                     :collections {:item {:intent "Card" :cardinality :many}}}))
      (spit (str (fs/path dir "card.edn"))
            (pr-str {:intent "Card" :elements {:title {:bindings {:web {:css ".title"}}}}}))
      (let [err (-> (loader/load-all-intents dir) :errors first)]
        (is (= :intent/missing-anchor (:type err)))
        (is (re-find #"Dash\.item references Card" (:message err))
            "the error names the offending collection and component")))))

;; -----------------------------------------------------------------------------
;; Live resolution against the fixture DOM (^:e2e, gated)
;; -----------------------------------------------------------------------------

(deftest ^:e2e parent-anchored-resolution
  (if-not h/live?
    (is true "skipped — SHIFTLEFTER_LIVE_WEBDRIVER not set")
    (h/run-against
     {:intents-dir intents-dir :pages [:catalog] :path "/catalog"}
     (fn [browser _intents resolve]
       (testing "the same ProductCard resolves under three different wrappers"
         (is (= "Widget A" (h/text-of browser (resolve "Dashboard.featured[1].title"))))
         (is (= "$20"      (h/text-of browser (resolve "Dashboard.featured[2].price"))))
         (is (= "Gadget X" (h/text-of browser (resolve "Dashboard.sidebar[1].title"))))
         (is (= "Thing 1"  (h/text-of browser (resolve "Dashboard.results[1].title")))))

       (testing "[-1] last and [*] whole-collection fan-out"
         (is (= "Thing 3" (h/text-of browser (resolve "Dashboard.results[-1].title"))))
         (is (= ["Thing 1" "Thing 2" "Thing 3"]
                (h/texts-of browser (resolve "Dashboard.results[*].title")))))

       (testing "heterogeneous-cell exclusion (§7.7): the .promo banner is not a card"
         (is (= ["Gadget X" "Gadget Y"]
                (h/texts-of browser (resolve "Dashboard.sidebar[*].title")))))

       (testing "nested descent into a card's rating region"
         (is (= "4.5" (h/text-of browser (resolve "Dashboard.featured[1].rating.stars")))))

       (testing "out-of-range is a loud error, never nil (§5)"
         (is (= :intent/index-out-of-range
                (h/error-type (resolve "Dashboard.featured[9].title")))))))))
