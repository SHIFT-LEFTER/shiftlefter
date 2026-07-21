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
   - :bindings/unnamed-only-groups        NOTICE (matcher captures groups
                                                  but names none — binds
                                                  nothing)
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
  "Tokens this step consumes: whole-capture tokens in :value/:location
   slots, embedded tokens in :matcher slots. Seq of {:name kw :token str}."
  [bound-step]
  (mapcat (fn [[capture kind]]
            (case kind
              (:value :location)
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
        unnamed-notices
        (keep (fn [{:keys [info capture]}]
                (when (and info
                           (pos? (:group-count info))
                           (empty? (:names info)))
                  {:type :bindings/unnamed-only-groups
                   :pattern capture
                   :location location
                   :severity :warn}))
              matcher-results)
        produced (into #{} (mapcat (comp :names :info)) matcher-results)]
    {:issues (concat consumption-issues pattern-issues unnamed-notices)
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
