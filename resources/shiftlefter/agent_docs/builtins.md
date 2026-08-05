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
frame is one accepted argument shape), then the step grammar (regex
source) and the verb/frame each pattern maps to.

FRAMES ARE SCHEMA, NOT STEPS. A frame like `S navigates to O` names an
argument shape — writing it in a feature file will NOT bind. The only
step text this reference quotes is the `bind with:` example under each
frame; every one of those is machine-verified to bind. Copy those.

## Interface `:sms`

### Adapters

- `:sms-mock` — provides `:shiftlefter.sms.protocol/ISMS`, `:shiftlefter.sms.protocol/ISMSInbound`
- `:sms-twilio` — provides `:shiftlefter.sms.protocol/ISMS`

### Verbs

#### `:receive` — Poll for an inbound SMS matching a body pattern

- frame `:default` — schema (not a step): S receives an SMS to TO-PHONE matching MATCH — args `:to-phone` (literal or `{binding}`), `:match` (regex; `(?<name>...)` produces `{name}` bindings) — implicit object `:sms-message`
  - bind with: `:alice receives an SMS to '+15550100' matching /your code is (?<code>\d+)/`
- frame `:since-iso` — schema (not a step): S receives an SMS to TO-PHONE since SINCE-ISO matching MATCH — args `:to-phone` (literal or `{binding}`), `:since-iso` (literal or `{binding}`), `:match` (regex; `(?<name>...)` produces `{name}` bindings) — implicit object `:sms-message`
  - bind with: `:alice receives an SMS to '+15550100' since '2026-01-01T00:00:00Z' matching /code (?<code>\d+)/`
- frame `:within-last` — schema (not a step): S receives an SMS to TO-PHONE within the last DURATION matching MATCH — args `:to-phone` (literal or `{binding}`), `:duration`, `:match` (regex; `(?<name>...)` produces `{name}` bindings) — implicit object `:sms-message`
  - bind with: `:alice receives an SMS to '+15550100' within the last 30 seconds matching /code (?<code>\d+)/`

#### `:see` — Assert observable state about SMS messages

- frame `:count` — schema (not a step): S sees COUNT messages to TO-PHONE — args `:count`, `:to-phone` (literal or `{binding}`) — implicit object `:sms-messages`
  - bind with: `:alice should see 2 messages to '+15550100'`

#### `:send` — Send an SMS to a phone number

- frame `:to` — schema (not a step): S sends an SMS to TO-PHONE saying BODY — args `:to-phone` (literal or `{binding}`), `:body` (literal or `{binding}`) — implicit object `:sms-message`
  - bind with: `:alice sends an SMS to '+15550100' saying 'On my way'`

#### `:send-media` — Send an MMS (text + media) to a phone number

- frame `:to` — schema (not a step): S sends an MMS to TO-PHONE with caption BODY and media MEDIA-URL — args `:to-phone` (literal or `{binding}`), `:body` (literal or `{binding}`), `:media-url` (literal or `{binding}`) — implicit object `:mms-message`
  - bind with: `:alice sends an MMS to '+15550100' with caption 'Look' and media 'https://example.com/cat.png'`

### Step grammar (regex source — not pasteable step text)

Reference grammar for each built-in step. To author a step, copy a
`bind with:` example from the verb entries above — never this regex
source.

- `:([\w./-]+) receives an SMS to ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) matching /(.+)/` → `:receive`/`:default` `[:sms]` — requires `:shiftlefter.sms.protocol/ISMS`
- `:([\w./-]+) receives an SMS to ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) since ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) matching /(.+)/` → `:receive`/`:since-iso` `[:sms]` — requires `:shiftlefter.sms.protocol/ISMS`
- `:([\w./-]+) receives an SMS to ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) within the last (\d+ (?:seconds?|minutes?|hours?)) matching /(.+)/` → `:receive`/`:within-last` `[:sms]` — requires `:shiftlefter.sms.protocol/ISMS`
- `:([\w./-]+) sends an MMS to ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) with caption ('(?:[^']|'')*'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) and media ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:send-media`/`:to` `[:sms]` — requires `:shiftlefter.sms.protocol/ISMS`
- `:([\w./-]+) sends an SMS to ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) saying ('(?:[^']|'')*'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:send`/`:to` `[:sms]` — requires `:shiftlefter.sms.protocol/ISMS`
- `:([\w./-]+) should see (\d+) messages to ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:see`/`:count` `[:sms]` — requires `:shiftlefter.sms.protocol/ISMS`

## Interface `:web`

### Adapters

- `:etaoin` — provides `:shiftlefter.browser.protocol/IBrowser`
- `:playwright` — provides `:shiftlefter.browser.protocol/IBrowser` (not bundled — add com.microsoft.playwright/playwright {:mvn/version "1.50.0"} to your deps.edn, or drop the resolved jars in sl/lib/; see docs/INSTALL-TIERS.md)

### Verbs

#### `:accept-alert` — Accept (OK) an alert dialog

- frame `:default` — schema (not a step): S accepts the alert — implicit object `:alert`
  - bind with: `:alice accepts the alert`

#### `:be` — Assert a property or state of the subject

- frame `:at` — schema (not a step): S is on O — O: intent ref (bare PascalCase name), literal URL, or `{binding}`
  - bind with: `:alice should be on '/dashboard'`
- frame `:at-exactly` — schema (not a step): S is on exactly O — O: literal URL
  - bind with: `:alice should be on exactly '/dashboard?tab=1'`

#### `:capture` — Observe an element's text and bind named-group matches

- frame `:default` — schema (not a step): S captures O matching MATCH — args `:match` (regex; `(?<name>...)` produces `{name}` bindings)
  - bind with: `:alice captures {:id "total"} matching /(?<amount>[0-9.]+)/`

#### `:clear` — Clear input field contents

- frame `:default` — schema (not a step): S clears O
  - bind with: `:alice clears {:id "search"}`

#### `:click` — Click on an element

- frame `:default` — schema (not a step): S clicks O
  - bind with: `:alice clicks {:id "login"}`

#### `:dismiss-alert` — Dismiss (Cancel) an alert dialog

- frame `:default` — schema (not a step): S dismisses the alert — implicit object `:alert`
  - bind with: `:alice dismisses the alert`

#### `:doubleclick` — Double-click on an element

- frame `:default` — schema (not a step): S double-clicks O
  - bind with: `:alice double-clicks {:id "row"}`

#### `:drag` — Drag an element to a target

- frame `:to` — schema (not a step): S drags O to TARGET — args `:target`
  - bind with: `:alice drags {:id "card"} to {:id "done-column"}`

#### `:fill` — Enter text into an input field

- frame `:with` — schema (not a step): S fills O with VALUE — args `:value` (literal or `{binding}`)
  - bind with: `:alice fills {:id "email"} with 'alice@example.com'`

#### `:hover` — Hover the mouse over an element (delegates to the move-to kernel op)

- frame `:default` — schema (not a step): S hovers over O
  - bind with: `:alice hovers over {:id "menu"}`

#### `:maximize` — Maximize the browser window

- frame `:default` — schema (not a step): S maximizes the window — implicit object `:window`
  - bind with: `:alice maximizes the window`

#### `:move` — Move mouse to an element

- frame `:default` — schema (not a step): S moves to O
  - bind with: `:alice moves to {:id "menu"}`

#### `:navigate` — Navigate to a URL; the URL is the object

- frame `:to` — schema (not a step): S navigates to O — O: intent ref (bare PascalCase name), literal URL, or `{binding}`
  - bind with: `:alice opens the browser to '/login'`

#### `:navigate-back` — Navigate backward in browser history

- frame `:default` — schema (not a step): S goes back — implicit object `:browser-history`
  - bind with: `:alice goes back`

#### `:navigate-forward` — Navigate forward in browser history

- frame `:default` — schema (not a step): S goes forward — implicit object `:browser-history`
  - bind with: `:alice goes forward`

#### `:not-see` — Observe absence of an element

- frame `:invisible` — schema (not a step): S does not see O
  - bind with: `:alice should not see {:id "error"}`

#### `:press` — Press a single keyboard key; the key is the object

- frame `:default` — schema (not a step): S presses O — O: key name (closed W3C KeyboardEvent.key set) or quoted single character
  - bind with: `:alice presses Enter`

#### `:refresh` — Reload the current page

- frame `:default` — schema (not a step): S refreshes the page — implicit object `:page`
  - bind with: `:alice refreshes the page`

#### `:resize` — Set browser window dimensions

- frame `:dimensions` — schema (not a step): S resizes the window to WIDTH by HEIGHT — args `:width`, `:height` — implicit object `:window`
  - bind with: `:alice resizes the window to 1280x800`

#### `:rightclick` — Right-click (context menu) on an element

- frame `:default` — schema (not a step): S right-clicks O
  - bind with: `:alice right-clicks {:id "file"}`

#### `:scroll` — Scroll the page to an element or to a named position

- frame `:to-element` — schema (not a step): S scrolls to O
  - bind with: `:alice scrolls to {:id "footer"}`
- frame `:to-position` — schema (not a step): S scrolls to POSITION — args `:position` — implicit object `:page`
  - bind with: `:alice scrolls to the bottom`

#### `:see` — Observe an element or page property

- frame `:alert` — schema (not a step): S sees an alert — implicit object `:alert`
  - bind with: `:alice should see an alert`
- frame `:alert-with-text` — schema (not a step): S sees an alert with TEXT — args `:text` (literal or `{binding}`) — implicit object `:alert`
  - bind with: `:alice should see an alert with 'Are you sure?'`
- frame `:attribute` — schema (not a step): S sees O with attribute ATTRIBUTE equal to VALUE — args `:attribute` (literal or `{binding}`), `:value` (literal or `{binding}`)
  - bind with: `:alice should see {:id "link"} with attribute 'href' equal to '/home'`
- frame `:count` — schema (not a step): S sees COUNT O — args `:count`
  - bind with: `:alice should see 3 {:css ".row"} elements`
- frame `:disabled` — schema (not a step): S sees O disabled
  - bind with: `:alice should see {:id "submit"} disabled`
- frame `:enabled` — schema (not a step): S sees O enabled
  - bind with: `:alice should see {:id "submit"} enabled`
- frame `:matching` — schema (not a step): S sees O matching MATCH — args `:match` (regex; `(?<name>...)` produces `{name}` bindings)
  - bind with: `:alice should see {:id "total"} matching /\d+ items/`
- frame `:on-page` — schema (not a step): S sees O on the page
  - bind with: `:alice should see 'Welcome'`
- frame `:text` — schema (not a step): S sees O with text TEXT — args `:text` (literal or `{binding}`)
  - bind with: `:alice should see {:id "banner"} with text 'Success'`
- frame `:title` — schema (not a step): S sees the title O — O: text (quoted literal or `{binding}`)
  - bind with: `:alice should see the title 'Home'`
- frame `:value` — schema (not a step): S sees O with value VALUE — args `:value` (literal or `{binding}`)
  - bind with: `:alice should see {:id "qty"} with value '3'`
- frame `:visible` — schema (not a step): S sees O
  - bind with: `:alice should see {:id "profile"}`

#### `:select` — Select an option from a dropdown

- frame `:from` — schema (not a step): S selects VALUE from O — args `:value` (literal or `{binding}`)
  - bind with: `:alice selects 'Blue' from {:id "color"}`

#### `:switch-frame` — Switch into an iframe or back to the main frame

- frame `:into` — schema (not a step): S switches to frame O
  - bind with: `:alice switches to frame {:id "payment"}`
- frame `:main` — schema (not a step): S switches to the main frame — implicit object `:main-frame`
  - bind with: `:alice switches to the main frame`

#### `:switch-window` — Switch to another browser window or tab

- frame `:default` — schema (not a step): S switches to the next window — implicit object `:window`
  - bind with: `:alice switches to the next window`

#### `:wait` — Pause until a condition is met or a duration elapses

- frame `:duration` — schema (not a step): S waits DURATION seconds — args `:duration` — implicit object `:time`
  - bind with: `:alice waits 2 seconds`
- frame `:for-count` — schema (not a step): S waits for COUNT O — args `:count`
  - bind with: `:alice waits for 3 {:css ".result"}`
- frame `:for-element` — schema (not a step): S waits for O
  - bind with: `:alice waits for {:id "spinner"}`
- frame `:for-text` — schema (not a step): S waits for O to show TEXT — args `:text` (literal or `{binding}`)
  - bind with: `:alice waits for {:id "status"} to show 'Done'`

### Step grammar (regex source — not pasteable step text)

Reference grammar for each built-in step. To author a step, copy a
`bind with:` example from the verb entries above — never this regex
source.

- `:([\w./-]+) accepts the alert` → `:accept-alert`/`:default` `[:web]`
- `:([\w./-]+) captures ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) matching /(.+)/` → `:capture`/`:default` `[:web]`
- `:([\w./-]+) clears ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:clear`/`:default` `[:web]`
- `:([\w./-]+) clicks ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:click`/`:default` `[:web]`
- `:([\w./-]+) dismisses the alert` → `:dismiss-alert`/`:default` `[:web]`
- `:([\w./-]+) double-clicks ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:doubleclick`/`:default` `[:web]`
- `:([\w./-]+) drags ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) to ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:drag`/`:to` `[:web]`
- `:([\w./-]+) fills ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) with ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:fill`/`:with` `[:web]`
- `:([\w./-]+) goes back` → `:navigate-back`/`:default` `[:web]`
- `:([\w./-]+) goes forward` → `:navigate-forward`/`:default` `[:web]`
- `:([\w./-]+) hovers over ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:hover`/`:default` `[:web]`
- `:([\w./-]+) maximizes the window` → `:maximize`/`:default` `[:web]`
- `:([\w./-]+) moves to ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:move`/`:default` `[:web]`
- `:([\w./-]+) opens the browser to ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}|[A-Z][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_-]+)*)` → `:navigate`/`:to` `[:web]`
- `:([\w./-]+) presses ([A-Z][A-Za-z0-9]*|'(?:[^']|'')+')` → `:press`/`:default` `[:web]`
- `:([\w./-]+) refreshes the page` → `:refresh`/`:default` `[:web]`
- `:([\w./-]+) resizes the window to (\d+)x(\d+)` → `:resize`/`:dimensions` `[:web]`
- `:([\w./-]+) right-clicks ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:rightclick`/`:default` `[:web]`
- `:([\w./-]+) scrolls to ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:scroll`/`:to-element` `[:web]`
- `:([\w./-]+) scrolls to the (top|bottom)` → `:scroll`/`:to-position` `[:web]`
- `:([\w./-]+) selects ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) from ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:select`/`:from` `[:web]`
- `:([\w./-]+) should be on ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}|[A-Z][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_-]+)*)` → `:be`/`:at` `[:web]`
- `:([\w./-]+) should be on exactly ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:be`/`:at-exactly` `[:web]`
- `:([\w./-]+) should not see ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:not-see`/`:invisible` `[:web]`
- `:([\w./-]+) should see '((?:[^']|'')+)'` → `:see`/`:on-page` `[:web]`
- `:([\w./-]+) should see ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:see`/`:visible` `[:web]`
- `:([\w./-]+) should see ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) disabled` → `:see`/`:disabled` `[:web]`
- `:([\w./-]+) should see ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) enabled` → `:see`/`:enabled` `[:web]`
- `:([\w./-]+) should see ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) matching /(.+)/` → `:see`/`:matching` `[:web]`
- `:([\w./-]+) should see ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) with attribute ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}) equal to ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:see`/`:attribute` `[:web]`
- `:([\w./-]+) should see ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) with text ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:see`/`:text` `[:web]`
- `:([\w./-]+) should see ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) with value ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:see`/`:value` `[:web]`
- `:([\w./-]+) should see (\d+) ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) elements` → `:see`/`:count` `[:web]`
- `:([\w./-]+) should see an alert` → `:see`/`:alert` `[:web]`
- `:([\w./-]+) should see an alert with ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:see`/`:alert-with-text` `[:web]`
- `:([\w./-]+) should see the title ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:see`/`:title` `[:web]`
- `:([\w./-]+) switches to frame ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:switch-frame`/`:into` `[:web]`
- `:([\w./-]+) switches to the main frame` → `:switch-frame`/`:main` `[:web]`
- `:([\w./-]+) switches to the next window` → `:switch-window`/`:default` `[:web]`
- `:([\w./-]+) waits (\d+(?:\.\d+)?) seconds?` → `:wait`/`:duration` `[:web]`
- `:([\w./-]+) waits for ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:wait`/`:for-element` `[:web]`
- `:([\w./-]+) waits for ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\}) to show ('(?:[^']|'')+'|\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\})` → `:wait`/`:for-text` `[:web]`
- `:([\w./-]+) waits for (\d+) ([A-Z][A-Za-z0-9_-]*(?:\.[a-z][a-z0-9_-]*(?:\[-?\d+\]|\[\*\])?)+|\{[^}]+\})` → `:wait`/`:for-count` `[:web]`
