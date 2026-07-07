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
            [clojure.string :as str]
            [etaoin.api :as eta]
            [org.httpkit.server :as http]
            [shiftlefter.sieve.contract :as contract]
            [shiftlefter.sieve.shell :as shell]
            [shiftlefter.sieve.store :as store]
            [shiftlefter.sieve.web :as web])
  (:import [java.util Base64]))

;; =============================================================================
;; Core Operations (transport-agnostic)
;; =============================================================================

(defn run-sieve
  "Capture web evidence, analyze it, and return the current bundled inventory.

   NOTE: This function calls Etaoin directly (not IBrowser protocol).
   The entire sieve server is Etaoin-coupled — see leftglove-xl7 for the
   planned port to IBrowser, which would give us Playwright for free."
  ([driver]
   (run-sieve driver {}))
  ([driver {:keys [projection store-root] :as opts}]
   (let [{:keys [evidence analysis]} (web/capture-and-analyze-web
                                       (assoc opts :driver driver
                                                   :projection projection))
         evidence (if store-root
                    (store/save-evidence-snapshot! store-root evidence)
                    evidence)
         analysis (if store-root
                    (store/save-analysis-result! store-root analysis)
                    analysis)
         inventory (or (get-in evidence [:payload :inventory]) {})]
     (assoc inventory
            :sieve {:evidence-snapshot (contract/evidence-ref evidence)
                    :analysis-result (contract/analysis-ref analysis)
                    :candidate-count (count (:candidates analysis))
                    :warnings (:warnings analysis)
                    :stored? (boolean store-root)}))))

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

(defn write-proposal
  "Load the referenced Analysis Result, build a Proposal Result from the UI's
   classify/rename/decide claims, and persist it under the store root.

   Writes only to the SIEVE store (Apply Proposal is out of scope, so no glossary
   or intent files are touched). Returns the persisted Proposal Result."
  [store-root {:keys [analysis-id claims page-url session-id]}]
  (let [analysis (store/load-analysis-result store-root analysis-id)
        proposal (shell/build-proposal-result analysis (vec claims)
                                               {:page-url page-url
                                                :session-id session-id})]
    (store/save-proposal-result! store-root proposal)))

(defn reconcile-observations
  "Load two referenced Analysis Results and build a reconciled Proposal Result
   carrying the two-observation diff plus any human refinements of the shared
   vocabulary. Persists to the SIEVE store (Apply Proposal stays out of scope)."
  [store-root {:keys [analysis-a-id analysis-b-id claims session-id]}]
  (let [analysis-a (store/load-analysis-result store-root analysis-a-id)
        analysis-b (store/load-analysis-result store-root analysis-b-id)
        proposal (shell/build-reconciled-proposal analysis-a analysis-b
                                                   (vec claims)
                                                   {:session-id session-id})]
    (store/save-proposal-result! store-root proposal)))

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

(defn- handle-sieve [driver opts _request]
  (try
    (json-response 200 (run-sieve driver opts))
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

(defn- handle-proposal [store-root request]
  (let [body        (parse-json-body request)
        analysis-id (:analysis-id body)]
    (cond
      (not store-root)
      (error-response 400 "Server has no store root configured for proposals")

      (not (seq analysis-id))
      (error-response 400 "Missing 'analysis-id' in request body")

      :else
      (try
        (let [proposal (write-proposal store-root
                                       {:analysis-id analysis-id
                                        :claims (:claims body)
                                        :page-url (:page-url body)
                                        :session-id (:session-id body)})]
          (json-response 200 {:proposal-id (:proposal/id proposal)
                              :selected (count (:selected proposal))
                              :rejected (count (:rejected proposal))
                              :unresolved (count (:unresolved proposal))
                              :status (name (:status proposal))}))
        (catch Exception e
          (error-response 500 (.getMessage e)))))))

(defn- reconciliation-counts [proposal]
  (let [diff (:reconciliation proposal)]
    (into {} (map (fn [k] [k (count (get diff k))]))
          [:retained :new :disappeared :changed])))

(defn- handle-reconcile [store-root request]
  (let [body (parse-json-body request)
        {:keys [analysis-a-id analysis-b-id]} body]
    (cond
      (not store-root)
      (error-response 400 "Server has no store root configured for reconcile")

      (not (and (seq analysis-a-id) (seq analysis-b-id)))
      (error-response 400 "Missing 'analysis-a-id' or 'analysis-b-id' in request body")

      :else
      (try
        (let [proposal (reconcile-observations
                         store-root
                         {:analysis-a-id analysis-a-id
                          :analysis-b-id analysis-b-id
                          :claims (:claims body)
                          :session-id (:session-id body)})]
          (json-response 200 {:proposal-id (:proposal/id proposal)
                              :reconciliation (:reconciliation proposal)
                              :counts (reconciliation-counts proposal)
                              :status (name (:status proposal))}))
        (catch Exception e
          (error-response 500 (.getMessage e)))))))

(def ^:private ui-content-types
  {"html" "text/html; charset=utf-8"
   "js"   "application/javascript; charset=utf-8"
   "css"  "text/css; charset=utf-8"})

(defn- handle-ui
  "Serve the bundled authoring shell from resources/sieve/ui/. GET / maps to
   index.html. Rejects path traversal; unknown paths fall through to 404."
  [uri]
  (let [rel (if (= uri "/") "index.html" (subs uri 1))]
    (when-let [resource (and (not (str/includes? rel ".."))
                             (io/resource (str "sieve/ui/" rel)))]
      (let [ext (last (str/split rel #"\."))]
        {:status  200
         :headers (merge cors-headers
                         {"Content-Type" (get ui-content-types ext "application/octet-stream")})
         :body    (slurp resource)}))))

(defn- make-handler
  "Build the HTTP handler. Closes over the driver reference."
  [driver opts]
  (fn [request]
    (let [method (:request-method request)
          path   (:uri request)]
      (cond
        (= method :options)
        {:status 200 :headers cors-headers :body ""}

        (and (= method :post) (= path "/sieve"))
        (handle-sieve driver opts request)

        (and (= method :get) (= path "/screenshot"))
        (handle-screenshot driver request)

        (and (= method :post) (= path "/navigate"))
        (handle-navigate driver request)

        (and (= method :get) (= path "/status"))
        (handle-status driver request)

        (and (= method :post) (= path "/proposal"))
        (handle-proposal (:store-root opts) request)

        (and (= method :post) (= path "/reconcile"))
        (handle-reconcile (:store-root opts) request)

        (= method :get)
        (or (handle-ui path)
            (error-response 404 "Not found"))

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
     :projection — accepted project projection reference for SIEVE analysis
     :store-root — SIEVE artifact store root (default store/default-root, so
                   /sieve persists analysis and /proposal can reference it)

   The authoring shell UI is served from this same server at http://localhost:<port>/.

   Returns an env map: {:server stop-fn, :driver driver, :port port}"
  [{:keys [port driver] :or {port 3333} :as opts}]
  (let [store-root (or (:store-root opts) store/default-root)
        driver  (or driver
                    (eta/chrome {:path-driver
                                 (or (some-> (io/file (str (System/getProperty "user.home")
                                                          "/.shiftlefter/config.edn"))
                                             slurp
                                             read-string
                                             :chromedriver-path)
                                     "chromedriver")}))
        handler (make-handler driver {:projection (:projection opts)
                                      :store-root store-root})
        server  (http/run-server handler {:port port})]
    (println (str "[sieve] Server started on http://localhost:" port))
    (println (str "[sieve] Authoring shell UI: http://localhost:" port "/"))
    (println "[sieve] Endpoints: POST /sieve, GET /screenshot, POST /navigate, GET /status, POST /proposal, POST /reconcile")
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
