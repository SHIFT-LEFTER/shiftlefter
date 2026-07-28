(ns shiftlefter.svo.bindings-lint
  "Static producer/consumer check for the scenario data plane (sl-yh7).

   Walks each plan's bound steps in scenario order, tracking which binding
   names are produced upstream (named groups in :matcher-slot capture
   literals, plus hook :provides declarations riding :plan/hooks), and
   checks every consumed `{name}` token against them.

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

   Runs AFTER hook attachment (:plan/hooks is a producer source), so it
   lives downstream of compile-suite — see runner.core/compile-suite-stage.
   Steps without :slot-kinds (undeclared stepdefs, unresolvable frames)
   are invisible to this check — declaring the frame is what buys a step
   its static guarantees."
  (:require [clojure.string :as str]
            [shiftlefter.stepengine.bindings :as bindings]
            [shiftlefter.svo.validate :as validate]))

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
  "The :matcher-slot capture literals of a bound step."
  [bound-step]
  (keep (fn [[capture kind]] (when (= :matcher kind) capture))
        (capture-kind-pairs bound-step)))

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

(defn- check-step
  "Check one bound step against the names produced upstream.
   Returns {:issues [...] :produced #{names} :consumed #{names}}."
  [bound-step produced-so-far location]
  (let [consumed (consumed-tokens bound-step)
        consumption-issues
        (keep (fn [{:keys [name token]}]
                (when-not (contains? produced-so-far name)
                  (let [suggestion (validate/suggest-similar
                                    name (vec produced-so-far) 2)]
                    (cond-> {:type :bindings/consumed-without-producer
                             :token token
                             :name name
                             :known (vec (sort produced-so-far))
                             :location location
                             :severity :error}
                      suggestion (assoc :suggestion suggestion)))))
              consumed)
        matcher-results
        (map (fn [capture]
               (try
                 {:info (bindings/pattern-group-info capture) :capture capture}
                 (catch clojure.lang.ExceptionInfo e
                   {:issue (assoc (select-keys (ex-data e) [:type :pattern])
                                  :message (:message (ex-data e))
                                  :location location
                                  :severity :error)})))
             (matcher-captures bound-step))
        pattern-issues (keep :issue matcher-results)
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
        named-in-assert-only
        (when (= :assert-only role)
          (keep (fn [{:keys [info capture]}]
                  (when (seq (:names info))
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
                   (into #{} (mapcat (comp :names :info)) matcher-results))]
    {:issues (concat consumption-issues pattern-issues
                     unnamed-issues named-in-assert-only)
     :produced produced
     :consumed (set (map :name consumed))}))

(defn- check-plan
  "Walk one plan's matched steps in order. Hook :provides seed the
   produced set. Returns a vector of issues."
  [plan]
  (let [source-file (-> plan :plan/pickle :pickle/source-file)
        hook-provides (into #{} (mapcat :provides) (:plan/hooks plan))
        steps (filter #(= :matched (:status %)) (:plan/steps plan))
        walk (reduce
              (fn [{:keys [produced consumed-all issues step-produced]} bound-step]
                (let [location (step-location bound-step source-file)
                      result (check-step bound-step produced location)]
                  {:produced (into produced (:produced result))
                   :consumed-all (into consumed-all (:consumed result))
                   :issues (into issues (:issues result))
                   :step-produced (into step-produced
                                        (map (fn [n] [n location])
                                             (remove produced (:produced result))))}))
              {:produced hook-provides
               :consumed-all #{}
               :issues []
               :step-produced []}
              steps)
        never-consumed
        (keep (fn [[n location]]
                (when-not (contains? (:consumed-all walk) n)
                  {:type :bindings/produced-never-consumed
                   :name n
                   :location location
                   :severity :warn}))
              (:step-produced walk))]
    (vec (concat (:issues walk) never-consumed))))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn check-plans
  "Run the data-plane static check over all plans (post hook-attachment).
   Returns a vector of issues in the :svo-issues shape; empty when clean."
  [plans]
  (vec (mapcat check-plan plans)))

(defn blocking?
  "True when any issue is :severity :error — planning must fail (exit 2)."
  [issues]
  (boolean (some #(= :error (:severity %)) issues)))
