(ns shiftlefter.runner.tag-disposition
  "Planning-time tag-disposition seam (sl-i608).

   The SINGLE evaluation point where a pickle's tags are read and a
   disposition decided. The runner never touches :pickle/tags and never
   sees a tag name — it threads opaque rules into `disposition` /
   `apply-dispositions` and reads facets off the returned map.

   The disposition is an OPEN MAP, never an enum: today `{:selected? bool}`
   (plus :reason when filtered out). Future consumers add facets additively —
   sl-q9wp's scheduling (`{:schedule {:serial-group ...}}`) and the sl-zsay
   tag-semantics registry (`{:blocked-on ...}`, `{:annotations [...]}`) —
   each pipeline stage reading its own facet from this one seam.

   MVP rules: `{:include #{tag} :exclude #{tag}}` of normalized `@`-prefixed
   strings. Include is OR (any match selects); exclude wins over include;
   an absent/empty key is unconstrained; nil rules = everything selected."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Specs
;; -----------------------------------------------------------------------------

;; The tag grammar (tightened at sl-190x, C1): `@` opens a tag and never
;; appears inside one; `=` is the ONE separator — everything left of the
;; first `=` is the key, everything right is the value, whatever the key;
;; whitespace is structural. `@a@b` and `@=x` are legal to PARSE (Gherkin
;; compliance) but never meant: they are rejected at the CLI boundary and
;; linted in a feature file (`tag-lints`).
(s/def ::tag-name
  (s/with-gen
    (s/and string?
           #(str/starts-with? % "@")
           #(not (str/blank? (subs % 1)))
           #(not (re-find #"\s" %))
           #(not (str/includes? (subs % 1) "@"))
           #(not (str/starts-with? (subs % 1) "=")))
    #(gen/fmap (fn [s] (str "@" s))
               (gen/not-empty (gen/string-alphanumeric)))))

(s/def ::include (s/coll-of ::tag-name :kind set?))
(s/def ::exclude (s/coll-of ::tag-name :kind set?))
(s/def ::rules (s/nilable (s/keys :opt-un [::include ::exclude])))

(s/def ::selected? boolean?)
(s/def ::reason (s/nilable map?))
;; The scheduling facet (sl-q9wp): {:serial? true :reason :tag|:costume|
;; :shared-impl}. This seam only ever produces :reason :tag (the @serial
;; tag); the auto-serial gates in runner/schedule.clj produce the same
;; facet shape from plan inspection — the scheduler consumes one facet
;; and never knows why.
(s/def :shiftlefter.schedule/serial? boolean?)
;; [:hook <name>] (sl-esq): a registered hook carrying :requires-serial
;; auto-serializes every scenario it applies to — the hook owns the hazard,
;; so its name rides the reason for the notice line and derived-scheduling
;; surfacing.
(s/def :shiftlefter.schedule/reason
  (s/or :source #{:tag :costume :shared-impl}
        :hook (s/tuple #{:hook} string?)))
(s/def ::schedule (s/keys :req-un [:shiftlefter.schedule/serial?
                                   :shiftlefter.schedule/reason]))
;; The hooks facet (sl-esq): ordered references to named lifecycle hooks,
;; extracted from @hook=<name> value-tags in PICKLE-TAG ORDER (= feature ->
;; rule -> scenario -> examples-block nesting by pickler construction).
;; Names are unresolved here — the seam knows the `hook` KEY, never which
;; names exist; runner/hooks.clj resolves them against the loaded registry.
(s/def :shiftlefter.hook-ref/name string?)
(s/def :shiftlefter.hook-ref/location (s/nilable map?))
(s/def ::hooks (s/coll-of (s/keys :req-un [:shiftlefter.hook-ref/name]
                                  :opt-un [:shiftlefter.hook-ref/location])
                          :kind vector?))
;; The hold facet (sl-190x): the author's hold claim on a scenario, from
;; the FIRST reserved hold tag in pickle-tag order. `:reason` is the hold
;; kind and the discriminator every :held consumer already reads
;; (:unplannable, :aborted live in the same record); `:tag` the literal
;; token; `:note` the @wip/@skip value, `:ref` the @blocked value — each
;; absent when the tag was bare.
(s/def :shiftlefter.hold/reason #{:wip :skip :blocked})
(s/def :shiftlefter.hold/tag ::tag-name)
(s/def :shiftlefter.hold/note string?)
(s/def :shiftlefter.hold/ref string?)
(s/def :shiftlefter.hold/location (s/nilable map?))
(s/def ::hold (s/keys :req-un [:shiftlefter.hold/reason :shiftlefter.hold/tag]
                      :opt-un [:shiftlefter.hold/note :shiftlefter.hold/ref
                               :shiftlefter.hold/location]))
;; Open map by design (addendum 2b): future facets (:annotations) are
;; additive keys — never a disposition-type enum. :schedule landed with
;; sl-q9wp; :hooks with sl-esq; :hold with sl-190x.
(s/def ::disposition
  (s/keys :req-un [::selected?] :opt-un [::reason ::schedule ::hooks ::hold]))

;; -----------------------------------------------------------------------------
;; CLI-boundary helpers (liberal: accept tags with or without `@`)
;; -----------------------------------------------------------------------------

(defn normalize-tag
  "Trim `s` and prepend `@` if absent. `--tags smoke` == `--tags @smoke`."
  [s]
  (let [t (str/trim s)]
    (if (str/starts-with? t "@") t (str "@" t))))

(defn parse-tag-list
  "Comma-separated tag list -> set of normalized tag names (CLI :parse-fn)."
  [s]
  (into #{}
        (comp (map str/trim) (remove str/blank?) (map normalize-tag))
        (str/split s #",")))

(defn valid-tag-set?
  "Non-empty set of valid tag names (CLI :validate predicate)."
  [tags]
  (boolean (and (set? tags) (seq tags) (every? #(s/valid? ::tag-name %) tags))))

(defn tag-name-problem
  "Why `tag-name` is not a ::tag-name, as a did-you-mean-grade sentence
   (sl-190x C1) — or nil when it is valid. Shared by the CLI validate
   message and the feature-file lint so the two entry points say the
   same thing."
  [tag-name]
  (let [body (if (str/starts-with? tag-name "@") (subs tag-name 1) tag-name)]
    (cond
      (str/blank? body) "a tag needs a name after '@'"
      (re-find #"\s" body) "a tag cannot contain whitespace"
      (str/includes? body "@")
      (str "'@' opens a tag and never appears inside one — did you mean "
           (str/join " " (map #(str "@" %) (remove str/blank? (str/split body #"@"))))
           "?")
      (str/starts-with? body "=")
      (str "'=' separates a key from its value, so the key cannot be empty"
           " — did you mean @<key>" body "?")
      :else nil)))

(defn tag-set-problem
  "The first ::tag-name problem in a CLI tag set, or nil (the CLI
   :validate message — one sentence naming the tag)."
  [tags]
  (some (fn [t] (when-let [p (tag-name-problem t)] (str t ": " p))) (sort tags)))

;; -----------------------------------------------------------------------------
;; execute!-boundary validation (strict: rules must already be normalized)
;; -----------------------------------------------------------------------------

(defn rules-error
  "nil when `rules` is a valid ::rules value, else a structured error map."
  [rules]
  (when-not (s/valid? ::rules rules)
    {:type :tag-filter/invalid
     :message (str "Invalid :tag-filter " (s/explain-str ::rules rules))}))

;; -----------------------------------------------------------------------------
;; The seam
;; -----------------------------------------------------------------------------

(defn- pickle-tag-names
  "The ONLY place :pickle/tags is read for selection. Inheritance
   (feature -> rule -> scenario -> examples-block) is already flattened
   into the pickle by the pickler."
  [pickle]
  (into #{} (map :name) (:pickle/tags pickle)))

(def serial-tag
  "The scheduling escape-hatch tag (sl-q9wp): a tagged scenario is
   individually EXCLUSIVE — no other scenario may run alongside it. At the
   feature level it marks each scenario individually, NOT an atomic block."
  "@serial")

;; -----------------------------------------------------------------------------
;; Value-tags — @<key>=<value> (sl-esq; the sl-zsay key-registry shape
;; arriving early)
;; -----------------------------------------------------------------------------

(def value-tag-keys
  "The value-tag KEY REGISTRY: key string -> disposition facet. Tags stay
   opaque tokens through parser and pickler (Gherkin compliance; dedupe and
   filtering operate on full tokens unchanged); the split on the FIRST '='
   happens HERE and only here. `hook` is the first registered key; the
   hold family's keys (`wip`, `skip`, `blocked`, sl-190x) live in
   `hold-tag-keys` and produce the :hold facet. Unknown keys are INERT —
   ordinary selectable tokens that keep their value (a user's @env=prod
   is `--tags @env` selectable and censuses by key) — until sl-zsay's
   user-extensible registry makes an unknown-key lint fair."
  {"hook" :hooks})

(def hold-tag-keys
  "The CLOSED reserved hold set (sl-190x): key -> {:reason kind :runs? bool
   :value-key k :value-required? bool}. @wip and @skip are never-run holds
   (deselected at planning like tag narrowing); @blocked RUNS expecting
   failure (xfail — a pass is XPASS, the tag's claim is false). @wip's
   value is optional and kept as a note; @skip and @blocked expect one —
   a bare form is legal, held, and warned (reason-free holds rot; the nag
   is the alarm, never a gate)."
  {"wip"     {:reason :wip     :runs? false :value-key :note :value-required? false}
   "skip"    {:reason :skip    :runs? false :value-key :note :value-required? true}
   "blocked" {:reason :blocked :runs? true  :value-key :ref  :value-required? true}})

(defn never-run-hold?
  "True when a :hold facet deselects its scenario (wip, skip); false for a
   hold that runs (blocked)."
  [hold]
  (boolean (some-> (:reason hold) name hold-tag-keys :runs? not)))

(defn split-value-tag
  "Split a tag token on the first '='. `\"@hook=reset-db\"` ->
   `{:key \"hook\" :value \"reset-db\"}`; nil when the token carries no '='.
   The value is everything after the first '=' — a value containing '=' is
   preserved verbatim."
  [tag-name]
  (when-let [idx (str/index-of tag-name "=")]
    (when (str/starts-with? tag-name "@")
      {:key (subs tag-name 1 idx)
       :value (subs tag-name (inc idx))})))

(defn tag-key
  "The KEY of a tag token, `@` dropped: `\"@env=prod\"` -> `\"env\"`,
   `\"@smoke\"` -> `\"smoke\"`. Every tag has a key; only value-tags have a
   value (sl-190x C1: '=' is the one separator, whatever the key)."
  [tag-name]
  (or (:key (split-value-tag tag-name))
      (subs tag-name 1)))

(defn- tag-matches?
  "The selection match rule (sl-190x, J-f): a rule token WITHOUT '=' is a
   bare key and matches every tag of that key (`--tags @blocked` = all
   blocked, `--tags @env` = every @env=*; a plain `@smoke` still matches
   exactly `@smoke`); a rule token WITH '=' matches the full token exactly."
  [rule-token tag-name]
  (if (str/includes? rule-token "=")
    (= rule-token tag-name)
    (= rule-token (str "@" (tag-key tag-name)))))

(defn- matched-rules
  "The subset of `rule-tokens` that match any of `names` (set)."
  [rule-tokens names]
  (into #{} (filter (fn [r] (some #(tag-matches? r %) names))) rule-tokens))

(defn- value-tag-facets
  "Extract registered value-tag facets from a pickle's tags, ORDER
   PRESERVED (pickle-tag order = outer->inner nesting). Each facet is a
   vector of {:name <value> :location <tag location>}. Unknown keys
   contribute nothing. Returns {} when no registered value-tags present."
  [pickle]
  (reduce (fn [acc {:keys [name location]}]
            (if-let [{:keys [key value]} (split-value-tag name)]
              (if-let [facet (value-tag-keys key)]
                (update acc facet (fnil conj []) {:name value :location location})
                acc)
              acc))
          {}
          (:pickle/tags pickle)))

(defn hold-facet
  "The :hold facet for one pickle (sl-190x): the FIRST reserved hold tag
   in pickle-tag order decides — `{:reason :wip|:skip|:blocked :tag
   \"@…\" :note|:ref value :location {…}}`, the value key absent when the
   tag was bare (or `@key=`). nil when the pickle carries no hold tag."
  [pickle]
  (some (fn [{:keys [name location]}]
          (when-let [{:keys [reason value-key]} (hold-tag-keys (tag-key name))]
            (let [value (:value (split-value-tag name))]
              (cond-> {:reason reason :tag name :location location}
                (not (str/blank? value)) (assoc value-key value)))))
        (:pickle/tags pickle)))

(s/fdef hold-facet
  :args (s/cat :pickle map?)
  :ret (s/nilable ::hold))

;; -----------------------------------------------------------------------------
;; Tag lints (sl-190x) — warn-level truth about authored tags; never the
;; exit code. Structured like the config lints (:type :message + the tag
;; and where it sits) so they ride the diagnostics the same way.
;; -----------------------------------------------------------------------------

(s/def :shiftlefter.tag-lint/type
  #{:tag/reason-required :tag/ref-required :tag/value-ignored :tag/malformed})
(s/def :shiftlefter.tag-lint/message string?)
(s/def ::tag-lint (s/keys :req-un [:shiftlefter.tag-lint/type :shiftlefter.tag-lint/message
                                   :shiftlefter.hold/tag]
                          :opt-un [:shiftlefter.hold/location]))
(s/def ::tag-lints (s/coll-of ::tag-lint :kind vector?))

(defn- lint-site
  "The `(scenario \"name\", uri:line)` locator a tag lint ends with."
  [pickle location]
  (str " (scenario " (pr-str (:pickle/name pickle))
       (when-let [uri (:pickle/source-file pickle)]
         (str ", " uri (when-let [l (:line location)] (str ":" l))))
       ")"))

(defn- one-tag-lint
  "The lint for one authored tag, or nil when it is clean."
  [pickle {:keys [name location]}]
  (let [problem (tag-name-problem name)
        {:keys [key value]} (or (split-value-tag name) {:key (tag-key name)})
        hold (hold-tag-keys key)
        site (lint-site pickle location)]
    (when-let [[type message]
               (cond
                 problem
                 [:tag/malformed (str "tag " name ": " problem)]

                 (and hold (:value-required? hold) (str/blank? value))
                 (if (= :skip (:reason hold))
                   [:tag/reason-required
                    (str name " without a reason — reason-free holds rot;"
                         " write @skip=<why>")]
                   [:tag/ref-required
                    (str name " without a reference — write @blocked=<REF>"
                         " so the hold names what it waits on")])

                 (and (= serial-tag (str "@" key)) (some? value))
                 [:tag/value-ignored
                  (str "tag " name ": " serial-tag " takes no value — treated as "
                       serial-tag)])]
      (cond-> {:type type :tag name :message (str message site)}
        location (assoc :location location)))))

(defn tag-lints
  "Every tag lint for one pickle, in pickle-tag order (empty vector when
   clean): a malformed tag (C1), a bare @skip / @blocked (a value was
   expected — the hold still applies), a valued @serial (the value is
   ignored)."
  [pickle]
  (into [] (keep #(one-tag-lint pickle %)) (:pickle/tags pickle)))

(s/fdef tag-lints
  :args (s/cat :pickle map?)
  :ret ::tag-lints)

(defn disposition
  "THE tag-disposition seam: read one pickle's tags, decide its disposition.
   Returns an open map — `{:selected? true}` or
   `{:selected? false :reason {:rule ...}}`. The precedence, stated ONCE:
   exclude wins over include; include is OR; a never-run hold (@wip,
   @skip — sl-190x) deselects UNLESS its tag is named in `:include`
   (Cucumber's --wip convention: `--tags @wip` runs the wip scenarios);
   otherwise selected. Rule tokens match by the bare-key rule
   (`tag-matches?`); empty/absent key = unconstrained; nil rules = every
   pickle selected, holds still applied.

   A pickle tagged @serial additionally carries the scheduling facet
   `{:schedule {:serial? true :reason :tag}}` (sl-q9wp), independent of
   filter rules.

   A pickle carrying @hook=<name> value-tags additionally carries the
   `:hooks` facet (sl-esq): an ordered vector of unresolved
   {:name <value> :location <tag location>} references, in pickle-tag
   order. Registered value-tag keys come from `value-tag-keys`; unknown
   keys stay inert.

   A pickle carrying a reserved hold tag additionally carries the `:hold`
   facet (`hold-facet`), whatever the selection outcome — @blocked's
   facet rides a SELECTED pickle so the runner can stamp :plan/hold and
   the engine can invert the verdict."
  [rules pickle]
  (let [names (pickle-tag-names pickle)
        {:keys [include exclude]} rules
        excluded (when (seq exclude) (not-empty (matched-rules exclude names)))
        hold (hold-facet pickle)
        held? (and hold (never-run-hold? hold)
                   (empty? (matched-rules include #{(:tag hold)})))
        base (cond
               excluded
               {:selected? false :reason {:rule :exclude :matched excluded}}

               (and (seq include) (empty? (matched-rules include names)))
               {:selected? false :reason {:rule :include :wanted include}}

               held?
               {:selected? false :reason {:rule :hold :tag (:tag hold)}}

               :else {:selected? true})]
    (cond-> (merge base (value-tag-facets pickle))
      hold
      (assoc :hold hold)
      (contains? names serial-tag)
      (assoc :schedule {:serial? true :reason :tag}))))

(s/fdef disposition
  :args (s/cat :rules ::rules :pickle map?)
  :ret ::disposition)

(s/def ::selected vector?)
(s/def ::filtered-out vector?)
(s/def ::held (s/coll-of (s/keys :req-un [::hold]) :kind vector?))

(defn apply-dispositions
  "Evaluate the seam once per pickle; partition three ways. Returns
   {:selected [pickles...] :filtered-out [pickles...] :held [{:pickle p
   :hold facet} ...]} — `:filtered-out` = deselected by the rules,
   `:held` = deselected by a never-run hold (sl-190x; the run's :held
   record is built from these). Order preserved, all keys always present."
  [rules pickles]
  (reduce (fn [acc pickle]
            (let [d (disposition rules pickle)]
              (cond
                (:selected? d) (update acc :selected conj pickle)
                (= :hold (get-in d [:reason :rule]))
                (update acc :held conj {:pickle pickle :hold (:hold d)})
                :else (update acc :filtered-out conj pickle))))
          {:selected [] :filtered-out [] :held []}
          pickles))

(s/fdef apply-dispositions
  :args (s/cat :rules ::rules :pickles (s/coll-of map?))
  :ret (s/keys :req-un [::selected ::filtered-out ::held]))
