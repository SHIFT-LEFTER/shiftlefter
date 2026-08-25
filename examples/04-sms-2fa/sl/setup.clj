(ns setup
  "Test orchestration for the 2FA password-reset demo.

   Builds a single MockSMS instance and hands it to BOTH:
     1. The fixture HTTP server (which sends the verification code via
        ISMS/send! when a user POSTs the reset form), and
     2. The framework's `:sms` interface (via a custom adapter registry
        whose `:sms-mock` factory returns the SAME instance).

   Without this shared instance, the fixture server would write to one
   in-memory log and the framework's SMS receive step would poll a
   different one — code lost in transit.

   ## Mirror wrapper

   MockSMS.send! stamps `:direction :outbound` (it's modeling \"the app
   sent an SMS\"). The framework's receive step polls with
   `:direction :inbound` (it's modeling \"the user's phone got an SMS\").
   In real life Twilio logs both directions on the account; in the mock
   we mirror manually here. ~10 lines."
  (:require [org.httpkit.server :as http]
            [shiftlefter.adapters.registry :as reg]
            [shiftlefter.demo.fixture.handler :as fh]
            ;; Side-effect: registers :reset-password page in the page registry.
            [shiftlefter.demo.fixture.reset-password]
            [shiftlefter.sms.mock :as mock]
            [shiftlefter.sms.protocol :as sms]))

(def ^:private port 9090)

;; ---------------------------------------------------------------------------
;; Mirror wrapper — outbound→inbound on every send
;; ---------------------------------------------------------------------------

(defrecord MirrorMockSMS [mock]
  sms/ISMS
  (send! [_ params]
    (let [result (sms/send! mock params)]
      ;; Mirror outbound as inbound from the recipient's perspective so
      ;; the framework's :receive step (which filters :direction :inbound)
      ;; finds the message. Same body, swapped direction.
      (sms/simulate-inbound! mock params)
      result))
  (query-messages [_ filters] (sms/query-messages mock filters))

  sms/ISMSInbound
  (simulate-inbound! [_ params] (sms/simulate-inbound! mock params)))

;; ---------------------------------------------------------------------------
;; Group: SMS-2FA reset
;; ---------------------------------------------------------------------------

(defn- start-sms-2fa
  "Build a MirrorMockSMS, hand it to a fixture server (port 9090) and to
   a custom adapter registry, return both the registry and a stop fn."
  [_config]
  (let [shared-sms (->MirrorMockSMS (mock/make-mock-sms))
        handler    (fh/build-handler
                    {:users    {"alice" {:password "secret1"
                                         :phone    "+15550001111"
                                         :email    "alice@example.com"}}
                     :pages    [:reset-password]
                     :sms      shared-sms
                     :sms-from "+15550000000"})
        stop!      (http/run-server handler {:ip "127.0.0.1" :port port})]
    {:adapter-registry
     ;; UPDATE the default entry, don't rebuild it (sl-xjx0, Warden W22):
     ;; rebuilding silently dropped the default's :on-provision hook — the
     ;; per-scenario SMS time fence (:sms/scenario-start-ts) never ran, so
     ;; count/see fell back to a now−1h window. The update preserves the
     ;; hook; :impl-key is DELIBERATELY dropped because this factory
     ;; returns the impl directly (no bundle to extract from) — and the
     ;; :adapters declaration below records both facts, so the registry
     ;; contract verifies them and the lossy-override warn stays silent.
     (update reg/default-registry :sms-mock
             (fn [entry]
               (-> entry
                   (dissoc :impl-key)
                   (assoc :factory (fn [_] {:ok shared-sms})
                          ;; don't reset a shared mock
                          :cleanup (fn [_] {:ok :closed})))))
     :stop (fn [] (stop! :timeout 100))}))

;; ---------------------------------------------------------------------------
;; Setup contract: vector of group entries
;; ---------------------------------------------------------------------------

(def setups
  ;; :adapters (sl-e0ml): the pure-data declaration of the custom registry
  ;; :start returns — the plan-time face of the :sms-mock override above.
  ;; Dry-run binds against this declaration (preview-honest); at :start the
  ;; live registry is verified to match it.
  [{:label    "sms-2fa"
    :adapters [{:name :sms-mock
                :provides [:shiftlefter.sms.protocol/ISMS
                           :shiftlefter.sms.protocol/ISMSInbound]
                ;; sl-xjx0 (W20 grammar): the runtime-field declarations —
                ;; :impl-key nil = this override's factory returns the impl
                ;; directly (deliberate drop of the default's :sms key);
                ;; :on-provision? true = the default's time-fence hook is
                ;; preserved. Both are verified against the live registry.
                :impl-key nil
                :on-provision? true}]
    :start    start-sms-2fa
    :features ["features/password_reset_sms.feature"]}])
