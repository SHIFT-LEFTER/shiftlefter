(ns shiftlefter.stepengine.capability-gating-test
  "Integration tests for `:requires-protocols` capability gating, end-to-end
   through `compile-suite`.

   sl-unz lifted the per-step bind-time gate (sl-ewn) to a per-stepdef
   suite-load lint pass — the unit-level gate now lives in
   `shiftlefter.stepengine.suite-lint` and is covered exhaustively by
   `suite_lint_test.clj`. These tests verify the wiring: planning fails
   with `:suite-lint/failed` when the lint catches a missing capability
   on a stepdef matched by some scenario, and succeeds otherwise.

   Stepdef-registration metadata acceptance is also checked here since
   it's the on-ramp to the gate."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.stepengine.compile :as compile]
            [shiftlefter.stepengine.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- step
  "Build a minimal pickle step that matches `pattern-src` exactly."
  [pattern-src]
  {:step/text     pattern-src
   :step/id       (str "step-" pattern-src)
   :step/location {:line 1 :column 1}})

(defn- pickle [step-texts]
  {:pickle/steps       (mapv step step-texts)
   :pickle/source-file "fixture.feature"})

(defn- mk-stepdef
  "Hand-built stepdef shape, bypasses register! so tests can control source."
  [pattern-src metadata]
  (let [pat (re-pattern pattern-src)
        f   (fn [_ctx] :ok)]
    {:stepdef/id  (str "sd-" (hash pattern-src))
     :pattern     pat
     :pattern-src pattern-src
     :source      {:ns 'test :file "test.clj" :line 1}
     :arity       1
     :fn          f
     :metadata    metadata}))

(def ^:private fake-registry
  {:real-isms {:factory  (constantly {:ok {}})
               :cleanup  (constantly {:ok :closed})
               :provides [:shiftlefter.sms.protocol/ISMS]}
   :test-isms {:factory  (constantly {:ok {}})
               :cleanup  (constantly {:ok :closed})
               :provides [:shiftlefter.sms.protocol/ISMS
                          :shiftlefter.sms.protocol/ISMSInbound]}})

(defn- config-with [interfaces]
  {:runner     {:step-paths []}
   :interfaces interfaces})

;; ---------------------------------------------------------------------------
;; Compile-time gating — lint fires for matched stepdefs
;; ---------------------------------------------------------------------------

(deftest compile-fails-when-adapter-missing-required-protocol
  (testing "Step matches stepdef requiring ISMSInbound; :real-isms only provides ISMS → not runnable"
    (let [stepdefs [(mk-stepdef
                     "text 'STOP'"
                     {:interface :sms
                      :requires-protocols
                      [:shiftlefter.sms.protocol/ISMSInbound]})]
          pickles  [(pickle ["text 'STOP'"])]
          opts     {:adapter-registry fake-registry}
          {:keys [runnable? diagnostics]}
          (compile/compile-suite (config-with {:sms {:type :sms :adapter :real-isms}})
                                 pickles stepdefs opts)
          issue (first (:suite-lint-issues diagnostics))]
      (is (not runnable?))
      (is (= :stepdef/missing-capability (:type issue)))
      (is (= :sms       (:interface issue)))
      (is (= :real-isms (:adapter issue)))
      (is (= [:shiftlefter.sms.protocol/ISMSInbound] (:missing issue)))
      (is (= :error     (:severity issue)))
      (is (string?      (:message issue))))))

(deftest compile-succeeds-when-adapter-provides-required-protocol
  (testing ":test-isms provides ISMSInbound → runnable"
    (let [stepdefs [(mk-stepdef
                     "text 'STOP'"
                     {:interface :sms
                      :requires-protocols
                      [:shiftlefter.sms.protocol/ISMSInbound]})]
          pickles  [(pickle ["text 'STOP'"])]
          opts     {:adapter-registry fake-registry}
          {:keys [runnable?]}
          (compile/compile-suite (config-with {:sms {:type :sms :adapter :test-isms}})
                                 pickles stepdefs opts)]
      (is runnable?))))

(deftest loaded-but-unused-stepdef-doesnt-trip-the-gate
  (testing "sl-unz key behavior: a stepdef misconfigured for :sms but not
            matched by any pickle step does NOT block planning"
    (let [used    (mk-stepdef "do a web thing" {:interface :web})
          ;; This SMS stepdef would fail the gate, but no pickle uses it.
          unused  (mk-stepdef
                   "text 'STOP'"
                   {:interface :sms
                    :requires-protocols
                    [:shiftlefter.sms.protocol/ISMSInbound]})
          stepdefs [used unused]
          pickles  [(pickle ["do a web thing"])]
          opts     {:adapter-registry fake-registry}
          {:keys [runnable?]}
          (compile/compile-suite
           (config-with {:web {:type :web :adapter :etaoin}})
           pickles stepdefs opts)]
      ;; This is the contrast with my first-cut design: pre-bind lint
      ;; would have flagged the unused SMS stepdef and broken every
      ;; web-only suite. Post-bind lint sees only matched stepdefs.
      (is runnable?))))

(deftest compile-flags-undefined-interface-on-matched-stepdef
  (testing "Stepdef declares :interface :sms but :sms not in :interfaces config"
    (let [stepdefs [(mk-stepdef "text 'STOP'" {:interface :sms})]
          pickles  [(pickle ["text 'STOP'"])]
          opts     {:adapter-registry fake-registry}
          {:keys [runnable? diagnostics]}
          (compile/compile-suite (config-with {:web {:type :web :adapter :etaoin}})
                                 pickles stepdefs opts)
          issue (first (:suite-lint-issues diagnostics))]
      (is (not runnable?))
      (is (= :stepdef/undefined-interface (:type issue)))
      (is (= :sms (:interface issue))))))

(deftest compile-skips-gating-without-interfaces-config
  (testing "No :interfaces in config → suite-lint is a no-op (vanilla mode)"
    (let [stepdefs [(mk-stepdef
                     "text 'STOP'"
                     {:interface :sms
                      :requires-protocols
                      [:shiftlefter.sms.protocol/ISMSInbound]})]
          pickles  [(pickle ["text 'STOP'"])]
          {:keys [runnable?]}
          (compile/compile-suite {:runner {:step-paths []}}
                                 pickles stepdefs nil)]
      (is runnable?))))

(deftest compile-uses-default-registry-when-none-supplied
  (testing "Without :adapter-registry opt, suite-lint uses adapters/default-registry"
    ;; :sms-twilio in default-registry provides only ISMS, not ISMSInbound
    (let [stepdefs [(mk-stepdef
                     "text 'STOP'"
                     {:interface :sms
                      :requires-protocols
                      [:shiftlefter.sms.protocol/ISMSInbound]})]
          pickles  [(pickle ["text 'STOP'"])]
          {:keys [runnable? diagnostics]}
          (compile/compile-suite (config-with {:sms {:type :sms :adapter :sms-twilio}})
                                 pickles stepdefs nil)
          issue (first (:suite-lint-issues diagnostics))]
      (is (not runnable?))
      (is (= :sms-twilio (:adapter issue)))
      (is (= [:shiftlefter.sms.protocol/ISMSInbound] (:missing issue))))))

;; ---------------------------------------------------------------------------
;; Metadata acceptance at registration
;; ---------------------------------------------------------------------------

(deftest registry-accepts-requires-protocols-metadata
  (testing "register! accepts metadata with :requires-protocols and round-trips it"
    (registry/clear-registry!)
    (let [stepdef (registry/register!
                    #"hello world"
                    (fn [] :ok)
                    {:ns 'test :file "test.clj" :line 1}
                    {:interface :sms
                     :requires-protocols
                     [:shiftlefter.sms.protocol/ISMSInbound]})]
      (is (= [:shiftlefter.sms.protocol/ISMSInbound]
             (-> stepdef :metadata :requires-protocols))))
    (registry/clear-registry!)))
