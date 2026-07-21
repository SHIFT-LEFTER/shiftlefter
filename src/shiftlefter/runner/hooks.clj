(ns shiftlefter.runner.hooks
  "Scenario lifecycle hook registry via a `hooks.clj` convention file (sl-esq).

   ## What hooks.clj is for

   Hooks are the GRADED ESCAPE HATCH for lifecycle code around a scenario:
   Before hooks (seed/reset external state) and After hooks (screenshot on
   failure, console scraping). Scenarios NAME their hooks via `@hook=<name>`
   value-tags — the feature file states what runs around it; nothing fires
   invisibly. Registrations live here so there is exactly ONE enumerable
   place to look.

   ## The contract

   When a `hooks.clj` sits next to the active `shiftlefter.edn`, the runner
   load-files it and invokes `hooks/hooks` (var or 1-arg fn of config) —
   the same discovery-and-shape convention as setup.clj. A MISSING file is
   silently fine: no hooks.

   `hooks` returns an ORDERED vector of hook entries:

   ```clojure
   (ns hooks)

   (def hooks
     [{:name   \"reset-db\"                ;; required, unique
       :before (fn [{:keys [ctx scenario]}] {:seed/user-id 1234})
       :after  (fn [{:keys [ctx scenario result]}] nil)
       :global? false                       ;; applies to every scenario
       :requires-serial false}])            ;; auto-serialize carrying scenarios
   ```

   - `:name`   — required, unique across the vector. What `@hook=<name>`
                 resolves against.
   - `:before` — optional `(fn [{:keys [ctx scenario]}])`. A map return
                 merges into ctx (nil = no contribution).
   - `:after`  — optional `(fn [{:keys [ctx scenario result]}])`. Return
                 IGNORED (reserved for the attachments-era contract).
   - `:global?` — applies to every scenario, stamped and previewed exactly
                 like a named hook; vector order = execution order among
                 globals.
   - `:requires-serial` — scenarios this hook applies to are auto-serialized
                 under parallel execution ({:schedule {:serial? true
                 :reason [:hook <name>]}}).
   - `:provides` — optional vector of binding names (bare lowerCamel
                 keywords, e.g. [:sessionToken]) this hook's :before seeds
                 into the scenario data plane (sl-yh7). Static declaration
                 for the dry-run consumed-without-producer check; conforming
                 contribution keys mirror into :sl/bindings either way.

   A malformed file or a duplicate `:name` is a PLANNING error (exit 2)."
  (:require [babashka.fs :as fs]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [shiftlefter.stepengine.bindings :as bindings]
            [shiftlefter.runner.tag-disposition :as tagd]))

;; -----------------------------------------------------------------------------
;; Specs — Hook Entry Shape
;; -----------------------------------------------------------------------------

(s/def ::name (s/and string? seq))
;; ifn? has no built-in generator; provide no-ops so spec-health
;; (test/shiftlefter/spec_health_test.clj) can exercise these specs.
(s/def ::before (s/with-gen ifn? #(gen/return (fn [_payload] nil))))
(s/def ::after  (s/with-gen ifn? #(gen/return (fn [_payload] nil))))
(s/def ::global? boolean?)
(s/def ::requires-serial boolean?)
;; :provides (sl-yh7): binding names this hook's :before seeds into the
;; scenario data plane (bare lowerCamel keywords). Purely a STATIC
;; declaration — the dry-run consumed-without-producer check can't see
;; runtime returns, so hooks that seed bindings declare them here.
;; Undeclared contributions still mirror and resolve at exec.
(s/def ::provides (s/coll-of ::bindings/binding-name :kind vector?))

(s/def ::hook-entry
  (s/keys :req-un [::name]
          :opt-un [::before ::after ::global? ::requires-serial ::provides]))

(s/def ::hook-list
  (s/coll-of ::hook-entry :kind vector?))

;; -----------------------------------------------------------------------------
;; File Resolution — sibling-of-config, exactly like setup.clj
;; -----------------------------------------------------------------------------

(defn find-hooks-file
  "Locate hooks.clj as a sibling of the active config file.

   `config-path` is the path to the resolved `shiftlefter.edn` (whatever
   `load-config` actually loaded). If nil (built-in defaults, no config
   file present), there is no project root and therefore no hooks.clj.
   A missing file is silently fine — no hooks.

   Returns absolute path string or nil."
  [config-or-path]
  (let [config-path (if (map? config-or-path)
                      (:config-path config-or-path)
                      config-or-path)]
    (when config-path
      (let [base  (or (some-> config-path fs/parent str) ".")
            hooks (str (fs/path base "hooks.clj"))]
        (when (fs/exists? hooks)
          (str (fs/real-path hooks)))))))

;; -----------------------------------------------------------------------------
;; Loader
;; -----------------------------------------------------------------------------

(defn- resolve-hooks-var
  "After load-file, resolve the `hooks/hooks` var. Hook files declare
   `(ns hooks ...)` by convention; we look the symbol up there."
  []
  (or (ns-resolve 'hooks 'hooks)
      (throw (ex-info "hooks.clj must declare (ns hooks ...) and define `hooks`"
                      {:type :hooks/missing-hooks-var}))))

(defn- duplicate-names
  "Names appearing more than once, in first-occurrence order."
  [entries]
  (->> (map :name entries)
       frequencies
       (keep (fn [[n c]] (when (> c 1) n)))
       vec))

(defn load-hooks
  "Load hooks.clj and produce its ordered hook registry.

   1. Force a clean reload by remove-ns'ing 'hooks before load-file.
   2. load-file the path (raises on syntax/eval errors).
   3. Resolve `hooks/hooks` — may be a var holding a vector or a fn
      that takes config. Both shapes are accepted.
   4. Validate against ::hook-list; reject duplicate :name entries
      (the registry sells NAMES — one name, one meaning).

   Returns:
   - {:ok [hook-entry ...] :path path} on success (vector order preserved —
     it IS the global execution order)
   - {:error {:type ... :message ... :path ...}} on any failure"
  [hooks-path config]
  (try
    (remove-ns 'hooks)
    (load-file hooks-path)
    (let [hooks-var (resolve-hooks-var)
          raw       (var-get hooks-var)
          entries   (if (fn? raw) (raw config) raw)
          dups      (when (sequential? entries) (duplicate-names entries))]
      (cond
        (not (s/valid? ::hook-list entries))
        {:error {:type    :hooks/invalid-shape
                 :message (str "hooks.clj `hooks` failed validation: "
                               (s/explain-str ::hook-list entries))
                 :explain (s/explain-data ::hook-list entries)
                 :path    hooks-path}}

        (seq dups)
        {:error {:type    :hooks/duplicate-name
                 :message (str "hooks.clj registers duplicate hook name(s): "
                               (pr-str dups)
                               " — one name, one meaning; rename or merge them")
                 :names   dups
                 :path    hooks-path}}

        :else
        {:ok (vec entries) :path hooks-path}))
    (catch Throwable t
      {:error {:type    :hooks/load-failed
               :message (str "Failed to load hooks.clj: " (ex-message t))
               :path    hooks-path
               :cause   (.getName (class t))}})))

;; -----------------------------------------------------------------------------
;; Plan attachment — mirror of schedule/attach-schedules
;; -----------------------------------------------------------------------------

(defn- resolved-entry
  "The shape that rides :plan/hooks: the registration's relevant keys plus
   attribution — :registration (where the hook lives) and :tag-source (the
   @hook= tag's file:line; nil for :global? entries, which no tag names)."
  [entry tag-source hooks-path]
  (cond-> (select-keys entry [:name :before :after :global? :requires-serial
                              :provides])
    hooks-path (assoc :registration {:path hooks-path})
    tag-source (assoc :tag-source tag-source)))

(defn- resolve-plan-hooks
  "Resolve one plan's hooks: read the :hooks facet from the tag-disposition
   seam, look each name up in the registry, prepend :global? entries
   (registry vector order = outermost), dedupe by name FIRST-OCCURRENCE-WINS
   (a hook named at two positions runs once, at the outermost).

   Returns {:hooks [resolved...]} or {:unknowns [{:name :file :line}...]}."
  [plan name-index globals hooks-path]
  (let [facet (:hooks (tagd/disposition nil (:plan/pickle plan)))
        source-file (get-in plan [:plan/pickle :pickle/source-file])
        tagged (mapv (fn [{:keys [name location]}]
                       (if-let [entry (get name-index name)]
                         {:entry entry
                          :tag-source {:file source-file :line (:line location)}}
                         {:unknown {:name name :file source-file
                                    :line (:line location)}}))
                     facet)
        unknowns (into [] (keep :unknown) tagged)]
    (if (seq unknowns)
      {:unknowns unknowns}
      (let [combined (concat (map (fn [e] {:entry e}) globals) tagged)]
        {:hooks (:acc (reduce (fn [{:keys [seen] :as st} {:keys [entry tag-source]}]
                                (if (seen (:name entry))
                                  st
                                  (-> st
                                      (update :seen conj (:name entry))
                                      (update :acc conj (resolved-entry entry tag-source hooks-path)))))
                              {:seen #{} :acc []}
                              combined))}))))

(defn- unknown-names-message
  [unknowns entries hooks-path]
  (str "Unknown hook name(s): "
       (str/join "; " (map (fn [{:keys [name file line]}]
                             (str "@hook=" name " at " file ":" line))
                           unknowns))
       (if hooks-path
         (str " — known hooks in " hooks-path ": "
              (if (seq entries) (str/join ", " (map :name entries)) "(none)"))
         " — no hooks.clj found next to the active shiftlefter.edn")))

(defn attach-hooks
  "Assoc :plan/hooks (ordered vector of resolved hook entries) onto each
   plan the registry applies to. Plans with no hooks are returned untouched
   (no nil/empty-valued key — byte-identity for hook-less suites).

   `registry` is the loaded ordered vector (nil/empty when no hooks.clj —
   still valid: any @hook= tag then names an unknown hook). `hooks-path`
   is where the registry was loaded from, for attribution.

   Returns:
   - {:ok plans'} on success
   - {:error {:type :hooks/unknown-name ...}} listing EVERY unknown
     @hook=<name> with its file:line (deduped — outline expansion yields
     one pickle per row from the same tag)."
  [plans registry hooks-path]
  (let [entries (vec (or registry []))
        name-index (into {} (map (juxt :name identity)) entries)
        globals (filterv :global? entries)
        results (mapv #(resolve-plan-hooks % name-index globals hooks-path) plans)
        unknowns (into [] (comp (mapcat :unknowns) (distinct)) results)]
    (if (seq unknowns)
      {:error {:type    :hooks/unknown-name
               :message (unknown-names-message unknowns entries hooks-path)
               :unknown unknowns
               :path    hooks-path}}
      {:ok (mapv (fn [plan {:keys [hooks]}]
                   (if (seq hooks)
                     (assoc plan :plan/hooks hooks)
                     plan))
                 plans results)})))
