(ns setup
  "Run-level orchestration for the custom-capture-kind example.

   This is the CONTRIBUTE side of the capture contract: the built-in
   kinds (:screenshot, :console, :page-source) are adapter-contributed,
   and so is yours. The `:start` below returns an adapter registry whose
   `:sms-mock` entry carries a `:capture` map — that entry is the whole
   extension mechanism. No plugin API, no protocol to implement: a
   registry entry is data, and the capture fn is ~10 lines.

   ## The capture fn contract (capture-message-log below)

   - Receives `{:handle h}` — whatever your factory produced (here: the
     MirrorMockSMS instance itself, because the factory returns it
     directly and declares no `:impl-key`).
   - Returns `{:data <bytes-or-String> :media-type \"...\" :ext \"...\"}`
     (+ optional `:excerpt`) — the framework owns timing, file writes,
     filenames, and ref construction.
   - Returns nil when there is NOTHING to capture — no file, no ref.
     (The scenario's first step fires a capture before any message is
     sent; the empty log returns nil and no artifact appears.)
   - THROWS when the capture itself FAILS — the honest-error rule: a
     query error is not an empty log, and swallowing it would make a
     green run whose promised evidence silently vanished. The framework
     converts the throw into a capture-attributed failure (docs/hooks.md,
     the evidence contract).

   ## Mirror wrapper

   Same ~10-line wrapper as example 04 (see its setup.clj for the full
   rationale): MockSMS.send! stamps :outbound, but the built-in receive/
   see-count steps filter :inbound — the mirror re-records every send as
   inbound, the way a real provider logs both directions."
  (:require [clojure.string :as str]
            [shiftlefter.adapters.registry :as reg]
            [shiftlefter.sms.mock :as mock]
            [shiftlefter.sms.protocol :as sms])
  (:import (java.time Instant)))

;; ---------------------------------------------------------------------------
;; Mirror wrapper — outbound→inbound on every send (example 04's pattern)
;; ---------------------------------------------------------------------------

(defrecord MirrorMockSMS [mock]
  sms/ISMS
  (send! [_ params]
    (let [result (sms/send! mock params)]
      (sms/simulate-inbound! mock params)
      result))
  (query-messages [_ filters] (sms/query-messages mock filters))

  sms/ISMSInbound
  (simulate-inbound! [_ params] (sms/simulate-inbound! mock params)))

;; ---------------------------------------------------------------------------
;; The contributed capture kind
;; ---------------------------------------------------------------------------

(defn- format-message
  [{:keys [direction to body]}]
  (str (name direction) " -> " to ": " body))

(defn- capture-message-log
  "The :message-log capture fn — the ~10-line contract in the flesh.
   Dumps the whole SMS conversation (both directions) as text/plain.
   nil = empty log, nothing to capture; a query error THROWS (it is a
   failed capture, not an empty one)."
  [{:keys [handle]}]
  (let [{:keys [ok error]} (sms/query-messages handle {:since-ts Instant/EPOCH})]
    (cond
      error      (throw (ex-info (str "message-log query failed: "
                                      (:message error))
                                 {:type :sms/query-failed :error error}))
      (empty? ok) nil
      :else      {:data       (str (str/join "\n" (map format-message ok)) "\n")
                  :media-type "text/plain"
                  :ext        "txt"})))

;; ---------------------------------------------------------------------------
;; Group: message-log
;; ---------------------------------------------------------------------------

(defn- start-message-log-demo
  "Build the shared mock and return a registry whose :sms-mock entry
   both provisions it (:factory) and contributes :message-log
   (:capture). No :impl-key: the factory's return IS the impl, so the
   capture fn's :handle is exactly the instance built here."
  [_config]
  (let [shared-sms (->MirrorMockSMS (mock/make-mock-sms))]
    {:adapter-registry
     ;; UPDATE the default entry, don't rebuild it (sl-xjx0, Warden W22):
     ;; rebuilding silently dropped the default's :on-provision hook — the
     ;; per-scenario SMS time fence never ran. The update preserves it;
     ;; :impl-key is DELIBERATELY dropped because this factory returns the
     ;; impl directly — both facts are recorded on the :adapters
     ;; declaration below and verified against this registry.
     (update reg/default-registry :sms-mock
             (fn [entry]
               (-> entry
                   (dissoc :impl-key)
                   (assoc :factory (fn [_] {:ok shared-sms})
                          :cleanup (fn [_] {:ok :closed})
                          :capture {:message-log {:fn capture-message-log}}))))}))

;; ---------------------------------------------------------------------------
;; Setup contract: vector of group entries
;; ---------------------------------------------------------------------------

(def setups
  ;; :adapters declares the registry's plan-time data half. :capture here
  ;; is the declaration that :sms-mock contributes :message-log (kind ->
  ;; data map; {} because the kind is not scenario-scoped). Omitting it
  ;; is a :setup/registry-mismatch planning error — an undeclared delta
  ;; between declaration and live registry is exactly what the contract
  ;; refuses to preview-blindly accept.
  [{:label    "message-log"
    :adapters [{:name :sms-mock
                :provides [:shiftlefter.sms.protocol/ISMS
                           :shiftlefter.sms.protocol/ISMSInbound]
                :capture  {:message-log {}}
                ;; sl-xjx0 (W20 grammar): :impl-key nil = the override's
                ;; factory returns the impl directly (deliberate drop);
                ;; :on-provision? true = the default's hook is preserved.
                :impl-key nil
                :on-provision? true}]
    :start    start-message-log-demo
    :features ["features/message_log_capture.feature"]}])
