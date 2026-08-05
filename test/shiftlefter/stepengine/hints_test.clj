(ns shiftlefter.stepengine.hints-test
  "Unit tests for grammar hints on undefined steps (sl-s04n)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.stepengine.hints :as hints]))

;; Raw stepdef maps — the hint classifier only reads :pattern and :metadata.
(def ^:private stepdefs
  [{:pattern #"alice fills Login\.code with '((?:[^']|'')+)'"
    :metadata {:interface :web}}
   {:pattern #"alice clicks ([A-Z][A-Za-z0-9_.-]*)"
    :metadata {:interface :web}}])

;; Hand-built loaded-intents shape (same accessor contract as
;; loader/load-all-intents: :lookup / :regions / :intents).
(def ^:private intents
  {:lookup {["Login" "code"] {:bindings {:web {:css "#code"}}}}
   :regions {"Login" {:location {:web {:path "/login"}}}}
   :intents ["Login"]})

;; -----------------------------------------------------------------------------
;; Scanner
;; -----------------------------------------------------------------------------

(deftest slot-quote-scanner
  (testing "apostrophes flanked by letters are not slot quotes"
    (is (= [] (hints/slot-quote-indices "checks the user's dashboard"))))
  (testing "quote before a word and at end-of-string are slot quotes"
    (is (= 2 (count (hints/slot-quote-indices "fills X with 'x'")))))
  (testing "apostrophe inside a quoted literal does not break the span"
    (is (= [{:inner "the user's name"}]
           (map #(select-keys % [:inner])
                (hints/single-quote-spans "fills X with 'the user's name'")))))
  (testing "doubling-aware pairing (sl-jlqz): '' inside a span is one
            escaped quote, not a close-and-reopen"
    (is (= [{:inner "don''t"}]
           (map #(select-keys % [:inner])
                (hints/single-quote-spans "fills X with 'don''t'"))))
    (is (= [{:inner "''"}]
           (map #(select-keys % [:inner])
                (hints/single-quote-spans "fills X with ''''"))))
    (is (= [{:inner ""}]
           (map #(select-keys % [:inner])
                (hints/single-quote-spans "fills X with ''")))
        "a bare '' still opens and immediately closes — the empty-literal cue")
    (is (= [{:inner "a"} {:inner "b"}]
           (map #(select-keys % [:inner])
                (hints/single-quote-spans "sel 'a' from 'b'")))
        "non-adjacent quotes still pair as separate spans")))

(deftest double-quote-scanner
  (testing "double quote inside a single-quoted literal is ordinary (law scope)"
    (is (not (hints/stray-double-quote? "fills X with 'say \"hi\"'"))))
  (testing "double quotes outside spans are stray"
    (is (hints/stray-double-quote? "fills X with \"x\""))))

(deftest quoted-ref-span-scanner
  (testing "ref-shaped interiors only"
    (is (= ["Login.code"]
           (map :inner (hints/quoted-ref-spans "clicks 'Login.code'"))))
    (is (empty? (hints/quoted-ref-spans "fills X with 'lowercase value'")))))

;; -----------------------------------------------------------------------------
;; Case 1 — double quotes (grammar-markers row 8)
;; -----------------------------------------------------------------------------

(deftest double-quote-hint
  (let [hint (hints/undefined-hint "alice fills Login.code with \"x\""
                                   stepdefs intents)]
    (is (= :grammar/double-quotes (:type hint)))
    (is (= "alice fills Login.code with 'x'" (:repaired-text hint)))
    (is (str/includes? (:suggestion hint)
                       "literals use single quotes; double quotes are reserved"))
    (is (str/includes? (:suggestion hint)
                       "Did you mean: alice fills Login.code with 'x'?"))))

(deftest double-quote-hint-without-matching-repair
  (let [hint (hints/undefined-hint "alice frobs \"x\"" stepdefs intents)]
    (is (= :grammar/double-quotes (:type hint)))
    (is (nil? (:repaired-text hint)))
    (is (not (str/includes? (:suggestion hint) "Did you mean")))))

;; -----------------------------------------------------------------------------
;; Case 2 — unbalanced single quote (grammar-markers row 9)
;; -----------------------------------------------------------------------------

(deftest unbalanced-quote-hint
  (let [hint (hints/undefined-hint "alice fills Login.code with 'x"
                                   stepdefs intents)]
    (is (= :grammar/unbalanced-quote (:type hint)))
    (is (= "alice fills Login.code with 'x'" (:repaired-text hint))
        "closing the literal at end-of-step is the matching repair")))

(deftest unbalanced-gates-other-classifiers
  (testing "mixed cues: odd quote count wins (span analysis needs balance)"
    (let [hint (hints/undefined-hint "alice fills Login.code with \"x'"
                                     stepdefs intents)]
      (is (= :grammar/unbalanced-quote (:type hint)))
      (is (nil? (:repaired-text hint))))))

(deftest apostrophes-do-not-trigger-hints
  (is (nil? (hints/undefined-hint "alice checks the user's dashboard"
                                  stepdefs intents))
      "apostrophes in unquoted prose stay silent"))

(deftest unbalanced-doubling-candidate
  (testing "sl-jlqz: an interior stray quote repairs by doubling, with
            did-you-mean (was: close-at-end/drop-last only, both failing)"
    (let [hint (hints/undefined-hint
                "alice fills Login.code with 'friends' page'"
                stepdefs intents)]
      (is (= :grammar/unbalanced-quote (:type hint)))
      (is (= "alice fills Login.code with 'friends'' page'"
             (:repaired-text hint))))))

;; -----------------------------------------------------------------------------
;; sl-jlqz — undoubled apostrophe inside a balanced literal
;; -----------------------------------------------------------------------------

(deftest apostrophe-in-literal-hint
  (testing "letter-flanked apostrophe in a literal (pre-jlqz: silent no-match)
            — the repair doubles it and rematch proves the intent"
    (let [hint (hints/undefined-hint
                "alice fills Login.code with 'Don't stop'"
                stepdefs intents)]
      (is (= :grammar/apostrophe-in-literal (:type hint)))
      (is (= "alice fills Login.code with 'Don''t stop'"
             (:repaired-text hint)))
      (is (str/includes? (:suggestion hint) "written doubled"))
      (is (str/includes? (:suggestion hint)
                         "Did you mean: alice fills Login.code with 'Don''t stop'?")))))

(deftest apostrophe-in-literal-hint-without-matching-repair
  (let [hint (hints/undefined-hint "alice frobs 'the user's name'"
                                   stepdefs intents)]
    (is (= :grammar/apostrophe-in-literal (:type hint)))
    (is (nil? (:repaired-text hint)))
    (is (not (str/includes? (:suggestion hint) "Did you mean")))))

(deftest legal-doubling-is-not-a-cue
  (testing "a correctly doubled literal in a step that no-matches for other
            reasons draws no apostrophe hint"
    (is (nil? (hints/undefined-hint "alice frobs 'don''t'"
                                    stepdefs intents)))))

;; -----------------------------------------------------------------------------
;; Case 3 — quoted ref-shaped content (grammar-markers row 3)
;; -----------------------------------------------------------------------------

(deftest quoted-ref-resolving
  (let [hint (hints/undefined-hint "alice clicks 'Login.code'"
                                   stepdefs intents)]
    (is (= :grammar/quoted-ref (:type hint)))
    (is (= "Login.code" (:did-you-mean hint)))
    (is (= "alice clicks Login.code" (:repaired-text hint)))
    (is (str/includes? (:suggestion hint)
                       "Did you mean Login.code (unquoted)?"))))

(deftest quoted-ref-checked-and-absent
  (let [hint (hints/undefined-hint "alice clicks 'Missing.thing'"
                                   stepdefs intents)]
    (is (= :grammar/quoted-ref (:type hint)))
    (is (nil? (:did-you-mean hint)))
    (is (str/includes? (:suggestion hint)
                       "takes glossary refs, not literals"))
    (is (str/includes? (:suggestion hint) "does not resolve"))))

(deftest quoted-ref-glossary-unavailable
  (testing "nil intents: slot claim stands (proven by repair), no resolve claim"
    (let [hint (hints/undefined-hint "alice clicks 'Login.code'" stepdefs nil)]
      (is (= :grammar/quoted-ref (:type hint)))
      (is (some? (:repaired-text hint)))
      (is (not (str/includes? (:suggestion hint) "does not resolve"))))))

(deftest quoted-ref-no-matching-repair
  (testing "no repair match: only the quoted-is-literal law, no slot claim"
    (let [hint (hints/undefined-hint "alice pokes 'Login.code'"
                                     stepdefs intents)]
      (is (= :grammar/quoted-ref (:type hint)))
      (is (nil? (:repaired-text hint)))
      (is (not (str/includes? (:suggestion hint) "This slot takes")))
      (is (str/includes? (:suggestion hint) "quoted values are always literals")))))

;; -----------------------------------------------------------------------------
;; Empty literal '' — fit-pass condition (Chair 07-22, Q2 reversed:
;; the clears verb exists, so the hint points at something real)
;; -----------------------------------------------------------------------------

(deftest empty-literal-hint-names-the-element
  (testing "fills-shaped step: the suggestion uses the step's own element ref"
    (let [hint (hints/undefined-hint "alice fills Login.code with ''"
                                     [{:pattern #"alice fills ([A-Z][A-Za-z0-9_.-]*) with '([^']+)'"
                                       :metadata {:interface :web}}]
                                     nil)]
      (is (= :grammar/empty-literal (:type hint)))
      (is (= "Empty literals aren't admitted - to empty a field: clears Login.code"
             (:suggestion hint)))
      (is (not (contains? hint :repaired-text))
          "direct classification — no repair is claimed"))))

(deftest empty-literal-hint-generic-form
  (testing "no fills-shaped match: generic pointer at the verb"
    (let [hint (hints/undefined-hint "alice frobs '' wildly" stepdefs intents)]
      (is (= :grammar/empty-literal (:type hint)))
      (is (str/includes? (:suggestion hint) "use a verb: clears")))))

;; -----------------------------------------------------------------------------
;; No cue -> no hint
;; -----------------------------------------------------------------------------

(deftest cue-free-step-gets-no-hint
  (is (nil? (hints/undefined-hint "alice does something else"
                                  stepdefs intents))))

;; -----------------------------------------------------------------------------
;; sl-y9gn: brace-delimited EDN slots are opaque to the scanner. The decision
;; matrix from the Chair-ruled option review IS the fixture (condition 1):
;; genuine hints byte-pinned, locator-only cases pinned silent, and the
;; case-5 repair pinned with the locator's own quotes INTACT (the pre-fix
;; did-you-mean converted them, corrupting the locator into a symbol).
;; -----------------------------------------------------------------------------

;; Builtin-shaped patterns so repair-and-rematch is live for the matrix:
;; a locator slot accepts a brace form or a ref; values are single-quoted.
;; Value slots use the real doubling-aware shape '(?:[^']|'')*' (sl-38fy) —
;; the simplified '[^']*' would reject repairs that double an apostrophe.
(def ^:private matrix-stepdefs
  [{:pattern #"(?::\S+ )?fills (\{.+?\}|[A-Z][A-Za-z0-9_.-]*) with '((?:[^']|'')*)'"
    :metadata {:interface :web}}
   {:pattern #"(?::\S+ )?should see '((?:[^']|'')*)'"
    :metadata {:interface :web}}
   {:pattern #"(?::\S+ )?clicks (\{.+?\}|[A-Z][A-Za-z0-9_.-]*)"
    :metadata {:interface :web}}])

(deftest locator-quote-matrix
  (let [hint #(hints/undefined-hint % matrix-stepdefs nil)]
    (testing "cases 1/6 — quotes only inside EDN locators: SILENT (the
              undefined diagnostic itself still reports; no false repair)"
      (is (nil? (hint ":user/alice clicsk {:css \"button.submit\"}")))
      (is (nil? (hint ":user clicksk {:css \"button[type='submit']\"}"))))

    (testing "case 2 — bare words in a value slot (sl-38fy, y9gn option C):
              SUPERSEDES the option-A silent pin. Quote-the-tail rematches
              the fills frame, so the positive hint fires — locator quotes
              intact, the law sentence names both admitted value forms"
      (let [h (hint ":user fills {:id \"code\"} with the SMS code")]
        (is (= :grammar/bare-value (:type h)))
        (is (= ":user fills {:id \"code\"} with 'the SMS code'"
               (:repaired-text h)))
        (is (= (str "Value slots take a quoted literal ('...') or a binding"
                    " ({Name}). Did you mean: :user fills {:id \"code\"}"
                    " with 'the SMS code'?")
               (:suggestion h)))))

    (testing "cases 3/4 — genuine prose double quotes: byte-identical to the
              pre-fix hint, did-you-mean included"
      (let [h (hint ":user fills Login.email with \"alice@example.com\"")]
        (is (= :grammar/double-quotes (:type h)))
        (is (= ":user fills Login.email with 'alice@example.com'"
               (:repaired-text h)))
        (is (= (str "ShiftLefter literals use single quotes; double quotes"
                    " are reserved. Did you mean: :user fills Login.email"
                    " with 'alice@example.com'?")
               (:suggestion h))))
      (let [h (hint ":user should see \"Welcome\"")]
        (is (= ":user should see 'Welcome'" (:repaired-text h)))))

    (testing "case 5 — locator AND prose quotes: hint fires on the prose pair
              and the repair preserves the locator's own quotes (pre-fix it
              suggested {:id 'code'}, a symbol-valued locator that targets
              nothing)"
      (let [h (hint ":user fills {:id \"code\"} with \"1234\"")]
        (is (= :grammar/double-quotes (:type h)))
        (is (= ":user fills {:id \"code\"} with '1234'" (:repaired-text h)))
        (is (str/includes? (:suggestion h)
                           "Did you mean: :user fills {:id \"code\"} with '1234'?"))))

    (testing "boundary — unbalanced prose quote beside a locator: unbalanced
              family fires and its repair edits the ORIGINAL text (locator
              untouched, never a masked copy)"
      (let [h (hint ":user fills {:id \"code\"} with 'x")]
        (is (= :grammar/unbalanced-quote (:type h)))
        (is (= ":user fills {:id \"code\"} with 'x'" (:repaired-text h)))))

    (testing "boundary — stray unclosed brace fails open (scanner yields no
              span; prose scanning proceeds as before the fix)"
      (is (nil? (hint ":user clicks the { button")))
      (let [h (hint ":user clicks the { \"odd\" button")]
        (is (= :grammar/double-quotes (:type h))
            "unclosed brace does not blind the scanner to real prose quotes")))

    (testing "edge (condition 2) — a brace INSIDE the locator's own string:
              the depth counter never closes the outer span, so it fails
              open and the double-quote family fires as pre-fix"
      (let [h (hint ":user clicks {:text \"{\"}")]
        (is (= :grammar/double-quotes (:type h)))))))

;; -----------------------------------------------------------------------------
;; sl-38fy: bare-value family — rematch-gated (no textual cue; the proven
;; near-miss frame IS the evidence). Suffix tails only by design: interior
;; value slots (selects 'X' from ..., MMS caption/media) stay silent — the
;; pattern-relaxation follow-up bead opens when interior evidence appears.
;; -----------------------------------------------------------------------------

(deftest bare-value-tail-candidates
  (testing "tail zone starts after the last brace span — no candidate
            crosses or precedes the locator"
    (is (= [":user fills {:id \"a b\"} 'with x'"
            ":user fills {:id \"a b\"} with 'x'"]
           (hints/quote-tail-candidates ":user fills {:id \"a b\"} with x"))))
  (testing "tail zone starts after the last slot quote — existing literals
            are opaque"
    (is (= ["fills X with 'a' 'b c'"
            "fills X with 'a' b 'c'"]
           (hints/quote-tail-candidates "fills X with 'a' b c"))))
  (testing "interior apostrophes in the quoted tail are doubled (sl-jlqz);
            candidates run longest tail first"
    (is (= ["with 'the user''s code'"
            "with the 'user''s code'"
            "with the user's 'code'"]
           (hints/quote-tail-candidates "with the user's code")))))

(deftest bare-value-hint-fires-on-proven-frame
  (let [hint #(hints/undefined-hint % matrix-stepdefs nil)]
    (testing "apostrophe in the tail is doubled in the repair, and the
              doubling-aware value slot admits it"
      (let [h (hint ":user fills Login.note with the user's code")]
        (is (= :grammar/bare-value (:type h)))
        (is (= ":user fills Login.note with 'the user''s code'"
               (:repaired-text h)))))
    (testing "longest matching tail wins when several candidates rematch"
      (let [says-stepdefs [{:pattern #"says (.+) '((?:[^']|'')*)'"
                           :metadata {:interface :web}}]
            h (hints/undefined-hint "says hello big world" says-stepdefs nil)]
        (is (= "says hello 'big world'" (:repaired-text h))
            "'big world' (earlier boundary) beats 'world'")))))

(deftest bare-value-stays-silent-without-proof
  (let [hint #(hints/undefined-hint % matrix-stepdefs nil)]
    (testing "no near-miss frame: bare words are ordinary undefined text"
      (is (nil? (hint ":user frobnicates the widget"))))
    (testing "interior value slot (v1 boundary): tail quoting cannot reach
              a slot before 'from', so selects-shaped steps stay silent"
      (is (nil? (hints/undefined-hint
                 ":user selects Basic from Menu.plans"
                 [{:pattern #"(?::\S+ )?selects '((?:[^']|'')*)' from ([A-Z][A-Za-z0-9_.-]*)"
                   :metadata {:interface :web}}]
                 nil))))
    (testing "bare words after a locator with no matching frame: silent"
      (is (nil? (hint ":user clicks {:id \"x\"} now"))))))

;; -----------------------------------------------------------------------------
;; sl-bav1: prose-as-step — terminal teaching-only fallback. Description
;; prose that opens with a step keyword lexes as a real step (standard
;; Gherkin; the keyword is stripped before the classifier sees the text).
;; Structural cue (Chair-ruled): zero structured tokens (no slot quotes,
;; no brace characters, no PascalCase ref-shaped word — dotted or bare)
;; AND terminal sentence punctuation. No did-you-mean — no repair makes
;; prose match.
;; -----------------------------------------------------------------------------

(deftest prose-as-step-fires-on-the-v85e-bite
  (testing "the thrice-observed trap: 'When the gateway degrades, the
            client retries.' — keyword lexed off, prose left as step text"
    (let [h (hints/undefined-hint "the gateway degrades, the client retries."
                                  stepdefs intents)]
      (is (= :grammar/prose-as-step (:type h)))
      (is (= (str "This line reads like description prose. Descriptions end"
                  " at the first Given/When/Then/And/But (or *) — move prose"
                  " above the first step or rephrase so it does not open"
                  " with a step keyword.")
             (:suggestion h))
          "byte-pinned wording")
      (is (not (contains? h :repaired-text))
          "teaching-only — no repair is claimed"))))

(deftest prose-as-step-accepts-all-sentence-enders
  (doseq [text ["the gateway simply gives up!"
                "does the gateway even retry?"
                "the gateway degrades…"]]
    (is (= :grammar/prose-as-step
           (:type (hints/undefined-hint text stepdefs intents)))
        text)))

(deftest prose-as-step-negative-ref-shaped-tokens
  (testing "cue precision: an undefined real step ending in a period stays
            silent when it carries a ref-shaped token — dotted or bare"
    (is (nil? (hints/undefined-hint "alice clcks Login.submit."
                                    stepdefs intents))
        "dotted ref, trailing period stripped before the shape test")
    (is (nil? (hints/undefined-hint "goe to Login." stepdefs intents))
        "bare named-location ref counts as structure too (Chair-ruled)")))

(deftest prose-as-step-negative-no-sentence-punctuation
  (testing "both cue halves required: token-free prose without a terminal
            sentence mark stays silent (existing cue-free behavior)"
    (is (nil? (hints/undefined-hint
               "the gateway degrades and the client retries"
               stepdefs intents)))))

(deftest prose-as-step-negative-brace-character
  (testing "any brace character is structure evidence — even an unclosed
            one that brace-spans would fail open on"
    (is (nil? (hints/undefined-hint "the gateway { degrades, sadly."
                                    stepdefs intents)))))
