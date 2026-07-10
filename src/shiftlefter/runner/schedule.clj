(ns shiftlefter.runner.schedule
  "Plan-level scheduling facet (sl-q9wp).

   Computes `{:serial? true :reason :tag|:costume|:shared-impl}` per compiled
   plan and attaches it as `:plan/schedule` (an additive key — the plan is
   unchanged otherwise). The scheduler in exec/cleanup.clj consumes only
   `:serial?`; the reason codes exist for the auto-serial notice line and for
   humans reading envelopes.

   Three producers, one facet:

   - `:tag` — the scenario carries @serial. Read through the tag-disposition
     seam (the ONLY namespace that reads :pickle/tags).
   - `:costume` — the plan provisions a persistent capability (a bound step's
     SVO carries :wears). One authed Chrome cannot host two WebDriver
     sessions, and the one-wearer guard is per-scenario ctx — it cannot see
     across parallel scenarios. Persistence is DERIVED, not declared: there
     is no persistent flag on the plan, so this is the plan-visible signal.
   - `:shared-impl` — the plan touches an interface configured
     `:shared-impl? true` (e.g. SMS). Cross-scenario isolation for shared
     impls rests on :sms/scenario-start-ts acting as a time fence, which
     only holds when scenario wall-clock windows never overlap.

   Precedence :tag > :costume > :shared-impl — first match wins. A scenario
   matching several reasons is serialized once; the reason is cosmetic.

   At :max-parallel 1 (or unset) the facet is inert: the sequential path
   never reads it."
  (:require [clojure.spec.alpha :as s]
            [shiftlefter.runner.tag-disposition :as tagd]
            [shiftlefter.stepengine.exec.provisioning :as prov]))

(defn plan-schedule
  "The scheduling facet for one compiled plan, or nil when the plan is free
   to run on the pool. `interfaces` is the config :interfaces map."
  [plan interfaces]
  (or (:schedule (tagd/disposition nil (:plan/pickle plan)))
      (let [targets (prov/collect-provisioning-targets plan)]
        (cond
          (some :wears targets)
          {:serial? true :reason :costume}

          (some #(get-in interfaces [(:interface %) :shared-impl?]) targets)
          {:serial? true :reason :shared-impl}

          :else nil))))

(s/fdef plan-schedule
  :args (s/cat :plan map? :interfaces (s/nilable map?))
  :ret (s/nilable ::tagd/schedule))

(defn attach-schedules
  "Assoc :plan/schedule onto each plan whose facet is non-nil. Plans without
   a facet are returned untouched (no nil-valued key)."
  [plans interfaces]
  (mapv (fn [plan]
          (if-let [facet (plan-schedule plan interfaces)]
            (assoc plan :plan/schedule facet)
            plan))
        plans))

(defn auto-serial-counts
  "How many plans each AUTO gate serialized: {:costume N :shared-impl N},
   keys present only when positive. @serial (:tag) is deliberate user
   intent, not an auto gate, so it is excluded — the notice line (DP1)
   reports only what the runner decided on the user's behalf."
  [plans]
  (->> plans
       (keep (comp :reason :plan/schedule))
       (filter #{:costume :shared-impl})
       frequencies))
