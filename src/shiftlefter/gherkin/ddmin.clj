(ns shiftlefter.gherkin.ddmin
  "Delta-debugging minimizer for Gherkin files.

   Shrinks a failing .feature file while preserving the same failure signature.
   Operates in two strategies:
   - structured (default): removes whole constructs (scenarios, steps, tables, etc.)
   - raw-lines: removes arbitrary lines (for lex bugs, edge cases)

   Usage:
     (ddmin content {:mode :parse :timeout-ms 200})
     (ddmin content {:mode :auto :strategy :raw-lines})

   CLI:
     sl gherkin ddmin <path> --mode parse|pickles|lex|auto [--strategy structured|raw-lines]"
  (:require [shiftlefter.gherkin.api :as api]
            [shiftlefter.gherkin.fuzz :as fuzz]))

;; -----------------------------------------------------------------------------
;; Signature matching (from FZ3)
;; -----------------------------------------------------------------------------

(defn make-signature
  "Create signature from check result for comparison."
  [{:keys [phase reason] :as result}]
  (let [error-type (-> result :details :errors first :type)]
    {:phase phase
     :reason reason
     :error/type error-type}))

(defn signatures-match?
  "Check if two signatures match (same failure class)."
  [sig1 sig2]
  (and (= (:phase sig1) (:phase sig2))
       (= (:reason sig1) (:reason sig2))
       ;; error/type must match if both present, or both nil
       (= (:error/type sig1) (:error/type sig2))))

;; -----------------------------------------------------------------------------
;; Failure checking (reuses FZ3 infrastructure)
;; -----------------------------------------------------------------------------

(defn check-failure
  "Check content for failure. Returns result map with :status, :reason, :phase, etc.
   Uses same structure as fuzz/check-mutation-invariants."
  [content mode timeout-ms]
  (case mode
    :lex
    ;; For lex mode, check if lexing produces errors
    (try
      (let [{:keys [errors]} (api/lex-string content)]
        (if (seq errors)
          {:status :ok :reason :graceful-errors :phase :lex
           :details {:errors errors}}
          {:status :ok :reason :no-failure :phase :lex}))
      (catch Throwable t
        {:status :fail :reason :uncaught-exception :phase :lex
         :details {:exception-class (str (class t))
                   :message (.getMessage t)}}))

    :parse
    ;; Parse-only mode
    (let [result (fuzz/parse-with-timeout content timeout-ms)]
      (case (:status result)
        :timeout {:status :fail :reason :timeout :phase :parse
                  :details {:timeout-ms timeout-ms}}
        :exception {:status :fail :reason :uncaught-exception :phase :parse
                    :details {:exception-class (str (class (:exception result)))}}
        :ok (let [{:keys [errors]} (:result result)]
              (if (seq errors)
                {:status :ok :reason :graceful-errors :phase :parse
                 :details {:errors errors}}
                {:status :ok :reason :no-failure :phase :parse}))))

    :pickles
    ;; Parse + pickles mode
    (let [parse-result (fuzz/parse-with-timeout content timeout-ms)]
      (case (:status parse-result)
        :timeout {:status :fail :reason :timeout :phase :parse
                  :details {:timeout-ms timeout-ms}}
        :exception {:status :fail :reason :uncaught-exception :phase :parse
                    :details {:exception-class (str (class (:exception parse-result)))}}
        :ok (let [{:keys [ast errors]} (:result parse-result)]
              (if (seq errors)
                ;; Parse failed - that's the failure
                {:status :ok :reason :graceful-errors :phase :parse
                 :details {:errors errors}}
                ;; Parse succeeded, try pickles
                (let [pickle-result (fuzz/pickles-with-timeout ast "ddmin.feature" timeout-ms)]
                  (case (:status pickle-result)
                    :timeout {:status :fail :reason :timeout :phase :pickles
                              :details {:timeout-ms timeout-ms}}
                    :exception {:status :fail :reason :uncaught-exception :phase :pickles
                                :details {:exception-class (str (class (:exception pickle-result)))}}
                    :ok (let [{:keys [errors]} (:result pickle-result)]
                          (if (seq errors)
                            {:status :ok :reason :graceful-errors :phase :pickles
                             :details {:errors errors}}
                            {:status :ok :reason :no-failure :phase :pickles}))))))))))

(defn infer-mode
  "Infer the appropriate mode from an initial failure check result."
  [result]
  (case (:phase result)
    :lex :lex
    :parse :parse
    :pickles :pickles
    :parse)) ;; default

;; -----------------------------------------------------------------------------
;; Deletion Units (structured mode)
;; -----------------------------------------------------------------------------

(defn- collect-units-from-node
  "Recursively collect deletion units from an AST node.
   Returns [{:type kw :span {:start-idx N :end-idx M} :node node} ...]"
  [node]
  (when (and node (:span node))
    (let [node-type (:type node)
          base-unit {:type node-type :span (:span node) :node node}]
      (case node-type
        :feature
        ;; Feature itself is not deletable, but its children are
        (let [children (:children node)]
          (mapcat collect-units-from-node children))

        :rule
        ;; Rule is deletable, and contains children
        (let [children (:children node)
              child-units (mapcat collect-units-from-node children)]
          (cons base-unit child-units))

        (:scenario :scenario-outline)
        ;; Scenario is deletable, steps and examples are also deletable
        (let [step-units (map (fn [s] {:type :step :span (:span s) :node s})
                              (:steps node))
              example-units (mapcat collect-units-from-node (:examples node))]
          (concat [base-unit] step-units example-units))

        :background
        ;; Background is deletable, steps inside are also deletable
        (let [step-units (map (fn [s] {:type :step :span (:span s) :node s})
                              (:steps node))]
          (cons base-unit step-units))

        :examples
        ;; Examples block is deletable as a whole
        [base-unit]

        :step
        ;; Step with its argument (docstring/datatable) is one unit
        [base-unit]

        ;; Other types - just return if they have a span
        (when (:span node)
          [base-unit])))))

(defn identify-units
  "Identify deletion units from content.
   Returns {:tokens [...] :units [{:type kw :span {:start-idx N :end-idx M}} ...]}
   Units are sorted by start-idx."
  [content]
  (let [{:keys [tokens ast]} (api/parse-string content)]
    (if (empty? ast)
      ;; No AST - can't do structured deletion
      {:tokens tokens :units [] :parse-failed? true}
      (let [units (->> ast
                       (mapcat collect-units-from-node)
                       (filter identity)
                       (sort-by #(get-in % [:span :start-idx]))
                       vec)]
        {:tokens tokens :units units :parse-failed? false}))))

(defn remove-unit
  "Remove a unit from tokens. Returns new token vector."
  [tokens {:keys [span]}]
  (let [{:keys [start-idx end-idx]} span]
    (into (subvec tokens 0 start-idx)
          (subvec tokens end-idx))))

(defn tokens->content
  "Reconstruct content from tokens."
  [tokens]
  (apply str (map :raw tokens)))

;; -----------------------------------------------------------------------------
;; Core ddmin algorithm
;; -----------------------------------------------------------------------------

(defn- partition-units
  "Partition units into n roughly equal chunks."
  [units n]
  (let [size (count units)
        chunk-size (max 1 (quot size n))]
    (->> units
         (partition-all chunk-size)
         (map vec)
         vec)))

(defn- compute-removed-indices
  "Compute set of token indices covered by units."
  [units]
  (reduce (fn [indices {:keys [span]}]
            (let [{:keys [start-idx end-idx]} span]
              (into indices (range start-idx end-idx))))
          #{}
          units))

(defn- remove-units
  "Remove multiple units from tokens by filtering out covered indices."
  [tokens units]
  (let [removed-indices (compute-removed-indices units)]
    (vec (keep-indexed
          (fn [idx token]
            (when-not (contains? removed-indices idx)
              token))
          tokens))))

(defn ddmin-units
  "Core ddmin algorithm operating on deletion units.
   Returns {:minimized content :steps N :units-removed N}."
  [tokens units predicate-fn {:keys [max-iterations] :or {max-iterations 1000}}]
  (let [steps (atom 0)
        units-removed (atom 0)]

    (loop [current-tokens tokens
           current-units units
           n 2]
      (swap! steps inc)

      (cond
        ;; Safety: max iterations
        (> @steps max-iterations)
        {:minimized (tokens->content current-tokens)
         :steps @steps
         :units-removed @units-removed
         :stopped-reason :max-iterations}

        ;; No more units to try
        (empty? current-units)
        {:minimized (tokens->content current-tokens)
         :steps @steps
         :units-removed @units-removed
         :stopped-reason :no-units}

        ;; Only one unit left
        (= 1 (count current-units))
        (let [unit (first current-units)
              reduced-tokens (remove-units current-tokens [unit])
              reduced-content (tokens->content reduced-tokens)]
          (if (predicate-fn reduced-content)
            ;; Can remove the last unit
            (do
              (swap! units-removed inc)
              {:minimized reduced-content
               :steps @steps
               :units-removed @units-removed
               :stopped-reason :fully-minimized})
            ;; Can't remove it
            {:minimized (tokens->content current-tokens)
             :steps @steps
             :units-removed @units-removed
             :stopped-reason :minimal}))

        :else
        ;; Try partitioning and removing chunks
        (let [partitions (partition-units current-units n)
              ;; Try removing each partition
              result (reduce
                      (fn [_ [idx partition]]
                        (let [reduced-tokens (remove-units current-tokens partition)
                              reduced-content (tokens->content reduced-tokens)]
                          (when (predicate-fn reduced-content)
                            ;; Success! Remove this partition and continue
                            (swap! units-removed + (count partition))
                            (reduced {:success true
                                      :new-tokens reduced-tokens
                                      :new-units (vec (mapcat identity
                                                              (concat (take idx partitions)
                                                                      (drop (inc idx) partitions))))}))))
                      nil
                      (map-indexed vector partitions))]

          (if (:success result)
            ;; Found a removable partition - restart with n=2
            (recur (:new-tokens result) (:new-units result) 2)
            ;; No partition worked - increase granularity
            (if (>= n (count current-units))
              ;; Already at max granularity - try individual units
              (let [individual-result
                    (reduce
                     (fn [acc unit]
                       (let [reduced-tokens (remove-units current-tokens [unit])
                             reduced-content (tokens->content reduced-tokens)]
                         (if (predicate-fn reduced-content)
                           ;; Can remove this unit
                           (do
                             (swap! units-removed inc)
                             (reduced {:success true
                                       :new-tokens reduced-tokens
                                       :new-units (vec (remove #(= % unit) current-units))}))
                           acc)))
                     nil
                     current-units)]
                (if (:success individual-result)
                  (recur (:new-tokens individual-result) (:new-units individual-result) 2)
                  ;; Can't remove any individual unit - we're done
                  {:minimized (tokens->content current-tokens)
                   :steps @steps
                   :units-removed @units-removed
                   :stopped-reason :minimal}))
              ;; Increase granularity
              (recur current-tokens current-units (min (* 2 n) (count current-units))))))))))

;; -----------------------------------------------------------------------------
;; Raw lines ddmin (fallback)
;; -----------------------------------------------------------------------------

(defn- split-lines-keep-endings
  "Split content into lines, preserving line endings."
  [content]
  (vec (re-seq #"[^\n]*\n?" content)))

(defn- join-lines
  "Join lines back into content."
  [lines]
  (apply str lines))

(defn ddmin-lines
  "Core ddmin algorithm operating on raw lines.
   Returns {:minimized content :steps N :lines-removed N}."
  [content predicate-fn {:keys [max-iterations] :or {max-iterations 1000}}]
  (let [lines (split-lines-keep-endings content)
        steps (atom 0)
        lines-removed (atom 0)]

    (loop [current-lines lines
           n 2]
      (swap! steps inc)

      (cond
        ;; Safety: max iterations
        (> @steps max-iterations)
        {:minimized (join-lines current-lines)
         :steps @steps
         :lines-removed @lines-removed
         :stopped-reason :max-iterations}

        ;; No more lines
        (empty? current-lines)
        {:minimized ""
         :steps @steps
         :lines-removed @lines-removed
         :stopped-reason :empty}

        ;; Only one line left
        (= 1 (count current-lines))
        {:minimized (join-lines current-lines)
         :steps @steps
         :lines-removed @lines-removed
         :stopped-reason :minimal}

        :else
        (let [size (count current-lines)
              chunk-size (max 1 (quot size n))
              partitions (->> current-lines
                              (partition-all chunk-size)
                              (map vec)
                              vec)

              ;; Try removing each partition
              result (reduce
                      (fn [_ [idx partition]]
                        (let [remaining (vec (mapcat identity
                                                     (concat (take idx partitions)
                                                             (drop (inc idx) partitions))))
                              reduced-content (join-lines remaining)]
                          (when (predicate-fn reduced-content)
                            (swap! lines-removed + (count partition))
                            (reduced {:success true :new-lines remaining}))))
                      nil
                      (map-indexed vector partitions))]

          (if (:success result)
            (recur (:new-lines result) 2)
            (if (>= n size)
              ;; Try individual lines
              (let [individual-result
                    (reduce
                     (fn [_ idx]
                       (let [remaining (vec (concat (take idx current-lines)
                                                    (drop (inc idx) current-lines)))
                             reduced-content (join-lines remaining)]
                         (when (predicate-fn reduced-content)
                           (swap! lines-removed inc)
                           (reduced {:success true :new-lines remaining}))))
                     nil
                     (range size))]
                (if (:success individual-result)
                  (recur (:new-lines individual-result) 2)
                  {:minimized (join-lines current-lines)
                   :steps @steps
                   :lines-removed @lines-removed
                   :stopped-reason :minimal}))
              (recur current-lines (min (* 2 n) size)))))))))

;; -----------------------------------------------------------------------------
;; Main API
;; -----------------------------------------------------------------------------

(defn ddmin
  "Delta-debugging minimizer for Gherkin content.

   Options:
   - :mode        — :parse, :pickles, :lex, or :auto (default :auto)
   - :strategy    — :structured (default) or :raw-lines
   - :timeout-ms  — per-check timeout (default 200)
   - :budget-ms   — global time budget (default 30000)
   - :max-iterations — max ddmin iterations (default 1000)
   - :baseline-sig — pre-computed baseline signature (optional)

   Returns:
   {:original content
    :minimized content
    :baseline-sig signature
    :minimized-sig signature
    :steps N
    :removed N  ; units or lines removed
    :strategy :structured|:raw-lines
    :mode :parse|:pickles|:lex
    :timing {:total-ms N}
    :stopped-reason kw}"
  [content opts]
  (let [start-time (System/currentTimeMillis)
        {:keys [mode strategy timeout-ms budget-ms max-iterations baseline-sig]
         :or {mode :auto
              strategy nil  ; will be inferred
              timeout-ms 200
              budget-ms 30000
              max-iterations 1000}} opts

        ;; Normalize nil mode to :auto (CLI may pass nil when no --mode specified)
        mode (or mode :auto)

        ;; Get baseline failure
        initial-mode (if (= mode :auto) :parse mode)
        baseline-result (check-failure content initial-mode timeout-ms)

        ;; Validate we have a failure
        _ (when (= :no-failure (:reason baseline-result))
            (throw (ex-info "Content does not produce a failure"
                            {:content-preview (subs content 0 (min 100 (count content)))})))

        ;; Infer mode if auto
        effective-mode (if (= mode :auto)
                         (infer-mode baseline-result)
                         mode)

        ;; Infer strategy if not specified
        effective-strategy (or strategy
                               (if (= effective-mode :lex) :raw-lines :structured))

        ;; Create baseline signature
        baseline-signature (or baseline-sig (make-signature baseline-result))

        ;; Create predicate function
        predicate-fn (fn [test-content]
                       (let [elapsed (- (System/currentTimeMillis) start-time)]
                         (when (> elapsed budget-ms)
                           (throw (ex-info "Budget exceeded" {:elapsed elapsed :budget budget-ms})))
                         (let [result (check-failure test-content effective-mode timeout-ms)
                               test-sig (make-signature result)]
                           (signatures-match? baseline-signature test-sig))))

        ;; Run ddmin
        result (try
                 (if (= effective-strategy :raw-lines)
                   (ddmin-lines content predicate-fn {:max-iterations max-iterations})
                   (let [{:keys [tokens units parse-failed?]} (identify-units content)]
                     (if (or parse-failed? (empty? units))
                       ;; Fall back to raw-lines if can't parse
                       (ddmin-lines content predicate-fn {:max-iterations max-iterations})
                       (ddmin-units tokens units predicate-fn {:max-iterations max-iterations}))))
                 (catch clojure.lang.ExceptionInfo e
                   (if (= "Budget exceeded" (ex-message e))
                     {:minimized content
                      :steps 0
                      :stopped-reason :budget-exceeded}
                     (throw e))))

        end-time (System/currentTimeMillis)

        ;; Get minimized signature for verification
        minimized-result (check-failure (:minimized result) effective-mode timeout-ms)
        minimized-signature (make-signature minimized-result)]

    {:original content
     :minimized (:minimized result)
     :baseline-sig baseline-signature
     :minimized-sig minimized-signature
     :signatures-match? (signatures-match? baseline-signature minimized-signature)
     :steps (:steps result)
     :removed (or (:units-removed result) (:lines-removed result) 0)
     :strategy effective-strategy
     :mode effective-mode
     :timing {:total-ms (- end-time start-time)}
     :stopped-reason (:stopped-reason result)
     :reduction-ratio (if (pos? (count content))
                        (double (/ (count (:minimized result)) (count content)))
                        1.0)}))

;; -----------------------------------------------------------------------------
;; Artifact integration
;; -----------------------------------------------------------------------------

(defn ddmin-artifact
  "Run ddmin on a fuzz artifact directory.
   Reads result.edn for baseline, writes min.feature and ddmin.edn."
  [artifact-dir opts]
  (let [case-file (str artifact-dir "/case.feature")
        result-file (str artifact-dir "/result.edn")

        content (slurp case-file)
        saved-result (read-string (slurp result-file))

        ;; Extract baseline from saved result
        baseline-sig (:signature saved-result)
        mode (or (:mode opts)
                 (case (:phase saved-result)
                   :parse :parse
                   :pickles :pickles
                   :lex :lex
                   :parse))

        result (ddmin content (assoc opts
                                     :mode mode
                                     :baseline-sig baseline-sig))

        ;; Write outputs
        min-file (str artifact-dir "/min.feature")
        ddmin-file (str artifact-dir "/ddmin.edn")]

    (spit min-file (:minimized result))
    (spit ddmin-file (pr-str {:baseline-sig baseline-sig
                              :minimized-sig (:minimized-sig result)
                              :signatures-match? (:signatures-match? result)
                              :steps (:steps result)
                              :removed (:removed result)
                              :strategy (:strategy result)
                              :mode (:mode result)
                              :timing (:timing result)
                              :stopped-reason (:stopped-reason result)
                              :reduction-ratio (:reduction-ratio result)
                              :original-size (count content)
                              :minimized-size (count (:minimized result))}))

    (assoc result
           :artifact-dir artifact-dir
           :min-file min-file
           :ddmin-file ddmin-file)))
