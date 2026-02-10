(ns shiftlefter.browser.ctx
  "Browser capability helpers for ctx management.

   Browser capability is stored in ctx under `:cap/browser` with the shape:

   ```clj
   {:cap/browser {:sessions {:default <browser-capability>}
                  :active   :default}}
   ```

   This shape supports single-session now while enabling multi-session later.
   In 0.2.x, only `:default` session is used.")

;; -----------------------------------------------------------------------------
;; Predicates
;; -----------------------------------------------------------------------------

(defn browser-present?
  "Returns true if ctx has an active browser session."
  [ctx]
  (let [{:keys [sessions active]} (:cap/browser ctx)]
    (boolean (and active (get sessions active)))))

;; -----------------------------------------------------------------------------
;; Accessors
;; -----------------------------------------------------------------------------

(defn get-active-browser
  "Returns the active browser capability, or nil if none."
  [ctx]
  (let [{:keys [sessions active]} (:cap/browser ctx)]
    (when active
      (get sessions active))))

(defn get-session
  "Returns a specific browser session by name, or nil if not found."
  [ctx session-name]
  (get-in ctx [:cap/browser :sessions session-name]))

;; -----------------------------------------------------------------------------
;; Mutators (pure — return new ctx)
;; -----------------------------------------------------------------------------

(defn assoc-active-browser
  "Add or update the active browser capability in ctx.

   Uses `:default` as the session name. Returns updated ctx."
  ([ctx browser]
   (assoc-active-browser ctx :default browser))
  ([ctx session-name browser]
   (-> ctx
       (assoc-in [:cap/browser :sessions session-name] browser)
       (assoc-in [:cap/browser :active] session-name))))

(defn set-active-session
  "Set which session is active. Returns updated ctx.

   Returns ctx unchanged if session doesn't exist."
  [ctx session-name]
  (if (get-in ctx [:cap/browser :sessions session-name])
    (assoc-in ctx [:cap/browser :active] session-name)
    ctx))

(defn dissoc-session
  "Remove a specific session from ctx. Returns updated ctx.

   If the removed session was active, sets :active to nil."
  [ctx session-name]
  (let [was-active? (= session-name (get-in ctx [:cap/browser :active]))
        ctx' (update-in ctx [:cap/browser :sessions] dissoc session-name)]
    (if was-active?
      (assoc-in ctx' [:cap/browser :active] nil)
      ctx')))

(defn dissoc-browser
  "Remove all browser capability from ctx. Returns updated ctx."
  [ctx]
  (dissoc ctx :cap/browser))
