(ns shiftlefter.repl-bare-browser-test
  "sl-vmc5: the REPL can provision a BARE (non-costume) capability when a
   loaded config declares the step's interface — the bare-caller path the
   :lazy provisioning mode was built for (config.clj's own
   comment). The provisioned capability is :ephemeral in the named ctx,
   which is exactly what makes the sl-tss7 :closed cleanup branch
   reachable from public REPL calls — the coverage half of the bead.

   Uses a fake :fake-web adapter overlaid on the default registry: the
   full provisioning machinery runs (factory, mode, ctx assoc) with no
   real browser."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.adapters.registry :as areg]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.repl :as repl]
            [shiftlefter.stepengine.registry :as registry :refer [defstep]]
            [shiftlefter.webdriver.etaoin.session :as session]))

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (repl/reset-ctx!)
    (repl/reset-ctxs!)
    (reset! @#'repl/connected-subjects {})
    (repl/vanilla!)
    (f)
    (repl/vanilla!)))

(def ^:private bare-config
  ;; :svo all-off: this suite is about provisioning, not vocabulary.
  {:interfaces {:web {:type :web :adapter :fake-web :config {}}}
   :svo {:unknown-subject :off :unknown-verb :off :unknown-interface :off}})

(defn- fake-web-registry
  "Default registry + a :fake-web adapter whose factory returns `impl`
   directly and whose :cleanup records into `cleanup-calls`."
  [impl cleanup-calls]
  (assoc areg/default-registry :fake-web
         {:factory  (fn [_] {:ok impl})
          :cleanup  (fn [h] (swap! cleanup-calls conj h) {:ok :closed})
          :provides []}))

(deftest as-provisions-bare-capability-and-reset-closes-it
  (testing "as provisions :ephemeral from config; reset-ctxs! reaches :closed via public calls only"
    (let [impl {:fake-impl :web}
          cleanup-calls (atom [])
          quit-calls (atom [])]
      (with-redefs [areg/default-registry (fake-web-registry impl cleanup-calls)
                    session/quit-driver! (fn [d] (swap! quit-calls conj d) {:ok :quit})]
        (repl/shifted! bare-config)
        (defstep #":(\w+) uses the web" {:interface :web} [ctx _subject] ctx)
        (let [r (repl/as :bare "uses the web")]
          (is (= :passed (:status r)))
          (let [entry (cap/get-capability-entry (repl/ctx :bare) :web :bare)]
            (is (= impl (:impl entry)) "the factory's impl landed in the named ctx")
            (is (= :ephemeral (:mode entry)) "bare capabilities are ephemeral")))
        ;; The sl-tss7 coverage proof: the :closed branch, public calls only.
        (let [actions (repl/reset-ctxs!)]
          (is (= [:closed] (mapv :action actions)))
          (is (= [impl] @quit-calls) "the ephemeral impl was actually quit"))))))

(deftest as-provision-failure-surfaces-as-failed-step
  (testing "a failing factory surfaces as a :failed result carrying the provisioning error"
    (with-redefs [areg/default-registry
                  (assoc areg/default-registry :fake-web
                         {:factory (fn [_] {:error {:type :adapter/boom :message "no web today"}})
                          :cleanup (fn [_] {:ok :closed})
                          :provides []})]
      (repl/shifted! bare-config)
      (defstep #":(\w+) uses the web" {:interface :web} [ctx _subject] ctx)
      (let [r (repl/as :bare "uses the web")]
        (is (= :failed (:status r)))
        (is (some? (:error r)))))))

(deftest as-without-config-is-unchanged
  (testing "vanilla mode (no config): no provisioning, non-browser steps unaffected"
    (defstep #":(\w+) uses the web" {:interface :web} [ctx _subject] ctx)
    ;; No config: the step runs with no capability provisioned — this
    ;; particular step doesn't need one, so it passes with an empty ctx.
    (let [r (repl/as :bare "uses the web")]
      (is (= :passed (:status r)))
      (is (nil? (cap/get-capability-entry (repl/ctx :bare) :web :bare))))))

(deftest run-provisions-and-cleans-per-scenario
  (testing "repl/run threads :interfaces to the engine: provision + per-scenario ephemeral cleanup"
    (let [impl {:fake-impl :web}
          cleanup-calls (atom [])]
      (with-redefs [areg/default-registry (fake-web-registry impl cleanup-calls)]
        ;; Shifted compile requires glossaries: explicit nils pass the
        ;; config check and the framework-default :web verb glossary
        ;; auto-loads, so the svo must use a known verb/frame.
        (repl/shifted! (assoc bare-config :glossaries {:subjects nil :verbs nil}))
        (defstep #":(\w+) uses the web"
          {:interface :web
           :svo {:subject :$1 :verb :navigate :frame :to :object nil :args {}}}
          [ctx _subject] ctx)
        (let [r (repl/run "Feature: Bare\n  Scenario: S\n    Given :bare uses the web")]
          (is (= :ok (:status r)))
          (is (= 1 (get-in r [:summary :passed])))
          (is (= [impl] @cleanup-calls)
              "the engine's per-scenario ephemeral cleanup closed the bare capability"))))))
