(ns shiftlefter.gherkin.api
  "Stable public API for ShiftLefter Gherkin parsing.

   This namespace provides the supported entrypoints for framework integration.
   Internal namespaces may change; this facade remains stable.

   ## Envelope Contract
   All functions return maps with vector values (never nil):
   - :tokens  — vector of Token records
   - :ast     — vector of AST nodes (Feature records)
   - :pickles — vector of pickle maps
   - :errors  — vector of error maps

   ## Error Map Shape
   {:type     keyword      ; e.g. :parse-error, :invalid-keyword
    :message  string       ; human-readable description
    :location Location     ; {:line N :column M}
    :path     string?      ; file path if applicable
    :data     map?}        ; additional context

   ## Functions
   - lex-string      — tokenize a string
   - parse-tokens    — parse tokens to AST
   - parse-string    — tokenize + parse in one call
   - pickles         — generate pickles from AST
   - print-tokens    — lossless reconstruction from tokens
   - roundtrip-ok?   — check if string round-trips exactly
   - fmt-check       — check roundtrip + parse (returns status map)
   - fmt-canonical   — canonical formatting (normalizes whitespace)"
  (:require [shiftlefter.gherkin.lexer :as lexer]
            [shiftlefter.gherkin.parser :as parser]
            [shiftlefter.gherkin.pickler :as pickler]
            [shiftlefter.gherkin.printer :as printer]))

;; -----------------------------------------------------------------------------
;; Envelope Helpers
;; -----------------------------------------------------------------------------

(defn- ensure-vec
  "Ensure value is a vector, never nil."
  [v]
  (if (nil? v) [] (vec v)))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn lex-string
  "Tokenize a Gherkin string.

   Returns {:tokens [...] :errors [...]}
   - :tokens — vector of Token records
   - :errors — always [] (lexer doesn't produce errors, just :unknown-line tokens)"
  [s]
  {:tokens (ensure-vec (lexer/lex s))
   :errors []})

(defn parse-tokens
  "Parse tokens into an AST.

   Returns {:ast [...] :errors [...]}
   - :ast    — vector of Feature records
   - :errors — vector of parse error maps"
  [tokens]
  (let [result (parser/parse tokens)]
    {:ast (ensure-vec (:ast result))
     :errors (ensure-vec (:errors result))}))

(defn parse-string
  "Tokenize and parse a Gherkin string in one call.

   Returns {:tokens [...] :ast [...] :errors [...]}
   - :tokens — vector of Token records
   - :ast    — vector of Feature records
   - :errors — vector of parse error maps"
  [s]
  (let [tokens (lexer/lex s)
        result (parser/parse tokens)]
    {:tokens (ensure-vec tokens)
     :ast (ensure-vec (:ast result))
     :errors (ensure-vec (:errors result))}))

(defn pickles
  "Generate pickles from an AST.

   Arguments:
   - ast  — vector of Feature records (from parse-tokens or parse-string)
   - uri  — source URI string for the feature file

   Returns {:pickles [...] :errors [...]}
   - :pickles — vector of pickle maps
   - :errors  — vector of error maps (currently always [])"
  [ast uri]
  (let [result (pickler/pickles ast {} uri)]  ; registry unused, pass empty map
    {:pickles (ensure-vec (:pickles result))
     :errors (ensure-vec (:errors result))}))

(defn print-tokens
  "Reconstruct original source from tokens (lossless).

   Returns the exact original string, byte-for-byte.
   Uses token :raw fields for perfect fidelity."
  [tokens]
  (printer/print-tokens tokens))

(defn roundtrip-ok?
  "Check if string round-trips perfectly through lex -> print-tokens.

   Returns true if the reconstructed string matches the original exactly.
   Use for validating lossless preservation."
  [s]
  (let [tokens (lexer/lex s)
        reconstructed (printer/print-tokens tokens)]
    (= s reconstructed)))

(defn fmt-check
  "Check if string round-trips and parses without errors.

   Returns {:status :ok/:error ...}
   - On success: {:status :ok}
   - On parse error: {:status :error :reason :parse-errors :details [...]}
   - On mismatch: {:status :error :reason :mismatch :original-length N :reconstructed-length M}"
  [s]
  (let [tokens (lexer/lex s)
        {:keys [errors]} (parser/parse tokens)]
    (cond
      (seq errors)
      {:status :error
       :reason :parse-errors
       :details (vec errors)}

      :else
      (let [reconstructed (printer/print-tokens tokens)]
        (if (= s reconstructed)
          {:status :ok}
          {:status :error
           :reason :mismatch
           :original-length (count s)
           :reconstructed-length (count reconstructed)})))))

(defn fmt-canonical
  "Format a Gherkin string to canonical style.

   Returns {:status :ok/:error ...}
   - On success: {:status :ok :output \"formatted string\"}
   - On parse error: {:status :error :reason :parse-errors :details [...]}
   - On Rules present: {:status :error :reason :canonical/rules-unsupported :message string}

   Canonical formatting:
   - Normalizes indentation (2 spaces)
   - Normalizes line endings to LF
   - Normalizes tag spacing to single space
   - Does NOT support Rule: blocks (returns error)"
  [s]
  (try
    {:status :ok :output (printer/canonical s)}
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (if (:errors data)
          {:status :error
           :reason :parse-errors
           :details (vec (:errors data))}
          {:status :error
           :reason (:reason data :canonical/unknown-error)
           :message (ex-message e)
           :location (:location data)})))))
