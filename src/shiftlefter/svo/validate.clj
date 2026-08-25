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
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [shiftlefter.stepengine.bindings :as bindings]
            [shiftlefter.svo.glossary :as glossary]
            [shiftlefter.svo.keys :as keys]
            [shiftlefter.intent.resolve :as intent-resolve]
            [shiftlefter.intent.state :as intent-state]))

;; -----------------------------------------------------------------------------
;; Specs — SVO Validation Issue Shapes
;; -----------------------------------------------------------------------------

(s/def ::type #{:svo/missing-subject :svo/unknown-subject :svo/unknown-verb
                :svo/unknown-interface :svo/provisioning-failed
                :svo/unknown-object :svo/raw-locator-disallowed
                ;; :key slots (sl-2sj6) — framework-owned closed set
                :svo/unknown-key :svo/invalid-key-literal
                ;; Scenario data plane (sl-yh7) — ride the same issue channel
                :bindings/consumed-without-producer :bindings/invalid-pattern
                :bindings/unnamed-only-groups :bindings/produced-never-consumed
                ;; sl-gwru: producer marker in the assert-only matcher frame
                :bindings/named-groups-in-assert-only
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
(s/def ::suggestion (s/nilable keyword?))

(s/def ::svo-issue
  (s/keys :req-un [::type]
          :opt-un [::subject ::verb ::interface ::interface-type
                   ::known ::suggestion]))

(s/def ::valid? boolean?)
(s/def ::issues (s/coll-of map?))

(s/def ::validation-result
  (s/keys :req-un [::valid? ::issues]))

;; -----------------------------------------------------------------------------
;; Levenshtein Distance (for typo suggestions)
;; -----------------------------------------------------------------------------

(defn- levenshtein
  "Compute Levenshtein edit distance between two strings."
  [s1 s2]
  (let [len1 (count s1)
        len2 (count s2)]
    (cond
      (zero? len1) len2
      (zero? len2) len1
      :else
      (let [cost-fn (fn [i j]
                      (if (= (nth s1 (dec i)) (nth s2 (dec j))) 0 1))
            matrix (vec (for [i (range (inc len1))]
                          (vec (for [j (range (inc len2))]
                                 (cond
                                   (zero? i) j
                                   (zero? j) i
                                   :else 0)))))
            fill-cell (fn [m i j]
                        (assoc-in m [i j]
                                  (min (inc (get-in m [(dec i) j]))
                                       (inc (get-in m [i (dec j)]))
                                       (+ (get-in m [(dec i) (dec j)])
                                          (cost-fn i j)))))]
        (get-in (reduce (fn [m [i j]]
                          (fill-cell m i j))
                        matrix
                        (for [i (range 1 (inc len1))
                              j (range 1 (inc len2))]
                          [i j]))
                [len1 len2])))))

(defn- keyword->str
  "Convert a keyword to its string form without leading colon.
   Handles namespaced keywords: `:user/alice` → `\"user/alice\"`."
  [kw]
  (if (namespace kw)
    (str (namespace kw) "/" (name kw))
    (name kw)))

(defn suggest-similar
  "Find the most similar keyword from candidates.
   Uses full keyword string (including namespace) for comparison.
   Returns the best match if distance <= max-distance, else nil.
   Public since sl-yh7 — the bindings lint reuses it for
   consumed-without-producer did-you-mean."
  [target candidates max-distance]
  (when (and target (seq candidates))
    (let [target-str (keyword->str target)
          scored (map (fn [kw]
                        {:keyword kw
                         :distance (levenshtein target-str (keyword->str kw))})
                      candidates)
          best (apply min-key :distance scored)]
      (when (<= (:distance best) max-distance)
        (:keyword best)))))

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
            suggestion (suggest-similar subject all-forms 2)]
        {:type :svo/unknown-subject
         :subject subject
         :known all-forms
         :suggestion suggestion}))))

(defn- validate-verb
  "Validate verb against glossary for interface type.
   Returns issue map or nil if valid."
  [glossary interface-type verb]
  (when verb
    (when-not (glossary/known-verb? glossary interface-type verb)
      (let [known (glossary/known-verbs glossary interface-type)
            suggestion (suggest-similar verb known 2)]
        {:type :svo/unknown-verb
         :verb verb
         :interface-type interface-type
         :known known
         :suggestion suggestion}))))

(defn- validate-interface
  "Validate interface against config.
   Returns issue map or nil if valid."
  [interfaces interface-name]
  (when interface-name
    (when-not (contains? interfaces interface-name)
      (let [known (vec (keys interfaces))
            suggestion (suggest-similar interface-name known 2)]
        {:type :svo/unknown-interface
         :interface interface-name
         :known known
         :suggestion suggestion}))))

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
      (let [suggestion (suggest-similar (keyword object-str)
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

(defn- validate-object
  "Validate object (locator) against intent glossary.

   Enforcement modes:
   - :error — raw locators disallowed, unknown intents disallowed
     (:strict is a deprecated alias, sl-femd)
   - :warn — unknown intents warn (raw locators allowed)
   - :off — no validation

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
  [object-str interface enforcement kind location-refs?]
  (when (and object-str (string? object-str))
    (cond
      ;; :text — content-only slot, nothing to validate statically.
      (= kind :text)
      nil

      ;; :key — framework-owned closed set, always-on (no enforcement gate).
      (= kind :key)
      (validate-key-object object-str)

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
                (let [suggestion (when (#{:unknown-intent :no-location
                                          :no-location-for-interface}
                                        (:type loc-error))
                                   (suggest-similar
                                    (keyword object-str)
                                    (map keyword (intent-resolve/located-intents
                                                  intents interface))
                                    2))]
                  (cond-> {:type :svo/unknown-object
                           :object object-str
                           :interface interface
                           :message (cond-> (:message loc-error)
                                      suggestion
                                      (str ". Did you mean: " suggestion "?"))}
                    suggestion (assoc :suggestion suggestion)))))
            (catch Exception _
              ;; If intents not loaded yet, skip validation
              nil))

          (quoted-capture? object-str)
          (ref-shaped-location-literal object-str interface)))

      ;; Raw locator check (blocking level only — :error, or its deprecated
      ;; alias :strict)
      (and (#{:error :strict} enforcement) (raw-locator? object-str))
      {:type :svo/raw-locator-disallowed
       :object object-str
       :message "Raw locators are not allowed when :unknown-object is :error. Use intent references instead."}

      ;; Intent reference check — supports flat AND nested (multi-segment) refs.
      (not (raw-locator? object-str))
      (let [parse-result (intent-resolve/parse-intent-ref object-str)]
        (if (:error parse-result)
          ;; Invalid intent reference syntax
          {:type :svo/unknown-object
           :object object-str
           :message (-> parse-result :error :message)}
          ;; Valid syntax — statically walk the path against the schema (no browser):
          ;; intent known -> each non-last name is a collection -> last is a
          ;; collection or an element with a binding for this interface.
          (try
            (let [intents (intent-state/get-intents)
                  path-error (intent-resolve/validate-path-static
                              intents (:ok parse-result) interface)]
              (when path-error
                {:type :svo/unknown-object
                 :object object-str
                 :interface interface
                 :message (:message path-error)}))
            (catch Exception _
              ;; If intents not loaded yet, skip validation
              nil))))

      :else nil)))

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
       effective default is computed by runner.config/load-config (:warn
       when an :intents glossary is configured, sl-femd).

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
         ;; type-as-name fallback — vanilla/unconfigured paths unchanged).
         interface-def (get interfaces interface)
         interface-type (:type interface-def)
         effective-type (or interface-type interface)
         ;; Object enforcement — :off is the no-opts safety net, NOT the
         ;; default: load-config owns the effective default (sl-femd).
         object-enforcement (get opts :unknown-object :off)
         ;; Collect issues
         issues (keep identity
                       [(validate-subject glossary subject)
                        ;; Only validate verb if interface is known
                        (when interface-type
                          (validate-verb glossary interface-type verb))
                        (validate-interface interfaces interface)
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
                                         (location-refs-frame? glossary effective-type svo))])]
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
  (str ":" (keyword->str kw)))

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
    (str "Did you mean: " (format-keyword suggestion) "?")))

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
           (str "\n       " (format-suggestion suggestion))))))

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
           (str "\n       " (format-suggestion suggestion))))))

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
  (let [{:keys [object interface message location]} issue
        {:keys [step-text uri line]} location]
    (str "Unknown object '" object "'"
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         (when interface (str "\n       Interface: :" (name interface)))
         (when message (str "\n       " message)))))

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
           (str "\n       Did you mean: {" (name suggestion) "}?")))))

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

(defn format-unnamed-only-groups
  "Format a :bindings/unnamed-only-groups notice (sl-yh7) — the pattern
   captures groups but names none, so it binds nothing."
  [issue]
  (let [{:keys [pattern location]} issue
        {:keys [step-text uri line]} location]
    (str "Capture pattern /" pattern "/ has only unnamed groups — it binds nothing"
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         "\n       Name a group ((?<name>...)) to produce a {name} binding.")))

(defn format-produced-never-consumed
  "Format a :bindings/produced-never-consumed notice (sl-yh7)."
  [issue]
  (let [{:keys [name location]} issue
        {:keys [step-text uri line]} location]
    (str "Binding {" (clojure.core/name name) "} is produced but never consumed"
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line)))))

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
    :bindings/produced-never-consumed (format-produced-never-consumed issue)
    :costume/wrong-interface (format-message-only issue)
    :costume/shared-wears (format-message-only issue)
    :costume/undeclared (format-message-only issue)
    ;; Fallback for unknown types
    (str "SVO issue: " (pr-str issue))))
