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
  (:require [clojure.spec.alpha :as s]
            [shiftlefter.gherkin.lexer :as lexer]
            [shiftlefter.gherkin.parser :as parser]
            [shiftlefter.gherkin.pickler :as pickler]
            [shiftlefter.gherkin.printer :as printer]))

;; -----------------------------------------------------------------------------
;; Specs — Return types for API functions
;; -----------------------------------------------------------------------------

;; Reuse existing specs from underlying namespaces
(s/def ::tokens (s/coll-of :shiftlefter.gherkin.tokens/token :kind vector?))
(s/def ::ast (s/coll-of :shiftlefter.gherkin.parser/ast-node :kind vector?))
(s/def ::errors (s/coll-of :shiftlefter.gherkin.parser/error :kind vector?))
(s/def ::pickles (s/coll-of map? :kind vector?))  ; pickle maps

;; Return envelope specs
(s/def ::lex-result (s/keys :req-un [::tokens ::errors]))
(s/def ::parse-result (s/keys :req-un [::ast ::errors]))
(s/def ::full-parse-result (s/keys :req-un [::tokens ::ast ::errors]))
(s/def ::pickle-result (s/keys :req-un [::pickles ::errors]))

;; fmt-check/fmt-canonical return specs
(s/def ::status #{:ok :error})
(s/def ::reason keyword?)
(s/def ::output string?)
(s/def ::details (s/coll-of map? :kind vector?))
(s/def ::message string?)

(s/def ::fmt-ok-result (s/keys :req-un [::status]))
(s/def ::fmt-canonical-ok-result (s/keys :req-un [::status ::output]))
(s/def ::fmt-error-result (s/keys :req-un [::status ::reason]
                                  :opt-un [::details ::message]))
(s/def ::fmt-check-result (s/or :ok ::fmt-ok-result :error ::fmt-error-result))
(s/def ::fmt-canonical-result (s/or :ok ::fmt-canonical-ok-result :error ::fmt-error-result))

;; -----------------------------------------------------------------------------
;; Function specs (fdefs)
;; -----------------------------------------------------------------------------

(s/fdef lex-string
  :args (s/cat :s string?)
  :ret ::lex-result)

(s/fdef parse-tokens
  :args (s/cat :tokens (s/coll-of :shiftlefter.gherkin.tokens/token))
  :ret ::parse-result)

(s/fdef parse-string
  :args (s/cat :s string?)
  :ret ::full-parse-result)

(s/fdef pickles
  :args (s/cat :ast (s/coll-of :shiftlefter.gherkin.parser/ast-node)
               :uri string?)
  :ret ::pickle-result)

(s/fdef print-tokens
  :args (s/cat :tokens (s/coll-of :shiftlefter.gherkin.tokens/token))
  :ret string?)

(s/fdef roundtrip-ok?
  :args (s/cat :s string?)
  :ret boolean?)

(s/fdef fmt-check
  :args (s/cat :s string?)
  :ret ::fmt-check-result)

(s/fdef fmt-canonical
  :args (s/cat :s string?)
  :ret ::fmt-canonical-result)

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
  "Check if string is already in canonical format.

   Returns {:status :ok/:error ...}
   - On success (already canonical): {:status :ok}
   - On parse error: {:status :error :reason :parse-errors :details [...]}
   - On needs formatting: {:status :error :reason :needs-formatting}"
  [s]
  (try
    (let [canonical (printer/canonical s)]
      (if (= s canonical)
        {:status :ok}
        {:status :error
         :reason :needs-formatting}))
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (if (:errors data)
          {:status :error
           :reason :parse-errors
           :details (vec (:errors data))}
          {:status :error
           :reason (:reason data :fmt-check/unknown-error)
           :message (ex-message e)})))))

(defn fmt-canonical
  "Format a Gherkin string to canonical style.

   Returns {:status :ok/:error ...}
   - On success: {:status :ok :output \"formatted string\"}
   - On parse error: {:status :error :reason :parse-errors :details [...]}

   Canonical formatting:
   - Normalizes indentation (2 spaces)
   - Normalizes line endings to LF
   - Normalizes tag spacing to single space
   - Supports all Gherkin constructs including Rule: blocks"
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
