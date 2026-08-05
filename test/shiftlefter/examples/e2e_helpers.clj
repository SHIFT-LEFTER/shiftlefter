(ns shiftlefter.examples.e2e-helpers
  "Shared harness for the nested-addressing example validators (sl-1ps).

   These are INTERNAL validators, not patterns to copy: a real ShiftLefter
   author never writes resolution code — the framework resolves intent
   references inside ordinary Gherkin steps (see the examples' .feature files).
   This harness exists only so the validators can (a) inspect [*] fan-out as a
   raw sequence and (b) assert the precise `:errors` contract that a Gherkin
   `Then` can't surface.

   Each validator stands up the bundled fixture server, drives a REAL browser
   via the production Etaoin adapter, loads that example's own intents, and
   exercises `browser.intent/resolve-target` against the live DOM. Gated on
   SHIFTLEFTER_LIVE_WEBDRIVER (like integration/browser-smoke-test); skipped by
   default, opt in with the env var + a running ChromeDriver."
  (:require [shiftlefter.adapters.etaoin :as eta-adapter]
            [shiftlefter.adapters.playwright :as pw-adapter]
            [shiftlefter.browser.intent :as bi]
            [shiftlefter.browser.protocol :as bp]
            [shiftlefter.demo.fixture.handler :as fh]
            [shiftlefter.demo.fixture.server :as srv]
            [shiftlefter.intent.loader :as loader]))

(def live?
  "True when live-browser (WebDriver/etaoin) E2E tests should run."
  (some? (System/getenv "SHIFTLEFTER_LIVE_WEBDRIVER")))

(def live-playwright?
  "True when live-Playwright E2E tests should run (sl-tnx). Same gate as the
   adapter tests: needs Playwright on the classpath and its browsers cached
   (first use auto-fetches)."
  (= "1" (System/getenv "SHIFTLEFTER_LIVE_PLAYWRIGHT")))

(defn run-against*
  "Full-context variant of `run-against`: load intents from `:intents-dir`,
   stand up the fixture server with `:pages`, open a real (headless) browser to
   `:path`, then call `(f {:browser :intents :resolve :base-url})` — `:base-url`
   is the fixture server's dynamic `http://localhost:<port>`, for validators
   that exercise named-location resolution (sl-3jr4). Cleans up the browser and
   server even on throw.

   `:adapter` selects the backend — :etaoin (default) or :playwright — and
   `:pruning` (Playwright-only meaning) passes the sl-tnx strategy knob
   through, so the same validator bodies exercise every backend × strategy
   combination against the same live DOM.

   Throws if the intents fail to load (a broken glossary is a test failure, not
   a silent skip)."
  [{:keys [intents-dir pages path adapter pruning] :or {adapter :etaoin}} f]
  (let [load-res (loader/load-all-intents intents-dir)]
    (when (:errors load-res)
      (throw (ex-info "intents failed to load" {:errors (:errors load-res)})))
    (let [intents (:ok load-res)
          {:keys [server port]} (srv/start-server (fh/build-handler {:pages pages}))
          [create-fn close-fn] (case adapter
                                 :etaoin [eta-adapter/create-browser
                                          eta-adapter/close-browser]
                                 :playwright [pw-adapter/create-browser
                                              pw-adapter/close-browser])
          cap (:ok (create-fn (cond-> {:headless true}
                                pruning (assoc :pruning pruning))))
          browser (:browser cap)]
      (try
        (bp/open-to! browser (str "http://localhost:" port path))
        (f {:browser browser
            :intents intents
            :resolve (fn [address] (bi/resolve-target browser intents address :web))
            :base-url (str "http://localhost:" port)})
        (finally
          (close-fn cap)
          (srv/stop-server server))))))

(defn run-against
  "Load intents from `:intents-dir`, stand up the fixture server with `:pages`,
   open a real (headless) browser to `:path`, then call
   `(f browser intents resolve)` where `resolve` is `(fn [address] target)` for
   the `:web` interface. Cleans up the browser and server even on throw.

   Throws if the intents fail to load (a broken glossary is a test failure, not
   a silent skip)."
  [opts f]
  (run-against* opts (fn [{:keys [browser intents resolve]}]
                       (f browser intents resolve))))

(defn text-of
  "Text of a single resolved `{:el}`/`{:q}` target."
  [browser target]
  (bp/get-text browser target))

(defn texts-of
  "Text of each target in a `[*]` fan-out vector."
  [browser targets]
  (mapv #(text-of browser %) targets))

(defn error-type
  "The first error `:type` of a resolution failure value, or nil if not an error."
  [target]
  (when (and (map? target) (:errors target))
    (-> target :errors first :type)))

(defn text-or-error
  "Text of a single resolved target, or `{:error <type>}` when resolution
   failed. Lets a deftest assert the expected text and get a CLEAN failure
   (Expected \"Alice\", Actual {:error :intent/ambiguous-element}) instead of a
   thrown exception when resolution currently errors — used by the sl-h7h
   acceptance assertions that are red until the resolver fix lands."
  [browser target]
  (if-let [t (error-type target)]
    {:error t}
    (text-of browser target)))
