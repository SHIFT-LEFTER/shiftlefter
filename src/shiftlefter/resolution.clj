(ns shiftlefter.resolution
  "`sl glossary` + `sl explain NAME` — the resolution-query surface (sl-0gfc).

   The files are the DECLARED state; these commands expose the RESOLVED
   state — what the run will believe, before the run. Both are thin
   renderers over `project-projection/build-projection`, which resolves
   through the run's own config/glossary/intent/stepdef loaders — never a
   parallel path. Scope: the projection is plan-time and declaration-fed;
   it cannot see registry mutations a group's `:start` performs live (the
   declaration grammar cannot express removal), so a listing can drift
   from a run whose `:start` removes or replaces entries. Parity holes
   are chartered as sl-j2c3.

   Contract: `--edn` output is the stable machine surface. The human
   format is EXPLICITLY UNSTABLE — do not parse it.

   Shifted-only: Vanilla mode gets an honest not-applicable message
   (exit 0), never a silent empty listing.

   v2 (sl-mpu5) adds the corpus-riding surfaces over the SVO reverse
   index (projection.usage-index): usage facets on explain, used/unused +
   dead-vocabulary markers on listings, the locator→intents reverse
   index (explain-by-locator + the on-demand `duplicates` section), and
   scoped selection (paths + --tags/--skip-tags, the run's grammar).
   WORDING DISCIPLINE (addendum 2, the safety): full-corpus output says
   `unused`; a scoped selection says `not referenced within this
   selection` and NEVER stronger — a scoped verdict must not read as
   deletion license. The EDN mirrors this structurally: full-corpus
   entries carry :unused?, selection entries carry
   :referenced-in-selection? — distinct keys, so a consumer cannot
   conflate them. Builtin vocabulary is NEVER dead (globally provided).
   Macros contribute steps, not vocabulary — they do not appear here."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [shiftlefter.intent.loader :as intent-loader]
            [shiftlefter.project-projection :as projection]
            [shiftlefter.runner.reporter :as reporter]
            [shiftlefter.runner.setup :as setup]
            [shiftlefter.svo.bindings-join :as bindings-join]
            [shiftlefter.svo.glossary :as glossary]
            [shiftlefter.svo.keys :as svo-keys]
            [shiftlefter.svo.locator-index :as locator-index]
            [shiftlefter.projection.usage-index :as usage-index]
            [shiftlefter.text :as text]))

(def output-version 1)

;; -----------------------------------------------------------------------------
;; Shared: header + gates
;; -----------------------------------------------------------------------------

(defn- emit-edn! [value]
  (print (reporter/pr-edn-str value))
  (println)
  (flush))

(defn- mode-line [mode]
  (case mode
    :shifted "Mode: Shifted — SVO validation ON (:svo present in config)"
    :vanilla "Mode: Vanilla — SVO validation OFF (no :svo in config)"
    nil))

(defn- substrate-lines [{:keys [config-path config-source git-sha git-dirty?]}]
  [(str "Config: " (or config-path "(none)")
        (when config-source (str " (" (name config-source) ")")))
   (str "Substrate: "
        (if git-sha
          (str "git " git-sha (when git-dirty? " (dirty — working tree modified)"))
          "git: not a repository"))])

(defn- strictness-line [svo-effective]
  (if svo-effective
    (str "Strictness (:svo effective): "
         (str/join " · " (map (fn [[k v]] (str (name k) " " v))
                              (sort-by key svo-effective))))
    "Strictness: n/a (Vanilla — no :svo)"))

(defn- header-lines [proj]
  (concat
   (keep identity [(mode-line (:mode proj))])
   (substrate-lines (:substrate proj))
   [(strictness-line (:svo-effective proj))]))

(defn- vanilla-edn [proj command]
  {:resolution/version output-version
   :command command
   :status :not-applicable
   :mode :vanilla
   :substrate (:substrate proj)
   :message (str "Vanilla mode: no vocabulary contract applies — there are no"
                 " subjects, and steps bind by pattern alone. Add :svo to the"
                 " config to enable the typed vocabulary. Builtin step patterns:"
                 " `sl agent-doc builtins`.")})

(defn- print-vanilla! [proj edn? command]
  (if edn?
    (emit-edn! (vanilla-edn proj command))
    (do (println (str/join "\n" (header-lines proj)))
        (println)
        (println (str "No vocabulary contract applies in Vanilla mode — there"
                      " are no subjects, and steps bind by pattern alone."))
        (println "Add :svo to the config to enable the typed vocabulary.")
        (println "Builtin step patterns: `sl agent-doc builtins`.")
        (flush)))
  0)

(defn- no-project! [project-context edn? command]
  (if edn?
    (emit-edn! {:resolution/version output-version
                :command command
                :status :no-project
                :config-source (:config-source project-context)
                :project-root (:project-root project-context)})
    (do (println "No ShiftLefter project here — nothing to resolve.")
        (println "Create sl/shiftlefter.edn, or see `sl agent-doc overview`.")
        (flush)))
  0)

(defn- ambiguous-config! [project-context command]
  (binding [*out* *err*]
    (println (str "Cannot run " command ": ambiguous ShiftLefter config."))
    (doseq [d (:diagnostics project-context)]
      (println (str "  " (or (:message d) (:type d))))))
  2)

(defn- projection-failed! [proj command]
  (binding [*out* *err*]
    (println (str "Cannot " command ": the project's accepted truth failed to"
                  " load. Run `sl orient` for the full diagnostics."))
    (doseq [d (take 5 (:diagnostics proj))]
      (println (str "  [" (name (or (:severity d) :error)) "] "
                    (or (:type d) "")
                    (when (:message d) (str " — " (:message d)))))))
  1)

(defn- listing-blocked?
  "Whether a projection error blocks THIS listing surface (sl-hyey).

   A listing is not a run (the mpu5 vacuity law): suite-lint issues —
   capability gating included — mean a RUN would fail planning, which
   `sl orient` reports faithfully (:status :error, runner parity, kept).
   But they say nothing false about the vocabulary or its usage, so
   glossary/explain proceed when suite-lint issues are the ONLY errors;
   the corpus-bind carries them as the visible warn caveat instead.
   Any other :error diagnostic (config, glossary, steps, setup…) still
   blocks — those make the listing itself untrustworthy."
  [proj]
  (and (= :error (:status proj))
       (not (every? #(= :suite-lint (:stage %))
                    (filter #(= :error (:severity %)) (:diagnostics proj))))))

;; -----------------------------------------------------------------------------
;; Usage context (sl-mpu5) — corpus bind + reverse index, once per command
;; -----------------------------------------------------------------------------

(defn- selection-from
  "The scoped-selection parameter (paths + tag rules, the run's grammar),
   or nil when the invocation selects nothing — nil means full corpus."
  [paths {:keys [tags skip-tags]}]
  (let [tf (when (or (seq tags) (seq skip-tags))
             (cond-> {}
               (seq tags) (assoc :include tags)
               (seq skip-tags) (assoc :exclude skip-tags)))]
    (when (or (seq paths) tf)
      (cond-> {}
        (seq paths) (assoc :paths (vec paths))
        tf (assoc :tag-filter tf)))))

(defn- usage-context
  "Bind the corpus (full or selected) and index it. Returns
   {:status :ok :scope :full-corpus|:selection :selection sel
    :corpus {...} :tuples [...] :used {...} :glossary-map gmap}
   or {:status :unavailable :reason str} when there is no usable corpus —
   markers are then SUPPRESSED entirely rather than reporting everything
   unused (the false-positive-deadness harm)."
  [proj project-context selection]
  (let [cfg (:config proj)
        ;; sl-hyey (Warden W4 sibling): bind against the registry the
        ;; project actually resolves — the e0ml declared-preview overlay,
        ;; recomputed from the projection's pure-data declarations (the
        ;; registry value holds factory fns and never rides the projection).
        registry (when-let [decls (seq (:adapter-declarations proj))]
                   (setup/declared-preview-registry (vec decls)))
        corpus (usage-index/bind-corpus project-context cfg selection
                                        {:adapter-registry registry})
        gmap (:ok (glossary/load-all-glossaries-strict (:glossaries cfg)))]
    (if (zero? (:feature-count corpus))
      {:status :unavailable
       :reason (or (:message (first (:parse-errors corpus)))
                   "no feature corpus found")}
      (let [tuples (usage-index/index (:plans corpus) cfg gmap)]
        {:status :ok
         :scope (if selection :selection :full-corpus)
         :selection selection
         :corpus (select-keys corpus [:feature-count :scenario-count
                                      :parse-errors :unmatched-step-count
                                      :suite-lint-issues])
         :tuples tuples
         :used (usage-index/used-sets tuples)
         :glossary-map gmap}))))

(defn- corpus-lines
  "Header lines for the corpus the usage facts were computed over."
  [{:keys [status scope corpus reason selection]}]
  (if (= :unavailable status)
    [(str "Corpus: usage unavailable — " reason)]
    (cond-> [(str "Corpus: " (:feature-count corpus) " feature file(s), "
                  (:scenario-count corpus) " scenario(s)"
                  (when (= :selection scope)
                    (str " — SELECTION ("
                         (str/join " " (concat (:paths selection)
                                               (map str (sort (:include (:tag-filter selection))))
                                               (map #(str "not " %) (sort (:exclude (:tag-filter selection))))))
                         ")")))]
      (seq (:parse-errors corpus))
      (conj (str "Caveat: " (count (:parse-errors corpus))
                 " feature file(s) failed to parse — usage/deadness is"
                 " computed over the files that parsed"))
      (pos? (:unmatched-step-count corpus))
      (conj (str "Caveat: " (:unmatched-step-count corpus)
                 " step(s) did not bind — they contribute no usage facts"))
      ;; sl-hyey: warn-not-kill — a listing is not a run, so suite-lint
      ;; issues inform instead of suppressing every marker; but they must
      ;; stay VISIBLE (warn, never silent).
      (seq (:suite-lint-issues corpus))
      (conj (str "Caveat: " (count (:suite-lint-issues corpus))
                 " suite-lint issue(s) over the corpus — a run would fail"
                 " planning; usage markers are computed over the bound"
                 " corpus anyway")))))

(defn- usage-edn
  "The :usage block every v2 EDN output carries."
  [{:keys [status scope corpus reason selection]}]
  (if (= :unavailable status)
    {:status :unavailable :reason reason}
    (cond-> {:status :ok :scope scope :corpus corpus}
      selection (assoc :selection selection))))

(defn- marker-keys
  "Per-entry usage keys, wording discipline enforced structurally:
   full-corpus → :unused? (deadness verdict); selection →
   :referenced-in-selection? (NEVER a deadness claim)."
  [scope n]
  (if (= :selection scope)
    {:use-count n :referenced-in-selection? (pos? n)}
    {:use-count n :unused? (zero? n)}))

(defn- entry-marker
  "Human marker read off an enriched entry's own keys, so wording can
   never outrun the EDN verdict. Count-only entries (builtins — never
   dead) show a count when positive and nothing otherwise."
  [e]
  (cond
    (contains? e :referenced-in-selection?)
    (if (:referenced-in-selection? e)
      (str " [referenced ×" (:use-count e) "]")
      " [not referenced within this selection]")

    (contains? e :unused?)
    (if (:unused? e) " [UNUSED]" (str " [used ×" (:use-count e) "]"))

    (and (contains? e :use-count) (pos? (:use-count e)))
    (str " [used ×" (:use-count e) "]")

    :else ""))

;; --- per-entry counting + enrichment ----------------------------------------

(defn- subject-type-count [tuples type-kw]
  (count (filter #(= type-kw (:subject-type %)) tuples)))

(defn- instance-use-count [tuples inst qualified]
  (count (filter #(or (= inst (:subject %)) (= qualified (:subject %))) tuples)))

(defn- verb-use-count [tuples itype verb]
  (count (filter #(and (= itype (:interface-type %)) (= verb (:verb %))) tuples)))

(defn- intent-use-count [tuples intent-name]
  (count (filter #(= intent-name (:intent-use %)) tuples)))

(defn- enrich-subject [uctx s]
  (merge s (marker-keys (:scope uctx)
                        (subject-type-count (:tuples uctx) (:type s)))))

(defn- enrich-instance [uctx i]
  (merge i (marker-keys (:scope uctx)
                        (instance-use-count (:tuples uctx) (:instance i) (:qualified i)))))

(defn- enrich-verb
  "Builtin verbs are NEVER dead (globally provided — addendum 2): they get
   a bare :use-count, no deadness verdict. User vocabulary gets the full
   marker keys."
  [uctx itype v]
  (let [n (verb-use-count (:tuples uctx) itype (:verb v))]
    (if (= :builtin (:provenance v))
      (assoc v :use-count n)
      (merge v (marker-keys (:scope uctx) n)))))

(defn- enrich-intent [uctx i]
  (merge i (marker-keys (:scope uctx)
                        (intent-use-count (:tuples uctx) (str (:intent i))))))

(defn- interfaces-without-bindings
  "Configured interface TYPES with no binding for any of the intent's
   elements — the #16 coverage seam (sl-mpu5). The axis is TYPES, never
   instance names (stepengine doctrine; the sl-sfb1 trap's inverse)."
  [proj intent-entry]
  (let [configured (set (map :type (:interfaces proj)))
        bound (->> (:bindings intent-entry)
                   (mapcat (comp keys :bindings))
                   set)]
    (vec (sort (remove bound configured)))))

(defn- load-intents-index
  "The loader-shaped intents structure for the locator reverse index —
   the run's own loader over the resolved intents dir (same-path)."
  [proj]
  (let [dir (get-in proj [:config :glossaries :intents])]
    (if (and dir (fs/exists? (str dir)))
      (or (:ok (intent-loader/load-all-intents (str dir))) {})
      {})))

;; -----------------------------------------------------------------------------
;; Objects section (intents + interface-contributed vocabulary)
;; -----------------------------------------------------------------------------

(defn- implicit-objects
  "Distinct implicit objects contributed by verb frames, with the
   interface type that contributes them."
  [verbs-section]
  (->> verbs-section
       (mapcat (fn [{:keys [interface-type verbs]}]
                 (for [v verbs
                       f (:frames v)
                       :when (:implicit-object f)]
                   {:object (:implicit-object f)
                    :interface-type interface-type
                    :via-verb (:verb v)
                    :provenance :interface-contributed})))
       distinct
       vec))

(defn- objects-section [proj uctx]
  {:intents (mapv (fn [i]
                    (cond-> (assoc (select-keys i [:intent :provenance :root
                                                   :reusable? :elements
                                                   :collections :bindings])
                                   :interfaces-without-bindings
                                   (interfaces-without-bindings proj i))
                      (= :ok (:status uctx)) (->> (enrich-intent uctx))))
                  (:intents proj))
   :interface-contributed
   {:keys {:provenance :interface-contributed
           :kind :key
           :description "Closed W3C KeyboardEvent.key name set (frames with :object-kind :key)"
           :names (vec (sort svo-keys/key-names))}
    :implicit-objects (implicit-objects (:verbs proj))}})

;; -----------------------------------------------------------------------------
;; sl glossary
;; -----------------------------------------------------------------------------

(def ^:private glossary-sections #{"subjects" "verbs" "objects" "duplicates"})

(defn- enrich-verbs-section [uctx verbs-section]
  (mapv (fn [{:keys [interface-type] :as block}]
          (update block :verbs
                  (partial mapv #(enrich-verb uctx interface-type %))))
        verbs-section))

(defn- build-sections
  "The (possibly usage-enriched) section map — one build shared by the
   EDN and human renderers so they can never disagree."
  [proj section uctx]
  (let [ok? (= :ok (:status uctx))]
    (cond-> {}
      (or (nil? section) (= section "subjects"))
      (assoc :subjects (cond->> (:subjects proj)
                         ok? (mapv #(enrich-subject uctx %)))
             :instances (cond->> (:instances proj)
                          ok? (mapv #(enrich-instance uctx %))))
      (or (nil? section) (= section "verbs"))
      (assoc :verbs (cond-> (:verbs proj)
                      ok? (->> (enrich-verbs-section uctx))))
      (or (nil? section) (= section "objects"))
      (assoc :objects (objects-section proj uctx))
      (= section "duplicates")
      (assoc :duplicates (locator-index/duplicates
                          (load-intents-index proj))))))

(defn- glossary-edn [proj uctx sections]
  (cond-> {:resolution/version output-version
           :command :glossary
           :status :ok
           :mode (:mode proj)
           :substrate (:substrate proj)
           :svo-effective (:svo-effective proj)
           :fingerprint (:fingerprint proj)
           :sections sections}
    uctx (assoc :usage (usage-edn uctx))))

(defn- persona-sub-line
  "One indented line for an instance carrying persona meta (sl-4e91)."
  [type-kw inst meta']
  (str "    - :" (name type-kw) "/" (name inst)
       (when-let [d (:desc meta')] (str " — " d))
       (when (contains? meta' :wears)
         (if-let [w (:wears meta')]
           (str " (wears :" (name w) ")")
           " (wears nothing)"))))

(defn- subject-line [{:keys [type description instances singleton? provenance
                             instance-meta] :as entry}]
  (str "- :" (name type) " [" (name provenance) "]"
       (entry-marker entry)
       (when description (str " — " description))
       (if singleton?
         " (singleton)"
         (str " — instances: " (str/join ", " (map name instances))))
       ;; Personas with meta get their own sub-lines, in :instances order.
       (->> instances
            (filter #(contains? instance-meta %))
            (map #(str "\n" (persona-sub-line type % (get instance-meta %))))
            (apply str))))

(defn- verb-line [{:keys [verb description provenance frames] :as entry}]
  (str "- :" (name verb) " [" (name provenance) "]"
       (entry-marker entry)
       (when description (str " — " description))
       "\n" (str/join "\n"
                      (map (fn [f]
                             (str "    frame :" (name (:frame f))
                                  " — schema (not a step): " (:pattern f)))
                           frames))))

(defn- intent-line [{:keys [intent provenance elements
                            interfaces-without-bindings] :as entry}]
  (str "- " intent " [" (name provenance) "]"
       (entry-marker entry)
       (when (seq elements)
         (str " — elements: " (str/join ", " (map name elements))))
       ;; The #16 coverage seam: per-interface-TYPE binding holes.
       (when (seq interfaces-without-bindings)
         (str " — missing bindings: "
              (str/join ", " (map str interfaces-without-bindings))))))

(defn- duplicates-lines
  "Human rendering of the on-demand duplicates report, high tier first."
  [dups]
  (if (empty? dups)
    ["No duplicate locators — every locator value is owned by one address."]
    (let [{high :high cross :cross-intent} (group-by :tier dups)
          render (fn [{:keys [interface locator owners]}]
                   (str "- " interface " " (pr-str locator) " — "
                        (str/join ", " (map (fn [{:keys [address role file]}]
                                              (str address " (" (name role) ")"
                                                   (when file (str " [" file "]"))))
                                            owners))))]
      (concat
       (when (seq high)
         (cons "High confidence — same intent namespace (real duplicates):"
               (map render high)))
       (when (seq cross)
         (cons "Cross-intent — likely page-scoped coincidence, not a defect:"
               (map render cross)))))))

(defn- render-glossary [proj section uctx sections]
  (let [want? (fn [s] (or (nil? section) (= section s)))
        objects (:objects sections)]
    (str/join
     "\n"
     (concat
      ["# Glossary (resolved)" ""]
      (header-lines proj)
      (when uctx (corpus-lines uctx))
      (when (want? "subjects")
        (concat ["" (str "## Subjects (" (count (:subjects sections)) ")") ""]
                (if (seq (:subjects sections))
                  (map subject-line (sort-by :type (:subjects sections)))
                  ["(none declared)"])))
      (when (want? "verbs")
        (mapcat (fn [{:keys [interface-type verbs source]}]
                  (concat
                   ["" (str "## Verbs — interface type :" (name interface-type)
                            " (" (count verbs) ")")
                    (str "Declared in: "
                         (or source "framework builtin glossary"))
                    ""]
                   (map verb-line (sort-by :verb verbs))))
                (sort-by :interface-type (:verbs sections))))
      (when objects
        (concat ["" (str "## Objects / intents ("
                         (count (:intents objects)) ")") ""]
                (if (seq (:intents objects))
                  (map intent-line (sort-by (comp str :intent) (:intents objects)))
                  ["(no intents configured)"])
                [""
                 (str "Interface-contributed: "
                      (count (get-in objects [:interface-contributed :keys :names]))
                      " key names (closed W3C set, frames with :object-kind :key)"
                      (when-let [ios (seq (get-in objects [:interface-contributed
                                                           :implicit-objects]))]
                        (str "; implicit objects: "
                             (str/join ", " (map #(str ":" (name (:object %)))
                                                 ios)))))]))
      (when (= section "duplicates")
        (concat ["" (str "## Duplicate locators ("
                         (count (:duplicates sections)) " group(s))") ""]
                (duplicates-lines (:duplicates sections))))
      [""]))))

(defn- path-like?
  "Heuristic for a positional arg that is a selection PATH, not a
   (mistyped) section word: contains a slash, names a .feature file, or
   exists on disk. Typos ('verbz') hit none of these and keep v1's loud
   unknown-section error."
  [s]
  (boolean (and s (or (str/includes? s "/")
                      (str/ends-with? s ".feature")
                      (fs/exists? s)))))

(defn- parse-glossary-args
  "[section paths] from the raw argv tail. Section is optional; anything
   after it (or any leading path-like arg) is a selection path."
  [arguments]
  (let [cand (second arguments)
        tail (vec (drop 2 arguments))]
    (cond
      (nil? cand) {:section nil :paths []}
      (contains? glossary-sections cand) {:section cand :paths tail}
      (path-like? cand) {:section nil :paths (into [cand] tail)}
      :else {:bad-section cand})))

(defn glossary-cmd
  "Render the resolved vocabulary, with corpus usage markers (sl-mpu5).
   Returns a CLI exit code.

   `sl glossary [subjects|verbs|objects|duplicates] [path ...]
                [--tags T] [--skip-tags T] [--edn]`

   Paths and tag flags select a corpus subset (the run's own grammar);
   usage wording then weakens to `not referenced within this selection` —
   never a deadness claim. `duplicates` is the on-demand locator-collision
   report (addendum 4) and reads no corpus.

   - 0: listed (or honest Vanilla/no-project message)
   - 1: project found but its accepted truth failed to load
   - 2: malformed invocation or ambiguous config"
  ([arguments options]
   (glossary-cmd arguments options nil))
  ([arguments {:keys [edn] :as options} project-context]
   (let [{:keys [section paths bad-section]} (parse-glossary-args arguments)
         config-source (:config-source project-context)]
     (cond
       bad-section
       (do (binding [*out* *err*]
             (println (str "Unknown glossary section: " bad-section))
             (println (str "Usage: sl glossary [subjects|verbs|objects|duplicates]"
                           " [path ...] [--tags T] [--skip-tags T] [--edn]")))
           2)

       (= :defaults config-source)
       (no-project! project-context edn :glossary)

       (= :error config-source)
       (ambiguous-config! project-context "glossary")

       :else
       (let [proj (projection/build-projection project-context)]
         (cond
           (listing-blocked? proj)
           (projection-failed! proj "list the glossary")

           (= :vanilla (:mode proj))
           (print-vanilla! proj edn :glossary)

           :else
           (let [uctx (when (not= section "duplicates")
                        (usage-context proj project-context
                                       (selection-from paths options)))
                 sections (build-sections proj section uctx)]
             (if edn
               (emit-edn! (glossary-edn proj uctx sections))
               (do (print (render-glossary proj section uctx sections)) (flush)))
             0)))))))

;; -----------------------------------------------------------------------------
;; sl explain NAME — resolution across all vocabulary kinds
;; -----------------------------------------------------------------------------

(defn- parse-name
  "Lenient NAME parsing: accepts `click`, `:click`, `user/alice`,
   `:user/alice`, `Login`. Returns {:input str :kw keyword :bare str}."
  [raw]
  (let [stripped (if (str/starts-with? raw ":") (subs raw 1) raw)]
    {:input raw
     :kw (if (str/includes? stripped "/")
           (let [[ns' n] (str/split stripped #"/" 2)]
             (keyword ns' n))
           (keyword stripped))
     :bare stripped}))

(defn- builtin-resource-path [interface-type]
  (str "shiftlefter/glossaries/verbs-" (name interface-type) ".edn (builtin resource)"))

;; def-site helpers — file-path precision per the Q1 ruling: glossary and
;; intent EDN is read without position info, so the honest def site is the
;; resolved file ("where do I edit this"); true file:line appears only where
;; it actually exists (stepdef :source, below).

(defn- subjects-source [proj]
  (or (->> (:inputs proj)
           (filter #(= :glossary/subjects (:kind %)))
           first
           :path)
      "(no subjects glossary configured)"))

(defn- intents-source [proj]
  (or (get-in proj [:config :glossaries :intents])
      "(no intents directory configured)"))

(defn- match-subjects [proj {:keys [kw]}]
  (concat
   (for [s (:subjects proj)
         :when (= (:type s) kw)]
     {:kind :subject-type
      :name (:type s)
      :provenance (:provenance s)
      :description (:description s)
      :instances (:instances s)
      :singleton? (:singleton? s)
      :def-site (subjects-source proj)})
   (for [i (:instances proj)
         :when (or (= (:qualified i) kw)
                   (and (nil? (namespace kw)) (= (:instance i) kw)))]
     ;; Persona surface (sl-4e91): docstring + effective costume ride the
     ;; projection instance row when present.
     (cond-> {:kind :subject-instance
              :name (:qualified i)
              :type (:type i)
              :provenance (:provenance i)
              :def-site (subjects-source proj)}
       (:description i) (assoc :description (:description i))
       (:wears i) (assoc :wears (:wears i)
                         :wears-source (:wears-source i))))))

(defn- match-verbs [proj cfg {:keys [kw]}]
  (for [{:keys [interface-type verbs source]} (:verbs proj)
        v verbs
        :when (= (:verb v) kw)]
    {:kind :verb
     :name (:verb v)
     :interface-type interface-type
     :provenance (:provenance v)
     :description (:description v)
     :def-site (or source (builtin-resource-path interface-type))
     :frames (:frames v)
     :bindings (bindings-join/verb-interface-bindings
                cfg interface-type (:verb v)
                (map (fn [f] [(:frame f) f]) (:frames v))
                (:stepdefs proj))}))

(defn- match-intents [proj {:keys [bare]}]
  (for [i (:intents proj)
        :when (= (str (:intent i)) bare)]
    {:kind :intent
     :name (:intent i)
     :provenance (:provenance i)
     :root (:root i)
     :elements (:elements i)
     :def-site (intents-source proj)
     :bindings (:bindings i)
     ;; sl-mpu5: the axis is interface TYPES (shared with the listing's #16
     ;; seam) — v1 compared against instance NAMES, which mis-reports for
     ;; renamed instances (the sl-sfb1 trap's sibling; flagged at fit-pass).
     :interfaces-without-bindings (interfaces-without-bindings proj i)}))

(defn- match-keys [{:keys [bare]}]
  ;; key-names is a vector — membership, not contains? (which indexes).
  (when (some #{bare} svo-keys/key-names)
    [{:kind :key
      :name bare
      :provenance :interface-contributed
      :description "W3C KeyboardEvent.key name (closed set; frames with :object-kind :key)"
      :def-site "shiftlefter.svo.keys (framework)"}]))

;; --- did-you-mean -----------------------------------------------------------

(defn- pattern-token-hits
  "Q2 minimal surface-token reverse lookup: exact token match of the input
   against frame :pattern words (e.g. `hovers` → :hover). No index — one
   pass over the projection's frames."
  [proj bare]
  (let [token (str/lower-case bare)]
    (->> (:verbs proj)
         (mapcat (fn [{:keys [verbs]}]
                   (for [v verbs
                         f (:frames v)
                         :when (some #(= token (str/lower-case %))
                                     (re-seq #"[\w-]+" (or (:pattern f) "")))]
                     (:verb v))))
         distinct
         vec)))

(defn- suggestion-candidates
  "All resolvable names, per the sl-6e7p rule: singleton subject types as
   bare keywords; types WITH instances only via their qualified instance
   forms (a bare type-with-instances is unresolvable and must not be
   suggested)."
  [proj]
  (concat
   (keep (fn [s] (when (:singleton? s) (:type s))) (:subjects proj))
   (map :qualified (:instances proj))
   (mapcat (fn [vb] (map :verb (:verbs vb))) (:verbs proj))
   (map (comp keyword str :intent) (:intents proj))
   (map keyword (sort svo-keys/key-names))))

(defn- miss-suggestions [proj {:keys [kw bare]}]
  (let [similar (text/suggest-similar kw (suggestion-candidates proj) 2)
        tokens (pattern-token-hits proj bare)]
    (vec (distinct (concat tokens (if (coll? similar)
                                    similar
                                    (when similar [similar])))))))

;; --- rendering --------------------------------------------------------------

(defn- render-frame [f]
  (str "  frame :" (name (:frame f)) " — schema (not a step): " (:pattern f)
       (when (seq (:args f))
         (str " — args: " (str/join ", " (map name (:args f)))))
       (when (:implicit-object f)
         (str " — implicit object :" (name (:implicit-object f))))))

(defn- render-binding-stepdef [sd]
  (let [src (:source sd)
        example (get-in sd [:metadata :doc/example])]
    (str "      - " (or (:pattern-src sd) "(pattern)")
         (when example (str " — bind with: " example))
         (when (:file src) (str " (" (:file src) ":" (:line src) ")")))))

(defn- render-verb-bindings [{:keys [interface-type interfaces]}]
  (if (empty? interfaces)
    [(str "  bindings: no configured interface has type :" (name interface-type))]
    (mapcat (fn [{:keys [interface bindings]}]
              (cons (str "  bindings on interface " interface ":")
                    (mapcat (fn [{:keys [frame stepdefs missing?]}]
                              (if missing?
                                [(str "    :" (name frame)
                                      " — MISSING: no stepdef implements this"
                                      " frame on this interface")]
                                (cons (str "    :" (name frame) ":")
                                      (map render-binding-stepdef stepdefs))))
                            bindings)))
            interfaces)))

(defn- render-match [m]
  (case (:kind m)
    :subject-type
    (concat [(str "## :" (name (:name m)) " — subject type ["
                  (name (:provenance m)) "]")
             (str "Declared in: " (:def-site m))]
            (when (:description m) [(:description m)])
            [(if (:singleton? m)
               "Singleton (no instances — use the bare type in steps)."
               (str "Instances: "
                    (str/join ", " (map #(str ":" (name (:name m)) "/" (name %))
                                        (:instances m)))))])

    :subject-instance
    (concat
     [(str "## :" (namespace (:name m)) "/" (name (:name m))
           " — subject instance of :" (name (:type m))
           " [" (name (:provenance m)) "]")
      (str "Declared in: " (:def-site m))]
     (when (:description m) [(:description m)])
     (when (:wears m)
       [(str "Wears: :" (name (:wears m))
             (when (= :type-default (:wears-source m))
               (str " (inherited from :" (name (:type m)) ")")))]))

    :verb
    (concat [(str "## :" (name (:name m)) " — verb on interface type :"
                  (name (:interface-type m)) " [" (name (:provenance m)) "]")
             (str "Declared in: " (:def-site m))]
            (when (:description m) [(:description m)])
            (map render-frame (:frames m))
            (render-verb-bindings (:bindings m)))

    :intent
    (concat [(str "## " (:name m) " — intent [" (name (:provenance m)) "]")
             (str "Declared in: " (:def-site m))
             (str "Elements: " (str/join ", " (map name (:elements m))))]
            (map (fn [{:keys [element bindings]}]
                   (str "  " (name element) " — bindings: "
                        (if (seq bindings)
                          (str/join ", " (map (fn [[iface loc]]
                                                (str iface " " (pr-str loc)))
                                              bindings))
                          "NONE")))
                 (:bindings m))
            (when (seq (:interfaces-without-bindings m))
              [(str "MISSING: no bindings for configured interface(s): "
                    (str/join ", " (map str (:interfaces-without-bindings m))))]))

    :key
    [(str "## " (:name m) " — key name [interface-contributed]")
     (:description m)
     (str "Declared in: " (:def-site m))]))

(defn- match-usage
  "The usage block for one explain match (sl-mpu5 addendum 1): subjects
   get the full actor-usage aggregate (per-instance + per-type rollup,
   multi-actor, co-occurrence); verbs and intents the lighter hits block."
  [uctx m]
  (when (= :ok (:status uctx))
    (let [tuples (:tuples uctx)]
      (case (:kind m)
        :subject-type
        (let [hits (filterv #(= (:name m) (:subject-type %)) tuples)]
          (assoc (usage-index/summarize-hits hits)
                 :by-instance (->> hits
                                   (keep :subject)
                                   frequencies
                                   (sort-by (comp - val))
                                   (mapv (fn [[inst n]] {:instance inst :steps n})))))
        :subject-instance
        (usage-index/usage-for-subject tuples (:glossary-map uctx) (:name m))
        :verb
        (usage-index/summarize-hits
         (filterv #(and (= (:interface-type m) (:interface-type %))
                        (= (:name m) (:verb %)))
                  tuples))
        :intent
        (usage-index/summarize-hits
         (filterv #(= (str (:name m)) (:intent-use %)) tuples))
        nil))))

(defn- hit-line [{:keys [file line scenario step-text pattern-src]}]
  (str "  " file ":" line " — " scenario " — \"" step-text "\""
       (when pattern-src (str " (pattern: " pattern-src ")"))))

(defn- hit-block [{:keys [hit-count scenario-count file-count hits]} scope]
  (cons (str "Usage ("
             (if (= :selection scope) "within this selection" "full corpus")
             "): " hit-count " step(s) in " scenario-count " scenario(s), "
             file-count " file(s)")
        (concat (map hit-line (take 10 hits))
                (when (> (count hits) 10)
                  [(str "  … and " (- (count hits) 10) " more (see --edn)")]))))

(defn- render-usage
  "Human usage section for one match; nil-safe (no corpus → no section)."
  [uctx m usage]
  (when usage
    (let [scope (:scope uctx)]
      (case (:kind m)
        :subject-instance
        (concat (hit-block (:instance usage) scope)
                (when-let [t (:type usage)]
                  [(str "Type rollup :" (name (:subject-type usage)) ": "
                        (:hit-count t) " step(s) in " (:scenario-count t)
                        " scenario(s)")])
                [(str "Multi-actor: " (if (:multi-actor? usage) "yes" "no")
                      (when-let [co (seq (:co-occurrence usage))]
                        (str " — co-occurs with "
                             (str/join ", " (map #(str ":" (name (:with %)) " ("
                                                       (:scenarios %) " scenario(s))")
                                                 co)))))])
        :subject-type
        (concat (hit-block usage scope)
                (when-let [bi (seq (:by-instance usage))]
                  [(str "By instance: "
                        (str/join ", " (map #(str ":" (name (:instance %))
                                                  " ×" (:steps %)) bi)))]))
        (:verb :intent)
        (hit-block usage scope)
        nil))))

(defn- explain-edn [proj matches parsed uctx]
  (cond-> {:resolution/version output-version
           :command :explain
           :status (if (seq matches) :ok :miss)
           :name (:input parsed)
           :mode (:mode proj)
           :substrate (:substrate proj)
           :svo-effective (:svo-effective proj)
           :matches (vec matches)
           :suggestions (when (empty? matches) (miss-suggestions proj parsed))}
    uctx (assoc :usage (usage-edn uctx))))

;; --- explain-by-locator (sl-mpu5 addendum 4) --------------------------------

(defn- locator-owners-edn [proj locator-edn owners]
  {:resolution/version output-version
   :command :explain
   :status (if (seq owners) :ok :miss)
   :locator locator-edn
   :mode (:mode proj)
   :substrate (:substrate proj)
   :owners (mapv #(select-keys % [:interface :address :role :intent]) owners)})

(defn- explain-locator!
  "`sl explain '{:css \"#x\"}'` — the reverse lookup: which intent
   addresses own this locator value (structural equality). The authoring/
   sieve-flow check: look before you mint a duplicate."
  [proj raw edn?]
  (let [parsed (try {:ok (edn/read-string raw)}
                    (catch Exception e {:error (ex-message e)}))]
    (cond
      (:error parsed)
      (do (binding [*out* *err*]
            (println (str "Not a readable locator EDN value: " raw))
            (println (str "  " (:error parsed))))
          2)

      :else
      (let [intents (load-intents-index proj)
            files (:intent-files intents)
            owners (->> (locator-index/lookup-locator
                         (locator-index/build intents) (:ok parsed))
                        (mapv #(cond-> %
                                 (get files (:intent %))
                                 (assoc :file (get files (:intent %))))))]
        (cond
          (seq owners)
          (do (if edn?
                (emit-edn! (locator-owners-edn proj (:ok parsed) owners))
                (do (println (str/join "\n" (header-lines proj)))
                    (println)
                    (println (str "## " (pr-str (:ok parsed)) " — locator"))
                    (println "Owned by:")
                    (doseq [{:keys [interface address role file]} owners]
                      (println (str "- " interface " " address " (" (name role) ")"
                                    (when file (str " [" file "]")))))
                    (flush)))
              0)

          :else
          (if edn?
            (do (emit-edn! (locator-owners-edn proj (:ok parsed) owners)) 1)
            (do (binding [*out* *err*]
                  (println (str "No intent owns locator " (pr-str (:ok parsed))
                                " — free to mint.")))
                1)))))))

(defn explain-cmd
  "Explain one vocabulary name — kind(s), def site, provenance, frames,
   per-interface bindings INCLUDING missing ones — plus its corpus USAGE
   (sl-mpu5): where it's referenced, per-instance and per-type, with
   multi-actor and co-occurrence facts for subjects. A `{...}` argument is
   explained AS A LOCATOR: which intent addresses own that EDN value.
   Returns a CLI exit code.

   `sl explain NAME [path ...] [--tags T] [--skip-tags T] [--edn]`
   `sl explain '{:css \"#submit\"}' [--edn]`

   - 0: found (all matching kinds reported)
   - 1: name/locator not found (did-you-mean on stderr; EDN :status :miss)
   - 2: malformed invocation or ambiguous config"
  ([arguments options]
   (explain-cmd arguments options nil))
  ([arguments {:keys [edn] :as options} project-context]
   (let [raw (second arguments)
         paths (vec (drop 2 arguments))
         config-source (:config-source project-context)]
     (cond
       (str/blank? raw)
       (do (binding [*out* *err*]
             (println "Usage: sl explain NAME [path ...] [--tags T] [--edn]"))
           2)

       (= :defaults config-source)
       (no-project! project-context edn :explain)

       (= :error config-source)
       (ambiguous-config! project-context "explain")

       :else
       (let [proj (projection/build-projection project-context)]
         (cond
           (listing-blocked? proj)
           (projection-failed! proj "explain")

           (= :vanilla (:mode proj))
           (print-vanilla! proj edn :explain)

           (str/starts-with? raw "{")
           (explain-locator! proj raw edn)

           :else
           (let [parsed (parse-name raw)
                 uctx (usage-context proj project-context
                                     (selection-from paths options))
                 matches (->> (concat (match-subjects proj parsed)
                                      (match-verbs proj (:config proj) parsed)
                                      (match-intents proj parsed)
                                      (match-keys parsed))
                              (mapv (fn [m]
                                      (if-let [u (match-usage uctx m)]
                                        (assoc m :usage u)
                                        m))))]
             (cond
               (seq matches)
               (do (if edn
                     (emit-edn! (explain-edn proj matches parsed uctx))
                     (do (println (str/join "\n" (header-lines proj)))
                         (when uctx (println (str/join "\n" (corpus-lines uctx))))
                         (doseq [m matches]
                           (println)
                           (println (str/join "\n" (render-match m)))
                           (when-let [u-lines (seq (render-usage uctx m (:usage m)))]
                             (println (str/join "\n" u-lines))))
                         (flush)))
                   0)

               :else
               (if edn
                 (do (emit-edn! (explain-edn proj matches parsed uctx)) 1)
                 (do (binding [*out* *err*]
                       (println (str "Unknown name: " raw))
                       (when-let [sugg (seq (miss-suggestions proj parsed))]
                         (println (str "Did you mean: "
                                       (str/join ", " (map pr-str sugg)) "?"))))
                     1))))))))))
