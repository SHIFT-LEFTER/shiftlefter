(ns shiftlefter.demo.fixture.login-test
  "Tests for the login page block."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [shiftlefter.demo.fixture.server :as server]
            ;; Require login to register the page
            [shiftlefter.demo.fixture.login]))

;; -----------------------------------------------------------------------------
;; HTTP Helpers
;; -----------------------------------------------------------------------------

(defn- http-get
  "Make a GET request, return {:status :body :headers :cookies}"
  [url]
  (let [conn (.openConnection (java.net.URL. url))]
    (.setInstanceFollowRedirects conn false)
    {:status (.getResponseCode conn)
     :headers (into {} (.getHeaderFields conn))
     :body (try (slurp (.getInputStream conn))
                (catch Exception _ nil))}))

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
  "Extract session cookie from response headers."
  [response]
  (some->> response
           :headers
           (some (fn [[k v]] (when (= "Set-Cookie" k) (first v))))
           (re-find #"fixture-session=([^;]+)")
           second))

;; -----------------------------------------------------------------------------
;; Tests
;; -----------------------------------------------------------------------------

(deftest test-login-page-renders
  (testing "GET /login returns login form"
    (server/with-fixture-server
      {:pages [:login]}
      (fn [{:keys [base-url]}]
        (let [response (http-get (str base-url "/login"))]
          (is (= 200 (:status response)))
          (is (str/includes? (:body response) "<form"))
          (is (str/includes? (:body response) "id=\"username\""))
          (is (str/includes? (:body response) "id=\"password\""))
          (is (str/includes? (:body response) "type=\"submit\"")))))))

(deftest test-login-success-redirects
  (testing "POST /login with valid credentials redirects to dashboard"
    (server/with-fixture-server
      {:users {"alice" "secret"}
       :pages [:login]}
      (fn [{:keys [base-url]}]
        (let [response (http-post (str base-url "/login")
                                  {:username "alice" :password "secret"})]
          (is (= 302 (:status response)) "Should redirect")
          (is (= "/dashboard" (first (get (:headers response) "Location")))
              "Should redirect to dashboard")
          (is (some? (extract-session-cookie response))
              "Should set session cookie"))))))

(deftest test-login-failure-shows-error
  (testing "POST /login with invalid credentials shows error"
    (server/with-fixture-server
      {:users {"alice" "secret"}
       :pages [:login]}
      (fn [{:keys [base-url]}]
        (let [response (http-post (str base-url "/login")
                                  {:username "alice" :password "wrong"})]
          (is (= 200 (:status response)) "Should stay on login page")
          (is (str/includes? (:body response) "Invalid username or password")
              "Should show error message"))))))

(deftest test-login-delay-behavior
  (testing ":login-delay-ms behavior adds delay"
    (server/with-fixture-server
      {:users {"alice" "secret"}
       :pages [:login]
       :behaviors {:login-delay-ms 200}}
      (fn [{:keys [base-url]}]
        (let [start (System/currentTimeMillis)
              _response (http-post (str base-url "/login")
                                   {:username "alice" :password "secret"})
              elapsed (- (System/currentTimeMillis) start)]
          (is (>= elapsed 200) "Should delay at least 200ms"))))))

(deftest test-login-fail-for-behavior
  (testing ":fail-login-for behavior forces login failure"
    (server/with-fixture-server
      {:users {"alice" "secret" "bob" "pass"}
       :pages [:login]
       :behaviors {:fail-login-for #{"bob"}}}
      (fn [{:keys [base-url]}]
        ;; Alice should succeed
        (let [response (http-post (str base-url "/login")
                                  {:username "alice" :password "secret"})]
          (is (= 302 (:status response)) "Alice should succeed"))
        ;; Bob should fail even with correct password
        (let [response (http-post (str base-url "/login")
                                  {:username "bob" :password "pass"})]
          (is (= 200 (:status response)) "Bob should fail")
          (is (str/includes? (:body response) "Login failed")))))))
