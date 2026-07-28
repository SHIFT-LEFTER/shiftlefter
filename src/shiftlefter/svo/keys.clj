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

   Values are etaoin key constants (W3C WebDriver code points) consumed by
   the browser-protocol press path; the Playwright backend translates them
   back to W3C names via shiftlefter.webdriver.playwright.keys."
  (:require [etaoin.keys :as k]))

(def w3c-key->etaoin
  "W3C KeyboardEvent.key name → etaoin key constant. The single source of
   truth for the closed set: plan-time validation uses its keys, exec
   resolution uses its values."
  {"Enter"      k/enter
   "Tab"        k/tab
   "Escape"     k/escape
   "Backspace"  k/backspace
   "Delete"     k/delete
   "Insert"     k/insert
   "Home"       k/home
   "End"        k/end
   "PageUp"     k/pageup
   "PageDown"   k/pagedown
   "ArrowUp"    k/arrow-up
   "ArrowDown"  k/arrow-down
   "ArrowLeft"  k/arrow-left
   "ArrowRight" k/arrow-right
   "F1"         k/f1
   "F2"         k/f2
   "F3"         k/f3
   "F4"         k/f4
   "F5"         k/f5
   "F6"         k/f6
   "F7"         k/f7
   "F8"         k/f8
   "F9"         k/f9
   "F10"        k/f10
   "F11"        k/f11
   "F12"        k/f12
   "Shift"      k/shift-left
   "Control"    k/control-left
   "Alt"        k/alt-left
   "Meta"       k/meta-left
   "Pause"      k/pause
   "Clear"      k/clear
   "Help"       k/help
   "Cancel"     k/cancel
   ;; The documented deviation — see ns docstring.
   "Space"      k/space})

(def key-names
  "Sorted vector of every admissible unquoted key name."
  (vec (sort (keys w3c-key->etaoin))))

(defn known-key?
  "True when s is a member of the closed key-name set."
  [s]
  (contains? w3c-key->etaoin s))
