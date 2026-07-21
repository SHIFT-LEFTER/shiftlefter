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
     own reporter instance on the SAME path — last group wins, exactly like
     JUnit. The artifact-per-group question is deferred to sl-oobu.

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

   Printer dynvars are pinned because the ambient bindings are NOT ours:
   clojure.main (every `sl` CLI invocation, `clj -M -m`, and any REPL/nREPL
   eval) binds *print-namespace-maps* true, which prints #:pickle{...}
   namespace-map syntax the embedded JS parser deliberately doesn't expand;
   a REPL can also bind *print-length*/*print-level*, which would TRUNCATE
   the island silently. Pinning makes emission context-independent."
  [island-data]
  (binding [*print-length* nil
            *print-level* nil
            *print-namespace-maps* false
            *print-readably* true
            *print-meta* false
            *print-dup* false]
    (-> island-data clean-strings pr-str escape-island assert-island-integrity)))

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

(defrecord HtmlReporter [path state]
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
    (let [{:keys [run-ctx scenarios diagnostics]} @state
          html (build-document {:run-ctx run-ctx
                                :scenarios scenarios
                                :diagnostics diagnostics
                                :summary run-summary})
          file (java.io.File. ^String path)]
      (when-let [parent (.getParentFile file)]
        (.mkdirs parent))
      (spit file html)
      nil)))

(defn make-reporter
  "Construct an HtmlReporter. `opts` is {:path <output-path>}."
  [opts]
  (->HtmlReporter (:path opts) (atom {:scenarios [] :run-ctx nil :diagnostics nil})))
