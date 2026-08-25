# Costumes

A **costume** is the persona's browser-character — a named, durable browser
context (a Chrome profile) that an actor wears to act through an interface.
The concept is **durable per-actor state**; the Chrome profile is its
current — and so far only — manifestation, which is why this page teaches
the browser case throughout.
It is a jacket the persona wears: language preferences, extensions, theme,
PDF handling, accessibility settings — and (the most load-bearing case
today) an accumulated authenticated session. It survives JVM restarts,
sleep/wake, and reruns — so a logged-in session is set up once and reused.
The durability is the **profile plus relaunch**, not the process: Chrome
itself is a child of the launching JVM and dies with it; what survives is
the persisted profile, and `connect-costume!` relaunches Chrome against
it with cookies, localStorage, and auth intact (`relaunch-costume!` does
the same launch-only, for a human re-login).
Costumes are how you drive real, already-authenticated browsers: REPL
development, long-running automation, and feature runs that need a real
account.

> **Subject vs. costume.** The *subject* is who acts (`:alice`, `:admin`); the
> *costume* is the authenticated getup the subject wears to act (`:finance` = your
> bank logins, `:x` = your Twitter). A subject can act bare (a fresh browser) or
> wearing a costume; a costume is never an actor and has no agency.

> **Costume vs. adapter config — person vs. world.** The boundary is the
> Chrome-profile boundary. Profile-resident traits belong to the PERSON and
> are costume material: language, plugins, theme, storage, auth. Launch- and
> environment-resident facts belong to the WORLD and are interface-instance
> material in `shiftlefter.edn`: the browser binary, flags, proxy, network
> path, target system. A Spanish-language preference is a property of the
> person — it rides the costume, never an interface entry, however
> declarable it is. Within a costume, prefer what can be declared (the
> recipe: language, plugins) over what must be accumulated (the residue:
> auth, cookies). See the three-axes diagram in
> [SVO.md](SVO.md#the-three-axes--one-picture).

> **Single-wearer, single-interface.** A costume is a device-local browser
> profile, so it has exactly one wearer on exactly one interface per scenario.
> What crosses interfaces is *identity* — the account, which lives in the
> application — so "the same user on web and mobile" is two costumes plus one
> account, never one costume shared. The runner rejects duplicate `:wears`
> across targets before any browser attaches.

## Declaring costumes in config

Declare each costume's interface INSTANCE in `shiftlefter.edn` — committed
config, so the single-interface doctrine holds statically instead of only at
the runtime guard:

```clojure
:costumes {:finance {:interface :storefront}
           :gmail   {:interface :webmail}}
```

The declaration binds the costume to an interface *instance*, not a type —
wrong-world wear is commonest between same-type instances (two `:web`
systems), and instance scoping catches type mismatches for free. Nothing
here names WebDriver: the adapter behind the instance is free to vary
(sl-3j7c).

Two plan-time lints ride validation and `--dry-run` (both surface in human
output and `--edn`):

- **Wrong-interface wear** (error): a persona's `:wears` names a costume
  declared for a different instance than the one being provisioned for that
  subject in the bound scenario — with a did-you-mean over the costumes
  declared for the provisioned instance.
- **Shared wear** (error): two provisioning targets in the same scenario
  share a `:wears` — one logged-in identity cannot be two actors, and one
  Chrome profile cannot serve two drivers.

An **undeclared** costume in `:wears` is a plan-time *warning*, not an
error — existing wardrobes and REPL ad-hoc flows keep working, and the warn
text teaches the declaration to add. The runtime refusals above stay as the
belt for paths that bypass planning (sl-1206; the lint is the front door,
sl-xbm the backstop).

**What a costume is not.** It launches plain Chrome with no anti-automation
flags — ever. A costume is "bring your own authenticated session," not a
detection-evasion tool. You log in once, like a human; ShiftLefter reuses that
session.

**Costume contents are opaque — by design.** ShiftLefter holds custody of the
container — init, wear, relaunch, destroy — and never parses or interprets
what is inside it. There is no view of a costume's cookies or localStorage as
data, deliberately: cookie stores are encrypted against the OS keychain, the
formats are Chrome-internal, and the session state inside belongs to the
system under test — interpreting it would be rebuilding the SUT's session
layer one level down. What the framework shows you is its own metadata:
`sl costume list` reports each costume's status and last-connected age —
liveness truth, never contents.

## Managing costumes (CLI)

The `sl costume` commands manage the costume lifecycle — no Clojure toolchain
needed:

```bash
sl costume init <name> [--chrome-path PATH]       # launch Chrome; log in once, by hand
sl costume relaunch <name> [--chrome-path PATH]   # reopen an EXISTING costume's browser to log in again
sl costume list                                   # show costumes, status, last-connected age
sl costume destroy <name> [--force]               # delete the costume (refuses while its browser is live)
```

`sl costume init` opens a real Chrome window. Log into whatever the costume is
for, then leave it — the authenticated profile is saved to the wardrobe and
reused from then on.

`sl costume relaunch` is the re-login flow for a costume whose credential
expired: it reopens the existing profile's browser with **no WebDriver
session attached**, so you can log in again by hand; the next connect (or
`sl run` via `:wears`) attaches as usual. If the browser is already
running it succeeds without touching it (`already running … log in in
that window`) — relaunch never kills a live browser.

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

### Per-persona wear (instance `:wears`)

`:wears` also binds **per persona instance** — write the instance as a persona
map instead of a bare keyword, and each persona gets its own jacket:

```clojure
{:subjects
 {:user {:desc "Standard user"
         :wears :default-jacket          ; type-level default, inherited
         :instances [{:id :alicia :desc "Spanish-first admin reviewer"
                      :wears :alicia}    ; her own jacket wins over the default
                     {:id :bob :wears :bob-jacket}
                     {:id :dana :wears nil}   ; opts OUT of the default
                     :carol]}}}               ; bare keyword — inherits :default-jacket
```

Resolution is **instance > type**: a persona's own `:wears` wins; a persona
without one inherits the type-level `:wears`; an explicit `:wears nil` opts
out. This is what lets `:user/alicia` and `:user/bob` act in one scenario
wearing different costumes — the single-wearer rule above still holds per
costume, so two personas resolving to the *same* costume in one scenario is
still rejected. The persona `:desc` is surfaced by `sl explain` and
`sl glossary subjects`, so test writers can see which persona to use when.

## Using a costume in the REPL (Clojure dev)

For interactive development against a live session, the REPL functions in
`shiftlefter.repl` (also in `shiftlefter.costume`) mirror the CLI.
(Costumes are the *authenticated* path; for a throwaway bare browser in
the REPL, load a config whose `:interfaces` declares `:web` — `shifted!`
— and `as`/`run` provision one on demand, closed again by
`reset-ctxs!`/`clear!`.)

```clojure
(require '[shiftlefter.repl :as repl])
(require '[shiftlefter.stepdefs.browser])   ; load built-in browser steps

;; 1. Launch-only: a pristine Chrome opens for YOU to log in. No WebDriver
;;    session is attached yet, and steps cannot drive it.
(repl/init-costume! :finance)
;; => {:status :launched :costume :finance :port 9222 :pid 12345}

;; 2. After logging in, attach a WebDriver session. This is what registers
;;    the actor — `as` only works after a connect.
(repl/connect-costume! :finance)
;; => {:status :connected :costume :finance :port 9222 :pid 12345 :browser <CostumeBrowser>}

;; 3. Drive it via the actor:
(repl/as :finance "opens the browser to 'https://app.example.com'")
(repl/as :finance "clicks {:css \"button.submit\"}")
(repl/as :finance "should see {:text \"Welcome\"}")

;; ...later, after a JVM restart — cookies/localStorage/history preserved;
;; connect-costume! reattaches (and relaunches Chrome if it died):
(repl/connect-costume! :finance)

;; 4. Credential expired? Reopen the browser WITHOUT attaching, log in by
;;    hand, then connect again — the launch-only re-login flow:
(repl/relaunch-costume! :finance)
;; => {:status :launched :costume :finance :port 9222 :pid 23456}
;; ... log in in that window ...
(repl/connect-costume! :finance)

(repl/destroy-costume! :finance)            ; refuses while the browser is live
;; => {:error {:type :costume/browser-live ...}}   ; names the pid serving the port
(repl/destroy-costume! :finance {:force true})     ; kill the browser, delete the costume
;; => {:status :destroyed :costume :finance}
```

`list-costumes` reports each costume as `:alive` (Chrome running), `:dead` (needs
reconnect), or `:unknown` (metadata missing/corrupt). The CLI listing also shows
each costume's last-connected age (`connected 45d ago`) — a costume nobody has
connected to in weeks is a standing credential you've stopped watching; destroy
it (see [Custody](#custody-a-costume-holds-live-credentials)).

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

## Custody: a costume holds live credentials

The `chrome-profile/` holds live auth state. Treat the wardrobe like a `.env`
file: **never commit it.** ShiftLefter self-protects:

- On `init`, it ensures `.shiftlefter/wardrobe/` is gitignored (idempotent).
- `init` and `connect` **refuse** (hard error `:costume/git-tracked`) if they
  detect the costume dir is git-tracked. Add `.shiftlefter/wardrobe/` to
  `.gitignore`, `git rm --cached` the tracked files, and retry.

**Warm runs keep a worn costume's browser alive inside the daemon between
invocations** — that is costume persistence working (the same design as the
dev REPL JVM), which also means consecutive warm runs share the costume's
authenticated state while cold runs never do. `sl daemon status` shows what
the daemon holds; `sl daemon stop` releases it.

Git is not the only leak path. The posture to hold:

- **A costume is as sensitive as your own browser profile** — treat the
  wardrobe like your own logged-in Chrome, because that's what it is.
- **Exclude the wardrobe from sync and backup.** The wardrobe is gitignored,
  but Dropbox, iCloud, and Time Machine happily capture project directories.
  Add `.shiftlefter/wardrobe/` to their exclusion lists.
- **A worn costume is drivable by any local process while it runs.** Attaching
  goes through Chrome's debug port, and any process running as your user can
  connect to it. This is structural, not a bug — don't wear costumes you care
  about on a machine whose local processes you don't trust.
- **Destroy costumes you stop using** (`sl costume destroy <name>`). A costume
  you no longer wear is stored credentials with no job.
- **Destroy verifies before it deletes.** The profile directory is only removed
  once the browser is confirmed dead — liveness is checked against the debug
  port (who is actually serving it), never the recorded pid, which can go
  stale. While the browser is live, destroy **refuses** with
  `:costume/browser-live` and names the real pid; close the browser yourself or
  override with `--force` (CLI) / `{:force true}` (REPL), which kills the
  port's actual owner and re-verifies death before deleting. A silent
  "destroyed" that leaves an authenticated browser running was exactly the
  custody failure this guards against.
- **Destroy also reaps the costume's WebDriver driver.** The costume owns
  its chromedriver process: every connect/reconnect **replaces** the prior
  driver (quitting its session and killing its process — the attached
  Chrome is never touched) instead of accumulating one per connect, and
  destroy reaps the last one. No live WebDriver endpoint pointed at an
  authenticated Chrome outlives the costume.

## Auto-reconnection

A costume's browser is a `CostumeBrowser` — it transparently survives session
death. When your laptop sleeps and the WebDriver session dies, the next browser
command detects the dead session, creates a fresh one against the still-open (or
relaunched) Chrome, and retries. You don't reconnect by hand. Each
reconnect also reaps the driver it replaces, so a long REPL session holds
exactly one live driver per costume — a consequence: drive a costume
through ONE handle at a time (two handles used concurrently reap each
other's drivers alternately — reconnect thrash, not breakage).

## Bare vs. costumed

| Aspect | Bare (default) | Costume |
|---|---|---|
| Created via | `sl run`, test runner | `sl costume init` / `:wears` / `init-costume!` |
| Profile | Temp dir, deleted on close | `.shiftlefter/wardrobe/<name>/` |
| Survives JVM restart | No | Yes (profile + relaunch) |
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
