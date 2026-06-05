(ns shiftlefter.runner.setup-test
  "Unit tests for the setup.clj convention loader."
  (:require [babashka.fs :as fs]
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
