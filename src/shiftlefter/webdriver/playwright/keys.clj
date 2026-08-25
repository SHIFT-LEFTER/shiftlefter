(ns shiftlefter.webdriver.playwright.keys
  "W3C KeyboardEvent.key name → Playwright key name — the Playwright
   backend's half of the key split (sl-52ly).

   Near-passthrough by design: Playwright's keyboard API already speaks
   W3C KeyboardEvent.key names, so for ShiftLefter's closed name set
   (shiftlefter.svo.keys) the translation is identity on every name but
   one — `Space`, whose Playwright form is the single space character.
   Single literal characters pass through unchanged, and name-level
   chords (\"Control+a\", parked as sl-eecq) are Playwright-native
   `Modifier+Key` syntax, so they pass through too.

   History: this ns used to decode etaoin's private-use unicode
   representation back into Playwright names — a double translation in
   which etaoin's dialect was the accidental interchange format. That
   middleman died with the split; its genuinely-different rows (the
   Numpad family, right-side modifiers, dual Enter codes) decoded etaoin
   codes unreachable from the closed name set. The parity test
   (webdriver/keys_parity_test.clj) asserts identity-except-Space over
   the whole set.")

(def ^:private w3c->playwright
  "The exception table. Everything not listed translates as itself."
  {"Space" " "})

(defn translate-key-string
  "Translate a key token from the press path (a W3C KeyboardEvent.key
   name from the closed set, or a single literal character) to the
   expression Playwright's press() wants."
  [token]
  (get w3c->playwright token token))
