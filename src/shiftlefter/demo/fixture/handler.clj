(ns shiftlefter.demo.fixture.handler
  "Config-driven Ring handler composition for test fixture server.

   Builds a Ring handler from fixture config by:
   1. Creating session middleware (atom-based, keyed by cookie)
   2. Composing routes from the page registry based on `:pages` config
   3. Injecting auth logic from `:users` config
   4. Applying behaviors from `:behaviors` config

   ## Usage

   ```clojure
   (build-handler {:users {\"alice\" \"secret\"}       ; legacy flat
                   :pages [:login :dashboard]
                   :behaviors {:login-delay-ms 100}})

   (build-handler {:users {\"alice\" {:password \"secret\"
                                      :phone \"+15551230001\"
                                      :email \"alice@example.com\"}}
                   :pages [:login :reset-password]
                   :sms (mock/make-mock-sms)
                   :sms-from \"+15550000000\"})
   ```

   Users may be declared as either a plain password string (legacy) or a
   map with `:password`, `:phone`, `:email`. Auth reads whichever shape
   is present. `:sms` takes any ISMS-implementing record; defaults to a
   fresh MockSMS if absent."
  (:require [clojure.string :as str]
            [shiftlefter.demo.fixture.pages :as pages]
            [shiftlefter.sms.mock :as sms-mock]))

;; -----------------------------------------------------------------------------
;; Session Management
;; -----------------------------------------------------------------------------

(def ^:private session-cookie-name "fixture-session")

(defn- generate-session-id
  "Generate a unique session ID."
  []
  (str (java.util.UUID/randomUUID)))

(defn- get-session-id
  "Extract session ID from request cookies."
  [request]
  (some-> request
          :headers
          (get "cookie")
          (str/split #";\s*")
          (->> (some #(when (str/starts-with? % (str session-cookie-name "="))
                        (subs % (inc (count session-cookie-name))))))))

(defn- set-session-cookie
  "Add Set-Cookie header for session ID."
  [response session-id]
  (update response :headers assoc
          "Set-Cookie" (str session-cookie-name "=" session-id "; Path=/; HttpOnly")))

(defn- clear-session-cookie
  "Add Set-Cookie header to clear the session."
  [response]
  (update response :headers assoc
          "Set-Cookie" (str session-cookie-name "=; Path=/; HttpOnly; Max-Age=0")))

(defn wrap-session
  "Session middleware using an atom for storage.

   Injects :session-atom and :session-id into the request.
   Sessions are stored as {session-id {:user \"alice\" ...}}."
  [handler session-atom]
  (fn [request]
    (let [session-id (or (get-session-id request) (generate-session-id))
          request (assoc request
                         :session-atom session-atom
                         :session-id session-id
                         :session (get @session-atom session-id {}))]
      (handler request))))

;; -----------------------------------------------------------------------------
;; Auth Helpers (for pages to use)
;; -----------------------------------------------------------------------------

(defn- user-password
  "Extract the expected password for a user entry. Supports both the
   legacy flat format (`\"secret\"`) and the structured map format
   (`{:password \"secret\" :phone ... :email ...}`)."
  [entry]
  (cond
    (string? entry) entry
    (map? entry)    (:password entry)
    :else           nil))

(defn authenticate!
  "Authenticate a user and store in session. Returns true if valid.
   Users is a map of username->entry from config, where entry is either
   a password string (legacy) or a map with `:password`."
  [session-atom session-id users username password]
  (let [expected (user-password (get users username))]
    (when (and username password expected (= expected password))
      (swap! session-atom assoc session-id {:user username})
      true)))

(defn find-user-by-email
  "Find a user entry by its `:email` field. Returns `[username entry]` or
   nil. Only structured user entries (maps) can be matched — flat string
   entries have no email."
  [users email]
  (some (fn [[username entry]]
          (when (and (map? entry) (= email (:email entry)))
            [username entry]))
        users))

(defn current-user
  "Get the current user from session, or nil if not authenticated."
  [request]
  (-> request :session :user))

(defn logout!
  "Clear the user's session."
  [session-atom session-id]
  (swap! session-atom dissoc session-id))

;; -----------------------------------------------------------------------------
;; Route Matching
;; -----------------------------------------------------------------------------

(defn- method-matches?
  "Check if request method matches route method."
  [request-method route-method]
  (= (str/upper-case (name request-method))
     (str/upper-case route-method)))

(defn- path-matches?
  "Check if request path matches route path.
   Simple exact match for now."
  [request-path route-path]
  (= request-path route-path))

(defn- find-route
  "Find matching route from compiled routes.
   Returns [page-key handler-key] or nil."
  [request routes]
  (let [method (name (:request-method request))
        path (:uri request)]
    (some (fn [[route-method route-path page-key handler-key]]
            (when (and (method-matches? method route-method)
                       (path-matches? path route-path))
              [page-key handler-key]))
          routes)))

;; -----------------------------------------------------------------------------
;; Handler Building
;; -----------------------------------------------------------------------------

(defn- compile-routes
  "Compile routes from page config into a flat list.
   Each entry: [method path page-key handler-key]"
  [page-keys]
  (mapcat (fn [page-key]
            (for [[method path handler-key] (pages/page-routes page-key)]
              [method path page-key handler-key]))
          page-keys))

(defn- not-found-handler
  "Default 404 handler."
  [_request]
  {:status 404
   :headers {"Content-Type" "text/html"}
   :body "<html><body><h1>404 Not Found</h1></body></html>"})

(defn build-handler
  "Build a Ring handler from fixture config.

   Config keys:
   - :users — map of username->entry (string password or map) for auth
   - :pages — vector of page keywords to include
   - :behaviors — map of behavioral tweaks
   - :sms — an ISMS-implementing record (defaults to a fresh MockSMS)
   - :sms-from — default sender phone for SMS (optional)

   Page handlers are invoked with a 5th `ctx` argument — a map with the
   keys `:users :behaviors :sms :sms-from`. Existing pages that don't
   need `ctx` may ignore it.

   Returns a Ring handler function."
  [{:keys [users pages behaviors sms sms-from] :as _config}]
  (let [session-atom (atom {})
        routes       (compile-routes (or pages []))
        users-map    (or users {})
        behaviors    (or behaviors {})
        sms          (or sms (sms-mock/make-mock-sms))
        ctx          {:users users-map :behaviors behaviors
                      :sms sms :sms-from sms-from}]
    (wrap-session
     (fn [request]
       (if-let [[page-key handler-key] (find-route request routes)]
         (let [handler (pages/page-handler page-key handler-key)]
           (if handler
             (handler request session-atom users-map behaviors ctx)
             (not-found-handler request)))
         (not-found-handler request)))
     session-atom)))

;; -----------------------------------------------------------------------------
;; Response Helpers (for pages to use)
;; -----------------------------------------------------------------------------

(defn html-response
  "Create an HTML response."
  [status body]
  {:status status
   :headers {"Content-Type" "text/html"}
   :body body})

(defn redirect
  "Create a redirect response."
  [location]
  {:status 302
   :headers {"Location" location}
   :body ""})

(defn redirect-with-session
  "Create a redirect response that sets a session cookie."
  [location session-id]
  (-> (redirect location)
      (set-session-cookie session-id)))

(defn redirect-clearing-session
  "Create a redirect response that clears the session cookie."
  [location]
  (-> (redirect location)
      clear-session-cookie))
