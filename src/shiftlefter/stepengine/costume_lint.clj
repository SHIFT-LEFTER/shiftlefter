(ns shiftlefter.stepengine.costume-lint
  "Plan-time costume lints (sl-1206) — the static front door of the costume
   doctrine, with the sl-xbm/sl-s7t runtime guards staying as the belt
   (h0oj two-seam pattern: lint front door + runtime refusal).

   A costume declares the interface INSTANCE it belongs to in committed
   config (`:costumes {:gmail {:interface :webmail}}` in shiftlefter.edn,
   R2). Three checks, run inside bind-suite over the stamped plans:

   - :costume/wrong-interface (ERROR, R1/lint A): a persona's :wears names
     a costume whose declared instance is not the instance being
     provisioned for that subject in the bound scenario.
   - :costume/shared-wears (ERROR, R4/lint B): two provisioning targets in
     the SAME scenario share a :wears — one logged-in identity cannot be
     two actors (mechanically the sl-34fu two-handles thrash). Semantics
     deliberately match the runtime twin
     (exec.provisioning/duplicate-wears-errors) EXACTLY: targets dedup by
     [interface subject], so one persona wearing a costume across two
     interfaces trips it too (a costume is single-interface, sl-xbm).
   - :costume/undeclared (WARN, R3): a worn costume with no :costumes
     entry — grandfathers existing wardrobes and REPL ad-hoc; the warn
     text teaches the declaration. Deduped suite-wide per costume name.

   Issues ride the :svo-issues channel (validate's closed ::type set), so
   console, --edn, HTML, counts, and runnable? gating come from the
   existing machinery.

   RECORDED INVARIANT this lint leans on (Chair+Tower sitting 08-21):
   `:interfaces` is ONE FLAT MAP keyed by instance name, so instance names
   are globally unique ACROSS types by construction and the bare name is a
   complete address. If the config ever becomes type-namespaced, the
   costume declaration MUST become a (type, instance) pair — this comment
   exists so that refactor trips loudly here, not silently."
  (:require [shiftlefter.stepengine.exec.provisioning :as provisioning]
            [shiftlefter.text :as text]))

(defn- step-location
  "Location map in the :svo-issues shape (bindings-lint pattern)."
  [bound-step source-file]
  (let [step (:step bound-step)
        loc (:step/location step)]
    {:step-text (:step/text step)
     :step-id (:step/id step)
     :uri source-file
     :line (:line loc)
     :column (:column loc)}))

(defn- wearing-steps
  "Matched steps of the plan whose bound SVO wears a costume, as
   [bound-step svo] pairs."
  [plan]
  (for [bs (:plan/steps plan)
        :when (= :matched (:status bs))
        :let [svo (get-in bs [:binding :svo])]
        :when (:wears svo)]
    [bs svo]))

(defn- first-wearing-location
  "Location of the first step in the plan wearing costume (for lint B and
   the undeclared warn, which aggregate above step granularity)."
  [plan source-file costume]
  (some (fn [[bs svo]]
          (when (= costume (:wears svo))
            (step-location bs source-file)))
        (wearing-steps plan)))

(defn- wrong-interface-issues
  "Lint A: ERROR when a worn costume's declared instance is not the
   instance being provisioned. Skips steps whose :interface is not a
   configured instance — the type-as-name fallback (bind.clj
   resolve-instance) puts a bare TYPE keyword there when no instance of
   the type is configured, and validate-svo skips the same way."
  [plan source-file costumes interfaces]
  (for [[bs svo] (wearing-steps plan)
        :let [costume (:wears svo)
              instance (:interface svo)
              declared (get-in costumes [costume :interface])]
        :when (and declared
                   (get interfaces instance)
                   (not= declared instance))]
    (let [iface-type (fn [k] (or (get-in interfaces [k :type]) k))
          cross-type? (not= (iface-type declared) (iface-type instance))
          candidates (keep (fn [[cname cdef]]
                             (when (= instance (:interface cdef)) cname))
                           costumes)
          suggestion (when-not cross-type?
                       (text/suggest-similar costume candidates 2))]
      (cond-> {:type :costume/wrong-interface
               :severity :error
               :subject (:subject svo)
               :costume costume
               :declared-interface declared
               :interface instance
               :message (if cross-type?
                          ;; sl-xjx0 (Warden W15): the wearing persona is
                          ;; acting on an interface of a DIFFERENT TYPE —
                          ;; "declare the costume for it" would be
                          ;; unsatisfiable (it flips the error forever) and
                          ;; dangerous (a browser profile handed to a
                          ;; non-web adapter). Say what is actually wrong.
                          (str (pr-str (:subject svo)) " wears costume "
                               (pr-str costume) " (declared for "
                               (pr-str declared) ", a "
                               (pr-str (iface-type declared)) " interface)"
                               " but this step runs on " (pr-str instance)
                               " (" (pr-str (iface-type instance)) ")"
                               " — a costume is single-interface: a wearing"
                               " persona's steps on other interface types"
                               " don't use the costume. Move this step to a"
                               " non-wearing persona, or drop :wears from"
                               " the subject")
                          (str (pr-str (:subject svo)) " wears costume "
                               (pr-str costume) ", declared for interface "
                               (pr-str declared) ", but is provisioned on "
                               (pr-str instance)
                               " — declare the costume for " (pr-str instance)
                               " in :costumes, or wear one of its costumes"
                               (when (seq candidates)
                                 (str " " (pr-str (vec (sort candidates)))))
                               (when suggestion
                                 (str " — did you mean " (pr-str suggestion) "?"))))
               :location (step-location bs source-file)}
        suggestion (assoc :suggestion suggestion)))))

(defn- shared-wears-issues
  "Lint B: ERROR when a costume has 2+ provisioning targets in one plan.
   Targets come from the runtime's own walk
   (provisioning/collect-provisioning-targets — dedup by
   [interface subject]), so plan-time and runtime semantics cannot drift."
  [plan source-file]
  (let [targets (provisioning/collect-provisioning-targets plan)
        by-costume (group-by :wears (filter :wears targets))]
    (for [[costume ts] by-costume
          :when (< 1 (count ts))]
      (let [wearers (mapv (fn [t] (or (:subject t) (:interface t))) ts)
            subjects (distinct (keep :subject ts))
            ;; sl-xjx0 (Warden W15): ONE subject across N interfaces is a
            ;; different situation from N subjects sharing a login — the old
            ;; message diagnosed [:alice :alice] as identity-sharing.
            single-wearer? (= 1 (count subjects))]
        {:type :costume/shared-wears
         :severity :error
         :costume costume
         :wearers wearers
         :message (if single-wearer?
                    (str "costume " (pr-str costume) " is worn by "
                         (pr-str (first subjects)) " across " (count ts)
                         " interfaces ("
                         (pr-str (vec (sort (map :interface ts))))
                         ") in this scenario — a costume is"
                         " single-interface: the wearer's steps on other"
                         " interfaces must not carry it. Move those steps"
                         " to a non-wearing persona, or drop :wears")
                    (str "costume " (pr-str costume) " is worn by "
                         (count ts) " targets in this scenario ("
                         (pr-str wearers)
                         ") — a costume is single-wearer and single-interface;"
                         " share identity through the application"
                         " (account/context), not the browser profile"))
         :location (first-wearing-location plan source-file costume)}))))

(defn- undeclared-warns
  "R3: WARN once per undeclared-but-worn costume, suite-wide (the warn
   teaches one config edit; per-step repetition is spam). Location = the
   first wearing step seen."
  [plans costumes]
  (let [seen (volatile! #{})]
    (vec
     (for [plan plans
           :let [source-file (get-in plan [:plan/pickle :pickle/source-file])]
           [bs svo] (wearing-steps plan)
           :let [costume (:wears svo)]
           :when (and (not (contains? costumes costume))
                      (not (contains? @seen costume)))]
       (do (vswap! seen conj costume)
           ;; sl-xjx0 (Warden W13): a one-char typo in the :costumes KEY
           ;; used to downgrade the wrong-interface ERROR to this warn,
           ;; whose old text then prescribed declaring the typo'd name —
           ;; instructions to legitimize the defect. With a near-match
           ;; among the DECLARED names, lead with the typo hypothesis.
           (let [suggestion (text/suggest-similar
                             costume (keys costumes) 2)]
             (cond-> {:type :costume/undeclared
                      :severity :warn
                      :subject (:subject svo)
                      :costume costume
                      :message (if suggestion
                                 (str "costume " (pr-str costume) " is worn"
                                      " but not declared — did you mean "
                                      (pr-str suggestion) " (declared in"
                                      " :costumes)? If the DECLARATION is the"
                                      " typo, fix it there; only if "
                                      (pr-str costume) " is really a distinct"
                                      " costume add its own :costumes entry")
                                 (str "costume " (pr-str costume) " is worn but not"
                                      " declared — add :costumes {" (pr-str costume)
                                      " {:interface " (pr-str (:interface svo))
                                      "}} to shiftlefter.edn so wrong-interface wear"
                                      " fails at plan time"))
                      :location (step-location bs source-file)}
               suggestion (assoc :suggestion suggestion))))))))

(defn check-plans
  "Run the costume lints over bound plans. Returns issues in the
   :svo-issues shape; empty when clean or when no glossary stamped any
   :wears. `costumes` is the :costumes config map (may be nil/empty —
   undeclared wear then warns), `interfaces` the :interfaces config map."
  [plans costumes interfaces]
  (let [costumes (or costumes {})
        per-plan (mapcat (fn [plan]
                           (let [source-file (get-in plan [:plan/pickle
                                                           :pickle/source-file])]
                             (concat
                              (wrong-interface-issues plan source-file
                                                      costumes interfaces)
                              (shared-wears-issues plan source-file))))
                         plans)]
    (vec (concat per-plan (undeclared-warns plans costumes)))))
