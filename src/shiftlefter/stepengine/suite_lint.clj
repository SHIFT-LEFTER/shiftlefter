(ns shiftlefter.stepengine.suite-lint
  "Suite-load static lint pass over the bound suite (sl-unz). Runs once
   after binding, before execution — surfaces stepdef ↔ config ↔ adapter
   inconsistencies as planning errors with per-stepdef diagnostics
   (deduped from bound steps), instead of repeating the same gap once
   per scenario as sl-ewn did.

   Scope: only stepdefs actually matched by at least one bound step in
   the suite. Loaded-but-unused stepdefs are not flagged — that would
   produce false positives for built-in stepdefs (e.g. SMS) when a
   project's scenarios don't touch those interfaces.

   Three checks; all gate on the `:interfaces` config being present
   (vanilla mode is exempt).

   - `:stepdef/undefined-interface`
     A matched stepdef declares `:interface :foo` — an interface TYPE
     since sl-s2oa — but no configured interface instance provides that
     type (an entry without :type reads as an instance of the type named
     by its own key). Surfaced once at the stepdef's source file:line.
     This is also the migration signal for pre-s2oa custom stepdefs that
     pinned a config instance NAME in their metadata.

   - `:stepdef/missing-capability`
     A matched stepdef declares `:requires-protocols [...]` but the
     adapter of an interface INSTANCE the suite actually resolves it to
     doesn't `:provides` them (sl-s2oa: bound plans carry resolved
     instances; a stepdef with no resolvable instances falls back to
     every instance of its type). Lifted from sl-ewn's per-step
     bind-time check: same shape, but reported once per (stepdef,
     instance), against the stepdef's source — not once per use.
     Skipped for stepdefs whose interface type is itself undefined
     (the undefined-interface issue subsumes it). Also skipped when the
     instance's adapter is absent from the registry (sl-8cvd P2-2):
     registry membership is owned by the plan-aware exact check
     (`setup/bound-adapter-errors`, sl-kkoh) — this lint only judges
     the capabilities of adapters that exist.

   - `:glossary/orphan-type`
     A user-declared verb glossary path targets `:type :foo` but no
     configured interface has `:type :foo`. Verbs in that glossary will
     never be matched. Only project-declared glossaries (the types the
     project's own verb files declare — sl-67dj: read from the loaded
     glossary's `:sources :verbs` mounts) are checked — framework
     defaults are exempt so a project that doesn't use the bundled
     `:sms` interface isn't punished for the default `:sms` glossary
     resource being loaded.

   Issues all carry `:severity :error`. Out of scope: anything dynamic
   (creds, network, adapter constructor failures) — those still surface
   at provisioning time."
  (:require [clojure.spec.alpha :as s]
            [shiftlefter.adapters.registry :as adapters]))

;; -----------------------------------------------------------------------------
;; Specs
;; -----------------------------------------------------------------------------

(s/def ::type #{:stepdef/undefined-interface
                :stepdef/missing-capability
                :glossary/orphan-type})
(s/def ::severity #{:error :warn})
(s/def ::message string?)
(s/def ::issue
  (s/keys :req-un [::type ::severity ::message]))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- format-source
  "Format a stepdef source map as 'file:line'."
  [{:keys [file line]}]
  (str file ":" line))

(defn- stepdef-summary
  "Compact stepdef identification carried on issues."
  [stepdef]
  {:pattern-src (:pattern-src stepdef)
   :source      (:source stepdef)})

;; -----------------------------------------------------------------------------
;; Check 1 — :stepdef/undefined-interface
;; -----------------------------------------------------------------------------

(defn configured-types
  "The set of interface TYPES the config provides (sl-s2oa): each entry's
   :type, an entry without :type reading as the type named by its own key
   (the run path never normalizes config, so type-less entries exist).
   A non-map :interfaces (normalize's :config/invalid-interfaces to name)
   provides no types — #{} rather than a destructure crash (sl-a7sx)."
  [interfaces]
  (if (map? interfaces)
    (into #{} (map (fn [[nm idef]] (or (:type idef) nm))) interfaces)
    #{}))

(defn- check-undefined-interface
  "Returns an issue if no configured interface instance provides
   `stepdef`'s declared interface TYPE (sl-s2oa: stepdef :interface
   metadata means the TYPE — a stepdef still pinning a pre-s2oa config
   instance NAME lands here, which is the migration signal). Stepdefs
   without a declared interface (legacy / cross-interface) are silently
   skipped."
  [stepdef configured-type-set]
  (when-let [iface (get-in stepdef [:metadata :interface])]
    (when-not (contains? configured-type-set iface)
      {:type       :stepdef/undefined-interface
       :severity   :error
       :stepdef    (stepdef-summary stepdef)
       :interface  iface
       :configured (vec (sort configured-type-set))
       :message    (str "Stepdef at " (format-source (:source stepdef))
                        " declares :interface " iface
                        " but no configured interface provides that type"
                        " (stepdef :interface metadata names an interface"
                        " TYPE; the [name] step annotation selects an"
                        " instance). Configured types: "
                        (pr-str (vec (sort configured-type-set))) ".")})))

;; -----------------------------------------------------------------------------
;; Check 2 — :stepdef/missing-capability
;; -----------------------------------------------------------------------------

(defn- instances-of-type
  "Sorted names of configured instances providing `iface-type` (same
   name-as-type fallback as `configured-types`)."
  [interfaces iface-type]
  (->> interfaces
       (keep (fn [[nm idef]] (when (= iface-type (or (:type idef) nm)) nm)))
       sort
       vec))

(defn- check-instance-capability
  "One `:stepdef/missing-capability` issue if `instance`'s configured
   adapter doesn't provide all of `stepdef`'s `:requires-protocols`."
  [stepdef interfaces adapter-registry instance]
  (let [required     (get-in stepdef [:metadata :requires-protocols])
        iface        (get-in stepdef [:metadata :interface])
        adapter-name (get-in interfaces [instance :adapter])
        provides     (adapters/provides adapter-name adapter-registry)
        missing      (vec (remove provides required))]
    (cond
      (nil? adapter-name)
      {:type      :stepdef/missing-capability
       :severity  :error
       :stepdef   (stepdef-summary stepdef)
       :interface instance
       :interface-type iface
       :adapter   nil
       :missing   (vec required)
       :provides  #{}
       :message   (str "Stepdef at " (format-source (:source stepdef))
                       " requires protocols " (pr-str required)
                       " under interface " instance " (type " iface ")"
                       " but no adapter is configured for that interface.")}

      ;; Adapter named but absent from the registry: registry MEMBERSHIP is
      ;; owned by the plan-aware exact check (setup/bound-adapter-errors,
      ;; sl-kkoh) — `provides` returns the empty set for an unknown adapter,
      ;; so falling through to the arm below told the false story "only
      ;; provides []. Configure an adapter that supports the missing
      ;; capability" and shadowed the exact check (sl-8cvd, P2-2).
      (not (contains? adapter-registry adapter-name))
      nil

      (seq missing)
      {:type      :stepdef/missing-capability
       :severity  :error
       :stepdef   (stepdef-summary stepdef)
       :interface instance
       :interface-type iface
       :adapter   adapter-name
       :missing   missing
       :provides  (set provides)
       :message   (str "Stepdef at " (format-source (:source stepdef))
                       " requires " (pr-str missing)
                       " under interface " instance " (type " iface ")"
                       " but configured adapter " adapter-name
                       " only provides " (pr-str (vec provides))
                       ". Configure an adapter that supports the missing"
                       " capability.")})))

(defn- check-missing-capability
  "Returns issues for each configured interface INSTANCE the stepdef
   resolves to whose adapter doesn't provide the stepdef's
   `:requires-protocols` (sl-s2oa). Instances checked are the
   `:used-instances` the bound plans resolved this stepdef to (falling
   back to every configured instance of the stepdef's type when none —
   e.g. a stepdef without :svo metadata). Unconfigured instance names
   (type-as-name fallbacks) are skipped — `:stepdef/undefined-interface`
   subsumes them. Stepdefs without an interface are skipped."
  [stepdef interfaces adapter-registry]
  (let [meta     (:metadata stepdef)
        iface    (:interface meta)
        required (:requires-protocols meta)]
    (when (and (seq required) iface)
      (let [candidates (or (seq (sort (:used-instances stepdef)))
                           (instances-of-type interfaces iface))
            configured (filter #(contains? interfaces %) candidates)]
        (seq (keep #(check-instance-capability stepdef interfaces
                                               adapter-registry %)
                   configured))))))

;; -----------------------------------------------------------------------------
;; Check 3 — :glossary/orphan-type
;; -----------------------------------------------------------------------------

(defn- check-glossary-orphan-types
  "Returns issues — one per orphaned project-declared verb-glossary type.
   `project-glossary-types` is the set of types the project's own verb
   glossary files DECLARE (sl-67dj — typically `(keys (get-in
   loaded-glossary [:sources :verbs]))`, the loader's actual mounts).
   Framework default glossaries are not in scope here — the project may
   load them transparently without declaring an interface for each."
  [project-glossary-types interfaces]
  (when (and (seq project-glossary-types) (seq interfaces))
    (let [iface-types (set (keep :type (vals interfaces)))
          orphans     (remove iface-types project-glossary-types)]
      (mapv (fn [t]
              {:type             :glossary/orphan-type
               :severity         :error
               :glossary-type    t
               :configured-types (vec iface-types)
               :message          (str "Verb glossary declares :type " t
                                      " but no configured interface has"
                                      " :type " t
                                      ". Verbs in this glossary will never"
                                      " be matched. Configured interface"
                                      " types: " (pr-str (vec iface-types))
                                      ".")})
            orphans))))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn used-stepdef-infos
  "Project bound steps in `plans` to the unique stepdef-info maps the
   lint checks need (`:pattern-src`, `:source`, `:metadata`, plus
   `:used-instances` — the set of resolved interface INSTANCES the
   suite's bound SVOs execute this stepdef on, sl-s2oa). Dedupe by
   `:stepdef/id` so a stepdef matched by N steps yields one info map
   with the union of its used instances.

   Only stepdefs from `:status :matched` bound steps are included —
   undefined/ambiguous/synthetic steps don't carry a stepdef to lint."
  [plans]
  (->> plans
       (mapcat :plan/steps)
       (filter #(= :matched (:status %)))
       (keep (fn [bs]
               (let [b (:binding bs)]
                 (when (:stepdef/id b)
                   {:stepdef/id  (:stepdef/id b)
                    :pattern-src (:pattern-src b)
                    :source      (:source b)
                    :metadata    (:metadata b)
                    :used-instances (if-let [inst (-> b :svo :interface)]
                                      #{inst}
                                      #{})}))))
       (group-by :stepdef/id)
       vals
       (map (fn [infos]
              (assoc (first infos)
                     :used-instances
                     (into #{} (mapcat :used-instances) infos))))))

(defn lint-suite
  "Run the suite-load lint pass over a collection of stepdef-info maps.

   Each `stepdef-info` must carry `:pattern-src`, `:source`, `:metadata`,
   and may carry `:used-instances` (sl-s2oa — resolved instances to check
   `:requires-protocols` against; absent/empty falls back to every
   configured instance of the stepdef's type). Both raw stepdefs (from
   `registry/all-stepdefs`) and the projection produced by
   `used-stepdef-infos` satisfy this shape.

   Parameters:
   - `stepdef-infos`           — seq of stepdef-info maps to lint
   - `project-glossary-types`  — coll of interface-type keywords the
                                 project's own verb glossary files
                                 declare (sl-67dj — typically `(keys
                                 (get-in loaded-glossary [:sources :verbs]))`,
                                 the loader's actual mounts; nil in the
                                 vanilla run path, where no glossary
                                 loads and the orphan check is skipped
                                 by choice).
                                 Framework defaults are NOT included —
                                 a project shouldn't be flagged for
                                 default `:sms` glossary loading just
                                 because it has no `:sms` interface.
   - `interfaces`              — `:interfaces` config map or nil
   - `adapter-registry`        — adapter registry (defaults to
                                 `adapters/default-registry` when nil)

   Returns a vector of issues; empty when clean. When `interfaces` is
   nil (vanilla mode without explicit interface config), all checks are
   skipped and an empty vector is returned."
  [stepdef-infos project-glossary-types interfaces adapter-registry]
  (if (nil? interfaces)
    []
    (let [registry   (or adapter-registry adapters/default-registry)
          type-set   (configured-types interfaces)
          undefined  (keep #(check-undefined-interface % type-set)
                           stepdef-infos)
          missing    (mapcat #(check-missing-capability % interfaces registry)
                             stepdef-infos)
          orphans    (check-glossary-orphan-types project-glossary-types
                                                  interfaces)]
      (vec (concat undefined missing orphans)))))

(defn issue-counts
  "Bucket counts for `lint-suite` output, suitable for diagnostics."
  [issues]
  (let [by-type (group-by :type issues)]
    {:undefined-interface-count (count (:stepdef/undefined-interface by-type))
     :missing-capability-count  (count (:stepdef/missing-capability by-type))
     :orphan-glossary-type-count (count (:glossary/orphan-type by-type))
     :total                     (count issues)}))
