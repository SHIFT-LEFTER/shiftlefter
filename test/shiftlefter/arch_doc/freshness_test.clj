(ns shiftlefter.arch-doc.freshness-test
  "Freshness guards for the architecture maps' stamped counts (sl-zxt5) —
   the module map's require-edge count and the invocation map's dispatch-
   branch count are re-extracted from the code every suite and compared to
   the bold stamps in notes/architecture/*. Same family as the derive drift
   guard beside this file and the codox version stamp
   (docs_api_drift_test.clj): a stale stamp is a red suite naming its remedy.

   Guard-family law: red side first, real-messy literals. The anti-flap AC
   (Chair-ratified 08-30) is tested twice — on a scratch source tree whose
   docstrings, comments, and string literals all name namespaces the file
   does NOT require, and on the live corpus's own known noise row
   (svo/validate's docstring mention of shiftlefter.config, which the module
   map names as a docstring mention, not a require).

   The live guards read notes/architecture/*, which does not ship in the
   public export (public-top-level-allowlist.txt has no notes/), so they
   self-skip green when the canon is absent — the changelog guard's idiom.
   The rule tests run everywhere."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [shiftlefter.arch-doc.freshness :as fresh]))

;; ---------------------------------------------------------------------------
;; fixtures — a scratch source tree, messy on purpose
;; ---------------------------------------------------------------------------

(def ^:private noisy-ns
  ;; Names four shiftlefter namespaces in prose positions; requires ONE.
  "(ns shiftlefter.scratch.noisy
  \"Did-you-mean over glossary terms. Shares the classifier with
   shiftlefter.svo.validate (plan-time) and shiftlefter.stepengine.compile
   (exec-time) — see the shiftlefter.runner.core docstring for the loop.\"
  (:require [clojure.string :as str]
            [shiftlefter.text :as text]
            [etaoin.api :as e]))

;; TODO fold into shiftlefter.svo.glossary once the frames land
(def ^:private provenance
  \"moved from shiftlefter.runner.config at the 2026-08-26 extraction\")

(defn suggest [word] (text/did-you-mean word (str/lower-case provenance)))
")

(def ^:private prefix-list-ns
  ;; Prefix lists in both brackets, a bare symbol, and a :refer :all vector.
  "(ns shiftlefter.scratch.prefixed
  (:require (shiftlefter.svo [validate :as v] glossary)
            [shiftlefter.stepengine [registry :as reg]]
            shiftlefter.config
            [shiftlefter.gherkin.parser :refer :all]
            [clojure.set :as set]))
")

(def ^:private no-ns-file
  ;; A .clj whose first form is not `ns` (a data file) — contributes nothing.
  "{:fixture true :mentions \"shiftlefter.core\"}\n")

(defn- scratch-tree!
  "Write the fixture files under a fresh temp dir; return the dir."
  []
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir")
                           (str "sl-freshness-" (System/nanoTime)))
              .mkdirs)]
    (spit (io/file dir "noisy.clj") noisy-ns)
    (spit (io/file dir "prefixed.clj") prefix-list-ns)
    (spit (io/file dir "data.clj") no-ns-file)
    dir))

(def ^:private synthetic-core
  ;; Three cond branches; a `cond->` in the bindings and a nested `case`
  ;; must not be mistaken for the table.
  "(ns shiftlefter.scratch.core)

(defn helper [x] (cond (pos? x) :pos :else :other))

(defn dispatch
  \"Return an exit code.\"
  [args]
  (let [opts (cond-> {} (seq args) (assoc :args args))]
    (cond
      (:help opts) 0
      (= (first args) \"run\") (case (second args) \"x\" 1 2)
      :else 2)))
")

;; ---------------------------------------------------------------------------
;; red side FIRST: the extraction
;; ---------------------------------------------------------------------------

(deftest require-extraction-counts-requires-only
  (let [dir (scratch-tree!)
        edges (fresh/require-edges (str dir))]
    (testing "prose mentions are not edges — the anti-flap proof"
      (is (= [['shiftlefter.scratch.noisy 'shiftlefter.text]]
             (filterv #(= 'shiftlefter.scratch.noisy (first %)) edges))
          "noisy.clj names svo.validate, stepengine.compile, runner.core,
           svo.glossary, runner.config in prose and requires only text"))
    (testing "prefix lists (both brackets), bare symbol, :refer :all"
      (is (= '[[shiftlefter.scratch.prefixed shiftlefter.config]
               [shiftlefter.scratch.prefixed shiftlefter.gherkin.parser]
               [shiftlefter.scratch.prefixed shiftlefter.stepengine.registry]
               [shiftlefter.scratch.prefixed shiftlefter.svo.glossary]
               [shiftlefter.scratch.prefixed shiftlefter.svo.validate]]
             (filterv #(= 'shiftlefter.scratch.prefixed (first %)) edges))))
    (testing "non-shiftlefter requires and non-ns files contribute nothing"
      (is (= 6 (count edges)))
      (is (not-any? #(str/starts-with? (str (second %)) "clojure.") edges))
      (is (not-any? #(str/starts-with? (str (second %)) "etaoin.") edges)))))

(deftest spec-libs-shapes
  (is (= '[a.b] (fresh/spec-libs 'a.b)))
  (is (= '[a.b] (fresh/spec-libs '[a.b :as b :refer [x]])))
  (is (= '[a.b.c a.b.d] (fresh/spec-libs '(a.b c [d :as d]))))
  (is (= '[a.b.c] (fresh/spec-libs '[a.b c])))
  (is (= [] (fresh/spec-libs :reload))))

(deftest dispatch-branch-counting
  (testing "the cond of (defn dispatch …), not helper conds or cond->"
    (is (= 3 (fresh/dispatch-branch-count synthetic-core))))
  (testing "no dispatch defn → nil, never 0 (anti-vacuous)"
    (is (nil? (fresh/dispatch-branch-count "(ns x)\n(defn other [] (cond :else 1))")))))

;; ---------------------------------------------------------------------------
;; red side: stamps and remedies
;; ---------------------------------------------------------------------------

(def ^:private stamped-module-map
  ;; The real header sentence shape.
  "# Module Map — checked against the actual require graph

Arrows below are EXTRACTED from `(:require …)` forms across
`src/shiftlefter/**/*.clj` — **314 require edges** at the 2026-08-30
re-extraction (distinct `shiftlefter.*` → `shiftlefter.*` pairs).
")

(def ^:private stamped-invocation-map
  "## Lanes

Every branch of the dispatch `cond` (`core.clj:917-1030`; **19 branches**
at the 2026-08-30 re-stamp), one row each.
")

(defn- fake-edges [n] (vec (for [i (range n)] [(symbol (str "a" i)) 'shiftlefter.b])))

(deftest red-side-fires
  (testing "module map: stamp disagrees → names the stamp, the rerun, the re-derive"
    (let [[p :as problems] (fresh/module-map-problems stamped-module-map (fake-edges 315))]
      (is (= 1 (count problems)))
      (is (str/includes? p "stamps 314 require edges but the code has 315"))
      (is (str/includes? p "notes/architecture/module-map.md"))
      (is (str/includes? p fresh/rerun-command))
      (is (str/includes? p fresh/rederive-command))))
  (testing "invocation map: stamp disagrees"
    (let [[p :as problems] (fresh/invocation-map-problems stamped-invocation-map 20)]
      (is (= 1 (count problems)))
      (is (str/includes? p "stamps 19 dispatch branches but the code has 20"))
      (is (str/includes? p "notes/architecture/invocation-map.md"))))
  (testing "missing stamp is loud, not vacuous"
    (let [problems (fresh/module-map-problems "# Module Map\n\nno stamp here\n" (fake-edges 3))]
      (is (= 1 (count problems)))
      (is (str/includes? (first problems) "no stamp matching"))))
  (testing "broken extraction (nothing counted) is loud, not vacuous"
    (is (str/includes? (first (fresh/module-map-problems stamped-module-map []))
                       "found nothing to count"))
    (is (str/includes? (first (fresh/invocation-map-problems stamped-invocation-map nil))
                       "found nothing to count"))))

(deftest green-side-passes
  (is (= [] (fresh/module-map-problems stamped-module-map (fake-edges 314))))
  (is (= [] (fresh/invocation-map-problems stamped-invocation-map 19))))

;; ---------------------------------------------------------------------------
;; live guards — self-skip when the canon is absent (public export)
;; ---------------------------------------------------------------------------

(defn- canon-text [path]
  (let [f (io/file path)]
    (when (.exists f) (slurp f))))

(deftest module-map-fresh
  (if-let [canon (canon-text fresh/module-map-canon)]
    (let [edges (fresh/require-edges fresh/src-root)]
      (is (seq edges) "extraction found no edges under src/shiftlefter — broken, not empty")
      (is (empty? (fresh/module-map-problems canon edges))
          (str/join "\n" (fresh/module-map-problems canon edges))))
    (testing "canon absent (public export) — self-skip"
      (is true (str "skipped: " fresh/module-map-canon
                    " is not in the public export")))))

(deftest invocation-map-fresh
  (if-let [canon (canon-text fresh/invocation-map-canon)]
    (let [branches (fresh/dispatch-branch-count (slurp fresh/core-file))]
      (is (some? branches) "no (defn dispatch …) with a cond in core.clj — the entry point moved")
      (is (empty? (fresh/invocation-map-problems canon branches))
          (str/join "\n" (fresh/invocation-map-problems canon branches))))
    (testing "canon absent (public export) — self-skip"
      (is true (str "skipped: " fresh/invocation-map-canon
                    " is not in the public export")))))

(deftest live-anti-flap-literal
  ;; The module map's own named noise row: svo/validate mentions
  ;; shiftlefter.config in a docstring and does not require it. If this
  ;; stops holding, the module map's prose about that row is stale too.
  (let [text (slurp "src/shiftlefter/svo/validate.clj")
        edges (fresh/require-edges fresh/src-root)]
    (is (str/includes? text "shiftlefter.config/")
        "fixture stale: svo/validate.clj no longer mentions shiftlefter.config in prose — pick another live noise row")
    (is (not-any? #{['shiftlefter.svo.validate 'shiftlefter.config]} edges)
        "a docstring mention became an edge — the extraction is counting prose")))
