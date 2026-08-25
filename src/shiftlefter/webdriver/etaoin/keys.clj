(ns shiftlefter.webdriver.etaoin.keys
  "W3C KeyboardEvent.key name → etaoin key constant — the etaoin backend's
   half of the key split (sl-52ly; table moved here from shiftlefter.svo.keys,
   where it was the one true etaoin containment break, module map F1).

   The language layer owns only the closed NAME set
   (shiftlefter.svo.keys); each adapter owns name → vendor-code directly.
   The parity test (webdriver/keys_parity_test.clj) pins this table's key
   set to the closed name set — adding a key name without extending this
   table fails the build."
  (:require [etaoin.keys :as k]))

(def w3c-key->etaoin
  "W3C KeyboardEvent.key name → etaoin key constant (W3C WebDriver code
   point). Key set == shiftlefter.svo.keys/key-names, parity-tested."
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
   ;; The documented deviation — see shiftlefter.svo.keys ns docstring.
   "Space"      k/space})

(defn ->key-str
  "Resolve a key token from the press path (a W3C KeyboardEvent.key name
   from the closed set, or a single literal character) to the string the
   WebDriver wire wants. Unknown tokens pass through unchanged — plan
   validation and resolve-key-capture upstream own the throw."
  [token]
  (if-let [key-val (get w3c-key->etaoin token)]
    (str key-val)
    token))
