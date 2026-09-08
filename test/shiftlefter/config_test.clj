(ns shiftlefter.config-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.paths :as paths]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.config :as config]
            [shiftlefter.runner.reporter :as reporter]))

;; -----------------------------------------------------------------------------
;; default-config Tests
;; -----------------------------------------------------------------------------

(deftest test-default-config-shape
  (testing "Default config has expected shape"
    (let [cfg config/default-config]
      (is (= "en" (get-in cfg [:parser :dialect])))
      (is (= ["steps/"] (get-in cfg [:runner :step-paths])))
      (is (= ["features/"] (get-in cfg [:runner :features])))
      (is (= false (get-in cfg [:runner :allow-pending?])))
      (is (= false (get-in cfg [:runner :macros :enabled?]))))))

(deftest get-features-default-test
  (is (= ["features/"] (config/get-features {})))
  (is (= ["custom/"] (config/get-features {:runner {:features ["custom/"]}}))))

;; -----------------------------------------------------------------------------
;; Conventional glossary defaults (sl-fcjm) — whole-map, existence-probed,
;; real-config path only
;; -----------------------------------------------------------------------------

(defn- with-sl-project
  "Temp project in sl/ layout: writes config-text at sl/shiftlefter.edn plus
   the given relative files under sl/, calls (f resolved-context), cleans up."
  [config-text rel-files f]
  (let [dir (str (fs/create-temp-dir))]
    (try
      (fs/create-dirs (fs/path dir "sl"))
      (spit (str (fs/path dir "sl" "shiftlefter.edn")) config-text)
      (doseq [[rel content] rel-files]
        (let [p (fs/path dir "sl" rel)]
          (fs/create-dirs (fs/parent p))
          (spit (str p) content)))
      (f (project-context/resolve {:invocation-root dir}))
      (finally
        (fs/delete-tree dir)))))

(deftest conventional-glossaries-probed-when-unset
  (with-sl-project "{}"
    {"glossary/subjects.edn" "{:subjects {}}"
     "glossary/verbs/web.edn" "{}"
     "glossary/intents/Login.edn" "{:intent \"Login\" :elements {}}"}
    (fn [ctx]
      (is (= {:subjects "glossary/subjects.edn"
              :verbs ["glossary/verbs/web.edn"]
              :intents "glossary/intents"}
             (:glossaries (config/load-config {:project-context ctx})))))))

(deftest conventional-glossaries-partial-probe
  (with-sl-project "{}"
    {"glossary/subjects.edn" "{:subjects {}}"}
    (fn [ctx]
      (is (= {:subjects "glossary/subjects.edn"}
             (:glossaries (config/load-config {:project-context ctx})))))))

(deftest conventional-glossaries-nil-when-no-files
  (with-sl-project "{}" {}
    (fn [ctx]
      (is (nil? (:glossaries (config/load-config {:project-context ctx})))))))

(deftest explicit-glossaries-win-whole-map
  ;; Explicit :glossaries is used exactly as written — conventions never fill
  ;; absent keys (Chair ruling 2026-08-03).
  (with-sl-project "{:glossaries {:subjects \"custom.edn\"}}"
    {"glossary/subjects.edn" "{:subjects {}}"
     "glossary/verbs/web.edn" "{}"}
    (fn [ctx]
      (is (= {:subjects "custom.edn"}
             (:glossaries (config/load-config {:project-context ctx})))))))

(deftest explicit-nil-glossaries-respected
  (with-sl-project "{:glossaries nil}"
    {"glossary/subjects.edn" "{:subjects {}}"}
    (fn [ctx]
      (is (nil? (:glossaries (config/load-config {:project-context ctx})))))))

(deftest glossaries-spec-admits-intents
  ;; sl-7kct: runner/core reads [:glossaries :intents] and the lint suggests
  ;; that path, but the spec omitted it.
  (is (s/valid? ::config/glossaries {:subjects "g/s.edn" :intents "g/intents/"}))
  (is (s/valid? ::config/config {:glossaries {:intents "g/intents/"}})))

;; -----------------------------------------------------------------------------
;; Config Lint Tests (sl-hlkz)
;; -----------------------------------------------------------------------------

(deftest lint-config-flags-misplaced-known-keys
  (testing "the evidence case: top-level :step-paths (example 03's silent ignore)"
    (let [warnings (config/lint-config
                    (merge config/default-config {:step-paths ["steps/"]}))]
      (is (= 1 (count warnings)))
      (is (= :config/misplaced-key (:type (first warnings))))
      (is (= :step-paths (:key (first warnings))))
      (is (= [:runner :step-paths] (:suggested-path (first warnings))))
      (is (s/valid? ::config/lint-warnings warnings))))

  (testing "other known-nested keys get their canonical path suggested"
    (doseq [[k path] {:dialect [:parser :dialect]
                      :junit-xml [:runner :report :junit-xml]
                      :capture [:runner :capture]
                      :verbs [:glossaries :verbs]
                      :unknown-verb [:svo :unknown-verb]
                      :deadline-ms [:timing :deadline-ms]
                      :interval-ms [:timing :interval-ms]}]
      (let [[w] (config/lint-config {k :whatever})]
        (is (= :config/misplaced-key (:type w)) (str k))
        (is (= path (:suggested-path w)) (str k))))))

(deftest lint-config-flags-runner-nested-top-level-keys
  ;; sl-xjx0 (Warden W9): the reverse direction — the release's two newest
  ;; TOP-LEVEL keys nested under :runner used to pass silently dead.
  (testing ":timing and :costumes under :runner warn with the top-level path"
    (let [warnings (config/lint-config
                    (merge config/default-config
                           {:runner {:timing {:deadline-ms 5000}
                                     :costumes {:finance {:interface :web}}}}))
          by-key (into {} (map (juxt :key identity)) warnings)]
      (is (= 2 (count warnings)))
      (is (= [:runner :timing] (:found-path (by-key :timing))))
      (is (= [:timing] (:suggested-path (by-key :timing))))
      (is (= [:costumes] (:suggested-path (by-key :costumes))))
      (is (s/valid? ::config/lint-warnings warnings))))
  (testing "top-level :timing/:costumes stay clean"
    (is (empty? (config/lint-config
                 (merge config/default-config
                        {:timing {:deadline-ms 5000}
                         :costumes {:finance {:interface :web}}
                         :interfaces {:web {:type :web :adapter :etaoin}}}))))))

(deftest lint-config-warns-costume-on-non-etaoin-adapter
  ;; sl-xjx0 (Warden W14, cheap warn; real adapter-awareness = sl-bbqb):
  ;; costume attach is etaoin-only this release.
  (testing "declared costume on a playwright-adapter interface warns"
    (let [warnings (config/lint-config
                    (merge config/default-config
                           {:interfaces {:web {:type :web :adapter :playwright}}
                            :costumes {:finance {:interface :web}}}))
          [w] warnings]
      (is (= 1 (count warnings)))
      (is (= :config/costume-adapter-unsupported (:type w)))
      (is (= :finance (:key w)))
      (is (str/includes? (:message w) "etaoin-only"))
      (is (s/valid? ::config/lint-warnings warnings))))
  (testing "etaoin-adapter interface: clean"
    (is (empty? (config/lint-config
                 (merge config/default-config
                        {:interfaces {:web {:type :web :adapter :etaoin}}
                         :costumes {:finance {:interface :web}}})))))
  (testing "malformed :costumes shapes never throw here (validator's job)"
    (is (vector? (config/lint-config
                  (merge config/default-config
                         {:interfaces {:web {:type :web :adapter :playwright}}
                          :costumes {:finance :web}}))))
    (is (vector? (config/lint-config
                  (merge config/default-config {:costumes [:finance]}))))))

(deftest lint-config-flags-unknown-keys
  (let [[w :as warnings] (config/lint-config
                          (merge config/default-config {:stpe-paths ["x"]}))]
    (is (= 1 (count warnings)))
    (is (= :config/unknown-key (:type w)))
    (is (= :stpe-paths (:key w)))
    (is (nil? (:suggested-path w)))))

(deftest lint-config-accepts-every-known-top-level-key
  (testing "default-config and the full documented shape are clean"
    (is (= [] (config/lint-config config/default-config)))
    (is (= [] (config/lint-config
               {:parser {:dialect "en"}
                :runner {:step-paths ["steps/"]}
                :glossaries {:subjects "g/s.edn"}
                :interfaces {:web {:type :web :adapter :etaoin}}
                :costumes {:finance {:interface :web}}
                :svo {:unknown-verb :warn}
                :timing {:deadline-ms 30000 :interval-ms 500}}))))
  (testing "empty config is clean"
    (is (= [] (config/lint-config {})))))

(deftest lint-config-warns-on-top-level-webdriver-keys
  ;; sl-7kct: formerly exempted because `normalize` handles them — but the
  ;; normalized result has no consumer, so to `sl run` they are ignored keys
  ;; like any other. No suggested path: nothing reads a canonical location.
  (doseq [k [:webdriver :webdriver-url]]
    (let [[w :as warnings] (config/lint-config {k "http://localhost:9515"})]
      (is (= 1 (count warnings)) (str k))
      (is (= :config/unknown-key (:type w)) (str k))
      (is (= k (:key w)))
      (is (nil? (:suggested-path w))))))

(deftest lint-config-is-deterministic-and-never-blocks
  (testing "multiple offenders come back sorted by key"
    (is (= [:aaa :step-paths :zzz]
           (mapv :key (config/lint-config {:zzz 1 :aaa 2 :step-paths ["x"]})))))
  (testing "warnings only: the config itself is untouched and loading proceeds
            (forward compat — a newer config on an older version must run)"
    (let [cfg (merge config/default-config {:future-key {:x 1}})]
      (is (= cfg (do (config/lint-config cfg) cfg)))
      (is (vector? (config/lint-config cfg))))))

(deftest lint-config-scrub-composition
  ;; sl-lnj1 attach-time scrub (the exact expression compile-suite-stage
  ;; uses): a lint's :key holds the OFFENDING keyword itself, which can be
  ;; non-round-tripping (sl-27uh class — unreachable from an EDN config
  ;; file, but reachable from programmatic execute! callers). Scrub
  ;; stringifies it so the machine summary can never be corrupted.
  (let [lints (reporter/scrub (config/lint-config {(keyword "a b") 1}))]
    (is (= 1 (count lints)))
    (is (= ":a b" (:key (first lints))) "hostile keyword arrives as its pr-str string")
    (is (= :config/unknown-key (:type (first lints))))
    (is (= lints (edn/read-string (pr-str lints))) "scrubbed lints round-trip")))

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

(deftest find-default-config-anchored-to-user-cwd-test
  (testing "find-default-config resolves shiftlefter.edn against SL_USER_CWD"
    (let [tmp (str (fs/create-temp-dir))]
      (try
        ;; No sl/shiftlefter.edn in the user dir yet -> nil even though one may
        ;; exist at the raw process CWD.
        (with-redefs [paths/user-cwd (constantly tmp)]
          (is (nil? (config/find-default-config))))
        ;; Drop a config in the user dir's sl/ -> discovered as an absolute path.
        (fs/create-dirs (fs/path tmp "sl"))
        (spit (str (fs/path tmp "sl" "shiftlefter.edn")) "{}")
        (with-redefs [paths/user-cwd (constantly tmp)]
          (is (= (str (fs/real-path (fs/path tmp "sl" "shiftlefter.edn")))
                 (config/find-default-config))))
        (finally
          (fs/delete-tree tmp))))))

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

;; -----------------------------------------------------------------------------
;; Shifted-Mode Opt-In Tests (sl-ieie)
;; -----------------------------------------------------------------------------

(deftest test-load-config-no-user-svo-means-vanilla
  (testing "User config without :svo → loaded config has NO :svo key (Vanilla)"
    (let [cfg (config/load-config {:config-path "test/fixtures/config/minimal.edn"})]
      (is (not (contains? cfg :svo)))
      ;; Everything else still merges from defaults
      (is (= "de" (get-in cfg [:parser :dialect])))
      (is (= ["steps/"] (get-in cfg [:runner :step-paths]))))))

(deftest test-load-config-user-svo-merges-defaults
  (testing "User config with partial :svo → Shifted; defaults merged under it"
    (let [cfg (config/load-config {:config-path "test/fixtures/config/svo-partial.edn"})]
      (is (contains? cfg :svo))
      ;; User override wins
      (is (= :error (get-in cfg [:svo :unknown-subject])))
      ;; Unspecified levels come from default-config
      (is (= :warn (get-in cfg [:svo :unknown-verb])))
      (is (= :error (get-in cfg [:svo :unknown-interface])))
      (is (= :off (get-in cfg [:svo :unknown-object]))))))

(deftest test-load-config-no-config-file-is-vanilla
  (testing "No config file at all → defaults WITHOUT :svo (Vanilla)"
    (let [tmp (str (fs/create-temp-dir))]
      (try
        (with-redefs [paths/user-cwd (constantly tmp)]
          (let [cfg (config/load-config {})]
            (is (not (contains? cfg :svo)))
            ;; Remaining defaults intact
            (is (= "en" (get-in cfg [:parser :dialect])))
            (is (= ["steps/"] (get-in cfg [:runner :step-paths])))))
        (finally
          (fs/delete-tree tmp))))))

(deftest test-default-config-still-documents-svo-defaults
  (testing "default-config keeps the :svo opt-in defaults block"
    (is (= {:unknown-subject :warn
            :unknown-verb :warn
            :unknown-interface :error
            :unknown-object :off}
           (:svo config/default-config)))))

;; -----------------------------------------------------------------------------
;; Conditional :unknown-object default (sl-femd)
;; -----------------------------------------------------------------------------

(deftest test-conditional-unknown-object-default
  (testing "Intents configured + user silent → :warn"
    (let [cfg (config/load-config {:config-path "test/fixtures/config/svo-intents.edn"})]
      (is (= :warn (get-in cfg [:svo :unknown-object])))))

  (testing "Intents configured + user-explicit :off → stays :off"
    (let [cfg (config/load-config {:config-path "test/fixtures/config/svo-intents-off.edn"})]
      (is (= :off (get-in cfg [:svo :unknown-object])))))

  (testing "Conventionally probed glossary/intents/ flips the default too"
    (let [tmp (str (fs/create-temp-dir))]
      (try
        (fs/create-dirs (fs/path tmp "sl" "glossary" "intents"))
        (spit (str (fs/path tmp "sl" "shiftlefter.edn")) "{:svo {}}")
        (with-redefs [paths/user-cwd (constantly tmp)]
          (let [cfg (config/load-config {})]
            (is (= "glossary/intents" (get-in cfg [:glossaries :intents])))
            (is (= :warn (get-in cfg [:svo :unknown-object])))))
        (finally
          (fs/delete-tree tmp)))))

  (testing "No intents anywhere + user silent → :off (static default)"
    (let [tmp (str (fs/create-temp-dir))]
      (try
        (fs/create-dirs (fs/path tmp "sl"))
        (spit (str (fs/path tmp "sl" "shiftlefter.edn")) "{:svo {}}")
        (with-redefs [paths/user-cwd (constantly tmp)]
          (let [cfg (config/load-config {})]
            (is (= :off (get-in cfg [:svo :unknown-object])))))
        (finally
          (fs/delete-tree tmp)))))

  (testing "Vanilla config (no :svo) with intents stays Vanilla — no flip"
    (let [tmp (str (fs/create-temp-dir))]
      (try
        (fs/create-dirs (fs/path tmp "sl" "glossary" "intents"))
        (spit (str (fs/path tmp "sl" "shiftlefter.edn")) "{:parser {:dialect \"en\"}}")
        (with-redefs [paths/user-cwd (constantly tmp)]
          (let [cfg (config/load-config {})]
            (is (not (contains? cfg :svo)))))
        (finally
          (fs/delete-tree tmp))))))

(deftest test-unknown-object-spec-accepts-both-blocking-spellings
  (testing ":error is canonical, :strict a deprecated alias (sl-femd)"
    (is (s/valid? ::config/unknown-object :error))
    (is (s/valid? ::config/unknown-object :strict))
    (is (s/valid? ::config/unknown-object :warn))
    (is (s/valid? ::config/unknown-object :off))
    (is (not (s/valid? ::config/unknown-object :nope)))))

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

(deftest test-provisioning-mode
  (testing "provisioning-mode extracts strategy with :eager as default (sl-aa5)"
    (is (= :eager (config/provisioning-mode {})))                                       ;; absent → :eager
    (is (= :eager (config/provisioning-mode {:runner {}})))                             ;; missing key → :eager
    (is (= :eager (config/provisioning-mode {:runner {:provisioning :eager}})))
    (is (= :lazy  (config/provisioning-mode {:runner {:provisioning :lazy}}))))
  (testing ":runner spec accepts :eager and :lazy, rejects others"
    (is (s/valid? :shiftlefter.config/runner
                                   {:step-paths ["s/"] :allow-pending? false :provisioning :eager}))
    (is (s/valid? :shiftlefter.config/runner
                                   {:step-paths ["s/"] :allow-pending? false :provisioning :lazy}))
    (is (not (s/valid? :shiftlefter.config/runner
                                        {:step-paths ["s/"] :allow-pending? false :provisioning :nope})))))

(deftest test-capture-policy
  (testing "capture-policy extracts the map with {} as default (sl-3q9r)"
    (is (= {} (config/capture-policy {})))
    (is (= {} (config/capture-policy {:runner {}})))
    (is (= {:screenshot :on-failure}
           (config/capture-policy {:runner {:capture {:screenshot :on-failure}}}))))
  (testing ":runner spec accepts policy values, rejects garbage"
    (let [base {:step-paths ["s/"] :allow-pending? false}]
      (doseq [policy [:never :on-failure :every-step]]
        (is (s/valid? :shiftlefter.config/runner
                      (assoc base :capture {:screenshot policy}))
            (str policy)))
      (is (s/valid? :shiftlefter.config/runner
                    (assoc base :capture {:message-log :on-failure}))
          "kinds are OPEN — adapter-contributed keywords all pass")
      (is (not (s/valid? :shiftlefter.config/runner
                         (assoc base :capture {:screenshot :sometimes}))))
      (is (not (s/valid? :shiftlefter.config/runner
                         (assoc base :capture {"screenshot" :never}))))
      (is (not (s/valid? :shiftlefter.config/runner
                         (assoc base :capture [:screenshot :never])))))))

(deftest test-parse-capture-spec
  (testing "the happy grammar"
    (is (= {:screenshot :on-failure} (config/parse-capture-spec "screenshot=on-failure")))
    (is (= {:screenshot :on-failure :console :never}
           (config/parse-capture-spec "screenshot=on-failure,console=never")))
    (is (= {:screenshot :every-step}
           (config/parse-capture-spec ":screenshot=:every-step"))
        "leading colons tolerated on either side (agents write keywords)"))
  (testing "unknown KINDS are legal (open set) — the warn happens at resolution"
    (is (= {:message-log :on-failure} (config/parse-capture-spec "message-log=on-failure"))))
  (testing "malformed input parses to nil (tools.cli rejects the flag, exit 1)"
    (doseq [bad ["screenshot" "screenshot=" "=on-failure" ""
                 "screenshot=sometimes" "screenshot=on-failure,,console=never"
                 "screenshot=on-failure,console" "screenshot=on=failure"]]
      (is (nil? (config/parse-capture-spec bad)) (pr-str bad))))
  (testing "the validate pair"
    (is (config/valid-capture-spec? {:screenshot :never}))
    (is (not (config/valid-capture-spec? nil)))))

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

(deftest test-normalize-empty-config-valid
  (testing "Empty config is valid (no browser errors)"
    (let [st (config/normalize {})]
      (is (nil? (:errors st))))))

(deftest test-normalize-passes-webdriver-keys-through
  (testing ":webdriver/:webdriver-url are inert pass-through — no validation,
            no derived key (sl-w9wp; lint-config owns the warning, sl-7kct)"
    (let [st (config/normalize {:webdriver {:host "127.0.0.1"}})]
      (is (nil? (:errors st)))
      (is (nil? (:webdriver-url st))))))

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
  (testing "Empty interfaces map is valid (zero interfaces, sl-g2lj)"
    (let [cfg {:interfaces {}}
          result (config/normalize cfg)]
      (is (nil? (:errors result))))))

;; -----------------------------------------------------------------------------
;; sl-g2lj — user :interfaces replaces the defaults wholesale (Design A)
;; -----------------------------------------------------------------------------

(deftest test-interfaces-wholesale-replace
  (testing "user :interfaces is the COMPLETE set — no phantom builtin :web"
    (let [cfg (config/load-config
               {:config-path "test/fixtures/config/interfaces_sms_only.edn"})]
      (is (= [:sms] (keys (:interfaces cfg))))
      (is (nil? (config/get-interface cfg :web)))
      (is (nil? (:errors (config/normalize cfg))))))
  (testing "no :interfaces key -> builtin defaults mounted, unchanged"
    (let [cfg (config/load-config
               {:config-path "test/fixtures/config/minimal.edn"})]
      (is (= (:interfaces config/default-config) (:interfaces cfg)))))
  (testing "explicit :interfaces nil = zero interfaces, normalized to {} at
            load (sl-a7sx, Warden W-B1) — nil and {} are ONE shape downstream"
    (let [cfg (config/load-config
               {:config-path "test/fixtures/config/interfaces_nil.edn"})]
      (is (contains? cfg :interfaces))
      (is (= {} (:interfaces cfg)))
      (is (nil? (:errors (config/normalize cfg))))))
  (testing "W34 falls out: a costume naming an interface under :interfaces nil
            now errors like it does under {} (one shape, one verdict)"
    (let [tmp (str (fs/create-temp-dir))
          path (str (fs/path tmp "shiftlefter.edn"))]
      (try
        (spit path (pr-str {:interfaces nil
                            :costumes {:finance {:interface :web}}}))
        (let [cfg (config/load-config {:config-path path})
              [err] (:errors (config/normalize cfg))]
          (is (= :config/unknown-costume-interface (:type err))))
        (finally (fs/delete-tree tmp))))))

(deftest test-interfaces-default-sentinel
  (testing ":default re-includes the shipped builtin def VERBATIM"
    (let [cfg (config/load-config
               {:config-path
                "test/fixtures/config/interfaces_default_sentinel.edn"})]
      (is (= (get-in config/default-config [:interfaces :web])
             (get-in cfg [:interfaces :web])))
      (is (= #{:web :api :admin-web} (set (keys (:interfaces cfg)))))
      ;; NAME vs TYPE: :admin-web is a second :web-TYPE instance under a
      ;; non-builtin name — the user's def as-is, never the builtin's.
      (is (= :web (config/get-interface-type cfg :admin-web)))
      (is (= "http://localhost:9092/admin"
             (config/get-base-url cfg :admin-web)))
      (is (nil? (:errors (config/normalize cfg))))))
  (testing "a full map under the builtin NAME fully shadows — never merged"
    ;; The builtin :web def carries :config {}; a user :web without :config
    ;; must come through with the key ABSENT, proving no merge happened.
    (let [ifaces (config/expand-interface-defaults
                  {:web {:type :web :adapter :playwright}})]
      (is (= {:type :web :adapter :playwright} (:web ifaces)))
      (is (not (contains? (:web ifaces) :config)))))
  (testing "an unmatched sentinel is left in place for normalize"
    (is (= {:foo :default} (config/expand-interface-defaults {:foo :default}))))
  (testing "non-map input (incl. nil = zero interfaces) returned as written"
    (is (nil? (config/expand-interface-defaults nil)))
    (is (= [:web] (config/expand-interface-defaults [:web])))))

(deftest test-normalize-unknown-default-interface
  (testing ":foo :default with no builtin :foo is a planning error, never
            silent-empty"
    (let [[err] (:errors (config/normalize {:interfaces {:foo :default}}))]
      (is (= :config/unknown-default-interface (:type err)))
      (is (str/includes? (:message err) ":foo"))
      (is (= [:web] (get-in err [:data :builtins]))))))

(deftest test-normalize-reserved-sl-namespace
  (testing "sl/-namespaced interface NAME is rejected (builtin reservation)"
    (let [[err] (:errors (config/normalize
                          {:interfaces {:sl/web {:type :web
                                                 :adapter :etaoin}}}))]
      (is (= :config/reserved-interface-name (:type err)))))
  (testing "the name reservation fires for sentinel entries too"
    (let [[err] (:errors (config/normalize {:interfaces {:sl/api :default}}))]
      (is (= :config/reserved-interface-name (:type err)))))
  (testing "sl/-namespaced :type gets its own error, not unknown-type"
    (let [[err] (:errors (config/normalize
                          {:interfaces {:x {:type :sl/web :adapter :a}}}))]
      (is (= :config/reserved-interface-type (:type err)))
      (is (= :sl/web (get-in err [:data :type]))))))

(deftest test-string-keyed-timing-is-a-planning-error
  ;; sl-q4cb (Warden W35): {:timing {"deadline-ms" 5}} crashed normalize
  ;; inside the did-you-mean suggester (String cannot be cast to Named).
  (doseq [cfg [{:timing {"deadline-ms" 5}}
               {:interfaces {:web {:type :web :adapter :etaoin
                                   :timing {"deadline-ms" 5}}}}]]
    (let [errs (:errors (config/normalize cfg))]
      (is (seq errs) (pr-str cfg))
      (is (str/includes? (:message (first errs)) "\"deadline-ms\"")
          "the error names the string key the user typed"))))

(deftest test-stray-default-inside-a-full-interface-map-is-linted
  ;; sl-q4cb (Warden W36): the :default sentinel is an entry VALUE; as a key
  ;; inside a full map it was silently ignored.
  (let [lints (config/lint-config
               {:interfaces {:web {:default true :type :web :adapter :etaoin}
                             :api {:type :api :adapter :http}}})
        lint (first (filter #(= [:interfaces :web :default] (:found-path %)) lints))]
    (is (some? lint))
    (is (= :config/misplaced-key (:type lint)))
    (is (= :default (:key lint)))
    (is (str/includes? (:message lint) "never merged with the builtin"))
    (is (empty? (filter #(= [:interfaces :api :default] (:found-path %)) lints))
        "only the entry carrying the stray key is named"))
  (testing "a sentinel VALUE and a clean full map lint nothing"
    (is (empty? (filter #(= :default (:key %))
                        (config/lint-config {:interfaces {:web :default
                                                          :api {:type :api :adapter :http}}}))))))

(deftest test-svo-levels-are-a-closed-set
  ;; sl-3bqv: a typo'd level (:warm) used to flow through compute-severity
  ;; as non-blocking — the strict setting silently became a warning.
  (testing "an unknown level is a hard error with did-you-mean over the enum"
    (let [[err :as errs] (:errors (config/normalize {:svo {:unknown-subject :warm}}))]
      (is (= 1 (count errs)))
      (is (= :config/invalid-svo-level (:type err)))
      (is (str/includes? (:message err) "[:svo :unknown-subject]"))
      (is (str/includes? (:message err) "did you mean :warn?"))
      (is (= :warn (get-in err [:data :suggestion])))))
  (testing "every key is checked; :strict is accepted for :unknown-object only"
    (is (nil? (:errors (config/normalize {:svo {:unknown-subject :error
                                                :unknown-verb :warn
                                                :unknown-interface :off
                                                :unknown-object :strict}}))))
    (is (= [:config/invalid-svo-level]
           (mapv :type (:errors (config/normalize {:svo {:unknown-verb :strict}})))))
    (is (= [:unknown-interface :unknown-object]
           (mapv #(get-in % [:data :key])
                 (:errors (config/normalize {:svo {:unknown-interface "error"
                                                   :unknown-object :nope}}))))))
  (testing "no :svo, an empty :svo, and a non-map :svo"
    (is (nil? (:errors (config/normalize {}))))
    (is (nil? (:errors (config/normalize {:svo {}}))))
    (is (= [:config/invalid-svo-level]
           (mapv :type (:errors (config/normalize {:svo [:warn]})))))))

(deftest test-halt-policy-tri-state
  ;; sl-78u4 (Warden W3): false | :group | :run, true reads as :run.
  (testing "the accessor's tri-state"
    (is (false? (config/halt-policy {})))
    (is (false? (config/halt-policy {:runner {:halt-on-observation-failure false}})))
    (is (= :group (config/halt-policy {:runner {:halt-on-observation-failure :group}})))
    (is (= :run (config/halt-policy {:runner {:halt-on-observation-failure :run}})))
    (is (= :run (config/halt-policy {:runner {:halt-on-observation-failure true}}))
        "true = :run, so every pre-tri-state doc sentence stays true"))
  (testing "the engine's boolean is on for ANY scope"
    (is (config/halt-on-observation-failure? {:runner {:halt-on-observation-failure :group}}))
    (is (config/halt-on-observation-failure? {:runner {:halt-on-observation-failure true}}))
    (is (not (config/halt-on-observation-failure? {}))))
  (testing "the spec accepts the three states and rejects the rest"
    (doseq [ok [false true :group :run]]
      (is (s/valid? :shiftlefter.config/halt-on-observation-failure ok) (pr-str ok)))
    (doseq [bad ["true" :everything 1 nil]]
      (is (not (s/valid? :shiftlefter.config/halt-on-observation-failure bad)) (pr-str bad)))))

(deftest test-normalize-glossaries-shape
  ;; sl-a7sx (Warden W33): the ::config spec was documentation-only — the
  ;; retired type-keyed :verbs map crashed the path resolver instead of
  ;; reaching a planning error.
  (testing "the retired type-keyed :verbs map → :config/invalid-glossaries,
            quoting the found value and naming the path-list form"
    (let [[err :as errs] (:errors (config/normalize
                                   {:glossaries {:verbs {:web "a.edn"}}}))]
      (is (= 1 (count errs)))
      (is (= :config/invalid-glossaries (:type err)))
      (is (str/includes? (:message err) "{:web \"a.edn\"}"))
      (is (str/includes? (:message err) "LIST"))
      (is (= {:web "a.edn"} (get-in err [:data :definition])))))
  (testing "a string :verbs, a non-string element, a non-map :glossaries,
            and non-string :subjects/:intents are the same error class"
    (doseq [bad [{:glossaries {:verbs "a.edn"}}
                 {:glossaries {:verbs ["a.edn" :b]}}
                 {:glossaries ["a.edn"]}
                 {:glossaries {:subjects :x}}
                 {:glossaries {:intents 42}}]]
      (is (= [:config/invalid-glossaries]
             (mapv :type (:errors (config/normalize bad))))
          (pr-str bad))))
  (testing "a LIST literal is as valid as a vector (spec :kind sequential? —
            aligned to the shipped loader), and nil/absent stay clean"
    (doseq [ok [{:glossaries {:verbs '("a.edn" "b.edn")}}
                {:glossaries {:verbs ["a.edn"]}}
                {:glossaries {:verbs nil}}
                {:glossaries nil}
                {}]]
      (is (nil? (:errors (config/normalize ok))) (pr-str ok)))))

(deftest test-resolve-glossary-config-paths-is-shape-safe
  ;; sl-a7sx (Warden W33): the resolver runs BEFORE normalize on both
  ;; channels, so it must never throw — only strings are paths; anything
  ;; else passes through untouched for normalize to name.
  (let [ctx {:config-root "/tmp/sl-shape-safe"}
        resolve #(config/resolve-glossary-config-paths ctx %)]
    (testing "a list resolves like a vector"
      (is (= [(str (fs/path "/tmp/sl-shape-safe" "a.edn"))]
             (:verbs (resolve {:verbs '("a.edn")})))))
    (testing "the retired map, a string, and non-string elements pass through"
      (is (= {:verbs {:web "a.edn"}} (resolve {:verbs {:web "a.edn"}})))
      (is (= {:verbs "a.edn"} (resolve {:verbs "a.edn"})))
      (is (= :b (second (:verbs (resolve {:verbs ["a.edn" :b]})))))
      (is (= {:subjects :x} (resolve {:subjects :x}))))
    (testing "nil config stays nil"
      (is (nil? (resolve nil))))))

(deftest test-normalize-interfaces-shape
  (testing "present-but-non-map :interfaces errors — presence is meaningful"
    (let [[err] (:errors (config/normalize {:interfaces [:web]}))]
      (is (= :config/invalid-interfaces (:type err)))))
  (testing "a non-map, non-:default entry value errors"
    (let [[err] (:errors (config/normalize {:interfaces {:web "etaoin"}}))]
      (is (= :config/invalid-interface (:type err)))
      (is (str/includes? (:message err) ":default")))))

(deftest test-interface-base-url
  (testing "A string :base-url in interface :config is valid and spec-conformant"
    (let [iface {:type :web :adapter :etaoin
                 :config {:base-url "http://localhost:9092" :headless true}}
          cfg {:interfaces {:web iface}}
          result (config/normalize cfg)]
      (is (nil? (:errors result)))
      (is (s/valid? :shiftlefter.config/interface-def iface))
      (is (= "http://localhost:9092" (config/get-base-url result :web)))))

  (testing "get-base-url is nil when unset"
    (let [result (config/normalize {:interfaces {:web {:type :web :adapter :etaoin}}})]
      (is (nil? (config/get-base-url result :web)))))

  (testing "Non-string or blank :base-url is a loud config error (sl-3jr4)"
    (doseq [bad [9092 "" "   " :localhost]]
      (let [cfg {:interfaces {:web {:type :web :adapter :etaoin
                                    :config {:base-url bad}}}}
            result (config/normalize cfg)]
        (is (seq (:errors result)) (str ":base-url " (pr-str bad) " should error"))
        (is (= :config/invalid-interface (-> result :errors first :type)))
        (is (re-find #"base-url" (-> result :errors first :message)))))))

(deftest test-timing-config-validation
  (testing "valid :timing shapes pass at both rungs (sl-99u2)"
    (doseq [timing [{:deadline-ms 30000 :interval-ms 500}
                    {:deadline-ms 1000}
                    {:interval-ms 50}
                    {}]]
      (let [cfg {:timing timing
                 :interfaces {:web {:type :web :adapter :etaoin
                                    :timing timing}}}
            result (config/normalize cfg)]
        (is (nil? (:errors result)) (pr-str timing))
        (is (s/valid? :shiftlefter.config/config cfg) (pr-str timing)))))

  (testing "malformed top-level :timing is a loud config error"
    (doseq [bad [{:deadline-ms "5s"} {:deadline-ms 0} {:interval-ms -1} [30000] 30000]]
      (let [result (config/normalize {:timing bad})]
        (is (seq (:errors result)) (str ":timing " (pr-str bad) " should error"))
        (is (= :config/invalid-timing (-> result :errors first :type)))
        (is (re-find #":timing" (-> result :errors first :message))))))

  (testing "malformed per-interface :timing is a loud config error"
    (doseq [bad [{:deadline-ms "5s"} {:deadline-ms 0} 30000]]
      (let [cfg {:interfaces {:web {:type :web :adapter :etaoin :timing bad}}}
            result (config/normalize cfg)]
        (is (seq (:errors result)) (str ":timing " (pr-str bad) " should error"))
        (is (= :config/invalid-interface (-> result :errors first :type)))
        (is (re-find #":timing" (-> result :errors first :message))))))

  (testing "unknown :timing inner key is a loud error at the GLOBAL rung
            with a did-you-mean (sl-d3cz — ::timing is open s/keys, so a
            typo'd key previously passed while the timing config sat
            silently dead)"
    (doseq [[bad suggestion] [[{:interval 250} ":interval-ms"]
                              [{:deadlinems 5000} ":deadline-ms"]]]
      (let [result (config/normalize {:timing bad})]
        (is (seq (:errors result)) (str ":timing " (pr-str bad) " should error"))
        (is (= :config/invalid-timing (-> result :errors first :type)))
        (is (re-find #"unknown key" (-> result :errors first :message)))
        (is (re-find (re-pattern (java.util.regex.Pattern/quote suggestion)) (-> result :errors first :message))
            "the did-you-mean names the intended key")))
    (let [result (config/normalize {:timing {:deadline-ms 1000 :bogus 1}})]
      (is (= :config/invalid-timing (-> result :errors first :type))
          "an unknown key errors even beside a valid one")))

  (testing "unknown :timing inner key is a loud error at the INTERFACE rung"
    (let [cfg {:interfaces {:web {:type :web :adapter :etaoin
                                  :timing {:interval 250}}}}
          result (config/normalize cfg)]
      (is (seq (:errors result)))
      (is (= :config/invalid-interface (-> result :errors first :type)))
      (is (re-find #"unknown key" (-> result :errors first :message)))
      (is (re-find #":interval-ms" (-> result :errors first :message))))))

;; -----------------------------------------------------------------------------
;; Glossary Config Accessor Tests
;; -----------------------------------------------------------------------------

(deftest test-get-glossary-config
  (testing "get-glossary-config extracts glossary paths"
    (let [cfg {:glossaries {:subjects "config/subjects.edn"
                            :verbs ["config/verbs-web.edn"]}}]
      (is (= {:subjects "config/subjects.edn"
              :verbs ["config/verbs-web.edn"]}
             (config/get-glossary-config cfg)))))

  (testing "get-glossary-config returns nil for missing"
    (is (nil? (config/get-glossary-config {})))
    (is (nil? (config/get-glossary-config {:parser {:dialect "en"}})))))

;; -----------------------------------------------------------------------------
;; Max-parallel accessor (sl-q9wp)
;; -----------------------------------------------------------------------------

(deftest test-max-parallel-accessor
  (testing "reads [:runner :max-parallel]"
    (is (= 6 (config/max-parallel {:runner {:max-parallel 6}}))))
  (testing "defaults to 1 (sequential) when absent"
    (is (= 1 (config/max-parallel {})))
    (is (= 1 (config/max-parallel {:runner {:step-paths ["steps/"]
                                            :allow-pending? false}})))))

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
    (let [cfg {:glossaries {:subjects "glossary/subjects.edn"
                            :verbs ["glossary/verbs/web.edn"]}
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

;; -----------------------------------------------------------------------------
;; Costume declarations (sl-1206)
;; -----------------------------------------------------------------------------

(deftest test-costumes-spec
  (testing "valid declaration map conforms"
    (is (s/valid? ::config/costumes {:gmail {:interface :webmail}
                                     :finance {:interface :web}}))
    (is (s/valid? ::config/config {:interfaces {:web {:type :web :adapter :etaoin}}
                                   :costumes {:finance {:interface :web}}})))
  (testing "malformed entries do not conform"
    (is (not (s/valid? ::config/costumes {:gmail :webmail})))
    (is (not (s/valid? ::config/costumes {:gmail {}})))
    (is (not (s/valid? ::config/costumes {:gmail {:interface "webmail"}})))))

(deftest test-validate-costumes-config
  (let [interfaces {:web {:type :web :adapter :etaoin}
                    :webmail {:type :web :adapter :etaoin}}]
    (testing "declared against a configured instance: clean"
      (is (nil? (:errors (config/normalize {:interfaces interfaces
                                            :costumes {:gmail {:interface :webmail}}})))))
    (testing "malformed entry: :config/invalid-costume"
      (let [{:keys [errors]} (config/normalize {:interfaces interfaces
                                                :costumes {:gmail :webmail}})]
        (is (= [:config/invalid-costume] (mapv :type errors)))))
    (testing "non-map :costumes: :config/invalid-costume"
      (let [{:keys [errors]} (config/normalize {:interfaces interfaces
                                                :costumes [:gmail]})]
        (is (= [:config/invalid-costume] (mapv :type errors)))))
    (testing "unknown instance: :config/unknown-costume-interface with did-you-mean"
      (let [{:keys [errors]} (config/normalize {:interfaces interfaces
                                                :costumes {:gmail {:interface :webmial}}})]
        (is (= [:config/unknown-costume-interface] (mapv :type errors)))
        (is (= :webmail (get-in (first errors) [:data :suggestion])))
        (is (re-find #"did you mean :webmail" (:message (first errors))))))))

(deftest test-costume-accessors
  (let [config {:costumes {:finance {:interface :web}}}]
    (is (= {:finance {:interface :web}} (config/get-costumes config)))
    (is (= {} (config/get-costumes {})))))
