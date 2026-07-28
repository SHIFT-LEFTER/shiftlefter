(ns shiftlefter.stepengine.hints-test
  "Unit tests for grammar hints on undefined steps (sl-s04n)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.stepengine.hints :as hints]))

;; Raw stepdef maps — the hint classifier only reads :pattern and :metadata.
(def ^:private stepdefs
  [{:pattern #"alice fills Login\.code with '([^']+)'"
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
                (hints/single-quote-spans "fills X with 'the user's name'"))))))

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
                                  stepdefs intents)))
  (is (nil? (hints/undefined-hint "alice fills X with 'the user's name'"
                                  stepdefs intents))
      "apostrophe inside a balanced literal is not an unbalanced quote"))

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
(def ^:private matrix-stepdefs
  [{:pattern #"(?::\S+ )?fills (\{.+?\}|[A-Z][A-Za-z0-9_.-]*) with '([^']*)'"
    :metadata {:interface :web}}
   {:pattern #"(?::\S+ )?should see '([^']*)'"
    :metadata {:interface :web}}
   {:pattern #"(?::\S+ )?clicks (\{.+?\}|[A-Z][A-Za-z0-9_.-]*)"
    :metadata {:interface :web}}])

(deftest locator-quote-matrix
  (let [hint #(hints/undefined-hint % matrix-stepdefs nil)]
    (testing "cases 1/2/6 — quotes only inside EDN locators: SILENT (the
              undefined diagnostic itself still reports; no false repair)"
      (is (nil? (hint ":user/alice clicsk {:css \"button.submit\"}")))
      (is (nil? (hint ":user fills {:id \"code\"} with the SMS code")))
      (is (nil? (hint ":user clicksk {:css \"button[type='submit']\"}"))))

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
