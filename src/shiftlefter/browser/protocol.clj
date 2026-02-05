(ns shiftlefter.browser.protocol
  "Internal browser protocol defining kernel operations.

   All browser-backed stepdefs call these operations via an implementation
   of IBrowser (e.g., Etaoin-backed browser).

   ## Locators

   All locator arguments are **resolved** locators — the output of
   `shiftlefter.browser.locators/resolve-locator`. The minimum shape is:

   ```clj
   {:q {:css \"...\"}}   ; or {:xpath \"...\"}, {:id \"...\"}, etc.
   ```

   ## Kernel Operations (locked for 0.2.x)

   - `open-to!`     — navigate to URL
   - `click!`       — left-click element
   - `doubleclick!` — double-click element
   - `rightclick!`  — right-click (context menu)
   - `move-to!`     — move mouse to element
   - `drag-to!`     — drag from one element to another
   - `fill!`        — fill text input
   - `element-count` — count matching elements

   ## Query Operations (added 0.3.5)

   - `get-text`     — visible text of element
   - `get-url`      — current page URL
   - `get-title`    — current page title
   - `visible?`     — is element displayed?

   ## Return Values

   Mutating ops (`!` suffix) return the browser instance for chaining.
   Query ops (`element-count`, `get-text`, `get-url`, `get-title`, `visible?`)
   return their result directly.")

(defprotocol IBrowser
  "Protocol for browser operations.

   Implementations must handle resolved locators (maps with `:q` key).
   All mutating operations should return `this` for chaining."

  (open-to! [this url]
    "Navigate browser to the given URL. Returns this.")

  (click! [this locator]
    "Click the element matching locator. Returns this.")

  (doubleclick! [this locator]
    "Double-click the element matching locator. Returns this.")

  (rightclick! [this locator]
    "Right-click the element matching locator. Returns this.")

  (move-to! [this locator]
    "Move mouse to the element matching locator. Returns this.")

  (drag-to! [this from-locator to-locator]
    "Drag from one element to another. Returns this.")

  (fill! [this locator text]
    "Fill text into the input element matching locator. Returns this.")

  (element-count [this locator]
    "Count elements matching locator. Returns a long.

     Named `element-count` to avoid shadowing `clojure.core/count`.
     Stepdefs may alias this as needed.")

  (get-text [this locator]
    "Return visible text content of element matching locator.
     Use `{:tag :body}` locator for full page visible text.")

  (get-url [this]
    "Return the current page URL as a string.")

  (get-title [this]
    "Return the current page title as a string.")

  (visible? [this locator]
    "Return true if element matching locator is displayed.
     Returns false if element does not exist or is not displayed."))
