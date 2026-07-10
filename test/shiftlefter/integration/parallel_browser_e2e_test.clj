(ns shiftlefter.integration.parallel-browser-e2e-test
  "sl-q9wp acceptance 7: live parallel browser smoke (^:e2e, gated).

   Two browser scenarios through the real execute-suite at :max-parallel 2 —
   real eager provisioning (production :etaoin adapter, headless Chrome per
   scenario), real per-scenario cleanup, the bundled fixture server for a
   deterministic page. The assertion is CONCURRENCY, not speed: both Chrome
   sessions must be live at the same moment at some point (smoke, not
   benchmark — no upper-bound timing anywhere).

   Runs only under SHIFTLEFTER_LIVE_WEBDRIVER=1 ./bin/kaocha e2e with a
   running ChromeDriver; self-skips green otherwise."
  (:require [clojure.test :refer [deftest is]]
            [shiftlefter.browser.locators :as locators]
            [shiftlefter.browser.protocol :as bp]
            [shiftlefter.demo.fixture.handler :as fh]
            [shiftlefter.demo.fixture.server :as srv]
            [shiftlefter.stepengine.exec :as exec]
            ;; Side-effect: registers the :catalog fixture page.
            [shiftlefter.demo.fixture.catalog]))

(def ^:private live?
  (some? (System/getenv "SHIFTLEFTER_LIVE_WEBDRIVER")))

(defn- browser-plan
  "A plan whose single step drives THIS scenario's engine-provisioned real
   browser against the fixture page, then records the window during which
   the session was demonstrably live. The sleep widens the window so the two
   scenarios reliably coexist at :max-parallel 2; it sets no upper bound."
  [windows tag url]
  {:plan/id (java.util.UUID/randomUUID)
   :plan/pickle {:pickle/id (java.util.UUID/randomUUID)
                 :pickle/name (str "parallel smoke " (name tag))}
   :plan/runnable? true
   :plan/steps
   [{:status :matched
     :step {:step/id (java.util.UUID/randomUUID)
            :step/text (str (name tag) " visits the fixture page")}
     :binding {:fn (fn [scenario-ctx]
                     ;; A ctx-arity step fn receives the scenario ctx itself;
                     ;; the engine-provisioned capability lives under its
                     ;; (interface, subject) key.
                     (let [impl (get-in scenario-ctx [:cap/web.alice :impl])
                           start (System/nanoTime)]
                       (bp/open-to! impl url)
                       (is (pos? (bp/element-count
                                  impl (locators/resolve-locator {:css "body"})))
                           (str (name tag) " drives a live session"))
                       (Thread/sleep 1500)
                       (swap! windows assoc tag
                              {:start start :end (System/nanoTime)})
                       nil))
               :arity 1
               :captures []
               :stepdef/id (str "parallel-smoke-" (name tag))
               :pattern-src "parallel smoke"
               :svo {:interface :web :subject :alice}}}]})

(deftest ^:e2e parallel-browser-sessions-concurrent
  (if-not live?
    (is true "skipped - no live webdriver")
    (let [{:keys [server port]} (srv/start-server
                                 (fh/build-handler {:pages [:catalog]}))
          url (str "http://localhost:" port "/catalog")
          windows (atom {})]
      (try
        (let [plans [(browser-plan windows :a url)
                     (browser-plan windows :b url)]
              result (exec/execute-suite
                      plans
                      {:max-parallel 2
                       :provisioning :eager
                       :interfaces {:web {:type :web
                                          :adapter :etaoin
                                          :config {:headless true}}}})]
          (is (= :passed (:status result)) (pr-str (:counts result)))
          (let [{:keys [a b]} @windows]
            (is (and a b) "both scenarios recorded their live window")
            (is (and (< (:start a) (:end b)) (< (:start b) (:end a)))
                "both Chrome sessions were live at the same moment")))
        (finally
          (srv/stop-server server))))))
