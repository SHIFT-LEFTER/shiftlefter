(ns shiftlefter.svo.bindings-lint
  "Static producer/consumer check for the scenario data plane (sl-yh7).

   Walks each plan's bound steps in scenario order, tracking which binding
   names are produced upstream (named groups in :matcher-slot patterns —
   inline `/…/` or an `Intent.element`'s declared :shape, classified by
   bindings/resolve-matcher (sl-ectw) — plus hook :provides declarations
   riding :plan/hooks), and checks every consumed `{name}` token against
   them.

   Issues (ride the :svo-issues channel — same shape, severity, and
   reporting as SVO validation):
   - :bindings/consumed-without-producer  ERROR  (+ did-you-mean)
   - :bindings/invalid-pattern            ERROR  (bad regex, incl. Java's
                                                  duplicate-group-name)
   - :bindings/unnamed-only-groups        frame-aware (sl-gwru): ERROR on
                                          :capture (binds nothing — pointer
                                          at should see ... matching);
                                          silent on :see :matching (the
                                          expected shape); NOTICE elsewhere
                                          (SMS receives — legitimate
                                          binding-free assertion)
   - :bindings/named-groups-in-assert-only ERROR (a named group in
                                          should see ... matching — pointer
                                          at captures; clause 4)
   - :bindings/produced-never-consumed    NOTICE (step-produced names only;
                                                  hook :provides seeds are
                                                  exempt — custom stepdefs
                                                  may read them from open
                                                  ctx invisibly)
   - :bindings/invalid-matcher-slot       ERROR  (neither /…/ nor a ref)
   - :bindings/unknown-shape-ref          ERROR  (ref names no element)
   - :bindings/element-has-no-shape       ERROR  (ref to an unshaped element
                                                  in a matching clause)
   - :bindings/shape-no-binding-for-interface ERROR
   - :bindings/captures-without-shape     ERROR  (captures O, no clause, on
                                                  an unshaped element or a
                                                  raw locator — def-site +
                                                  both exits)
   - :bindings/shape-and-inline           ERROR  (captures O matching /…/
                                                  where O declares a :shape
                                                  — both sites named)
   The shape-sourced ones carry :def-site (the glossary line), and a
   shape-produced name's producer site is that line, not the step.

   Runs AFTER hook attachment (:plan/hooks is a producer source), so it
   lives downstream of compile-suite — see runner.core/compile-suite-stage.
   Steps without :slot-kinds (undeclared stepdefs, unresolvable frames)
   are invisible to this check — declaring the frame is what buys a step
   its static guarantees."
  (:require [clojure.string :as str]
            [shiftlefter.stepengine.bindings :as bindings]
            [shiftlefter.svo.extract :as extract]
            [shiftlefter.text :as text]))

;; -----------------------------------------------------------------------------
;; Per-step extraction
;; -----------------------------------------------------------------------------

(defn- step-location
  "Location map in the :svo-issues shape."
  [bound-step source-file]
  (let [step (:step bound-step)
        loc (:step/location step)]
    {:step-text (:step/text step)
     :step-id (:step/id step)
     :uri source-file
     :line (:line loc)
     :column (:column loc)}))

(defn- capture-kind-pairs
  "Seq of [capture kind] for a bound step, kinds from bind-time stamping."
  [bound-step]
  (let [{:keys [captures slot-kinds]} (:binding bound-step)]
    (when (seq slot-kinds)
      (map vector captures (concat slot-kinds (repeat nil))))))

(defn- consumed-tokens
  "Tokens this step consumes: whole-capture tokens in :value/:location/
   :text slots, embedded tokens in :matcher slots. Seq of
   {:name kw :token str}."
  [bound-step]
  (mapcat (fn [[capture kind]]
            (case kind
              (:value :location :text)
              (when (bindings/token? capture)
                [{:name (:name (bindings/parse-token capture))
                  :token capture}])
              :matcher
              (map (fn [tok]
                     {:name (:name tok)
                      :token (str "{" (name (:name tok))
                                  (when (seq (:path tok))
                                    (str "." (str/join "." (map name (:path tok)))))
                                  "}")})
                   (bindings/embedded-tokens capture))
              nil))
          (capture-kind-pairs bound-step)))

(defn- matcher-captures
  "The :matcher-slot captures of a bound step — WITH their markers
   (`/…/` or `Intent.element`, sl-ectw)."
  [bound-step]
  (keep (fn [[capture kind]] (when (= :matcher kind) capture))
        (capture-kind-pairs bound-step)))

(defn- object-capture
  "The O-slot capture text of a bound step (the stepdef's :svo :object
   placeholder mapped back to its capture), or nil."
  [bound-step]
  (let [{:keys [captures metadata]} (:binding bound-step)
        obj (-> metadata :svo :object)]
    (when (extract/placeholder? obj)
      (get (vec captures) (extract/placeholder-index obj)))))

(defn- interface-type
  "The step's interface TYPE (the stepdef's :interface metadata, sl-s2oa)."
  [bound-step]
  (-> bound-step :binding :metadata :interface))

(defn- verb-frame
  [bound-step]
  (let [{:keys [verb frame]} (-> bound-step :binding :metadata :svo)]
    [verb frame]))

(defn- matcher-role
  "How this step's frame treats its matcher slot (sl-gwru):

   :assert-only — should see ... matching: all-unnamed is the expected
                  shape (silent); a named group is a plan ERROR pointing
                  at captures (clause 4: named groups are producers,
                  everywhere — a frame that binds nothing cannot host a
                  producer marker and quietly ignore it).
   :capture     — the binding verb: all-unnamed is a plan ERROR pointing
                  at should see ... matching (binding is this verb's
                  entire identity). The symmetric teaching pair.
   :generic     — the shared slot elsewhere (SMS receives): all-unnamed
                  stays the :warn NOTICE — binding-free assertion is
                  legitimate there, and the nudge is cheap when
                  /(\\d+)/ plausibly meant /(?<code>\\d+)/."
  [bound-step]
  (let [{:keys [verb frame]} (-> bound-step :binding :metadata :svo)]
    (cond
      (and (= :see verb) (= :matching frame)) :assert-only
      (= :capture verb) :capture
      :else :generic)))

;; -----------------------------------------------------------------------------
;; Per-plan walk
;; -----------------------------------------------------------------------------

(defn- site-str [{:keys [file line]}]
  (str file (when line (str ":" line))))

(defn- shape-sources
  "The regex sources this step's matcher slots run, each classified by
   `bindings/resolve-matcher` (sl-ectw — the same classifier exec runs).
   One entry per source: `{:capture <slot text> :pattern <source>
   :inline? bool :def-site {…}}` on success, `{:capture :error {…}}` on
   a classification failure. Also covers the no-clause `captures O`
   frame (:capture/:default), whose source is O's declared :shape."
  [bound-step intents]
  (let [iface (interface-type bound-step)
        [verb frame] (verb-frame bound-step)
        from-slot (map (fn [capture]
                         (let [r (bindings/resolve-matcher capture intents iface)]
                           (if-let [err (:error r)]
                             {:capture capture :error err}
                             {:capture capture
                              :pattern (get-in r [:ok :pattern])
                              :inline? (boolean (get-in r [:ok :inline]))
                              :def-site (get-in r [:ok :def-site])})))
                       (matcher-captures bound-step))]
    (if (and (= :capture verb) (= :default frame) (empty? from-slot))
      ;; captures O — the shape IS the element's.
      (let [o (object-capture bound-step)
            r (when (and (string? o) (not (str/starts-with? o "{")))
                (bindings/resolve-matcher o intents iface))]
        (cond
          (nil? r)
          [{:capture o
            :error {:type :bindings/captures-without-shape
                    :message (str "captures " o " — a raw locator has no "
                                  "declared :shape; write captures " o
                                  " matching /…/, or name an intent element "
                                  "that declares a :shape")}}]

          (:error r)
          (let [err (:error r)]
            [{:capture o
              :error (if (= :bindings/element-has-no-shape (:type err))
                       (assoc err
                              :type :bindings/captures-without-shape
                              :message (str "captures " o " reads " o "'s declared "
                                            ":shape, and it has none"
                                            (when (:def-site err)
                                              (str " (" (site-str (:def-site err)) ")"))
                                            " — add :shape \"…\" to the element, "
                                            "or write captures " o " matching /…/"))
                       err)}])

          :else
          [{:capture o
            :pattern (get-in r [:ok :pattern])
            :inline? false
            :def-site (get-in r [:ok :def-site])}]))
      from-slot)))

(defn- shape-and-inline-issue
  "sl-ectw precedence: `captures O matching /…/` where O declares a :shape
   is a plan ERROR naming both sites — two declarations of one shape."
  [bound-step intents sources location]
  (let [[verb frame] (verb-frame bound-step)]
    (when (and (= :capture verb) (= :matching frame)
               (some :inline? sources))
      (let [o (object-capture bound-step)
            r (when (and (string? o) (not (str/starts-with? o "{")))
                (bindings/resolve-matcher o intents (interface-type bound-step)))]
        (when (:ok r)
          (let [def-site (get-in r [:ok :def-site])]
            {:type :bindings/shape-and-inline
             :ref o
             :pattern (some #(when (:inline? %) (:capture %)) sources)
             :location location
             :def-site def-site
             :severity :error
             :message (str "captures " o " matching /…/ — " o " already declares "
                           "a :shape"
                           (when def-site (str " at " (site-str def-site)))
                           "; drop the matching clause (captures " o
                           ") to read the declared shape, or remove the "
                           ":shape to keep the inline pattern")}))))))

(defn- check-step
  "Check one bound step against the names produced upstream.
   Returns {:issues [...] :produced #{names} :consumed #{names}
   :produced-sites {name def-site}} (sites for shape-sourced names only)."
  [bound-step produced-so-far location producers intents]
  (let [consumed (consumed-tokens bound-step)
        consumption-issues
        (keep (fn [{:keys [name token]}]
                (when-not (contains? produced-so-far name)
                  (let [suggestion (text/suggest-similar
                                    name (vec produced-so-far) 2)
                        ;; sl-u2r0 (Sol 30): the suggested producer's own
                        ;; site — the upstream step's file:line, or the
                        ;; hooks file for a :provides seed.
                        def-site (when suggestion (get producers suggestion))]
                    (cond-> {:type :bindings/consumed-without-producer
                             :token token
                             :name name
                             :known (vec (sort produced-so-far))
                             :location location
                             :severity :error}
                      suggestion (assoc :suggestion suggestion)
                      def-site (assoc :def-site (assoc def-site :entry suggestion))))))
              consumed)
        sources (shape-sources bound-step intents)
        matcher-results
        (map (fn [{:keys [capture pattern inline? def-site error]}]
               (if error
                 {:issue (cond-> (assoc error
                                        :ref (or (:ref error) capture)
                                        :location location
                                        :severity :error)
                           (:def-site error) (assoc :def-site (:def-site error)))}
                 (try
                   {:info (bindings/pattern-group-info pattern)
                    :capture capture
                    :inline? inline?
                    :def-site def-site}
                   (catch clojure.lang.ExceptionInfo e
                     {:issue (assoc (select-keys (ex-data e) [:type :pattern])
                                    :message (:message (ex-data e))
                                    :location location
                                    :severity :error)}))))
             sources)
        pattern-issues (keep :issue matcher-results)
        precedence-issue (shape-and-inline-issue bound-step intents sources location)
        role (matcher-role bound-step)
        unnamed-issues
        (keep (fn [{:keys [info capture]}]
                (when (and info
                           (pos? (:group-count info))
                           (empty? (:names info)))
                  (case role
                    ;; All-unnamed is this frame's expected shape.
                    :assert-only nil
                    ;; sl-gwru escalation: captures that bind nothing.
                    :capture {:type :bindings/unnamed-only-groups
                              :pattern capture
                              :location location
                              :severity :error
                              :message (str "captures matcher binds nothing "
                                            "(no named groups) — did you mean "
                                            "'should see ... matching'?")}
                    :generic {:type :bindings/unnamed-only-groups
                              :pattern capture
                              :location location
                              :severity :warn})))
              matcher-results)
        ;; sl-ectw: a shape REF in the assert-only frame is legal — the
        ;; reader sees a structure marker, not a producer marker; the
        ;; frame still binds nothing. Only an INLINE named group trips this.
        named-in-assert-only
        (when (= :assert-only role)
          (keep (fn [{:keys [info capture inline?]}]
                  (when (and inline? (seq (:names info)))
                    {:type :bindings/named-groups-in-assert-only
                     :pattern capture
                     :names (vec (sort (:names info)))
                     :location location
                     :severity :error
                     :message (str "named groups produce bindings, and "
                                   "'should see ... matching' binds nothing "
                                   "— did you mean 'captures ... matching'?")}))
                matcher-results))
        ;; Defense-in-depth (sl-gwru): an assert-only frame's names never
        ;; enter the produced set even though the error above makes this
        ;; mostly unreachable — a false producer is the worst lint lie.
        produced (if (= :assert-only role)
                   #{}
                   (into #{} (mapcat (comp :names :info)) matcher-results))
        ;; sl-ectw: a shape-sourced name's site is the element's def-site
        ;; in the glossary — where the group was actually written.
        produced-sites (if (= :assert-only role)
                         {}
                         (into {}
                               (for [{:keys [info def-site]} matcher-results
                                     :when def-site
                                     n (:names info)]
                                 [n def-site])))]
    {:issues (concat consumption-issues pattern-issues
                     (when precedence-issue [precedence-issue])
                     unnamed-issues named-in-assert-only)
     :produced produced
     :produced-sites produced-sites
     :consumed (set (map :name consumed))}))

(defn- check-plan
  "Walk one plan's matched steps in order. Hook :provides seed the
   produced set. Returns a vector of issues."
  [plan intents]
  (let [source-file (-> plan :plan/pickle :pickle/source-file)
        hook-provides (into #{} (mapcat :provides) (:plan/hooks plan))
        ;; sl-u2r0: name → where it is produced (first producer wins) — a
        ;; hook seed's site is its hooks file, a step's its file:line.
        hook-producers (into {}
                             (for [hook (:plan/hooks plan)
                                   n (:provides hook)
                                   :when (get-in hook [:registration :path])]
                               [n {:file (get-in hook [:registration :path])}]))
        steps (filter #(= :matched (:status %)) (:plan/steps plan))
        walk (reduce
              (fn [{:keys [produced consumed-all issues step-produced producers]} bound-step]
                (let [location (step-location bound-step source-file)
                      result (check-step bound-step produced location producers
                                         intents)
                      new-names (remove produced (:produced result))
                      site-of (fn [n]
                                (or (get (:produced-sites result) n)
                                    (cond-> {:file (:uri location)}
                                      (:line location) (assoc :line (:line location)))))]
                  {:produced (into produced (:produced result))
                   :consumed-all (into consumed-all (:consumed result))
                   :issues (into issues (:issues result))
                   :step-produced (into step-produced
                                        (map (fn [n] [n location (get (:produced-sites result) n)])
                                             new-names))
                   :producers (into producers
                                    (map (fn [n] [n (site-of n)]) new-names))}))
              {:produced hook-provides
               :consumed-all #{}
               :issues []
               :step-produced []
               :producers hook-producers}
              steps)
        never-consumed
        (keep (fn [[n location def-site]]
                (when-not (contains? (:consumed-all walk) n)
                  (cond-> {:type :bindings/produced-never-consumed
                           :name n
                           :location location
                           :severity :warn}
                    ;; sl-ectw: a shape-sourced name points at the glossary
                    ;; line that declared the group.
                    def-site (assoc :def-site def-site))))
              (:step-produced walk))]
    (vec (concat (:issues walk) never-consumed))))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn check-plans
  "Run the data-plane static check over all plans (post hook-attachment).
   `intents` is the loaded intents map (sl-ectw: matcher-slot shape refs
   resolve against it; nil when none are loaded — refs then fail as
   unknown). Returns a vector of issues in the :svo-issues shape; empty
   when clean."
  ([plans] (check-plans plans nil))
  ([plans intents]
   (vec (mapcat #(check-plan % intents) plans))))

(defn blocking?
  "True when any issue is :severity :error — planning must fail (exit 2)."
  [issues]
  (boolean (some #(= :error (:severity %)) issues)))
