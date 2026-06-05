(ns shiftlefter.sieve.server
  "HTTP bridge serving sieve output and screenshots to external UIs.

   Core operations (run-sieve, take-screenshot, navigate, get-status) are
   transport-agnostic — when this migrates to MCP, only the dispatch layer
   changes.

   ## REPL usage

   ```clojure
   (require '[shiftlefter.sieve.server :as sieve-server])
   (def env (sieve-server/start! {:port 3333}))
   ;; UI fetches from http://localhost:3333/sieve, /screenshot, etc.
   (sieve-server/navigate! env \"https://example.com\")
   (sieve-server/stop! env)
   ```"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [etaoin.api :as eta]
            [org.httpkit.server :as http])
  (:import [java.util Base64]))

;; =============================================================================
;; Core Operations (transport-agnostic)
;; =============================================================================

(def ^:private sieve-js-path "sieve.js")

(defn run-sieve
  "Inject sieve.js into the current page and return the inventory map.
   Enriches the result with ambient browser state (cookies, tabs) that
   can only be obtained from the WebDriver layer.

   NOTE: This function calls Etaoin directly (not IBrowser protocol).
   The entire sieve server is Etaoin-coupled — see leftglove-xl7 for the
   planned port to IBrowser, which would give us Playwright for free."
  [driver]
  (let [sieve-src (slurp (io/resource sieve-js-path))
        inventory (eta/js-execute driver (str "return " sieve-src))
        ;; Ambient browser state from WebDriver (includes HttpOnly cookies)
        cookie-keys (try (->> (eta/get-cookies driver)
                              (map :name)
                              (distinct)
                              (vec))
                         (catch Exception _ []))
        tab-count   (try (count (eta/get-window-handles driver))
                         (catch Exception _ 1))]
    (assoc inventory
           :cookies cookie-keys
           :tabs tab-count)))

(defn take-screenshot
  "Take a PNG screenshot of the current page. Returns a byte array."
  [driver]
  (let [resp (eta/execute {:driver driver
                           :method :get
                           :path   [:session (:session driver) :screenshot]})
        b64str (-> resp :value)]
    (when (not (seq b64str))
      (throw (ex-info "Empty screenshot" {:type :sieve/screenshot-failed})))
    (.decode (Base64/getDecoder) ^String b64str)))

(defn navigate
  "Navigate the browser to a URL. Returns status map."
  [driver url]
  (eta/go driver url)
  {:url   (eta/get-url driver)
   :title (eta/get-title driver)})

(defn get-status
  "Return current browser state."
  [driver]
  {:ready true
   :url   (eta/get-url driver)
   :title (eta/get-title driver)})

;; =============================================================================
;; HTTP Layer
;; =============================================================================

(def ^:private cors-headers
  {"Access-Control-Allow-Origin"  "*"
   "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
   "Access-Control-Allow-Headers" "Content-Type"})

(defn- json-response [status body]
  {:status  status
   :headers (merge cors-headers {"Content-Type" "application/json"})
   :body    (json/generate-string body)})

(defn- png-response [bytes]
  {:status  200
   :headers (merge cors-headers {"Content-Type" "image/png"})
   :body    (java.io.ByteArrayInputStream. bytes)})

(defn- error-response [status message]
  (json-response status {:error message}))

(defn- parse-json-body [request]
  (when-let [body (:body request)]
    (try
      (json/parse-stream (io/reader body) true)
      (catch Exception _
        nil))))

(defn- handle-sieve [driver _request]
  (try
    (json-response 200 (run-sieve driver))
    (catch Exception e
      (error-response 500 (.getMessage e)))))

(defn- handle-screenshot [driver _request]
  (try
    (png-response (take-screenshot driver))
    (catch Exception e
      (error-response 500 (.getMessage e)))))

(defn- handle-navigate [driver request]
  (let [body (parse-json-body request)
        url  (:url body)]
    (if (seq url)
      (try
        (json-response 200 (navigate driver url))
        (catch Exception e
          (error-response 500 (.getMessage e))))
      (error-response 400 "Missing 'url' in request body"))))

(defn- handle-status [driver _request]
  (try
    (json-response 200 (get-status driver))
    (catch Exception e
      (error-response 500 (.getMessage e)))))

(defn- make-handler
  "Build the HTTP handler. Closes over the driver reference."
  [driver]
  (fn [request]
    (let [method (:request-method request)
          path   (:uri request)]
      (cond
        (= method :options)
        {:status 200 :headers cors-headers :body ""}

        (and (= method :post) (= path "/sieve"))
        (handle-sieve driver request)

        (and (= method :get) (= path "/screenshot"))
        (handle-screenshot driver request)

        (and (= method :post) (= path "/navigate"))
        (handle-navigate driver request)

        (and (= method :get) (= path "/status"))
        (handle-status driver request)

        :else
        (error-response 404 "Not found")))))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn start!
  "Start the sieve server. Provisions a headed Chrome browser and starts
   an HTTP server.

   Options:
     :port    — HTTP port (default 3333)
     :driver  — existing Etaoin driver to reuse (skips browser provisioning)

   Returns an env map: {:server stop-fn, :driver driver, :port port}"
  [{:keys [port driver] :or {port 3333}}]
  (let [driver  (or driver
                    (eta/chrome {:path-driver
                                 (or (some-> (io/file (str (System/getProperty "user.home")
                                                          "/.shiftlefter/config.edn"))
                                             slurp
                                             read-string
                                             :chromedriver-path)
                                     "chromedriver")}))
        handler (make-handler driver)
        server  (http/run-server handler {:port port})]
    (println (str "[sieve] Server started on http://localhost:" port))
    (println "[sieve] Endpoints: POST /sieve, GET /screenshot, POST /navigate, GET /status")
    {:server server
     :driver driver
     :port   port}))

(defn stop!
  "Stop the sieve server and close the browser."
  [{:keys [server driver]}]
  (when server
    (server :timeout 100)
    (println "[sieve] Server stopped."))
  (when driver
    (try
      (eta/quit driver)
      (println "[sieve] Browser closed.")
      (catch Exception _ nil))))

(defn navigate!
  "Convenience: navigate the browser from the REPL."
  [env url]
  (navigate (:driver env) url))
