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
     w/ durations, error>}. The return position is LIVE (sl-l9nj, the
     attachments-era redemption): nil = no claim; {:attachments [ref …]}
     is a spec-validated claim (closed map — see
     shiftlefter.attachments/::after-return), each ref framework-stamped
     :attachment/source [:hook <name>]. Anything else is
     :hook/invalid-return => scenario :error (FULL-STRICT, Chair-ratified
     2026-08-03 — Before symmetry; a malformed claim dies loudly, never
     silently).
   - Both payloads carry :artifacts-dir (absolute path, pre-created) when
     the run has a results dir — the sanctioned place for hook-written
     artifact files. Absent (not nil) otherwise."
  (:require [clojure.spec.alpha :as s]
            [shiftlefter.attachments :as attachments]
            [shiftlefter.stepengine.bindings :as bindings]
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
   registration home, and the @hook= tag's file:line (absent for globals).

   An NPE in a :before very likely means the hook body reached for a
   capability — which cannot exist yet (Befores run before provisioning) —
   so the message carries that hint (sl-7kct)."
  [entry phase t]
  (cond-> {:type (keyword "hook" (str (name phase) "-failed"))
           :message (cond-> (ex-message t)
                      (and (= :before phase)
                           (instance? NullPointerException t))
                      (str " (note: Befores run before capability"
                           " provisioning — :ctx has no live capabilities"
                           " in a :before)"))
           :exception-class (.getName (class t))
           :hook (:name entry)}
    (:registration entry) (assoc :registration (:registration entry))
    (:tag-source entry)   (assoc :tag-source (:tag-source entry))
    (instance? clojure.lang.ExceptionInfo t) (assoc :data (ex-data t))))

(defn- invalid-return-error
  [entry phase ret]
  {:type :hook/invalid-return
   :message (str "hook '" (:name entry) "' " phase " returned "
                 (pr-str (type ret))
                 (case phase
                   :before " — a Before returns a map (merged into ctx) or nil"
                   :after (str " — an After returns nil or {:attachments"
                               " [ref …]}, nothing else (full-strict;"
                               " refs need :attachment/kind +"
                               " :attachment/path)")))
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
   frame; later entries never run.

   `extras` (optional) merges into every hook payload — the single-map
   convention's extension point (sl-l9nj adds :artifacts-dir)."
  ([hook-entries scenario ctx]
   (run-befores! hook-entries scenario ctx nil))
  ([hook-entries scenario ctx extras]
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
                         {:ret (before (merge {:ctx ctx :scenario scenario}
                                              extras))}
                         (catch Throwable t
                           {:threw t}))
               ret (:ret outcome)
               err (cond
                     (:threw outcome) (hook-error entry :before (:threw outcome))
                     (and (some? ret) (not (map? ret))) (invalid-return-error entry :before ret))]
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
      {:ctx ctx :frames frames :records records :error nil}))))

(defn run-afters!
  "LIFO unwind of `frames` (reverse run order). NEVER throws; one failure
   does not stop the rest (finally-style).

   The :after return position is LIVE (sl-l9nj): nil = no claim; a valid
   {:attachments [ref …]} claim (attachments/::after-return, closed map)
   has each ref stamped :attachment/source [:hook <name>] and accumulated;
   any other return is :hook/invalid-return (FULL-STRICT — the caller
   flips the scenario to :error, same as a throw).

   Returns {:records [record ...] :errors [error ...] :attachments
   [ref ...]} — errors in unwind order (the first carries scenario
   attribution); attachments in unwind order, validated and stamped but
   NOT yet finalized against the filesystem (the caller owns
   attachments/finalize-refs! — this fn stays pure of IO).

   `extras` (optional) merges into every hook payload (sl-l9nj adds
   :artifacts-dir)."
  ([frames scenario ctx result]
   (run-afters! frames scenario ctx result nil))
  ([frames scenario ctx result extras]
   (reduce
    (fn [acc {:keys [name after] :as entry}]
      (if (nil? after)
        acc
        (let [start (System/nanoTime)
              outcome (try
                        {:ret (after (merge {:ctx ctx :scenario scenario
                                             :result result}
                                            extras))}
                        (catch Throwable t
                          {:threw t}))
              ret (:ret outcome)
              err (cond
                    (:threw outcome)
                    (hook-error entry :after (:threw outcome))

                    (and (some? ret)
                         (not (s/valid? ::attachments/after-return ret)))
                    (invalid-return-error entry :after ret))]
          (cond
            err
            (-> acc
                (update :records conj {:name name :phase :after :status :failed
                                       :duration-ms (elapsed-ms start)
                                       :error err})
                (update :errors conj err))

            (some? ret)
            (-> acc
                (update :records conj {:name name :phase :after :status :ok
                                       :duration-ms (elapsed-ms start)})
                (update :attachments into
                        (map #(attachments/stamp-source name %)
                             (:attachments ret))))

            :else
            (update acc :records conj {:name name :phase :after :status :ok
                                       :duration-ms (elapsed-ms start)})))))
    {:records [] :errors [] :attachments []}
    (reverse frames))))

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
