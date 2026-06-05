(ns shiftlefter.demo.fixture.dashboard
  "Dashboard page block for test fixture server.

   Provides a protected dashboard and logout functionality. Register with
   `:dashboard` and `:logout` in your test's `:pages` config.

   ## Routes

   - GET /dashboard — Protected page showing current user
   - GET /logout — Clear session and redirect to login

   ## Authentication

   If not authenticated, redirects to /login."
  (:require [shiftlefter.demo.fixture.pages :as pages]
            [shiftlefter.demo.fixture.handler :as handler]))

;; -----------------------------------------------------------------------------
;; HTML Templates
;; -----------------------------------------------------------------------------

(defn- dashboard-html
  "HTML for the dashboard page."
  [username]
  (str "<!DOCTYPE html>
<html>
<head><title>Dashboard</title></head>
<body>
  <h1>Dashboard</h1>
  <p id=\"welcome\">Welcome, " username "!</p>
  <nav>
    <a href=\"/logout\">Logout</a>
  </nav>
  <section id=\"content\">
    <p>You are logged in.</p>
  </section>
</body>
</html>"))

;; -----------------------------------------------------------------------------
;; Handlers
;; -----------------------------------------------------------------------------

(defn- get-dashboard
  "Handler for GET /dashboard (protected)"
  [request _session-atom _users _behaviors & _ctx]
  (if-let [user (handler/current-user request)]
    (handler/html-response 200 (dashboard-html user))
    (handler/redirect "/login")))

(defn- get-logout
  "Handler for GET /logout"
  [request session-atom _users _behaviors & _ctx]
  (handler/logout! session-atom (:session-id request))
  (handler/redirect-clearing-session "/login"))

;; -----------------------------------------------------------------------------
;; Page Registration
;; -----------------------------------------------------------------------------

(pages/defpage :dashboard
  {:routes [["GET" "/dashboard" :get-dashboard]]
   :handlers {:get-dashboard get-dashboard}})

(pages/defpage :logout
  {:routes [["GET" "/logout" :get-logout]]
   :handlers {:get-logout get-logout}})
