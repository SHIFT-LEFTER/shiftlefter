(ns shiftlefter.gherkin.lexer
  (:require [clojure.string :as str]
            [shiftlefter.gherkin.location :as loc]
            [shiftlefter.gherkin.tokens :as tokens]
            [clojure.spec.alpha :as s]))

;; -----------------------------------------------------------------------------
;; Specs
;; -----------------------------------------------------------------------------

(s/def ::input string?)
(s/def ::token-seq (s/coll-of ::tokens/token :kind seq?))

(s/fdef lex
  :args (s/cat :input ::input)
  :ret ::token-seq)

;; -----------------------------------------------------------------------------
;; Lexer implementation
;; -----------------------------------------------------------------------------

(defn- split-lines-preserving-newlines
  "Split input into lines, preserving exact line endings on each line.
   Supports LF (\\n), CRLF (\\r\\n), and CR (\\r) line endings.
   E.g., 'a\\nb\\n' -> ['a\\n' 'b\\n']
         'a\\r\\nb\\r\\n' -> ['a\\r\\n' 'b\\r\\n']
         'a\\rb\\r' -> ['a\\r' 'b\\r']
         'a\\nb' -> ['a\\n' 'b']
   Does NOT normalize line endings - this is required for lossless roundtrip."
  [s]
  (if (empty? s)
    []
    ;; Match lines with their original line endings:
    ;; - CRLF must come before LF to avoid partial matching
    ;; - Then LF, then CR (old Mac style)
    ;; - Finally, content without any line ending (last line)
    (vec (re-seq #"[^\r\n]*\r\n|[^\r\n]*\n|[^\r\n]*\r|[^\r\n]+" s))))

(defn- update-docstring-state
  "Update docstring state based on token. Returns new state.
   State is nil (not in docstring) or :triple-quote or :backtick."
  [current-state token]
  (if (= (:type token) :docstring-separator)
    (let [fence (get-in token [:value :fence])]
      (cond
        ;; Not in docstring - entering
        (nil? current-state) fence
        ;; In docstring - only matching fence exits
        (= current-state fence) nil
        ;; Non-matching fence - stay in docstring (shouldn't happen with correct tokenization)
        :else current-state))
    current-state))

(defn lazy-lex-helper [lines line-idx token-idx lang docstring-state]
  (lazy-seq
    (if (< line-idx (count lines))
      (let [raw-line (nth lines line-idx)
            token (tokens/tokenize-line line-idx raw-line token-idx lang docstring-state)
            new-lang (if (= (:type token) :language-header)
                       (str/trim (:value token))
                       lang)
            new-docstring-state (update-docstring-state docstring-state token)]
        (cons token (lazy-lex-helper lines (inc line-idx) (inc token-idx) new-lang new-docstring-state)))
      (list (tokens/->Token :eof nil (loc/->Location (count lines) 0) "" token-idx "" nil)))))

(defn lex
  "Lex the input Gherkin string into a lazy sequence of tokens.
   Handles language switching via # language: comments.
   Tracks docstring state to correctly tokenize content inside docstrings.
   Each token includes :idx (monotonic from 0) and :raw (exact original text including newline)."
  [input]
  (let [lines (split-lines-preserving-newlines input)]
    (lazy-lex-helper lines 0 0 "en" nil)))