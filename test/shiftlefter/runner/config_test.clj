(ns shiftlefter.runner.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.config :as config]))

;; -----------------------------------------------------------------------------
;; default-config Tests
;; -----------------------------------------------------------------------------

(deftest test-default-config-shape
  (testing "Default config has expected shape"
    (let [cfg config/default-config]
      (is (= "en" (get-in cfg [:parser :dialect])))
      (is (= ["steps/"] (get-in cfg [:runner :step-paths])))
      (is (= false (get-in cfg [:runner :allow-pending?])))
      (is (= false (get-in cfg [:runner :macros :enabled?]))))))

;; -----------------------------------------------------------------------------
;; load-config Tests
;; -----------------------------------------------------------------------------

(deftest test-load-config-explicit-path
  (testing "Explicit --config path is loaded"
    (let [cfg (config/load-config {:config-path "test/fixtures/config/shiftlefter.fr.edn"})]
      (is (= "fr" (get-in cfg [:parser :dialect])))
      (is (= ["steps/" "more-steps/"] (get-in cfg [:runner :step-paths])))
      (is (= true (get-in cfg [:runner :allow-pending?]))))))

(deftest test-load-config-merges-with-defaults
  (testing "Partial config merges with defaults"
    (let [cfg (config/load-config {:config-path "test/fixtures/config/minimal.edn"})]
      ;; Overridden value
      (is (= "de" (get-in cfg [:parser :dialect])))
      ;; Default values preserved
      (is (= ["steps/"] (get-in cfg [:runner :step-paths])))
      (is (= false (get-in cfg [:runner :allow-pending?]))))))

(deftest test-load-config-no-config-uses-defaults
  (testing "No config file uses defaults"
    ;; When no config file exists at default location, returns defaults
    ;; We test this by not providing a config-path and assuming no ./shiftlefter.edn
    ;; Since we can't control CWD easily, test the helper functions instead
    (is (= "en" (config/get-dialect config/default-config)))
    (is (= ["steps/"] (config/get-step-paths config/default-config)))
    (is (= false (config/allow-pending? config/default-config)))))

(deftest test-load-config-missing-explicit-path-throws
  (testing "Missing explicit config path throws"
    (is (thrown-with-msg? Exception #"not found"
          (config/load-config {:config-path "nonexistent/config.edn"})))))

;; -----------------------------------------------------------------------------
;; load-config-safe Tests
;; -----------------------------------------------------------------------------

(deftest test-load-config-safe-success
  (testing "Safe load returns :ok on success"
    (let [result (config/load-config-safe {:config-path "test/fixtures/config/minimal.edn"})]
      (is (= :ok (:status result)))
      (is (= "de" (get-in result [:config :parser :dialect]))))))

(deftest test-load-config-safe-error
  (testing "Safe load returns :error on failure"
    (let [result (config/load-config-safe {:config-path "nonexistent/config.edn"})]
      (is (= :error (:status result)))
      (is (= :config/not-found (:type result))))))

;; -----------------------------------------------------------------------------
;; Helper Function Tests
;; -----------------------------------------------------------------------------

(deftest test-get-dialect
  (testing "get-dialect extracts dialect"
    (is (= "fr" (config/get-dialect {:parser {:dialect "fr"}})))
    (is (= "en" (config/get-dialect {})))  ;; default
    (is (= "en" (config/get-dialect {:parser {}})))))  ;; default

(deftest test-get-step-paths
  (testing "get-step-paths extracts step paths"
    (is (= ["a/" "b/"] (config/get-step-paths {:runner {:step-paths ["a/" "b/"]}})))
    (is (= ["steps/"] (config/get-step-paths {})))  ;; default
    (is (= ["steps/"] (config/get-step-paths {:runner {}})))))  ;; default

(deftest test-allow-pending
  (testing "allow-pending? extracts allow-pending flag"
    (is (= true (config/allow-pending? {:runner {:allow-pending? true}})))
    (is (= false (config/allow-pending? {:runner {:allow-pending? false}})))
    (is (= false (config/allow-pending? {})))  ;; default
    (is (= false (config/allow-pending? {:runner {}})))))  ;; default

;; -----------------------------------------------------------------------------
;; Deep Merge Tests
;; -----------------------------------------------------------------------------

(deftest test-deep-merge-behavior
  (testing "Config merges deeply, not shallowly"
    (let [cfg (config/load-config {:config-path "test/fixtures/config/minimal.edn"})]
      ;; :parser was in file, but only :dialect was specified
      ;; Other :parser keys (if any) should come from defaults
      (is (map? (:parser cfg)))
      ;; :runner was NOT in file, so should be entirely from defaults
      (is (= ["steps/"] (get-in cfg [:runner :step-paths]))))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria
;; -----------------------------------------------------------------------------

(deftest test-acceptance-config-precedence
  (testing "Spec: explicit --config wins"
    (is (= "fr" (-> (config/load-config {:config-path "test/fixtures/config/shiftlefter.fr.edn"})
                    :parser :dialect)))))

;; -----------------------------------------------------------------------------
;; Macro Helper Tests
;; -----------------------------------------------------------------------------

(deftest test-macros-enabled
  (testing "macros-enabled? extracts macro enabled flag"
    (is (= true (config/macros-enabled? {:runner {:macros {:enabled? true}}})))
    (is (= false (config/macros-enabled? {:runner {:macros {:enabled? false}}})))
    (is (= false (config/macros-enabled? {})))  ;; default
    (is (= false (config/macros-enabled? {:runner {}})))))  ;; default

(deftest test-get-macro-registry-paths
  (testing "get-macro-registry-paths extracts registry paths"
    (is (= ["a.ini" "b.ini"]
           (config/get-macro-registry-paths {:runner {:macros {:registry-paths ["a.ini" "b.ini"]}}})))
    (is (= [] (config/get-macro-registry-paths {})))  ;; default
    (is (= [] (config/get-macro-registry-paths {:runner {:macros {}}})))))  ;; default

;; -----------------------------------------------------------------------------
;; Normalize / Validation Tests
;; -----------------------------------------------------------------------------

(deftest test-normalize-macros-disabled
  (testing "normalize passes when macros disabled"
    (let [cfg {:runner {:macros {:enabled? false}}}
          result (config/normalize cfg)]
      (is (nil? (:errors result)))
      (is (= false (get-in result [:runner :macros :enabled?]))))))

(deftest test-normalize-macros-enabled-with-paths
  (testing "normalize passes when macros enabled with registry-paths"
    (let [cfg {:runner {:macros {:enabled? true :registry-paths ["macros/auth.ini"]}}}
          result (config/normalize cfg)]
      (is (nil? (:errors result)))
      (is (= true (get-in result [:runner :macros :enabled?])))
      (is (= ["macros/auth.ini"] (get-in result [:runner :macros :registry-paths]))))))

(deftest test-normalize-macros-enabled-missing-paths
  (testing "normalize fails when macros enabled but registry-paths missing"
    (let [cfg {:runner {:macros {:enabled? true}}}
          result (config/normalize cfg)]
      (is (seq (:errors result)))
      (is (= :macro/config-missing-registry-paths (-> result :errors first :type))))))

(deftest test-normalize-macros-enabled-empty-paths
  (testing "normalize fails when macros enabled but registry-paths empty"
    (let [cfg {:runner {:macros {:enabled? true :registry-paths []}}}
          result (config/normalize cfg)]
      (is (seq (:errors result)))
      (is (= :macro/config-missing-registry-paths (-> result :errors first :type))))))

(deftest test-normalize-no-macros-key
  (testing "normalize passes when no macros key at all"
    (let [cfg {:runner {:step-paths ["steps/"]}}
          result (config/normalize cfg)]
      (is (nil? (:errors result))))))

;; -----------------------------------------------------------------------------
;; Task 2.2 Acceptance Criteria
;; -----------------------------------------------------------------------------

(deftest test-acceptance-criteria-macros-valid
  (testing "Task 2.2 AC: valid macro config"
    (let [cfg {:runner {:macros {:enabled? true :registry-paths ["macros/auth.ini"]}}}
          st (config/normalize cfg)]
      (is (true? (-> st :runner :macros :enabled?)))
      (is (= ["macros/auth.ini"] (-> st :runner :macros :registry-paths))))))

(deftest test-acceptance-criteria-macros-missing-paths
  (testing "Task 2.2 AC: macros enabled without paths"
    (let [cfg {:runner {:macros {:enabled? true}}}
          st (config/normalize cfg)]
      (is (= :macro/config-missing-registry-paths (-> st :errors first :type))))))
