(ns shiftlefter.sms.adapter-hooks
  "Adapter `:on-provision` hooks shared across SMS adapters.

   Both `:sms-mock` and `:sms-twilio` registry entries reference these
   functions via `:on-provision`. The engine invokes the hook after
   `cap/assoc-capability`, threading the returned ctx forward.

   See `shiftlefter.stepengine.exec/ensure-capability` for the call site
   and `shiftlefter.adapters.registry/default-registry` for wiring.

   Hook signature: `(fn [ctx impl] -> ctx)`."
  (:import (java.time Instant)))

(defn set-scenario-start-ts
  "On-provision hook: stamp the SMS scenario baseline once per scenario.

   Sets `:sms/scenario-start-ts` to `(Instant/now)` on first SMS
   provisioning. Idempotent: if a previous SMS provisioning already
   stamped the key, the existing value is preserved so a second-subject
   provision (or a shared-impl reuse) does not clobber the baseline.

   The baseline is read by `:see :count` for whole-scenario inbound
   counts and by `:receive`'s smart-default `:since-ts` ladder.

   Parameters:
   - ctx:  scenario context (post-`assoc-capability`)
   - impl: provisioned SMS impl (unused — kept for hook arity)

   Returns: updated ctx."
  [ctx _impl]
  (if (contains? ctx :sms/scenario-start-ts)
    ctx
    (assoc ctx :sms/scenario-start-ts (Instant/now))))
