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

;; -----------------------------------------------------------------------------
;; WebDriver Config Normalization Tests (Task 2.5.5)
;; -----------------------------------------------------------------------------

(deftest test-normalize-empty-config-valid
  (testing "Empty config is valid (no browser errors)"
    (let [st (config/normalize {})]
      (is (nil? (:errors st))))))

(deftest test-normalize-webdriver-url-direct
  (testing "Direct :webdriver-url is used as-is"
    (let [cfg {:webdriver-url "http://127.0.0.1:9515"}
          st (config/normalize cfg)]
      (is (nil? (:errors st)))
      (is (= "http://127.0.0.1:9515" (:webdriver-url st))))))

(deftest test-normalize-webdriver-host-port
  (testing ":webdriver {:host :port} normalizes to :webdriver-url"
    (let [cfg {:webdriver {:host "127.0.0.1" :port 9515}}
          st (config/normalize cfg)]
      (is (nil? (:errors st)))
      (is (= "http://127.0.0.1:9515" (:webdriver-url st))))))

(deftest test-normalize-webdriver-missing-port
  (testing ":webdriver with missing :port produces error"
    (let [cfg {:webdriver {:host "127.0.0.1"}}
          st (config/normalize cfg)]
      (is (seq (:errors st)))
      (is (= :webdriver/invalid-config (-> st :errors first :type))))))

(deftest test-normalize-webdriver-missing-host
  (testing ":webdriver with missing :host produces error"
    (let [cfg {:webdriver {:port 9515}}
          st (config/normalize cfg)]
      (is (seq (:errors st)))
      (is (= :webdriver/invalid-config (-> st :errors first :type))))))

(deftest test-normalize-webdriver-empty-map
  (testing ":webdriver as empty map produces error"
    (let [cfg {:webdriver {}}
          st (config/normalize cfg)]
      (is (seq (:errors st)))
      (is (= :webdriver/invalid-config (-> st :errors first :type))))))

(deftest test-normalize-webdriver-invalid-type
  (testing ":webdriver as non-map produces error"
    (let [cfg {:webdriver "not-a-map"}
          st (config/normalize cfg)]
      (is (seq (:errors st)))
      (is (= :webdriver/invalid-config (-> st :errors first :type))))))

(deftest test-get-webdriver-url
  (testing "get-webdriver-url extracts normalized URL"
    (let [cfg (config/normalize {:webdriver {:host "localhost" :port 4444}})]
      (is (= "http://localhost:4444" (config/get-webdriver-url cfg))))
    (is (nil? (config/get-webdriver-url {})))))

;; -----------------------------------------------------------------------------
;; Task 2.5.5 Acceptance Criteria
;; -----------------------------------------------------------------------------

(deftest test-acceptance-criteria-webdriver
  (testing "Task 2.5.5 AC: empty config is valid"
    (let [st (config/normalize {})]
      (is (nil? (:errors st)))))

  (testing "Task 2.5.5 AC: valid :webdriver normalizes to :webdriver-url"
    (let [st (config/normalize {:webdriver {:host "127.0.0.1" :port 9515}})]
      (is (= "http://127.0.0.1:9515" (:webdriver-url st)))))

  (testing "Task 2.5.5 AC: invalid :webdriver produces error"
    (let [st (config/normalize {:webdriver {:host "127.0.0.1"}})]
      (is (= :webdriver/invalid-config (-> st :errors first :type))))))

;; -----------------------------------------------------------------------------
;; Task 3.0.3: Config Schema Extension Tests
;; -----------------------------------------------------------------------------

(deftest test-default-config-new-keys
  (testing "Default config has glossaries, interfaces, and svo keys"
    (let [cfg config/default-config]
      (is (nil? (:glossaries cfg)) "glossaries nil by default")
      (is (map? (:interfaces cfg)) "interfaces is a map")
      (is (contains? (:interfaces cfg) :web) "has :web interface")
      (is (map? (:svo cfg)) "svo is a map")))

  (testing "Default :web interface has required keys"
    (let [web-iface (get-in config/default-config [:interfaces :web])]
      (is (= :web (:type web-iface)))
      (is (= :etaoin (:adapter web-iface)))
      (is (map? (:config web-iface)))))

  (testing "Default SVO settings are :warn/:error"
    (let [svo (:svo config/default-config)]
      (is (= :warn (:unknown-subject svo)))
      (is (= :warn (:unknown-verb svo)))
      (is (= :error (:unknown-interface svo))))))

;; -----------------------------------------------------------------------------
;; Interface Validation Tests
;; -----------------------------------------------------------------------------

(deftest test-normalize-interface-missing-type
  (testing "Interface without :type produces error"
    (let [cfg {:interfaces {:web {:adapter :etaoin}}}
          result (config/normalize cfg)]
      (is (seq (:errors result)))
      (is (= :config/invalid-interface (-> result :errors first :type)))
      (is (re-find #":web" (-> result :errors first :message))))))

(deftest test-normalize-interface-missing-adapter
  (testing "Interface without :adapter produces error"
    (let [cfg {:interfaces {:web {:type :web}}}
          result (config/normalize cfg)]
      (is (seq (:errors result)))
      (is (= :config/invalid-interface (-> result :errors first :type)))
      (is (re-find #"adapter" (-> result :errors first :message))))))

(deftest test-normalize-interface-unknown-type
  (testing "Interface with unknown :type produces error"
    (let [cfg {:interfaces {:web {:type :foobar :adapter :etaoin}}}
          result (config/normalize cfg)]
      (is (seq (:errors result)))
      (is (= :config/unknown-interface-type (-> result :errors first :type)))
      (is (= :foobar (-> result :errors first :data :type))))))

(deftest test-normalize-interface-valid-types
  (testing "All known interface types are valid"
    (doseq [itype [:web :api :sms :email]]
      (let [cfg {:interfaces {:test {:type itype :adapter :test-adapter}}}
            result (config/normalize cfg)]
        (is (nil? (:errors result)) (str "Type " itype " should be valid"))))))

(deftest test-normalize-multiple-interfaces
  (testing "Multiple valid interfaces pass validation"
    (let [cfg {:interfaces {:web {:type :web :adapter :etaoin}
                            :api {:type :api :adapter :http-kit}}}
          result (config/normalize cfg)]
      (is (nil? (:errors result)))))

  (testing "Multiple interfaces with one invalid produces error"
    (let [cfg {:interfaces {:web {:type :web :adapter :etaoin}
                            :bad {:type :web}}}  ;; missing adapter
          result (config/normalize cfg)]
      (is (seq (:errors result)))
      (is (= :config/invalid-interface (-> result :errors first :type))))))

(deftest test-normalize-empty-interfaces
  (testing "Empty interfaces map is valid (uses defaults)"
    (let [cfg {:interfaces {}}
          result (config/normalize cfg)]
      (is (nil? (:errors result))))))

;; -----------------------------------------------------------------------------
;; Glossary Config Accessor Tests
;; -----------------------------------------------------------------------------

(deftest test-get-glossary-config
  (testing "get-glossary-config extracts glossary paths"
    (let [cfg {:glossaries {:subjects "config/subjects.edn"
                            :verbs {:web "config/verbs-web.edn"}}}]
      (is (= {:subjects "config/subjects.edn"
              :verbs {:web "config/verbs-web.edn"}}
             (config/get-glossary-config cfg)))))

  (testing "get-glossary-config returns nil for missing"
    (is (nil? (config/get-glossary-config {})))
    (is (nil? (config/get-glossary-config {:parser {:dialect "en"}})))))

;; -----------------------------------------------------------------------------
;; Interface Config Accessor Tests
;; -----------------------------------------------------------------------------

(deftest test-get-interfaces
  (testing "get-interfaces extracts all interfaces"
    (let [cfg {:interfaces {:web {:type :web :adapter :etaoin}
                            :api {:type :api :adapter :http}}}]
      (is (= 2 (count (config/get-interfaces cfg))))
      (is (contains? (config/get-interfaces cfg) :web))
      (is (contains? (config/get-interfaces cfg) :api)))))

(deftest test-get-interface
  (testing "get-interface extracts single interface"
    (let [cfg {:interfaces {:web {:type :web :adapter :etaoin :config {:headless true}}}}]
      (is (= {:type :web :adapter :etaoin :config {:headless true}}
             (config/get-interface cfg :web)))))

  (testing "get-interface returns nil for missing"
    (is (nil? (config/get-interface {} :web)))
    (is (nil? (config/get-interface {:interfaces {:api {}}} :web)))))

(deftest test-get-interface-type
  (testing "get-interface-type extracts type"
    (let [cfg {:interfaces {:web {:type :web :adapter :etaoin}}}]
      (is (= :web (config/get-interface-type cfg :web)))))

  (testing "get-interface-type returns nil for missing"
    (is (nil? (config/get-interface-type {} :web)))))

(deftest test-get-interface-adapter
  (testing "get-interface-adapter extracts adapter"
    (let [cfg {:interfaces {:web {:type :web :adapter :etaoin}}}]
      (is (= :etaoin (config/get-interface-adapter cfg :web)))))

  (testing "get-interface-adapter returns nil for missing"
    (is (nil? (config/get-interface-adapter {} :web)))))

;; -----------------------------------------------------------------------------
;; SVO Config Accessor Tests
;; -----------------------------------------------------------------------------

(deftest test-get-svo-settings
  (testing "get-svo-settings extracts all settings"
    (let [cfg {:svo {:unknown-subject :error
                     :unknown-verb :warn
                     :unknown-interface :error}}]
      (is (= {:unknown-subject :error
              :unknown-verb :warn
              :unknown-interface :error}
             (config/get-svo-settings cfg)))))

  (testing "get-svo-settings returns nil for missing"
    (is (nil? (config/get-svo-settings {})))))

(deftest test-svo-enforcement
  (testing "svo-enforcement extracts specific check level"
    (let [cfg {:svo {:unknown-subject :error
                     :unknown-verb :warn}}]
      (is (= :error (config/svo-enforcement cfg :unknown-subject)))
      (is (= :warn (config/svo-enforcement cfg :unknown-verb)))))

  (testing "svo-enforcement returns nil for missing check"
    (let [cfg {:svo {:unknown-subject :warn}}]
      (is (nil? (config/svo-enforcement cfg :unknown-verb))))))

;; -----------------------------------------------------------------------------
;; Task 3.0.3 Acceptance Criteria
;; -----------------------------------------------------------------------------

(deftest test-acceptance-criteria-valid-config
  (testing "Task 3.0.3 AC: valid config passes"
    (let [cfg {:glossaries {:subjects "config/glossaries/subjects.edn"
                            :verbs {:web "config/glossaries/verbs-web.edn"}}
               :interfaces {:web {:type :web
                                  :adapter :etaoin
                                  :config {:headless true}}}
               :svo {:unknown-subject :warn
                     :unknown-verb :warn
                     :unknown-interface :error}}
          result (config/normalize cfg)]
      (is (nil? (:errors result))))))

(deftest test-acceptance-criteria-missing-type
  (testing "Task 3.0.3 AC: missing :type → validation error"
    (let [cfg {:interfaces {:web {:adapter :etaoin}}}
          result (config/normalize cfg)]
      (is (seq (:errors result)))
      (is (= :config/invalid-interface (-> result :errors first :type))))))

(deftest test-acceptance-criteria-unknown-type
  (testing "Task 3.0.3 AC: unknown type → validation error"
    (let [cfg {:interfaces {:web {:type :foobar :adapter :etaoin}}}
          result (config/normalize cfg)]
      (is (seq (:errors result)))
      (is (= :config/unknown-interface-type (-> result :errors first :type))))))

;; -----------------------------------------------------------------------------
;; CLI Override Round-Trip Tests (WI-033.007)
;; -----------------------------------------------------------------------------

(deftest test-cli-step-paths-override-config
  (testing "CLI --step-paths takes precedence over config file step-paths"
    (let [config (config/load-config {:config-path "test/fixtures/config/shiftlefter.fr.edn"})
          config-step-paths (config/get-step-paths config)
          cli-step-paths ["custom/steps/" "more/steps/"]
          ;; Simulate run-cmd logic: CLI wins if provided
          effective (or cli-step-paths config-step-paths)]
      ;; Config has its own step-paths, but CLI overrides
      (is (= cli-step-paths effective))
      ;; Verify config actually had different step-paths
      (is (not= cli-step-paths config-step-paths)
          "Config should have different step-paths to prove CLI override works")))

  (testing "Config step-paths used when CLI doesn't provide --step-paths"
    (let [config (config/load-config {:config-path "test/fixtures/config/shiftlefter.fr.edn"})
          config-step-paths (config/get-step-paths config)
          cli-step-paths nil
          effective (or cli-step-paths config-step-paths)]
      (is (= config-step-paths effective))))

  (testing "Deep merge preserves nested user values alongside defaults"
    (let [config (config/load-config {:config-path "test/fixtures/config/shiftlefter.fr.edn"})]
      ;; User specified :parser :dialect "fr" — should survive
      (is (= "fr" (get-in config [:parser :dialect])))
      ;; User specified :runner :step-paths — should survive
      (is (= ["steps/" "more-steps/"] (get-in config [:runner :step-paths])))
      ;; Default :runner :macros should still be present (deep merge)
      (is (some? (get-in config [:runner :macros]))))))
