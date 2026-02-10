(ns shiftlefter.webdriver.session-store
  "Session handle persistence for browser surfaces.

   Surfaces are opt-in persistent browser sessions that survive JVM restarts.
   This namespace provides the protocol and EDN file-based implementation.

   ## Handle Shape

   ```clj
   {:webdriver-url \"http://127.0.0.1:9515\"
    :session-id    \"abc123...\"
    :saved-at      \"2026-01-07T12:34:56Z\"
    :meta          {:surface :alice}}
   ```

   ## Usage

   ```clj
   (def store (make-edn-store {:dir \"./.shiftlefter\"}))

   (save-session-handle! store :alice
     {:webdriver-url \"http://127.0.0.1:9515\"
      :session-id \"abc123\"})

   (load-session-handle store :alice)
   ;; => {:webdriver-url ... :session-id ... :saved-at ...}

   (delete-session-handle! store :alice)
   ```"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.time Instant]
           [java.util UUID]))

;; -----------------------------------------------------------------------------
;; Protocol
;; -----------------------------------------------------------------------------

(defprotocol ISessionStore
  "Protocol for session handle persistence."

  (load-session-handle [store surface-name]
    "Load a session handle by surface name. Returns handle map or nil.")

  (save-session-handle! [store surface-name handle]
    "Save a session handle for a surface. Returns {:ok handle'} or {:error ...}.
     The handle will have :saved-at timestamp added.")

  (delete-session-handle! [store surface-name]
    "Delete a session handle. Returns {:ok true} or {:error ...}."))

;; -----------------------------------------------------------------------------
;; EDN File Store Implementation
;; -----------------------------------------------------------------------------

(defn- surface-file
  "Get the file path for a surface's session handle."
  [dir surface-name]
  (str dir "/" (name surface-name) ".edn"))

(defn- ensure-dir!
  "Ensure the store directory exists."
  [dir]
  (when-not (fs/exists? dir)
    (fs/create-dirs dir)))

(defn- safe-write!
  "Write content to file using temp+rename for atomicity."
  [path content]
  (let [temp-path (str path ".tmp." (UUID/randomUUID))]
    (spit temp-path content)
    (fs/move temp-path path {:replace-existing true})))

(defrecord EdnSessionStore [dir]
  ISessionStore

  (load-session-handle [_ surface-name]
    (let [path (surface-file dir surface-name)]
      (when (fs/exists? path)
        (try
          (edn/read-string (slurp path))
          (catch Exception _
            nil)))))

  (save-session-handle! [_ surface-name handle]
    (try
      (ensure-dir! dir)
      (let [path (surface-file dir surface-name)
            handle' (assoc handle
                           :saved-at (str (Instant/now))
                           :surface surface-name)]
        (safe-write! path (pr-str handle'))
        {:ok handle'})
      (catch Exception e
        {:error {:type :session-store/save-failed
                 :message (ex-message e)
                 :data {:surface surface-name}}})))

  (delete-session-handle! [_ surface-name]
    (try
      (let [path (surface-file dir surface-name)]
        (when (fs/exists? path)
          (fs/delete path))
        {:ok true})
      (catch Exception e
        {:error {:type :session-store/delete-failed
                 :message (ex-message e)
                 :data {:surface surface-name}}}))))

;; -----------------------------------------------------------------------------
;; Factory
;; -----------------------------------------------------------------------------

(def default-dir
  "Default directory for session storage."
  "./.shiftlefter")

(defn make-edn-store
  "Create an EDN file-based session store.

   Options:
   - :dir — directory for session files (default: ./.shiftlefter)"
  ([]
   (make-edn-store {}))
  ([opts]
   (->EdnSessionStore (get opts :dir default-dir))))

;; -----------------------------------------------------------------------------
;; Convenience
;; -----------------------------------------------------------------------------

(defn list-surfaces
  "List all surface names that have stored handles."
  [store]
  (let [dir (:dir store)]
    (when (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter #(str/ends-with? (str %) ".edn"))
           (map #(-> (fs/file-name %)
                     (str/replace #"\.edn$" "")
                     keyword))
           (into [])))))
