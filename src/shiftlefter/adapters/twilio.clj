(ns shiftlefter.adapters.twilio
  "Twilio adapter for the adapter registry.

   Provides factory and cleanup functions for SMS capabilities backed by
   the Twilio REST API. Mirrors the shape of `shiftlefter.adapters.etaoin`.

   ## Config

   ```clojure
   {:account-sid \"AC...\"     ; or env TWILIO_ACCOUNT_SID
    :auth-token  \"...\"       ; or env TWILIO_AUTH_TOKEN
    :from-number \"+1...\"}    ; default sender (no env fallback)
   ```

   Env vars are the fallback when keys are missing from the config map.
   `:from-number` has no env fallback — it's the per-deployment sender
   number and is expected to live in `shiftlefter.edn` or a step's args."
  (:require [clojure.string :as str]
            [shiftlefter.sms.twilio :as twilio]))

;; ---------------------------------------------------------------------------
;; Config resolution
;; ---------------------------------------------------------------------------

(defn- resolve-creds [config]
  {:account-sid (or (:account-sid config) (System/getenv "TWILIO_ACCOUNT_SID"))
   :auth-token  (or (:auth-token  config) (System/getenv "TWILIO_AUTH_TOKEN"))})

(defn- missing-creds [{:keys [account-sid auth-token]}]
  (cond-> []
    (not account-sid) (conj :account-sid)
    (not auth-token)  (conj :auth-token)))

;; ---------------------------------------------------------------------------
;; Factory
;; ---------------------------------------------------------------------------

(defn create-sms
  "Create a Twilio-backed SMS capability from configuration.

   Config (see ns docstring):
   - :account-sid — Twilio Account SID (or env TWILIO_ACCOUNT_SID)
   - :auth-token  — Twilio Auth Token  (or env TWILIO_AUTH_TOKEN)
   - :from-number — default sender number (optional)

   Returns:
   - Success: {:ok {:sms TwilioSMS :from-number string-or-nil}}
   - Error:   {:error {:type :adapter/missing-config :missing [...] ...}}"
  [config]
  (let [creds   (resolve-creds config)
        missing (missing-creds creds)]
    (if (seq missing)
      {:error {:type    :adapter/missing-config
               :adapter :sms-twilio
               :missing missing
               :message (str "Missing Twilio credentials: "
                             (str/join ", " (map name missing)))}}
      {:ok {:sms         (twilio/make-twilio-sms creds)
            :from-number (:from-number config)}})))

;; ---------------------------------------------------------------------------
;; Cleanup
;; ---------------------------------------------------------------------------

(defn close-sms
  "Close the SMS capability. Twilio is stateless — no resources to release.

   Returns: {:ok :closed}"
  [_capability]
  {:ok :closed})
