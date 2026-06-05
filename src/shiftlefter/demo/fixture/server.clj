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

(defn- find-free-port
  "Find an available port by binding to port 0 and reading the assigned port."
  []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn start-server
  "Start an http-kit server on an auto-assigned port with the given handler.
   Returns {:server <stop-fn> :port <assigned-port>}."
  [handler]
  (let [port (find-free-port)
        server (http/run-server handler {:port port})]
    {:server server
     :port port}))

(defn stop-server
  "Stop an http-kit server. Takes the stop function from start-server."
  [server-fn]
  (when server-fn
    (server-fn :timeout 100)))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defmacro with-fixture-server
  "Start a configured fixture server, run test-fn, stop server.

   config — Map with :users, :pages, :behaviors (all optional for now)
   test-fn — Function taking {:base-url \"http://localhost:PORT\"}

   Example:
   ```clojure
   (with-fixture-server
     {:users {\"alice\" \"secret\"}}
     (fn [{:keys [base-url]}]
       (is (str/starts-with? base-url \"http://localhost:\"))))
   ```

   The server starts on port 0 (auto-assigned) to avoid conflicts.
   Server is guaranteed to stop even if test-fn throws."
  [config test-fn]
  `(let [handler# (build-handler ~config)
         {:keys [~'server ~'port]} (start-server handler#)
         base-url# (str "http://localhost:" ~'port)]
     (try
       (~test-fn {:base-url base-url#
                  :port ~'port
                  :config ~config})
       (finally
         (stop-server ~'server)))))
