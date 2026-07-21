# ShiftLefter Built-in Vocabulary

Generated reference for the framework's built-in verbs, frames, step
patterns, and adapters. A consumer project has only the jar, so this
vocabulary cannot be discovered by grepping the project — it is supplied
here as static, packaged reference.

This file is GENERATED from the framework's default glossaries
(`verbs-web.edn`, `verbs-sms.edn`), the step registry, and the adapter
registry. Do not edit it by hand — edit the source and regenerate with
`clojure -T:build gen-builtins`.

Sections are grouped by interface for targeted subreads. Within each
interface: the adapters that back it, the verbs and their frames (each
frame is one accepted argument shape), then the concrete built-in step
patterns and the verb/frame they map to.

## Interface `:sms`

### Adapters

- `:sms-mock` — provides `:shiftlefter.sms.protocol/ISMS`, `:shiftlefter.sms.protocol/ISMSInbound`
- `:sms-twilio` — provides `:shiftlefter.sms.protocol/ISMS`

### Verbs

#### `:receive` — Poll for an inbound SMS matching a body pattern

- frame `:default`: `S receives an SMS to TO-PHONE matching MATCH` — args `:to-phone` (literal or `{binding}`), `:match` (regex; `(?<name>...)` produces `{name}` bindings) — implicit object `:sms-message`
- frame `:since-iso`: `S receives an SMS to TO-PHONE since SINCE-ISO matching MATCH` — args `:to-phone` (literal or `{binding}`), `:since-iso` (literal or `{binding}`), `:match` (regex; `(?<name>...)` produces `{name}` bindings) — implicit object `:sms-message`
- frame `:within-last`: `S receives an SMS to TO-PHONE within the last DURATION matching MATCH` — args `:to-phone` (literal or `{binding}`), `:duration`, `:match` (regex; `(?<name>...)` produces `{name}` bindings) — implicit object `:sms-message`

#### `:see` — Assert observable state about SMS messages

- frame `:count`: `S sees COUNT messages to TO-PHONE` — args `:count`, `:to-phone` (literal or `{binding}`) — implicit object `:sms-messages`

#### `:send` — Send an SMS to a phone number

- frame `:to`: `S sends an SMS to TO-PHONE saying BODY` — args `:to-phone` (literal or `{binding}`), `:body` (literal or `{binding}`) — implicit object `:sms-message`

#### `:send-media` — Send an MMS (text + media) to a phone number

- frame `:to`: `S sends an MMS to TO-PHONE with caption BODY and media MEDIA-URL` — args `:to-phone` (literal or `{binding}`), `:body` (literal or `{binding}`), `:media-url` (literal or `{binding}`) — implicit object `:mms-message`

### Built-in step patterns

- `:([\w./-]+) receives an SMS to ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) matching /(.+)/` → `:receive`/`:default` `[:sms]` — requires `:shiftlefter.sms.protocol/ISMS`
- `:([\w./-]+) receives an SMS to ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) since ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) matching /(.+)/` → `:receive`/`:since-iso` `[:sms]` — requires `:shiftlefter.sms.protocol/ISMS`
- `:([\w./-]+) receives an SMS to ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) within the last (\d+ (?:seconds?|minutes?|hours?)) matching /(.+)/` → `:receive`/`:within-last` `[:sms]` — requires `:shiftlefter.sms.protocol/ISMS`
- `:([\w./-]+) sends an MMS to ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) with caption ('[^']*'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) and media ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:send-media`/`:to` `[:sms]` — requires `:shiftlefter.sms.protocol/ISMS`
- `:([\w./-]+) sends an SMS to ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) saying ('[^']*'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:send`/`:to` `[:sms]` — requires `:shiftlefter.sms.protocol/ISMS`
- `:([\w./-]+) should see (\d+) messages to ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:see`/`:count` `[:sms]` — requires `:shiftlefter.sms.protocol/ISMS`

## Interface `:web`

### Adapters

- `:etaoin` — provides `:shiftlefter.browser.protocol/IBrowser`
- `:playwright` — provides `:shiftlefter.browser.protocol/IBrowser`

### Verbs

#### `:accept-alert` — Accept (OK) an alert dialog

- frame `:default`: `S accepts the alert` — implicit object `:alert`

#### `:be` — Assert a property or state of the subject

- frame `:at`: `S is on O` — O: intent ref (bare PascalCase name), literal URL, or `{binding}`
- frame `:at-exactly`: `S is on exactly O` — O: literal URL

#### `:capture` — Observe an element's text and bind named-group matches

- frame `:default`: `S captures O matching MATCH` — args `:match` (regex; `(?<name>...)` produces `{name}` bindings)

#### `:clear` — Clear input field contents

- frame `:default`: `S clears O`

#### `:click` — Click on an element

- frame `:default`: `S clicks O`

#### `:dismiss-alert` — Dismiss (Cancel) an alert dialog

- frame `:default`: `S dismisses the alert` — implicit object `:alert`

#### `:doubleclick` — Double-click on an element

- frame `:default`: `S double-clicks O`

#### `:drag` — Drag an element to a target

- frame `:to`: `S drags O to TARGET` — args `:target`

#### `:fill` — Enter text into an input field

- frame `:with`: `S fills O with VALUE` — args `:value` (literal or `{binding}`)

#### `:hover` — Hover the mouse over an element (delegates to the move-to kernel op)

- frame `:default`: `S hovers over O`

#### `:maximize` — Maximize the browser window

- frame `:default`: `S maximizes the window` — implicit object `:window`

#### `:move` — Move mouse to an element

- frame `:default`: `S moves to O`

#### `:navigate` — Navigate to a URL; the URL is the object

- frame `:to`: `S navigates to O` — O: intent ref (bare PascalCase name), literal URL, or `{binding}`

#### `:navigate-back` — Navigate backward in browser history

- frame `:default`: `S goes back` — implicit object `:browser-history`

#### `:navigate-forward` — Navigate forward in browser history

- frame `:default`: `S goes forward` — implicit object `:browser-history`

#### `:not-see` — Observe absence of an element

- frame `:invisible`: `S does not see O`

#### `:press` — Press a keyboard key or chord; the key is the object

- frame `:default`: `S presses O`

#### `:refresh` — Reload the current page

- frame `:default`: `S refreshes the page` — implicit object `:page`

#### `:resize` — Set browser window dimensions

- frame `:dimensions`: `S resizes the window to WIDTH by HEIGHT` — args `:width`, `:height` — implicit object `:window`

#### `:rightclick` — Right-click (context menu) on an element

- frame `:default`: `S right-clicks O`

#### `:scroll` — Scroll the page to an element or to a named position

- frame `:to-element`: `S scrolls to O`
- frame `:to-position`: `S scrolls to POSITION` — args `:position` — implicit object `:page`

#### `:see` — Observe an element or page property

- frame `:alert`: `S sees an alert` — implicit object `:alert`
- frame `:alert-with-text`: `S sees an alert with TEXT` — args `:text` (literal or `{binding}`) — implicit object `:alert`
- frame `:attribute`: `S sees O with attribute ATTRIBUTE equal to VALUE` — args `:attribute` (literal or `{binding}`), `:value` (literal or `{binding}`)
- frame `:count`: `S sees COUNT O` — args `:count`
- frame `:disabled`: `S sees O disabled`
- frame `:enabled`: `S sees O enabled`
- frame `:on-page`: `S sees O on the page`
- frame `:text`: `S sees O with text TEXT` — args `:text` (literal or `{binding}`)
- frame `:title`: `S sees the title O`
- frame `:value`: `S sees O with value VALUE` — args `:value` (literal or `{binding}`)
- frame `:visible`: `S sees O`

#### `:select` — Select an option from a dropdown

- frame `:from`: `S selects VALUE from O` — args `:value` (literal or `{binding}`)

#### `:switch-frame` — Switch into an iframe or back to the main frame

- frame `:into`: `S switches to frame O`
- frame `:main`: `S switches to the main frame` — implicit object `:main-frame`

#### `:switch-window` — Switch to another browser window or tab

- frame `:default`: `S switches to the next window` — implicit object `:window`

#### `:wait` — Pause until a condition is met or a duration elapses

- frame `:duration`: `S waits DURATION seconds` — args `:duration` — implicit object `:time`
- frame `:for-count`: `S waits for COUNT O` — args `:count`
- frame `:for-element`: `S waits for O`
- frame `:for-text`: `S waits for O to show TEXT` — args `:text` (literal or `{binding}`)

### Built-in step patterns

- `:([\w./-]+) accepts the alert` → `:accept-alert`/`:default` `[:web]`
- `:([\w./-]+) captures ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) matching /(.+)/` → `:capture`/`:default` `[:web]`
- `:([\w./-]+) clears ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:clear`/`:default` `[:web]`
- `:([\w./-]+) clicks ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:click`/`:default` `[:web]`
- `:([\w./-]+) dismisses the alert` → `:dismiss-alert`/`:default` `[:web]`
- `:([\w./-]+) double-clicks ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:doubleclick`/`:default` `[:web]`
- `:([\w./-]+) drags ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) to ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:drag`/`:to` `[:web]`
- `:([\w./-]+) fills ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) with ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:fill`/`:with` `[:web]`
- `:([\w./-]+) goes back` → `:navigate-back`/`:default` `[:web]`
- `:([\w./-]+) goes forward` → `:navigate-forward`/`:default` `[:web]`
- `:([\w./-]+) hovers over ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:hover`/`:default` `[:web]`
- `:([\w./-]+) maximizes the window` → `:maximize`/`:default` `[:web]`
- `:([\w./-]+) moves to ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:move`/`:default` `[:web]`
- `:([\w./-]+) opens the browser to ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}|[A-Z][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_-]+)*)` → `:navigate`/`:to` `[:web]`
- `:([\w./-]+) presses (.+)` → `:press`/`:default` `[:web]`
- `:([\w./-]+) refreshes the page` → `:refresh`/`:default` `[:web]`
- `:([\w./-]+) resizes the window to (\d+)x(\d+)` → `:resize`/`:dimensions` `[:web]`
- `:([\w./-]+) right-clicks ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:rightclick`/`:default` `[:web]`
- `:([\w./-]+) scrolls to ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:scroll`/`:to-element` `[:web]`
- `:([\w./-]+) scrolls to the (top|bottom)` → `:scroll`/`:to-position` `[:web]`
- `:([\w./-]+) selects ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) from ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:select`/`:from` `[:web]`
- `:([\w./-]+) should be on ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}|[A-Z][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_-]+)*)` → `:be`/`:at` `[:web]`
- `:([\w./-]+) should be on exactly ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:be`/`:at-exactly` `[:web]`
- `:([\w./-]+) should not see ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:not-see`/`:invisible` `[:web]`
- `:([\w./-]+) should see '([^']+)'` → `:see`/`:on-page` `[:web]`
- `:([\w./-]+) should see ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:see`/`:visible` `[:web]`
- `:([\w./-]+) should see ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) disabled` → `:see`/`:disabled` `[:web]`
- `:([\w./-]+) should see ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) enabled` → `:see`/`:enabled` `[:web]`
- `:([\w./-]+) should see ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) with attribute ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) equal to ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:see`/`:attribute` `[:web]`
- `:([\w./-]+) should see ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) with text ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:see`/`:text` `[:web]`
- `:([\w./-]+) should see ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) with value ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:see`/`:value` `[:web]`
- `:([\w./-]+) should see (\d+) ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) elements` → `:see`/`:count` `[:web]`
- `:([\w./-]+) should see an alert` → `:see`/`:alert` `[:web]`
- `:([\w./-]+) should see an alert with ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:see`/`:alert-with-text` `[:web]`
- `:([\w./-]+) should see the title '([^']+)'` → `:see`/`:title` `[:web]`
- `:([\w./-]+) switches to frame ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:switch-frame`/`:into` `[:web]`
- `:([\w./-]+) switches to the main frame` → `:switch-frame`/`:main` `[:web]`
- `:([\w./-]+) switches to the next window` → `:switch-window`/`:default` `[:web]`
- `:([\w./-]+) waits (\d+(?:\.\d+)?) seconds?` → `:wait`/`:duration` `[:web]`
- `:([\w./-]+) waits for ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:wait`/`:for-element` `[:web]`
- `:([\w./-]+) waits for ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) to show ('[^']+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:wait`/`:for-text` `[:web]`
- `:([\w./-]+) waits for (\d+) ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:wait`/`:for-count` `[:web]`
