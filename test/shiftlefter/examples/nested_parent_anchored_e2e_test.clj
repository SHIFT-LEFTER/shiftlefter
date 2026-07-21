(ns shiftlefter.examples.nested-parent-anchored-e2e-test
  "Internal validator for examples/06-nested-parent-anchored. Setup and the
   user-facing artifacts live in that directory; this asserts the parent-anchored
   addressing surface against the live fixture DOM. See e2e-helpers for why this
   exists rather than the example's .feature file carrying every assertion.

   The live cases are gated on SHIFTLEFTER_LIVE_WEBDRIVER (^:e2e suite). The
   §7.5 load-time validation case needs no browser and runs in the default suite."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.examples.e2e-helpers :as h]
            [shiftlefter.intent.loader :as loader]
            [shiftlefter.intent.state :as intent-state]
            [shiftlefter.stepdefs.browser :as browser-steps]
            [shiftlefter.stepengine.bind :as bind]
            [shiftlefter.stepengine.bindings :as bindings]
            [shiftlefter.stepengine.registry :as registry]
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

;; -----------------------------------------------------------------------------
;; Live web capture into the data plane (^:e2e, gated) — sl-zgna
;;
;; The one thing FakeBrowser units structurally cannot prove: live-DOM text
;; extraction feeding bindings/capture!. This drives the REAL capture stepdef
;; (registry-matched, engine-style capture normalization) against the fixture
;; catalog in real Chrome and asserts the named group lands in :sl/bindings.
;; -----------------------------------------------------------------------------

(defn- invoke-registered-step
  "Match text against the registry and invoke the stepdef ctx-first,
   replicating the engine's capture normalization (sl-yh7). Local copy of
   the browser-test helper — extraction into a shared test helper is a
   filed chore from the yh7 fit-pass."
  [text ctx]
  (let [stepdef (first (filter #(re-matches (:pattern %) text)
                               (registry/all-stepdefs)))
        _ (when-not stepdef
            (throw (ex-info (str "No stepdef matches: " text) {})))
        matcher (re-matcher (:pattern stepdef) text)
        _ (.matches matcher)
        raw (mapv #(.group matcher %) (range 1 (inc (.groupCount matcher))))
        kinds (bind/default-slot-kinds (:metadata stepdef) (count raw))
        {:keys [ok error]} (bindings/normalize-captures raw kinds ctx)
        _ (when error (throw (ex-info (:message error) error)))
        args (if (= (:arity stepdef) (inc (count ok)))
               (into [ctx] ok)
               ok)]
    (apply (:fn stepdef) args)))

(deftest ^:e2e web-capture-binds-from-live-dom
  (if-not h/live?
    (is true "skipped — SHIFTLEFTER_LIVE_WEBDRIVER not set")
    (do
      (registry/clear-registry!)
      (require 'shiftlefter.stepdefs.browser :reload)
      (try
        (intent-state/reload-intents! intents-dir)
        (h/run-against
         {:intents-dir intents-dir :pages [:catalog] :path "/catalog"}
         (fn [browser _intents _resolve]
           (let [ctx (cap/assoc-capability {} :web browser :ephemeral :alice)
                 result (invoke-registered-step
                         ":alice captures Dashboard.featured[1].title matching /(?<widgetName>Widget .+)/"
                         ctx)]
             (testing "a named group captured off the live DOM lands in the data plane"
               (is (= "Widget A"
                      (get-in result [bindings/bindings-key :widgetName]))))
             (testing "a live no-match is the structured capture failure, locator stamped"
               (let [e (try (binding [browser-steps/*retry-timeout-ms* 250]
                              (invoke-registered-step
                               ":alice captures Dashboard.featured[1].title matching /(?<code>\\d{6})/"
                               ctx))
                            (catch clojure.lang.ExceptionInfo e e))
                     data (ex-data e)]
                 (is (= :bindings/capture-failure (:type data)))
                 (is (= "Widget A" (:text data))
                     "the error carries the text actually seen on the page")
                 (is (= "Dashboard.featured[1].title" (:locator data))))))))
        (finally
          (intent-state/clear-intents!)
          (registry/clear-registry!))))))
