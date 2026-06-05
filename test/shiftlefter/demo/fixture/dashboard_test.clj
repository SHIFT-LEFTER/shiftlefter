(ns shiftlefter.demo.fixture.dashboard-test
  "Tests for the dashboard and logout page blocks."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [shiftlefter.demo.fixture.server :as server]
            ;; Require pages to register them
            [shiftlefter.demo.fixture.login]
            [shiftlefter.demo.fixture.dashboard]))

;; -----------------------------------------------------------------------------
;; HTTP Helpers
;; -----------------------------------------------------------------------------

(defn- http-get
  "Make a GET request, return {:status :body :headers}"
  ([url] (http-get url nil))
  ([url cookies]
   (let [conn (.openConnection (java.net.URL. url))]
     (.setInstanceFollowRedirects conn false)
     (when cookies
       (.setRequestProperty conn "Cookie" cookies))
     {:status (.getResponseCode conn)
      :headers (into {} (.getHeaderFields conn))
      :body (try (slurp (.getInputStream conn))
                 (catch Exception _ nil))})))

(defn- http-post
  "Make a POST request with form data, return {:status :body :headers}"
  ([url form-data] (http-post url form-data nil))
  ([url form-data cookies]
   (let [conn (doto (.openConnection (java.net.URL. url))
                (.setRequestMethod "POST")
                (.setDoOutput true)
                (.setInstanceFollowRedirects false)
                (.setRequestProperty "Content-Type" "application/x-www-form-urlencoded"))
         _ (when cookies
             (.setRequestProperty conn "Cookie" cookies))
         body-str (->> form-data
                       (map (fn [[k v]]
                              (str (java.net.URLEncoder/encode (name k) "UTF-8")
                                   "="
                                   (java.net.URLEncoder/encode v "UTF-8"))))
                       (str/join "&"))]
     (with-open [out (.getOutputStream conn)]
       (.write out (.getBytes body-str "UTF-8")))
     {:status (.getResponseCode conn)
      :headers (into {} (.getHeaderFields conn))
      :body (try (slurp (.getInputStream conn))
                 (catch Exception _ nil))})))

(defn- extract-session-cookie
  "Extract session cookie value from response headers."
  [response]
  (some->> response
           :headers
           (some (fn [[k v]] (when (= "Set-Cookie" k) (first v))))
           (re-find #"fixture-session=([^;]+)")
           second))

(defn- cookie-header
  "Format session ID as a Cookie header value."
  [session-id]
  (str "fixture-session=" session-id))

;; -----------------------------------------------------------------------------
;; Tests
;; -----------------------------------------------------------------------------

(deftest test-dashboard-redirects-when-not-authenticated
  (testing "GET /dashboard redirects to /login when not authenticated"
    (server/with-fixture-server
      {:pages [:login :dashboard]}
      (fn [{:keys [base-url]}]
        (let [response (http-get (str base-url "/dashboard"))]
          (is (= 302 (:status response)) "Should redirect")
          (is (= "/login" (first (get (:headers response) "Location")))
              "Should redirect to login"))))))

(deftest test-dashboard-shows-user-when-authenticated
  (testing "GET /dashboard shows welcome message when authenticated"
    (server/with-fixture-server
      {:users {"alice" "secret"}
       :pages [:login :dashboard]}
      (fn [{:keys [base-url]}]
        ;; Login first
        (let [login-response (http-post (str base-url "/login")
                                        {:username "alice" :password "secret"})
              session-id (extract-session-cookie login-response)]
          (is (some? session-id) "Should get session cookie")
          ;; Now access dashboard with session
          (let [dashboard-response (http-get (str base-url "/dashboard")
                                             (cookie-header session-id))]
            (is (= 200 (:status dashboard-response)))
            (is (str/includes? (:body dashboard-response) "Welcome, alice!")
                "Should show user's name")))))))

(deftest test-logout-clears-session
  (testing "GET /logout clears session and redirects to login"
    (server/with-fixture-server
      {:users {"alice" "secret"}
       :pages [:login :dashboard :logout]}
      (fn [{:keys [base-url]}]
        ;; Login first
        (let [login-response (http-post (str base-url "/login")
                                        {:username "alice" :password "secret"})
              session-id (extract-session-cookie login-response)
              ;; Logout
              logout-response (http-get (str base-url "/logout")
                                        (cookie-header session-id))]
          (is (= 302 (:status logout-response)) "Should redirect")
          (is (= "/login" (first (get (:headers logout-response) "Location"))))
          ;; Try to access dashboard after logout - should redirect
          (let [dashboard-response (http-get (str base-url "/dashboard")
                                             (cookie-header session-id))]
            (is (= 302 (:status dashboard-response))
                "Should redirect to login after logout")))))))

(deftest test-alice-and-bob-separate-sessions
  (testing "Alice and Bob have separate sessions"
    (server/with-fixture-server
      {:users {"alice" "secret1" "bob" "secret2"}
       :pages [:login :dashboard :logout]}
      (fn [{:keys [base-url]}]
        ;; Alice logs in
        (let [alice-login (http-post (str base-url "/login")
                                     {:username "alice" :password "secret1"})
              alice-session (extract-session-cookie alice-login)]
          (is (some? alice-session) "Alice should get session")

          ;; Bob logs in (different session)
          (let [bob-login (http-post (str base-url "/login")
                                     {:username "bob" :password "secret2"})
                bob-session (extract-session-cookie bob-login)]
            (is (some? bob-session) "Bob should get session")
            (is (not= alice-session bob-session) "Sessions should be different")

            ;; Alice sees her dashboard
            (let [alice-dash (http-get (str base-url "/dashboard")
                                       (cookie-header alice-session))]
              (is (str/includes? (:body alice-dash) "Welcome, alice!")))

            ;; Bob sees his dashboard
            (let [bob-dash (http-get (str base-url "/dashboard")
                                     (cookie-header bob-session))]
              (is (str/includes? (:body bob-dash) "Welcome, bob!")))

            ;; Alice logs out - should not affect Bob
            (http-get (str base-url "/logout") (cookie-header alice-session))

            ;; Bob still sees his dashboard
            (let [bob-dash (http-get (str base-url "/dashboard")
                                     (cookie-header bob-session))]
              (is (= 200 (:status bob-dash))
                  "Bob should still be logged in"))))))))
