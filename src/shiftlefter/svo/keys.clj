(ns shiftlefter.svo.keys
  "The closed key-name set for :object-kind :key slots (sl-2sj6).

   Canonical surface names are W3C KeyboardEvent.key values — the browser
   interop standard, not any framework's dialect (grammar-markers.md,
   ratified 2026-07-22). Keys are STRUCTURE: the set is framework-owned
   and closed, so an unknown name is always a plan error — there is no
   world in which a typo'd key name becomes valid by editing a project.

   ONE documented deviation, and the exception list stays at exactly this
   entry: `Space`. The key-value namespace has no name for the spacebar
   (its value IS \" \"), and a quoted invisible whitespace character is
   the least legible token this grammar could produce — legibility
   outranks naming purity. `presses ' '` also works via the single-char
   rule and lands on the same key; `Space` is canonical. Numpad names
   (Numpad0…) are deliberately absent: they live in the `code` namespace,
   the same deviation class, and would be exception #2.

   This ns owns ONLY the name set — plan-time vocabulary. Vendor
   translation lives with each adapter (name → code, sl-52ly):
   shiftlefter.webdriver.etaoin.keys and
   shiftlefter.webdriver.playwright.keys. The parity test
   (webdriver/keys_parity_test.clj) pins the vendor tables to this set,
   so extending the set without extending an adapter fails the build.")

(def key-names
  "Sorted vector of every admissible unquoted key name — the single
   source of truth for the closed set."
  ["Alt" "ArrowDown" "ArrowLeft" "ArrowRight" "ArrowUp" "Backspace"
   "Cancel" "Clear" "Control" "Delete" "End" "Enter" "Escape"
   "F1" "F10" "F11" "F12" "F2" "F3" "F4" "F5" "F6" "F7" "F8" "F9"
   "Help" "Home" "Insert" "Meta" "PageDown" "PageUp" "Pause" "Shift"
   "Space" "Tab"])

(def ^:private key-name-set (set key-names))

(defn known-key?
  "True when s is a member of the closed key-name set."
  [s]
  (contains? key-name-set s))
