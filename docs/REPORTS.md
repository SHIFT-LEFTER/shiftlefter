# Build your own report

ShiftLefter has no reporting plugin API — on purpose. What it has instead is
a **machine record**: the `--edn` summary stream plus the bytes in the run
directory. Those two surfaces are the extension contract. The in-tree JUnit
reporter earns its output from the same record your code reads; nothing it
does is privileged. This page documents the record and walks a complete,
runnable rendering recipe.

## The built-in outputs

**Console** writes human-readable progress and failure detail to **stderr**,
always — it never competes with machine output on stdout.

**EDN** (`--edn`) writes the machine record to **stdout**: a stream of EDN
forms carrying the verdict, counts, failures, diagnostics, and attachment
refs. This is the surface the rest of this page documents. Note the most
common misconception up front: **the EDN summary is not a file** — nothing
like `summary.edn` lands in the run directory. If you want the record on
disk, redirect stdout (`sl run … --edn > run.edn`).

**JUnit XML** (`--junit-xml PATH`) writes the lossy interop subset — flat
testsuites/testcase — for native CI ingestion (GitLab, GitHub, Jenkins).
Wiring and worked pipelines: [Running ShiftLefter in CI](CI.md).

**HTML** (`report.html` in the run directory, plus an optional `--html`
fixed-path copy) is the self-contained human report; it sits beside the
`attachments/` directories so relative references resolve, and the whole
run directory zips and travels as one artifact
([the run results directory](CI.md#the-run-results-directory)).
Its status vocabulary mirrors the exit ladder: a scenario whose failure
carried `:error/class :observation` renders the word **UNOBSERVED**
(styled by the `st-unobserved` modifier, distinct from ERROR — the harness
family — and from FAILED, a verdict), the header's counts row gains an
`N unobserved` entry whenever that count is positive, and a halted run
shows the `halted after scenario N (<name>): observation failure` line
under the run meta. The same words appear on the multi-group invocation
TOC's rows.

## The machine-record contract

The `--edn` summary is the machine surface, and it is a **locked data
contract**: keys are additive, never repurposed — code written against the
record survives releases. The canonical shape lives in the
`shiftlefter.runner.report.edn` namespace docstring
(`src/shiftlefter/runner/report/edn.clj`); the CI-facing summary is
[CI.md → Consuming `--edn` output](CI.md#consuming---edn-output).

Three rules cover every consumer:

- **stdout is a form *stream*, not one map.** A classic run prints one
  summary form; `setup.clj` orchestration prints one per group, in group
  order; small supplementary diagnostics forms may appear between or before
  summaries. Read **all** forms.
- **Skip shapes you don't recognize.** The form vocabulary grows by
  release; unknown forms and unknown keys are never an error. Summaries are
  the forms carrying `:run/exit-code`.
- **Gate on the exit code, route on the record.** The exit-code table
  (0 passed / 1 failed / 2 planning-failed / 3 crashed / 4 degraded) is
  maintained in [README → Return Codes](../README.md#return-codes) and
  [CI.md](CI.md) — this page deliberately doesn't restate it. `:counts`
  rides every summary, all-zero when nothing executed, so you can read it
  without branching on the code.

**Multi-group runs** (more than one `setup.clj` group after CLI narrowing)
add two things, absent everywhere else so single-group streams stay
byte-identical:

- Every per-group summary form carries top-level **`:group/label`** and
  **`:group/index`** (0-based declared order — the durable join key when
  labels collide; under CLI narrowing the indexes keep their declared
  values and may have gaps, so `alpha` + `charlie` out of three groups are
  `0` and `2`). Order is no longer the only way to join forms to groups.
- After all group forms, **one final invocation aggregate form** carries the
  suite verdict: `{:run/id … :run/seed … :invocation/exit-code N
  :invocation/status kw :invocation/groups [{:group/label … :group/index …
  :run/exit-code … :run/status …} …]}`. It deliberately does **not** carry
  `:run/exit-code`, so the summary discriminator above never matches it.
  Verdict rule: read `:invocation/exit-code` if that form is present, else
  the lone summary's `:run/exit-code` is the verdict. The aggregate is
  absent on a crash (exit 3 — the stream ends where the crash cut it) and
  on planning failures that precede the group loop (no groups ran; the lone
  summary is the verdict). Under `--dry-run` it is still emitted, with
  `:invocation/status :dry-run` and exit code 0 — a plan verdict, never
  `:passed` for a run that measured nothing — so a CI plan gate reading the
  aggregate finds it. Under `{:runner {:halt-on-observation-failure :run}}`
  a group that went dark halts the invocation: the aggregate carries
  `:halted {:after-scenario N :scenario "<name>" :group/label "<group>"}`,
  and every group not yet started has a row with `:run/status :skipped` and
  **no** `:run/exit-code` — it never ran, so no verdict exists (the same
  absence rule this form already uses for itself). The aggregate also
  carries `:invocation/results-path` (config-root-relative, the
  `results/<stamp>` directory every group's own `:run/results-path` sits
  under; absent when no results dir was made, as under `--dry-run`) — so
  the invocation-level run dir is read, never derived by stripping the
  group slug.

**Dry-run summaries** (single- or multi-group) additionally carry
`:run/reports-not-written`, a vector of report kinds (`[:html :junit-xml]`),
whenever a report is declared by flag or by the `[:runner :report …]` config
mirror: the dry run proceeds and writes nothing, and this key (plus one
stderr line in console mode) says so. Absent when no report is declared.

## The evidence walk

When capture is enabled (`:capture` config or `--capture`) or an After hook
claims attachments, the summary carries evidence **references** — bytes
never ride the envelope; files live in the run directory
(payload-by-reference).

Refs appear in two places, deliberately:

- **`:attachments`** (top level) is the **authoritative, complete evidence
  walk**: one entry per scenario carrying any refs, *regardless of status*.
  Green-run evidence — `:every-step` captures, After-hook claims on passing
  scenarios — appears **only** here. Each entry names the scenario and
  nests step-level refs under `:steps`:

  ```clojure
  :attachments [{:scenario/name "…" :scenario/id #uuid "…"
                 :attachments [ref …]              ; scenario-level claims
                 :steps [{:step/id #uuid "…" :step/text "…"
                          :attachments [ref …]}]}]
  ```

- **`:failures` entries** carry their own step's/scenario's refs inline, so
  a triage tool renders a failure with its evidence join-free. These are the
  *same refs verbatim* as the index — the duplication is deliberate, not a
  bug to dedupe around.

The key is absent when the run produced no refs (default capture policy is
`:never` — attachment-less summaries are unchanged by this feature).

A **ref** is a map of `:attachment/*` keys. `:attachment/kind` (keyword) and
`:attachment/path` (string) are required; the rest are optional:
`:attachment/bytes`, `:attachment/media-type`, `:attachment/source`
(`:capture-policy`, or `[:hook "name"]` — framework-stamped, never
self-asserted), `:attachment/duration-ms`, `:attachment/excerpt` (bounded
triage courtesy), and `:attachment/error`.

Paths follow the **portability contract** (canonical:
`src/shiftlefter/attachments.clj` namespace docstring): a *relative* path is
run-dir-relative (group-root-relative under `setup.clj` groups) — portable,
resolve it against the directory you archived. The summary names that
directory itself as `:run/results-path` (config-root-relative), so resolve
relative refs beneath it — no directory guessing. An *absolute* path outside
the run directory stays verbatim — legible but non-portable; render it as
text, never a link.

`:attachment/error` is the **honesty marker**: a failed capture, a claimed
file that doesn't exist, or a claim with no run directory to resolve
against. The ref stays visible — evidence loss is recorded, never silently
dropped — but treat it as a report line, not a link. Also read
`:capture-lints` under `:diagnostics`: with stderr suppressed under
`--edn`, that key is where a machine consumer learns promised capture
evidence is never coming (unknown kind, invalid policy, no results dir,
or `:capture/adapter-unsupported` — the kind exists but no adapter this
group uses supports it; that one is computed at plan time and appears in
dry-run summaries too, per group).

## Recipe: a markdown digest in ~45 lines

A complete consumer, honoring every rule above: reads the redirected form
stream, keeps the summaries, renders a markdown digest with resolved
evidence links. Save it as `render-digest.bb` ([babashka](https://babashka.org)).

```clojure
#!/usr/bin/env bb
;; render-digest.bb — markdown digest from a ShiftLefter --edn run record.
;; Usage: bb render-digest.bb <run.edn> <run-dir> > digest.md

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(defn read-forms
  "All EDN forms in the file. Unknown tagged literals are kept as data —
   the skip-unknown rule, applied to tags."
  [file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (into [] (take-while #(not= ::eof %))
          (repeatedly #(edn/read {:eof ::eof :default tagged-literal} r)))))

(defn evidence-line
  "One markdown line per ref: errored refs render as FAILED text, absolute
   paths as text (non-portable), relative paths as links into the run dir."
  [run-dir {:attachment/keys [kind path error]}]
  (cond
    error                        (str "- " (name kind) " FAILED: " error)
    (.isAbsolute (io/file path)) (str "- " (name kind) ": " path)
    :else                        (str "- [" (name kind) "](" (io/file run-dir path) ")")))

(defn scenario-section [run-dir entry]
  (concat [(str "### " (:scenario/name entry))]
          (map #(evidence-line run-dir %) (:attachments entry))
          (mapcat (fn [step]
                    (cons (str "**" (:step/text step) "**")
                          (map #(evidence-line run-dir %) (:attachments step))))
                  (:steps entry))))

(defn digest [run-dir {:run/keys [status exit-code] :keys [counts failures attachments]}]
  (concat
   [(str "## Run: " (name status) " (exit " exit-code ")")
    (str (:passed counts) " passed, " (:failed counts) " failed — "
         (:scenarios counts) " scenarios, " (:steps counts) " steps")]
   (for [f failures]
     (str "- FAILED: " (:scenario/name f) " — " (or (:step/text f) "(scenario)")
          (when-let [m (-> f :error :message)] (str " — " m))))
   (when (seq attachments) ["" "## Evidence"])
   (mapcat #(scenario-section run-dir %) attachments)))

(let [[edn-file run-dir] *command-line-args*]
  (doseq [line (->> (read-forms edn-file)
                    (filter :run/exit-code)   ; summaries; skip everything else
                    (mapcat #(digest run-dir %)))]
    (println line)))
```

Produce the inputs from any capture-enabled run. Concretely, from
`examples/02-browser-zero-code` (offline — its `setup.clj` starts a bundled
fixture server; needs ChromeDriver):

```bash
cd examples/02-browser-zero-code
../../bin/sl run sl/features/ \
  --capture screenshot=every-step,console=every-step --edn > /tmp/run.edn
# refs are GROUP-root-relative: pass the group's summary :run/results-path
# (config-root-relative — here it resolves under sl/), e.g.
# sl/results/<STAMP>/login. No setup.clj = the run dir itself.
bb render-digest.bb /tmp/run.edn sl/results/<STAMP>/login
```

The digest lists every scenario's evidence — this is a green run, which is
exactly the point: the `:attachments` index is where green-run evidence
lives, and a consumer reading only `:failures` would report none.

## The in-tree proof of the pattern

The JUnit reporter (`src/shiftlefter/runner/report/junit.clj`) is the
existence proof that the record is enough: it renders testsuites, per-step
transcripts, and Jenkins `[[ATTACHMENT|…]]` lines from the same scenario
record — same refs, same portability contract, same honesty marker
(errored refs get FAILED lines, never links). One honest nuance: as an
in-tree reporter it consumes the scenario envelopes in-process through the
`Reporter` protocol rather than parsing its own serialized output — but the
envelope holds the same data the EDN summary serializes, and it uses no
API a consumer of the serialized record lacks. Anything it does, your
renderer can do from `run.edn` + the run directory.

## Non-goals

- **A reporting plugin API** — deliberately absent. A plugin surface would
  freeze reporter internals into a public contract; the data contract is
  the stable surface instead, and it's already locked.
- **Richer built-in renderings** (attachment galleries, trend views, diff
  reports): parked as wait-for-demand (sl-kv23). If the recipe above is the
  start of something you need built in, that demand is the signal.

Related pages: [Running ShiftLefter in CI](CI.md) ·
[Hooks — the attachments claim](hooks.md) ·
[The execution lifecycle](lifecycle.md) ·
[Project layout](project-layout.md)
