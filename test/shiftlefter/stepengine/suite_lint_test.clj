(ns shiftlefter.stepengine.suite-lint-test
  "Tests for the suite-load lint pass (sl-unz):
   - :stepdef/undefined-interface
   - :stepdef/missing-capability  (lifted from sl-ewn per-step → per-stepdef)
   - :glossary/orphan-type"
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.stepengine.suite-lint :as suite-lint]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- mk-stepdef
  "Build a stepdef-info map shaped like both `registry/register!` output
   and `suite-lint/used-stepdef-infos` projection — the lint reads the
   intersection (`:pattern-src`, `:source`, `:metadata`)."
  [pattern-src metadata]
  {:stepdef/id  (str "sd-" (hash pattern-src))
   :pattern-src pattern-src
   :source      {:ns 'test :file "test.clj" :line 42}
   :metadata    metadata})

(defn- mk-bound-step
  "A `:status :matched` bound-step carrying just the binding fields the
   lint's projection helper needs."
  [stepdef-info]
  {:status :matched
   :step   {:step/text (:pattern-src stepdef-info)}
   :binding {:stepdef/id  (:stepdef/id stepdef-info)
             :pattern-src (:pattern-src stepdef-info)
             :source      (:source stepdef-info)
             :metadata    (:metadata stepdef-info)}})

(defn- mk-plan
  "A plan whose steps are matched bound-steps for the given stepdefs."
  [stepdef-infos]
  {:plan/id     (java.util.UUID/randomUUID)
   :plan/pickle {:pickle/source-file "fixture.feature"}
   :plan/steps  (mapv mk-bound-step stepdef-infos)
   :plan/runnable? true})

(def ^:private fake-registry
  "Adapter registry used across these tests."
  {:real-isms {:provides [:shiftlefter.sms.protocol/ISMS]}
   :test-isms {:provides [:shiftlefter.sms.protocol/ISMS
                          :shiftlefter.sms.protocol/ISMSInbound]}
   :etaoin    {:provides [:shiftlefter.browser.protocol/IBrowser]}
   :nothing   {:provides []}})

;; ---------------------------------------------------------------------------
;; No exempt path (sl-6wh9): nil :interfaces reads as the empty set
;; ---------------------------------------------------------------------------

(deftest lint-runs-against-empty-set-without-interfaces-config
  (testing "When :interfaces is nil, the checks run against zero configured
            instances — the :sms stepdef finds no provider (the old vanilla
            skip is retired)"
    (let [stepdefs [(mk-stepdef "x" {:interface :sms
                                     :requires-protocols
                                     [:shiftlefter.sms.protocol/ISMSInbound]})]
          issues (suite-lint/lint-suite stepdefs [] nil fake-registry)]
      (is (= [:stepdef/undefined-interface] (mapv :type issues)))
      (is (= (suite-lint/lint-suite stepdefs [] {} fake-registry) issues)
          "nil and {} are one shape"))))

;; ---------------------------------------------------------------------------
;; :stepdef/undefined-interface
;; ---------------------------------------------------------------------------

(deftest undefined-interface-flagged
  (testing "Stepdef with :interface :sms fails when no :sms in :interfaces config"
    (let [stepdefs   [(mk-stepdef "x" {:interface :sms})]
          interfaces {:web {:type :web :adapter :etaoin}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)]
      (is (= 1 (count issues)))
      (let [{:keys [type interface configured severity]} (first issues)]
        (is (= :stepdef/undefined-interface type))
        (is (= :sms interface))
        (is (= [:web] configured))
        (is (= :error severity))))))

(deftest configured-interface-passes
  (testing "Stepdef with :interface matching a configured key produces no issue"
    (let [stepdefs   [(mk-stepdef "x" {:interface :sms})]
          interfaces {:sms {:type :sms :adapter :real-isms}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)]
      (is (empty? issues)))))

(deftest stepdef-without-interface-skipped
  (testing "Legacy stepdefs (no :interface metadata) are silently skipped"
    (let [stepdefs   [(mk-stepdef "x" nil)
                      (mk-stepdef "y" {:svo {:verb :click :frame :default}})]
          interfaces {:web {:type :web :adapter :etaoin}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)]
      (is (empty? issues)))))

;; ---------------------------------------------------------------------------
;; :stepdef/missing-capability  (lifted from sl-ewn per-step check)
;; ---------------------------------------------------------------------------

(deftest missing-capability-when-adapter-lacks-protocol
  (testing "Stepdef requires ISMSInbound; :real-isms only provides ISMS → fails"
    (let [stepdefs   [(mk-stepdef
                       "x"
                       {:interface :sms
                        :requires-protocols
                        [:shiftlefter.sms.protocol/ISMSInbound]})]
          interfaces {:sms {:type :sms :adapter :real-isms}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)]
      (is (= 1 (count issues)))
      (let [{:keys [type interface adapter missing provides severity]}
            (first issues)]
        (is (= :stepdef/missing-capability type))
        (is (= :sms interface))
        (is (= :real-isms adapter))
        (is (= [:shiftlefter.sms.protocol/ISMSInbound] missing))
        (is (contains? provides :shiftlefter.sms.protocol/ISMS))
        (is (= :error severity))))))

(deftest missing-capability-when-no-adapter-configured
  (testing "Configured interface has no :adapter → :adapter nil on issue"
    (let [stepdefs   [(mk-stepdef
                       "x"
                       {:interface :sms
                        :requires-protocols
                        [:shiftlefter.sms.protocol/ISMS]})]
          ;; Interface configured but no :adapter key present
          interfaces {:sms {:type :sms}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)
          issue      (first issues)]
      (is (= :stepdef/missing-capability (:type issue)))
      (is (nil? (:adapter issue)))
      (is (= [:shiftlefter.sms.protocol/ISMS] (:missing issue))))))

(deftest unknown-adapter-deferred-to-exact-registry-check
  (testing "Adapter named but absent from the registry → NO capability issue:
            registry membership is owned by the plan-aware exact check
            (setup/bound-adapter-errors, sl-kkoh). Before sl-8cvd (P2-2)
            this shape drew the false remedy 'only provides []' and
            shadowed that check for every :requires-protocols stepdef"
    (let [stepdefs   [(mk-stepdef
                       "x"
                       {:interface :sms
                        :requires-protocols
                        [:shiftlefter.sms.protocol/ISMSInbound]})]
          interfaces {:sms {:type :sms :adapter :ghost-isms}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)]
      (is (empty? issues)))))

(deftest empty-provides-on-known-adapter-still-fires
  (testing "A registry MEMBER that genuinely provides nothing keeps the
            (now true) provides message — the sl-8cvd P2-2 skip is for
            unknown adapters only"
    (let [stepdefs   [(mk-stepdef
                       "x"
                       {:interface :sms
                        :requires-protocols
                        [:shiftlefter.sms.protocol/ISMS]})]
          interfaces {:sms {:type :sms :adapter :nothing}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)
          issue      (first issues)]
      (is (= 1 (count issues)))
      (is (= :stepdef/missing-capability (:type issue)))
      (is (= :nothing (:adapter issue)))
      (is (= #{} (:provides issue)))
      (is (= [:shiftlefter.sms.protocol/ISMS] (:missing issue))))))

(deftest capability-passes-when-adapter-provides-everything
  (testing ":test-isms provides ISMS+ISMSInbound; required protocols match → no issue"
    (let [stepdefs   [(mk-stepdef
                       "x"
                       {:interface :sms
                        :requires-protocols
                        [:shiftlefter.sms.protocol/ISMS
                         :shiftlefter.sms.protocol/ISMSInbound]})]
          interfaces {:sms {:type :sms :adapter :test-isms}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)]
      (is (empty? issues)))))

(deftest capability-reports-only-missing-subset
  (testing "Required [ISMS ISMSInbound], adapter provides [ISMS] → missing [ISMSInbound] only"
    (let [stepdefs   [(mk-stepdef
                       "x"
                       {:interface :sms
                        :requires-protocols
                        [:shiftlefter.sms.protocol/ISMS
                         :shiftlefter.sms.protocol/ISMSInbound]})]
          interfaces {:sms {:type :sms :adapter :real-isms}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)
          issue      (first issues)]
      (is (= [:shiftlefter.sms.protocol/ISMSInbound] (:missing issue)))
      (is (contains? (:provides issue) :shiftlefter.sms.protocol/ISMS)))))

(deftest capability-skipped-when-interface-itself-undefined
  (testing "If :interface is undefined, the undefined-interface issue subsumes;
            we don't double-report a capability issue"
    (let [stepdefs   [(mk-stepdef
                       "x"
                       {:interface :sms
                        :requires-protocols
                        [:shiftlefter.sms.protocol/ISMSInbound]})]
          ;; :sms not in interfaces — undefined-interface fires, capability skips
          interfaces {:web {:type :web :adapter :etaoin}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)]
      (is (= 1 (count issues)))
      (is (= :stepdef/undefined-interface (:type (first issues)))))))

(deftest capability-skipped-when-no-requires-protocols
  (testing "Stepdef with :interface but no :requires-protocols → capability check is no-op"
    (let [stepdefs   [(mk-stepdef "x" {:interface :sms})]
          interfaces {:sms {:type :sms :adapter :real-isms}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)]
      (is (empty? issues)))))

(deftest one-stepdef-one-issue-regardless-of-uses
  (testing "Suite-load reports the stepdef once, not per-use (the sl-unz win over sl-ewn)"
    ;; Three stepdefs — the same misconfiguration would surface 3× under
    ;; the old per-step check if each had multiple bound steps. Here we
    ;; just verify the per-stepdef shape: 3 stepdefs → 3 issues, each
    ;; pointing at its own source.
    (let [stepdefs   [(mk-stepdef "a"
                                  {:interface :sms
                                   :requires-protocols
                                   [:shiftlefter.sms.protocol/ISMSInbound]})
                      (mk-stepdef "b"
                                  {:interface :sms
                                   :requires-protocols
                                   [:shiftlefter.sms.protocol/ISMSInbound]})
                      (mk-stepdef "c"
                                  {:interface :sms
                                   :requires-protocols
                                   [:shiftlefter.sms.protocol/ISMSInbound]})]
          interfaces {:sms {:type :sms :adapter :real-isms}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)]
      (is (= 3 (count issues)))
      (is (every? #(= :stepdef/missing-capability (:type %)) issues)))))

;; ---------------------------------------------------------------------------
;; used-stepdef-infos — projection from plans
;; ---------------------------------------------------------------------------

(deftest used-stepdef-infos-dedupes-by-id
  (testing "A stepdef matched by N steps in the suite yields one info"
    (let [sd       (mk-stepdef "x" {:interface :sms})
          plans    [(mk-plan [sd sd sd])         ; same stepdef 3× in one plan
                    (mk-plan [sd])]              ; same again in another plan
          infos    (suite-lint/used-stepdef-infos plans)]
      (is (= 1 (count infos)))
      (is (= "x" (:pattern-src (first infos)))))))

(deftest used-stepdef-infos-skips-non-matched-statuses
  (testing "Undefined / ambiguous / synthetic steps don't surface a stepdef"
    (let [sd     (mk-stepdef "x" {:interface :sms})
          plans  [{:plan/steps [{:status :undefined :step {:step/text "nope"}}
                                {:status :ambiguous :step {:step/text "huh"}}
                                {:status :synthetic :step {:step/text "macro"}}
                                (mk-bound-step sd)]}]
          infos  (suite-lint/used-stepdef-infos plans)]
      (is (= 1 (count infos)))
      (is (= "x" (:pattern-src (first infos)))))))

(deftest loaded-but-unused-stepdefs-not-flagged
  (testing "A stepdef with bad config that NO bound step matches is not flagged"
    ;; This is the sl-unz behavior change vs my first cut: lint scope is
    ;; matched stepdefs only. SMS stepdefs loaded into a registry but
    ;; not used by any scenario should not surface :sms config issues.
    (let [used      (mk-stepdef "x" {:interface :web})
          ;; Built into the stepdef pool, but not referenced by any plan
          plans     [(mk-plan [used])]
          infos     (suite-lint/used-stepdef-infos plans)
          interfaces {:web {:type :web :adapter :etaoin}}
          issues    (suite-lint/lint-suite infos nil interfaces fake-registry)]
      (is (empty? issues)))))

;; ---------------------------------------------------------------------------
;; :glossary/orphan-type
;; ---------------------------------------------------------------------------

(deftest glossary-orphan-type-flagged
  (testing "Project declares verb glossary :type :sms but no :sms interface configured"
    (let [project-types [:web :sms]
          interfaces    {:web {:type :web :adapter :etaoin}}
          issues        (suite-lint/lint-suite [] project-types interfaces fake-registry)
          issue         (first issues)]
      (is (= 1 (count issues)))
      (is (= :glossary/orphan-type (:type issue)))
      (is (= :sms (:glossary-type issue)))
      (is (= [:web] (:configured-types issue))))))

(deftest lint-project-is-the-corpus-free-entry-point
  (testing "sl-a55s: lint-project runs ONLY the check that needs no bound
            suite (:glossary/orphan-type) — the projection's entry point.
            It is the same implementation lint-suite runs, not a second one"
    (let [project-types [:web :sms]
          interfaces    {:web {:type :web :adapter :etaoin}}]
      (is (= [:glossary/orphan-type]
             (mapv :type (suite-lint/lint-project project-types interfaces))))
      (is (= (suite-lint/lint-project project-types interfaces)
             (suite-lint/lint-suite [] project-types interfaces fake-registry))
          "identical issues to lint-suite's orphan part")
      (is (= [] (suite-lint/lint-project project-types nil))
          "nil interfaces reads as the empty set, as in lint-suite")
      (is (= [] (suite-lint/lint-project nil interfaces))))))

(deftest glossary-types-all-covered
  (testing "Every project glossary type matches some configured interface :type"
    (let [project-types [:web :sms]
          interfaces    {:web {:type :web :adapter :etaoin}
                         :sms {:type :sms :adapter :real-isms}}
          issues        (suite-lint/lint-suite [] project-types interfaces fake-registry)]
      (is (empty? issues)))))

(deftest glossary-multiple-interfaces-same-type
  (testing "Two interfaces sharing a :type both satisfy a single glossary type"
    (let [project-types [:web]
          interfaces    {:public-web {:type :web :adapter :etaoin}
                         :admin-web  {:type :web :adapter :etaoin}}
          issues        (suite-lint/lint-suite [] project-types interfaces fake-registry)]
      (is (empty? issues)))))

(deftest framework-defaults-not-checked
  (testing "Default :sms verb glossary loaded transparently doesn't fire orphan
            check when project didn't declare it. The 'project-glossary-types'
            arg lists ONLY user-declared types."
    ;; project-types is empty — even though the merged glossary in real
    ;; runtime contains :sms verbs from defaults, lint-suite is told the
    ;; project didn't declare :sms, so no orphan issue.
    (let [interfaces {:web {:type :web :adapter :etaoin}}
          issues     (suite-lint/lint-suite [] [] interfaces fake-registry)]
      (is (empty? issues)))))

;; ---------------------------------------------------------------------------
;; Counts
;; ---------------------------------------------------------------------------

(deftest issue-counts-buckets
  (testing "issue-counts groups by :type and totals correctly"
    (let [issues [{:type :stepdef/undefined-interface :severity :error}
                  {:type :stepdef/undefined-interface :severity :error}
                  {:type :stepdef/missing-capability  :severity :error}
                  {:type :glossary/orphan-type        :severity :error}]
          counts (suite-lint/issue-counts issues)]
      (is (= 2 (:undefined-interface-count counts)))
      (is (= 1 (:missing-capability-count counts)))
      (is (= 1 (:orphan-glossary-type-count counts)))
      (is (= 4 (:total counts))))))

;; ---------------------------------------------------------------------------
;; Default registry path
;; ---------------------------------------------------------------------------

(deftest defaults-to-built-in-registry-when-nil
  (testing "Passing nil registry falls back to adapters/default-registry"
    ;; :sms-twilio is in default-registry and provides only ISMS
    (let [stepdefs   [(mk-stepdef
                       "x"
                       {:interface :sms
                        :requires-protocols
                        [:shiftlefter.sms.protocol/ISMSInbound]})]
          interfaces {:sms {:type :sms :adapter :sms-twilio}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces nil)
          issue      (first issues)]
      (is (= :stepdef/missing-capability (:type issue)))
      (is (= :sms-twilio (:adapter issue)))
      (is (= [:shiftlefter.sms.protocol/ISMSInbound] (:missing issue))))))

;; ---------------------------------------------------------------------------
;; sl-s2oa: stepdef :interface metadata = TYPE; instances checked per use
;; ---------------------------------------------------------------------------

(deftest s2oa-type-provided-by-renamed-instance-passes
  (testing "Stepdef declaring TYPE :web passes when the only :web-typed
            instance is renamed (:storefront) — pre-s2oa this errored on
            name membership"
    (let [stepdefs   [(mk-stepdef "x" {:interface :web})]
          interfaces {:storefront {:type :web :adapter :etaoin}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)]
      (is (empty? issues)))))

(deftest s2oa-instance-name-metadata-is-migration-error
  (testing "A pre-s2oa stepdef pinning a config instance NAME errors —
            the reshaped check is the migration signal"
    (let [stepdefs   [(mk-stepdef "x" {:interface :storefront})]
          interfaces {:storefront {:type :web :adapter :etaoin}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)]
      (is (= 1 (count issues)))
      (let [{:keys [type interface configured]} (first issues)]
        (is (= :stepdef/undefined-interface type))
        (is (= :storefront interface))
        (is (= [:web] configured) "configured TYPES, not names")))))

(deftest s2oa-missing-capability-keys-on-used-instances
  (testing "requires-protocols is checked against the instances the suite
            actually resolves the stepdef to; the failing instance is named"
    (let [stepdefs   [(assoc (mk-stepdef
                              "x"
                              {:interface :sms
                               :requires-protocols
                               [:shiftlefter.sms.protocol/ISMSInbound]})
                             :used-instances #{:sms-b})]
          interfaces {:sms-a {:type :sms :adapter :test-isms}
                      :sms-b {:type :sms :adapter :real-isms}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)]
      (is (= 1 (count issues)))
      (let [{:keys [type interface interface-type adapter missing]} (first issues)]
        (is (= :stepdef/missing-capability type))
        (is (= :sms-b interface) "the failing INSTANCE is named")
        (is (= :sms interface-type))
        (is (= :real-isms adapter))
        (is (= [:shiftlefter.sms.protocol/ISMSInbound] missing))))))

(deftest s2oa-missing-capability-falls-back-to-all-instances-of-type
  (testing "No :used-instances (raw registry stepdefs) -> every configured
            instance of the type is checked; only the deficient one errors"
    (let [stepdefs   [(mk-stepdef
                       "x"
                       {:interface :sms
                        :requires-protocols
                        [:shiftlefter.sms.protocol/ISMSInbound]})]
          interfaces {:sms-a {:type :sms :adapter :test-isms}
                      :sms-b {:type :sms :adapter :real-isms}}
          issues     (suite-lint/lint-suite stepdefs nil interfaces fake-registry)]
      (is (= 1 (count issues)))
      (is (= :sms-b (:interface (first issues)))))))

(deftest s2oa-used-stepdef-infos-unions-instances
  (testing "used-stepdef-infos carries the union of resolved instances a
            stepdef executes on across the suite"
    (let [sd   (mk-stepdef "x" {:interface :web})
          bs-a (assoc-in (mk-bound-step sd) [:binding :svo :interface] :web)
          bs-b (assoc-in (mk-bound-step sd) [:binding :svo :interface] :admin)
          plan {:plan/id (java.util.UUID/randomUUID)
                :plan/pickle {:pickle/source-file "f.feature"}
                :plan/steps [bs-a bs-b]
                :plan/runnable? true}
          infos (suite-lint/used-stepdef-infos [plan])]
      (is (= 1 (count infos)))
      (is (= #{:web :admin} (:used-instances (first infos)))))))
