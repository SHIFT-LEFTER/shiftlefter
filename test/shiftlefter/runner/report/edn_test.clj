(ns shiftlefter.runner.report.edn-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [shiftlefter.runner.report.edn :as report-edn]
            [shiftlefter.runner.reporter :as reporter]))

;; -----------------------------------------------------------------------------
;; build-summary Tests
;; -----------------------------------------------------------------------------

(deftest test-build-summary-exit-0
  (testing "Exit 0 (all passed) includes counts"
    (let [result {:scenarios [{:status :passed :steps [{:status :passed}]}
                              {:status :passed :steps [{:status :passed}]}]
                  :counts {:passed 2 :failed 0 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run-123" 0 result {})]
      (is (= "run-123" (:run/id summary)))
      (is (= 0 (:run/exit-code summary)))
      (is (= :passed (:run/status summary)))
      (is (= 2 (-> summary :counts :passed)))
      (is (= 2 (-> summary :counts :scenarios)))
      (is (nil? (:failures summary))))))

(deftest test-build-summary-exit-1
  (testing "Exit 1 (failures) includes failures list"
    (let [result {:scenarios [{:status :passed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000001"
                                                    :pickle/name "Passing"}}
                               :steps [{:status :passed}]}
                              {:status :failed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000002"
                                                    :pickle/name "Failing"}}
                               :steps [{:status :failed
                                        :step {:step/id #uuid "00000000-0000-0000-0000-000000000003"
                                               :step/text "I click button"}
                                        :binding {:source {:file "s.clj" :line 10}}
                                        :error {:type :step/exception
                                                :message "Not found"}}]}]
                  :counts {:passed 1 :failed 1 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run-456" 1 result {})]
      (is (= 1 (:run/exit-code summary)))
      (is (= :failed (:run/status summary)))
      (is (= 1 (count (:failures summary))))
      (let [failure (first (:failures summary))]
        (is (= "I click button" (:step/text failure)))
        (is (= "Failing" (:scenario/name failure)))
        (is (= "Not found" (-> failure :error :message)))))))

(deftest test-build-summary-failure-carries-poll-counts
  ;; sl-tcsq: a failing oracle's :failures entry carries its summed poll
  ;; digest top-level; a failing step that never polled carries no key.
  (let [digest {:attempts 5 :answered 0 :threw 0 :observation-throws 0}
        failing-step (fn [extra]
                       (merge {:status :failed
                               :step {:step/id #uuid "00000000-0000-0000-0000-000000000003"
                                      :step/text "the oracle"}
                               :error {:type :step/exception :message "Timed out"}}
                              extra))
        summary-of (fn [step]
                     (report-edn/build-summary
                      "run-789" 1
                      {:scenarios [{:status :failed
                                    :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000002"
                                                         :pickle/name "Failing"}}
                                    :steps [step]}]
                       :counts {:passed 0 :failed 1 :pending 0 :skipped 0}}
                      {}))]
    (testing "polled: the digest rides the failure entry"
      (is (= digest (-> (summary-of (failing-step {:poll-counts digest}))
                        :failures first :poll-counts))))
    (testing "never polled: no key"
      (is (not (contains? (-> (summary-of (failing-step {})) :failures first)
                          :poll-counts))))))

(deftest test-build-summary-exit-2
  (testing "Exit 2 (planning failure) includes diagnostics"
    (let [diagnostics {:undefined [{:step {:step/id #uuid "00000000-0000-0000-0000-000000000001"
                                           :step/text "undefined step"}}]
                       :ambiguous []
                       :invalid-arity []
                       :counts {:undefined-count 1 :ambiguous-count 0
                                :invalid-arity-count 0 :total-issues 1}}
          summary (report-edn/build-summary "run-789" 2 nil {:diagnostics diagnostics})]
      (is (= 2 (:run/exit-code summary)))
      (is (= :planning-failed (:run/status summary)))
      (is (some? (:planning summary)))
      (is (= 1 (count (-> summary :planning :issues))))
      (is (= :undefined (-> summary :planning :issues first :type))))))

(deftest test-build-summary-exit-4
  (testing "Exit 4 (run degraded, sl-7c7i) carries counts AND failures —
            the :error scenarios' attributions ride :failures like exit 1"
    (let [result {:scenarios [{:status :passed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000001"
                                                    :pickle/name "Passing"}}
                               :steps [{:status :passed}]}
                              {:status :error
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000002"
                                                    :pickle/name "Degraded"}}
                               :error {:type :svo/provisioning-failed
                                       :message "Browser provisioning failed"}
                               :steps [{:status :failed
                                        :step {:step/id #uuid "00000000-0000-0000-0000-000000000003"
                                               :step/text "I click button"}
                                        :binding {:source {:file "s.clj" :line 10}}
                                        :error {:type :svo/provisioning-failed
                                                :message "Browser provisioning failed"}}]}]
                  :counts {:passed 1 :failed 0 :pending 0 :skipped 0 :error 1}}
          summary (report-edn/build-summary "run-deg" 4 result {})]
      (is (= 4 (:run/exit-code summary)))
      (is (= :degraded (:run/status summary)))
      (is (= 1 (-> summary :counts :error)))
      (is (= 2 (-> summary :counts :scenarios)))
      (is (pos? (count (:failures summary)))
          "scenario-level :error contributes a failure entry")
      (is (some #(= "Degraded" (:scenario/name %)) (:failures summary))))))

(deftest test-build-summary-exit-3
  (testing "Exit 3 (crash) includes error"
    (let [error {:type :runner/crash
                 :message "Unexpected error"
                 :exception-class "java.lang.RuntimeException"}
          summary (report-edn/build-summary "run-crash" 3 nil {:error error})]
      (is (= 3 (:run/exit-code summary)))
      (is (= :crashed (:run/status summary)))
      (is (= "Unexpected error" (-> summary :error :message))))))

;; -----------------------------------------------------------------------------
;; Group identity + invocation aggregate (sl-pwbp)
;; -----------------------------------------------------------------------------

(deftest test-build-summary-group-identity
  (testing "build-summary is a pure projection: identity keys land iff the
            opts carry them (the multi-group gate lives in the runner)"
    (let [result {:scenarios [] :counts {:passed 0 :failed 0 :pending 0 :skipped 0}}
          stamped (report-edn/build-summary "run-g" 0 result
                                            {:group-label "alpha" :group-index 0})
          bare (report-edn/build-summary "run-g" 0 result {})]
      (is (= "alpha" (:group/label stamped)))
      (is (= 0 (:group/index stamped)) "index 0 survives (some?, not truthy)")
      (is (not (contains? bare :group/label)))
      (is (not (contains? bare :group/index)))))
  (testing "the exit-2 path stamps the same way (report-planning-error! seam)"
    (let [summary (report-edn/build-summary "run-g" 2 nil
                                            {:diagnostics {}
                                             :group-label "bravo" :group-index 1})]
      (is (= "bravo" (:group/label summary)))
      (is (= 1 (:group/index summary))))))

(deftest test-invocation-summary
  (testing "the aggregate form: :invocation/* verdict keys, rows echoing the
            per-group summary vocabulary, and NO :run/exit-code — the
            documented summary discriminator must not match it"
    (let [agg (report-edn/invocation-summary
               "run-inv" 4242 2
               [{:group-label "alpha" :exit-code 0 :status :passed}
                {:group-label "bravo" :exit-code 2 :status :planning-failed}])]
      (is (= "run-inv" (:run/id agg)))
      (is (= 4242 (:run/seed agg)))
      (is (= 2 (:invocation/exit-code agg)))
      (is (= :planning-failed (:invocation/status agg)))
      (is (not (contains? agg :run/exit-code)))
      (is (= [{:group/label "alpha" :group/index 0
               :run/exit-code 0 :run/status :passed}
              {:group/label "bravo" :group/index 1
               :run/exit-code 2 :run/status :planning-failed}]
             (:invocation/groups agg)))))
  (testing "dry-run? (sl-a7sx, Warden W6): the aggregate says :dry-run, not
            the exit-code-derived :passed — dry-run is invocation-wide"
    (let [agg (report-edn/invocation-summary
               "run-inv" 4242 0
               [{:group-label "alpha" :exit-code 0 :status :dry-run}
                {:group-label "bravo" :exit-code 0 :status :dry-run}]
               {:dry-run? true})]
      (is (= 0 (:invocation/exit-code agg)))
      (is (= :dry-run (:invocation/status agg)))
      (is (= [:dry-run :dry-run] (mapv :run/status (:invocation/groups agg))))
      (is (not (contains? agg :run/exit-code)))))
  (testing "a dry run whose group failed PLANNING keeps the exit-derived
            status — :planning-failed beside exit 2, as the rows say; never
            :dry-run beside a nonzero code"
    (let [agg (report-edn/invocation-summary
               "run-inv" 4242 2
               [{:group-label "alpha" :exit-code 0 :status :dry-run}
                {:group-label "bravo" :exit-code 2 :status :planning-failed}]
               {:dry-run? true})]
      (is (= 2 (:invocation/exit-code agg)))
      (is (= :planning-failed (:invocation/status agg)))))
  (testing ":halted under :run scope (sl-78u4, Warden W3): the record rides
            the aggregate with :group/label; a skipped group's row says
            :skipped and carries NO :run/exit-code"
    (let [halted {:after-scenario 1 :scenario "Dark" :group/label "bravo"}
          agg (report-edn/invocation-summary
               "run-inv" 4242 4
               [{:group-label "alpha" :exit-code 0 :status :passed}
                {:group-label "bravo" :exit-code 4 :status :degraded}
                {:group-label "charlie" :status :skipped :halted-by halted}]
               {:halted halted})]
      (is (= halted (:halted agg)))
      (is (= :degraded (:invocation/status agg)))
      (is (= {:group/label "charlie" :group/index 2 :run/status :skipped}
             (nth (:invocation/groups agg) 2))
          "absence of :run/exit-code = no verdict exists")))
  (testing "no :halted key when the policy never fired"
    (is (not (contains? (report-edn/invocation-summary "r" 1 0 [] {}) :halted))))
  (testing ":invocation/results-path (sl-alha, Warden W32): the invocation
            run dir, additive; absent when no results dir was made"
    (is (= "results/20260903-1"
           (:invocation/results-path
            (report-edn/invocation-summary "r" 1 0 [] {:results-path "results/20260903-1"}))))
    (is (not (contains? (report-edn/invocation-summary "r" 1 0 [] {:results-path nil})
                        :invocation/results-path)))))

;; -----------------------------------------------------------------------------
;; prn-summary Tests
;; -----------------------------------------------------------------------------

;; -----------------------------------------------------------------------------
;; Config-lint diagnostics (sl-lnj1)
;; -----------------------------------------------------------------------------

(def ^:private step-paths-lint
  {:type :config/misplaced-key :key :step-paths
   :suggested-path [:runner :step-paths]
   :message (str "config key :step-paths is not read at the top level and"
                 " was ignored — did you mean [:runner :step-paths]?")})

(deftest test-execution-diagnostics-config-lints
  (testing "nil when clean — clean-run summaries stay byte-identical"
    (is (nil? (report-edn/execution-diagnostics nil)))
    (is (nil? (report-edn/execution-diagnostics {})))
    (is (nil? (report-edn/execution-diagnostics {:svo-issues [] :config-lints []}))))
  (testing "config-lints alone: no :svo-issues key, no empty :counts noise"
    (is (= {:config-lints [step-paths-lint]}
           (report-edn/execution-diagnostics {:config-lints [step-paths-lint]}))))
  (testing "svo-issues alone keeps the historical shape"
    (is (= {:svo-issues [{:severity :warn}] :counts {:svo-issue-count 1}}
           (report-edn/execution-diagnostics {:svo-issues [{:severity :warn}]
                                              :counts {:svo-issue-count 1 :other 9}}))))
  (testing "both present, both carried"
    (let [d (report-edn/execution-diagnostics {:svo-issues [{:severity :warn}]
                                               :counts {:svo-issue-count 1}
                                               :config-lints [step-paths-lint]})]
      (is (= [step-paths-lint] (:config-lints d)))
      (is (= [{:severity :warn}] (:svo-issues d)))))
  (testing "cleanup failures ride :cleanup-failures (sl-lbrc); empty = absent"
    (let [failure {:scenario "s" :capability :web :adapter :leaky :error "leaked"}]
      (is (= {:cleanup-failures [failure]}
             (report-edn/execution-diagnostics {:cleanup-failures [failure]})))
      (is (nil? (report-edn/execution-diagnostics {:cleanup-failures []}))))))

(deftest test-config-lints-in-summaries
  (testing "exit 0: :diagnostics :config-lints rides the summary and round-trips"
    (let [summary (report-edn/build-summary
                   "r" 0 {:scenarios [] :counts {:passed 0}}
                   {:diagnostics {:config-lints [step-paths-lint]}})]
      (is (= [step-paths-lint] (-> summary :diagnostics :config-lints)))
      (is (= summary (edn/read-string (pr-str summary))))))
  (testing "exit 2: :planning :config-lints rides the summary"
    (let [summary (report-edn/build-summary
                   "r" 2 nil
                   {:diagnostics {:undefined [] :ambiguous [] :invalid-arity []
                                  :counts {} :config-lints [step-paths-lint]}})]
      (is (= [step-paths-lint] (-> summary :planning :config-lints))))))

(def ^:private override-flag-glossary-lint
  ;; sl-67dj (Warden P3-13) — the structured glossary-load warning shape.
  {:type :glossary/override-flag-ignored
   :path "glossary/verbs-web.edn"
   :message "Glossary file glossary/verbs-web.edn carries :override-defaults …"})

(deftest test-glossary-lints-in-summaries
  (testing "clean when absent/empty — summaries stay byte-identical"
    (is (nil? (report-edn/execution-diagnostics {:glossary-lints []}))))
  (testing "exit 0: :diagnostics :glossary-lints rides the summary and round-trips"
    (let [summary (report-edn/build-summary
                   "r" 0 {:scenarios [] :counts {:passed 0}}
                   {:diagnostics {:glossary-lints [override-flag-glossary-lint]}})]
      (is (= [override-flag-glossary-lint]
             (-> summary :diagnostics :glossary-lints)))
      (is (= summary (edn/read-string (pr-str summary))))))
  (testing "exit 2: :planning :glossary-lints rides the summary"
    (let [summary (report-edn/build-summary
                   "r" 2 nil
                   {:diagnostics {:undefined [] :ambiguous [] :invalid-arity []
                                  :counts {}
                                  :glossary-lints [override-flag-glossary-lint]}})]
      (is (= [override-flag-glossary-lint]
             (-> summary :planning :glossary-lints))))))

(deftest test-prn-summary-outputs-to-stdout
  (testing "prn-summary outputs to stdout, not stderr"
    (let [summary {:run/id "x" :run/exit-code 0}
          stdout (with-out-str (report-edn/prn-summary summary))
          stderr (let [sw (java.io.StringWriter.)]
                   (binding [*err* sw]
                     (report-edn/prn-summary summary))
                   (str sw))]
      (is (not (empty? stdout)) "Should output to stdout")
      (is (empty? stderr) "Should not output to stderr"))))

(deftest test-prn-summary-immune-to-ambient-printer-bindings
  (testing "the --edn artifact reads back even under clojure.main's ambient
            bindings (sl-3ub4: pnm=true turns shared digit-leading-namespace
            map keys into unreadable #:0a{...}; print-length would truncate)"
    (let [summary {:run/id "r" :run/exit-code 1 :run/status :failed
                   :failures [{:error {:data {(keyword "0a" "A") nil}}}]}
          stdout (binding [*print-namespace-maps* true
                           *print-length* 2
                           *print-level* 2]
                   (with-out-str (report-edn/prn-summary summary)))]
      (is (= summary (edn/read-string stdout))
          "round-trips byte-for-byte regardless of ambient printer state"))))

(deftest test-prn-summary-is-valid-edn
  (testing "prn-summary output is valid EDN"
    (let [summary {:run/id "test-run"
                   :run/exit-code 1
                   :run/status :failed
                   :counts {:passed 3 :failed 2 :pending 0 :skipped 0
                            :scenarios 5 :steps 15}
                   :failures [{:step/text "I click" :error {:message "error"}}]}
          stdout (with-out-str (report-edn/prn-summary summary))
          parsed (edn/read-string stdout)]
      (is (= "test-run" (:run/id parsed)))
      (is (= 1 (:run/exit-code parsed)))
      (is (= :failed (:run/status parsed))))))

;; -----------------------------------------------------------------------------
;; Serialization Tests
;; -----------------------------------------------------------------------------

(deftest test-no-throwable-in-output
  (testing "Errors are serialized to maps, no Throwables"
    (let [result {:scenarios [{:status :failed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000001"
                                                    :pickle/name "Test"}}
                               :steps [{:status :failed
                                        :step {:step/id #uuid "00000000-0000-0000-0000-000000000002"
                                               :step/text "step"}
                                        :binding {}
                                        :error {:type :step/exception
                                                :message "Error message"
                                                :exception-class "clojure.lang.ExceptionInfo"
                                                :data {:foo 1}}}]}]
                  :counts {:passed 0 :failed 1 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run" 1 result {})
          stdout (with-out-str (report-edn/prn-summary summary))]
      ;; Should be readable as EDN
      (is (edn/read-string stdout))
      ;; Error should be a map, not a Throwable
      (is (map? (-> summary :failures first :error))))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria from Spec
;; -----------------------------------------------------------------------------

(deftest test-acceptance-edn-on-stdout-console-on-stderr
  (testing "Spec: EDN on stdout, human on stderr, never mixed"
    (let [summary {:run/id "x" :run/exit-code 1 :counts {:passed 0 :failed 1}}
          stdout (with-out-str (report-edn/prn-summary summary))]
      ;; EDN output contains :run/id
      (is (str/includes? stdout ":run/id"))
      ;; EDN can be parsed
      (is (= "x" (:run/id (edn/read-string stdout)))))))

(deftest test-planning-diagnostics-structure
  (testing "Planning diagnostics include issue details"
    (let [diagnostics {:undefined [{:step {:step/id #uuid "00000000-0000-0000-0000-000000000001"
                                           :step/text "I do undefined thing"}}]
                       :ambiguous [{:step {:step/id #uuid "00000000-0000-0000-0000-000000000002"
                                           :step/text "I have 5 items"}
                                    :alternatives [{:stepdef/id "sd-1" :pattern-src ".*" :source {:file "a.clj"}}
                                                   {:stepdef/id "sd-2" :pattern-src ".*" :source {:file "b.clj"}}]}]
                       :invalid-arity [{:step {:step/id #uuid "00000000-0000-0000-0000-000000000003"
                                               :step/text "bad arity step"}
                                        :binding {:expected #{2 3} :actual 5}}]
                       :counts {:undefined-count 1 :ambiguous-count 1
                                :invalid-arity-count 1 :total-issues 3}}
          summary (report-edn/build-summary "run" 2 nil {:diagnostics diagnostics})
          issues (-> summary :planning :issues)]
      (is (= 3 (count issues)))
      (is (some #(= :undefined (:type %)) issues))
      (is (some #(= :ambiguous (:type %)) issues))
      (is (some #(= :invalid-arity (:type %)) issues))
      ;; Ambiguous includes alternatives
      (let [ambig (first (filter #(= :ambiguous (:type %)) issues))]
        (is (= 2 (count (:alternatives ambig)))))
      ;; Invalid arity includes arity info
      (let [bad-arity (first (filter #(= :invalid-arity (:type %)) issues))]
        (is (= 5 (-> bad-arity :arity :actual)))))))

(deftest test-planning-diagnostics-hint-passthrough
  (testing "Undefined issues carry the machine-readable :hint and :filter-info maps (sl-s04n)"
    (let [hint {:type :grammar/quoted-ref
                :suggestion "Quoted values are always literals, but Login.code resolves as a glossary ref. Did you mean Login.code (unquoted)?"
                :repaired-text "alice clicks Login.code"
                :did-you-mean "Login.code"}
          diagnostics {:undefined [{:step {:step/id #uuid "00000000-0000-0000-0000-000000000004"
                                           :step/text "alice clicks 'Login.code'"}
                                    :hint hint
                                    :filter-info {:declared-interface :sms}}]
                       :ambiguous []
                       :invalid-arity []
                       :counts {:undefined-count 1 :ambiguous-count 0
                                :invalid-arity-count 0 :total-issues 1}}
          summary (report-edn/build-summary "run" 2 nil {:diagnostics diagnostics})
          issue (-> summary :planning :issues first)]
      (is (= hint (:hint issue)))
      (is (= {:declared-interface :sms} (:filter-info issue))))))

(deftest test-planning-diagnostics-full-location
  (testing "Planning diagnostics include full location (uri, line, column)"
    (let [diagnostics {:undefined [{:step {:step/id #uuid "00000000-0000-0000-0000-000000000001"
                                           :step/text "I do undefined thing"
                                           :step/location {:line 15 :column 7}}
                                    :source-file "features/test.feature"}]
                       :ambiguous []
                       :invalid-arity []
                       :counts {:undefined-count 1 :ambiguous-count 0
                                :invalid-arity-count 0 :total-issues 1}}
          summary (report-edn/build-summary "run" 2 nil {:diagnostics diagnostics})
          issue (-> summary :planning :issues first)]
      (is (= :undefined (:type issue)))
      (is (= "I do undefined thing" (:step/text issue)))
      ;; Full location fields (WI-031.012)
      (is (= "features/test.feature" (:uri issue)))
      (is (= 15 (:line issue)))
      (is (= 7 (:column issue))))))

;; -----------------------------------------------------------------------------
;; SVO Diagnostics Tests (WI-031.002)
;; -----------------------------------------------------------------------------

(deftest test-svo-issues-in-exit-2
  (testing "Exit 2 (planning failure) includes SVO issues alongside binding issues"
    (let [diagnostics {:undefined []
                       :ambiguous []
                       :invalid-arity []
                       :svo-issues [{:type :svo/unknown-subject
                                     :subject :alcie
                                     :known [:alice :admin :guest]
                                     :suggestion :alice
                                     :location {:step-text "When Alcie clicks the button"
                                                :step-id #uuid "00000000-0000-0000-0000-000000000001"}}]
                       :counts {:undefined-count 0
                                :ambiguous-count 0
                                :invalid-arity-count 0
                                :svo-issue-count 1
                                :total-issues 0}}
          summary (report-edn/build-summary "run" 2 nil {:diagnostics diagnostics})]
      (is (= 2 (:run/exit-code summary)))
      (is (= :planning-failed (:run/status summary)))
      (is (some? (:planning summary)))
      ;; SVO issues included
      (is (= 1 (count (-> summary :planning :svo-issues))))
      (is (= :svo/unknown-subject (-> summary :planning :svo-issues first :type)))
      (is (= :alcie (-> summary :planning :svo-issues first :subject))))))

(deftest test-stepdef-issues-in-exit-2
  (testing "Exit 2 includes stepdef glossary issues; wrapper error not duplicated (sl-b49d)"
    (let [stepdef-issue {:type :stepdef/unknown-frame
                         :stepdef-id "sd-1"
                         :source {:file "steps.clj" :line 12}
                         :interface :web
                         :verb :click
                         :frame :nope
                         :message "Step at steps.clj:12 uses unknown frame :nope on verb :web/click; known frames: :on"}
          diagnostics {:undefined []
                       :ambiguous []
                       :invalid-arity []
                       :svo-issues []
                       :stepdef-issues [stepdef-issue]
                       :errors [{:type :stepdef/glossary-mismatch
                                 :issues [stepdef-issue]
                                 :message "Found 1 stepdef(s) inconsistent with the glossary"}]
                       :counts {:undefined-count 0
                                :ambiguous-count 0
                                :invalid-arity-count 0
                                :svo-issue-count 0
                                :stepdef-issue-count 1
                                :total-issues 1}}
          summary (report-edn/build-summary "run" 2 nil {:diagnostics diagnostics})]
      (is (= 2 (:run/exit-code summary)))
      (is (= 1 (count (-> summary :planning :stepdef-issues))))
      (is (= :stepdef/unknown-frame (-> summary :planning :stepdef-issues first :type)))
      (is (str/includes? (-> summary :planning :stepdef-issues first :message)
                         "steps.clj:12"))
      (is (= 1 (-> summary :planning :counts :stepdef-issue-count)))
      ;; The wrapper error is excluded from :errors (payload already
      ;; under :stepdef-issues)
      (is (nil? (-> summary :planning :errors))))))

(deftest test-generic-errors-in-exit-2
  (testing "Exit 2 includes generic planning errors (e.g. glossary load failure)"
    (let [diagnostics {:undefined []
                       :ambiguous []
                       :invalid-arity []
                       :svo-issues []
                       :errors [{:type :svo/missing-glossaries-config
                                 :message "Shifted mode requires :glossaries config"}]
                       :counts {:total-issues 0}}
          summary (report-edn/build-summary "run" 2 nil {:diagnostics diagnostics})]
      (is (= 1 (count (-> summary :planning :errors))))
      (is (= :svo/missing-glossaries-config (-> summary :planning :errors first :type)))
      (is (= "Shifted mode requires :glossaries config"
             (-> summary :planning :errors first :message)))
      ;; EDN-safe: round-trips through pr-str/read-string
      (is (= summary (read-string (pr-str summary)))))))

(deftest test-nested-sub-errors-survive-serialization
  (testing "aggregate errors (intents/parse/step-load, sl-0n6t) keep nested
            :errors — recursively projected, records and extra keys dropped"
    (let [diagnostics {:errors [{:type :parse/failed
                                 :message "2 parse error(s) in feature file(s)"
                                 :errors [{:type :unexpected-token
                                           :message "Unexpected token: :step-line"
                                           :path "features/bad.feature"
                                           :token {:not "edn-safe records in prod"}
                                           :location {:line 3}}
                                          {:type :unexpected-eof
                                           :message "Unexpected EOF"
                                           :path "features/worse.feature"}]}]}
          summary (report-edn/build-summary "run" 2 nil {:diagnostics diagnostics})
          err (-> summary :planning :errors first)]
      (is (= :parse/failed (:type err)))
      (is (= 2 (count (:errors err))) "sub-errors survive")
      (is (= "features/bad.feature" (-> err :errors first :path))
          ":path is kept for file attribution")
      (is (nil? (-> err :errors first :token))
          "non-projected keys (record carriers) are dropped")
      (is (nil? (-> err :errors first :location)))
      (is (= summary (read-string (pr-str summary))) "round-trips"))))

(deftest test-annotation-and-macro-errors-in-exit-2
  (testing "Exit 2 includes annotation and macro errors"
    (let [diagnostics {:undefined []
                       :ambiguous []
                       :invalid-arity []
                       :svo-issues []
                       :annotation-errors [{:type :annotation/unknown-interface
                                            :message "Step declares interface :foo"}]
                       :macro-errors [{:type :macro/undefined
                                       :message "Undefined macro: 'login +'"}]
                       :counts {:total-issues 0}}
          summary (report-edn/build-summary "run" 2 nil {:diagnostics diagnostics})]
      (is (= :annotation/unknown-interface
             (-> summary :planning :annotation-errors first :type)))
      (is (= :macro/undefined
             (-> summary :planning :macro-errors first :type))))))

(deftest test-svo-issues-in-exit-0
  (testing "Exit 0 (passed) includes :diagnostics key when SVO issues present"
    (let [result {:scenarios [{:status :passed :steps [{:status :passed}]}]
                  :counts {:passed 1 :failed 0 :pending 0 :skipped 0}}
          diagnostics {:undefined []
                       :ambiguous []
                       :invalid-arity []
                       :svo-issues [{:type :svo/unknown-verb
                                     :verb :smash
                                     :known [:click :fill :see]
                                     :location {:step-text "When Alice smashes the button"
                                                :step-id #uuid "00000000-0000-0000-0000-000000000002"}}]
                       :counts {:undefined-count 0
                                :ambiguous-count 0
                                :invalid-arity-count 0
                                :svo-issue-count 1
                                :total-issues 0}}
          summary (report-edn/build-summary "run" 0 result {:diagnostics diagnostics})]
      (is (= 0 (:run/exit-code summary)))
      (is (= :passed (:run/status summary)))
      ;; :diagnostics key present with SVO issues
      (is (some? (:diagnostics summary)))
      (is (= 1 (count (-> summary :diagnostics :svo-issues))))
      (is (= :svo/unknown-verb (-> summary :diagnostics :svo-issues first :type)))
      (is (= 1 (-> summary :diagnostics :counts :svo-issue-count))))))

(deftest test-no-diagnostics-key-when-empty
  (testing "Exit 0 (passed) omits :diagnostics key when no SVO issues"
    (let [result {:scenarios [{:status :passed :steps [{:status :passed}]}]
                  :counts {:passed 1 :failed 0 :pending 0 :skipped 0}}
          ;; Empty diagnostics (no issues)
          diagnostics {:undefined []
                       :ambiguous []
                       :invalid-arity []
                       :svo-issues []
                       :counts {:undefined-count 0
                                :ambiguous-count 0
                                :invalid-arity-count 0
                                :svo-issue-count 0
                                :total-issues 0}}
          summary (report-edn/build-summary "run" 0 result {:diagnostics diagnostics})]
      (is (= 0 (:run/exit-code summary)))
      ;; No :diagnostics key (no noise on clean runs)
      (is (nil? (:diagnostics summary))))))

(deftest test-no-diagnostics-key-when-none-passed
  (testing "Exit 0 with no diagnostics passed omits the :diagnostics key entirely"
    (let [result {:scenarios [{:status :passed :steps [{:status :passed}]}]
                  :counts {:passed 1 :failed 0 :pending 0 :skipped 0}}
          ;; No diagnostics passed
          summary (report-edn/build-summary "run" 0 result {})]
      (is (= 0 (:run/exit-code summary)))
      ;; No :diagnostics key
      (is (nil? (:diagnostics summary))))))

(deftest test-svo-issues-in-exit-1
  (testing "Exit 1 (failures) includes both failures and diagnostics"
    (let [result {:scenarios [{:status :failed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000001"
                                                    :pickle/name "Test"}}
                               :steps [{:status :failed
                                        :step {:step/id #uuid "00000000-0000-0000-0000-000000000002"
                                               :step/text "When Alice clicks"}
                                        :binding {:source {:file "s.clj" :line 10}}
                                        :error {:type :step/exception :message "Not found"}}]}]
                  :counts {:passed 0 :failed 1 :pending 0 :skipped 0}}
          diagnostics {:undefined []
                       :ambiguous []
                       :invalid-arity []
                       :svo-issues [{:type :svo/unknown-interface
                                     :interface :foobar
                                     :known [:web :api]
                                     :location {:step-text "When Alice clicks"
                                                :step-id #uuid "00000000-0000-0000-0000-000000000002"}}]
                       :counts {:undefined-count 0
                                :ambiguous-count 0
                                :invalid-arity-count 0
                                :svo-issue-count 1
                                :total-issues 0}}
          summary (report-edn/build-summary "run" 1 result {:diagnostics diagnostics})]
      (is (= 1 (:run/exit-code summary)))
      (is (= :failed (:run/status summary)))
      ;; Has both failures and diagnostics
      (is (= 1 (count (:failures summary))))
      (is (some? (:diagnostics summary)))
      (is (= 1 (count (-> summary :diagnostics :svo-issues)))))))

;; -----------------------------------------------------------------------------
;; Macro Provenance Tests (WI-031.003)
;; -----------------------------------------------------------------------------

(deftest test-edn-failure-includes-macro-provenance
  (testing "Failed macro step includes :macro field with provenance"
    (let [result {:scenarios [{:status :failed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000001"
                                                    :pickle/name "Login test"
                                                    :pickle/source-file "features/login.feature"}}
                               :steps [{:status :failed
                                        :step {:step/id #uuid "00000000-0000-0000-0000-000000000002"
                                               :step/text "I click login button"
                                               :step/macro {:role :expanded
                                                            :key "login as alice"
                                                            :call-site {:line 12 :column 5}
                                                            :definition-step {:file "macros/auth.ini"
                                                                              :line 5
                                                                              :column 3}}}
                                        :binding {:source {:file "src/steps/auth.clj" :line 42}}
                                        :error {:type :step/exception :message "Not found"}}]}]
                  :counts {:passed 0 :failed 1 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run" 1 result {})]
      (is (= 1 (count (:failures summary))))
      (let [failure (first (:failures summary))]
        ;; Has :macro key
        (is (some? (:macro failure)))
        ;; Macro key value
        (is (= "login as alice" (-> failure :macro :key)))
        ;; Call-site with assembled file path
        (is (= "features/login.feature" (-> failure :macro :call-site :file)))
        (is (= 12 (-> failure :macro :call-site :line)))
        (is (= 5 (-> failure :macro :call-site :column)))
        ;; Definition-step (for expanded role)
        (is (= "macros/auth.ini" (-> failure :macro :definition-step :file)))
        (is (= 5 (-> failure :macro :definition-step :line)))))))

(deftest test-edn-failure-wrapper-has-call-site-only
  (testing "Failed wrapper step has call-site but no definition-step"
    (let [result {:scenarios [{:status :failed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000001"
                                                    :pickle/name "Test"
                                                    :pickle/source-file "features/test.feature"}}
                               :steps [{:status :failed
                                        :step {:step/id #uuid "00000000-0000-0000-0000-000000000002"
                                               :step/text "login as alice"
                                               :step/synthetic? true
                                               :step/macro {:role :call
                                                            :key "login as alice"
                                                            :call-site {:line 8 :column 5}}}
                                        :binding {}
                                        :error {:type :step/exception :message "Failed"}}]}]
                  :counts {:passed 0 :failed 1 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run" 1 result {})]
      #_{:clj-kondo/ignore [:redundant-let]}
      (let [failure (first (:failures summary))]
        ;; Has :macro with call-site
        (is (some? (:macro failure)))
        (is (= "login as alice" (-> failure :macro :key)))
        (is (some? (-> failure :macro :call-site)))
        ;; No definition-step for :call role
        (is (nil? (-> failure :macro :definition-step)))))))

(deftest test-edn-failure-non-macro-no-macro-key
  (testing "Non-macro step failure has no :macro key"
    (let [result {:scenarios [{:status :failed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000001"
                                                    :pickle/name "Basic test"
                                                    :pickle/source-file "features/basic.feature"}}
                               :steps [{:status :failed
                                        :step {:step/id #uuid "00000000-0000-0000-0000-000000000002"
                                               :step/text "I do something"}
                                        :binding {:source {:file "steps.clj" :line 10}}
                                        :error {:type :step/exception :message "Error"}}]}]
                  :counts {:passed 0 :failed 1 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run" 1 result {})]
      #_{:clj-kondo/ignore [:redundant-let]}
      (let [failure (first (:failures summary))]
        ;; No :macro key
        (is (nil? (:macro failure)))
        ;; Still has other fields
        (is (= "I do something" (:step/text failure)))
        (is (some? (:location failure)))))))

;; -----------------------------------------------------------------------------
;; :error status — scenario-level hook failures (sl-esq)
;; -----------------------------------------------------------------------------

(deftest test-error-scenario-contributes-scenario-level-failure
  (testing "an :error scenario yields a scenario-level :failures entry with hook attribution"
    (let [sid (java.util.UUID/randomUUID)
          result {:scenarios [{:status :error
                               :error {:type :hook/before-failed
                                       :message "connection refused"
                                       :hook "reset-db"
                                       :registration {:path "/proj/hooks.clj"}
                                       :tag-source {:file "features/x.feature" :line 3}}
                               :plan {:plan/pickle {:pickle/name "Seeded" :pickle/id sid}}
                               :steps [{:status :skipped
                                        :step {:step/text "I log in"}}]}]
                  :counts {:passed 0 :failed 0 :pending 0 :skipped 0 :error 1}}
          summary (report-edn/build-summary "run-err" 1 result {})]
      (is (= 1 (-> summary :counts :error)))
      (let [failure (first (:failures summary))]
        (is (= "Seeded" (:scenario/name failure)))
        (is (= sid (:scenario/id failure)))
        (is (= :hook/before-failed (-> failure :error :type)))
        (is (= "reset-db" (-> failure :error :hook)))
        (is (= "/proj/hooks.clj" (-> failure :error :registration :path)))
        (is (= 3 (-> failure :error :tag-source :line))))))
  (testing "after-throw plus step failure: scenario entry AND failed-step entry"
    (let [result {:scenarios [{:status :error
                               :error {:type :hook/after-failed :message "boom"
                                       :hook "screenshot"}
                               :plan {:plan/pickle {:pickle/name "Both"
                                                    :pickle/id (java.util.UUID/randomUUID)}}
                               :steps [{:status :failed
                                        :step {:step/text "I click" :step/id "s1"}
                                        :binding {:source {:file "steps.clj" :line 5}}
                                        :error {:type :step/exception :message "no element"}}]}]
                  :counts {:passed 0 :failed 0 :pending 0 :skipped 0 :error 1}}
          summary (report-edn/build-summary "run-both" 1 result {})]
      (is (= 2 (count (:failures summary))))
      (is (= "screenshot" (-> summary :failures first :error :hook)))
      (is (= "I click" (-> summary :failures second :step/text))))))

;; -----------------------------------------------------------------------------
;; Attachment Refs (sl-oxiz)
;; -----------------------------------------------------------------------------

(def ^:private screenshot-ref
  #:attachment{:kind :screenshot
               :path "attachments/failing/shot.png"
               :media-type "image/png"
               :bytes 1024
               :source :capture-policy})

(def ^:private console-ref
  #:attachment{:kind :console
               :path "attachments/failing/console.log"
               :source [:hook "drain-console"]})

(deftest test-attachments-exit-1-inline-and-indexed
  (testing "failed step's refs ride its :failures entry AND the top-level index"
    (let [sid #uuid "00000000-0000-0000-0000-00000000000a"
          stid #uuid "00000000-0000-0000-0000-00000000000b"
          result {:scenarios [{:status :failed
                               :plan {:plan/pickle {:pickle/id sid
                                                    :pickle/name "Failing"}}
                               :steps [{:status :failed
                                        :step {:step/id stid
                                               :step/text "I click button"}
                                        :binding {:source {:file "s.clj" :line 10}}
                                        :error {:type :step/exception
                                                :message "Not found"}
                                        :attachments [screenshot-ref]}]}]
                  :counts {:passed 0 :failed 1 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run-att-1" 1 result {})]
      (is (= [screenshot-ref] (-> summary :failures first :attachments))
          "refs inline on the failure entry, verbatim")
      (let [entry (first (:attachments summary))]
        (is (= "Failing" (:scenario/name entry)))
        (is (= sid (:scenario/id entry)))
        (is (nil? (:attachments entry))
            "no scenario-level claims -> no scenario-level :attachments")
        (is (= [{:step/id stid
                 :step/text "I click button"
                 :attachments [screenshot-ref]}]
               (:steps entry))
            "the index carries the same refs verbatim")))))

(deftest test-attachments-exit-4-scenario-level
  (testing "scenario-level claims ride the scenario :error entry AND the index"
    (let [sid #uuid "00000000-0000-0000-0000-00000000000c"
          result {:scenarios [{:status :error
                               :error {:type :hook/after-failed :message "boom"}
                               :attachments [console-ref]
                               :plan {:plan/pickle {:pickle/id sid
                                                    :pickle/name "Degraded"}}
                               :steps [{:status :skipped
                                        :step {:step/text "I log in"}}]}]
                  :counts {:passed 0 :failed 0 :pending 0 :skipped 0 :error 1}}
          summary (report-edn/build-summary "run-att-4" 4 result {})]
      (is (= [console-ref] (-> summary :failures first :attachments))
          "scenario-level refs inline on the scenario failure entry")
      (let [entry (first (:attachments summary))]
        (is (= [console-ref] (:attachments entry)))
        (is (nil? (:steps entry))
            "no step carries refs -> no :steps in the index entry")))))

(deftest test-attachments-exit-0-index-only
  (testing "green-run evidence (:every-step / After-hook claims) appears in
            the index — the only surface, since :failures is absent"
    (let [sid #uuid "00000000-0000-0000-0000-00000000000d"
          stid #uuid "00000000-0000-0000-0000-00000000000e"
          result {:scenarios [{:status :passed
                               :attachments [console-ref]
                               :plan {:plan/pickle {:pickle/id sid
                                                    :pickle/name "Green"}}
                               :steps [{:status :passed
                                        :step {:step/id stid
                                               :step/text "I click button"}
                                        :attachments [screenshot-ref]}]}
                              {:status :passed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-00000000000f"
                                                    :pickle/name "Bare"}}
                               :steps [{:status :passed
                                        :step {:step/text "I wait"}}]}]
                  :counts {:passed 2 :failed 0 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run-att-0" 0 result {})]
      (is (nil? (:failures summary)))
      (is (= 1 (count (:attachments summary)))
          "only the evidence-carrying scenario is indexed")
      (let [entry (first (:attachments summary))]
        (is (= "Green" (:scenario/name entry)))
        (is (= sid (:scenario/id entry)))
        (is (= [console-ref] (:attachments entry)))
        (is (= [{:step/id stid
                 :step/text "I click button"
                 :attachments [screenshot-ref]}]
               (:steps entry)))))))

(deftest test-attachments-absent-when-none
  (testing "attachment-less runs are byte-identical: no top-level key, no
            :attachments on failure entries (golden-safety, policy :never)"
    (let [result {:scenarios [{:status :failed
                               :plan {:plan/pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000010"
                                                    :pickle/name "Plain"}}
                               :steps [{:status :failed
                                        :step {:step/text "I click"}
                                        :binding {:source {:file "s.clj" :line 1}}
                                        :error {:type :step/exception
                                                :message "nope"}}]}]
                  :counts {:passed 0 :failed 1 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run-none" 1 result {})]
      (is (not (contains? summary :attachments)))
      (is (not (contains? (first (:failures summary)) :attachments))))))

(deftest test-attachments-round-trip-valid-edn
  (testing "a summary carrying refs survives prn-summary -> edn/read-string"
    (let [sid #uuid "00000000-0000-0000-0000-000000000011"
          result {:scenarios [{:status :passed
                               :attachments [console-ref]
                               :plan {:plan/pickle {:pickle/id sid
                                                    :pickle/name "Round trip"}}
                               :steps [{:status :passed
                                        :step {:step/id #uuid "00000000-0000-0000-0000-000000000012"
                                               :step/text "I click"}
                                        :attachments [screenshot-ref]}]}]
                  :counts {:passed 1 :failed 0 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run-rt" 0 result {})
          parsed (edn/read-string (with-out-str (report-edn/prn-summary summary)))]
      (is (= (:attachments summary) (:attachments parsed))
          "refs (namespaced keys, uuids, hook-tuple source) are EDN-native"))))

;; -----------------------------------------------------------------------------
;; Dropped-events Tests (sl-d7ks)
;; -----------------------------------------------------------------------------

(deftest test-build-summary-dropped-events
  (testing "Executed summaries carry :dropped-events when opts pass one"
    (let [result {:scenarios [{:status :passed :steps [{:status :passed}]}]
                  :counts {:passed 1 :failed 0 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run-drop" 0 result {:dropped-events 3})]
      (is (= 3 (:dropped-events summary)))))
  (testing "Absent-when-zero: no key in opts -> no key in the summary (golden safety)"
    (let [result {:scenarios [{:status :passed :steps [{:status :passed}]}]
                  :counts {:passed 1 :failed 0 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run-clean" 0 result {})]
      (is (not (contains? summary :dropped-events)))))
  (testing "nil threads through as absent (the reporter passes nil when its
            run-summary lacks the key)"
    (let [result {:scenarios [] :counts {:passed 0 :failed 0 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run-nil" 0 result {:dropped-events nil})]
      (is (not (contains? summary :dropped-events))))))

(deftest test-edn-reporter-threads-dropped-events
  (testing "EdnReporter's printed form carries :dropped-events off the run-summary"
    (let [r (report-edn/make-reporter)
          out (with-out-str
                (reporter/on-run-end r {:run-id "run-x" :exit-code 0
                                        :counts {:passed 1 :failed 0 :pending 0 :skipped 0}
                                        :status :passed
                                        :dropped-events 2}))
          form (edn/read-string out)]
      (is (= 2 (:dropped-events form)))))
  (testing "No :dropped-events on the run-summary -> none in the printed form"
    (let [r (report-edn/make-reporter)
          out (with-out-str
                (reporter/on-run-end r {:run-id "run-y" :exit-code 0
                                        :counts {:passed 1 :failed 0 :pending 0 :skipped 0}
                                        :status :passed}))
          form (edn/read-string out)]
      (is (not (contains? form :dropped-events))))))

(deftest test-run-summary-spec-dropped-events
  (let [base {:run-id "r" :exit-code 0 :status :passed :counts {:passed 1}}]
    (is (s/valid? ::reporter/run-summary base))
    (is (s/valid? ::reporter/run-summary (assoc base :dropped-events 5)))
    (testing "zero is a spec violation — absent-when-zero IS the contract"
      (is (not (s/valid? ::reporter/run-summary (assoc base :dropped-events 0)))))))

;; -----------------------------------------------------------------------------
;; Results-path Tests (sl-7dq5 F11b)
;; -----------------------------------------------------------------------------

(deftest test-build-summary-results-path
  (testing "Executed summaries carry :run/results-path when opts pass one"
    (let [result {:scenarios [{:status :passed :steps [{:status :passed}]}]
                  :counts {:passed 1 :failed 0 :pending 0 :skipped 0}}
          summary (report-edn/build-summary
                   "run-rp" 0 result {:results-path "results/20260825T112500Z/movie-api"})]
      (is (= "results/20260825T112500Z/movie-api" (:run/results-path summary)))))
  (testing "Absent-when-unknown: no key in opts -> no key in the summary
            (dry-run/exit-2/3/:defaults runs create no dir — golden safety)"
    (let [result {:scenarios [{:status :passed :steps [{:status :passed}]}]
                  :counts {:passed 1 :failed 0 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run-nodir" 0 result {})]
      (is (not (contains? summary :run/results-path)))))
  (testing "nil threads through as absent"
    (let [result {:scenarios [] :counts {:passed 0 :failed 0 :pending 0 :skipped 0}}
          summary (report-edn/build-summary "run-nilrp" 0 result {:results-path nil})]
      (is (not (contains? summary :run/results-path))))))

(deftest test-edn-reporter-threads-results-path
  (testing "EdnReporter's printed form carries :run/results-path off the run-summary"
    (let [r (report-edn/make-reporter)
          out (with-out-str
                (reporter/on-run-end r {:run-id "run-rp" :exit-code 0
                                        :counts {:passed 1 :failed 0 :pending 0 :skipped 0}
                                        :status :passed
                                        :results-path "results/20260825T112500Z"}))
          form (edn/read-string out)]
      (is (= "results/20260825T112500Z" (:run/results-path form)))))
  (testing "No :results-path on the run-summary -> none in the printed form"
    (let [r (report-edn/make-reporter)
          out (with-out-str
                (reporter/on-run-end r {:run-id "run-norp" :exit-code 0
                                        :counts {:passed 1 :failed 0 :pending 0 :skipped 0}
                                        :status :passed}))
          form (edn/read-string out)]
      (is (not (contains? form :run/results-path))))))

(deftest test-run-summary-spec-results-path
  (let [base {:run-id "r" :exit-code 0 :status :passed :counts {:passed 1}}]
    (is (s/valid? ::reporter/run-summary
                  (assoc base :results-path "results/20260825T112500Z/login")))))
