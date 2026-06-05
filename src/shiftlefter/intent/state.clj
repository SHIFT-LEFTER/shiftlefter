(ns shiftlefter.intent.state
  "Global intents state for ShiftLefter.

   Intents are loaded once on first access and cached. This provides
   a simple API for step definitions to access intents without
   threading the intents map through ctx.

   ## Usage

   ```clojure
   ;; Get the loaded intents (loads on first call)
   (get-intents)

   ;; Force reload (for testing)
   (reload-intents!)

   ;; Clear for test isolation
   (clear-intents!)
   ```"
  (:require [shiftlefter.intent.loader :as loader]))

;; -----------------------------------------------------------------------------
;; State
;; -----------------------------------------------------------------------------

(defonce ^:private intents-atom
  (atom {:loaded? false
         :intents nil
         :errors nil}))

(def ^:private default-intents-dir
  "glossary/intents")

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn get-intents
  "Get the loaded intents map. Loads on first call.
   Returns the intents map (may be empty if no intents dir exists).
   Throws on load errors."
  ([] (get-intents default-intents-dir))
  ([intents-dir]
   (if (:loaded? @intents-atom)
     (:intents @intents-atom)
     (let [result (loader/load-all-intents intents-dir)]
       (if (:errors result)
         (do
           (swap! intents-atom assoc
                  :loaded? true
                  :errors (:errors result)
                  :intents nil)
           (throw (ex-info "Failed to load intents"
                           {:type :intent/load-failed
                            :errors (:errors result)})))
         (do
           (swap! intents-atom assoc
                  :loaded? true
                  :intents (:ok result)
                  :errors nil)
           (:ok result)))))))

(defn reload-intents!
  "Force reload intents from disk. Returns intents map or throws."
  ([] (reload-intents! default-intents-dir))
  ([intents-dir]
   (reset! intents-atom {:loaded? false :intents nil :errors nil})
   (get-intents intents-dir)))

(defn clear-intents!
  "Clear intents state for test isolation."
  []
  (reset! intents-atom {:loaded? false :intents nil :errors nil})
  nil)

(defn intents-loaded?
  "Returns true if intents have been loaded."
  []
  (:loaded? @intents-atom))

(defn intents-errors
  "Returns any errors from loading, or nil if no errors."
  []
  (:errors @intents-atom))
