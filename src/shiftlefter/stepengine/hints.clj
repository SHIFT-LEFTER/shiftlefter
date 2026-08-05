(ns shiftlefter.stepengine.hints
  "Grammar hints for undefined steps (sl-s04n).

   When a step matches no stepdef, classify WHY from the step text and emit
   at most one teaching hint. Classification is repair-and-rematch: propose a
   repaired step text (double quotes -> single, quoted ref -> bare, close an
   unbalanced quote) and re-match it against the same candidate stepdefs. A
   matching repair is the confidence signal — it proves what the author
   plausibly meant (and, for quoted refs, that the slot admits bare refs)
   without guessing at slot kinds. When no repair matches, the hint falls
   back to the law-backed generic wording, which is correct regardless of
   what would have matched.

   Classifier order (structural, not priority): unbalanced single quotes
   gate span analysis, so they are checked first; double quotes are only
   significant outside single-quoted spans (a `\"` inside a literal is an
   ordinary character); an undoubled apostrophe INSIDE a balanced span is
   checked before quoted-ref classification (sl-jlqz — the literal grammar
   writes it doubled, '');  quoted-ref classification needs balanced spans.
   The bare-value family (sl-38fy) runs last among the repair families: it
   has no textual cue at all — bare words are ordinary prose in most
   undefined steps — so its only evidence is the rematch itself, and it
   stays silent without one. The prose-as-step family (sl-bav1) is the
   terminal fallback after even that: teaching-only (no repair makes prose
   match), fired by a structural cue — zero structured tokens plus sentence
   punctuation — for description prose that opened with a step keyword and
   so lexed as a real step.

   Apostrophe rule: a `'` flanked by letters on both sides is never a slot
   quote (\"the user's dashboard\" contributes no quotes). Doubling rule
   (sl-jlqz): two adjacent slot quotes inside a span are one escaped quote,
   not a close-and-reopen.

   Pure functions; callers supply stepdefs and the loaded intents map."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [shiftlefter.intent.resolve :as intent-resolve]))

;; -----------------------------------------------------------------------------
;; Spec — hint shape (crosses the bind -> report boundary; EDN-stable envelope,
;; open :type set: future hint families add types without breaking consumers)
;; -----------------------------------------------------------------------------

(s/def ::type #{:grammar/unbalanced-quote :grammar/double-quotes
                :grammar/quoted-ref :grammar/empty-literal
                :grammar/apostrophe-in-literal :grammar/bare-value
                :grammar/prose-as-step})
(s/def ::suggestion string?)
(s/def ::repaired-text string?)
(s/def ::did-you-mean string?)

(s/def ::hint
  (s/keys :req-un [::type ::suggestion]
          :opt-un [::repaired-text ::did-you-mean]))

;; -----------------------------------------------------------------------------
;; Quote scanning
;; -----------------------------------------------------------------------------

(defn- letter-at?
  [^String text i]
  (and (< -1 i (count text))
       (Character/isLetter (.charAt text i))))

(defn brace-spans
  "Top-level balanced `{...}` spans as `[start end]` index pairs (inclusive
   of both braces). These are EDN locator/binding slots — opaque tokens to
   the grammar-hint scanner (sl-y9gn): quotes inside them are legitimate
   EDN, not step-grammar markers, so no quote family may fire on them and
   no repair may edit them.

   FAILS OPEN by design: an unclosed `{` yields no span for it, so its tail
   is scanned as ordinary prose — same behavior as before this fn existed.
   Better to over-hint on genuinely malformed brace text than to silently
   blind the scanner to the rest of the step."
  [^String text]
  (loop [i 0 depth 0 start nil acc []]
    (if (= i (count text))
      acc
      (let [c (.charAt text i)]
        (cond
          (= c \{) (recur (inc i) (inc depth) (if (zero? depth) i start) acc)
          (= c \}) (if (= 1 depth)
                     (recur (inc i) 0 nil (conj acc [start i]))
                     (recur (inc i) (max 0 (dec depth)) start acc))
          :else (recur (inc i) depth start acc))))))

(defn- inside-brace?
  [spans i]
  (boolean (some (fn [[a b]] (< a i b)) spans)))

(defn slot-quote-indices
  "Indices of `'` characters that act as quote markers: every `'` except one
   flanked by letters on both sides (an apostrophe inside a word) or one
   inside a brace-delimited EDN slot (opaque, sl-y9gn)."
  [^String text]
  (let [braces (brace-spans text)]
    (vec (for [i (range (count text))
               :when (and (= \' (.charAt text i))
                          (not (inside-brace? braces i))
                          (not (and (letter-at? text (dec i))
                                    (letter-at? text (inc i)))))]
           i))))

(defn single-quote-spans
  "Paired slot-quote indices as spans `{:start :end :inner}` (indices of the
   opening and closing quotes; :inner excludes them and keeps doubled pairs
   raw, not undoubled). Doubling-aware (sl-jlqz): two textually adjacent
   slot quotes INSIDE a span are one escaped quote, so `'don''t'` is a
   single span — while a bare `''` still opens and immediately closes (the
   empty-literal cue). An unpaired trailing quote yields no span — balance
   is the caller's check."
  [text]
  (loop [idxs (seq (slot-quote-indices text)) open nil acc []]
    (if-not idxs
      acc
      (let [[i & more] idxs]
        (cond
          (nil? open)
          (recur more i acc)

          ;; adjacent pair inside a span = escaped quote, consume both
          (and (some? (first more)) (= (first more) (inc i)))
          (recur (next more) open acc)

          :else
          (recur more nil
                 (conj acc {:start open :end i
                            :inner (subs text (inc open) i)})))))))

(defn- outside-spans?
  [spans i]
  (not-any? #(<= (:start %) i (:end %)) spans))

(defn- double-quote-indices
  "Indices of `\"` characters outside single-quoted spans AND outside
   brace-delimited EDN slots (sl-y9gn) — the only positions where a double
   quote is a grammar error rather than an ordinary character inside a
   literal or a locator. Repairs edit the ORIGINAL text at exactly these
   indices, so a locator's own quotes are never converted."
  [^String text]
  (let [spans (single-quote-spans text)
        braces (brace-spans text)]
    (vec (for [i (range (count text))
               :when (and (= \" (.charAt text i))
                          (outside-spans? spans i)
                          (not (inside-brace? braces i)))]
           i))))

(defn stray-double-quote?
  "True when any `\"` sits outside single-quoted spans."
  [text]
  (boolean (seq (double-quote-indices text))))

(defn quoted-ref-spans
  "Single-quoted spans whose interior is ref-shaped (bare PascalCase token,
   optionally dotted) — quoted content that looks like a glossary ref."
  [text]
  (filterv #(intent-resolve/location-ref? (:inner %))
           (single-quote-spans text)))

;; -----------------------------------------------------------------------------
;; Repairs
;; -----------------------------------------------------------------------------

(defn repair-double->single
  "Replace every double quote outside single-quoted spans with a single
   quote. Returns nil when there is nothing to repair."
  [^String text]
  (when-let [indices (seq (double-quote-indices text))]
    (let [sb (StringBuilder. text)]
      (doseq [i indices]
        (.setCharAt sb i \'))
      (str sb))))

(defn repair-unquote-refs
  "Strip the quotes from every ref-shaped single-quoted span. Returns nil
   when no span is ref-shaped."
  [text]
  (when-let [spans (seq (quoted-ref-spans text))]
    (reduce (fn [s {:keys [start end]}]
              (str (subs s 0 start) (subs s (inc start) end)
                   (subs s (inc end))))
            text
            (reverse spans))))

(defn close-quote-candidates
  "Candidate repairs for an odd slot-quote count: close the literal at the
   end of the step, drop the last (stray) slot quote, or double one of the
   interior quotes (sl-jlqz — `'friends' page'` plausibly meant
   `'friends'' page'`)."
  [text]
  (let [indices (slot-quote-indices text)
        last-i (peek indices)]
    (into [(str text "'")
           (str (subs text 0 last-i) (subs text (inc last-i)))]
          (map (fn [i] (str (subs text 0 i) "'" (subs text i))))
          (rest indices))))

(def ^:private undoubled-apostrophe-re
  "A `'` that is not part of a doubled pair — the character the literal
   grammar requires to be written '' (sl-jlqz)."
  #"(?<!')'(?!')")

(defn repair-double-apostrophes
  "Double every undoubled apostrophe inside balanced single-quoted spans.
   Returns nil when no span contains one."
  [text]
  (let [spans (filterv #(re-find undoubled-apostrophe-re (:inner %))
                       (single-quote-spans text))]
    (when (seq spans)
      (reduce (fn [s {:keys [start end inner]}]
                (str (subs s 0 (inc start))
                     (str/replace inner undoubled-apostrophe-re "''")
                     (subs s end)))
              text
              (reverse spans)))))

(defn quote-tail-candidates
  "Candidate repairs for bare words in a trailing value slot (sl-38fy):
   single-quote the tail starting at each word boundary, longest tail
   first. The tail zone never crosses a slot quote or a brace-delimited
   EDN slot — locators and existing literals are opaque, so tails begin
   strictly after the last of either. Interior apostrophes in the quoted
   tail are doubled, as the literal grammar requires (sl-jlqz)."
  [^String text]
  (let [zone-start (inc (max (or (peek (slot-quote-indices text)) -1)
                             (or (second (peek (brace-spans text))) -1)))
        word-start? (fn [i]
                      (and (pos? i)
                           (>= i zone-start)
                           (not= \space (.charAt text i))
                           (= \space (.charAt text (dec i)))))]
    (vec (for [i (range (count text))
               :when (word-start? i)]
           (str (subs text 0 i)
                "'" (str/replace (subs text i) undoubled-apostrophe-re "''")
                "'")))))

;; -----------------------------------------------------------------------------
;; Rematch + glossary probe
;; -----------------------------------------------------------------------------

(defn- first-full-match
  "First stepdef whose :pattern full-string-matches `text`, or nil."
  [text stepdefs]
  (some #(when (re-matches (:pattern %) text) %) stepdefs))

(defn- would-resolve?
  "Would the bare (unquoted) ref-shaped string resolve against the loaded
   intent glossary? Undotted names are checked as named locations; dotted
   refs as element paths or dotted named-location refs. nil intents -> false
   (hint degrades to the generic wording)."
  [interior interface intents]
  (boolean
   (when intents
     (try
       (or (nil? (intent-resolve/validate-location-static
                  intents interior interface))
           (when (str/includes? interior ".")
             (let [{:keys [ok]} (intent-resolve/parse-intent-ref interior)]
               (and ok
                    (nil? (intent-resolve/validate-path-static
                           intents ok interface))))))
       ;; Same guard as svo.validate: a partially-loaded glossary must
       ;; degrade the hint, never throw out of a diagnostics pass.
       (catch Exception _ false)))))

(defn- stepdef-interface
  [stepdef]
  (or (-> stepdef :metadata :interface) :web))

;; -----------------------------------------------------------------------------
;; Hint builders
;; -----------------------------------------------------------------------------

(defn- with-repair
  "Attach :repaired-text and append the did-you-mean sentence when one of
   `candidates` full-matches a stepdef."
  [hint candidates stepdefs]
  (if-let [repaired (some #(when (first-full-match % stepdefs) %) candidates)]
    (-> hint
        (assoc :repaired-text repaired)
        (update :suggestion str " Did you mean: " repaired "?"))
    hint))

(defn- unbalanced-hint
  [text stepdefs]
  (with-repair
    {:type :grammar/unbalanced-quote
     :suggestion "Unbalanced single quote - a literal may be missing its closing '."}
    (close-quote-candidates text)
    stepdefs))

(defn- double-quote-hint
  [text stepdefs]
  (with-repair
    {:type :grammar/double-quotes
     :suggestion "ShiftLefter literals use single quotes; double quotes are reserved."}
    (some-> (repair-double->single text) vector)
    stepdefs))

(defn- quoted-ref-hint
  "Row 3: quoted ref-shaped content. A matching unquote-repair proves the
   slot admits bare refs; the glossary probe then picks between the
   did-you-mean and the takes-refs-not-literals wording. Without a matching
   repair, only the generic quoted-is-literal law is asserted."
  [text ref-spans stepdefs intents]
  (let [interior (:inner (first ref-spans))
        repaired (repair-unquote-refs text)
        matched (when repaired (first-full-match repaired stepdefs))
        ;; nil = glossary unavailable (unchecked), false = checked and absent
        resolves (when (and matched intents)
                   (would-resolve? interior (stepdef-interface matched) intents))]
    (cond
      resolves
      {:type :grammar/quoted-ref
       :suggestion (str "Quoted values are always literals, but " interior
                        " resolves as a glossary ref. Did you mean " interior
                        " (unquoted)?")
       :repaired-text repaired
       :did-you-mean interior}

      matched
      {:type :grammar/quoted-ref
       :suggestion (str "This slot takes glossary refs, not literals (quoted"
                        " is always literal)."
                        (when (false? resolves)
                          (str " '" interior "' does not resolve as a ref.")))
       :repaired-text repaired}

      :else
      {:type :grammar/quoted-ref
       :suggestion (str "'" interior "' looks like a glossary ref, but quoted"
                        " values are always literals; refs are bare"
                        " (unquoted).")})))

(defn- apostrophe-hint
  "sl-jlqz ride-along: a balanced span holding an undoubled apostrophe —
   `'Don't have an account?'` — where the literal grammar requires ''.
   Repair-and-rematch: doubling the interior apostrophes proves the
   author's plausible intent; without a match the law-backed generic
   wording stands."
  [text stepdefs]
  (with-repair
    {:type :grammar/apostrophe-in-literal
     :suggestion (str "An apostrophe inside a literal is written doubled"
                      " ('') - a single ' would end the literal.")}
    (some-> (repair-double-apostrophes text) vector)
    stepdefs))

(defn- bare-value-hint
  "sl-38fy (y9gn option C): bare words where a value slot expects a quoted
   literal or a {Name} binding — ':user fills {:id \"code\"} with the SMS
   code'. Rematch-gated: the family has no textual cue, so it fires ONLY
   when quoting a tail produces a full match against a candidate stepdef —
   the proof of a near-miss frame. Without one, nil: bare words are
   ordinary undefined text."
  [text stepdefs]
  (let [hint (with-repair
               {:type :grammar/bare-value
                :suggestion (str "Value slots take a quoted literal ('...')"
                                 " or a binding ({Name}).")}
               (quote-tail-candidates text)
               stepdefs)]
    (when (:repaired-text hint) hint)))

(defn- empty-literal-hint
  "An empty literal ('') is not admitted anywhere — emptying a field wants
   the clears verb, not an empty value. Direct classification, no repair to
   offer; a placeholder rematch only recovers the step's own element ref so
   the suggestion can name it (clears Login.code) when the rest of the step
   is fills-shaped."
  [text stepdefs]
  (let [probe (str/replace text "''" "'x'")
        matched (first-full-match probe stepdefs)
        element (when matched
                  (let [m (re-matches (:pattern matched) probe)
                        caps (if (string? m) [] (rest m))]
                    (first (filter intent-resolve/location-ref? caps))))]
    {:type :grammar/empty-literal
     :suggestion (if element
                   (str "Empty literals aren't admitted - to empty a field:"
                        " clears " element)
                   (str "Empty literals aren't admitted - to empty a field,"
                        " use a verb: clears"))}))

(def ^:private sentence-end-chars
  "Terminal characters that mark a sentence — the punctuation half of the
   prose cue (sl-bav1). `…` covers dictated ellipses; a literal `...` ends
   in `.` and needs no case of its own."
  #{\. \! \? \…})

(def ^:private trailing-punct-re
  "Trailing punctuation stripped from a word before the ref-shape test —
   `Login.` and `Login.submit,` must still count as ref-shaped tokens."
  #"[.,;:!?…)\"]+$")

(defn- prose-like?
  "Structural cue for description prose that lexed as a step (sl-bav1,
   Chair-ruled): ZERO structured tokens — no slot quotes, no brace
   characters (even an unclosed `{` is structure evidence here; stricter
   than brace-spans, whose fail-open serves scanning, not classification),
   no bare-ref-shaped word (any PascalCase token, dotted or not, after
   trailing punctuation is stripped) — AND the text ends in sentence
   punctuation. Both halves required: real steps essentially never end in
   sentence punctuation, and prose essentially always does."
  [^String text]
  (let [trimmed (str/trimr text)]
    (boolean
     (and (seq trimmed)
          (contains? sentence-end-chars (.charAt trimmed (dec (count trimmed))))
          (empty? (slot-quote-indices text))
          (not (str/includes? text "{"))
          (not (str/includes? text "}"))
          (not-any? #(intent-resolve/location-ref?
                      (str/replace % trailing-punct-re ""))
                    (str/split trimmed #"\s+"))))))

(def ^:private prose-as-step-suggestion
  ;; Byte-pinned wording (sl-bav1, Chair-previewed; `(or *)` amendment
  ;; ratified at plan review) — tests assert it verbatim.
  (str "This line reads like description prose. Descriptions end at the"
       " first Given/When/Then/And/But (or *) — move prose above the first"
       " step or rephrase so it does not open with a step keyword."))

(defn- prose-as-step-hint
  "sl-bav1: description prose that opens with a step keyword lexes as a
   real (undefined) step — standard Gherkin, Cucumber-identical, and
   staying that way. Teaching-only: no did-you-mean, because no repair
   makes prose match; the hint names the trap instead."
  [text]
  (when (prose-like? text)
    {:type :grammar/prose-as-step
     :suggestion prose-as-step-suggestion}))

;; -----------------------------------------------------------------------------
;; Entry point
;; -----------------------------------------------------------------------------

(defn undefined-hint
  "Classify a zero-match step text into at most one grammar hint.

   Parameters:
   - match-text: the (annotation-stripped) step text that matched nothing
   - stepdefs: the candidate stepdefs the text was matched against
   - intents: loaded intents map (or nil — glossary probes degrade)

   Returns a map conforming to ::hint, or nil when no cue is present."
  [match-text stepdefs intents]
  (let [quote-count (count (slot-quote-indices match-text))]
    (cond
      (odd? quote-count)
      (unbalanced-hint match-text stepdefs)

      (stray-double-quote? match-text)
      (double-quote-hint match-text stepdefs)

      (some #(= "" (:inner %)) (single-quote-spans match-text))
      (empty-literal-hint match-text stepdefs)

      (some #(re-find undoubled-apostrophe-re (:inner %))
            (single-quote-spans match-text))
      (apostrophe-hint match-text stepdefs)

      :else
      (if-let [ref-spans (seq (quoted-ref-spans match-text))]
        (quoted-ref-hint match-text ref-spans stepdefs intents)
        (or (bare-value-hint match-text stepdefs)
            (prose-as-step-hint match-text))))))

(s/fdef undefined-hint
  :args (s/cat :match-text string?
               :stepdefs (s/coll-of map?)
               :intents (s/nilable map?))
  :ret (s/nilable ::hint))
