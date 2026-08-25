# The Authored Grammar

In a ShiftLefter step, punctuation is meaning. Every marker — quotes, braces,
slashes, a capital letter — tells the framework (and the next person reading
the scenario) exactly what a token is, before anything runs. There is one rule,
it holds in every slot, and it exists so that nobody ever has to guess:

- `'…'` (single quotes) — **content, literal, always.** The characters between
  the quotes, exactly as written, no matter what they look like.
- `Capitalized.dotted` (unquoted) — **structure.** A name from your glossary:
  an intent, an element, a location, a key. It must resolve at planning time;
  an unknown name is a plan error with a did-you-mean.
- `{name}` — **content in motion.** A value from the scenario data plane,
  captured earlier (or provided by a hook) and consumed here.
- `/…/` — **a content pattern.** A regex to match against observed text;
  `(?<name>…)` named groups inside it produce `{name}` bindings.
- Bare lowercase words — the step language itself (`fills`, `with`,
  `should see`).
- `:alice` / `:user/alice` — the subject, the actor whose session runs the
  step. `[:web]` at the head of a step — an interface tag naming the config
  instance the step executes on (its `:type` picks the vocabulary).

That's the whole grammar. A quoted token never resolves to anything; an
unquoted capitalized token always must. You can read any step cold and know
which parts are your app's vocabulary and which parts are this scenario's data.

## Two layers: structure and content

Everything stable about the system under test lives in the **structure layer**:
subjects, interfaces, intents and their elements, named locations, verbs, key
names. Structure is glossary-owned and closed-world — every structural token is
checked when the suite loads, and structure changes when your app changes
shape.

Everything that varies scenario-to-scenario lives in the **content layer**:
values you type in, text you expect to see, values captured at runtime and
carried forward. Planning validates content's *plumbing* — every consumed
`{name}` has a producer, every pattern compiles, every literal is unambiguously
literal — but never its *truth*. Whether the page really says "Welcome" is the
run's job.

## Slot by slot

### Element slots — structure only

`fills Login.code with '123456'` — the element is an unquoted glossary
reference. `Login.cdoe` is a plan error with a suggestion. A quoted
`'Login.code'` is not admitted here: element slots take vocabulary, not
literals, so the step doesn't match — and the undefined-step diagnostic
says why: if the unquoted name resolves in your glossary, "Did you mean
Login.code (unquoted)?", otherwise that the slot takes glossary refs, not
literals.

### Value slots — content only

`fills Login.code with '123456'` or `fills Login.code with {code}` — a quoted
literal or a binding token, nothing else. Some consequences worth knowing:

- `'{code}'` is the six characters `{code}` — quoted is literal, always.
- `'say "hi"'` is fine; a double-quote inside a single-quoted literal is an
  ordinary character.
- `'Don''t have an account?'` — a literal `'` inside a literal is written
  doubled, SQL-style: `''` inside a nonempty literal is one quote character.
  The doubled pair must be adjacent, which is what keeps steps with several
  quoted slots unambiguous. `''''` is the one-character literal `'`. There
  are no backslash escapes — `''` is the whole escape grammar.
- `"x"` (double quotes) is **reserved** — it is not a quote form in this
  grammar, and the step won't match. The diagnostic teaches the rule and,
  when swapping the quotes would produce a real step, quotes the corrected
  text.
- `'x` (unbalanced quote) won't match either; the diagnostic says the
  literal may be unclosed, with the closed form when that fixes the step.
- `''` (empty literal) is not admitted — "put nothing in the field" wants a
  verb (`clears`), not an empty value. The diagnostic points at the verb,
  naming your element when it can: "to empty a field: clears Login.code".
  (Only the *bare* `''` is refused; inside a nonempty literal, `''` is the
  doubling escape above.)
- `with Welcome` (unquoted ref-shape in a value slot) matches nothing — there
  is deliberately no named-value registry.
- Binding names are born as Java regex group names: a letter, then letters and
  digits. `{order-number}` is impossible (no hyphens); `{orderNumber}` is the
  convention.

### Location slots — and why `exactly` is different

`opens the browser to Login` (a located intent), `to '/login'` (a literal),
`to {resetLink}` (a captured URL — the magic-link flow). Location slots accept
both layers, and the two location assertions split on purpose.

**Relative literals resolve against `:base-url`.** A schemeless literal —
hand-typed or captured into a `{token}` — resolves per RFC 3986 relative
resolution against the interface's `:config :base-url`: `'/login'` is
root-relative, `'Login'` becomes `…/Login`, and an absolute URL passes
through untouched. (One RFC consequence worth knowing: `'example.com'` is a
relative *path*, so it resolves to `…/example.com` — a host needs its
scheme.) This applies where a full URL is required — navigation and
`exactly` — and there the framework tells you when it can't: a relative
literal with no `:base-url` configured is a structured error naming both
exits (add `:base-url` to the interface config, or use an absolute URL).
Region assertions compare regions, so they never need a host — `should be
on '/secure'` works with or without a `:base-url`.

One guard rail on the boundary between the layers: quoted is literal, always —
so `to 'Login'` is the literal path `Login` (resolved like any relative
path), not the named location and not its declared `:location`. When the name
inside the quotes actually resolves as a named location in your glossary,
validation warns with "Did you mean Login (unquoted)?" (under the same
`:unknown-object` setting as the other location-ref checks).

**A named location is a region, not a URL.** When you declare that `Login`
lives at `/login`, you're naming *every URL that counts as being there* — with
tracking parameters, with a `?next=` redirect target, with a session token in
the query string. `should be on Login` asks one question: is the browser
somewhere in that region? That's almost always what a scenario means.

**`should be on exactly` compares one URL value, character by character** —
scheme, host, path, fragment, and the full query string. That's the right tool
when the URL itself is the observed behavior: the address a magic link opened,
a redirect target you captured a moment ago.

So why does `exactly` refuse a name like `Login`? Because a region doesn't
carry the details a full-URL comparison needs. If `exactly Login` were
admitted, the framework would have to fill in the missing parts — and every
fill-in is a hidden assertion you never wrote: *no query string*, *no
fragment*, *this exact host*. A test that asserts things its author never said
is worse than a test that fails loudly. So `exactly` takes only values — a
quoted URL or a `{captured}` one. A relative value resolves against
`:base-url` first (so `exactly '/login?a=1'` is expressible without
hardcoding a host — the base fills in scheme and host, which *you* declared,
not the framework guessed); with no `:base-url`, a non-full-URL value fails
structurally, naming both exits and pointing at the region assertion you
probably wanted.

### Text slots — content only

`should see the title 'Welcome'` — free text is content, so the slot takes a
quoted literal or a `{binding}` token. `'Dashboard.home'` in a text slot is
the literal string `Dashboard.home`, even though it looks like a reference —
quoted is literal, always. An unquoted bare word here is reserved (a future
release may add named text); today it matches nothing.

### Key slots — structure, from a closed set

`presses Enter` — key names are W3C `KeyboardEvent.key` values, the browser
interop standard, validated at planning time with did-you-mean. The full set:

`Enter` `Tab` `Escape` `Backspace` `Delete` `Insert` `Home` `End` `PageUp`
`PageDown` `ArrowUp` `ArrowDown` `ArrowLeft` `ArrowRight` `F1`–`F12` `Shift`
`Control` `Alt` `Meta` `Pause` `Clear` `Help` `Cancel` `Space`

One deliberate deviation, and only one: `Space`. The W3C key *value* for the
spacebar is the literal character `" "`, which has no name — and a quoted
invisible whitespace character would be the least legible token this grammar
could produce. So the spacebar answers to `Space`.

- `presses 'g'` — a quoted single character presses that character key (the
  keyboard-shortcut case, unreachable through `fills`).
- `presses ''''` — the doubling rule applies in every quoted slot, so this
  presses the apostrophe character key.
- `presses 'abc'` — a plan error: pressing is one key; entering text is
  `fills`.
- Chords (`Cmd+A`) and key sequences are not yet part of the grammar.

### Matcher slots — patterns, and who binds

Three steps share the `/…/` matcher slot, and they divide cleanly by what
happens to named groups:

- `captures Order.summary matching /Order (?<orderNumber>[A-Z0-9-]+)/` —
  assert **and bind**: the step fails unless the element's text matches, and
  every named group becomes a `{binding}`. A `captures` with *no* named groups
  is a plan error — a capture that binds nothing has lost the verb's point —
  and the error points you at the assertion below.
- `should see Order.total matching /\d+\.\d{2}/` — **assert only**: format
  assertions (order-number shapes, prices, dates) without inventing a
  throwaway binding. Unnamed groups are the expected shape here. A *named*
  group in this frame is a plan error pointing back at `captures`: named
  groups produce bindings everywhere, and a frame that binds nothing can't
  quietly ignore one.
- `receives an SMS … matching /code (\d+)/` — the polling assertions accept
  either shape; all-unnamed just earns a notice, since `/(\d+)/` plausibly
  meant `/(?<code>\d+)/`.

Inside any matcher, an embedded `{binding}` token interpolates as the *escaped
literal text* of its value — never as regex syntax. A malformed pattern is a
structured plan error.

## When a step doesn't match

An undefined step isn't reported bare. The diagnostic classifies why the text
missed and teaches the rule it broke — at most one hint per step:

- double quotes in a slot → literals use single quotes; double quotes are
  reserved
- an unbalanced single quote → the literal may be unclosed (and when doubling
  an interior quote would produce a real step — `'friends' page'` →
  `'friends'' page'` — the hint offers exactly that)
- an empty literal (`''`) → emptying a field wants the `clears` verb, and
  the hint names your element when it can
- an undoubled apostrophe inside a literal (`'Don't stop'`) → a literal
  quote is written doubled (`''`), with the doubled step quoted when that
  repair matches
- quoted ref-shaped content → quoted is literal, always; with a glossary
  check for "Did you mean X (unquoted)?"
- bare words where a value slot expects a value (`fills X with the SMS
  code`) → value slots take a quoted literal (`'...'`) or a binding
  (`{Name}`); offered only when quoting the tail produces a step that
  actually matches
- an undefined step with no structured tokens at all — no `'...'` literal,
  no `{...}` locator, no `Object.ref` — ending in sentence punctuation →
  it probably wasn't meant as a step. Description prose ends at the first
  step keyword (`Given/When/Then/And/But`, or `*`), so prose that opens
  with one lexes as a real step; move prose above the first step or
  rephrase it. Teaching-only — no repair makes prose match, so there is
  never a did-you-mean

When repairing the text produces a step that actually matches, the hint quotes
the exact corrected step. Apostrophes inside *unquoted* words (`the user's
name` outside any literal) never trigger any of this, and a `"` inside a
single-quoted literal stays an ordinary character.

For tooling, the EDN report carries the same hint machine-readably on each
undefined issue: a `:hint` map of `{:type :grammar/… :suggestion "…"}` plus
`:repaired-text` (present only when a repair produced a real match) and
`:did-you-mean`. The envelope is stable; the set of `:type` values grows as
new hint families land — dispatch on the types you know and fall back to
showing `:suggestion`.

## Reserved, on purpose

Some forms are held back deliberately, so today's no-match is tomorrow's
compatible extension:

| Form | Status |
|------|--------|
| `"double quoted"` anywhere | Reserved — not a quote form; won't match, and the hint says so |
| Unquoted bare word in a text slot | Reserved for future named text — won't match; the hint offers the quoted tail when that would |
| Unquoted name on `should be on exactly` | Reserved — see the region explanation above |
| `+` in a key slot (chords, sequences) | Parked pending a real need |
| `''` empty literal | Not admitted bare — the hint points at `clears`. Inside a nonempty literal, `''` is one literal quote (SQL-style doubling) |

## What `--dry-run` promises

Planning (and `sl run --dry-run`) guarantees: every structural name resolves,
every step is a known frame, every consumed binding has a producer, every
matcher compiles, every literal is unambiguously literal. It does not promise
any assertion passes. **Dry-run truth is structural truth** — the run supplies
the rest.
