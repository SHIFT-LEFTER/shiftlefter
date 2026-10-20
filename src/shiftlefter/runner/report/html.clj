(ns shiftlefter.runner.report.html
  "Native HTML run report (sl-muq9) — the fourth REPORT-plane reporter,
   joining console (stderr), EDN (stdout), and JUnit (file).

   ## Architecture: the report is a VIEWER

   ONE self-contained HTML file whose embedded EDN DATA ISLAND
   (<script type=\"application/edn\" id=\"run-data\">) is the SOLE source of
   truth — the accumulated run data (run-ctx + scenario envelopes +
   diagnostics + summary), rendered client-side by an embedded vanilla-JS
   renderer (resources/report/report.js, styles in report.css, both inlined
   at emission). No server-side markup beyond the shell. The renderer is
   deliberately liftable into the served multi-run viewer (sl-zztu).

   WHY the single-file embed: fetch against file:// is blocked in modern
   browsers, so a shell loading a separate .edn dies on double-click.
   Embedding makes the artifact work everywhere — double-click, email
   attachment, CI artifacts browser. Zero external requests of any kind.

   Accepted costs (grooming 2026-07-09): no-JS contexts get a <noscript>
   note (the island itself is readable/greppable EDN text via view-source);
   very large runs build a big DOM — features render collapsed, but a
   10k-scenario report will be sluggish (same caveat family as giant JUnit
   files).

   ## Island integrity

   The island must (a) survive HTML's raw-text <script> parsing — the
   sequence `</script` anywhere in the data would terminate it early — and
   (b) still `clojure.edn/read-string` cleanly (tests slice and re-read it).
   Both hold by escaping `<` as \\u003c INSIDE island string literals only
   (`escape-island` below); \\uXXXX is a valid EDN string escape, and pr-str
   of scrubbed envelopes puts arbitrary text only inside strings — scrub
   stringifies keywords/symbols whose names don't round-trip (sl-27uh), so
   a hostile token can't smuggle text outside a string. A final integrity
   guard (`assert-island-integrity`) backstops the residue: a token that
   round-trips EDN yet prints `</` or `<!--` raw cannot be escaped (EDN has
   no \\u escapes inside tokens), so emission fails loudly instead.

   ## Invariants upheld

   - Accumulating reporter: envelopes collected per run-scope, artifact
     materialized at on-run-end.
   - Loud failure (reporter invariant 4): a file-write error throws.
   - Multi-group runs (setup.clj): each group is its own run-scope with its
     own reporter instance; per-group artifacts land under each group's run
     dir, and the stable --html path carries the invocation-level TOC
     (write-invocation-toc!, sl-n9c6) — not whichever group wrote last.
     JUnit differs on purpose: it aggregates every group into one document
     via invocation-shared state (sl-xjx0). Artifact-per-group questions
     beyond the TOC stay deferred to sl-oobu.

   Emission is standalone by design (DP4): HTML is not XML (void elements,
   raw style/script blocks, doctype), so nothing is shared with junit.clj —
   the ~10 lines of sanitizer duplication are deliberate (rule of three
   before consolidating)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [shiftlefter.runner.reporter :as reporter]))

;; -----------------------------------------------------------------------------
;; Sanitization (browser console junk WILL appear in messages/transcripts)
;; -----------------------------------------------------------------------------

(def ^:private ansi-escape-re
  ;; CSI sequences: ESC [ <params> <final-byte>. Covers color/cursor codes.
  #"\x1b\[[0-9;?]*[ -/]*[@-~]")

(def ^:private control-char-re
  ;; C0 controls except tab (0x09), LF (0x0A), CR (0x0D) — junk in a report.
  #"[\x00-\x08\x0B\x0C\x0E-\x1F]")

(defn clean
  "Strip ANSI escapes and C0 control chars from arbitrary text. nil-safe."
  [s]
  (when s
    (-> (str s)
        (str/replace ansi-escape-re "")
        (str/replace control-char-re ""))))

(defn- clean-strings
  "Sanitize every string in the island data (the seam where terminal junk
   dies before it reaches the artifact)."
  [data]
  (walk/postwalk #(if (string? %) (clean %) %) data))

;; -----------------------------------------------------------------------------
;; Island emission
;; -----------------------------------------------------------------------------

(defn escape-island
  "Escape `<` as \\u003c inside the string literals of an EDN document
   string, so no `</script` (or `<!--`) sequence in the data can escape the
   island's raw-text <script> element. Arbitrary/untrusted text reaches
   pr-str output only inside strings — scrub guarantees it by stringifying
   non-round-tripping keyword/symbol names (sl-27uh) — so scanning with
   in-string tracking is exact; the result still reads back identically via
   clojure.edn. `assert-island-integrity` verifies the guarantee post-hoc."
  [edn-str]
  (let [sb (StringBuilder.)]
    (loop [i 0 in-string? false]
      (if (>= i (count edn-str))
        (str sb)
        (let [c (.charAt ^String edn-str i)]
          (cond
            (and in-string? (= c \\))
            (do (.append sb c)
                (.append sb (.charAt ^String edn-str (inc i)))
                (recur (+ i 2) true))

            (= c \")
            (do (.append sb c)
                (recur (inc i) (not in-string?)))

            (and in-string? (= c \<))
            (do (.append sb "\\u003c")
                (recur (inc i) true))

            :else
            (do (.append sb c)
                (recur (inc i) in-string?))))))))

(defn- assert-island-integrity
  "Fail-loud backstop (sl-27uh defense-in-depth): re-scan the escaped island
   text with the same in-string tracking as `escape-island` and THROW if a
   raw `</` or `<!--` survives outside a string literal. escape-island
   neutralizes `<` inside strings, and scrub stringifies non-round-tripping
   tokens — but a rare token that genuinely round-trips EDN can still print
   `</` outside any string (e.g. (keyword \"x<\" \"script\")), and EDN has no
   \\uXXXX escapes inside keyword/symbol tokens, so such a value cannot be
   escaped at all. Emitting it would let the data terminate the island's
   <script> element (or, via `<!--`, shift the HTML parser into script-data
   escaped state and swallow the real closing tag) — so per reporter
   invariant 4 we abort loudly rather than write a breakable artifact.
   Returns edn-str unchanged when clean."
  [^String edn-str]
  (let [n (count edn-str)
        looking-at? (fn [i s] (.regionMatches edn-str i ^String s 0 (count s)))]
    (loop [i 0 in-string? false]
      (if (>= i n)
        edn-str
        (let [c (.charAt edn-str i)]
          (cond
            (and in-string? (= c \\))
            (recur (+ i 2) true)

            (= c \")
            (recur (inc i) (not in-string?))

            (and (not in-string?) (= c \<)
                 (or (looking-at? i "</") (looking-at? i "<!--")))
            (throw (ex-info "HTML report island integrity violation: an EDN token prints a raw '</' or '<!--' outside any string literal; emitting it would break the data island, so the report is not written"
                            {:type :report/island-integrity
                             :message "raw '</' or '<!--' outside a string literal in island EDN"
                             :location {:index i
                                        :context (subs edn-str
                                                       (max 0 (- i 30))
                                                       (min n (+ i 30)))}}))

            :else
            (recur (inc i) in-string?)))))))

(defn- escape-html
  "Escape text for the static HTML shell (title, noscript). Island and
   renderer content never go through this — the island is raw-text script
   content protected by escape-island; all rendered data lands via
   textContent in report.js."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

;; -----------------------------------------------------------------------------
;; Document
;; -----------------------------------------------------------------------------

(defn island-edn
  "Sanitize and serialize the island data to escaped EDN text.

   Printer dynvars are pinned (via `reporter/pr-edn-str`, the shared writer
   seam — sl-3ub4) because the ambient bindings are NOT ours: clojure.main
   (every `sl` CLI invocation, `clj -M -m`, and any REPL/nREPL eval) binds
   *print-namespace-maps* true, which prints #:pickle{...} namespace-map
   syntax the embedded JS parser deliberately doesn't expand; a REPL can
   also bind *print-length*/*print-level*, which would TRUNCATE the island
   silently. Pinning makes emission context-independent."
  [island-data]
  (-> island-data clean-strings reporter/pr-edn-str escape-island assert-island-integrity))

(defn build-document
  "Pure: build the self-contained HTML document string from the island data
   {:run-ctx m :scenarios [envelopes] :diagnostics m-or-nil :summary m}.
   CSS and the renderer are inlined from resources/report/ (sl-0lj precedent:
   vanilla JS, no bundler, no CDN)."
  [island-data]
  (let [title (str (or (:project-name (:run-ctx island-data)) "ShiftLefter")
                   " — run report")]
    (str "<!DOCTYPE html>\n"
         "<html lang=\"en\">\n<head>\n"
         "<meta charset=\"utf-8\">\n"
         "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
         "<title>" (escape-html title) "</title>\n"
         "<style>\n" (slurp (io/resource "report/report.css")) "</style>\n"
         "</head>\n<body>\n"
         "<noscript><p class=\"noscript-note\">This report renders with"
         " JavaScript. The complete run data is embedded in this file as"
         " readable EDN &mdash; view source and find"
         " <code>script type=&quot;application/edn&quot;</code>.</p></noscript>\n"
         "<div id=\"app\"></div>\n"
         "<script type=\"application/edn\" id=\"run-data\">\n"
         (island-edn island-data)
         "\n</script>\n"
         "<script>\n" (slurp (io/resource "report/report.js")) "</script>\n"
         "</body>\n</html>\n")))

;; -----------------------------------------------------------------------------
;; Reporter
;; -----------------------------------------------------------------------------

;; Atomic fixed-path writes (sl-luhn's torn-write fix, HTML half via sl-l9nj)
;; live in reporter/write-atomically! — lifted there so JUnit shares them.

(defn- same-file-target?
  "True when two path strings name the same target file (the user pointed
   --html INTO the run dir) — guard against a pointless self-copy."
  [^String a ^String b]
  (= (.getCanonicalPath (java.io.File. a))
     (.getCanonicalPath (java.io.File. b))))

(defn write-html!
  "The ONE two-target write rule (sl-l9nj; shared since sl-o26v C1): when a
   `primary` (<group-root>/report.html, inside the run dir) exists it is
   the real artifact and is written first; the stable configured `path`
   then gets a byte copy unless it IS the primary. Without a primary the
   stable path is the only write. A write failure THROWS (invariant 4)."
  [primary path html]
  (if primary
    (do (reporter/write-atomically! primary html)
        (when-not (same-file-target? primary path)
          (reporter/write-atomically! path html)))
    (reporter/write-atomically! path html))
  nil)

(defn write-crash-page!
  "sl-o26v: materialize the CRASHED run page for a single-scope crash from
   an HtmlReporter's accumulated state — `{:run-ctx :scenarios
   :diagnostics}` as it stood when the coordinator died — with a
   `:summary {:status :crashed :crash record …}` the renderer already
   keys on (the run header reads :crashed via TOC_STATUS and
   appendCrashLine reads :crash on any summary — and, since sl-n6io,
   names the record's :scenario when it carries one).
   Same two targets as on-run-end. Throws on a write failure — the
   CALLER owns not letting that mask the crash itself."
  [primary path state summary]
  (let [{:keys [run-ctx scenarios diagnostics]} state]
    (write-html! primary path
                 (build-document {:run-ctx run-ctx
                                  :scenarios scenarios
                                  :diagnostics diagnostics
                                  :summary summary}))))

(defrecord HtmlReporter [path primary state]
  reporter/Reporter
  (on-run-start [_this run-ctx]
    (swap! state assoc :run-ctx run-ctx)
    nil)

  (on-scenario-complete [_this scenario-result]
    (swap! state update :scenarios conj scenario-result)
    nil)

  (on-diagnostics [_this diagnostics]
    (swap! state assoc :diagnostics diagnostics)
    nil)

  (on-run-end [_this run-summary]
    ;; Materialize the artifact. A write failure THROWS (invariant 4): the
    ;; user asked for this file and must never get a silent partial.
    ;;
    ;; sl-l9nj (Chair-ratified 08-03): when the run has a results dir the
    ;; report JOINS it — `primary` (<group-root>/report.html) is the real
    ;; artifact, sitting beside attachments/ so relative refs resolve and
    ;; the run dir zips as a self-contained directory. The configured
    ;; `path` then gets an end-of-run byte copy (same string, same
    ;; encoding — not a symlink, Windows-safe): the stable deterministic
    ;; path CI configs and agents grab survives exactly as before. With no
    ;; results dir (`primary` nil — :defaults context) the stable path is
    ;; the only write, as it always was.
    (let [{:keys [run-ctx scenarios diagnostics]} @state
          html (build-document {:run-ctx run-ctx
                                :scenarios scenarios
                                :diagnostics diagnostics
                                :summary run-summary})]
      (write-html! primary path html))))

(defn write-planning-failure!
  "Materialize the HTML report for a planning-failed run (exit 2, sl-6hsd).
   Console and EDN both carry the diagnostics on this path; the HTML file
   must not stay silently absent — worse, a previous run's report.html
   would sit there lying. Reuses the pure build-document: zero scenarios,
   the diagnostics island (scrubbed exactly like the lifecycle path), and
   a :planning-failed summary the renderer keys its header state on.
   Loud failure (reporter invariant 4): a write error throws."
  [path run-ctx diagnostics summary]
  ;; Stable path ONLY, by design (sl-l9nj kept it): planning failures never
  ;; create a run dir (sl-y3wd — execute-and-report-stage is never reached),
  ;; so there is no primary to join.
  (reporter/write-atomically! path
                     (build-document {:run-ctx run-ctx
                                      :scenarios []
                                      :diagnostics (reporter/diagnostics-envelope
                                                    diagnostics)
                                      :summary summary}))
  nil)

(defn write-invocation-toc!
  "Materialize the invocation-level TOC for a multi-group run (sl-n9c6):
   the stable --html path represents the INVOCATION, not whichever group
   wrote last. `island-data` carries {:run-ctx m :toc {:groups [rows]}
   :summary m} — build-document is island-shape-agnostic, and the renderer
   branches on :toc. `primary` is <run-dir>/report.html (byte copy to
   `path`, the l9nj idiom); nil primary (no run dir exists — every group
   planning-failed, sl-y3wd creates none) writes the stable path only:
   verdicts stay visible, links degrade to labeled text."
  [primary path island-data]
  (write-html! primary path (build-document island-data)))

(defn make-reporter
  "Construct an HtmlReporter. `opts` is {:path <stable-configured-path>
   :primary <group-root>/report.html | nil} — `path` is always written;
   when `primary` is set it is the real artifact and `path` becomes the
   end-of-run byte copy (sl-l9nj)."
  [opts]
  (->HtmlReporter (:path opts) (:primary opts)
                  (atom {:scenarios [] :run-ctx nil :diagnostics nil})))
