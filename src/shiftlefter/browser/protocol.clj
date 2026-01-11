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
   - `count`        — count matching elements

   ## Return Values

   Mutating ops (`!` suffix) return the browser instance for chaining.
   Query ops (`count`) return their result directly.")

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
     Stepdefs may alias this as needed."))
