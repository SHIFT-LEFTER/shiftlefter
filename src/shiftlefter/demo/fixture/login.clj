(ns shiftlefter.demo.fixture.login
  "Login page block for test fixture server.

   Provides login form and authentication flow. Register with `:login` in
   your test's `:pages` config.

   ## Routes

   - GET /login — Login form
   - POST /login — Authenticate and redirect

   ## Behaviors

   - `:login-delay-ms` — Delay before processing login (for retry testing)
   - `:fail-login-for` — Set of usernames that always fail

   ## Form Fields

   - `username` (id: \"username\")
   - `password` (id: \"password\")
   - Submit button (type: \"submit\")"
  (:require [clojure.string :as str]
            [shiftlefter.demo.fixture.pages :as pages]
            [shiftlefter.demo.fixture.handler :as handler]))

;; -----------------------------------------------------------------------------
;; HTML Templates
;; -----------------------------------------------------------------------------

(defn- login-form-html
  "HTML for the login form."
  [error-message]
  (str "<!DOCTYPE html>
<html>
<head><title>Login</title></head>
<body>
  <h1>Login</h1>"
       (when error-message
         (str "<p id=\"error\" style=\"color: red;\">" error-message "</p>"))
       "
  <form method=\"POST\" action=\"/login\">
    <div>
      <label for=\"username\">Username:</label>
      <input type=\"text\" id=\"username\" name=\"username\" required>
    </div>
    <div>
      <label for=\"password\">Password:</label>
      <input type=\"password\" id=\"password\" name=\"password\" required>
    </div>
    <div>
      <button type=\"submit\">Login</button>
    </div>
  </form>
</body>
</html>"))

;; -----------------------------------------------------------------------------
;; Form Parsing
;; -----------------------------------------------------------------------------

(defn- parse-form-body
  "Parse URL-encoded form body into a map."
  [request]
  (when-let [body (:body request)]
    (let [body-str (if (string? body)
                     body
                     (slurp body))]
      (->> (str/split body-str #"&")
           (map #(str/split % #"=" 2))
           (filter #(= 2 (count %)))
           (map (fn [[k v]] [(keyword k) (java.net.URLDecoder/decode v "UTF-8")]))
           (into {})))))

;; -----------------------------------------------------------------------------
;; Handlers
;; -----------------------------------------------------------------------------

(defn- get-login
  "Handler for GET /login"
  [_request _session-atom _users _behaviors & _ctx]
  (handler/html-response 200 (login-form-html nil)))

(defn- post-login
  "Handler for POST /login"
  [request session-atom users behaviors & _ctx]
  ;; Apply login delay if configured
  (when-let [delay-ms (:login-delay-ms behaviors)]
    (when (pos? delay-ms)
      (Thread/sleep delay-ms)))

  (let [{:keys [username password]} (parse-form-body request)
        session-id (:session-id request)
        fail-for (or (:fail-login-for behaviors) #{})]
    (cond
      ;; Check if user is in fail-for set
      (contains? fail-for username)
      (handler/html-response 200 (login-form-html "Login failed"))

      ;; Try to authenticate
      (handler/authenticate! session-atom session-id users username password)
      (handler/redirect-with-session "/dashboard" session-id)

      ;; Auth failed
      :else
      (handler/html-response 200 (login-form-html "Invalid username or password")))))

;; -----------------------------------------------------------------------------
;; Page Registration
;; -----------------------------------------------------------------------------

(pages/defpage :login
  {:routes [["GET" "/login" :get-login]
            ["POST" "/login" :post-login]]
   :handlers {:get-login get-login
              :post-login post-login}})
