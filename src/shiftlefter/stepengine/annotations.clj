(ns shiftlefter.stepengine.annotations
  "Interface annotation parsing for pickle steps (Shifted mode only).

   Feature files may prefix step text with `[:interface-name]` to declare
   which interface the step targets. This pass parses that prefix and
   attaches `:step/declared-interface` metadata to each pickle step —
   a binding-time filter honored by the binder.

   **`:step/text` is not mutated.** The annotation remains visible to
   reporters and events as a lane marker in the source. The binder strips
   the prefix ad-hoc at regex-match time.

   ## Pipeline position

   Runs post-pickle, pre-macro-expansion, in Shifted mode only. Vanilla
   mode treats `[:foo]` as literal step text (no new behavior introduced).

   ## Prefix regex

   `^\\[:[\\w./-]+\\]\\s+` — matches a single `[:keyword]` prefix followed by
   whitespace. The keyword may contain word chars, dots, slashes, and hyphens.

   ## Errors produced

   - `:annotation/on-macro-call-unsupported` — step has both `[:iface]`
     prefix and `\" +\"` macro suffix while macros are enabled. Banned for v1.
   - `:annotation/unknown-interface` — declared interface keyword is not
     present in the `:interfaces` config map keys. When the keyword is
     close (Levenshtein) to a configured one, the error carries a
     `:did-you-mean` field with the suggestion (sl-563).

   ## Example

   Input step text: `\"[:sms] :user/alice receives a message\"`

   Output step: adds `:step/declared-interface :sms`; `:step/text` unchanged.

   See `notes/sms-interface-plan.md`, sl-1ya."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Specs
;; -----------------------------------------------------------------------------

(s/def ::declared-interface keyword?)

(s/def :annotation/type
  #{:annotation/on-macro-call-unsupported
    :annotation/unknown-interface})

(s/def :annotation/message string?)
(s/def :annotation/step-text string?)
(s/def :annotation/step-id any?)
(s/def :annotation/known-interfaces (s/coll-of keyword? :kind vector?))
(s/def :annotation/location (s/nilable map?))
(s/def :annotation/did-you-mean (s/nilable keyword?))

(s/def ::error
  (s/keys :req-un [:annotation/type :annotation/message
                   ::declared-interface
                   :annotation/step-text]
          :opt-un [:annotation/step-id :annotation/known-interfaces
                   :annotation/location :annotation/did-you-mean]))

;; -----------------------------------------------------------------------------
;; Prefix Regex & Strip
;; -----------------------------------------------------------------------------

(def prefix-regex
  "Pattern for `[:interface]` prefix followed by whitespace.
   Captures the interface name (without the leading colon or brackets)."
  #"^\[:([\w./-]+)\]\s+")

;; -----------------------------------------------------------------------------
;; Did-You-Mean (Levenshtein over interface keywords, sl-563)
;; -----------------------------------------------------------------------------

(defn- levenshtein
  "Edit distance between two strings (insertions/deletions/substitutions
   each cost 1). Iterative two-row DP."
  [a b]
  (let [m (count a) n (count b)]
    (cond
      (zero? m) n
      (zero? n) m
      :else
      (loop [i 0 prev (vec (range (inc n)))]
        (if (= i m)
          (peek prev)
          (let [ai (.charAt ^String a i)
                curr (loop [j 0 row (transient [(inc i)])]
                       (if (= j n)
                         (persistent! row)
                         (let [bj (.charAt ^String b j)
                               cost (if (= ai bj) 0 1)
                               above (get prev (inc j))
                               left (nth row j)
                               diag (get prev j)]
                           (recur (inc j)
                                  (conj! row (min (inc above)
                                                  (inc left)
                                                  (+ diag cost)))))))]
            (recur (inc i) curr)))))))

(defn- closest-interface
  "Return the known interface keyword closest to `target` by edit distance,
   or nil if none is close enough to be a useful suggestion.

   Threshold: distance must be ≤ max(2, ⌊len/3⌋). This catches typical typos
   (`:wbe` → `:web`, `:sm` → `:sms`, `:webb` → `:web`) without proposing
   wildly different keywords for unrelated names."
  [target known]
  (when (and target (seq known))
    (let [target-name (name target)
          len (count target-name)
          threshold (max 2 (quot len 3))
          [best dist] (->> known
                           (map (fn [k] [k (levenshtein target-name (name k))]))
                           (sort-by second)
                           first)]
      (when (and best (<= dist threshold) (pos? dist))
        best))))

(def ^:private macro-suffix " +")

(defn strip-annotation
  "Return step text with `[:iface]` prefix removed.
   If no prefix is present, returns the input unchanged.

   Called by the binder at match time — pickle steps themselves retain the
   original `:step/text` with annotation intact."
  [step-text]
  (str/replace-first step-text prefix-regex ""))

(defn- parse-interface
  "Extract the declared interface keyword from step text, or nil if no prefix."
  [step-text]
  (when-let [m (re-find prefix-regex step-text)]
    (keyword (second m))))

(defn- macro-call?
  "Does this step text end in the macro suffix `\" +\"`?"
  [step-text]
  (str/ends-with? step-text macro-suffix))

;; -----------------------------------------------------------------------------
;; Per-Step Annotation
;; -----------------------------------------------------------------------------

(defn- annotate-step
  "Process a single step for annotation.

   Returns `{:step step' :error error-or-nil}`.

   - No annotation → step unchanged, no error
   - Annotation + macro suffix (macros enabled) → `:annotation/on-macro-call-unsupported`
   - Annotation references interface not in config → `:annotation/unknown-interface`
   - Annotation OK → `:step/declared-interface` attached"
  [step interfaces macros-enabled?]
  (let [step-text (:step/text step)
        declared (parse-interface step-text)]
    (cond
      ;; No annotation — pass through unchanged
      (nil? declared)
      {:step step :error nil}

      ;; Annotation on a macro call while macros are enabled — banned for v1
      (and macros-enabled? (macro-call? step-text))
      {:step step
       :error {:type :annotation/on-macro-call-unsupported
               :message (str "Interface annotations are not supported on "
                             "macro calls (v1 limitation): \"" step-text "\"")
               :declared-interface declared
               :step-text step-text
               :step-id (:step/id step)
               :location (:step/location step)}}

      ;; Declared interface not present in :interfaces config
      (not (contains? interfaces declared))
      (let [known (vec (sort (keys interfaces)))
            suggestion (closest-interface declared known)]
        {:step step
         :error (cond-> {:type :annotation/unknown-interface
                         :message (str "Step declares interface " declared
                                       " which is not present in :interfaces config"
                                       (if (seq interfaces)
                                         (str " (known: " known ")")
                                         " (no interfaces configured)")
                                       (when suggestion
                                         (str ". Did you mean [" suggestion "]?")))
                         :declared-interface declared
                         :known-interfaces known
                         :step-text step-text
                         :step-id (:step/id step)
                         :location (:step/location step)}
                  suggestion (assoc :did-you-mean suggestion))})

      ;; OK — attach declared-interface
      :else
      {:step (assoc step :step/declared-interface declared)
       :error nil})))

;; -----------------------------------------------------------------------------
;; Pickle / Suite Passes
;; -----------------------------------------------------------------------------

(defn annotate-pickle
  "Process all steps in a pickle for interface annotations.

   Synthetic steps (macro wrappers) are guarded defensively but should not
   exist at this phase — annotations run before macro expansion.

   Returns `{:pickle pickle' :errors [error-maps]}`."
  [pickle interfaces macros-enabled?]
  (let [steps (:pickle/steps pickle)
        results (mapv (fn [step]
                        (if (:step/synthetic? step)
                          {:step step :error nil}
                          (annotate-step step interfaces macros-enabled?)))
                      steps)]
    {:pickle (assoc pickle :pickle/steps (mapv :step results))
     :errors (into [] (keep :error) results)}))

(defn annotate-pickles
  "Process all pickles. Returns `{:pickles pickles' :errors [...]}`.

   Parameters:
   - `pickles` — seq of pickles
   - `interfaces` — `:interfaces` config map (keys are configured interface
     keywords)
   - `macros-enabled?` — whether macros are enabled (affects macro-call
     collision detection)"
  [pickles interfaces macros-enabled?]
  (let [results (mapv #(annotate-pickle % interfaces macros-enabled?) pickles)]
    {:pickles (mapv :pickle results)
     :errors (into [] (mapcat :errors) results)}))
