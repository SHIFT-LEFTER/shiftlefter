(ns shiftlefter.runner.setup-test
  "Unit tests for the setup.clj convention loader."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.setup :as setup]))

;; -----------------------------------------------------------------------------
;; Temp-dir helpers
;; -----------------------------------------------------------------------------

(defn- with-temp-dir [f]
  (let [dir (str (fs/create-temp-dir {:prefix "sl-setup-test-"}))]
    (try
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(defn- spit-file
  "Write `content` to `dir/relative`. Returns the realpath-normalized
   absolute path — matches what `discover/discover-feature-files` produces,
   so the two are directly comparable."
  [dir relative content]
  (let [target (fs/path dir relative)]
    (fs/create-dirs (fs/parent target))
    (spit (str target) content)
    (str (fs/real-path target))))

;; -----------------------------------------------------------------------------
;; find-setup-file
;; -----------------------------------------------------------------------------

(deftest find-setup-file-resolves-sibling
  (testing "Returns sibling setup.clj when present"
    (with-temp-dir
      (fn [dir]
        (let [config (spit-file dir "shiftlefter.edn" "{}")
              setup  (spit-file dir "setup.clj" "(ns setup)\n(def setups [])")]
          (is (= (str (fs/absolutize setup))
                 (setup/find-setup-file config))))))))

(deftest find-setup-file-returns-nil-when-absent
  (testing "Returns nil when no setup.clj sibling"
    (with-temp-dir
      (fn [dir]
        (let [config (spit-file dir "shiftlefter.edn" "{}")]
          (is (nil? (setup/find-setup-file config))))))))

(deftest find-setup-file-nil-when-no-config-path
  (testing "Returns nil when config-path is nil (built-in defaults)"
    (is (nil? (setup/find-setup-file nil)))))

;; -----------------------------------------------------------------------------
;; load-setup
;; -----------------------------------------------------------------------------

(deftest load-setup-happy-path-var
  (testing "Loads a setup.clj where `setups` is a vector var"
    (with-temp-dir
      (fn [dir]
        (let [setup-path (spit-file dir "setup.clj"
                                    "(ns setup)
                                     (defn- demo-start [_] {})
                                     (def setups
                                       [{:label \"demo\"
                                         :start demo-start
                                         :features [\"features/foo.feature\"]}])")
              {:keys [ok error]} (setup/load-setup setup-path {})]
          (is (nil? error))
          (is (vector? ok))
          (is (= 1 (count ok)))
          (is (= "demo" (-> ok first :label))))))))

(deftest load-setup-happy-path-fn
  (testing "Loads a setup.clj where `setups` is a (fn [config])"
    (with-temp-dir
      (fn [dir]
        (let [setup-path (spit-file dir "setup.clj"
                                    "(ns setup)
                                     (defn setups [_config]
                                       [{:label \"fn-shaped\"
                                         :start (fn [_] {})
                                         :features [\"a.feature\"]}])")
              {:keys [ok error]} (setup/load-setup setup-path {:dummy true})]
          (is (nil? error))
          (is (= "fn-shaped" (-> ok first :label))))))))

(deftest load-setup-rejects-missing-setups-var
  (testing "Setup file without `setups` produces a structured error"
    (with-temp-dir
      (fn [dir]
        (let [setup-path (spit-file dir "setup.clj" "(ns setup)\n(def something-else 1)")
              {:keys [error]} (setup/load-setup setup-path {})]
          (is (some? error))
          (is (= :setup/load-failed (:type error))))))))

(deftest load-setup-rejects-malformed-setups
  (testing "Spec failure on bad shape returns :setup/invalid-shape"
    (with-temp-dir
      (fn [dir]
        (let [setup-path (spit-file dir "setup.clj"
                                    "(ns setup)\n(def setups [{:no-start true}])")
              {:keys [error]} (setup/load-setup setup-path {})]
          (is (= :setup/invalid-shape (:type error)))
          (is (some? (:explain error))))))))

(deftest load-setup-syntax-error-surfaces
  (testing "Syntax error in setup.clj surfaces as :setup/load-failed"
    (with-temp-dir
      (fn [dir]
        (let [setup-path (spit-file dir "setup.clj" "(ns setup)\n(def setups [{:start")
              {:keys [error]} (setup/load-setup setup-path {})]
          (is (= :setup/load-failed (:type error))))))))

;; -----------------------------------------------------------------------------
;; expand-group-features + declared-feature-set
;; -----------------------------------------------------------------------------

(deftest expand-group-features-resolves-paths
  (testing "Per-group feature resolution returns absolute paths in declared order"
    (with-temp-dir
      (fn [dir]
        (let [a (spit-file dir "features/a.feature" "Feature: A\n")
              b (spit-file dir "features/b.feature" "Feature: B\n")
              entry {:start (fn [_])
                     :features [a b]}
              expanded (setup/expand-group-features entry)]
          (is (= 2 (count expanded)))
          ;; absolute paths
          (is (every? #(.startsWith ^String % "/") expanded)))))))

(deftest declared-feature-set-unions-across-groups
  (testing "Union of all groups' features"
    (with-temp-dir
      (fn [dir]
        (let [a (spit-file dir "features/a.feature" "Feature: A\n")
              b (spit-file dir "features/b.feature" "Feature: B\n")
              setups [{:start (fn [_]) :features [a]}
                      {:start (fn [_]) :features [b]}]
              union  (setup/declared-feature-set setups)]
          (is (= 2 (count union))))))))

;; -----------------------------------------------------------------------------
;; validate-cli-paths
;; -----------------------------------------------------------------------------

(deftest validate-cli-paths-passes-when-subset
  (testing "CLI path that's in the declared union passes"
    (with-temp-dir
      (fn [dir]
        (let [a (spit-file dir "features/a.feature" "Feature: A\n")
              setups [{:start (fn [_]) :features [a]}]
              {:keys [ok error]} (setup/validate-cli-paths [a] setups)]
          (is (nil? error))
          (is (= 1 (count ok))))))))

(deftest validate-cli-paths-fails-when-not-subset
  (testing "CLI path outside declared set returns planning error"
    (with-temp-dir
      (fn [dir]
        (let [a (spit-file dir "features/a.feature" "Feature: A\n")
              b (spit-file dir "features/b.feature" "Feature: B\n")
              setups [{:start (fn [_]) :features [a]}]
              {:keys [error]} (setup/validate-cli-paths [b] setups)]
          (is (= :setup/cli-path-not-declared (:type error)))
          (is (= 1 (count (:unknown error)))))))))

(deftest validate-cli-paths-empty-cli-passes
  (testing "Empty CLI paths returns ok with empty vector (full-suite run)"
    (let [setups [{:start (fn [_]) :features ["nope"]}]
          {:keys [ok error]} (setup/validate-cli-paths [] setups)]
      (is (nil? error))
      (is (= [] ok)))))

;; -----------------------------------------------------------------------------
;; filter-setups-by-cli-paths
;; -----------------------------------------------------------------------------

(deftest filter-setups-keeps-all-when-cli-empty
  (testing "Empty CLI selection means full suite — all groups preserved with their resolved features"
    (with-temp-dir
      (fn [dir]
        (let [a (spit-file dir "features/a.feature" "Feature: A\n")
              setups [{:start (fn [_]) :features [a]}]
              kept (setup/filter-setups-by-cli-paths setups [])]
          (is (= 1 (count kept)))
          (is (= [a] (setup/resolve-group-features (first kept)))))))))

(deftest filter-setups-narrows-to-matching-groups
  (testing "Groups with no matching CLI features are dropped"
    (with-temp-dir
      (fn [dir]
        (let [a (spit-file dir "features/a.feature" "Feature: A\n")
              b (spit-file dir "features/b.feature" "Feature: B\n")
              setups [{:label "g1" :start (fn [_]) :features [a]}
                      {:label "g2" :start (fn [_]) :features [b]}]
              kept (setup/filter-setups-by-cli-paths setups [a])]
          (is (= 1 (count kept)))
          (is (= "g1" (:label (first kept))))
          (is (= [a] (setup/resolve-group-features (first kept)))))))))

;; -----------------------------------------------------------------------------
;; lint-setups — plan-shape lints (sl-lkla)
;; red side FIRST (guard-family law): every miss class must fire
;; -----------------------------------------------------------------------------

(defn- start-noop [_config] {})

(deftest lint-dead-group-literal-path-fires
  (testing "A group whose :features name a nonexistent file is a plan ERROR"
    (with-temp-dir
      (fn [dir]
        (spit-file dir "features/password_reset_sms.feature" "Feature: SMS 2FA\n")
        (let [setups [{:label "sms-2fa"
                       :start start-noop
                       :features ["features/password_reset_sms.feature"]}
                      {:label "checkout"
                       :start start-noop
                       :features ["features/checkout.feature"]}]
              {:keys [errors warnings]} (setup/lint-setups setups dir)]
          (is (empty? warnings))
          (is (= 1 (count errors)))
          (let [err (first errors)]
            (is (= :setup/empty-features (:type err)))
            (is (= "checkout" (:group err)))
            (is (= ["features/checkout.feature"] (:features err)))
            (is (str/includes? (:message err) "match no feature files"))))))))

(deftest lint-dead-group-glob-fires
  (testing "A glob matching nothing is the same lie as a dead literal path"
    (with-temp-dir
      (fn [dir]
        (spit-file dir "features/notes.txt" "not a feature\n")
        (let [setups [{:label "multi-user-web"
                       :start start-noop
                       :features ["features/multi-user/*.feature"]}]
              {:keys [errors]} (setup/lint-setups setups dir)]
          (is (= [:setup/empty-features] (mapv :type errors))))))))

(deftest lint-duplicate-label-fires
  (testing "Two groups sharing a :label is a plan ERROR naming both positions"
    (with-temp-dir
      (fn [dir]
        (spit-file dir "features/a.feature" "Feature: A\n")
        (spit-file dir "features/b.feature" "Feature: B\n")
        (let [setups [{:label "sms-2fa" :start start-noop
                       :features ["features/a.feature"]}
                      {:label "sms-2fa" :start start-noop
                       :features ["features/b.feature"]}]
              {:keys [errors warnings]} (setup/lint-setups setups dir)]
          (is (empty? warnings))
          (is (= 1 (count errors)))
          (let [err (first errors)]
            (is (= :setup/duplicate-label (:type err)))
            (is (= "sms-2fa" (:label err)))
            (is (= [0 1] (:groups err)))
            (is (str/includes? (:message err) "reporting-load-bearing"))))))))

(deftest lint-shared-feature-warns-across-spellings
  (testing "The same file reached via file-spelling and glob-spelling warns, not errors"
    (with-temp-dir
      (fn [dir]
        (spit-file dir "features/two-users.feature" "Feature: Two users\n")
        (spit-file dir "features/chat.feature" "Feature: Chat\n")
        (let [setups [{:label "multi-user-web" :start start-noop
                       :features ["features/two-users.feature"]}
                      {:label "sms-2fa" :start start-noop
                       :features ["features/*.feature"]}]
              {:keys [errors warnings]} (setup/lint-setups setups dir)]
          (is (empty? errors) "a shared feature must never be an error")
          (is (= 1 (count warnings)) "only the SHARED file warns, not the glob's others")
          (let [warn (first warnings)]
            (is (= :setup/feature-in-multiple-groups (:type warn)))
            (is (str/ends-with? (:feature warn) "two-users.feature"))
            (is (= ["multi-user-web" "sms-2fa"] (:groups warn)))
            (is (str/includes? (:message warn) "deliberate"))))))))

;; green side — the shapes that must stay silent

(deftest lint-unlabeled-groups-are-not-duplicates
  (testing ":label is optional; two unlabeled groups are not a duplicate-label error"
    (with-temp-dir
      (fn [dir]
        (spit-file dir "features/a.feature" "Feature: A\n")
        (spit-file dir "features/b.feature" "Feature: B\n")
        (let [setups [{:start start-noop :features ["features/a.feature"]}
                      {:start start-noop :features ["features/b.feature"]}]
              {:keys [errors warnings]} (setup/lint-setups setups dir)]
          (is (empty? errors))
          (is (empty? warnings)))))))

(deftest lint-same-file-twice-in-one-group-is-not-cross-group
  (testing "A file declared twice by ONE group (entry + glob) is not a multi-group share"
    (with-temp-dir
      (fn [dir]
        (spit-file dir "features/a.feature" "Feature: A\n")
        (let [setups [{:label "one" :start start-noop
                       :features ["features/a.feature" "features/*.feature"]}]
              {:keys [warnings]} (setup/lint-setups setups dir)]
          (is (empty? warnings)))))))

(deftest lint-canonical-two-group-shape-is-clean
  (testing "The ns-docstring canonical shape lints clean"
    (with-temp-dir
      (fn [dir]
        (spit-file dir "features/multi-user/two-users.feature" "Feature: Two users\n")
        (spit-file dir "features/multi-user/alice-bob-chat.feature" "Feature: Chat\n")
        (spit-file dir "features/password_reset_sms.feature" "Feature: SMS reset\n")
        (let [setups [{:label "multi-user-web" :start start-noop
                       :features ["features/multi-user/two-users.feature"
                                  "features/multi-user/alice-bob-chat.feature"]}
                      {:label "sms-2fa" :start start-noop
                       :features ["features/password_reset_sms.feature"]}]]
          (is (= {:errors [] :warnings []} (setup/lint-setups setups dir))))))))
