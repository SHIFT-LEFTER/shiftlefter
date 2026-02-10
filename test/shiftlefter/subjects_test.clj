(ns shiftlefter.subjects-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.subjects :as subjects]
            [shiftlefter.subjects.profile :as profile]
            [shiftlefter.subjects.browser :as pbrowser]
            [shiftlefter.browser.chrome :as chrome]
            [shiftlefter.webdriver.etaoin.session :as session]
            [babashka.fs :as fs]))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(def ^:dynamic *test-home* nil)

(defn with-temp-home
  "Fixture that redirects user.home to a temp directory."
  [f]
  (let [temp-dir (str (fs/create-temp-dir {:prefix "sl-subjects-test-"}))]
    (try
      (with-redefs [profile/home-dir (constantly temp-dir)]
        (binding [*test-home* temp-dir]
          (f)))
      (finally
        (fs/delete-tree temp-dir)))))

(use-fixtures :each with-temp-home)

;; -----------------------------------------------------------------------------
;; Error Handling Tests (no Chrome needed)
;; -----------------------------------------------------------------------------

(deftest init-persistent-already-exists-test
  (testing "init-persistent! returns error if subject exists"
    ;; Create profile directory manually
    (profile/ensure-dirs! :existing)
    (profile/save-browser-meta! :existing {:debug-port 9222})

    (let [result (subjects/init-persistent! :existing)]
      (is (:error result))
      (is (= :subject/already-exists (get-in result [:error :type]))))))

(deftest connect-persistent-not-found-test
  (testing "connect-persistent! returns error if subject not found"
    (let [result (subjects/connect-persistent! :nonexistent)]
      (is (:error result))
      (is (= :subject/not-found (get-in result [:error :type]))))))

(deftest destroy-persistent-not-found-test
  (testing "destroy-persistent! returns error if subject not found"
    (let [result (subjects/destroy-persistent! :nonexistent)]
      (is (:error result))
      (is (= :subject/not-found (get-in result [:error :type]))))))

(deftest list-persistent-empty-test
  (testing "list-persistent returns empty vector when no subjects"
    (is (= [] (subjects/list-persistent)))))

;; -----------------------------------------------------------------------------
;; Mocked Integration Tests
;; -----------------------------------------------------------------------------

(deftest init-persistent-port-allocation-failure-test
  (testing "init-persistent! returns error on port allocation failure"
    (with-redefs [chrome/allocate-port (constantly {:errors [{:type :persistent/no-port-available}]})]
      (let [result (subjects/init-persistent! :test-subject)]
        (is (:error result))
        (is (= :subject/init-failed (get-in result [:error :type])))))))

(deftest init-persistent-launch-failure-test
  (testing "init-persistent! returns error on Chrome launch failure"
    (with-redefs [chrome/allocate-port (constantly 9222)
                  chrome/launch! (constantly {:errors [{:type :persistent/chrome-launch-failed}]})]
      (let [result (subjects/init-persistent! :test-subject)]
        (is (:error result))
        (is (= :subject/init-failed (get-in result [:error :type])))
        ;; Profile should be cleaned up
        (is (not (profile/profile-exists? :test-subject)))))))

(deftest init-persistent-connect-failure-test
  (testing "init-persistent! returns error on WebDriver connection failure"
    (with-redefs [chrome/allocate-port (constantly 9222)
                  chrome/launch! (constantly {:pid 12345 :port 9222 :process nil})
                  chrome/kill-by-pid! (constantly {:killed true})
                  session/connect-to-existing! (constantly {:error {:type :webdriver/connect-failed}})]
      (let [result (subjects/init-persistent! :test-subject)]
        (is (:error result))
        (is (= :subject/init-failed (get-in result [:error :type])))
        ;; Profile should be cleaned up
        (is (not (profile/profile-exists? :test-subject)))))))

(deftest init-persistent-success-test
  (testing "init-persistent! succeeds with mocked Chrome/session"
    (let [fake-browser {:type :fake}]
      (with-redefs [chrome/allocate-port (constantly 9222)
                    chrome/launch! (constantly {:pid 12345 :port 9222 :process nil})
                    session/connect-to-existing! (constantly {:ok {} :browser fake-browser})]
        (let [result (subjects/init-persistent! :test-subject {:stealth true})]
          (is (= :connected (:status result)))
          (is (= :test-subject (:subject result)))
          (is (= 9222 (:port result)))
          (is (= 12345 (:pid result)))
          ;; Browser is wrapped in PersistentBrowser
          (is (instance? shiftlefter.subjects.browser.PersistentBrowser (:browser result)))
          (is (= fake-browser (pbrowser/underlying-browser (:browser result))))
          ;; Profile should exist with metadata
          (is (profile/profile-exists? :test-subject))
          (let [meta (profile/load-browser-meta :test-subject)]
            (is (= 9222 (:debug-port meta)))
            (is (= 12345 (:chrome-pid meta)))
            (is (true? (:stealth meta)))))))))

(deftest connect-persistent-chrome-alive-test
  (testing "connect-persistent! connects to alive Chrome"
    ;; Setup: create profile with metadata
    (profile/ensure-dirs! :alive-subject)
    (profile/save-browser-meta! :alive-subject
                                {:debug-port 9222
                                 :chrome-pid 12345
                                 :stealth false
                                 :user-data-dir "/tmp/test"})
    (let [fake-browser {:type :fake}]
      (with-redefs [chrome/probe-cdp (constantly {:status :alive})
                    session/connect-to-existing! (constantly {:ok {} :browser fake-browser})]
        (let [result (subjects/connect-persistent! :alive-subject)]
          (is (= :connected (:status result)))
          (is (= :alive-subject (:subject result)))
          (is (= 9222 (:port result)))
          ;; Browser is wrapped in PersistentBrowser
          (is (instance? shiftlefter.subjects.browser.PersistentBrowser (:browser result)))
          (is (= fake-browser (pbrowser/underlying-browser (:browser result)))))))))

(deftest connect-persistent-chrome-dead-relaunch-test
  (testing "connect-persistent! relaunches dead Chrome"
    ;; Setup: create profile with metadata
    (profile/ensure-dirs! :dead-subject)
    (profile/save-browser-meta! :dead-subject
                                {:debug-port 9222
                                 :chrome-pid 99999
                                 :stealth true
                                 :user-data-dir "/tmp/test"})
    (let [fake-browser {:type :fake}]
      (with-redefs [chrome/probe-cdp (constantly {:status :dead})
                    chrome/launch! (constantly {:pid 55555 :port 9222 :process nil})
                    session/connect-to-existing! (constantly {:ok {} :browser fake-browser})]
        (let [result (subjects/connect-persistent! :dead-subject)]
          (is (= :connected (:status result)))
          (is (= :dead-subject (:subject result)))
          (is (= 55555 (:pid result)) "PID should be updated")
          ;; Browser is wrapped in PersistentBrowser
          (is (instance? shiftlefter.subjects.browser.PersistentBrowser (:browser result)))
          (is (= fake-browser (pbrowser/underlying-browser (:browser result))))
          ;; Metadata should be updated with new PID
          (let [meta (profile/load-browser-meta :dead-subject)]
            (is (= 55555 (:chrome-pid meta)))))))))

(deftest destroy-persistent-success-test
  (testing "destroy-persistent! kills Chrome and deletes profile"
    ;; Setup: create profile with metadata
    (profile/ensure-dirs! :doomed)
    (profile/save-browser-meta! :doomed {:debug-port 9222 :chrome-pid 12345})

    (let [killed-pids (atom [])]
      (with-redefs [chrome/kill-by-pid! (fn [pid]
                                          (swap! killed-pids conj pid)
                                          {:killed true :pid pid})]
        (let [result (subjects/destroy-persistent! :doomed)]
          (is (= :destroyed (:status result)))
          (is (= :doomed (:subject result)))
          (is (= [12345] @killed-pids))
          ;; Profile should be gone
          (is (not (profile/profile-exists? :doomed))))))))

(deftest list-persistent-with-subjects-test
  (testing "list-persistent returns subject statuses"
    ;; Setup: create some profiles
    (profile/ensure-dirs! :alpha)
    (profile/save-browser-meta! :alpha {:debug-port 9222 :chrome-pid 111})

    (profile/ensure-dirs! :beta)
    (profile/save-browser-meta! :beta {:debug-port 9223 :chrome-pid 222})

    (with-redefs [chrome/probe-cdp (fn [port _]
                                     (if (= 9222 port)
                                       {:status :alive}
                                       {:status :dead}))]
      (let [result (subjects/list-persistent)]
        (is (= 2 (count result)))
        (let [alpha (first (filter #(= "alpha" (:name %)) result))
              beta (first (filter #(= "beta" (:name %)) result))]
          (is (= :alive (:status alpha)))
          (is (= 9222 (:port alpha)))
          (is (= :dead (:status beta)))
          (is (= 9223 (:port beta))))))))

;; -----------------------------------------------------------------------------
;; Kill-by-PID Tests
;; -----------------------------------------------------------------------------

(deftest kill-by-pid-not-running-test
  (testing "kill-by-pid! returns not-running for nonexistent PID"
    (let [result (chrome/kill-by-pid! 999999999)]
      (is (false? (:killed result)))
      (is (= :not-running (:reason result))))))
