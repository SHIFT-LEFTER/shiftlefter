(ns shiftlefter.integration.navigation-classification-e2e-test
  "The slow-SUT hard line, live (sl-od2v, Warden W4; kowi Ruling 3c): a
   server that ACCEPTS a connection and never answers is silence — the
   navigation must classify `:observation` (exit 4), never indict the app
   with exit 1. The 0.5.6 audit's nav-probe made a test, two altitudes:

   1. the CLASSIFICATION POINT — the real etaoin adapter, headless Chrome,
      a 3s page-load timeout on the driver, `classify-navigation` around
      `bp/open-to!` exactly as the navigation step wraps it;
   2. the RUNNER — a project whose interface config carries
      `:page-load-timeout-ms`, one builtin navigation step against the
      silent socket, `execute!` ⇒ exit 4 in seconds (Chrome's default
      page-load timeout is five minutes — that knob is what makes the
      verdict arrive in CI time).

   Plus two controls: a refused port classifies as before, and the
   accept-then-close-without-a-byte server is the KNOWN HOLE — Chrome
   RETURNS its own error page, the navigation succeeds, and no
   message-based signal exists; asserted as such so a Chrome that starts
   throwing ERR_EMPTY_RESPONSE shows up as a test change, not a surprise.

   Runs only under SHIFTLEFTER_LIVE_WEBDRIVER=1 ./bin/kaocha e2e (ChromeDriver
   resolved from ~/.shiftlefter/config.edn); self-skips green otherwise."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [etaoin.api :as eta]
            [shiftlefter.adapters.etaoin :as eta-adapter]
            [shiftlefter.browser.errors :as errors]
            [shiftlefter.browser.protocol :as bp]
            [shiftlefter.runner.core :as runner]
            [shiftlefter.stepengine.registry :as registry])
  (:import [java.net ServerSocket]))

(def ^:private live?
  (some? (System/getenv "SHIFTLEFTER_LIVE_WEBDRIVER")))

;; -----------------------------------------------------------------------------
;; The two servers from the probe
;; -----------------------------------------------------------------------------

(defn- silent-server
  "Accepts and never writes; each accepted socket is held for `hold-ms`."
  [hold-ms]
  (let [ss (ServerSocket. 0)
        stop (atom false)]
    (future
      (while (not @stop)
        (try (let [s (.accept ss)]
               (future (Thread/sleep hold-ms) (.close s)))
             (catch Exception _ nil))))
    {:socket ss :port (.getLocalPort ss) :stop! (fn [] (reset! stop true) (.close ss))}))

(defn- closer-server
  "Accepts and closes without a byte — Chromium's ERR_EMPTY_RESPONSE shape."
  []
  (let [ss (ServerSocket. 0)
        stop (atom false)]
    (future
      (while (not @stop)
        (try (.close (.accept ss)) (catch Exception _ nil))))
    {:socket ss :port (.getLocalPort ss) :stop! (fn [] (reset! stop true) (.close ss))}))

(defn- url-of [port] (str "http://127.0.0.1:" port "/"))

(defn- thrown-by [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

;; -----------------------------------------------------------------------------
;; 1. The classification point
;; -----------------------------------------------------------------------------

(deftest ^:e2e silent-server-classifies-observation-at-the-navigation-site
  (if-not live?
    (is true "skipped — SHIFTLEFTER_LIVE_WEBDRIVER not set")
    (let [silent (silent-server 60000)
          closer (closer-server)
          created (eta-adapter/create-browser {:headless true})
          {:keys [browser etaoin-driver]} (:ok created)]
      (is (:ok created) (str "real headless Chrome came up: " (pr-str (:error created))))
      (try
        (eta/set-page-load-timeout etaoin-driver 3)
        (testing "SILENT (accept, never answer): the driver's timeout answer
                  is classified :observation — was unclassified, exit 1"
          (let [url (url-of (:port silent))
                e (thrown-by #(errors/classify-navigation
                               (fn [] (bp/open-to! browser url)) url))]
            (is (some? e) "the navigation threw")
            (is (= :observation (:error/class (ex-data e))))
            (is (= "timeout" (:w3c-error (ex-data e))))
            (is (str/starts-with? (ex-message e) "SUT silent: navigation timed out for"))))
        (testing "REFUSED control: a closed port classifies as before"
          (let [url (url-of 59999)
                e (thrown-by #(errors/classify-navigation
                               (fn [] (bp/open-to! browser url)) url))]
            (is (some? e))
            (is (= :observation (:error/class (ex-data e))))
            (is (= "ERR_CONNECTION_REFUSED" (:net-error (ex-data e))))))
        (testing "CLOSER (accept, close without a byte): NONDETERMINISTIC in
                  Chrome — sometimes ERR_CONNECTION_RESET (classified, in the
                  closed set), sometimes Chrome RETURNS its own error page and
                  the navigation succeeds (the KNOWN HOLE, no message-based
                  signal; the ladder page records it). Either way, never an
                  unclassified throw."
          (let [url (url-of (:port closer))
                e (thrown-by #(errors/classify-navigation
                               (fn [] (bp/open-to! browser url)) url))]
            (if (nil? e)
              (is true "open-to! RETURNED — the hole")
              (do (is (= :observation (:error/class (ex-data e)))
                      "when Chrome does report it, the code is in the closed set")
                  (is (contains? errors/unreachable-net-errors (:net-error (ex-data e))))))))
        (finally
          (eta-adapter/close-browser (:ok created))
          ((:stop! silent))
          ((:stop! closer)))))))

;; -----------------------------------------------------------------------------
;; 2. The runner: :page-load-timeout-ms + the silent socket ⇒ exit 4 in seconds
;; -----------------------------------------------------------------------------

(defn- silent-project!
  "A one-scenario project: the builtin navigation step against `url`, the
   :web interface carrying a short page-load timeout."
  [dir url timeout-ms]
  (let [sl (fs/path dir "sl")]
    (fs/create-dirs (fs/path sl "steps"))
    (fs/create-dirs (fs/path sl "features"))
    (spit (str (fs/path sl "shiftlefter.edn"))
          (pr-str {:runner {:step-paths ["steps/"] :allow-pending? false}
                   :interfaces {:web {:type :web :adapter :etaoin
                                      :config {:headless true
                                               :page-load-timeout-ms timeout-ms}}}}))
    (spit (str (fs/path sl "features" "silent.feature"))
          (str "Feature: Silent SUT\n\n  Scenario: The server accepts and never answers\n"
               "    When :user opens the browser to '" url "'\n"))
    {:config-path (str (fs/path sl "shiftlefter.edn"))
     :feature (str (fs/path sl "features" "silent.feature"))
     :steps (str (fs/path sl "steps"))}))

(deftest ^:e2e silent-server-is-exit-4-through-the-runner-in-seconds
  (if-not live?
    (is true "skipped — SHIFTLEFTER_LIVE_WEBDRIVER not set")
    (let [silent (silent-server 60000)
          dir (str (fs/create-temp-dir {:prefix "sl-silent-sut-"}))]
      (registry/clear-registry!)
      (try
        (let [{:keys [config-path feature steps]} (silent-project! dir (url-of (:port silent)) 3000)
              out (java.io.StringWriter.)
              err (java.io.StringWriter.)
              t0 (System/nanoTime)
              result (binding [*out* out *err* err]
                       (runner/execute! {:paths [feature] :config-path config-path
                                         :step-paths [steps] :no-color true}))
              secs (/ (- (System/nanoTime) t0) 1e9)]
          (is (= 4 (:exit-code result)) (str "silence is unobserved, not a failure; stderr: " err))
          (is (= 1 (get-in result [:counts :unobserved])))
          (is (str/includes? (str err) "SUT silent: navigation timed out"))
          (is (< secs 60) (str "the configured page-load timeout made the verdict arrive in "
                               secs "s, not Chrome's five-minute default")))
        (finally
          (registry/clear-registry!)
          ((:stop! silent))
          (fs/delete-tree dir))))))
