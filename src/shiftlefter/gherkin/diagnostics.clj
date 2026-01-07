(ns shiftlefter.gherkin.diagnostics
  "Centralized error formatting for consistent diagnostic output.

   Standard format: path:line:col: <type>: <message>

   Usage:
     (format-error path error)           ; single error string
     (format-errors-human path errors)   ; multi-line human output
     (format-result-edn result)          ; machine-readable EDN"
  (:require [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Error formatting
;; -----------------------------------------------------------------------------

(defn format-error
  "Format a single error in standard diagnostic format.
   Format: path:line:col: <type>: <message>

   If path is nil, uses '-' (stdin convention).
   If location is missing, uses line 1, col 1."
  [path {:keys [type message location] :as _error}]
  (let [path-str (or path "-")
        line (get location :line 1)
        col (get location :column 1)
        type-str (if (keyword? type) (name type) (str type))]
    (format "%s:%d:%d: %s: %s" path-str line col type-str message)))

(defn format-error-short
  "Format error without path (for indented sub-output).
   Format: line:col: <type>: <message>"
  [{:keys [type message location] :as _error}]
  (let [line (get location :line 1)
        col (get location :column 1)
        type-str (if (keyword? type) (name type) (str type))]
    (format "%d:%d: %s: %s" line col type-str message)))

;; -----------------------------------------------------------------------------
;; Human-readable output
;; -----------------------------------------------------------------------------

(defn format-errors-human
  "Format multiple errors for human output.
   Returns a string with one error per line, plus summary."
  [path errors]
  (let [error-lines (map #(format-error path %) errors)
        summary (format "%d error%s" (count errors) (if (= 1 (count errors)) "" "s"))]
    (str (str/join "\n" error-lines) "\n" summary)))

(defn print-errors
  "Print errors to stdout in standard format.
   Each error on its own line: path:line:col: type: message"
  [path errors]
  (doseq [e errors]
    (println (format-error path e))))

(defn print-errors-with-summary
  "Print errors with a summary line at the end."
  [path errors]
  (print-errors path errors)
  (println (format "\n%d error%s in %s"
                   (count errors)
                   (if (= 1 (count errors)) "" "s")
                   (or path "-"))))

;; -----------------------------------------------------------------------------
;; Indented output (for file-by-file reporting)
;; -----------------------------------------------------------------------------

(defn print-errors-indented
  "Print errors indented (for use after a file header line).
   Format: '  line:col: type: message'"
  [errors]
  (doseq [e errors]
    (println (str "  " (format-error-short e)))))

;; -----------------------------------------------------------------------------
;; EDN output
;; -----------------------------------------------------------------------------

(defn format-error-edn
  "Format a single error as a clean EDN map (no records)."
  [{:keys [type message location]}]
  {:type type
   :message message
   :line (get location :line)
   :column (get location :column)})

(defn format-result-edn
  "Format a command result as EDN.
   Input should be a map with :status, :errors, etc.
   Output is a clean EDN map suitable for machine parsing."
  [{:keys [status errors] :as result}]
  (cond-> {:status status}
    (seq errors) (assoc :errors (mapv format-error-edn errors))
    (:path result) (assoc :path (:path result))
    (:reason result) (assoc :reason (:reason result))
    (:details result) (assoc :details (:details result))))

(defn print-result-edn
  "Print a result as EDN to stdout."
  [result]
  (println (pr-str (format-result-edn result))))

;; -----------------------------------------------------------------------------
;; Summary helpers
;; -----------------------------------------------------------------------------

(defn error-type-counts
  "Count errors by type. Returns map of type -> count."
  [errors]
  (frequencies (map :type errors)))

(defn format-type-summary
  "Format error type counts as a summary string.
   Example: '3 errors: 2 invalid-keyword, 1 unexpected-token'"
  [errors]
  (let [counts (error-type-counts errors)
        total (count errors)
        type-strs (map (fn [[t c]] (format "%d %s" c (name t)))
                       (sort-by (comp - val) counts))]
    (format "%d error%s: %s"
            total
            (if (= 1 total) "" "s")
            (str/join ", " type-strs))))

;; -----------------------------------------------------------------------------
;; File result formatting
;; -----------------------------------------------------------------------------

(defn format-file-result-human
  "Format a single file check/format result for human output.
   Handles :ok, :error, :not-found statuses."
  [{:keys [path status reason details message] :as result} verb]
  (case status
    :ok (format "%s %s... OK" verb path)
    :not-found (format "%s %s... NOT FOUND" verb path)
    :reformatted (format "%s %s... reformatted" verb path)
    :unchanged (format "%s %s... unchanged" verb path)
    :error (str (format "%s %s... ERROR" verb path)
                (when (= :parse-errors reason)
                  (str "\n" (str/join "\n" (map #(str "  " (format-error-short %)) details))))
                (when (= :mismatch reason)
                  (format "\n  roundtrip mismatch (original: %d bytes, reconstructed: %d bytes)"
                          (:original-length result)
                          (:reconstructed-length result)))
                (when (and message (not= :parse-errors reason) (not= :mismatch reason))
                  (str "\n  " message)))
    ;; fallback
    (format "%s %s... %s" verb path (name status))))
