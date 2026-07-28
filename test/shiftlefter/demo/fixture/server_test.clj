(ns shiftlefter.demo.fixture.server-test
  "Tests for the fixture server harness."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [shiftlefter.demo.fixture.server :as server]))

(deftest test-server-starts-and-stops
  (testing "Server starts on auto-assigned port and provides base-url"
    (let [received-ctx (atom nil)]
      (server/with-fixture-server
        {}
        (fn [ctx]
          (reset! received-ctx ctx)))
      ;; After macro completes, server should have run
      (is (some? @received-ctx) "Test fn should have been called")
      (is (string? (:base-url @received-ctx)) "base-url should be a string")
      (is (str/starts-with? (:base-url @received-ctx) "http://127.0.0.1:")
          "base-url should be http://127.0.0.1:PORT (loopback-specific, sl-7hb1)")
      (is (integer? (:port @received-ctx)) "port should be an integer")
      (is (pos? (:port @received-ctx)) "port should be positive"))))

(deftest test-server-returns-404-for-empty-pages
  (testing "Server with no pages returns 404"
    (server/with-fixture-server
      {}
      (fn [{:keys [base-url]}]
        ;; Connection: close on every fixture request (sl-7hb1)
        (let [conn (doto (.openConnection (java.net.URL. base-url))
                     (.setRequestProperty "Connection" "close"))
              status (.getResponseCode conn)]
          (is (= 404 status) "Empty config should return 404"))))))

(deftest test-server-passes-config
  (testing "Config is passed through to test fn"
    (let [test-config {:users {"alice" "secret"}
                       :pages [:login]
                       :behaviors {:login-delay-ms 100}}]
      (server/with-fixture-server
        test-config
        (fn [{:keys [config]}]
          (is (= test-config config) "Config should be passed to test fn"))))))

(deftest test-server-stops-on-exception
  (testing "Server stops even if test fn throws"
    (let [port-used (atom nil)
          caught-ex (atom nil)]
      ;; Run a server that will throw
      (try
        (server/with-fixture-server
          {}
          (fn [{:keys [port]}]
            (reset! port-used port)
            (throw (ex-info "Test exception" {}))))
        (catch Exception e
          (reset! caught-ex e)))
      ;; Exception should propagate
      (is (some? @caught-ex) "Exception should propagate")
      ;; Port should have been assigned
      (is (some? @port-used) "Port should have been used")
      ;; Server should be stopped (port should be available)
      ;; We can't easily test port is free, but we can test another server starts fine
      (server/with-fixture-server
        {}
        (fn [{:keys [port]}]
          (is (pos? port) "Should be able to start another server"))))))

(deftest test-multiple-servers-different-ports
  (testing "Multiple servers get different ports"
    (let [ports (atom [])]
      ;; Start 3 servers in sequence (not parallel for simplicity)
      (dotimes [_ 3]
        (server/with-fixture-server
          {}
          (fn [{:keys [port]}]
            (swap! ports conj port))))
      ;; All ports should be distinct
      (is (= 3 (count (distinct @ports)))
          "Each server should get a unique port"))))
