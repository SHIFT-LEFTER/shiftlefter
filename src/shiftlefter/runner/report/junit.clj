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
     carry shiftlefter.version + mode; timestamp=run start (ISO-8601 UTC,
     no offset); hostname for XSD validity.
   - <testcase name=SCENARIO classname=FEATURE file=REL line=SCENARIO-LINE
     time=SECONDS>                        — one scenario (macro-proof:
     expansion multiplies steps, never scenarios). <system-out> carries the
     authored-primary step transcript (D6)."
  (:require [clojure.string :as str]
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

(defn- transcript [scenario counts]
  (str/join "\n" (map #(step-line % counts) (:steps scenario))))

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

(defn- testcase-outcome
  "Return the outcome child element for a scenario testcase, or nil for a pass.
   :error when the failing step carried an :exception-class, else :failure;
   pending mirrors the exit code via allow-pending? (D2). `counts` is the
   macro N/M lookup (may be {} when only the outcome tag is needed)."
  [scenario allow-pending? counts]
  (case (:status scenario)
    :passed nil
    :failed (let [fs (first-step-of-status scenario :failed)
                  err (:error fs)
                  tag (if (:exception-class err) "error" "failure")
                  etype (or (:exception-class err) (some-> (:type err) name))]
              (el tag {:type etype :message (failure-message fs counts)} nil))
    :pending (let [ps (first-step-of-status scenario :pending)
                   text (clean (get-in ps [:step :step/text]))
                   msg (str "pending: " text)]
               (if allow-pending?
                 (el "skipped" {:message msg} nil)
                 (el "failure" {:type "pending" :message msg} nil)))
    ;; :skipped (non-runnable plan) — didn't execute
    (el "skipped" {:message "scenario not executed"} nil)))

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

(defn- testcase-el [scenario display-name project-root allow-pending?]
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
         (el "system-out" {} [{:cdata (clean (transcript scenario counts))}])])))

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

(defn- testsuite-el [scenarios run-ctx]
  (let [{:keys [version mode started-at hostname project-root allow-pending?]} run-ctx
        pickle (pickle-of (first scenarios))
        feature-name (:pickle/feature-name pickle)
        names (disambiguate-names (map pickle-of scenarios))
        counts (count-outcomes scenarios allow-pending?)
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
        (into [(el "properties" {}
                   [(el "property" {:name "shiftlefter.version" :value version} nil)
                    (el "property" {:name "mode" :value (some-> mode name)} nil)])]
              (map (fn [sc nm] (testcase-el sc nm project-root allow-pending?))
                   scenarios names)))))

(defn build-document
  "Pure: build the JUnit-XML document string from accumulated scenario
   envelopes and the enriched run-ctx. Scenarios are grouped into one
   <testsuite> per feature file, preserving plan order."
  [scenarios run-ctx]
  (let [groups (->> scenarios
                    (group-by #(:pickle/source-file (pickle-of %)))
                    ;; deterministic suite order: first appearance in plan order
                    (sort-by (fn [[_ scs]]
                               (->> scenarios (keep-indexed (fn [i s] (when (identical? s (first scs)) i))) first))))
        totals (reduce (fn [acc [_ scs]]
                         (merge-with + acc (count-outcomes scs (:allow-pending? run-ctx))))
                       {:tests 0 :failures 0 :errors 0 :skipped 0}
                       groups)
        total-ms (reduce + 0.0 (map scenario-ms scenarios))
        root (el "testsuites"
                 {:name (clean (:project-name run-ctx))
                  :tests (:tests totals)
                  :failures (:failures totals)
                  :errors (:errors totals)
                  :skipped (:skipped totals)
                  :time (secs total-ms)}
                 (map (fn [[_ scs]] (testsuite-el scs run-ctx)) groups))]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         (render-node root)
         "\n")))

;; -----------------------------------------------------------------------------
;; Reporter
;; -----------------------------------------------------------------------------

(defn- hostname []
  (try (.getHostName (java.net.InetAddress/getLocalHost))
       (catch java.net.UnknownHostException _ "localhost")))

(defrecord JUnitReporter [path state]
  reporter/Reporter
  (on-run-start [_this run-ctx]
    ;; Stash the enriched run-ctx (version/mode/timestamp/project) and stamp a
    ;; hostname for XSD validity. Console/EDN ignore run-start.
    (swap! state assoc :run-ctx (assoc run-ctx :hostname (hostname)))
    nil)

  (on-scenario-complete [_this scenario-result]
    (swap! state update :scenarios conj scenario-result)
    nil)

  (on-diagnostics [_this _diagnostics]
    ;; JUnit carries results, not planning diagnostics.
    nil)

  (on-run-end [_this _run-summary]
    ;; Materialize the artifact. A write failure THROWS (invariant 4): a CI
    ;; gate reading this file must never get a silent partial.
    (let [{:keys [scenarios run-ctx]} @state
          xml (build-document scenarios run-ctx)
          file (java.io.File. ^String path)]
      (when-let [parent (.getParentFile file)]
        (.mkdirs parent))
      (spit file xml)
      nil)))

(defn make-reporter
  "Construct a JUnitReporter. `opts` is {:path <output-path>}."
  [opts]
  (->JUnitReporter (:path opts) (atom {:scenarios [] :run-ctx nil})))
