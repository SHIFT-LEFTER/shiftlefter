(ns shiftlefter.demo.fixture.handler-test
  "Tests for config-driven handler composition."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [shiftlefter.demo.fixture.server :as server]
            [shiftlefter.demo.fixture.handler :as handler]
            [shiftlefter.demo.fixture.pages :as pages]))

;; -----------------------------------------------------------------------------
;; Test Page Registration
;; -----------------------------------------------------------------------------

;; Register a simple test page for verification
(pages/defpage :test-page
  {:routes [["GET" "/test" :get-test]
            ["POST" "/test" :post-test]]
   :handlers {:get-test (fn [_req _session-atom _users _behaviors & _ctx]
                          {:status 200
                           :headers {"Content-Type" "text/plain"}
                           :body "GET test page"})
              :post-test (fn [_req _session-atom _users _behaviors & _ctx]
                            {:status 200
                             :headers {"Content-Type" "text/plain"}
                             :body "POST test page"})}})

;; Register a page that uses config
(pages/defpage :config-page
  {:routes [["GET" "/config" :get-config]]
   :handlers {:get-config (fn [_req _session-atom users behaviors & _ctx]
                            {:status 200
                             :headers {"Content-Type" "text/plain"}
                             :body (str "users=" (count users)
                                        ",delay=" (get behaviors :delay-ms 0))})}})

;; Register an auth-checking page
(pages/defpage :whoami
  {:routes [["GET" "/whoami" :get-whoami]]
   :handlers {:get-whoami (fn [req _session-atom _users _behaviors & _ctx]
                            (let [user (handler/current-user req)]
                              {:status 200
                               :headers {"Content-Type" "text/plain"}
                               :body (or user "anonymous")}))}})

;; -----------------------------------------------------------------------------
;; Tests
;; -----------------------------------------------------------------------------

(deftest test-empty-pages-returns-404
  (testing "Server with no pages returns 404 for any path"
    (server/with-fixture-server
      {:pages []}
      (fn [{:keys [base-url]}]
        (let [response (try
                         (slurp (str base-url "/anything"))
                         (catch java.io.IOException _
                           ;; 404 throws IOException, check the response
                           (let [conn (.openConnection (java.net.URL. (str base-url "/anything")))]
                             (.getResponseCode conn))))]
          (is (= 404 response) "Empty pages should return 404"))))))

(deftest test-page-registry-routes
  (testing "Registered page routes are served"
    (server/with-fixture-server
      {:pages [:test-page]}
      (fn [{:keys [base-url]}]
        (let [response (slurp (str base-url "/test"))]
          (is (= "GET test page" response) "GET /test should work"))))))

(deftest test-post-route
  (testing "POST routes work correctly"
    (server/with-fixture-server
      {:pages [:test-page]}
      (fn [{:keys [base-url]}]
        (let [conn (doto (.openConnection (java.net.URL. (str base-url "/test")))
                     (.setRequestMethod "POST")
                     (.setDoOutput true))
              response (slurp (.getInputStream conn))]
          (is (= "POST test page" response) "POST /test should work"))))))

(deftest test-config-passed-to-handlers
  (testing "Config is passed through to page handlers"
    (server/with-fixture-server
      {:users {"alice" "secret" "bob" "pass"}
       :pages [:config-page]
       :behaviors {:delay-ms 100}}
      (fn [{:keys [base-url]}]
        (let [response (slurp (str base-url "/config"))]
          (is (str/includes? response "users=2") "Should see 2 users")
          (is (str/includes? response "delay=100") "Should see delay behavior"))))))

(deftest test-unknown-route-returns-404
  (testing "Unknown routes return 404 even with pages configured"
    (server/with-fixture-server
      {:pages [:test-page]}
      (fn [{:keys [base-url]}]
        (let [conn (.openConnection (java.net.URL. (str base-url "/unknown")))
              status (.getResponseCode conn)]
          (is (= 404 status) "Unknown route should return 404"))))))

(deftest test-session-middleware-injects-session
  (testing "Session middleware provides session data to handlers"
    (server/with-fixture-server
      {:pages [:whoami]}
      (fn [{:keys [base-url]}]
        ;; Without login, should be anonymous
        (let [response (slurp (str base-url "/whoami"))]
          (is (= "anonymous" response) "Should be anonymous without login"))))))

(deftest test-multiple-pages-compose
  (testing "Multiple pages can be composed together"
    (server/with-fixture-server
      {:pages [:test-page :config-page :whoami]
       :users {"alice" "secret"}}
      (fn [{:keys [base-url]}]
        ;; All routes should work
        (is (= "GET test page" (slurp (str base-url "/test"))))
        (is (str/includes? (slurp (str base-url "/config")) "users=1"))
        (is (= "anonymous" (slurp (str base-url "/whoami"))))))))
