(ns shiftlefter.adapters.sms-mock
  "Mock SMS adapter for tests and CI — no Twilio credentials needed.

   Mirrors the shape of `shiftlefter.adapters.twilio`: factory returns
   `{:ok {:sms MockSMS :from-number ...}}`, cleanup is a no-op.

   ## Config

   ```clojure
   {:from-number \"+1...\"}    ; optional default sender
   ```

   No credentials, no env fallback — the mock has nothing to authenticate."
  (:require [shiftlefter.sms.mock :as mock]))

(defn create-sms
  "Create a mock SMS capability. Config is optional. The registry entry
   declares `:impl-key :sms` (sl-uv3r), so provisioning extracts the
   MockSMS as the step-facing impl; the whole bundle (with
   `:from-number`) rides `:cleanup-handle`.

   Returns `{:ok {:sms MockSMS :from-number string-or-nil}}`."
  [config]
  {:ok {:sms         (mock/make-mock-sms)
        :from-number (:from-number config)}})

(defn close-sms
  "No resources to release. Returns `{:ok :closed}`."
  [_capability]
  {:ok :closed})
