(ns shiftlefter.stepengine.exec.hooks
  "Scenario lifecycle hook weave (sl-esq).

   Execution sequence per scenario:

     Befores -> scoped-eager provisioning -> steps -> Afters -> cleanup

   Befores run BEFORE provisioning (the legitimate Before classes — seed/
   reset external state — need no capabilities, and a broken seed hook must
   error before paying seconds of browser launch). Afters run AFTER steps
   but BEFORE capability cleanup (screenshot-on-failure and console-scraping
   need the live browser).

   LIFO unwind: Afters run in reverse order of the frames whose Befores
   succeeded (try-with-resources). An entry with no :before gets a frame
   unconditionally — its :after always unwinds. When provisioning itself
   fails, all Befores succeeded, so ALL Afters unwind — running WITHOUT
   live capabilities (tolerate-and-run; an :after must check, not assume).

   Failure semantics (ratified round 2):
   - :before throw => scenario :error (infrastructure failure — never
     :failed, never a planning error); provisioning and steps never run.
   - :after throw => scenario :error EVEN when all steps passed — a suite
     with broken cleanup is lying about its health. One :after failing
     does NOT stop the unwind (finally-style); all failures are collected
     in the :hooks records, the FIRST carries scenario attribution.

   Payloads (ratified round 4; single-map calling convention so keys can
   be added later without breaking every hook ever written):
   - :before receives {:ctx <ctx so far> :scenario <identity>}. A map
     return MERGES into ctx (nil = no contribution). NOTE: steps REPLACE
     ctx wholesale on a map return (invoke-step); Befores merge — a seed
     hook returning only {:seed/user-id 1234} must not wipe ctx. The
     envelope records :contributed (the returned map's KEYS) so 'where did
     this ctx key come from' is answerable from the report.
   - :after receives {:ctx <final ctx, capabilities live when alive>
     :scenario <identity> :result <in-flight result: status, step results
     w/ durations, error>}. The return is IGNORED — reserved for the
     attachments-era contract (an After returning attachment references)."
  (:require [shiftlefter.stepengine.bindings :as bindings]
            [shiftlefter.stepengine.exec.provisioning :as prov]))

(defn scenario-identity
  "The :scenario payload key: pickle name, tags, source file/line."
  [plan]
  (let [pickle (:plan/pickle plan)]
    {:name (:pickle/name pickle)
     :tags (mapv :name (:pickle/tags pickle))
     :source-file (:pickle/source-file pickle)
     :line (get-in pickle [:pickle/location :line])}))

(defn- hook-error
  "EDN-safe hook failure with dual attribution: the hook's name +
   registration home, and the @hook= tag's file:line (absent for globals)."
  [entry phase t]
  (cond-> {:type (keyword "hook" (str (name phase) "-failed"))
           :message (ex-message t)
           :exception-class (.getName (class t))
           :hook (:name entry)}
    (:registration entry) (assoc :registration (:registration entry))
    (:tag-source entry)   (assoc :tag-source (:tag-source entry))
    (instance? clojure.lang.ExceptionInfo t) (assoc :data (ex-data t))))

(defn- invalid-return-error
  [entry ret]
  {:type :hook/invalid-return
   :message (str "hook '" (:name entry) "' :before returned "
                 (pr-str (type ret))
                 " — a Before returns a map (merged into ctx) or nil")
   :hook (:name entry)
   :value (pr-str ret)})

(defn- elapsed-ms [start-ns]
  (/ (- (System/nanoTime) start-ns) 1e6))

(defn run-befores!
  "Run :before fns in order (globals outermost, then tag order — the
   ordering was resolved at planning; :plan/hooks is already sorted).

   Returns {:ctx ctx' :frames [entry ...] :records [record ...] :error e?}.
   :frames is the unwind list in RUN order (run-afters! reverses it):
   every entry whose :before succeeded OR that has no :before (trivially
   succeeded). Stops at the first throwing :before — its entry gets NO
   frame; later entries never run."
  [hook-entries scenario ctx]
  (loop [remaining hook-entries
         ctx ctx
         frames []
         records []]
    (if-let [{:keys [name before] :as entry} (first remaining)]
      (if (nil? before)
        ;; after-only entry: frame (its :after always unwinds), no record —
        ;; nothing ran in the :before phase.
        (recur (rest remaining) ctx (conj frames entry) records)
        (let [start (System/nanoTime)
              outcome (try
                        {:ret (before {:ctx ctx :scenario scenario})}
                        (catch Throwable t
                          {:threw t}))
              ret (:ret outcome)
              err (cond
                    (:threw outcome) (hook-error entry :before (:threw outcome))
                    (and (some? ret) (not (map? ret))) (invalid-return-error entry ret))]
          (if err
            {:ctx ctx
             :frames frames
             :records (conj records {:name name :phase :before :status :failed
                                     :duration-ms (elapsed-ms start)
                                     :error err})
             :error err}
            (recur (rest remaining)
                   ;; Root merge unchanged (open ctx, sl-esq). Additionally
                   ;; (sl-yh7): contribution keys that are bare lowerCamel
                   ;; binding names mirror into :sl/bindings and count as
                   ;; data-plane producers; namespaced/nonconforming keys
                   ;; stay machinery, invisible to {name} tokens.
                   (let [merged (if ret (merge ctx ret) ctx)
                         mirror (when ret (bindings/conforming-keys ret))]
                     (if (seq mirror)
                       (bindings/merge-bindings merged mirror)
                       merged))
                   (conj frames entry)
                   (conj records
                         (cond-> {:name name :phase :before :status :ok
                                  :duration-ms (elapsed-ms start)}
                           (and ret (seq ret))
                           (assoc :contributed (vec (keys ret)))))))))
      {:ctx ctx :frames frames :records records :error nil})))

(defn run-afters!
  "LIFO unwind of `frames` (reverse run order). NEVER throws; one failure
   does not stop the rest (finally-style). The :after return is ignored.

   Returns {:records [record ...] :errors [error ...]} — errors in unwind
   order; the first carries scenario attribution (the caller flips the
   scenario to :error when any are present)."
  [frames scenario ctx result]
  (reduce
   (fn [acc {:keys [name after] :as entry}]
     (if (nil? after)
       acc
       (let [start (System/nanoTime)
             threw (try
                     (after {:ctx ctx :scenario scenario :result result})
                     nil
                     (catch Throwable t t))]
         (if threw
           (let [err (hook-error entry :after threw)]
             (-> acc
                 (update :records conj {:name name :phase :after :status :failed
                                        :duration-ms (elapsed-ms start)
                                        :error err})
                 (update :errors conj err)))
           (update acc :records conj {:name name :phase :after :status :ok
                                      :duration-ms (elapsed-ms start)})))))
   {:records [] :errors []}
   (reverse frames)))

(defn error-scenario-result
  "Scenario result for a Before failure: status :error, ALL steps :skipped
   (contrast prov/eager-failure-result, where the first step bears the
   error — here the failure is scenario-level, attributed to the hook).
   The Befores' ctx is preserved so cleanup can still scan it."
  [plan ctx error]
  {:status :error
   :plan plan
   :steps (mapv #(prov/make-step-result % :skipped nil nil) (:plan/steps plan))
   :scenario-ctx ctx
   :error error})
