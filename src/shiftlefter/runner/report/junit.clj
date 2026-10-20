(ns shiftlefter.runner.report.junit
  "JUnit-XML reporter (sl-40to) — the third REPORT-plane reporter, joining
   console (stderr) and EDN (stdout). It accumulates scrubbed scenario
   envelopes and materializes one JUnit-XML file at run-end so any CI
   (GitLab/GitHub/Jenkins) can ingest results natively.

   JUnit is deliberately the LOSSY INTEROP SUBSET: a flat
   testsuites/testsuite/testcase model. Anything that doesn't fit (rich
   step provenance, attachments) belongs in a future SL-native reporter;
   the envelope already carries everything. There is no official JUnit
   spec — we target the common consumer subset (testmoapp/junitxml +
   llg.cubic.org) and net correctness three ways: a community XSD, a
   parse-back property (clojure.xml, zero deps), and a real CI ingest.

   ## Invariants upheld

   - RED <=> NONZERO: the XML contains >=1 <failure>/<error> iff the run's
     exit code is nonzero. Pending mirrors the exit code via :allow-pending?
     (D2): allowed => <skipped>, strict => <failure type='pending'>.
   - testcase identity is stable across runs: name=:pickle/name (with a
     ' (example N)' suffix disambiguating identical outline-row names),
     classname=feature name. The :pickle/id UUID appears NOWHERE — CI
     history keys on classname+name.
   - Loud failure (reporter invariant 4): a file-write error throws.

   ## Mapping

   - <testsuites name=PROJECT>            — one run-scope
   - <testsuite name='Feature: X' …>      — one feature file; <properties>
     carry shiftlefter.version; timestamp=run start (ISO-8601 UTC,
     no offset); hostname for XSD validity.
   - <testcase name=SCENARIO classname=FEATURE file=REL line=SCENARIO-LINE
     time=SECONDS>                        — one scenario (macro-proof:
     expansion multiplies steps, never scenarios). <system-out> carries the
     authored-primary step transcript (D6)."
  (:require [clojure.string :as str]
            [shiftlefter.attachments :as attachments]
            [shiftlefter.runner.reporter :as reporter]))

;; -----------------------------------------------------------------------------
;; Sanitization (browser console junk WILL appear in messages/transcripts)
;; -----------------------------------------------------------------------------

(def ^:private ansi-escape-re
  ;; CSI sequences: ESC [ <params> <final-byte>. Covers color/cursor codes.
  #"\x1b\[[0-9;?]*[ -/]*[@-~]")

(def ^:private xml-illegal-control-re
  ;; XML 1.0 forbids C0 controls except tab (0x09), LF (0x0A), CR (0x0D).
  #"[\x00-\x08\x0B\x0C\x0E-\x1F]")

(defn clean
  "Strip ANSI escapes and XML-1.0-illegal control chars from arbitrary text
   (message attributes and system-out transcripts). nil-safe."
  [s]
  (when s
    (-> (str s)
        (str/replace ansi-escape-re "")
        (str/replace xml-illegal-control-re ""))))

;; -----------------------------------------------------------------------------
;; Minimal XML emitter (hand-rolled, zero deps; kept tight per the sl-40to
;; revisit trigger — a second XML surface or >~100 lines => reach for data.xml)
;; -----------------------------------------------------------------------------

(defn- escape-attr [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "\n" "&#10;")
      (str/replace "\t" "&#9;")
      (str/replace "\r" "&#13;")))

(defn- escape-text [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- cdata
  "Wrap text in CDATA, splitting any literal ]]> across two sections so it
   cannot terminate the block early."
  [s]
  (str "<![CDATA[" (str/replace (str s) "]]>" "]]]]><![CDATA[>") "]]>"))

(defn- render-attrs [attrs]
  ;; nil-valued attrs are dropped (optional attributes like file/line).
  (->> attrs
       (keep (fn [[k v]] (when (some? v) (str " " (name k) "=\"" (escape-attr v) "\""))))
       (apply str)))

(declare render-node)

(defn- render-element [{:keys [tag attrs content]}]
  (let [open (str "<" tag (render-attrs attrs))]
    (if (seq content)
      (str open ">" (apply str (map render-node content)) "</" tag ">")
      (str open "/>"))))

(defn- render-node [node]
  (cond
    (nil? node)      ""
    (string? node)   (escape-text node)
    (:cdata node)    (cdata (:cdata node))
    :else            (render-element node)))

(defn- el
  "Build an XML element node. content entries may be elements, strings
   (text-escaped), or {:cdata s}."
  ([tag attrs] (el tag attrs nil))
  ([tag attrs content] {:tag tag :attrs attrs :content (vec (remove nil? content))}))

;; -----------------------------------------------------------------------------
;; Duration / timing helpers
;; -----------------------------------------------------------------------------

(defn- secs
  "Format milliseconds as JUnit seconds (3dp, ROOT locale so the decimal is a
   dot). nil => 0.000."
  [ms]
  (String/format java.util.Locale/ROOT "%.3f" (object-array [(/ (double (or ms 0)) 1000.0)])))

(defn- scenario-ms [scenario]
  (or (:duration-ms scenario)
      (reduce + 0.0 (keep :duration-ms (:steps scenario)))))

;; -----------------------------------------------------------------------------
;; Step transcript (system-out) — authored-primary macro rendering (D6)
;; -----------------------------------------------------------------------------

(defn- expanded-child? [step]
  (= :expanded (get-in step [:step :step/macro :role])))

(defn- macro-counts
  "Map [macro-key call-site-line] -> :step-count, read off the synthetic
   wrapper steps (:role :call). Expanded children carry :index but NOT
   :step-count, so they look up their total M here."
  [steps]
  (into {}
        (keep (fn [s]
                (let [macro (get-in s [:step :step/macro])]
                  (when (= :call (:role macro))
                    [[(:key macro) (get-in macro [:call-site :line])]
                     (:step-count macro)])))
              steps)))

(defn- child-total [macro counts]
  (get counts [(:key macro) (get-in macro [:call-site :line])]))

(defn- step-line
  "One transcript line for a step. Expanded macro children get a per-line
   ASCII marker '+ [<key> N/M] …' (indentation is NOT the delimiter — CI UIs
   mangle whitespace; marker absence ends the expansion)."
  [step counts]
  (let [s (:step step)
        kw (:step/keyword s)
        text (:step/text s)
        status (name (:status step))
        dur (secs (:duration-ms step))
        base (str kw " " text " [" status "] (" dur "s)")]
    (if (expanded-child? step)
      (let [macro (:step/macro s)
            n (inc (or (:index macro) 0))
            m (child-total macro counts)]
        (str "+ [" (:key macro) " " n "/" (or m "?") "] " base))
      base)))

(defn- hook-line
  "One transcript line for a lifecycle hook record (sl-esq): ASCII marker
   discipline like the macro '+' lines — `hook reset-db [before] [ok]
   (0.012s)`. :contributed keys ride along when present."
  [record]
  (str "hook " (:name record)
       " [" (name (:phase record)) "]"
       " [" (name (:status record)) "]"
       " (" (secs (:duration-ms record)) "s)"
       (when-let [contributed (seq (:contributed record))]
         (str " -> " (str/join ", " (map str contributed))))))

(defn- attachment-line
  "One transcript line per attachment ref (sl-l9nj). Healthy refs use the
   Jenkins JUnit-Attachments convention — `[[ATTACHMENT|<absolute path>]]`
   — with relative (group-root-relative) paths absolutized against the
   run-ctx :artifacts-root. Errored refs get a plain FAILED line instead
   of an [[ATTACHMENT]] pointer: the failure message is the signal, and
   the .err evidence file (sl-yynw F7) stays unpromoted — failed captures
   visible, never linkable. A relative ref with no root to resolve
   against (hand-built envelopes) degrades to a plain named line."
  [artifacts-root ref]
  (let [kind (name (:attachment/kind ref))]
    (if-let [err (:attachment/error ref)]
      (str "attachment " kind " FAILED: " err)
      (if-let [abs (attachments/resolve-ref-path artifacts-root
                                                 (:attachment/path ref))]
        (str "[[ATTACHMENT|" abs "]]")
        (str "attachment " kind ": " (:attachment/path ref))))))

(defn- transcript
  "Scenario transcript: before-phase hook lines above the steps, after-phase
   below — mirroring actual execution order; each step's attachment lines
   directly under its step line, scenario-level attachment lines at the
   tail (sl-l9nj). Hook-less, attachment-less scenarios are byte-identical
   to before."
  [scenario counts artifacts-root]
  (let [hooks (:hooks scenario)
        befores (filter #(= :before (:phase %)) hooks)
        afters (filter #(= :after (:phase %)) hooks)]
    (str/join "\n" (concat (map hook-line befores)
                           (mapcat (fn [step]
                                     (cons (step-line step counts)
                                           (map #(attachment-line artifacts-root %)
                                                (:attachments step))))
                                   (:steps scenario))
                           (map hook-line afters)
                           (map #(attachment-line artifacts-root %)
                                (:attachments scenario))))))

;; -----------------------------------------------------------------------------
;; Failure / error / skipped classification
;; -----------------------------------------------------------------------------

(defn- synthetic? [step]
  (true? (get-in step [:step :step/synthetic?])))

(defn- first-step-of-status
  "The first NON-synthetic step with `status`. Synthetic macro wrappers carry
   a rolled-up status (and sit BEFORE their children) but hold no :error and
   no leaf text — skipping them lands on the real failing/pending leaf, so the
   <failure> attributes the actual step (and its macro call-site)."
  [scenario status]
  (first (filter #(and (= status (:status %)) (not (synthetic? %)))
                 (:steps scenario))))

(defn- macro-attribution
  "When the failing step is an expanded macro child, name BOTH the authored
   macro call (key + call-site feature line) AND the child (index, text) —
   the provenance guard the 0.6 macro rewrite must keep green (AC6)."
  [step counts]
  (when (expanded-child? step)
    (let [macro (get-in step [:step :step/macro])
          key (:key macro)
          n (inc (or (:index macro) 0))
          m (child-total macro counts)
          call-line (get-in macro [:call-site :line])
          child-text (get-in step [:step :step/text])]
      (str " [via macro '" key "' called at feature line " call-line
           ", step " n "/" (or m "?") ": " child-text "]"))))

(defn- failure-message [failing-step counts]
  (let [text (get-in failing-step [:step :step/text])
        err (:error failing-step)
        msg (:message err)]
    (str (clean text)
         (when msg (str " -- " (clean msg)))
         (clean (macro-attribution failing-step counts)))))

(defn- scenario-error-message
  "Message for a scenario-level :error (the infrastructure-failure family,
   sl-esq/sl-7c7i: hook throw, capability provisioning, capture).
   Hook-attributed errors keep the dual attribution (hook name +
   registration home + @hook= tag file:line); other family members render
   their own message verbatim."
  [err]
  (let [ts (:tag-source err)]
    (str (when (:hook err) (str "hook '" (:hook err) "' -- "))
         (clean (:message err))
         (when-let [reg (:path (:registration err))]
           (str " [registered at " reg "]"))
         (when (and ts (:file ts))
           (str " [tagged at " (:file ts) ":" (:line ts) "]")))))

(declare testcase-verdict-outcome)

(defn- testcase-outcome
  "Return the outcome child element for a scenario testcase, or nil for a pass.
   :error when the failing step carried an :exception-class, else :failure;
   pending mirrors the exit code via allow-pending? (D2). `counts` is the
   macro N/M lookup (may be {} when only the outcome tag is needed).
   Scenario :error (the infrastructure-failure family, sl-esq/sl-7c7i:
   hook throw, capability provisioning, capture) renders <error>
   with the error's type as the type attribute, reading the
   SCENARIO-level :error map.

   Error class (sl-kowi): the class travels as a grep-able message prefix
   — `[unobserved] ` on an observation-class scenario error, `[SUT error] `
   on a failed step classed :sut. The type attribute keeps the error type;
   the envelope carries the key structurally."
  [scenario allow-pending? counts]
  (cond
    ;; sl-190x: a @blocked scenario held as claimed — <skipped> with the
    ;; `held:` prefix and what was measured (C2), whatever its status.
    (= :blocked (-> scenario :held :reason))
    (el "skipped" {:message (str "held: " (reporter/blocked-held-phrase (:held scenario)))} nil)

    ;; sl-190x: XPASS — the claim is false; a <failure> carrying the
    ;; imperative (never <error>: nothing in the harness broke).
    (:xpass scenario)
    (el "failure" {:type "xpass" :message (-> scenario :xpass :message)} nil)

    :else (testcase-verdict-outcome scenario allow-pending? counts)))

(defn- testcase-verdict-outcome
  "`testcase-outcome` for a scenario that ran to an unexcused verdict."
  [scenario allow-pending? counts]
  (case (:status scenario)
    :passed nil
    :failed (let [fs (first-step-of-status scenario :failed)
                  err (:error fs)
                  tag (if (:exception-class err) "error" "failure")
                  etype (or (:exception-class err) (some-> (:type err) name))
                  prefix (when (= :sut (:error/class err)) "[SUT error] ")]
              (el tag {:type etype
                       :message (str prefix (failure-message fs counts))} nil))
    :error (let [err (:error scenario)
                 t (:type err)
                 etype (cond
                         (nil? t) "hook"
                         (namespace t) (str (namespace t) "/" (name t)) ; "hook/before-failed"
                         :else (name t))
                 prefix (when (= :observation (:error/class err)) "[unobserved] ")]
             (el "error" {:type etype
                          :message (str prefix (scenario-error-message err))} nil))
    :pending (let [ps (first-step-of-status scenario :pending)
                   text (clean (get-in ps [:step :step/text]))
                   msg (str "pending: " text)]
               (if allow-pending?
                 (el "skipped" {:message msg} nil)
                 (el "failure" {:type "pending" :message msg} nil)))
    ;; :skipped — didn't execute. A scenario the run abort held (sl-617t)
    ;; says so with the 6wh9 `held:` prefix, so a CI reader sees it was
    ;; held, not silently dropped; a non-runnable plan keeps the old line.
    (if-let [held (:held scenario)]
      (el "skipped"
          {:message (if-let [k (:after-step held)]
                      (str "held: aborted after step " k " of "
                           (count (:steps scenario)))
                      "held: aborted before this scenario ran")}
          nil)
      (el "skipped" {:message "scenario not executed"} nil))))

;; -----------------------------------------------------------------------------
;; Name disambiguation — identical outline-row names get ' (example N)'
;; -----------------------------------------------------------------------------

(defn- disambiguate-names
  "Given the pickles of one feature (in plan order), return a vector of
   testcase display names: a name shared by >1 scenario gets a stable
   ' (example N)' suffix (1-based occurrence). :pickle/id never participates."
  [pickles]
  (let [names (map #(or (:pickle/name %) "(unnamed)") pickles)
        dup? (->> names frequencies (keep (fn [[n c]] (when (> c 1) n))) set)]
    (first
     (reduce (fn [[acc seen] nm]
               (if (dup? nm)
                 (let [n (inc (get seen nm 0))]
                   [(conj acc (str nm " (example " n ")")) (assoc seen nm n)])
                 [(conj acc nm) seen]))
             [[] {}]
             names))))

;; -----------------------------------------------------------------------------
;; Envelope -> element tree
;; -----------------------------------------------------------------------------

(defn- relative-path [project-root path]
  (if (and project-root path (str/starts-with? (str path) (str project-root)))
    (let [rel (subs (str path) (count (str project-root)))]
      (str/replace rel #"^[/\\]+" ""))
    path))

(defn- pickle-of [scenario] (get-in scenario [:plan :plan/pickle]))

(defn- testcase-el [scenario display-name project-root allow-pending? artifacts-root]
  (let [pickle (pickle-of scenario)
        classname (:pickle/feature-name pickle)
        file (relative-path project-root (:pickle/source-file pickle))
        line (get-in pickle [:pickle/location :line])
        counts (macro-counts (:steps scenario))
        outcome (testcase-outcome scenario allow-pending? counts)]
    (el "testcase"
        {:name (clean display-name)
         :classname (clean classname)
         :file file
         :line line
         :time (secs (scenario-ms scenario))}
        [outcome
         (el "system-out" {}
             [{:cdata (clean (transcript scenario counts artifacts-root))}])])))

(defn- count-outcomes [scenarios allow-pending?]
  (reduce (fn [acc sc]
            (let [oc (testcase-outcome sc allow-pending? {})
                  tag (:tag oc)]
              (cond-> (update acc :tests inc)
                (= tag "failure") (update :failures inc)
                (= tag "error")   (update :errors inc)
                (= tag "skipped") (update :skipped inc))))
          {:tests 0 :failures 0 :errors 0 :skipped 0}
          scenarios))

(defn- held-testcase-el
  "A scenario held at planning — --hold-unplannable's (sl-6wh9) or a
   @wip/@skip tag's (sl-190x) — never ran, so it renders <skipped> with a
   `held:` message naming why (the CI consumer sees it was held, not
   silently dropped): `held: not plannable (2 undefined, 0 ambiguous
   step(s))` — `, 1 binding error(s): element-has-no-shape` appended when
   lint errors held it (sl-8ezu) — `held: @wip`, `held: @skip=flaky-on-ci`."
  [{:keys [scenario feature uri line reason tag undefined ambiguous] :as entry} project-root]
  (el "testcase"
      {:name (clean scenario)
       :classname (clean feature)
       :file (relative-path project-root uri)
       :line line
       :time (secs 0.0)}
      [(el "skipped" {:message (if (= :unplannable reason)
                                 (str "held: not plannable (" undefined
                                      " undefined, " ambiguous " ambiguous step(s)"
                                      (when-let [b (reporter/binding-errors-phrase entry)]
                                        (str ", " b))
                                      ")")
                                 (str "held: " tag))}
           nil)]))

(defn- testsuite-el [scenarios held run-ctx]
  (let [{:keys [version started-at hostname project-root allow-pending?
                artifacts-root]} run-ctx
        pickle (pickle-of (first scenarios))
        feature-name (or (:pickle/feature-name pickle) (:feature (first held)))
        names (disambiguate-names (map pickle-of scenarios))
        counts (-> (count-outcomes scenarios allow-pending?)
                   (update :tests + (count held))
                   (update :skipped + (count held)))
        total-ms (reduce + 0.0 (map scenario-ms scenarios))]
    (el "testsuite"
        {:name (clean (str "Feature: " feature-name))
         :tests (:tests counts)
         :failures (:failures counts)
         :errors (:errors counts)
         :skipped (:skipped counts)
         :time (secs total-ms)
         :timestamp started-at
         :hostname hostname}
        (-> [(el "properties" {}
                 [(el "property" {:name "shiftlefter.version" :value version} nil)])]
            (into (map (fn [sc nm]
                         (testcase-el sc nm project-root allow-pending? artifacts-root))
                       scenarios names))
            (into (map #(held-testcase-el % project-root) held))))))

(def crash-suite-name
  "The synthetic <testsuite> a runner crash appends (sl-8orx). Named so a
   consumer can tell the marker from a feature suite at a glance."
  "sl-crash")

(defn- crash-testsuite-el
  "sl-8orx: the crash marker — ONE synthetic testsuite holding ONE testcase
   whose <error type=\"crash\"> names the exception class, the message and
   the run-id (the same three fields the HTML TOC's :crash record carries,
   so a reader can match the two artifacts), plus — sl-n6io — a `scenario:`
   line naming the crashed scenario (project-relative file:line, like the
   <testcase file=> attribute) when the record carries `:scenario`; a
   crash with no scenario to name renders the three-line body unchanged.
   Appended on the crash path so the invocation file satisfies 'at least
   one failure/error iff the exit is nonzero' beside exit 3 — before it, a
   mid-run crash left a CURRENT, all-green, partial document (the xwwv
   finding)."
  [{:keys [exception-class message run-id scenario]} run-ctx]
  (let [{:keys [started-at hostname project-root]} run-ctx
        headline (str "Runner crash: " exception-class ": " message)
        scenario-line (when scenario
                        (str "scenario: " (:name scenario)
                             " (" (relative-path project-root (:source-file scenario))
                             ":" (:line scenario) ")\n"))]
    (el "testsuite"
        {:name crash-suite-name
         :tests 1
         :failures 0
         :errors 1
         :skipped 0
         :time (secs 0.0)
         :timestamp started-at
         :hostname hostname}
        [(el "testcase"
             {:name "runner crash"
              :classname crash-suite-name
              :time (secs 0.0)}
             [(el "error" {:type "crash" :message (clean headline)}
                  [{:cdata (clean (str "exception-class: " exception-class "\n"
                                       "message: " message "\n"
                                       "run-id: " run-id "\n"
                                       scenario-line
                                       "The runner crashed after the scenarios"
                                       " above completed; every later scenario"
                                       " and group never started."))}])])])))

(defn build-document
  "Pure: build the JUnit-XML document string from accumulated scenario
   envelopes and the enriched run-ctx. Scenarios are grouped into one
   <testsuite> per feature file, preserving plan order. `held` (sl-6wh9,
   optional) is the run's :held record — each entry renders as a
   <skipped> testcase under its feature file's suite (a suite is created
   for a file with only held scenarios). `crash` (sl-8orx, optional) is the
   runner-crash record {:exception-class :message :run-id}, plus
   `:scenario` when the crash escaped one scenario (sl-io05/sl-n6io): when
   present one synthetic `sl-crash` suite with one <error type=\"crash\">
   testcase is appended and counted in the root totals; nil is
   byte-identical to the no-crash document."
  ([scenarios run-ctx] (build-document scenarios run-ctx nil nil))
  ([scenarios run-ctx held] (build-document scenarios run-ctx held nil))
  ([scenarios run-ctx held crash]
   (let [held-by-file (group-by :uri held)
         groups (->> scenarios
                     (group-by #(:pickle/source-file (pickle-of %)))
                     ;; deterministic suite order: first appearance in plan order
                     (sort-by (fn [[_ scs]]
                                (->> scenarios (keep-indexed (fn [i s] (when (identical? s (first scs)) i))) first))))
         executed-files (set (map first groups))
         held-only (->> held-by-file
                        (remove (fn [[file _]] (executed-files file)))
                        (sort-by first))
         totals (reduce (fn [acc [_ scs]]
                          (merge-with + acc (count-outcomes scs (:allow-pending? run-ctx))))
                        {:tests 0 :failures 0 :errors 0 :skipped 0}
                        groups)
         totals (-> totals
                    (update :tests + (count held))
                    (update :skipped + (count held)))
         ;; sl-8orx: the crash marker is one more test and one more error.
         totals (cond-> totals
                  crash (-> (update :tests inc) (update :errors inc)))
         total-ms (reduce + 0.0 (map scenario-ms scenarios))
         root (el "testsuites"
                  {:name (clean (:project-name run-ctx))
                   :tests (:tests totals)
                   :failures (:failures totals)
                   :errors (:errors totals)
                   :skipped (:skipped totals)
                   :time (secs total-ms)}
                  (concat
                   (map (fn [[file scs]] (testsuite-el scs (get held-by-file file) run-ctx)) groups)
                   (map (fn [[_ hs]] (testsuite-el [] hs run-ctx)) held-only)
                   (when crash [(crash-testsuite-el crash run-ctx)])))]
     (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          (render-node root)
          "\n"))))

;; -----------------------------------------------------------------------------
;; Reporter
;; -----------------------------------------------------------------------------

(defn- hostname []
  (try (.getHostName (java.net.InetAddress/getLocalHost))
       (catch java.net.UnknownHostException _ "localhost")))

(defrecord JUnitReporter [path state]
  reporter/Reporter
  (on-run-start [_this run-ctx]
    ;; Stash the enriched run-ctx (version/timestamp/project) and stamp a
    ;; hostname for XSD validity. Console/EDN ignore run-start.
    ;; In a multi-group run `state` is the invocation-shared atom (sl-xjx0,
    ;; Warden W7) — the FIRST group's ctx wins so timestamp/name don't churn
    ;; across groups.
    (swap! state (fn [s]
                   (cond-> s
                     (nil? (:run-ctx s))
                     (assoc :run-ctx (assoc run-ctx :hostname (hostname))))))
    nil)

  (on-scenario-complete [_this scenario-result]
    (swap! state update :scenarios conj scenario-result)
    nil)

  (on-diagnostics [_this _diagnostics]
    ;; JUnit carries results, not planning diagnostics.
    nil)

  (on-run-end [_this run-summary]
    ;; sl-6wh9: held scenarios (--hold-unplannable; sl-190x's @wip/@skip)
    ;; ride the :run-end payload; accumulate across groups like scenarios
    ;; do. Only the PLANNING holds — the engine's held scenarios are
    ;; already testcases of their own.
    (swap! state update :held (fnil into []) (reporter/planning-held (:held run-summary)))
    ;; Materialize the artifact. A write failure THROWS (invariant 4): a CI
    ;; gate reading this file must never get a silent partial. Atomic
    ;; temp-sibling + rename (sl-luhn): a concurrent reader/writer on the
    ;; fixed path sees last-writer-wins, never a torn file.
    (let [{:keys [scenarios run-ctx held]} @state
          xml (build-document scenarios run-ctx held)]
      (reporter/write-atomically! path xml)
      nil)))

(defn make-reporter
  "Construct a JUnitReporter. `opts` is {:path <output-path>, :shared-state?}.

   `:shared-state` (sl-xjx0, Warden W7): in a multi-group run every group
   builds its own reporter but the output path is invocation-fixed, so
   per-group private state made each group's :run-end write REPLACE the
   whole document — a red early group vanished behind a green last group
   (breaking the ERRATA invariant: ≥1 failure/error iff exit nonzero).
   The runner passes one invocation-scoped atom for all groups; each
   :run-end write then aggregates every scenario seen so far — the last
   write carries the full invocation. A mid-run crash re-materializes the
   same state with the `sl-crash` marker suite appended (sl-8orx,
   write-crash-marker!), so the partial file is red beside exit 3 instead
   of current-and-green. Absent (single group / direct construction) the
   reporter keeps its own atom, byte-identical to before."
  [opts]
  (->JUnitReporter (:path opts)
                   (or (:shared-state opts)
                       (atom {:scenarios [] :run-ctx nil}))))

(defn write-crash-marker!
  "sl-8orx: materialize the invocation JUnit with the crash marker appended.
   `state` is the invocation-shared atom (sl-xjx0) — every scenario the
   reporters saw before the crash is in it; `crash` is the runner-crash
   record {:exception-class :message :run-id} (+ `:scenario` when one
   scenario crashed, sl-n6io), stored into the state (the record is part
   of the invocation's truth, not a render-time flag) and rendered as the
   `sl-crash` suite. `fallback-run-ctx` covers a crash
   before any group's on-run-start stamped the state's :run-ctx. The write
   rides the same atomic path as on-run-end; a write failure THROWS
   (invariant 4) — the CALLER owns not letting that mask the crash itself."
  [path state crash fallback-run-ctx]
  (let [{:keys [scenarios run-ctx held]} (swap! state assoc :crash crash)
        run-ctx (or run-ctx (assoc fallback-run-ctx :hostname (hostname)))]
    (reporter/write-atomically! path (build-document scenarios run-ctx held crash))
    nil))
