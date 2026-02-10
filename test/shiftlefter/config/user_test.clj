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
        (let [opts {:stealth true :chrome-path "/custom/chrome"}]
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
        (spit config-path "{:chrome-path \"/user/chrome\" :default-stealth true}")
        (System/setProperty "user.home" temp-dir)

        ;; Test: user config values are applied
        (let [result (user-config/merge-with-user-config {})]
          (is (= "/user/chrome" (:chrome-path result)))
          (is (true? (:stealth result))))

        ;; Test: explicit opts override user config
        (let [result (user-config/merge-with-user-config {:stealth false})]
          (is (= "/user/chrome" (:chrome-path result)))
          (is (false? (:stealth result))))

        ;; Test: explicit chrome-path overrides user config
        (let [result (user-config/merge-with-user-config {:chrome-path "/explicit/chrome"})]
          (is (= "/explicit/chrome" (:chrome-path result)))
          (is (true? (:stealth result))))

        (finally
          (System/setProperty "user.home" original-home)
          (fs/delete-tree temp-dir))))))

(deftest translate-default-stealth-test
  (testing "default-stealth only applies when :stealth not in opts"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "shiftlefter-test-"}))
          config-dir (str temp-dir "/.shiftlefter")
          config-path (str config-dir "/config.edn")
          original-home (System/getProperty "user.home")]
      (try
        (fs/create-dirs config-dir)
        (spit config-path "{:default-stealth true}")
        (System/setProperty "user.home" temp-dir)

        ;; Test: default-stealth becomes :stealth when opts empty
        (let [result (user-config/merge-with-user-config {})]
          (is (true? (:stealth result))))

        ;; Test: explicit :stealth false overrides default-stealth
        (let [result (user-config/merge-with-user-config {:stealth false})]
          (is (false? (:stealth result))))

        ;; Test: explicit :stealth true is preserved
        (let [result (user-config/merge-with-user-config {:stealth true})]
          (is (true? (:stealth result))))

        (finally
          (System/setProperty "user.home" original-home)
          (fs/delete-tree temp-dir))))))
