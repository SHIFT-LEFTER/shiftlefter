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
        stop!      (http/run-server handler {:port port})]
    {:adapter-registry
     (assoc reg/default-registry :sms-mock
            {:factory  (fn [_] {:ok shared-sms})
             :cleanup  (fn [_] {:ok :closed})  ;; don't reset a shared mock
             :provides [:shiftlefter.sms.protocol/ISMS
                        :shiftlefter.sms.protocol/ISMSInbound]})
     :stop (fn [] (stop! :timeout 100))}))

;; ---------------------------------------------------------------------------
;; Setup contract: vector of group entries
;; ---------------------------------------------------------------------------

(def setups
  [{:label    "sms-2fa"
    :start    start-sms-2fa
    :features ["features/password_reset_sms.feature"]}])
