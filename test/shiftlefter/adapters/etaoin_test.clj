(ns shiftlefter.adapters.etaoin-test
  "Unit tests for the etaoin adapter: cleanup hardening (sl-9vag) and the
   automation-hygiene prefs merge (sl-0fco).

   The happy path (create → quit) is covered by the gated live e2e suites;
   these tests pin the wrong-shape behavior that used to leak browsers:
   close-browser handed a map without :etaoin-driver must report an error,
   never {:ok :closed} — a silent success here left chromedriver + Chrome
   orphans running for days."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.adapters.etaoin :as etaoin]))

(deftest close-browser-rejects-the-wrapped-map
  (testing "create-browser's wrapped {:ok {...}} result is the wrong shape"
    (let [wrapped {:ok {:browser :fake-browser :etaoin-driver :fake-driver}}
          result  (etaoin/close-browser wrapped)]
      (is (:error result) "wrapped map must NOT report success — nothing was quit")
      (is (= :adapter/cleanup-failed (-> result :error :type)))
      (is (= :etaoin (-> result :error :adapter))))))

(deftest close-browser-rejects-a-driverless-capability
  (testing "any capability without :etaoin-driver is an error, not silent green"
    (doseq [bad-shape [{} {:browser :fake-browser} nil]]
      (let [result (etaoin/close-browser bad-shape)]
        (is (= :adapter/cleanup-failed (-> result :error :type))
            (str "shape: " (pr-str bad-shape)))))))

;; -----------------------------------------------------------------------------
;; Automation-hygiene prefs merge (sl-0fco) — build-driver-opts is pure, so
;; the merge semantics are pinned without launching Chrome.
;; -----------------------------------------------------------------------------

(deftest hygiene-prefs-present-with-bare-config
  (testing "a config with no :adapter-opts gets the full password trio"
    (let [opts (etaoin/build-driver-opts {} nil)]
      (is (= etaoin/automation-hygiene-prefs (:prefs opts)))
      (is (= :chrome (:type opts)))
      (is (false? (:headless opts))))))

(deftest hygiene-prefs-user-override-wins-per-key
  (testing "a user pref overriding a default key wins; other defaults survive"
    (let [opts (etaoin/build-driver-opts
                {:adapter-opts {:prefs {"profile.password_manager_leak_detection" true}}}
                nil)]
      (is (true? (get-in opts [:prefs "profile.password_manager_leak_detection"]))
          "user opt-back-in beats the default")
      (is (false? (get-in opts [:prefs "credentials_enable_service"]))
          "untouched defaults still present")
      (is (false? (get-in opts [:prefs "profile.password_manager_enabled"]))))))

(deftest hygiene-prefs-coexist-with-unrelated-user-prefs
  (testing "user prefs on other keys merge alongside the defaults"
    (let [opts (etaoin/build-driver-opts
                {:adapter-opts {:prefs {"download.default_directory" "/tmp"}}}
                nil)]
      (is (= "/tmp" (get-in opts [:prefs "download.default_directory"])))
      (is (= (set (keys etaoin/automation-hygiene-prefs))
             (disj (set (keys (:prefs opts))) "download.default_directory"))
          "all three defaults ride along"))))

(deftest hygiene-prefs-leave-other-adapter-opts-untouched
  (testing "non-prefs adapter-opts pass through; path-driver threads when given"
    (let [opts (etaoin/build-driver-opts
                {:headless true
                 :adapter-opts {:size {:width 1280 :height 720}}}
                "/fake/chromedriver")]
      (is (= {:width 1280 :height 720} (:size opts)))
      (is (true? (:headless opts)))
      (is (= "/fake/chromedriver" (:path-driver opts)))
      (is (= etaoin/automation-hygiene-prefs (:prefs opts))))))
