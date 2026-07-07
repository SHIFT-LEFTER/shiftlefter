(ns shiftlefter.config.user-test
  "Tests for user-level configuration loading."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.config.user :as user-config]
            [babashka.fs :as fs]))

;; -----------------------------------------------------------------------------
;; Path Tests
;; -----------------------------------------------------------------------------

(deftest user-config-path-test
  (testing "Returns path under ~/.shiftlefter"
    (let [path (user-config/user-config-path)]
      (is (string? path))
      (is (.endsWith path "/.shiftlefter/config.edn")))))

;; -----------------------------------------------------------------------------
;; Loading Tests
;; -----------------------------------------------------------------------------

(deftest load-user-config-test
  (testing "Returns nil when config file doesn't exist"
    ;; Use a temp dir to ensure no config exists
    (let [original-home (System/getProperty "user.home")]
      (try
        (System/setProperty "user.home" "/nonexistent/path/that/does/not/exist")
        (is (nil? (user-config/load-user-config)))
        (finally
          (System/setProperty "user.home" original-home))))))

(deftest load-user-config-safe-test
  (testing "Returns {:ok nil} when file doesn't exist"
    (let [original-home (System/getProperty "user.home")]
      (try
        (System/setProperty "user.home" "/nonexistent/path/that/does/not/exist")
        (let [result (user-config/load-user-config-safe)]
          (is (= {:ok nil} result)))
        (finally
          (System/setProperty "user.home" original-home))))))

;; -----------------------------------------------------------------------------
;; Merging Tests
;; -----------------------------------------------------------------------------

(deftest merge-with-user-config-test
  (testing "Returns opts unchanged when no user config exists"
    (let [original-home (System/getProperty "user.home")]
      (try
        (System/setProperty "user.home" "/nonexistent/path/that/does/not/exist")
        (let [opts {:chrome-path "/custom/chrome"}]
          (is (= opts (user-config/merge-with-user-config opts))))
        (finally
          (System/setProperty "user.home" original-home)))))

  (testing "Returns empty map when no config and empty opts"
    (let [original-home (System/getProperty "user.home")]
      (try
        (System/setProperty "user.home" "/nonexistent/path/that/does/not/exist")
        (is (= {} (user-config/merge-with-user-config {})))
        (finally
          (System/setProperty "user.home" original-home))))))

;; -----------------------------------------------------------------------------
;; Integration Tests with Temp Files
;; -----------------------------------------------------------------------------

(deftest merge-with-user-config-integration-test
  (testing "User config is merged with opts"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "shiftlefter-test-"}))
          config-dir (str temp-dir "/.shiftlefter")
          config-path (str config-dir "/config.edn")
          original-home (System/getProperty "user.home")]
      (try
        ;; Create config directory and file
        (fs/create-dirs config-dir)
        (spit config-path "{:chrome-path \"/user/chrome\"}")
        (System/setProperty "user.home" temp-dir)

        ;; Test: user config values are applied
        (let [result (user-config/merge-with-user-config {})]
          (is (= "/user/chrome" (:chrome-path result))))

        ;; Test: explicit chrome-path overrides user config
        (let [result (user-config/merge-with-user-config {:chrome-path "/explicit/chrome"})]
          (is (= "/explicit/chrome" (:chrome-path result))))

        (finally
          (System/setProperty "user.home" original-home)
          (fs/delete-tree temp-dir))))))

;; -----------------------------------------------------------------------------
;; ChromeDriver Resolution Tests (sl-sq4)
;; -----------------------------------------------------------------------------

(deftest resolve-chromedriver-path-test
  (testing "Explicit :path-driver opt wins (no config needed)"
    (is (= "/explicit/chromedriver"
           (user-config/resolve-chromedriver-path {:path-driver "/explicit/chromedriver"}))))

  (testing "Returns nil when no opt and no config file"
    (let [original-home (System/getProperty "user.home")]
      (try
        (System/setProperty "user.home" "/nonexistent/path/that/does/not/exist")
        (is (nil? (user-config/resolve-chromedriver-path {})))
        (finally
          (System/setProperty "user.home" original-home))))))

(deftest resolve-chromedriver-path-config-test
  (let [temp-dir (str (fs/create-temp-dir {:prefix "shiftlefter-test-"}))
        config-dir (str temp-dir "/.shiftlefter")
        config-path (str config-dir "/config.edn")
        original-home (System/getProperty "user.home")]
    (try
      (fs/create-dirs config-dir)
      (spit config-path "{:chromedriver-path \"/cfg/chromedriver\"}")
      (System/setProperty "user.home" temp-dir)

      (testing "Falls back to config.edn :chromedriver-path when no opt"
        (is (= "/cfg/chromedriver"
               (user-config/resolve-chromedriver-path {}))))

      (testing "Explicit :path-driver opt overrides config.edn"
        (is (= "/explicit/chromedriver"
               (user-config/resolve-chromedriver-path {:path-driver "/explicit/chromedriver"}))))

      (finally
        (System/setProperty "user.home" original-home)
        (fs/delete-tree temp-dir)))))

(deftest resolve-chromedriver-path-malformed-config-test
  (testing "Swallows malformed EDN (parse error) -> nil -> PATH fallback"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "shiftlefter-test-"}))
          config-dir (str temp-dir "/.shiftlefter")
          config-path (str config-dir "/config.edn")
          original-home (System/getProperty "user.home")]
      (try
        (fs/create-dirs config-dir)
        (spit config-path "{:chromedriver-path }}}broken")
        (System/setProperty "user.home" temp-dir)
        (is (nil? (user-config/resolve-chromedriver-path {})))
        (finally
          (System/setProperty "user.home" original-home)
          (fs/delete-tree temp-dir))))))
