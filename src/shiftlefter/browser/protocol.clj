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

   ## Navigation (added 0.3.6)

   - `go-back!`     — navigate backward in history
   - `go-forward!`  — navigate forward in history
   - `refresh!`     — reload current page

   ## Scrolling (added 0.3.6)

   - `scroll-to!`         — scroll element into view
   - `scroll-to-position!` — scroll to :top or :bottom

   ## Form Operations (added 0.3.6)

   - `clear!`       — clear input field
   - `select!`      — select dropdown option by visible text
   - `press-key!`   — send key press (string produced by etaoin.keys)

   ## Element Queries (added 0.3.6)

   - `get-attribute` — get element attribute value
   - `get-value`     — get input element value
   - `enabled?`      — is element enabled?

   ## Alerts (added 0.3.6)

   - `accept-alert!`  — accept (OK) alert dialog
   - `dismiss-alert!` — dismiss (Cancel) alert dialog
   - `get-alert-text` — get alert dialog text

   ## Window Management (added 0.3.6)

   - `maximize-window!`      — maximize browser window
   - `set-window-size!`      — set window dimensions
   - `switch-to-next-window!` — switch to next window/tab

   ## Frames (added 0.3.6)

   - `switch-to-frame!`      — switch to iframe
   - `switch-to-main-frame!` — switch back to main page

   ## Return Values

   Mutating ops (`!` suffix) return the browser instance for chaining.
   Query ops (`element-count`, `get-text`, `get-url`, `get-title`, `visible?`,
   `get-attribute`, `get-value`, `enabled?`, `get-alert-text`)
   return their result directly.")

(defprotocol IBrowser
  "Protocol for browser operations.

   Implementations must handle resolved locators (maps with `:q` key).
   All mutating operations should return `this` for chaining."

  ;; --- Kernel Operations (0.2.x) ---

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

  ;; --- Query Operations (0.3.5) ---

  (get-text [this locator]
    "Return visible text content of element matching locator.
     Use `{:tag :body}` locator for full page visible text.")

  (get-url [this]
    "Return the current page URL as a string.")

  (get-title [this]
    "Return the current page title as a string.")

  (visible? [this locator]
    "Return true if element matching locator is displayed.
     Returns false if element does not exist or is not displayed.")

  ;; --- Navigation (0.3.6) ---

  (go-back! [this]
    "Navigate backward in browser history. Returns this.")

  (go-forward! [this]
    "Navigate forward in browser history. Returns this.")

  (refresh! [this]
    "Reload the current page. Returns this.")

  ;; --- Scrolling (0.3.6) ---

  (scroll-to! [this locator]
    "Scroll the element matching locator into view. Returns this.")

  (scroll-to-position! [this position]
    "Scroll to named position. `position` is :top or :bottom. Returns this.")

  ;; --- Form Operations (0.3.6) ---

  (clear! [this locator]
    "Clear the input element matching locator. Returns this.")

  (select! [this locator text]
    "Select option by visible text from dropdown matching locator. Returns this.")

  (press-key! [this key-str]
    "Send a key press to the active element. `key-str` is a string produced
     by etaoin.keys (single key or chord). Returns this.")

  ;; --- Element Queries (0.3.6) ---

  (get-attribute [this locator attribute]
    "Return value of `attribute` on element matching locator. Returns string or nil.")

  (get-value [this locator]
    "Return value of input element matching locator. Returns string or nil.")

  (enabled? [this locator]
    "Return true if element matching locator is enabled (not disabled).")

  ;; --- Alerts (0.3.6) ---

  (accept-alert! [this]
    "Accept (OK) the current alert dialog. Returns this.")

  (dismiss-alert! [this]
    "Dismiss (Cancel) the current alert dialog. Returns this.")

  (get-alert-text [this]
    "Return the text of the current alert dialog.")

  ;; --- Window Management (0.3.6) ---

  (maximize-window! [this]
    "Maximize the current browser window. Returns this.")

  (set-window-size! [this width height]
    "Set the browser window size in pixels. Returns this.")

  (switch-to-next-window! [this]
    "Switch to the next browser window/tab. Returns this.")

  ;; --- Frames (0.3.6) ---

  (switch-to-frame! [this locator]
    "Switch context to the iframe matching locator. Returns this.")

  (switch-to-main-frame! [this]
    "Switch context back to the main page (top-level frame). Returns this."))
