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
   ordinary character); quoted-ref classification needs balanced spans.

   Apostrophe rule: a `'` flanked by letters on both sides is never a slot
   quote (\"the user's dashboard\" contributes no quotes).

   Pure functions; callers supply stepdefs and the loaded intents map."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [shiftlefter.intent.resolve :as intent-resolve]))

;; -----------------------------------------------------------------------------
;; Spec — hint shape (crosses the bind -> report boundary; EDN-stable envelope,
;; open :type set: future hint families add types without breaking consumers)
;; -----------------------------------------------------------------------------

(s/def ::type #{:grammar/unbalanced-quote :grammar/double-quotes
                :grammar/quoted-ref :grammar/empty-literal})
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
   opening and closing quotes; :inner excludes them). An unpaired trailing
   quote yields no span — balance is the caller's check."
  [text]
  (mapv (fn [[a b]] {:start a :end b :inner (subs text (inc a) b)})
        (partition 2 (slot-quote-indices text))))

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
   end of the step, or drop the last (stray) slot quote."
  [text]
  (let [indices (slot-quote-indices text)
        last-i (peek indices)]
    [(str text "'")
     (str (subs text 0 last-i) (subs text (inc last-i)))]))

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

      :else
      (when-let [ref-spans (seq (quoted-ref-spans match-text))]
        (quoted-ref-hint match-text ref-spans stepdefs intents)))))

(s/fdef undefined-hint
  :args (s/cat :match-text string?
               :stepdefs (s/coll-of map?)
               :intents (s/nilable map?))
  :ret (s/nilable ::hint))
