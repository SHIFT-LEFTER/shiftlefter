(ns shiftlefter.costume-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [shiftlefter.core]
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
                    chrome/port-listener-pids (constantly [])
                    session/connect-to-existing! (constantly {:ok {} :browser fake-browser})]
        (let [result (costume/connect-costume! :alive-costume)]
          (is (= :connected (:status result)))
          (is (= :alive-costume (:costume result)))
          (is (= 9222 (:port result)))
          ;; Port owner unresolvable — recorded pid is kept (sl-7q7w)
          (is (= 12345 (:pid result)))
          ;; Browser is wrapped in CostumeBrowser
          (is (instance? shiftlefter.costume.browser.CostumeBrowser (:browser result)))
          (is (= fake-browser (cbrowser/underlying-browser (:browser result)))))))))

(deftest connect-costume-attach-refreshes-stale-pid-test
  (testing "connect-costume! attach branch refreshes :chrome-pid from the port owner (sl-7q7w)"
    (wardrobe/ensure-dirs! :stale-pid)
    (wardrobe/save-costume-meta! :stale-pid
                                 {:debug-port 9223
                                  :chrome-pid 21574
                                  :user-data-dir "/tmp/test"})
    (with-redefs [chrome/probe-cdp (constantly {:status :alive})
                  chrome/port-listener-pids (constantly [30906])
                  session/connect-to-existing! (constantly {:ok {} :browser {:type :fake}})]
      (let [result (costume/connect-costume! :stale-pid)]
        (is (= :connected (:status result)))
        (is (= 30906 (:pid result)) "returned pid is the port's actual owner")
        (is (= 30906 (:chrome-pid (wardrobe/load-costume-meta :stale-pid)))
            "refreshed pid is persisted to browser-meta.edn")))))

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

;; -----------------------------------------------------------------------------
;; Destroy: port-ownership verification (sl-7q7w)
;; -----------------------------------------------------------------------------

(deftest destroy-costume-dead-browser-test
  (testing "destroy-costume! deletes a dead costume WITHOUT killing the recorded pid"
    ;; The recorded pid may have been reused by an unrelated process — when
    ;; nothing serves the port, no kill is attempted at all.
    (wardrobe/ensure-dirs! :doomed)
    (wardrobe/save-costume-meta! :doomed full-meta)

    (let [killed-pids (atom [])]
      (with-redefs [chrome/port-listener-pids (constantly [])
                    chrome/probe-cdp (constantly {:status :dead})
                    chrome/kill-by-pid! (fn [pid]
                                          (swap! killed-pids conj pid)
                                          {:killed true :pid pid})]
        (let [result (costume/destroy-costume! :doomed)]
          (is (= :destroyed (:status result)))
          (is (= :doomed (:costume result)))
          (is (= [] @killed-pids) "dead path must not kill the recorded pid")
          (is (not (wardrobe/costume-exists? :doomed))))))))

(deftest destroy-costume-refuses-live-browser-test
  (testing "destroy-costume! refuses while the browser is live, reporting the real pid"
    (wardrobe/ensure-dirs! :live)
    (wardrobe/save-costume-meta! :live
                                 {:debug-port 9223
                                  :chrome-pid 21574
                                  :user-data-dir "/tmp/test"})
    (let [killed-pids (atom [])]
      (with-redefs [chrome/port-listener-pids (constantly [30906])
                    chrome/probe-cdp (constantly {:status :alive})
                    chrome/kill-by-pid! (fn [pid]
                                          (swap! killed-pids conj pid)
                                          {:killed true :pid pid})]
        (let [result (costume/destroy-costume! :live)]
          (is (= :costume/browser-live (get-in result [:error :type])))
          (is (= 30906 (get-in result [:error :data :pid]))
              "reports the pid actually serving the port, not the recorded one")
          (is (= 21574 (get-in result [:error :data :recorded-pid])))
          (is (= 9223 (get-in result [:error :data :port])))
          (is (= [] @killed-pids) "refusal must not kill anything")
          (is (wardrobe/costume-exists? :live) "refusal must not delete the profile"))))))

(deftest destroy-costume-force-kills-port-owner-test
  (testing "destroy-costume! {:force true} kills the port owner, not the recorded pid"
    (wardrobe/ensure-dirs! :forced)
    (wardrobe/save-costume-meta! :forced
                                 {:debug-port 9223
                                  :chrome-pid 21574
                                  :user-data-dir "/tmp/test"})
    (let [killed-pids (atom [])
          live (atom true)]
      (with-redefs [chrome/port-listener-pids (fn [_] (if @live [30906] []))
                    chrome/probe-cdp (fn [& _] (if @live {:status :alive} {:status :dead}))
                    chrome/kill-by-pid! (fn [pid]
                                          (swap! killed-pids conj pid)
                                          (reset! live false)
                                          {:killed true :pid pid})]
        (let [result (costume/destroy-costume! :forced {:force true})]
          (is (= :destroyed (:status result)))
          (is (= [30906] @killed-pids) "kills the actual port owner, not 21574")
          (is (not (wardrobe/costume-exists? :forced))))))))

(deftest destroy-costume-force-kill-fails-test
  (testing "destroy-costume! keeps the profile when the browser survives a forced kill"
    (wardrobe/ensure-dirs! :survivor)
    (wardrobe/save-costume-meta! :survivor
                                 {:debug-port 9223
                                  :chrome-pid 21574
                                  :user-data-dir "/tmp/test"})
    (with-redefs [chrome/port-listener-pids (constantly [30906])
                  chrome/probe-cdp (constantly {:status :alive})
                  chrome/kill-by-pid! (constantly {:killed false :pid 30906 :reason :error})]
      (let [result (costume/destroy-costume! :survivor {:force true})]
        (is (= :costume/destroy-failed (get-in result [:error :type])))
        (is (wardrobe/costume-exists? :survivor)
            "profile must never be deleted under a still-live browser")))))

(deftest destroy-costume-meta-unreadable-test
  (testing "destroy-costume! refuses when browser-meta.edn is unreadable (no port to verify)"
    ;; Costume dir exists but the meta file is corrupt — liveness can't be
    ;; confirmed, so refusal without force; force deletes without killing.
    (wardrobe/ensure-dirs! :corrupt)
    (spit (str (fs/path *test-wardrobe* "corrupt" "browser-meta.edn")) "{:not-valid")
    (let [killed-pids (atom [])]
      (with-redefs [chrome/kill-by-pid! (fn [pid]
                                          (swap! killed-pids conj pid)
                                          {:killed true :pid pid})]
        (let [refused (costume/destroy-costume! :corrupt)]
          (is (= :costume/meta-unreadable (get-in refused [:error :type])))
          (is (wardrobe/costume-exists? :corrupt)))
        (let [forced (costume/destroy-costume! :corrupt {:force true})]
          (is (= :destroyed (:status forced)))
          (is (= [] @killed-pids) "nothing to kill without a port")
          (is (not (wardrobe/costume-exists? :corrupt))))))))

(deftest port-listener-pids-no-listener-test
  (testing "port-listener-pids returns [] when nothing listens on the port"
    ;; Real lsof against a port from the ephemeral range nothing binds in tests.
    (is (= [] (chrome/port-listener-pids 59999)))))

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
          (is (= 9223 (:port beta)))
          ;; sl-cdrg: the save-time stamp rides :meta through list-costumes —
          ;; the plumbing the CLI age column depends on.
          (is (string? (get-in alpha [:meta :last-connected-at]))))))))

;; -----------------------------------------------------------------------------
;; Last-Connected Age (sl-cdrg — F8 posture 1: staleness made visible)
;; -----------------------------------------------------------------------------

(deftest age-label-test
  ;; Literal clock injected — no wall-clock reaches any pinned expectation.
  (let [now (java.time.Instant/parse "2026-07-28T10:00:00Z")]
    (testing "unit boundaries, coarsest unit only"
      (is (= "<1m" (costume/age-label "2026-07-28T09:59:01Z" now)))
      (is (= "1m"  (costume/age-label "2026-07-28T09:59:00Z" now)))
      (is (= "59m" (costume/age-label "2026-07-28T09:01:00Z" now)))
      (is (= "1h"  (costume/age-label "2026-07-28T09:00:00Z" now)))
      (is (= "23h" (costume/age-label "2026-07-27T11:00:00Z" now)))
      (is (= "1d"  (costume/age-label "2026-07-27T10:00:00Z" now)))
      (is (= "45d" (costume/age-label "2026-06-13T10:00:00Z" now))))
    (testing "sub-second precision as save-costume-meta! actually writes it"
      (is (= "3d" (costume/age-label "2026-07-25T09:59:59.123456Z" now))))
    (testing "clock skew (future stamp) reads <1m, not negative"
      (is (= "<1m" (costume/age-label "2026-07-28T10:05:00Z" now))))
    (testing "nil and unparseable fail open to nil"
      (is (nil? (costume/age-label nil now)))
      (is (nil? (costume/age-label "not-a-timestamp" now))))))

(deftest costume-list-cmd-age-column-test
  (testing "the CLI listing renders the connected column, and - without a stamp"
    (wardrobe/ensure-dirs! :stamped)
    (wardrobe/save-costume-meta! :stamped full-meta)
    ;; Bypass save-time stamping: a legacy/hand-written meta with no
    ;; :last-connected-at is valid per ::costume-meta and must render "-".
    (wardrobe/ensure-dirs! :bare)
    (spit (str (fs/path *test-wardrobe* "bare" "browser-meta.edn"))
          (pr-str full-meta))
    (with-redefs [chrome/probe-cdp (constantly {:status :dead})]
      (let [out (with-out-str (#'shiftlefter.core/costume-list-cmd))
            [bare-line stamped-line] (->> (str/split-lines out)
                                          (sort))]
        (is (str/includes? stamped-line "stamped"))
        ;; Fresh stamp, seconds old at most:
        (is (str/includes? stamped-line "connected <1m ago"))
        (is (str/includes? bare-line "bare"))
        (is (str/ends-with? bare-line "connected -"))))))

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
                    chrome/port-listener-pids (constantly [])
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

;; -----------------------------------------------------------------------------
;; Relaunch Tests (sl-2de7) — launch-only reopen of an existing costume
;; -----------------------------------------------------------------------------

(deftest relaunch-costume-not-found-test
  (testing "relaunch-costume! returns not-found for nonexistent costume"
    (let [result (costume/relaunch-costume! :no-such)]
      (is (= :costume/not-found (get-in result [:error :type]))))))

(deftest relaunch-costume-refuses-git-tracked-test
  (testing "relaunch-costume! refuses a git-tracked costume dir"
    (with-redefs [wardrobe/git-tracked? (constantly true)]
      (let [result (costume/relaunch-costume! :tracked)]
        (is (= :costume/git-tracked (get-in result [:error :type])))))))

(deftest relaunch-costume-chrome-alive-test
  (testing "alive Chrome: idempotent :already-running, no launch, pid refreshed (sl-7q7w)"
    (wardrobe/ensure-dirs! :rl-alive)
    (wardrobe/save-costume-meta! :rl-alive full-meta)
    (let [launched (atom false)
          windowed (atom false)]
      (with-redefs [chrome/probe-cdp (constantly {:status :alive})
                    chrome/port-listener-pids (constantly [30906])
                    chrome/ensure-window! (fn [_] (reset! windowed true))
                    chrome/launch! (fn [_] (reset! launched true) {:pid 1 :port 1})]
        (let [result (costume/relaunch-costume! :rl-alive)]
          (is (= :already-running (:status result)))
          (is (= 9222 (:port result)))
          (is (= 30906 (:pid result)) "pid is the port's actual owner")
          (is (= 30906 (:chrome-pid (wardrobe/load-costume-meta :rl-alive)))
              "refreshed pid persisted")
          (is (true? @windowed) "human gets a window")
          (is (false? @launched) "no Chrome launch on the alive path"))))))

(deftest relaunch-costume-chrome-dead-test
  (testing "dead Chrome: relaunches against the persisted profile, records new pid"
    (wardrobe/ensure-dirs! :rl-dead)
    (wardrobe/save-costume-meta! :rl-dead full-meta)
    (let [launch-args (atom nil)]
      (with-redefs [chrome/probe-cdp (constantly {:status :dead})
                    chrome/ensure-window! (constantly nil)
                    chrome/launch! (fn [opts]
                                     (reset! launch-args opts)
                                     {:pid 77777 :port 9222 :process nil})]
        (let [result (costume/relaunch-costume! :rl-dead)]
          (is (= :launched (:status result)))
          (is (= 77777 (:pid result)))
          (is (= 9222 (:port @launch-args)) "launches on the recorded debug port")
          (is (= "/tmp/test" (:user-data-dir @launch-args)) "against the persisted profile")
          (is (= 77777 (:chrome-pid (wardrobe/load-costume-meta :rl-dead)))
              "new pid persisted — same save shape as connect's relaunch"))))))

(deftest relaunch-costume-launch-failure-test
  (testing "launch failure: :costume/relaunch-failed, meta unchanged"
    (wardrobe/ensure-dirs! :rl-fail)
    (wardrobe/save-costume-meta! :rl-fail full-meta)
    (with-redefs [chrome/probe-cdp (constantly {:status :dead})
                  chrome/launch! (constantly {:errors [{:type :persistent/launch-failed}]})]
      (let [result (costume/relaunch-costume! :rl-fail)]
        (is (= :costume/relaunch-failed (get-in result [:error :type])))
        (is (= 12345 (:chrome-pid (wardrobe/load-costume-meta :rl-fail)))
            "meta untouched on failure")))))

(deftest relaunch-costume-registry-untouched-test
  (testing "relaunch never touches the driver registry (launch-only, sl-34fu)"
    (wardrobe/ensure-dirs! :rl-reg)
    (wardrobe/save-costume-meta! :rl-reg full-meta)
    (let [registry-atom @#'costume/live-drivers
          stale {:fake-driver :stale}]
      (swap! registry-atom assoc :rl-reg stale)
      (try
        (with-redefs [chrome/probe-cdp (constantly {:status :dead})
                      chrome/ensure-window! (constantly nil)
                      chrome/launch! (constantly {:pid 88888 :port 9222 :process nil})
                      session/quit-driver! (fn [_] (throw (ex-info "must not reap" {})))]
          (is (= :launched (:status (costume/relaunch-costume! :rl-reg))))
          (is (identical? stale (get @registry-atom :rl-reg))
              "stale driver left for the next connect's adopt"))
        (finally
          (swap! registry-atom dissoc :rl-reg))))))

;; -----------------------------------------------------------------------------
;; Driver Ownership Tests (sl-34fu)
;;
;; The registry is a defonce atom — each test resets it so one JVM's test
;; order can't leak state between them. quit-driver! is captured, never
;; really called: these are fake driver maps.
;; -----------------------------------------------------------------------------

(def ^:private live-drivers-atom @#'costume/live-drivers)

(defn- reset-live-drivers! []
  (reset! live-drivers-atom {}))

(defn- alive-connect-stubs
  "with-redefs map fragment for a Chrome-alive connect whose spawned raw
   driver is served from `drivers` (an atom holding a vector; each connect
   pops the head), with reaps captured into `reaped`."
  [drivers reaped]
  {#'chrome/probe-cdp (constantly {:status :alive})
   #'chrome/port-listener-pids (constantly [])
   #'session/connect-to-existing!
   (fn [_]
     (let [[d & more] @drivers]
       (reset! drivers (vec more))
       {:ok {} :browser {:type :fake} :etaoin-driver d}))
   #'session/quit-driver!
   (fn [d] (swap! reaped conj d) {:ok :quit})})

(deftest connect-twice-reaps-replaced-driver-test
  (testing "a second connect-costume! reaps the first connect's driver (sl-34fu)"
    (reset-live-drivers!)
    (wardrobe/ensure-dirs! :reap-a)
    (wardrobe/save-costume-meta! :reap-a full-meta)
    (let [d1 {:fake-driver 1}
          d2 {:fake-driver 2}
          drivers (atom [d1 d2])
          reaped (atom [])]
      (with-redefs-fn (alive-connect-stubs drivers reaped)
        (fn []
          (is (= :connected (:status (costume/connect-costume! :reap-a))))
          (is (= [] @reaped) "first connect has nothing to reap")
          (is (= d1 (get @live-drivers-atom :reap-a)) "first driver adopted")
          (is (= :connected (:status (costume/connect-costume! :reap-a))))
          (is (= [d1] @reaped) "replaced driver reaped exactly once")
          (is (= d2 (get @live-drivers-atom :reap-a)) "new driver adopted, not reaped"))))))

(deftest failed-connect-keeps-prior-driver-test
  (testing "a FAILED re-connect neither reaps nor forgets the working prior driver"
    (reset-live-drivers!)
    (wardrobe/ensure-dirs! :reap-b)
    (wardrobe/save-costume-meta! :reap-b full-meta)
    (let [d1 {:fake-driver 1}
          drivers (atom [d1])
          reaped (atom [])]
      (with-redefs-fn (alive-connect-stubs drivers reaped)
        (fn []
          (is (= :connected (:status (costume/connect-costume! :reap-b))))))
      (with-redefs [chrome/probe-cdp (constantly {:status :alive})
                    chrome/port-listener-pids (constantly [])
                    session/connect-to-existing! (constantly {:error {:type :webdriver/connect-failed}})
                    session/quit-driver! (fn [d] (swap! reaped conj d) {:ok :quit})]
        (is (some? (:error (costume/connect-costume! :reap-b))))
        (is (= [] @reaped) "failed connect must not reap the prior")
        (is (= d1 (get @live-drivers-atom :reap-b)) "registry keeps the prior")))))

(deftest destroy-reaps-owned-driver-test
  (testing "destroy-costume! (browser dead) reaps and forgets the owned driver"
    (reset-live-drivers!)
    (wardrobe/ensure-dirs! :reap-c)
    (wardrobe/save-costume-meta! :reap-c full-meta)
    (let [d1 {:fake-driver 1}
          drivers (atom [d1])
          reaped (atom [])]
      (with-redefs-fn (alive-connect-stubs drivers reaped)
        (fn []
          (is (= :connected (:status (costume/connect-costume! :reap-c))))))
      (with-redefs [chrome/probe-cdp (constantly {:status :dead})
                    chrome/port-listener-pids (constantly [])
                    session/quit-driver! (fn [d] (swap! reaped conj d) {:ok :quit})]
        (is (= :destroyed (:status (costume/destroy-costume! :reap-c))))
        (is (= [d1] @reaped) "owned driver reaped on destroy")
        (is (not (contains? @live-drivers-atom :reap-c)) "registry forgets the costume")))))

(deftest destroy-failed-keeps-owned-driver-test
  (testing "a destroy that FAILS (browser survives the kill) keeps the driver handle"
    (reset-live-drivers!)
    (wardrobe/ensure-dirs! :reap-d)
    (wardrobe/save-costume-meta! :reap-d full-meta)
    (let [d1 {:fake-driver 1}
          drivers (atom [d1])
          reaped (atom [])]
      (with-redefs-fn (alive-connect-stubs drivers reaped)
        (fn []
          (is (= :connected (:status (costume/connect-costume! :reap-d))))))
      (with-redefs [chrome/probe-cdp (constantly {:status :alive})
                    chrome/port-listener-pids (constantly [11111])
                    chrome/kill-by-pid! (constantly {:killed true})
                    session/quit-driver! (fn [d] (swap! reaped conj d) {:ok :quit})]
        (is (= :costume/destroy-failed
               (get-in (costume/destroy-costume! :reap-d {:force true}) [:error :type])))
        (is (= [] @reaped) "no reap while the costume survives")
        (is (= d1 (get @live-drivers-atom :reap-d)) "registry keeps the handle")))))

(deftest reconnect-adopts-and-reaps-prior-test
  (testing "do-reconnect (the auto-reconnect path) adopts the new driver and reaps the prior"
    (reset-live-drivers!)
    (wardrobe/ensure-dirs! :reap-e)
    (wardrobe/save-costume-meta! :reap-e full-meta)
    (let [d1 {:fake-driver 1}
          d2 {:fake-driver 2}
          drivers (atom [d1 d2])
          reaped (atom [])]
      (with-redefs-fn (alive-connect-stubs drivers reaped)
        (fn []
          (is (= :connected (:status (costume/connect-costume! :reap-e))))
          (let [browser (#'costume/do-reconnect :reap-e)]
            (is (= {:type :fake} browser) "reconnect returns the raw browser")
            (is (= [d1] @reaped) "reconnect reaps the driver it replaces")
            (is (= d2 (get @live-drivers-atom :reap-e)) "reconnect's driver adopted")))))))

(deftest custody-snapshot-is-a-pure-read
  ;; sl-s35y (Warden W6 ruled): custody made visible. The snapshot reports
  ;; the registry without reaping or pinging — safe under `sl daemon status`.
  (reset-live-drivers!)
  (let [dead (let [p (.start (ProcessBuilder. ["true"]))] (.waitFor p) p)]
    (reset! live-drivers-atom {:zeta {:fake-driver 1}
                               :alpha {:process dead}})
    (is (= [{:costume :alpha :driver-alive? false}
            {:costume :zeta :driver-alive? nil}]
           (costume/custody-snapshot))
        "sorted; dead process reported false; unknown shape reported nil")
    (is (= 2 (count @live-drivers-atom)) "pure read — nothing reaped or forgotten")
    (reset-live-drivers!))
  (is (= [] (costume/custody-snapshot)) "empty registry → empty vector"))
