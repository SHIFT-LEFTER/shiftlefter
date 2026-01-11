(ns shiftlefter.stepdefs.browser
  "Browser step definitions for ShiftLefter.

   Provides stepdefs for kernel browser operations using boring, explicit text.
   Each stepdef:
   1. Gets browser from ctx via `browser.ctx/get-active-browser`
   2. Resolves locator via `locators/resolve-locator`
   3. Calls protocol method on browser
   4. Returns updated ctx

   ## Step Patterns

   Navigation:
   - `I open the browser to '<url>'`

   Click operations:
   - `I click {<locator>}`
   - `I double-click {<locator>}`
   - `I right-click {<locator>}`

   Mouse operations:
   - `I move to {<locator>}`
   - `I drag {<locator>} to {<locator>}`

   Input operations:
   - `I fill {<locator>} with '<text>'`

   Query operations:
   - `I count {<locator>} elements`

   ## Locator Syntax

   Locators in step text use EDN syntax:
   - `{:css \"#login\"}` — CSS selector
   - `{:xpath \"//button\"}` — XPath
   - `{:id \"submit\"}` — element ID
   - `[:css \"#login\"]` — vector shorthand"
  (:require [shiftlefter.stepengine.registry :refer [defstep]]
            [shiftlefter.browser.ctx :as browser.ctx]
            [shiftlefter.browser.locators :as locators]
            [shiftlefter.browser.protocol :as bp]
            [clojure.edn :as edn]))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- get-browser
  "Get browser from ctx, returning error if not configured."
  [ctx]
  (if-let [browser (browser.ctx/get-active-browser (:scenario ctx))]
    {:ok browser}
    {:error {:type :browser/not-configured
             :message "No browser configured in context"
             :data {}}}))

(defn- parse-locator
  "Parse locator string from step text (EDN format)."
  [locator-str]
  (try
    (let [token (edn/read-string locator-str)]
      (locators/resolve-locator token))
    (catch Exception e
      {:errors [{:type :browser/locator-parse-error
                 :message (str "Failed to parse locator: " (ex-message e))
                 :data {:input locator-str}}]})))

(defn- with-browser
  "Execute a browser operation, handling errors.
   Returns updated scenario ctx on success, or throws on error."
  [ctx op-fn]
  (let [{:keys [ok error]} (get-browser ctx)]
    (if error
      (throw (ex-info (:message error) error))
      (do
        (op-fn ok)
        (:scenario ctx)))))

(defn- with-locator
  "Execute a browser operation with a resolved locator.
   Returns updated scenario ctx on success, or throws on error."
  [ctx locator-str op-fn]
  (let [resolved (parse-locator locator-str)]
    (if (locators/valid? resolved)
      (with-browser ctx (fn [browser] (op-fn browser resolved)))
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)})))))

;; -----------------------------------------------------------------------------
;; Navigation
;; -----------------------------------------------------------------------------

(defstep #"I open the browser to '([^']+)'"
  [url ctx]
  (with-browser ctx
    (fn [browser]
      (bp/open-to! browser url))))

;; -----------------------------------------------------------------------------
;; Click Operations
;; -----------------------------------------------------------------------------

(defstep #"I click (\{[^}]+\}|\[[^\]]+\])"
  [locator-str ctx]
  (with-locator ctx locator-str
    (fn [browser resolved]
      (bp/click! browser resolved))))

(defstep #"I double-click (\{[^}]+\}|\[[^\]]+\])"
  [locator-str ctx]
  (with-locator ctx locator-str
    (fn [browser resolved]
      (bp/doubleclick! browser resolved))))

(defstep #"I right-click (\{[^}]+\}|\[[^\]]+\])"
  [locator-str ctx]
  (with-locator ctx locator-str
    (fn [browser resolved]
      (bp/rightclick! browser resolved))))

;; -----------------------------------------------------------------------------
;; Mouse Operations
;; -----------------------------------------------------------------------------

(defstep #"I move to (\{[^}]+\}|\[[^\]]+\])"
  [locator-str ctx]
  (with-locator ctx locator-str
    (fn [browser resolved]
      (bp/move-to! browser resolved))))

(defstep #"I drag (\{[^}]+\}|\[[^\]]+\]) to (\{[^}]+\}|\[[^\]]+\])"
  [from-str to-str ctx]
  (let [from-resolved (parse-locator from-str)
        to-resolved (parse-locator to-str)]
    (cond
      (not (locators/valid? from-resolved))
      (throw (ex-info "Invalid from locator" {:errors (:errors from-resolved)}))

      (not (locators/valid? to-resolved))
      (throw (ex-info "Invalid to locator" {:errors (:errors to-resolved)}))

      :else
      (with-browser ctx
        (fn [browser]
          (bp/drag-to! browser from-resolved to-resolved))))))

;; -----------------------------------------------------------------------------
;; Input Operations
;; -----------------------------------------------------------------------------

(defstep #"I fill (\{[^}]+\}|\[[^\]]+\]) with '([^']+)'"
  [locator-str text ctx]
  (with-locator ctx locator-str
    (fn [browser resolved]
      (bp/fill! browser resolved text))))

;; -----------------------------------------------------------------------------
;; Query Operations
;; -----------------------------------------------------------------------------

(defstep #"I count (\{[^}]+\}|\[[^\]]+\]) elements"
  [locator-str ctx]
  (let [resolved (parse-locator locator-str)]
    (if-not (locators/valid? resolved)
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)}))
      (let [{:keys [ok error]} (get-browser ctx)]
        (if error
          (throw (ex-info (:message error) error))
          (let [cnt (bp/element-count ok resolved)]
            (assoc (:scenario ctx) :element-count cnt)))))))
