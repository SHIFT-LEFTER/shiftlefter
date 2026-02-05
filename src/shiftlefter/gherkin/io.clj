(ns shiftlefter.gherkin.io
  "File I/O utilities with explicit UTF-8 encoding.

   ShiftLefter enforces UTF-8-only file reading to avoid platform-dependent
   charset surprises. All CLI-facing file reads should use these functions."
  (:require [clojure.spec.alpha :as s]
            [shiftlefter.gherkin.location :as location])
  (:import [java.nio.file Files Paths NoSuchFileException]
           [java.nio.charset StandardCharsets CodingErrorAction]
           [java.nio CharBuffer]))

;; -----------------------------------------------------------------------------
;; Specs
;; -----------------------------------------------------------------------------

(s/def ::status #{:ok :error})
(s/def ::content string?)
(s/def ::path string?)
(s/def ::reason #{:io/utf8-decode-failed :io/file-not-found :io/read-error})
(s/def ::message string?)

(s/def ::ok-result
  (s/keys :req-un [::status ::content ::path]))

(s/def ::error-result
  (s/keys :req-un [::status ::reason ::message ::path]
          :opt-un [:shiftlefter.gherkin.location/location]))

(s/def ::read-result
  (s/or :ok ::ok-result :error ::error-result))

;; -----------------------------------------------------------------------------
;; UTF-8 File Reading
;; -----------------------------------------------------------------------------

(defn- decode-utf8-strict
  "Decode bytes as UTF-8 with strict error handling.
   Returns the decoded string or throws CharacterCodingException on malformed input."
  [^bytes byte-arr]
  (let [decoder (-> StandardCharsets/UTF_8
                    .newDecoder
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
        byte-buf (java.nio.ByteBuffer/wrap byte-arr)
        ;; Allocate char buffer - worst case is 1 char per byte
        char-buf (CharBuffer/allocate (max 1 (alength byte-arr)))
        result (.decode decoder byte-buf char-buf true)]
    ;; Check for errors - .throwException throws if result is an error
    (when (.isError result)
      (.throwException result))
    ;; Also flush the decoder
    (let [flush-result (.flush decoder char-buf)]
      (when (.isError flush-result)
        (.throwException flush-result)))
    (.flip char-buf)
    (.toString char-buf)))

(defn read-file-utf8
  "Read a file as UTF-8, returning a result map.

   Returns:
   - {:status :ok :content string :path path} on success
   - {:status :error :reason :io/utf8-decode-failed :message ... :path path :location Location} on decode failure
   - {:status :error :reason :io/file-not-found :message ... :path path} if file doesn't exist
   - {:status :error :reason :io/read-error :message ... :path path} for other I/O errors

   This enforces ShiftLefter's UTF-8-only policy at the CLI/file boundary."
  [path]
  (try
    (let [path-obj (Paths/get path (into-array String []))
          bytes (Files/readAllBytes path-obj)
          content (decode-utf8-strict bytes)]
      {:status :ok
       :content content
       :path path})
    (catch java.nio.charset.CharacterCodingException _
      {:status :error
       :reason :io/utf8-decode-failed
       :message "File is not valid UTF-8. ShiftLefter requires UTF-8 encoded files."
       :path path
       :location (location/->Location 1 1)})
    (catch NoSuchFileException _
      {:status :error
       :reason :io/file-not-found
       :message (str "File not found: " path)
       :path path})
    (catch Exception e
      {:status :error
       :reason :io/read-error
       :message (str "Error reading file: " (.getMessage e))
       :path path})))

(s/fdef read-file-utf8
  :args (s/cat :path ::path)
  :ret ::read-result)

(defn slurp-utf8
  "Read a file as UTF-8, throwing on error.

   Convenience wrapper for code that wants exception-based error handling.
   For structured error handling, use `read-file-utf8` instead.

   Throws:
   - ExceptionInfo with {:reason :io/utf8-decode-failed ...} on decode failure
   - ExceptionInfo with {:reason :io/file-not-found ...} if file doesn't exist
   - ExceptionInfo with {:reason :io/read-error ...} for other I/O errors"
  [path]
  (let [result (read-file-utf8 path)]
    (if (= :ok (:status result))
      (:content result)
      (throw (ex-info (:message result)
                      {:reason (:reason result)
                       :path (:path result)
                       :location (:location result)})))))

(s/fdef slurp-utf8
  :args (s/cat :path ::path)
  :ret string?)

;; -----------------------------------------------------------------------------
;; Text EOL Utilities
;; -----------------------------------------------------------------------------

(defn strip-trailing-eol
  "Strip exactly one trailing line ending from a string.
   Handles CRLF (\\r\\n), LF (\\n), or CR (\\r) in that priority order.
   Returns the string unchanged if no trailing EOL is present.

   This is the canonical helper for removing EOL when processing raw token
   content that preserves original line endings (per RT1 lossless lexer).

   Examples:
     (strip-trailing-eol \"hello\\r\\n\") => \"hello\"
     (strip-trailing-eol \"hello\\n\")   => \"hello\"
     (strip-trailing-eol \"hello\\r\")   => \"hello\"
     (strip-trailing-eol \"hello\")      => \"hello\"
     (strip-trailing-eol \"a\\nb\\n\")   => \"a\\nb\"  ; only strips one"
  [s]
  (if (nil? s)
    ""
    (cond
      ;; CRLF first (must check before LF alone)
      (and (>= (count s) 2)
           (= \return (nth s (- (count s) 2)))
           (= \newline (nth s (dec (count s)))))
      (subs s 0 (- (count s) 2))

      ;; LF
      (and (>= (count s) 1)
           (= \newline (nth s (dec (count s)))))
      (subs s 0 (dec (count s)))

      ;; CR (alone)
      (and (>= (count s) 1)
           (= \return (nth s (dec (count s)))))
      (subs s 0 (dec (count s)))

      ;; No trailing EOL
      :else s)))

(s/fdef strip-trailing-eol
  :args (s/cat :s (s/nilable string?))
  :ret string?)
