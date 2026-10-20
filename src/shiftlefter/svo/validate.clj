(ns shiftlefter.svo.validate
  "SVO validation against glossaries and configuration.

   ## Validation Process

   Given an extracted SVO map:
   ```clojure
   {:subject :user/alice
    :verb :click
    :object \"the button\"
    :interface :web}
   ```

   Validates:
   1. Subject is known in glossary (supports qualified `:user/alice`
      and bare `:guest` forms)
   2. Verb is known for the interface type
   3. Interface is defined in config

   Returns:
   ```clojure
   {:valid? true :issues []}
   ;; or
   {:valid? false
    :issues [{:type :svo/unknown-subject
              :subject :user/alcie
              :known [:user :admin :guest :user/alice :user/bob ...]
              :suggestion :user/alice}]}
   ```

   ## Usage

   ```clojure
   (validate-svo glossaries interfaces svo)
   ```"
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [shiftlefter.stepengine.bindings :as bindings]
            [shiftlefter.svo.glossary :as glossary]
            [shiftlefter.svo.keys :as keys]
            [shiftlefter.svo.locator-index :as locator-index]
            [shiftlefter.intent.resolve :as intent-resolve]
            [shiftlefter.intent.state :as intent-state]
            [shiftlefter.text :as text]))

;; -----------------------------------------------------------------------------
;; Specs — SVO Validation Issue Shapes
;; -----------------------------------------------------------------------------

(s/def ::type #{:svo/missing-subject :svo/unknown-subject :svo/unknown-verb
                :svo/unknown-interface :svo/provisioning-failed
                :svo/unknown-object :svo/raw-locator-disallowed
                ;; sl-6wh9: a matched user defstep with no :svo metadata —
                ;; nothing ran through the type system for that step
                :stepdef/untyped
                ;; :key slots (sl-2sj6) — framework-owned closed set
                :svo/unknown-key :svo/invalid-key-literal
                ;; Scenario data plane (sl-yh7) — ride the same issue channel
                :bindings/consumed-without-producer :bindings/invalid-pattern
                :bindings/unnamed-only-groups :bindings/produced-never-consumed
                ;; sl-gwru: producer marker in the assert-only matcher frame
                :bindings/named-groups-in-assert-only
                ;; sl-ectw: the declared shape in the matcher slot
                :bindings/invalid-matcher-slot :bindings/unknown-shape-ref
                :bindings/element-has-no-shape
                :bindings/shape-no-binding-for-interface
                :bindings/captures-without-shape :bindings/shape-and-inline
                ;; Costume lints (sl-1206) — plan-time front door of the
                ;; costume doctrine (stepengine.costume-lint); xbm runtime
                ;; guard stays as the belt
                :costume/wrong-interface :costume/shared-wears
                :costume/undeclared})
(s/def ::subject keyword?)
(s/def ::verb keyword?)
(s/def ::interface keyword?)
(s/def ::interface-type keyword?)
(s/def ::known (s/coll-of keyword?))
(s/def ::suggestion (s/nilable (s/or :keyword keyword? :string string?)))

;; sl-u2r0: where the suggested thing is declared (with a :suggestion) or
;; where the missing thing would be (without one). :line only when the
;; loader's index pass vouched for the file; :entry = the suggested
;; entry; :path = the config path for config-declared things.
(s/def ::file string?)
(s/def ::line pos-int?)
(s/def ::def-site (s/keys :req-un [::file] :opt-un [::line]))

(s/def ::svo-issue
  (s/keys :req-un [::type]
          :opt-un [::subject ::verb ::interface ::interface-type
                   ::known ::suggestion ::def-site]))

;; -----------------------------------------------------------------------------
;; Def-sites (sl-u2r0)
;; -----------------------------------------------------------------------------

(defn- entry-def-site
  "The :def-site for a vocabulary issue: the suggested entry's recorded
   site (`sites` = `{kw {:file :line}}`) tagged with `:entry`, else the
   section's file alone (`fallback-file`) — where the missing thing would
   be declared. nil when neither is known."
  [sites suggestion fallback-file]
  (or (when-let [site (and suggestion (get sites suggestion))]
        (assoc site :entry suggestion))
      (when fallback-file {:file fallback-file})))

(defn- verb-fallback-file
  "The file a verb for `interface-type` would be declared in: the mounted
   project file when one exists, else the framework's builtin resource for
   that type (named as the sl-explain projection names it)."
  [glossary interface-type]
  (or (get-in glossary [:sources :verbs interface-type])
      (when (contains? (:verbs glossary) interface-type)
        (glossary/builtin-resource-label
         (str "shiftlefter/glossaries/verbs-" (name interface-type) ".edn")))))

(s/def ::valid? boolean?)
(s/def ::issues (s/coll-of map?))

(s/def ::validation-result
  (s/keys :req-un [::valid? ::issues]))

;; -----------------------------------------------------------------------------
;; Validation Functions
;; -----------------------------------------------------------------------------

(defn- validate-subject
  "Validate subject against glossary.
   Uses all-subject-forms for the :known hint and Levenshtein suggestions —
   resolvable forms only: qualified instances like :user/alice plus singleton
   type keys; bare types-with-instances are excluded (sl-6e7p).
   Returns issue map or nil if valid."
  [glossary subject]
  (if (nil? subject)
    {:type :svo/missing-subject
     :message "SVO step resolved without a subject"}
    (when-not (glossary/known-subject? glossary subject)
      (let [all-forms (glossary/all-subject-forms glossary)
            suggestion (text/suggest-similar subject all-forms 2)
            def-site (entry-def-site (get-in glossary [:def-sites :subjects])
                                     suggestion
                                     (get-in glossary [:sources :subjects]))]
        (cond-> {:type :svo/unknown-subject
                 :subject subject
                 :known all-forms
                 :suggestion suggestion}
          def-site (assoc :def-site def-site))))))

(defn- validate-verb
  "Validate verb against glossary for interface type.
   Returns issue map or nil if valid."
  [glossary interface-type verb]
  (when verb
    (when-not (glossary/known-verb? glossary interface-type verb)
      (let [known (glossary/known-verbs glossary interface-type)
            suggestion (text/suggest-similar verb known 2)
            def-site (entry-def-site (get-in glossary [:def-sites :verbs interface-type])
                                     suggestion
                                     (verb-fallback-file glossary interface-type))]
        (cond-> {:type :svo/unknown-verb
                 :verb verb
                 :interface-type interface-type
                 :known known
                 :suggestion suggestion}
          def-site (assoc :def-site def-site))))))

(defn- validate-interface
  "Validate interface against config. `config-path` (sl-u2r0) is where
   interfaces are declared — the def-site of this issue, under
   [:interfaces]. Returns issue map or nil if valid."
  [interfaces interface-name config-path]
  (when interface-name
    (when-not (contains? interfaces interface-name)
      (let [known (vec (keys interfaces))
            suggestion (text/suggest-similar interface-name known 2)]
        (cond-> {:type :svo/unknown-interface
                 :interface interface-name
                 :known known
                 :suggestion suggestion}
          config-path (assoc :def-site (cond-> {:file config-path :path [:interfaces]}
                                         suggestion (assoc :entry suggestion))))))))

(defn raw-locator?
  "Check if the object string is a raw EDN locator (starts with {)."
  [object-str]
  (and (string? object-str)
       (str/starts-with? object-str "{")))

(defn object-kind
  "Look up the accepted value kind of the O slot for the SVO's verb frame.

   Frames may declare :object-kind in the verb glossary (sl-rlxa);
   absent (or no frame on the SVO) defaults to :intent — the object is
   an intent reference. :location slots accept literal URLs."
  [glossary interface-type {:keys [verb frame]}]
  (or (when (and interface-type verb frame)
        (get-in glossary [:verbs interface-type verb :frames frame :object-kind]))
      :intent))

(defn location-refs-frame?
  "True when the SVO's verb frame declares :location-refs? — a :location slot
   that additionally accepts bare PascalCase named-location refs (sl-3jr4)."
  [glossary interface-type {:keys [verb frame]}]
  (boolean
   (when (and interface-type verb frame)
     (get-in glossary [:verbs interface-type verb :frames frame :location-refs?]))))

(defn quoted-capture?
  "True when the capture is a quoted literal ('…') — captured WITH its
   quotes (sl-yh7 capture-with-quotes; the exec seam strips them)."
  [s]
  (and (string? s)
       (>= (count s) 2)
       (str/starts-with? s "'")
       (str/ends-with? s "'")))

(defn- validate-key-object
  "Validate a :key-slot object (sl-2sj6). ALWAYS-ON — no leniency knob.

   The :unknown-object knob exists for PROJECT-owned vocabulary: while an
   app is being built, an unresolved intent ref may be tomorrow's glossary
   entry, so leniency has a purpose. The key set is FRAMEWORK-owned and
   closed — there is no world in which `Entr` becomes valid by editing a
   project — so unconditional error is the only honest behavior (same
   rationale as malformed-matcher errors; Chair ruling, sl-2sj6).
   Framework-owned closed sets → always-on; project-owned vocabulary →
   knobbed. Issues carry :severity :error directly for the same reason.

   Quoted single char = press that character (the shortcut case). Quoted
   multi-char = plan error pointing at fills (text entry is not a
   key-press). Unquoted = must be in the closed W3C set, did-you-mean on
   miss."
  [object-str]
  (if (quoted-capture? object-str)
    ;; Length check on the UNDOUBLED interior (sl-jlqz): '''' is the
    ;; one-character apostrophe key, not text.
    (when (not= 1 (count (bindings/undouble-quotes
                          (subs object-str 1 (dec (count object-str))))))
      {:type :svo/invalid-key-literal
       :object object-str
       :severity :error
       :message (str "presses takes a single key, not text: " object-str
                     " — to enter text, use fills (S fills O with '…')")})
    (when-not (keys/known-key? object-str)
      (let [suggestion (text/suggest-similar (keyword object-str)
                                        (map keyword keys/key-names) 2)]
        (cond-> {:type :svo/unknown-key
                 :object object-str
                 :known (mapv keyword keys/key-names)
                 :severity :error
                 :message (cond-> (str "Unknown key name: " object-str
                                       ". Key names are W3C KeyboardEvent.key values"
                                       " (Enter, Tab, ArrowUp, …); single characters"
                                       " must be quoted ('g')")
                            suggestion (str ". Did you mean: " (name suggestion) "?"))}
          suggestion (assoc :suggestion suggestion))))))

(defn- ref-shaped-location-literal
  "Case-4 warning of the sl-s04n hint family: a QUOTED capture in a
   ref-accepting :location slot whose interior provably names a located
   intent — the author almost certainly meant the named location, unquoted.

   Deliberately silent unless the interior resolves: quoted = literal,
   always, and what a location literal DOES at runtime is sl-34ec's ruling —
   this warning asserts nothing about literal semantics, it only flags the
   provable authored-a-ref-in-quotes case. Sits behind the enforcement knob
   with its :location-slot siblings (Chair ruling 07-22: project-owned
   vocabulary is knobbed; :off is an explicit decline of ref-resolution
   services). Carries :severity :warn directly — never blocks.

   Returns a :warn issue map or nil."
  [object-str interface]
  (let [interior (subs object-str 1 (dec (count object-str)))]
    (when (intent-resolve/location-ref? interior)
      (try
        (let [intents (intent-state/get-intents)]
          (when (nil? (intent-resolve/validate-location-static
                       intents interior interface))
            {:type :svo/ref-shaped-location-literal
             :object object-str
             :interface interface
             :severity :warn
             :message (str "'" interior "' is a quoted literal, but " interior
                           " is a named location. Did you mean " interior
                           " (unquoted)?")}))
        (catch Exception _
          ;; If intents not loaded yet, skip the warning
          nil)))))

;; -----------------------------------------------------------------------------
;; Intent-ref did-you-mean + def-sites (sl-u2r0)
;; -----------------------------------------------------------------------------

(defn- intents-dir
  "Where intents live, from the loaded structure: the directory the loader
   stamped (`:intents-dir`, sl-u2r0), else the parent of any intent file,
   else nil."
  [intents]
  (or (:intents-dir intents)
      (some-> (:intent-files intents) vals first str
              (str/replace #"/[^/]*$" ""))))

(defn- intent-site
  "`{:file :line}` of an intent, or its part (`part-name` under `:elements`
   / `:collections`), from the loader's :def-sites; file-only when the
   line is unknown; nil for an unknown intent."
  ([intents intent-name] (intent-site intents intent-name nil))
  ([intents intent-name part-name]
   (when-let [site (get-in intents [:def-sites intent-name])]
     (let [part-line (when part-name
                       (or (get-in site [:elements part-name])
                           (get-in site [:collections part-name])))]
       (cond-> {:file (:file site)}
         (or part-line (:line site)) (assoc :line (or part-line (:line site))))))))

(defn- suggest-name
  "Nearest of `candidates` (strings) to `target` (a string) within 2 edits,
   as a string; nil when none."
  [target candidates]
  (some-> (text/suggest-similar target (vec candidates) 2) str))

(defn- unresolvable-ref-issue
  "The :svo/unknown-object issue for a ref-shaped object that the static
   walk could not resolve (sl-u2r0, routed item 1 of the 6wh9 groom): an
   ERROR on its own severity (the run would fail at
   :browser/intent-resolve-error — the machine knew before the run), with
   the did-you-mean and def-site the walk's structured error allows:

   - :unknown-intent — the nearest loaded intent; def-site its file:line
     (else the intents directory)
   - :unknown-segment — the nearest element/collection of the intent the
     segment was looked up in; def-site that part's line (else the intent)
   - :no-binding-for-interface (Sol 32) — no suggestion; :known names the
     interfaces that DO bind the element; def-site the element's line
   - :element-has-children — def-site the intent's line"
  [object-str interface intents {:keys [type message segment intent available-interfaces]}]
  (let [[suggestion def-site]
        (case type
          :unknown-intent
          (let [s (suggest-name intent (:intents intents))]
            [s (or (when s (intent-site intents s))
                   (when-let [dir (intents-dir intents)] {:file dir}))])
          :unknown-segment
          (let [region (get-in intents [:regions intent])
                names (concat (:elements region) (map name (keys (:collections region))))
                s (suggest-name segment names)]
            [s (intent-site intents intent s)])
          :no-binding-for-interface
          [nil (intent-site intents intent segment)]
          [nil (intent-site intents intent)])
        ;; The suggestion as the author would write it: an intent alone, or
        ;; the full dotted ref for a part.
        full (when suggestion
               (if (= type :unknown-intent) suggestion (str intent "." suggestion)))]
    (cond-> {:type :svo/unknown-object
             :object object-str
             :interface interface
             :severity :error
             :message (cond-> message
                        full (str ". Did you mean: " full "?"))}
      full (assoc :suggestion full)
      (seq available-interfaces) (assoc :known (vec available-interfaces))
      def-site (assoc :def-site (cond-> def-site full (assoc :entry full))))))

(defn ref-shaped?
  "The C2 heuristic (sl-u2r0): does `s` LOOK like an intent reference —
   an uppercase start and no whitespace — even if it does not parse? A
   ref-shaped object that fails to resolve is a plan error by the
   language's own rule (a frame-less object defaults to :intent); free
   text (a space anywhere, or a lowercase start) is content that belongs
   on a frame declaring `:object-kind :text`, and stays at the knob's
   level with that advice."
  [s]
  (boolean (and (string? s)
                (re-find #"^[A-Z]" s)
                (not (re-find #"\s" s)))))

(defn- free-text-object-issue
  "A non-ref-shaped object on a slot that expects an intent ref: the
   knob-level issue (no :severity — the level decides) whose message
   names the fix, plus a did-you-mean when capitalizing the first letter
   would resolve (`login.submit` → `Login.submit`)."
  [object-str interface intents parse-message]
  (let [capitalized (when (re-find #"^[a-z]" object-str)
                      (str (str/upper-case (subs object-str 0 1)) (subs object-str 1)))
        resolves? (fn [s]
                    (let [p (intent-resolve/parse-intent-ref s)]
                      (and (:ok p)
                           (nil? (intent-resolve/validate-path-static intents (:ok p) interface)))))
        suggestion (when (and capitalized (resolves? capitalized)) capitalized)]
    (cond-> {:type :svo/unknown-object
             :object object-str
             :interface interface
             :message (str parse-message
                           (if suggestion
                             (str ". Did you mean: " suggestion "?")
                             (str ". This slot expects an intent reference"
                                  " (Capitalized.dotted); for free text, declare"
                                  " :object-kind :text on the verb's frame")))}
      suggestion (assoc :suggestion suggestion
                        :def-site (cond-> (or (intent-site intents
                                                           (:intent (:ok (intent-resolve/parse-intent-ref suggestion))))
                                              {})
                                    true (assoc :entry suggestion))))))

(defn- raw-locator-issue
  "The :svo/raw-locator-disallowed issue at the :raw-locator level (sl-u2r0,
   routed item 2 of the 6wh9 groom): no :severity — the level decides
   (:warn or :error; :off never reaches here). Did-you-mean via the
   locator index: when the same locator EDN is bound under an intent for
   this interface, name that intent ref and its def-site."
  [object-str interface intents]
  (let [parsed (try (edn/read-string object-str) (catch Exception _ ::unparseable))
        hit (when (and intents (not= ::unparseable parsed))
              (->> (locator-index/lookup-locator (locator-index/build intents) parsed)
                   (filter #(= interface (:interface %)))
                   first))
        suggestion (:address hit)
        def-site (when hit
                   (intent-site intents (:intent hit)
                                (when (= :element (:role hit))
                                  (subs (:address hit) (inc (count (:intent hit)))))))]
    (cond-> {:type :svo/raw-locator-disallowed
             :object object-str
             :interface interface
             :message (str "Raw locator " object-str " — reference the element by"
                           " its intent name instead"
                           (when suggestion (str ". Did you mean: " suggestion "?")))}
      suggestion (assoc :suggestion suggestion)
      def-site (assoc :def-site (cond-> def-site suggestion (assoc :entry suggestion))))))

(defn- validate-object
  "Validate object (locator) against intent glossary.

   Enforcement modes (the :unknown-object knob):
   - :error — unknown intents disallowed (:strict is a deprecated alias,
     sl-femd)
   - :warn — a FREE-TEXT object on an intent slot warns with the
     :object-kind :text advice; a REF-SHAPED object that does not resolve
     is an error regardless (sl-u2r0 — carries :severity :error itself)
   - :off — no validation

   Raw locators (sl-u2r0) ride the separate `raw-level` (the :raw-locator
   kind): :off = allowed silently, :warn / :error = the
   :svo/raw-locator-disallowed issue with a locator-index did-you-mean.

   Slots whose frame declares :object-kind :location accept literal values
   (URLs) — never an :unknown-object issue (sl-rlxa). When the frame also
   declares :location-refs? (sl-3jr4), a BARE PascalCase token is a
   named-location intent ref and IS statically validated (the classifier is
   `intent-resolve/location-ref?`, shared with runtime resolution so planning
   and execution can't drift). QUOTED captures ('…') never classify as refs —
   quoted = literal, always (sl-iseq). Frames without :location-refs?
   (the exactly assertion) keep the skip-everything behavior.

   :text slots (sl-2sj6) are content-only — quoted literal or {binding}
   token, admitted by the stepdef pattern; nothing here to validate (token
   plumbing is the bindings lint's job). :key slots validate against the
   framework-owned closed set UNCONDITIONALLY — see validate-key-object.

   Returns issue map or nil if valid."
  [object-str interface enforcement kind location-refs? raw-level]
  (when (and object-str (string? object-str))
    (cond
      ;; :text — content-only slot, nothing to validate statically.
      (= kind :text)
      nil

      ;; :key — framework-owned closed set, always-on (no enforcement gate).
      (= kind :key)
      (validate-key-object object-str)

      ;; Raw locator on an intent slot (sl-u2r0): its own level, checked
      ;; before the :unknown-object gate — the two knobs are independent.
      (and (not= kind :location) (raw-locator? object-str))
      (when (#{:warn :error :strict} raw-level)
        (raw-locator-issue object-str interface
                           (try (intent-state/get-intents) (catch Exception _ nil))))

      (= enforcement :off)
      nil

      ;; :location slot — literals pass (but see the ref-shaped-literal warn
      ;; below); bare-name refs validate only on ref-accepting frames.
      (= kind :location)
      (when location-refs?
        (cond
          (intent-resolve/location-ref? object-str)
          (try
            (let [intents (intent-state/get-intents)
                  loc-error (intent-resolve/validate-location-static
                             intents object-str interface)]
              (when loc-error
                ;; Did-you-mean (sl-q81m): suggest the nearest LOCATED intent —
                ;; suggesting an unlocated one would just be a second error.
                ;; Fires for all three static location error types (sl-s04n
                ;; widened it from :unknown-intent only): a near-name located
                ;; intent is the likely fix for :no-location* too.
                ;; sl-u2r0: an unresolvable named-location ref fails at run
                ;; (resolve-location) — an error on its own severity, with
                ;; the suggested intent's def-site.
                (let [suggestion (when (#{:unknown-intent :no-location
                                          :no-location-for-interface}
                                        (:type loc-error))
                                   (text/suggest-similar
                                    (keyword object-str)
                                    (map keyword (intent-resolve/located-intents
                                                  intents interface))
                                    2))
                      def-site (or (when suggestion (intent-site intents (name suggestion)))
                                   (intent-site intents object-str)
                                   (when-let [dir (intents-dir intents)] {:file dir}))]
                  (cond-> {:type :svo/unknown-object
                           :object object-str
                           :interface interface
                           :severity :error
                           :message (cond-> (:message loc-error)
                                      suggestion
                                      (str ". Did you mean: " suggestion "?"))}
                    suggestion (assoc :suggestion suggestion)
                    def-site (assoc :def-site (cond-> def-site
                                                suggestion (assoc :entry suggestion)))))))
            (catch Exception _
              ;; If intents not loaded yet, skip validation
              nil))

          (quoted-capture? object-str)
          (ref-shaped-location-literal object-str interface)))

      ;; Intent reference check — supports flat AND nested (multi-segment) refs.
      :else
      (let [parse-result (intent-resolve/parse-intent-ref object-str)
            intents (try (intent-state/get-intents) (catch Exception _ nil))]
        (cond
          ;; Free text on an intent slot (J-b): the knob's level + the fix.
          (and (:error parse-result) (not (ref-shaped? object-str)))
          (free-text-object-issue object-str interface intents
                                  (-> parse-result :error :message))

          ;; Ref-shaped but malformed: unresolvable by construction.
          (:error parse-result)
          (cond-> {:type :svo/unknown-object
                   :object object-str
                   :interface interface
                   :severity :error
                   :message (-> parse-result :error :message)}
            (intents-dir intents) (assoc :def-site {:file (intents-dir intents)}))

          ;; Valid syntax — statically walk the path against the schema (no
          ;; browser): intent known -> each non-last name is a collection ->
          ;; last is a collection or an element with a binding for this
          ;; interface. Intents not loaded => nothing to validate against.
          (nil? intents)
          nil

          :else
          (when-let [path-error (intent-resolve/validate-path-static
                                 intents (:ok parse-result) interface)]
            (unresolvable-ref-issue object-str interface intents path-error)))))))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn validate-svo
  "Validate an SVO map against glossaries and interface config.

   Parameters:
   - glossary: Loaded glossary from `svo.glossary/load-all-glossaries`
   - interfaces: Interface config map from `config/get-interfaces`
   - svo: Extracted SVO map {:subject :verb :object :interface}
   - opts: Optional enforcement options:
     - :unknown-object — :error, :warn, or :off (:strict = deprecated alias
       for :error). The :off here is only the no-opts fallback — the
       effective default is computed by shiftlefter.config/load-config (:warn
       when an :intents glossary is configured, sl-femd).
     - :raw-locator — :error, :warn, or :off (sl-u2r0): the raw-locator
       kind's level, independent of :unknown-object (same conditional
       default). :off here is the no-opts fallback.
     - :config-path — the config file (sl-u2r0): the def-site an
       :svo/unknown-interface issue names.

   Returns:
   - {:valid? true :issues []} if all checks pass
   - {:valid? false :issues [...]} if any check fails

   Issue types:
   - :svo/unknown-subject — subject not in glossary
   - :svo/unknown-verb — verb not known for interface type
   - :svo/unknown-interface — interface not defined in config
   - :svo/unknown-object — intent reference not found
   - :svo/raw-locator-disallowed — raw locator used in strict mode
   - :svo/unknown-key — :key-slot name outside the closed W3C set
     (always-on, carries :severity :error — sl-2sj6)
   - :svo/invalid-key-literal — quoted multi-char in a :key slot
     (always-on, carries :severity :error — sl-2sj6)

   Each issue includes :known (alternatives) and :suggestion (typo fix)."
  ([glossary interfaces svo]
   (validate-svo glossary interfaces svo nil))
  ([glossary interfaces svo opts]
   (let [{:keys [subject verb object interface]} svo
         ;; The SVO's :interface is the resolved execution INSTANCE
         ;; (sl-s2oa); its configured :type selects vocabulary and intent
         ;; artifacts. Unconfigured values read as types directly (the
         ;; type-as-name fallback — unconfigured paths unchanged).
         interface-def (get interfaces interface)
         interface-type (:type interface-def)
         effective-type (or interface-type interface)
         ;; Object enforcement — :off is the no-opts safety net, NOT the
         ;; default: load-config owns the effective default (sl-femd).
         object-enforcement (get opts :unknown-object :off)
         raw-level (get opts :raw-locator :off)
         ;; Collect issues
         issues (keep identity
                       [(validate-subject glossary subject)
                        ;; Only validate verb if interface is known
                        (when interface-type
                          (validate-verb glossary interface-type verb))
                        (validate-interface interfaces interface (:config-path opts))
                        ;; Validate object/locator (when enforcement is not :off
                        ;; and the frame's O slot expects an intent reference —
                        ;; or a named-location ref on a :location-refs? frame).
                        ;; Intent bindings/locations AND the frame lookups are
                        ;; TYPE-keyed (sl-s2oa), so every lookup crosses
                        ;; instance -> effective type — the frame helpers
                        ;; nil-guard, so passing the raw configured type would
                        ;; silently default unconfigured type-as-name values
                        ;; to :intent (fit-pass condition, 2026-08-13).
                        (validate-object object effective-type object-enforcement
                                         (object-kind glossary effective-type svo)
                                         (location-refs-frame? glossary effective-type svo)
                                         raw-level)])]
     {:valid? (empty? issues)
      :issues (vec issues)})))

(defn valid?
  "Returns true if SVO is valid according to validate-svo."
  [glossary interfaces svo]
  (:valid? (validate-svo glossary interfaces svo)))

;; -----------------------------------------------------------------------------
;; Stepdef-vs-Glossary Cross-Check (Tier 2) — sl-hse
;; -----------------------------------------------------------------------------
;;
;; Validate every registered stepdef's :svo metadata against the loaded
;; glossary: the verb must exist on the declared interface, the frame
;; must exist on the verb, and the step's :args keys must match the
;; frame's declared :args. Stepdefs without :svo metadata or whose
;; interface isn't loaded in the glossary are skipped.
;;
;; This is a once-per-compile check, distinct from validate-svo (which
;; runs per matched feature step against extracted SVOs).

(defn- format-keyword-list
  "Render a sequence of keywords as ':a, :b, :c' for error messages."
  [kws]
  (str/join ", " (map #(str ":" (name %)) (sort kws))))

(defn- source-loc
  "Format a stepdef source map as 'file.clj:42'."
  [{:keys [file line]}]
  (str (or file "?") ":" (or line "?")))

(defn- interface-label
  "Render an interface for an issue message: ':name' when the name IS the
   type, ':name (type :t)' when they differ."
  [interface interface-type]
  (str ":" (name interface)
       (when (not= interface interface-type)
         (str " (type :" (name interface-type) ")"))))

(defn- check-one-stepdef
  "Validate a single stepdef against the glossary. Returns nil if valid
   or no :svo metadata; otherwise a stepdef-issue map.

   The glossary's :verbs map is keyed by interface TYPE, and since
   sl-s2oa the stepdef's :interface metadata IS the TYPE — the lookup is
   direct. This retires sl-sfb1's name→type resolution here, and its
   'do not simplify' guard with it: that guard's premise (stepdefs
   declaring config instance NAMES, so a renamed project silently
   skipped the check) no longer holds — instance names live in the
   [name] step annotation now, never in stepdef metadata. Issue maps
   keep both :interface and :interface-type keys for shape stability;
   they are equal under the s2oa model."
  [stepdef glossary _interfaces]
  (let [{:keys [metadata source]} stepdef
        {:keys [interface svo]} metadata
        {:keys [verb frame args]} svo]
    (cond
      ;; Skip: no :svo, no :interface, or no :verb to check
      (or (nil? svo) (nil? interface) (nil? verb))
      nil

      :else
      (let [interface-type  interface
            interface-verbs (get-in glossary [:verbs interface-type])
            verb-entry      (get interface-verbs verb)]
        (cond
          ;; Interface not loaded in glossary — skip (e.g., :sms stepdef
          ;; in a config that only loads :web).
          (nil? interface-verbs)
          nil

          ;; Unknown verb under this interface
          (nil? verb-entry)
          {:type           :stepdef/unknown-verb
           :stepdef-id     (:stepdef/id stepdef)
           :source         source
           :interface      interface
           :interface-type interface-type
           :verb           verb
           :known-verbs    (sort (keys interface-verbs))
           :message        (str "Step at " (source-loc source)
                                " declares unknown verb :" (name verb)
                                " on interface " (interface-label interface interface-type)
                                "; known :" (name interface-type) " verbs: "
                                (format-keyword-list (keys interface-verbs)))}

          ;; Step :svo missing :frame (Tier 1 will catch eventually, but
          ;; defensive — bail out cleanly so we don't NPE downstream).
          (nil? frame)
          {:type           :stepdef/missing-frame
           :stepdef-id     (:stepdef/id stepdef)
           :source         source
           :interface      interface
           :interface-type interface-type
           :verb           verb
           :message        (str "Step at " (source-loc source)
                                " declares :verb :" (name interface-type) "/" (name verb)
                                " but no :frame; required since sl-hse")}

          ;; Frame not declared on this verb
          (not (contains? (:frames verb-entry) frame))
          {:type           :stepdef/unknown-frame
           :stepdef-id     (:stepdef/id stepdef)
           :source         source
           :interface      interface
           :interface-type interface-type
           :verb           verb
           :frame          frame
           :known-frames   (sort (keys (:frames verb-entry)))
           :message        (str "Step at " (source-loc source)
                                " uses unknown frame :" (name frame)
                                " on verb :" (name interface-type) "/" (name verb)
                                "; known frames: "
                                (format-keyword-list (keys (:frames verb-entry))))}

          ;; Args keyset mismatch
          :else
          (let [frame-args  (set (:args (get-in verb-entry [:frames frame])))
                step-args   (set (keys (or args {})))
                missing     (vec (sort (remove step-args frame-args)))
                unexpected  (vec (sort (remove frame-args step-args)))]
            (when (or (seq missing) (seq unexpected))
              {:type           :stepdef/args-mismatch
               :stepdef-id     (:stepdef/id stepdef)
               :source         source
               :interface      interface
               :interface-type interface-type
               :verb           verb
               :frame          frame
               :expected-args  (vec (sort frame-args))
               :provided-args  (vec (sort step-args))
               :missing-args   missing
               :extra-args     unexpected
               :message
               (str "Step at " (source-loc source)
                    " — frame :" (name interface-type) "/" (name verb) "/" (name frame)
                    " expects args ["
                    (format-keyword-list frame-args)
                    "]; got ["
                    (format-keyword-list step-args)
                    "]"
                    (when (seq missing)
                      (str " (missing: " (format-keyword-list missing) ")"))
                    (when (seq unexpected)
                      (str " (unexpected: " (format-keyword-list unexpected) ")")))})))))))

(defn validate-stepdefs-against-glossary
  "Walk all stepdefs; verify each :svo's verb/frame/args are declared
   in the loaded glossary. Returns a vector of issues; empty when valid.

   Since sl-s2oa each stepdef's :interface metadata IS the interface
   TYPE, indexing the type-keyed glossary directly (`interfaces` stays
   in the signature for callers; see check-one-stepdef for why sfb1's
   name→type hop is retired). Stepdefs whose type has no loaded
   glossary are skipped (interface-less stepdefs and cross-interface
   tooling are unaffected)."
  [stepdefs glossary interfaces]
  (->> stepdefs
       (keep #(check-one-stepdef % glossary interfaces))
       vec))

;; -----------------------------------------------------------------------------
;; Error Message Formatting
;; -----------------------------------------------------------------------------

(defn- format-keyword
  "Format a keyword for display, preserving namespace."
  [kw]
  (str ":" (text/keyword->str kw)))

(defn- format-known-list
  "Format a list of known values for display.
   Shows first N items, indicates if more exist."
  [known max-display]
  (let [items (take max-display known)
        remaining (- (count known) max-display)
        formatted (str/join ", " (map format-keyword items))]
    (if (pos? remaining)
      (str formatted ", ... (+" remaining " more)")
      formatted)))

(defn- format-suggestion
  "Format a typo suggestion if present."
  [suggestion]
  (when suggestion
    (str "Did you mean: " (if (keyword? suggestion)
                            (format-keyword suggestion)
                            (str suggestion))
         "?")))

(defn- format-def-site
  "The def-site line (sl-u2r0): `Declared in: file:line` when the issue
   carries a suggestion (the site is the suggested entry's), else
   `Declare it in: file` (the site is where the missing thing would be);
   a config path adds its key path. nil without a def-site."
  [{:keys [suggestion def-site]}]
  (when-let [{:keys [file line path]} def-site]
    (str (if suggestion "Declared in: " "Declare it in: ")
         file
         (when line (str ":" line))
         (when path (str " under " (pr-str path))))))

(defn- def-site-suffix
  "The def-site line as a message suffix, or nil."
  [issue]
  (when-let [line (format-def-site issue)]
    (str "\n       " line)))

(defn format-unknown-subject
  "Format an :svo/unknown-subject issue as a human-readable error message.

   Example output:
   ERROR: Unknown subject :alcie in step \"When Alcie clicks the button\"
          at features/login.feature:12
          Known subjects: :alice, :admin, :guest, :system/test-setup
          Did you mean: :alice?"
  [issue]
  (let [{:keys [subject known suggestion location]} issue
        {:keys [step-text uri line]} location]
    (str "Unknown subject " (format-keyword subject)
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         (when (seq known)
           (str "\n       Known subjects: " (format-known-list known 8)))
         (when suggestion
           (str "\n       " (format-suggestion suggestion)))
         (def-site-suffix issue))))

(defn format-missing-subject
  "Format an :svo/missing-subject issue as a human-readable error message."
  [issue]
  (let [{:keys [message location]} issue
        {:keys [step-text uri line]} location]
    (str (or message "SVO step resolved without a subject")
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         "\n       The step definition's declared subject capture resolved to nil.")))

(defn format-unknown-verb
  "Format an :svo/unknown-verb issue as a human-readable error message.

   Example output:
   ERROR: Unknown verb :smash in step \"When Alice smashes the button\"
          at features/login.feature:15
          Interface :web (type :web)
          Known verbs for :web: :click, :fill, :see, :navigate, :submit"
  [issue]
  (let [{:keys [verb interface-type known suggestion location interface-name]} issue
        {:keys [step-text uri line]} location]
    (str "Unknown verb :" (name verb)
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         (when interface-name
           (str "\n       Interface :" (name interface-name)
                (when interface-type (str " (type :" (name interface-type) ")"))))
         (when (seq known)
           (str "\n       Known verbs"
                (when interface-type (str " for :" (name interface-type)))
                ": " (format-known-list known 8)))
         (when suggestion
           (str "\n       " (format-suggestion suggestion)))
         (def-site-suffix issue))))

(defn format-unknown-interface
  "Format an :svo/unknown-interface issue as a human-readable error message.

   Example output:
   ERROR: Unknown interface :foobar in step \"When Alice clicks the button\"
          at features/login.feature:18
          Configured interfaces: :web, :api
          Add to shiftlefter.edn: {:interfaces {:foobar {:type ... :adapter ...}}}"
  [issue]
  (let [{:keys [interface known suggestion location]} issue
        {:keys [step-text uri line]} location]
    (str "Unknown interface :" (name interface)
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         (when (seq known)
           (str "\n       Configured interfaces: " (format-known-list known 8)))
         (when suggestion
           (str "\n       " (format-suggestion suggestion)))
         (def-site-suffix issue)
         "\n       Add to shiftlefter.edn: {:interfaces {:" (name interface)
         " {:type ... :adapter ...}}}")))

(defn format-provisioning-failed
  "Format an :svo/provisioning-failed issue as a human-readable error message.

   Example output:
   ERROR: Provisioning failed for interface :web
          at features/login.feature:10
          Adapter :etaoin error: Browser not installed
          Configured interfaces: :web, :api"
  [issue]
  (let [{:keys [interface adapter adapter-error message known location]} issue
        {:keys [step-text uri line]} location]
    (str "Provisioning failed for interface :" (name interface)
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         (cond
           adapter-error (str "\n       Adapter :" (name adapter) " error: " adapter-error)
           message (str "\n       " message))
         (when (seq known)
           (str "\n       Configured interfaces: " (format-known-list known 8))))))

(defn format-unknown-object
  "Format an :svo/unknown-object issue as a human-readable error message.

   Example output:
   ERROR: Unknown object 'Login.unknown' in step \"When :alice clicks Login.unknown\"
          at features/login.feature:15
          Intent reference could not be resolved: Element 'unknown' not found in intent 'Login'"
  [issue]
  (let [{:keys [object interface message location known suggestion]} issue
        {:keys [step-text uri line]} location]
    (str "Unknown object '" object "'"
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         (when interface (str "\n       Interface: :" (name interface)))
         (when message (str "\n       " message))
         ;; sl-u2r0: the two signals travel together — the suggestion as
         ;; its own line (the message already names it), the interfaces
         ;; that DO bind the element (Sol 32), and the def-site.
         (when (and suggestion (not (str/includes? (str message) "Did you mean")))
           (str "\n       " (format-suggestion suggestion)))
         (when (seq known)
           (str "\n       Bindings exist for: " (format-known-list known 8)))
         (def-site-suffix issue))))

(defn format-raw-locator-disallowed
  "Format an :svo/raw-locator-disallowed issue as a human-readable error message.

   Example output:
   ERROR: Raw locator '{:css \"#submit\"}' in step \"When :alice clicks {:css \"#submit\"}\"
          at features/login.feature:20
          Raw locators are not allowed in strict mode. Use intent references instead.
          Example: Login.submit or Checkout.pay-button"
  [issue]
  (let [{:keys [object message location]} issue
        {:keys [step-text uri line]} location]
    (str "Raw locator '" object "'"
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         (when message (str "\n       " message))
         (def-site-suffix issue)
         "\n       Example: Login.submit or Checkout.pay-button")))

(defn format-consumed-without-producer
  "Format a :bindings/consumed-without-producer issue (sl-yh7).

   Example output:
   ERROR: Binding {coed} is consumed but never produced in step \"...\"
          at features/reset.feature:14
          Producers upstream: {code}
          Did you mean: {code}?"
  [issue]
  (let [{:keys [token known suggestion location]} issue
        {:keys [step-text uri line]} location]
    (str "Binding " token " is consumed but never produced"
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         (if (seq known)
           (str "\n       Producers upstream: "
                (str/join ", " (map #(str "{" (name %) "}") known)))
           "\n       No upstream step or hook produces any binding")
         (when suggestion
           (str "\n       Did you mean: {" (name suggestion) "}?"))
         ;; sl-u2r0: the suggested producer's own site — "Produced at".
         (when-let [{:keys [file line]} (:def-site issue)]
           (str "\n       Produced at: " file (when line (str ":" line)))))))

(defn format-bindings-invalid-pattern
  "Format a :bindings/invalid-pattern issue (sl-yh7) — bad capture regex,
   including duplicate named groups."
  [issue]
  (let [{:keys [pattern message location]} issue
        {:keys [step-text uri line]} location]
    (str "Invalid capture pattern /" pattern "/"
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         (when message (str "\n       " message)))))

(defn format-named-groups-in-assert-only
  "Format a :bindings/named-groups-in-assert-only error (sl-gwru; formatter
   added at sl-mk0t, Warden W3 — it fell through to the raw-map fallback,
   step UUID included): the pattern and the names it would have bound, the
   lint's own pointer at `captures`, then the step and its site in the
   house style. The record's :pattern is the matcher slot AS WRITTEN, slashes
   included (bindings_lint `:pattern capture`), so it is printed as carried —
   wrapping it doubled the delimiters (sl-0qzd, Warden delta pass D1)."
  [issue]
  (let [{:keys [pattern names message location]} issue
        {:keys [step-text uri line]} location]
    (str "Pattern " pattern " names "
         (str/join ", " (map #(str "{" (name %) "}") names))
         " in an assert-only step — " message
         (when step-text (str "\n       in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line)))))

(defn format-unnamed-only-groups
  "Format a :bindings/unnamed-only-groups notice (sl-yh7) — the pattern
   captures groups but names none, so it binds nothing. :pattern is the
   matcher slot as written (`/…/`, or an `Intent.element` shape ref since
   sl-ectw), printed as carried (sl-0qzd — the same double-delimiting as the
   assert-only formatter)."
  [issue]
  (let [{:keys [pattern location]} issue
        {:keys [step-text uri line]} location]
    (str "Capture pattern " pattern " has only unnamed groups — it binds nothing"
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         "\n       Name a group ((?<name>...)) to produce a {name} binding.")))

(defn format-produced-never-consumed
  "Format a :bindings/produced-never-consumed notice (sl-yh7). A
   shape-sourced binding (sl-ectw) also names the glossary line that
   declared the group."
  [issue]
  (let [{:keys [name location]} issue
        {:keys [step-text uri line]} location]
    (str "Binding {" (clojure.core/name name) "} is produced but never consumed"
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         (when-let [{:keys [file line]} (:def-site issue)]
           (str "\n       Declared at: " file (when line (str ":" line)))))))

(defn format-shape-issue
  "Format the matcher-slot shape issues (sl-ectw): the classifier's
   message, the step's site, and the element's def-site when the issue
   has one."
  [issue]
  (let [{:keys [message location]} issue
        {:keys [step-text uri line]} location]
    (str message
         (when step-text (str "\n       in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         (when-let [{:keys [file line]} (:def-site issue)]
           (str "\n       Declared at: " file (when line (str ":" line)))))))

(defn format-untyped-stepdef
  "Format a :stepdef/untyped issue (sl-6wh9): the step, its file:line, and
   the untyped stepdef that matched it. Deliberately prints no :step-id —
   the console line must be byte-stable run to run."
  [issue]
  (let [{:keys [stepdef location]} issue
        {:keys [step-text uri line]} location]
    (str "Untyped step"
         (when step-text (str " \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         "\n       matched stepdef " (pr-str stepdef)
         ", which declares no :svo — no subject, verb, or object was checked")))

(defn format-message-only
  "Format an issue whose detail is fully carried in :message (the key-slot
   family and the ref-shaped location-literal warning) — message plus
   step/location suffix in the house style."
  [issue]
  (let [{:keys [message location]} issue
        {:keys [step-text uri line]} location]
    (str message
         (when step-text (str "\n       in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line)))))

(defn format-svo-issue
  "Format any SVO issue by dispatching on :type.

   Supported types:
   - :svo/missing-subject
   - :svo/unknown-subject
   - :svo/unknown-verb
   - :svo/unknown-interface
   - :svo/provisioning-failed
   - :svo/unknown-object
   - :svo/raw-locator-disallowed

   Returns formatted string or generic fallback for unknown types."
  [issue]
  (case (:type issue)
    :svo/missing-subject (format-missing-subject issue)
    :svo/unknown-subject (format-unknown-subject issue)
    :svo/unknown-verb (format-unknown-verb issue)
    :svo/unknown-interface (format-unknown-interface issue)
    :svo/provisioning-failed (format-provisioning-failed issue)
    :svo/unknown-object (format-unknown-object issue)
    :svo/raw-locator-disallowed (format-raw-locator-disallowed issue)
    :svo/ref-shaped-location-literal (format-message-only issue)
    :svo/invalid-key-literal (format-message-only issue)
    :svo/unknown-key (format-message-only issue)
    :bindings/consumed-without-producer (format-consumed-without-producer issue)
    :bindings/invalid-pattern (format-bindings-invalid-pattern issue)
    :bindings/unnamed-only-groups (format-unnamed-only-groups issue)
    ;; sl-gwru: a producer marker in the assert-only frame
    :bindings/named-groups-in-assert-only (format-named-groups-in-assert-only issue)
    :bindings/produced-never-consumed (format-produced-never-consumed issue)
    ;; sl-ectw: the declared shape in the matcher slot
    (:bindings/invalid-matcher-slot :bindings/unknown-shape-ref
     :bindings/element-has-no-shape :bindings/shape-no-binding-for-interface
     :bindings/captures-without-shape :bindings/shape-and-inline)
    (format-shape-issue issue)
    :costume/wrong-interface (format-message-only issue)
    :costume/shared-wears (format-message-only issue)
    :costume/undeclared (format-message-only issue)
    ;; sl-6wh9: a matched user stepdef with no :svo metadata
    :stepdef/untyped (format-untyped-stepdef issue)
    ;; Fallback for unknown types
    (str "SVO issue: " (pr-str issue))))
