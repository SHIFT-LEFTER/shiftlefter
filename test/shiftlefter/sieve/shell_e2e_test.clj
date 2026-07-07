(ns shiftlefter.sieve.shell-e2e-test
  "Gated live-browser proof for the SIEVE Toddler walking skeleton
   (sl-toddler-shell-adaptation-o4x), secondary to the headless round-trip in
   shell-test / server-test. It exercises only the two genuinely browser-bound
   pieces the fixture path cannot: the impure live capture + screenshot, and the
   shell rendering candidate overlays in a real Chrome. It does NOT gate CI.

   Run:  SHIFTLEFTER_LIVE_WEBDRIVER=1 ./bin/kaocha e2e   (needs ChromeDriver)"
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [etaoin.api :as eta]
            [shiftlefter.adapters.etaoin :as eta-adapter]
            [shiftlefter.demo.fixture.handler :as fh]
            [shiftlefter.demo.fixture.server :as srv]
            ;; Side-effect: registers the :login fixture page.
            [shiftlefter.demo.fixture.login]
            [shiftlefter.sieve.server :as sieve-server]
            [shiftlefter.sieve.web :as web]))

(def ^:private live?
  (some? (System/getenv "SHIFTLEFTER_LIVE_WEBDRIVER")))

(def ^:private bridge-port 3344)

(defn- headless-driver []
  (:etaoin-driver (:ok (eta-adapter/create-browser {:headless true}))))

(deftest ^:e2e live-capture-produces-real-evidence
  ;; Proves the impure path the headless fixture replays: a real browser on a
  ;; deterministic fixture page yields positioned candidates and a PNG.
  (if live?
    (let [{:keys [server port]} (srv/start-server (fh/build-handler {:pages [:login]}))
          cap (:ok (eta-adapter/create-browser {:headless true}))
          driver (:etaoin-driver cap)]
      (try
        (eta/go driver (str "http://localhost:" port "/login"))
        (let [{:keys [analysis]} (web/capture-and-analyze-web {:driver driver})]
          (testing "live capture + deterministic analysis yields positioned candidates"
            (is (pos? (count (:candidates analysis))))
            (is (some #(get-in % [:payload :rect]) (:candidates analysis))
                "at least one candidate carries page geometry"))
          (testing "screenshot of the live page is a non-empty PNG"
            (let [png (sieve-server/take-screenshot driver)]
              (is (bytes? png))
              (is (pos? (count png))))))
        (finally
          (eta-adapter/close-browser cap)
          (srv/stop-server server))))
    (is true "skipped - no live webdriver")))

(deftest ^:e2e live-shell-renders-overlays
  ;; Proves the visual layer: the bundled shell, served by the bridge, renders
  ;; SVG candidate overlays over the captured screenshot after one Sieve click.
  ;; Two browsers: the bridge's target (on the fixture page) + a shell viewer.
  (if live?
    (let [{:keys [server port]} (srv/start-server (fh/build-handler {:pages [:login]}))
          tmp (str (fs/create-temp-dir))
          target (headless-driver)
          env (sieve-server/start! {:port bridge-port :driver target :store-root tmp})
          viewer-cap (:ok (eta-adapter/create-browser {:headless true}))
          viewer (:etaoin-driver viewer-cap)]
      (try
        (eta/go target (str "http://localhost:" port "/login"))
        (eta/go viewer (str "http://localhost:" bridge-port "/"))
        (eta/wait-visible viewer {:data-testid "btn-sieve"})
        (eta/click viewer {:data-testid "btn-sieve"})
        (eta/wait-predicate
          #(pos? (count (eta/query-all viewer {:css "#overlay-svg rect"})))
          {:timeout 20 :interval 0.5})
        (testing "candidate overlays render over the live screenshot"
          (is (pos? (count (eta/query-all viewer {:css "#overlay-svg rect"}))))
          (is (seq (eta/get-element-attr viewer {:data-testid "screenshot-img"} :src))))
        (finally
          (sieve-server/stop! env)              ;; also quits the target driver
          (eta-adapter/close-browser viewer-cap)
          (srv/stop-server server))))
    (is true "skipped - no live webdriver")))
