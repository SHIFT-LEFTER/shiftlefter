# ShiftLefter examples — the on-ramp

Eight self-contained example projects, ordered as a learning path. The arc:
you can start in **Vanilla mode** (no vocabulary, works like any Gherkin
runner) and be productive immediately — but the advertised path is
**Shifted mode**, where a project glossary validates every scenario at
planning time, before anything runs. `sl orient` always tells you which
mode a project is in (Shifted = `:svo` present in `sl/shiftlefter.edn`).

## Running the examples

Two kinds of run command, depending on the example:

- **`sl …`** — examples 01–03 use the installed CLI. The release zip puts
  `sl` on your PATH; **in a checkout of this repo, substitute `bin/sl`**
  (it runs the built jar — build once with `clojure -T:build uberjar`).
- **`clj -M:demo`** — examples 04–06 are mini-projects that pull the
  framework via `:local/root` and stand up their own fixture server. Run
  from the example's directory so its `sl/` project resolves.

Browser examples need a local ChromeDriver matching your Chrome. It is
found via PATH **or** `~/.shiftlefter/config.edn` (`:chromedriver-path`) —
see the root README's CLI section.

## The path

| # | Example | Teaches | Mode | Run | Needs |
|---|---------|---------|------|-----|-------|
| 01 | [`01-validate-and-format/`](01-validate-and-format/) | Parse, validate, and canonically format any Gherkin — a CI gate with zero setup | n/a (no runner) | `sl fmt --check <file>` | nothing |
| 02 | [`02-browser-zero-code/`](02-browser-zero-code/) | A real browser test with zero custom stepdefs; `setup.clj` starts the bundled app it drives — fully offline | Vanilla | `sl run sl/features/` | ChromeDriver |
| 02b | [`02b-browser-multi-actor/`](02b-browser-multi-actor/) | Alice and Bob in separate browser sessions; the glossary declares who exists — fully offline | **Shifted** | `sl run sl/features/` | ChromeDriver |
| 03 | [`03-custom-steps/`](03-custom-steps/) | Your first `defstep` — patterns, captures, context flow | Vanilla | `sl run sl/features/` | nothing |
| 04 | [`04-sms-2fa/`](04-sms-2fa/) | Cross-interface: browser + mock SMS in one scenario; `setup.clj` orchestration | Shifted | `clj -M:demo` | ChromeDriver |
| 05 | [`05-nested-self-rooted/`](05-nested-self-rooted/) | Nested intent addressing — a self-rooted component mounted in two regions | Shifted | `clj -M:demo` | ChromeDriver |
| 06 | [`06-nested-parent-anchored/`](06-nested-parent-anchored/) | Nested intent addressing — parent-anchored collections (the reused-card pattern) | Shifted | `clj -M:demo` | ChromeDriver |

Suggested order: top to bottom. 01–03 need no Clojure knowledge; 04 is
where you first write orchestration code of your own (02/02b ship a small
ready-made `setup.clj` you can read but don't need to touch); 05–06 are
the intent-addressing deep end.

## Reference (not part of the path)

- [`svo-demo/`](svo-demo/) — a file-structure reference for SVO validation
  and for migrating **legacy plain-name steps** (`When Alice clicks …`) to
  the `:type/instance` model the path examples teach. The dry-run works in
  place (`sl run --dry-run sl/features/`); the stepdefs are deliberate stubs,
  so there's no browser demo to run — see its README.
