(ns shiftlefter.subjects.profile-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.subjects.profile :as profile]
            [babashka.fs :as fs]))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(def ^:dynamic *test-home* nil)

(defn with-temp-home
  "Fixture that redirects user.home to a temp directory."
  [f]
  (let [temp-dir (str (fs/create-temp-dir {:prefix "sl-profile-test-"}))]
    (try
      ;; Override the home-dir function via with-redefs
      (with-redefs [profile/home-dir (constantly temp-dir)]
        (binding [*test-home* temp-dir]
          (f)))
      (finally
        (fs/delete-tree temp-dir)))))

(use-fixtures :each with-temp-home)

;; -----------------------------------------------------------------------------
;; Path Tests
;; -----------------------------------------------------------------------------

(deftest profile-dir-test
  (testing "profile-dir returns correct path"
    (let [dir (profile/profile-dir :finance)]
      (is (string? dir))
      (is (.endsWith dir "/subjects/finance"))))

  (testing "profile-dir accepts string names"
    (let [dir (profile/profile-dir "work")]
      (is (.endsWith dir "/subjects/work")))))

(deftest chrome-profile-dir-test
  (testing "chrome-profile-dir returns correct path"
    (let [dir (profile/chrome-profile-dir :finance)]
      (is (string? dir))
      (is (.endsWith dir "/subjects/finance/chrome-profile")))))

;; -----------------------------------------------------------------------------
;; Directory Management Tests
;; -----------------------------------------------------------------------------

(deftest ensure-dirs-test
  (testing "ensure-dirs! creates directories"
    (let [profile (profile/ensure-dirs! :test-subject)]
      (is (fs/exists? profile))
      (is (fs/exists? (profile/chrome-profile-dir :test-subject)))
      (is (fs/directory? profile))))

  (testing "ensure-dirs! is idempotent"
    (profile/ensure-dirs! :test-subject)
    (profile/ensure-dirs! :test-subject)
    (is (fs/exists? (profile/profile-dir :test-subject)))))

(deftest delete-profile-test
  (testing "delete-profile! removes directory"
    (profile/ensure-dirs! :deleteme)
    (is (profile/profile-exists? :deleteme))
    (is (true? (profile/delete-profile! :deleteme)))
    (is (not (profile/profile-exists? :deleteme))))

  (testing "delete-profile! returns false if not exists"
    (is (false? (profile/delete-profile! :never-existed)))))

(deftest profile-exists-test
  (testing "profile-exists? returns false for missing"
    (is (not (profile/profile-exists? :nonexistent))))

  (testing "profile-exists? returns true after ensure-dirs!"
    (profile/ensure-dirs! :exists-test)
    (is (profile/profile-exists? :exists-test))))

;; -----------------------------------------------------------------------------
;; Metadata Tests
;; -----------------------------------------------------------------------------

(deftest save-and-load-browser-meta-test
  (testing "save and load roundtrip"
    (let [meta {:debug-port 9222
                :chrome-pid 12345
                :stealth true
                :user-data-dir "/some/path"}]
      (profile/save-browser-meta! :meta-test meta)
      (let [loaded (profile/load-browser-meta :meta-test)]
        (is (= 9222 (:debug-port loaded)))
        (is (= 12345 (:chrome-pid loaded)))
        (is (true? (:stealth loaded)))
        (is (string? (:created-at loaded)))
        (is (string? (:last-connected-at loaded))))))

  (testing "save adds timestamps"
    (profile/save-browser-meta! :timestamp-test {:debug-port 9999})
    (let [loaded (profile/load-browser-meta :timestamp-test)]
      (is (some? (:created-at loaded)))
      (is (some? (:last-connected-at loaded)))))

  (testing "save preserves existing created-at"
    (profile/save-browser-meta! :preserve-test {:debug-port 1111})
    (let [first-load (profile/load-browser-meta :preserve-test)
          created-at (:created-at first-load)]
      ;; Save again
      (Thread/sleep 10) ;; Ensure time passes
      (profile/save-browser-meta! :preserve-test {:debug-port 2222})
      (let [second-load (profile/load-browser-meta :preserve-test)]
        (is (= created-at (:created-at second-load)))
        (is (= 2222 (:debug-port second-load)))))))

(deftest load-browser-meta-missing-test
  (testing "load returns nil for missing file"
    (is (nil? (profile/load-browser-meta :never-saved)))))

(deftest clear-browser-meta-test
  (testing "clear-browser-meta! removes file"
    (profile/save-browser-meta! :clear-test {:debug-port 9222})
    (is (some? (profile/load-browser-meta :clear-test)))
    (is (true? (profile/clear-browser-meta! :clear-test)))
    (is (nil? (profile/load-browser-meta :clear-test))))

  (testing "clear-browser-meta! returns false if not exists"
    (is (false? (profile/clear-browser-meta! :never-existed)))))

;; -----------------------------------------------------------------------------
;; Listing Tests
;; -----------------------------------------------------------------------------

(deftest list-subjects-test
  (testing "list-subjects returns empty when none exist"
    (is (= [] (profile/list-subjects))))

  (testing "list-subjects returns created subjects"
    (profile/ensure-dirs! :alpha)
    (profile/ensure-dirs! :beta)
    (profile/ensure-dirs! :gamma)
    (let [subjects (profile/list-subjects)]
      (is (= ["alpha" "beta" "gamma"] subjects)))))
