# Costumes

A **costume** is a named, durable, *authenticated* browser context that an actor
wears to act through an interface. It survives JVM restarts, sleep/wake, and
reruns — so a logged-in session is set up once and reused. Costumes are how you
drive real, already-authenticated browsers: REPL development, long-running
automation, and feature runs that need a real account.

> **Subject vs. costume.** The *subject* is who acts (`:alice`, `:admin`); the
> *costume* is the authenticated getup the subject wears to act (`:finance` = your
> bank logins, `:x` = your Twitter). A subject can act bare (a fresh browser) or
> wearing a costume; a costume is never an actor and has no agency.

**What a costume is not.** It launches plain Chrome with no anti-automation
flags — ever. A costume is "bring your own authenticated session," not a
detection-evasion tool. You log in once, like a human; ShiftLefter reuses that
session.

## Managing costumes (CLI)

The `sl costume` commands manage the costume lifecycle — no Clojure toolchain
needed:

```bash
sl costume init <name> [--chrome-path PATH]   # launch Chrome; log in once, by hand
sl costume list                               # show costumes and status
sl costume destroy <name>                     # kill Chrome, delete the costume
```

`sl costume init` opens a real Chrome window. Log into whatever the costume is
for, then leave it — the authenticated profile is saved to the wardrobe and
reused from then on.

## Using a costume in feature runs (`:wears`)

Bind a subject to a costume with `:wears` on its entry in your **subjects glossary**,
and the runner provisions that subject's session by **attaching to the costume**
instead of spawning a fresh browser:

```clojure
;; glossary/subjects.edn
{:subjects {:bill-payer {:desc "Pays invoices" :wears :finance}}}
```

Now steps performed by `:bill-payer` run inside the `:finance` costume's
authenticated session.

## Using a costume in the REPL (Clojure dev)

For interactive development against a live session, the REPL functions in
`shiftlefter.repl` (also in `shiftlefter.costume`) mirror the CLI:

```clojure
(require '[shiftlefter.repl :as repl])
(require '[shiftlefter.stepdefs.browser])   ; load built-in browser steps

(repl/init-costume! :finance)               ; Chrome opens — log in, navigate
;; => {:status :connected :costume :finance :port 9222 :pid 12345 :browser <CostumeBrowser>}

;; ...later, after a JVM restart — cookies/localStorage/history preserved:
(repl/connect-costume! :finance)

;; drive it via the actor:
(repl/as :finance "opens the browser to 'https://app.example.com'")
(repl/as :finance "clicks {:css \"button.submit\"}")
(repl/as :finance "should see {:text \"Welcome\"}")

(repl/destroy-costume! :finance)            ; kill Chrome, delete the costume
```

`list-costumes` reports each costume as `:alive` (Chrome running), `:dead` (needs
reconnect), or `:unknown` (metadata missing/corrupt).

## What gets persisted

Costumes live in a project-scoped **wardrobe** at `.shiftlefter/wardrobe/<name>/`
(resolved against the directory you run `sl` from):

```
.shiftlefter/wardrobe/finance/
├── browser-meta.edn      # port, pid, settings
└── chrome-profile/       # Chrome user-data dir: cookies, localStorage, history, ...
```

The Chrome profile holds everything Chrome normally stores — cookies and session
data, localStorage/IndexedDB, history, cached credentials. **Not** persisted:
WebDriver session IDs (ephemeral handles that die on sleep/wake), in-memory JS
state, and unsaved form data.

## A costume holds live credentials — keep it out of git

The `chrome-profile/` holds live auth state. Treat the wardrobe like a `.env`
file: **never commit it.** ShiftLefter self-protects:

- On `init`, it ensures `.shiftlefter/wardrobe/` is gitignored (idempotent).
- `init` and `connect` **refuse** (hard error `:costume/git-tracked`) if they
  detect the costume dir is git-tracked. Add `.shiftlefter/wardrobe/` to
  `.gitignore`, `git rm --cached` the tracked files, and retry.

## Auto-reconnection

A costume's browser is a `CostumeBrowser` — it transparently survives session
death. When your laptop sleeps and the WebDriver session dies, the next browser
command detects the dead session, creates a fresh one against the still-open (or
relaunched) Chrome, and retries. You don't reconnect by hand.

## Bare vs. costumed

| Aspect | Bare (default) | Costume |
|---|---|---|
| Created via | `sl run`, test runner | `sl costume init` / `:wears` / `init-costume!` |
| Profile | Temp dir, deleted on close | `.shiftlefter/wardrobe/<name>/` |
| Survives JVM restart | No | Yes |
| Survives sleep/wake | No | Yes (auto-reconnect) |
| Use case | CI, test runs | Authenticated runs, REPL dev, exploration |
| Cleanup | Automatic | Manual (`sl costume destroy`) |

## Troubleshooting

- **"Costume already exists"** (`:costume/already-exists`) — it was created
  before. Connect to it, or `destroy` and recreate.
- **"Costume not found"** (`:costume/not-found`) — check the name (`sl costume
  list`); the error carries the path it searched.
- **"Refusing to use a git-tracked costume dir"** (`:costume/git-tracked`) — the
  costume holds live credentials and must never be committed. Gitignore the
  wardrobe, untrack it, retry.
- **"Chrome not found"** — install Chrome, pass `--chrome-path`, or set
  `:chrome-path` in `~/.shiftlefter/config.edn` (user config; only the wardrobe is
  project-scoped).
- **Port conflicts** — ShiftLefter auto-allocates debug ports starting at 9222. If
  it can't get one, check for zombie Chrome processes
  (`ps aux | grep remote-debugging-port`) and clear them.

## Good practice

- **Name costumes for what they are** — `:finance-prod`, `:client-acme`, not
  `:browser1`.
- **One JVM per costume at a time** — multiple processes driving the same Chrome
  cause session conflicts.
- **Destroy costumes you no longer need** — they hold real credentials.
