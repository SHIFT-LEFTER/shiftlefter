(ns shiftlefter.browser.chrome-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.browser.chrome :as chrome]
            [babashka.fs :as fs]))

;; -----------------------------------------------------------------------------
;; Test Helpers
;; -----------------------------------------------------------------------------

(defn- error-type
  "Extract the error type from an error result."
  [result]
  (-> result :errors first :type))

(defn- has-error?
  "Check if result contains an error of the given type."
  [result error-type-kw]
  (= error-type-kw (error-type result)))

;; -----------------------------------------------------------------------------
;; find-binary tests
;; -----------------------------------------------------------------------------

(deftest find-binary-with-override-test
  (testing "override path that exists returns path"
    ;; Use a file we know exists (this test file's directory)
    (let [existing-path (str (fs/canonicalize "deps.edn"))]
      (is (= existing-path (chrome/find-binary {:chrome-path existing-path})))))

  (testing "override path that doesn't exist returns error"
    (let [result (chrome/find-binary {:chrome-path "/nonexistent/chrome/path"})]
      (is (has-error? result :persistent/chrome-not-found))
      (is (= ["/nonexistent/chrome/path"]
             (-> result :errors first :data :searched-paths))))))

(deftest find-binary-macos-test
  (testing "macOS default detection"
    (with-redefs [chrome/get-os (constantly :macos)]
      (let [result (chrome/find-binary)]
        ;; On actual macOS with Chrome installed, this returns the path
        ;; On CI or systems without Chrome, this returns an error
        (if (fs/exists? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
          (is (= "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" result))
          (is (has-error? result :persistent/chrome-not-found)))))))

(deftest find-binary-linux-test
  (testing "Linux PATH search when chrome found"
    (with-redefs [chrome/get-os (constantly :linux)
                  chrome/find-on-path (fn [name]
                                        (when (= name "google-chrome")
                                          "/usr/bin/google-chrome"))]
      (is (= "/usr/bin/google-chrome" (chrome/find-binary)))))

  (testing "Linux PATH search tries candidates in order"
    (let [calls (atom [])]
      (with-redefs [chrome/get-os (constantly :linux)
                    chrome/find-on-path (fn [name]
                                          (swap! calls conj name)
                                          ;; Return match on chromium
                                          (when (= name "chromium")
                                            "/usr/bin/chromium"))]
        (is (= "/usr/bin/chromium" (chrome/find-binary)))
        ;; Should have tried google-chrome, google-chrome-stable, chromium-browser first
        (is (= ["google-chrome" "google-chrome-stable" "chromium-browser" "chromium"]
               @calls)))))

  (testing "Linux returns error when no chrome found"
    (with-redefs [chrome/get-os (constantly :linux)
                  chrome/find-on-path (constantly nil)]
      (let [result (chrome/find-binary)]
        (is (has-error? result :persistent/chrome-not-found))))))

(deftest find-binary-windows-test
  (testing "Windows returns os-not-supported error"
    (with-redefs [chrome/get-os (constantly :windows)]
      (let [result (chrome/find-binary)]
        (is (has-error? result :persistent/os-not-supported))
        (is (= "Windows" (-> result :errors first :data :os)))
        (is (= [:macos :linux] (-> result :errors first :data :supported-os)))))))

(deftest find-binary-unknown-os-test
  (testing "Unknown OS returns os-not-supported error"
    (with-redefs [chrome/get-os (constantly :unknown)]
      (let [result (chrome/find-binary)]
        (is (has-error? result :persistent/os-not-supported))))))

;; -----------------------------------------------------------------------------
;; binary-exists? tests
;; -----------------------------------------------------------------------------

(deftest binary-exists-test
  (testing "nil path returns false"
    (is (false? (chrome/binary-exists? nil))))

  (testing "nonexistent path returns false"
    (is (false? (chrome/binary-exists? "/nonexistent/path"))))

  (testing "existing non-executable returns false"
    ;; deps.edn exists but is not executable
    (is (false? (chrome/binary-exists? "deps.edn"))))

  (testing "existing executable returns true"
    ;; Use a known executable - the bash shell
    (when (fs/exists? "/bin/bash")
      (is (true? (chrome/binary-exists? "/bin/bash"))))))

;; -----------------------------------------------------------------------------
;; probe-cdp tests
;; -----------------------------------------------------------------------------

(deftest probe-cdp-dead-port-test
  (testing "port with nothing listening returns :dead"
    ;; Use a port that's very unlikely to have anything on it
    (let [result (chrome/probe-cdp 59999)]
      (is (= :dead (:status result)))
      (is (nil? (:version result)))))

  (testing "custom timeout is accepted"
    ;; Should still return :dead, just faster
    (let [result (chrome/probe-cdp 59999 {:timeout-ms 100})]
      (is (= :dead (:status result))))))

(deftest probe-cdp-response-shape-test
  (testing "alive response has expected shape"
    ;; Mock the HTTP call to return a valid CDP response
    (with-redefs [chrome/probe-cdp (fn [_port & _opts]
                                     {:status :alive
                                      :version {:Browser "Chrome/120.0.0.0"
                                                :Protocol-Version "1.3"
                                                :User-Agent "Mozilla/5.0"
                                                :webSocketDebuggerUrl "ws://127.0.0.1:9222/..."}})]
      (let [result (chrome/probe-cdp 9222)]
        (is (= :alive (:status result)))
        (is (map? (:version result)))
        (is (contains? (:version result) :Browser))))))

;; Integration test - only runs if SHIFTLEFTER_LIVE_CHROME env var is set
;; To run: Start Chrome with --remote-debugging-port=9222, then set env var
(deftest ^:integration probe-cdp-live-test
  (if (System/getenv "SHIFTLEFTER_LIVE_CHROME")
    (testing "live Chrome on port 9222"
      (let [result (chrome/probe-cdp 9222)]
        (is (= :alive (:status result)))
        (is (string? (get-in result [:version :Browser])))
        (is (string? (get-in result [:version :webSocketDebuggerUrl])))))
    (testing "skipped - SHIFTLEFTER_LIVE_CHROME not set"
      (is true "integration test skipped"))))

;; -----------------------------------------------------------------------------
;; launch! tests
;; -----------------------------------------------------------------------------

(deftest build-chrome-args-test
  (testing "basic args without stealth"
    (let [args (chrome/build-chrome-args {:port 9222
                                          :user-data-dir "/tmp/profile"})]
      (is (= ["--remote-debugging-port=9222"
              "--user-data-dir=/tmp/profile"
              "--no-first-run"
              "--no-default-browser-check"]
             args))))

  (testing "args with stealth enabled"
    (let [args (chrome/build-chrome-args {:port 9222
                                          :user-data-dir "/tmp/profile"
                                          :stealth true})]
      (is (= ["--remote-debugging-port=9222"
              "--user-data-dir=/tmp/profile"
              "--no-first-run"
              "--no-default-browser-check"
              "--disable-blink-features=AutomationControlled"]
             args)))))

(deftest launch-chrome-not-found-test
  (testing "launch! with nonexistent chrome-path returns error"
    (let [result (chrome/launch! {:port 9222
                                  :user-data-dir "/tmp/test"
                                  :chrome-path "/nonexistent/chrome"})]
      (is (has-error? result :persistent/chrome-not-found))))

  (testing "launch! with auto-detect when chrome not found returns error"
    (with-redefs [chrome/find-binary (fn []
                                       {:errors [{:type :persistent/chrome-not-found
                                                  :message "Chrome not found"
                                                  :data {:searched-paths []}}]})]
      (let [result (chrome/launch! {:port 9222
                                    :user-data-dir "/tmp/test"})]
        (is (has-error? result :persistent/chrome-not-found))))))

;; Integration test - actually launches Chrome
;; Only runs if SHIFTLEFTER_LIVE_CHROME env var is set
(deftest ^:integration launch-live-test
  (if (System/getenv "SHIFTLEFTER_LIVE_CHROME")
    (let [user-data-dir (str (fs/create-temp-dir {:prefix "sl-chrome-test-"}))]
      (try
        (testing "launch! starts Chrome and returns pid/port"
          (let [result (chrome/launch! {:port 9333
                                        :user-data-dir user-data-dir
                                        :cdp-timeout-ms 15000})]
            (is (nil? (:errors result)) (str "Got errors: " (:errors result)))
            (is (integer? (:pid result)))
            (is (= 9333 (:port result)))
            (is (some? (:process result)))

            ;; Verify CDP is reachable
            (let [probe (chrome/probe-cdp 9333)]
              (is (= :alive (:status probe))))

            ;; Clean up - kill the process
            (when-let [process (:process result)]
              (.destroyForcibly ^Process process))))
        (finally
          ;; Clean up temp directory
          (fs/delete-tree user-data-dir))))
    (testing "skipped - SHIFTLEFTER_LIVE_CHROME not set"
      (is true "integration test skipped"))))

;; -----------------------------------------------------------------------------
;; allocate-port tests
;; -----------------------------------------------------------------------------

(deftest allocate-port-first-available-test
  (testing "returns first port when nothing is running"
    ;; Mock probe-cdp to always return :dead (nothing running)
    (with-redefs [chrome/probe-cdp (fn [_port _opts] {:status :dead})]
      (is (= 9222 (chrome/allocate-port)))))

  (testing "custom start-port returns that port when available"
    (with-redefs [chrome/probe-cdp (fn [_port _opts] {:status :dead})]
      (is (= 9300 (chrome/allocate-port {:start-port 9300}))))))

(deftest allocate-port-skips-in-use-test
  (testing "skips ports that are in use"
    ;; 9222 is alive, 9223 is dead
    (with-redefs [chrome/probe-cdp (fn [port _opts]
                                     (if (= port 9222)
                                       {:status :alive :version {}}
                                       {:status :dead}))]
      (is (= 9223 (chrome/allocate-port)))))

  (testing "skips multiple in-use ports"
    ;; 9222, 9223, 9224 are alive, 9225 is dead
    (with-redefs [chrome/probe-cdp (fn [port _opts]
                                     (if (< port 9225)
                                       {:status :alive :version {}}
                                       {:status :dead}))]
      (is (= 9225 (chrome/allocate-port))))))

(deftest allocate-port-exhausted-test
  (testing "returns error when all ports in range are in use"
    ;; All ports alive
    (with-redefs [chrome/probe-cdp (fn [_port _opts] {:status :alive :version {}})]
      (let [result (chrome/allocate-port {:start-port 9222 :max-range 5})]
        (is (has-error? result :persistent/no-port-available))
        (is (= 9222 (-> result :errors first :data :start-port)))
        (is (= 9226 (-> result :errors first :data :end-port)))))))

(deftest allocate-port-real-test
  (testing "real allocation finds available port (nothing should be on 59000+)"
    ;; Use high port range unlikely to have anything running
    (let [port (chrome/allocate-port {:start-port 59000})]
      (is (integer? port))
      (is (= 59000 port)))))
