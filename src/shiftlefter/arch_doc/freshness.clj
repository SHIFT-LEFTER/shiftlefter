(ns shiftlefter.arch-doc.freshness
  "Freshness extraction for the architecture maps' stamped counts (sl-zxt5).

   The canonical maps in notes/architecture/ carry a few numbers that claim
   to be extracted from the code, not remembered: the module map's require-
   edge count and the invocation map's dispatch-branch count. Each is
   stamped in the canon as a bold, machine-readable phrase (`**314 require
   edges**`, `**19 branches**`); this namespace re-runs the SAME extraction
   the stamp claims and reports the disagreement, so a stale stamp is a red
   suite naming its remedy, not a hand re-count three times a release.

   Counting law (the anti-flap AC, Chair-ratified 08-30): the module-map
   count is REAL `(:require …)` forms only — the `ns` form is read as data
   and the require specs walked. A docstring, comment, or string literal
   that mentions `shiftlefter.foo` is not an edge and cannot move the
   number; the earlier grep one-liner counted those tokens (525 rows at
   2026-08-26 vs 314 real edges), which is why a raw-count guard would have
   flapped on every docstring edit.

   Consumers: the JVM guard (test/shiftlefter/arch_doc/freshness_test.clj)
   and the bb runner (`bb scripts/arch-freshness.bb`, the § Method one-liner
   in both maps). bb-compatible: clojure.core + string + java.io only,
   like the sibling derive ns."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; paths + commands (the remedy text names these)
;; ---------------------------------------------------------------------------

(def src-root "src/shiftlefter")
(def core-file "src/shiftlefter/core.clj")
(def module-map-canon "notes/architecture/module-map.md")
(def invocation-map-canon "notes/architecture/invocation-map.md")
(def rerun-command "bb scripts/arch-freshness.bb")
(def rederive-command "bb scripts/derive-arch-docs.bb")

;; ---------------------------------------------------------------------------
;; reading source as data
;; ---------------------------------------------------------------------------

(defn read-forms
  "Every top-level form in `text`, read as data (no eval)."
  [text]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. text))
        eof (Object.)]
    (binding [*read-eval* false]
      (loop [out []]
        (let [form (read {:eof eof} rdr)]
          (if (identical? form eof)
            out
            (recur (conj out form))))))))

(defn ns-form
  "The `ns` form of a source text, or nil when the first form isn't one."
  [text]
  (let [form (binding [*read-eval* false] (read-string text))]
    (when (and (seq? form) (= 'ns (first form)))
      form)))

(defn spec-libs
  "Lib symbols named by one require spec. Handles the bare symbol, the
   `[lib :as x …]` vector (rest empty or keyword-led), and prefix lists in
   either bracket — `(prefix sub [sub2 :as y])` / `[prefix sub …]`."
  [spec]
  (cond
    (symbol? spec) [spec]

    (and (sequential? spec) (symbol? (first spec)))
    (let [[head & more] spec]
      (if (or (empty? more) (keyword? (first more)))
        [head]
        (for [sub more, lib (spec-libs sub)]
          (symbol (str head "." lib)))))

    :else []))

(defn ns-requires
  "Distinct lib symbols required by an `ns` form, in source order."
  [form]
  (->> (drop 2 form)
       (filter #(and (seq? %) (= :require (first %))))
       (mapcat rest)
       (mapcat spec-libs)
       distinct
       vec))

(defn- shiftlefter-ns? [sym]
  (str/starts-with? (str sym) "shiftlefter."))

(defn- clj-files [root]
  (->> (file-seq (io/file root))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))
       (sort-by str)))

(defn- read-ns-form
  "The file's ns form; a reader failure names the file (a guard that cannot
   read a source file must be loud, never a smaller count)."
  [^java.io.File f]
  (try
    (ns-form (slurp f))
    (catch RuntimeException e
      (throw (ex-info (str "freshness: cannot read the ns form of " f)
                      {:file (str f)} e)))))

(defn require-edges
  "Sorted, distinct `[source-ns target-ns]` pairs for every `shiftlefter.*`
   require across the .clj files under `root`. Requires only — docstring
   and comment mentions are not edges. Files whose first form isn't `ns`
   contribute nothing."
  [root]
  (->> (clj-files root)
       (keep read-ns-form)
       (mapcat (fn [form]
                 (for [lib (ns-requires form)
                       :when (shiftlefter-ns? lib)]
                   [(second form) lib])))
       distinct
       sort
       vec))

;; ---------------------------------------------------------------------------
;; the dispatch table
;; ---------------------------------------------------------------------------

(defn dispatch-branch-count
  "Branches of the `cond` inside `(defn dispatch …)` in `core-text`, or nil
   when no such defn (or no cond in it) exists — nil, never 0, so a moved
   entry point reports instead of passing vacuously."
  [core-text]
  (when-let [dispatch (->> (read-forms core-text)
                           (filter #(and (seq? %)
                                         (= 'defn (first %))
                                         (= 'dispatch (second %))))
                           first)]
    (when-let [cond-form (->> (tree-seq seq? seq dispatch)
                              (filter #(and (seq? %) (= 'cond (first %))))
                              first)]
      (quot (count (rest cond-form)) 2))))

;; ---------------------------------------------------------------------------
;; stamps + problems
;; ---------------------------------------------------------------------------

(def require-edges-stamp-re
  "The module map's stamp phrase: `**314 require edges**`."
  #"\*\*(\d+) require edges\*\*")

(def dispatch-branches-stamp-re
  "The invocation map's stamp phrase: `**19 branches**`."
  #"\*\*(\d+) branches\*\*")

(defn stamped-count
  "The integer inside the first match of `stamp-re` in `text`, or nil."
  [text stamp-re]
  (some-> (re-find stamp-re text) second parse-long))

(defn- count-problems
  "Problem strings for one stamped count; empty vector = fresh. `live` nil
   means the extraction found nothing — reported, never treated as 0."
  [{:keys [label canon-path stamp-re what extraction]} canon-text live]
  (let [stamped (stamped-count canon-text stamp-re)]
    (cond
      (nil? live)
      [(str label ": " extraction " found nothing to count — the extraction "
            "is broken (update shiftlefter.arch-doc.freshness); a guard that "
            "stops matching must fail, not pass")]

      (nil? stamped)
      [(str label ": no stamp matching " (pr-str (str stamp-re)) " in "
            canon-path " — add the bold stamp phrase to the spine; the guard "
            "cannot run vacuous")]

      (not= stamped live)
      [(str label ": " canon-path " stamps " stamped " " what " but the code "
            "has " live " — update the bold stamp (count AND date) in "
            canon-path ", confirm with `" rerun-command "`, then re-derive "
            "with `" rederive-command "` (the public page carries the stamp)")]

      :else [])))

(def module-map-check
  {:label "module map"
   :canon-path module-map-canon
   :stamp-re require-edges-stamp-re
   :what "require edges"
   :extraction (str "reading (:require …) forms under " src-root)})

(def invocation-map-check
  {:label "invocation map"
   :canon-path invocation-map-canon
   :stamp-re dispatch-branches-stamp-re
   :what "dispatch branches"
   :extraction (str "reading the cond of (defn dispatch …) in " core-file)})

(defn module-map-problems
  "Problems for the module map's require-edge stamp against `edges` (the
   require-edges vector; an empty extraction counts as broken)."
  [canon-text edges]
  (count-problems module-map-check canon-text
                  (when (seq edges) (count edges))))

(defn invocation-map-problems
  "Problems for the invocation map's branch stamp against `branch-count`
   (nil when dispatch or its cond was not found)."
  [canon-text branch-count]
  (count-problems invocation-map-check canon-text branch-count))

;; ---------------------------------------------------------------------------
;; the runner (bb scripts/arch-freshness.bb)
;; ---------------------------------------------------------------------------

(defn live-report
  "Read the repo: {:edges [...] :branches n :problems [...]}. Requires the
   canon files — the runner is a dev-repo tool."
  []
  (let [edges (require-edges src-root)
        branches (dispatch-branch-count (slurp core-file))]
    {:edges edges
     :branches branches
     :problems (into (module-map-problems (slurp module-map-canon) edges)
                     (invocation-map-problems (slurp invocation-map-canon)
                                              branches))}))

(defn -main
  "Print live counts vs the canon stamps and any problems; exit 1 on drift.
   `--edges` prints the raw `source -> target` edge list instead."
  [& args]
  (let [{:keys [edges branches problems]} (live-report)]
    (if (some #{"--edges"} args)
      (doseq [[from to] edges] (println from "->" to))
      (do
        (println (count edges) "require edges under" src-root
                 "(distinct shiftlefter.* -> shiftlefter.* pairs, (:require …) forms only)")
        (println branches "dispatch branches in" core-file)
        (doseq [p problems] (println "DRIFT" p))
        (println (if (seq problems)
                   (str "stamps stale (" (count problems) ")")
                   "stamps fresh"))))
    (when (seq problems)
      (System/exit 1))))
