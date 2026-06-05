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
     A matched stepdef declares `:interface :foo` but no `:foo` entry
     exists in the resolved `:interfaces` config. Surfaced once at the
     stepdef's source file:line.

   - `:stepdef/missing-capability`
     A matched stepdef declares `:requires-protocols [...]` but the
     configured adapter for its interface doesn't `:provides` them.
     Lifted from sl-ewn's per-step bind-time check: same shape, but
     reported once per stepdef, against the stepdef's source — not once
     per use. Skipped for stepdefs whose interface is itself undefined
     (the undefined-interface issue subsumes it).

   - `:glossary/orphan-type`
     A user-declared verb glossary path targets `:type :foo` but no
     configured interface has `:type :foo`. Verbs in that glossary will
     never be matched. Only project-declared glossaries (via
     `:glossaries :verbs` config map) are checked — framework
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

(defn- check-undefined-interface
  "Returns an issue if `stepdef`'s declared interface isn't configured.
   Stepdefs without a declared interface (legacy / cross-interface) are
   silently skipped."
  [stepdef configured-iface-keys]
  (when-let [iface (get-in stepdef [:metadata :interface])]
    (when-not (contains? configured-iface-keys iface)
      {:type       :stepdef/undefined-interface
       :severity   :error
       :stepdef    (stepdef-summary stepdef)
       :interface  iface
       :configured (vec configured-iface-keys)
       :message    (str "Stepdef at " (format-source (:source stepdef))
                        " declares :interface " iface
                        " but no interface with that name is configured."
                        " Configured interfaces: "
                        (pr-str (vec configured-iface-keys)) ".")})))

;; -----------------------------------------------------------------------------
;; Check 2 — :stepdef/missing-capability
;; -----------------------------------------------------------------------------

(defn- check-missing-capability
  "Returns an issue if `stepdef`'s `:requires-protocols` aren't all
   provided by the adapter configured for its `:interface`. Skips
   stepdefs whose interface is itself undefined (those are subsumed by
   `:stepdef/undefined-interface`) and stepdefs without an interface."
  [stepdef interfaces adapter-registry]
  (let [meta     (:metadata stepdef)
        iface    (:interface meta)
        required (:requires-protocols meta)]
    (when (and (seq required)
               iface
               (contains? interfaces iface))
      (let [adapter-name (get-in interfaces [iface :adapter])
            provides     (adapters/provides adapter-name adapter-registry)
            missing      (vec (remove provides required))]
        (cond
          (nil? adapter-name)
          {:type      :stepdef/missing-capability
           :severity  :error
           :stepdef   (stepdef-summary stepdef)
           :interface iface
           :adapter   nil
           :missing   (vec required)
           :provides  #{}
           :message   (str "Stepdef at " (format-source (:source stepdef))
                           " requires protocols " (pr-str required)
                           " under interface " iface
                           " but no adapter is configured for that interface.")}

          (seq missing)
          {:type      :stepdef/missing-capability
           :severity  :error
           :stepdef   (stepdef-summary stepdef)
           :interface iface
           :adapter   adapter-name
           :missing   missing
           :provides  (set provides)
           :message   (str "Stepdef at " (format-source (:source stepdef))
                           " requires " (pr-str missing)
                           " under interface " iface
                           " but configured adapter " adapter-name
                           " only provides " (pr-str (vec provides))
                           ". Configure an adapter that supports the missing"
                           " capability.")})))))

;; -----------------------------------------------------------------------------
;; Check 3 — :glossary/orphan-type
;; -----------------------------------------------------------------------------

(defn- check-glossary-orphan-types
  "Returns issues — one per orphaned project-declared verb-glossary type.
   `project-glossary-types` is the set of types the user explicitly
   configured a glossary path for (typically `(keys (get-in config
   [:glossaries :verbs]))`). Framework default glossaries are not in
   scope here — the project may load them transparently without
   declaring an interface for each."
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
   lint checks need (`:pattern-src`, `:source`, `:metadata`). Dedupe by
   `:stepdef/id` so a stepdef matched by N steps yields one info map.

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
                    :metadata    (:metadata b)}))))
       (group-by :stepdef/id)
       vals
       (map first)))

(defn lint-suite
  "Run the suite-load lint pass over a collection of stepdef-info maps.

   Each `stepdef-info` must carry `:pattern-src`, `:source`, `:metadata`.
   Both raw stepdefs (from `registry/all-stepdefs`) and the projection
   produced by `used-stepdef-infos` satisfy this shape.

   Parameters:
   - `stepdef-infos`           — seq of stepdef-info maps to lint
   - `project-glossary-types`  — coll of interface-type keywords the
                                 project explicitly declared a verb-
                                 glossary path for (typically
                                 `(keys (get-in config [:glossaries :verbs]))`).
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
          iface-keys (set (keys interfaces))
          undefined  (keep #(check-undefined-interface % iface-keys)
                           stepdef-infos)
          missing    (keep #(check-missing-capability % interfaces registry)
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
