(ns shiftlefter.projection.usage-index
  "The SVO reverse index over bound plan steps (sl-mpu5, resolution
   queries v2). Binds the feature corpus WITHOUT executing it, then
   projects (S,V,O)+file:line tuples every v2 surface joins against:
   usage facets, used/unused markers, dead-vocabulary detection,
   scoped-selection partitions.

   Same-path doctrine (sl-0gfc pre-groom ruling): the corpus binds
   through `compile/compile-suite` — the runner's own entry — with the
   projection-loaded registry stepdefs, so what the index believes is
   what a run would believe. Non-runnable suites are fine: the index
   reads :matched steps only and reports the rest as caveats.

   Full-corpus vs selection: `bind-corpus` takes an optional selection
   ({:paths [...] :tag-filter {:include #{} :exclude #{}}}, the run's
   own grammar); the same index fns over the selected plans produce the
   'within this selection' partition. Deadness wording discipline lives
   in the renderer (resolution.clj), keyed off :usage-scope.

   Projection-boundary rule (graph-and-lineage.md): these tuples are
   0.5.x projections, not graph-gated — and they are the node/edge
   vocabulary the graph era consumes (prepaid graph work)."
  (:require [clojure.string :as str]
            [shiftlefter.gherkin.api :as api]
            [shiftlefter.gherkin.io :as io]
            [shiftlefter.intent.resolve :as intent-resolve]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.config :as config]
            [shiftlefter.runner.discover :as discover]
            [shiftlefter.runner.tag-disposition :as tagd]
            [shiftlefter.stepengine.compile :as compile]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.svo.glossary :as glossary]
            [shiftlefter.svo.validate :as svo-validate]))

;; -----------------------------------------------------------------------------
;; Corpus binding (parse + bind, never execute)
;; -----------------------------------------------------------------------------

(defn- parse-feature-file
  "Parse one feature file — the runner's parse semantics (read-file-utf8 →
   api/parse-string → api/pickles with the path stamped as source-file).
   Returns {:status :ok :path :pickles} or {:status :error :path :errors}."
  [path]
  (let [read-result (io/read-file-utf8 path)]
    (if (= :error (:status read-result))
      {:status :error :path path
       :errors [{:type :io/read-failed :message (:message read-result) :path path}]}
      (let [{:keys [ast errors]} (api/parse-string (:content read-result))]
        (if (seq errors)
          {:status :error :path path :errors errors}
          (let [{:keys [pickles errors]} (api/pickles ast path)]
            (if (seq errors)
              {:status :error :path path :errors errors}
              {:status :ok :path path :pickles pickles})))))))

(defn bind-corpus
  "Discover, parse, and BIND the project's feature corpus — no execution.

   `cfg` is the projection's resolved+normalized config (step-paths already
   loaded into the registry by build-projection — stepdef parity with the
   runner is by construction: same resolved paths, same loader sequence).
   `selection` (optional): {:paths [...]} overrides the config corpus
   (the run's positional-path grammar, resolved from the invocation cwd);
   {:tag-filter {:include #{..} :exclude #{..}}} applies the run's own
   tag-disposition semantics to the parsed pickles before binding.

   `opts` (optional): {:adapter-registry r} — the plan-time registry the
   corpus binds against (sl-hyey: the e0ml declared-preview registry,
   recomputed from the projection's :adapter-declarations; nil = default
   registry). Capability gating runs warn-not-kill here (compile-suite
   :suite-lint-mode :warn): a listing is not a run, so lint issues ride
   the result as :suite-lint-issues instead of suppressing every usage
   marker (the mpu5 vacuity law).

   Returns {:plans [...] :parse-errors [...] :feature-count N
            :scenario-count N :unmatched-step-count N :selection sel-or-nil
            :suite-lint-issues [...]}.
   Parse errors are per-file and non-fatal: the index over the files that
   did parse is still honest, with the caveat carried for the header."
  ([project-context cfg] (bind-corpus project-context cfg nil nil))
  ([project-context cfg selection] (bind-corpus project-context cfg selection nil))
  ([project-context cfg selection opts]
   (let [paths (if (seq (:paths selection))
                 (vec (:paths selection))
                 (project-context/resolve-config-paths
                  project-context (config/get-features cfg)))
         disc (discover/discover-feature-files-or-error paths)]
     (if (= :error (:status disc))
       {:plans [] :parse-errors [(select-keys disc [:type :message :paths])]
        :feature-count 0 :scenario-count 0 :unmatched-step-count 0
        :selection selection}
       (let [parsed (mapv parse-feature-file (:files disc))
             errors (vec (mapcat (fn [r] (map #(assoc % :path (:path r)) (:errors r)))
                                 (filter #(= :error (:status %)) parsed)))
             pickles (vec (mapcat :pickles (filter #(= :ok (:status %)) parsed)))
             pickles (if-let [rules (:tag-filter selection)]
                       (:selected (tagd/apply-dispositions rules pickles))
                       pickles)
             result (compile/compile-suite cfg pickles (registry/all-stepdefs)
                                           {:adapter-registry (:adapter-registry opts)
                                            :suite-lint-mode :warn})
             plans (vec (:plans result))
             unmatched (count (for [p plans, s (:plan/steps p)
                                    :when (not= :matched (:status s))]
                                s))]
         {:plans plans
          :parse-errors errors
          :feature-count (count (:files disc))
          :scenario-count (count plans)
          :unmatched-step-count unmatched
          :selection selection
          :suite-lint-issues (vec (get-in result [:diagnostics :suite-lint-issues]))})))))

;; -----------------------------------------------------------------------------
;; The reverse index — tuples
;; -----------------------------------------------------------------------------

(defn- intent-use
  "The intent NAME this bound object references, or nil (quoted literal,
   raw locator, :text/:key slot, unparseable ref). Mirrors the runner's
   own classification via the shared predicates in svo.validate /
   intent.resolve, so projection and planning cannot drift."
  [glossary-map interface-type {:keys [verb frame object] :as svo}]
  (when (string? object)
    (let [kind (svo-validate/object-kind glossary-map interface-type
                                         {:verb verb :frame frame})]
      (cond
        (#{:text :key} kind) nil
        (svo-validate/quoted-capture? object) nil
        (svo-validate/raw-locator? object) nil
        (= kind :location)
        ;; Bare/dotted PascalCase named-location ref — root name is the intent
        ;; (parse-intent-ref requires a dotted path, location refs may be bare).
        (when (and (svo-validate/location-refs-frame? glossary-map interface-type svo)
                   (intent-resolve/location-ref? object))
          (first (str/split object #"\.")))
        :else
        (:intent (:ok (intent-resolve/parse-intent-ref object)))))))

(defn index
  "Project bound plans into the flat tuple seq every v2 surface joins on.
   One tuple per :matched, SVO-bearing step:

   {:subject :alice :subject-type :user :verb :click :frame :default
    :object \"Login.submit\" :intent-use \"Login\"
    :interface-instance :web :interface-type :web
    :file \"sl/features/login.feature\" :line 12
    :scenario \"Alice logs in\" :scenario-id <pickle-id> :feature \"Login\"
    :tags [...] :pattern-src \"...\" :step-text \"...\" :stepdef-id ...}

   Subject TYPE resolves through the glossary (instance→type); interface
   TYPE through the config :interfaces entry (never the raw name — the
   sl-sfb1 trap)."
  [plans cfg glossary-map]
  (vec
   (for [plan plans
         :let [pickle (:plan/pickle plan)]
         step (:plan/steps plan)
         :let [svo (get-in step [:binding :svo])]
         :when (and (= :matched (:status step)) svo)]
     (let [subject (:subject svo)
           resolved (when subject (glossary/resolve-subject glossary-map subject))
           instance (:interface svo)
           iface-type (or (get-in cfg [:interfaces instance :type]) instance)]
       {:subject subject
        :subject-type (:type resolved)
        :verb (:verb svo)
        :frame (:frame svo)
        :object (:object svo)
        :intent-use (intent-use glossary-map iface-type svo)
        :interface-instance instance
        :interface-type iface-type
        :file (:pickle/source-file pickle)
        :line (get-in step [:step :step/location :line])
        :scenario (:pickle/name pickle)
        :scenario-id (:pickle/id pickle)
        :feature (:pickle/feature-name pickle)
        :tags (:pickle/tags pickle)
        :pattern-src (get-in step [:binding :pattern-src])
        :step-text (get-in step [:step :step/text])
        :stepdef-id (get-in step [:binding :stepdef/id])}))))

;; -----------------------------------------------------------------------------
;; Aggregates
;; -----------------------------------------------------------------------------

(defn- scenario-subjects
  "scenario-id → distinct subject set, over the whole tuple seq."
  [tuples]
  (reduce (fn [m t]
            (if (:subject t)
              (update m (:scenario-id t) (fnil conj #{}) (:subject t))
              m))
          {} tuples))

(defn summarize-hits
  "Shared hit summary for a tuple subset: the per-hit vector (file:line,
   scenario, step text, pattern — authoring-by-example) plus counts."
  [tuples]
  {:hit-count (count tuples)
   :scenario-count (count (distinct (map :scenario-id tuples)))
   :file-count (count (distinct (map :file tuples)))
   :hits (mapv #(select-keys % [:file :line :scenario :step-text :pattern-src
                                :verb :frame :object :interface-type])
               tuples)})

(defn usage-for-subject
  "Addendum 1's actor-usage aggregate for one subject INSTANCE keyword:
   instance hits, type rollup, multi-actor flag (appears in a scenario
   with 2+ distinct actors), co-occurrence pair frequencies (the future
   substitution planner's candidate inventory)."
  [tuples glossary-map subject-kw]
  (let [resolved (glossary/resolve-subject glossary-map subject-kw)
        subject-type (:type resolved)
        ;; Steps may write the bare instance (:alice) or the qualified form
        ;; (:user/alice) — both are the same actor (sl-s2oa annotation
        ;; semantics), so match the resolved identity, not the input spelling.
        forms (into #{subject-kw}
                    (remove nil? [(:instance resolved) (:qualified resolved)]))
        instance-tuples (filterv #(contains? forms (:subject %)) tuples)
        type-tuples (when subject-type
                      (filterv #(= subject-type (:subject-type %)) tuples))
        by-scenario (scenario-subjects tuples)
        my-scenarios (set (map :scenario-id instance-tuples))
        co-freqs (->> my-scenarios
                      (mapcat #(apply disj (get by-scenario % #{}) forms))
                      frequencies
                      (sort-by (comp - val))
                      (mapv (fn [[other n]] {:with other :scenarios n})))]
    {:subject subject-kw
     :subject-type subject-type
     :instance (summarize-hits instance-tuples)
     :type (when subject-type (summarize-hits type-tuples))
     :multi-actor? (boolean (some #(> (count (get by-scenario %)) 1) my-scenarios))
     :co-occurrence co-freqs}))

(defn used-sets
  "The deadness join: what the corpus actually references.
   {:subjects #{instance-kw} :subject-types #{type-kw}
    :verbs #{[interface-type verb]} :intents #{\"Login\" ...}
    :stepdef-ids #{...}}"
  [tuples]
  {:subjects (into #{} (keep :subject) tuples)
   :subject-types (into #{} (keep :subject-type) tuples)
   :verbs (into #{} (keep (fn [t] (when (:verb t) [(:interface-type t) (:verb t)]))) tuples)
   :intents (into #{} (keep :intent-use) tuples)
   :stepdef-ids (into #{} (keep :stepdef-id) tuples)})
