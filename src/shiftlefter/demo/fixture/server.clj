(ns shiftlefter.demo.fixture.server
  "Test fixture server harness for ShiftLefter browser tests.

   Provides a configurable in-process HTTP server where each test declares
   what it needs via a config map. The server starts on port 0 (auto-assign)
   and stops automatically after the test.

   ## Usage

   ```clojure
   (with-fixture-server
     {:users {\"alice\" \"secret1\" \"bob\" \"secret2\"}
      :pages [:login :dashboard]
      :behaviors {:login-delay-ms 2000}}

     (fn [{:keys [base-url]}]
       ;; Test code here, browser navigates to base-url
       ...))
   ```

   ## Config Schema

   - :users — map of username->password for auth
   - :pages — vector of page keywords to include (from registry)
   - :behaviors — map of behavioral tweaks (delays, failures, etc.)

   See `shiftlefter.demo.fixture.pages` for page registry."
  (:require [org.httpkit.server :as http]
            [shiftlefter.demo.fixture.handler :as handler]))

;; -----------------------------------------------------------------------------
;; Handler Building
;; -----------------------------------------------------------------------------

(defn build-handler
  "Build a Ring handler from fixture config.
   Delegates to handler/build-handler for route composition."
  [config]
  (handler/build-handler config))

;; -----------------------------------------------------------------------------
;; Server Lifecycle
;; -----------------------------------------------------------------------------

(defn start-server
  "Start an http-kit server on an auto-assigned port with the given handler.
   Returns {:server <server-object> :port <assigned-port>}.

   Binds 127.0.0.1 SPECIFICALLY, port 0 DIRECTLY — both halves matter
   (sl-7hb1, the fixture-family suite flake):

   - A wildcard 0.0.0.0 bind with SO_REUSEADDR silently COEXISTS with an
     existing 127.0.0.1-specific listener on the same port (macOS), and
     `:port 0` can be assigned exactly such a port. Loopback connections
     then route to the most-specific listener — a live ChromeDriver
     answered our tests' requests with WebDriver 404s (demonstrated
     2026-07-24: chromedriver held 127.0.0.1:61505, run-server bound
     0.0.0.0:61505 without error, every request reached chromedriver).
     A specific 127.0.0.1 bind cannot be assigned an occupied port and
     fails EADDRINUSE loudly if given one.
   - Direct :port 0 (modern http-kit return value + `server-port`) instead
     of the old probe-close-rebind find-free-port, whose window let the OS
     hand the probed port to anyone else."
  [handler]
  (let [server (http/run-server handler {:ip "127.0.0.1"
                                         :port 0
                                         :legacy-return-value? false})]
    {:server server
     :port (http/server-port server)}))

(defn stop-server
  "Stop an http-kit server. Takes the server object from start-server."
  [server]
  (when server
    (http/server-stop! server {:timeout 100})))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defmacro with-fixture-server
  "Start a configured fixture server, run test-fn, stop server.

   config — Map with :users, :pages, :behaviors (all optional for now)
   test-fn — Function taking {:base-url \"http://127.0.0.1:PORT\"}

   Example:
   ```clojure
   (with-fixture-server
     {:users {\"alice\" \"secret\"}}
     (fn [{:keys [base-url]}]
       (is (str/starts-with? base-url \"http://127.0.0.1:\"))))
   ```

   The server binds 127.0.0.1 on an auto-assigned port; base-url dials
   127.0.0.1 — not localhost — so the client can never resolve to an
   address family / listener the server didn't bind (sl-7hb1).
   Server is guaranteed to stop even if test-fn throws."
  [config test-fn]
  `(let [handler# (build-handler ~config)
         {:keys [~'server ~'port]} (start-server handler#)
         base-url# (str "http://127.0.0.1:" ~'port)]
     (try
       (~test-fn {:base-url base-url#
                  :port ~'port
                  :config ~config})
       (finally
         (stop-server ~'server)))))
