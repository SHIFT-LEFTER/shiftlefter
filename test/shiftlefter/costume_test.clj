(ns shiftlefter.costume-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [shiftlefter.costume :as costume]
            [shiftlefter.costume.wardrobe :as wardrobe]
            [shiftlefter.costume.browser :as cbrowser]
            [shiftlefter.browser.chrome :as chrome]
            [shiftlefter.config.user :as user-config]
            [shiftlefter.webdriver.etaoin.session :as session]
            [babashka.fs :as fs]))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(def ^:dynamic *test-wardrobe* nil)

(defn with-temp-wardrobe
  "Fixture that redirects the wardrobe root to a temp directory outside the repo.

   Also stubs ensure-gitignored! to a no-op so the lifecycle tests never touch the
   repo's real .gitignore. The temp dir is outside the repo and untracked, so the
   git-tracked guard does not trip."
  [f]
  (let [temp-dir (str (fs/create-temp-dir {:prefix "sl-costume-test-"}))]
    (try
      (with-redefs [wardrobe/wardrobe-dir (constantly temp-dir)
                    wardrobe/ensure-gitignored! (constantly nil)]
        (binding [*test-wardrobe* temp-dir]
          (f)))
      (finally
        (fs/delete-tree temp-dir)))))

(use-fixtures :each with-temp-wardrobe)

(def full-meta
  {:debug-port 9222
   :chrome-pid 12345
   :user-data-dir "/tmp/test"})

;; -----------------------------------------------------------------------------
;; Error Handling Tests (no Chrome needed)
;; -----------------------------------------------------------------------------

(deftest init-costume-already-exists-test
  (testing "init-costume! returns error if costume exists"
    ;; Create costume directory manually
    (wardrobe/ensure-dirs! :existing)
    (wardrobe/save-costume-meta! :existing full-meta)

    (let [result (costume/init-costume! :existing)]
      (is (:error result))
      (is (= :costume/already-exists (get-in result [:error :type]))))))

(deftest connect-costume-not-found-test
  (testing "connect-costume! returns error if costume not found"
    (let [result (costume/connect-costume! :nonexistent)
          searched (wardrobe/costume-dir :nonexistent)]
      (is (:error result))
      (is (= :costume/not-found (get-in result [:error :type])))
      ;; Loud misplacement: the searched wardrobe path is surfaced
      (is (= searched (get-in result [:error :data :searched-path])))
      (is (str/includes? (get-in result [:error :message]) searched)))))

(deftest destroy-costume-not-found-test
  (testing "destroy-costume! returns error if costume not found"
    (let [result (costume/destroy-costume! :nonexistent)
          searched (wardrobe/costume-dir :nonexistent)]
      (is (:error result))
      (is (= :costume/not-found (get-in result [:error :type])))
      (is (= searched (get-in result [:error :data :searched-path])))
      (is (str/includes? (get-in result [:error :message]) searched)))))

(deftest list-costumes-empty-test
  (testing "list-costumes returns empty vector when no costumes"
    (is (= [] (costume/list-costumes)))))

;; -----------------------------------------------------------------------------
;; Git Safety Rail Tests
;; -----------------------------------------------------------------------------

(deftest init-costume-refuses-git-tracked-test
  (testing "init-costume! refuses to operate on a git-tracked costume dir"
    (with-redefs [wardrobe/git-tracked? (constantly true)]
      (let [result (costume/init-costume! :tracked)]
        (is (:error result))
        (is (= :costume/git-tracked (get-in result [:error :type])))
        (is (re-find #"git-tracked" (get-in result [:error :message])))))))

(deftest connect-costume-refuses-git-tracked-test
  (testing "connect-costume! refuses to operate on a git-tracked costume dir"
    (with-redefs [wardrobe/git-tracked? (constantly true)]
      (let [result (costume/connect-costume! :tracked)]
        (is (:error result))
        (is (= :costume/git-tracked (get-in result [:error :type])))))))

;; -----------------------------------------------------------------------------
;; Mocked Integration Tests
;; -----------------------------------------------------------------------------

(deftest init-costume-port-allocation-failure-test
  (testing "init-costume! returns error on port allocation failure"
    (with-redefs [chrome/allocate-port (constantly {:errors [{:type :persistent/no-port-available}]})]
      (let [result (costume/init-costume! :test-costume)]
        (is (:error result))
        (is (= :costume/init-failed (get-in result [:error :type])))))))

(deftest init-costume-launch-failure-test
  (testing "init-costume! returns error on Chrome launch failure"
    (with-redefs [chrome/allocate-port (constantly 9222)
                  chrome/launch! (constantly {:errors [{:type :persistent/chrome-launch-failed}]})]
      (let [result (costume/init-costume! :test-costume)]
        (is (:error result))
        (is (= :costume/init-failed (get-in result [:error :type])))
        ;; Costume should be cleaned up
        (is (not (wardrobe/costume-exists? :test-costume)))))))

(deftest init-costume-success-test
  (testing "init-costume! launches Chrome WITHOUT attaching a WebDriver session"
    (let [connect-called (atom false)]
      (with-redefs [chrome/allocate-port (constantly 9222)
                    chrome/launch! (constantly {:pid 12345 :port 9222 :process nil})
                    session/connect-to-existing! (fn [_]
                                                   (reset! connect-called true)
                                                   {:ok {} :browser {:type :fake}})]
        (let [result (costume/init-costume! :test-costume)]
          (is (= :launched (:status result)))
          (is (= :test-costume (:costume result)))
          (is (= 9222 (:port result)))
          (is (= 12345 (:pid result)))
          ;; Launch-only: no browser handed back, no session spawned at init
          (is (not (contains? result :browser)) "init must not return a :browser")
          (is (false? @connect-called) "init must not attach a WebDriver session")
          ;; Costume should exist with metadata
          (is (wardrobe/costume-exists? :test-costume))
          (let [meta (wardrobe/load-costume-meta :test-costume)]
            (is (= 9222 (:debug-port meta)))
            (is (= 12345 (:chrome-pid meta)))))))))

(deftest connect-costume-chrome-alive-test
  (testing "connect-costume! connects to alive Chrome"
    ;; Setup: create costume with metadata
    (wardrobe/ensure-dirs! :alive-costume)
    (wardrobe/save-costume-meta! :alive-costume
                                 {:debug-port 9222
                                  :chrome-pid 12345
                                  :user-data-dir "/tmp/test"})
    (let [fake-browser {:type :fake}]
      (with-redefs [chrome/probe-cdp (constantly {:status :alive})
                    session/connect-to-existing! (constantly {:ok {} :browser fake-browser})]
        (let [result (costume/connect-costume! :alive-costume)]
          (is (= :connected (:status result)))
          (is (= :alive-costume (:costume result)))
          (is (= 9222 (:port result)))
          ;; Browser is wrapped in CostumeBrowser
          (is (instance? shiftlefter.costume.browser.CostumeBrowser (:browser result)))
          (is (= fake-browser (cbrowser/underlying-browser (:browser result)))))))))

(deftest connect-costume-chrome-dead-relaunch-test
  (testing "connect-costume! relaunches dead Chrome"
    ;; Setup: create costume with metadata
    (wardrobe/ensure-dirs! :dead-costume)
    (wardrobe/save-costume-meta! :dead-costume
                                 {:debug-port 9222
                                  :chrome-pid 99999
                                  :user-data-dir "/tmp/test"})
    (let [fake-browser {:type :fake}]
      (with-redefs [chrome/probe-cdp (constantly {:status :dead})
                    chrome/launch! (constantly {:pid 55555 :port 9222 :process nil})
                    session/connect-to-existing! (constantly {:ok {} :browser fake-browser})]
        (let [result (costume/connect-costume! :dead-costume)]
          (is (= :connected (:status result)))
          (is (= :dead-costume (:costume result)))
          (is (= 55555 (:pid result)) "PID should be updated")
          ;; Browser is wrapped in CostumeBrowser
          (is (instance? shiftlefter.costume.browser.CostumeBrowser (:browser result)))
          (is (= fake-browser (cbrowser/underlying-browser (:browser result))))
          ;; Metadata should be updated with new PID
          (let [meta (wardrobe/load-costume-meta :dead-costume)]
            (is (= 55555 (:chrome-pid meta)))))))))

(deftest destroy-costume-success-test
  (testing "destroy-costume! kills Chrome and deletes costume"
    ;; Setup: create costume with metadata
    (wardrobe/ensure-dirs! :doomed)
    (wardrobe/save-costume-meta! :doomed full-meta)

    (let [killed-pids (atom [])]
      (with-redefs [chrome/kill-by-pid! (fn [pid]
                                          (swap! killed-pids conj pid)
                                          {:killed true :pid pid})]
        (let [result (costume/destroy-costume! :doomed)]
          (is (= :destroyed (:status result)))
          (is (= :doomed (:costume result)))
          (is (= [12345] @killed-pids))
          ;; Costume should be gone
          (is (not (wardrobe/costume-exists? :doomed))))))))

(deftest list-costumes-with-costumes-test
  (testing "list-costumes returns costume statuses"
    ;; Setup: create some costumes
    (wardrobe/ensure-dirs! :alpha)
    (wardrobe/save-costume-meta! :alpha (assoc full-meta :debug-port 9222 :chrome-pid 111))

    (wardrobe/ensure-dirs! :beta)
    (wardrobe/save-costume-meta! :beta (assoc full-meta :debug-port 9223 :chrome-pid 222))

    (with-redefs [chrome/probe-cdp (fn [port _]
                                     (if (= 9222 port)
                                       {:status :alive}
                                       {:status :dead}))]
      (let [result (costume/list-costumes)]
        (is (= 2 (count result)))
        (let [alpha (first (filter #(= "alpha" (:name %)) result))
              beta (first (filter #(= "beta" (:name %)) result))]
          (is (= :alive (:status alpha)))
          (is (= 9222 (:port alpha)))
          (is (= :dead (:status beta)))
          (is (= 9223 (:port beta))))))))

;; -----------------------------------------------------------------------------
;; ChromeDriver Threading Tests (sl-sq4)
;; -----------------------------------------------------------------------------

(deftest connect-costume-threads-chromedriver-test
  (testing "connect-costume! passes the resolved chromedriver into connect-to-existing!"
    (wardrobe/ensure-dirs! :driver-alive)
    (wardrobe/save-costume-meta! :driver-alive
                                 {:debug-port 9222
                                  :chrome-pid 12345
                                  :user-data-dir "/tmp/test"})
    (let [captured (atom nil)
          fake-browser {:type :fake}]
      (with-redefs [chrome/probe-cdp (constantly {:status :alive})
                    user-config/resolve-chromedriver-path (constantly "/cfg/chromedriver")
                    session/connect-to-existing! (fn [opts]
                                                   (reset! captured opts)
                                                   {:ok {} :browser fake-browser})]
        (costume/connect-costume! :driver-alive)
        (is (= "/cfg/chromedriver" (:path-driver @captured)))
        (is (= 9222 (:port @captured)))))))

;; -----------------------------------------------------------------------------
;; Kill-by-PID Tests
;; -----------------------------------------------------------------------------

(deftest kill-by-pid-not-running-test
  (testing "kill-by-pid! returns not-running for nonexistent PID"
    (let [result (chrome/kill-by-pid! 999999999)]
      (is (false? (:killed result)))
      (is (= :not-running (:reason result))))))
