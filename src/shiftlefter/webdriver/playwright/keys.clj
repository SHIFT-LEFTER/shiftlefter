(ns shiftlefter.webdriver.playwright.keys
  "Translation from W3C WebDriver key codes (used by etaoin.keys) to
   Playwright key names.

   Etaoin represents special keys as single Unicode characters in the
   Private Use Area (U+E000–U+E05D). Playwright's keyboard API uses
   human-readable string names like \"Enter\", \"Tab\", \"Shift\", etc.

   This module provides translation between the two so that the
   PlaywrightBrowser can accept the same key-str values produced by
   etaoin.keys/chord and the step definitions' resolve-key-expression."
  (:require [clojure.string :as str]))

;; W3C WebDriver key codes → Playwright key names
;; Source: https://www.w3.org/TR/webdriver/#keyboard-actions
;; Source: https://playwright.dev/java/docs/api/class-keyboard#keyboard-press

(def ^:private webdriver-to-playwright
  "Map of W3C WebDriver Unicode code points to Playwright key names."
  {0xE000 "Unidentified"     ; Null
   0xE001 "Cancel"
   0xE002 "Help"
   0xE003 "Backspace"
   0xE004 "Tab"
   0xE005 "Clear"
   0xE006 "Enter"            ; Return
   0xE007 "Enter"            ; Enter
   0xE008 "Shift"            ; Shift Left
   0xE009 "Control"          ; Control Left
   0xE00A "Alt"              ; Alt Left
   0xE00B "Pause"
   0xE00C "Escape"
   0xE00D " "                ; Space
   0xE00E "PageUp"
   0xE00F "PageDown"
   0xE010 "End"
   0xE011 "Home"
   0xE012 "ArrowLeft"
   0xE013 "ArrowUp"
   0xE014 "ArrowRight"
   0xE015 "ArrowDown"
   0xE016 "Insert"
   0xE017 "Delete"
   ;; 0xE018 = Semicolon — passed as literal
   ;; 0xE019 = Equals — passed as literal
   0xE01A "Numpad0"
   0xE01B "Numpad1"
   0xE01C "Numpad2"
   0xE01D "Numpad3"
   0xE01E "Numpad4"
   0xE01F "Numpad5"
   0xE020 "Numpad6"
   0xE021 "Numpad7"
   0xE022 "Numpad8"
   0xE023 "Numpad9"
   0xE024 "NumpadMultiply"
   0xE025 "NumpadAdd"
   0xE026 "NumpadSeparator"
   0xE027 "NumpadSubtract"
   0xE028 "NumpadDecimal"
   0xE029 "NumpadDivide"
   0xE031 "F1"
   0xE032 "F2"
   0xE033 "F3"
   0xE034 "F4"
   0xE035 "F5"
   0xE036 "F6"
   0xE037 "F7"
   0xE038 "F8"
   0xE039 "F9"
   0xE03A "F10"
   0xE03B "F11"
   0xE03C "F12"
   0xE03D "Meta"             ; Command / Meta Left
   ;; Right-side modifiers
   0xE050 "Shift"            ; Shift Right
   0xE051 "Control"          ; Control Right
   0xE052 "Alt"              ; Alt Right
   0xE053 "Meta"})           ; Meta Right

(def ^:private modifier-keys
  "Set of Playwright key names that are modifiers (held during chords)."
  #{"Shift" "Control" "Alt" "Meta"})

(defn webdriver-char->playwright
  "Convert a single W3C WebDriver Unicode character to a Playwright key name.
   Returns the Playwright key name string, or the character as-is for
   normal printable characters."
  [^Character c]
  (let [code (int c)]
    (if-let [pw-name (get webdriver-to-playwright code)]
      pw-name
      ;; Normal character — return as string
      (str c))))

(defn translate-key-string
  "Translate an etaoin key string (from etaoin.keys/chord or single key)
   to a Playwright-compatible key expression.

   Etaoin chords are strings of WebDriver Unicode chars. For example:
   - Single key: one-char string → Playwright key name
   - Chord (shift+a): modifier chars followed by the key char

   Returns a Playwright key expression string suitable for Page.press().
   For chords, returns 'Modifier+Key' format (e.g., 'Shift+a')."
  [^String key-str]
  (if (= 1 (count key-str))
    ;; Single key
    (webdriver-char->playwright (first key-str))
    ;; Multi-char: could be a chord (modifiers + key) or typed text
    ;; Etaoin chords: modifier-down chars, then key, then modifier-up chars
    ;; We need to extract the unique keys and build Playwright chord syntax
    (let [chars (seq key-str)
          ;; Deduplicate: etaoin chords repeat modifier chars (down + up)
          unique-keys (distinct (map webdriver-char->playwright chars))
          modifiers (filter modifier-keys unique-keys)
          non-modifiers (remove modifier-keys unique-keys)]
      (if (seq modifiers)
        ;; Chord: "Shift+Control+a" format
        (str/join "+" (concat modifiers non-modifiers))
        ;; No modifiers — just type the string
        ;; Playwright's press() only takes one key, so for multi-char
        ;; non-chord strings, we'd need to type them. For now, take first.
        (first unique-keys)))))
