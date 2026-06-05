(ns shiftlefter.demo.fixture.pages
  "Page registry for test fixture server.

   Pages are composable route handlers that can be included in a test
   server via the `:pages` config. Each page is a keyword that maps to
   a handler function.

   ## Adding a New Page

   1. Create a handler function that takes [request session-atom users behaviors]
   2. Register it with `defpage` or add to `page-registry`
   3. Include the keyword in your test's `:pages` config

   ## Example

   ```clojure
   ;; In pages.clj
   (defpage :my-page
     (fn [req session-atom users behaviors]
       {:status 200 :body \"My page\"}))

   ;; In test
   (with-fixture-server
     {:pages [:my-page]}
     (fn [{:keys [base-url]}] ...))
   ```")

;; -----------------------------------------------------------------------------
;; Page Registry
;; -----------------------------------------------------------------------------

(def page-registry
  "Map of page keywords to their route specs.

   Each entry is:
   {:routes [[method path handler-key] ...]
    :handlers {:handler-key (fn [req session-atom users behaviors] response)}}

   Pages are added incrementally in GP.004c, GP.004d, etc."
  (atom {}))

(defmacro defpage
  "Register a page with routes and handlers.

   Example:
   ```clojure
   (defpage :login
     {:routes [[\"GET\" \"/login\" :get-login]
               [\"POST\" \"/login\" :post-login]]
      :handlers {:get-login (fn [req session-atom users behaviors] ...)
                 :post-login (fn [req session-atom users behaviors] ...)}})
   ```"
  [page-key spec]
  `(swap! page-registry assoc ~page-key ~spec))

(defn get-page
  "Get a page spec from the registry."
  [page-key]
  (get @page-registry page-key))

(defn page-routes
  "Get all routes for a page."
  [page-key]
  (-> (get-page page-key) :routes))

(defn page-handler
  "Get a specific handler from a page."
  [page-key handler-key]
  (-> (get-page page-key) :handlers handler-key))
