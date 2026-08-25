(ns shiftlefter.docs-api-drift-test
  "Codox regen drift-guard (sl-t86d, Warden W17 rider): docs/api is
   committed, generated HTML that only `bin/docs` refreshes — through the
   0.5.5 window it shipped a retired overclaim and the pre-7c7i exit
   table because nothing but a release-checklist line guarded the regen.

   Codox cannot run in-test (own deps alias; build.clj `docs` shells a
   fresh JVM and deletes the tracked tree), so this guard is a cheap
   hybrid instead of AGENT.md's regenerate-and-compare:

   1. VERSION-STAMP SYNC — docs/api's stamped project version must equal
      build.clj's `version`. At a release cut the version bump turns this
      red until `bin/docs` reruns: the checklist step, made mechanical.
   2. SAMPLED DOCSTRING SYNC — for a small table of load-bearing PUBLIC
      vars, the line extracted from the LIVE docstring must appear in the
      corresponding page. A src docstring edit reds this until `bin/docs`
      reruns — that is the drift alarm, and the small deliberate tax that
      keeps generated docs honest between cuts.

   HONEST SCOPE: this samples — it proves the named lines are fresh, not
   the whole 118-page tree. Full freshness is one `bin/docs` away; add a
   row here when a docstring becomes load-bearing.

   Sits beside version_source_test.clj (the cheap slurp-the-config
   precedent)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.costume]
            [shiftlefter.repl]
            [shiftlefter.runner.report.edn]
            [shiftlefter.svo.glossary]))

(defn- normalize
  "Common text form for a docstring and a codox page: markdown code/bold
   markers gone, typographic quotes straightened, whitespace collapsed."
  [s]
  (-> s
      (str/replace #"[`*]" "")
      (str/replace "’" "'")
      (str/replace "‘" "'")
      (str/replace "“" "\"")
      (str/replace "”" "\"")
      (str/replace #"\s+" " ")
      str/trim))

(defn- page-text
  "docs/api page reduced to near-plain text: tags stripped (with nothing,
   so inline code spans rejoin their surroundings — block boundaries keep
   the source's literal newlines), the entities codox emits unescaped,
   then `normalize`."
  [html]
  (-> html
      (str/replace #"<[^>]*>" "")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'")
      (str/replace "&amp;" "&")
      normalize))

(def ^:private remedy
  "docs/api is stale — regenerate it: bin/docs (clj -T:build docs)")

(defn- build-clj-version []
  (second (re-find #"\(def version \"([^\"]+)\"\)" (slurp "build.clj"))))

;; The sample: load-bearing public vars/ns docs whose truth has drifted in
;; docs/api before (the audited W17 rot sites). :extract runs on the
;; NORMALIZED live docstring; the match must appear in the page.
(def ^:private doc-sync-sample
  [{:label ":doc of shiftlefter.runner.report.edn/build-summary (the 7c7i exit table)"
    :doc (fn [] (:doc (meta #'shiftlefter.runner.report.edn/build-summary)))
    :page "docs/api/shiftlefter.runner.report.edn.html"
    :extract #"exit-code: [^(]*\(shiftlefter\.runner\.verdict, sl-7c7i\)"}
   {:label ":doc of shiftlefter.repl/reset-ctxs! (the t8bj custody sentence)"
    :doc (fn [] (:doc (meta #'shiftlefter.repl/reset-ctxs!)))
    :page "docs/api/shiftlefter.repl.html"
    :extract #"a costume outlives a context reset[^.]*\."}
   {:label "ns doc of shiftlefter.costume (durability = profile + relaunch)"
    :doc (fn [] (:doc (meta (find-ns 'shiftlefter.costume))))
    :page "docs/api/shiftlefter.costume.html"
    :extract #"what survives a JVM restart[^)]*\)"}
   {:label ":doc of shiftlefter.svo.glossary/merge-glossaries (h7c3 always-extend — a removed-feature docstring must not resurrect in the docs)"
    :doc (fn [] (:doc (meta #'shiftlefter.svo.glossary/merge-glossaries)))
    :page "docs/api/shiftlefter.svo.glossary.html"
    :extract #"project ALWAYS extends defaults[^)]*\)"}])

(defn- sample-problems
  "Sync problems for one sample row against page-content (a raw HTML
   string) — empty vector when fresh. Split from the deftest so the red
   side can drive it with a stale fixture."
  [{:keys [label doc extract]} page-content]
  (let [line (re-find extract (normalize (doc)))]
    (cond
      (nil? line)
      [(str label ": the extraction regex no longer matches the live"
            " docstring — update this test's sample row")]

      (not (str/includes? (page-text page-content) line))
      [(str label ": the live docstring line " (pr-str line)
            " is not in the generated page. " remedy)]

      :else [])))

;; Red side FIRST (guard-family law): a stale page shaped like the actual
;; pre-7c7i rot must fail the sync check, and a wrong version stamp must
;; be detected.
(def ^:private pre-7c7i-stale-page
  "<div class=\"doc\"><ul><li>exit-code: 0, 1, 2, or 3</li></ul></div>")

(deftest red-side-fires
  (testing "the audited stale exit table (real-shaped fixture) is caught"
    (let [problems (sample-problems (first doc-sync-sample) pre-7c7i-stale-page)]
      (is (= 1 (count problems)))
      (is (str/includes? (first problems) "bin/docs")
          "a red guard at the cut must name the remedy command")))
  (testing "a broken extraction regex is loud, never silently green"
    (is (seq (sample-problems (assoc (first doc-sync-sample)
                                     :extract #"never-in-any-docstring-zzz")
                              pre-7c7i-stale-page)))))

(deftest docs-api-version-stamp-matches-build
  (let [stamped (second (re-find #"<span class=\"project-version\">([^<]+)</span>"
                                 (slurp "docs/api/index.html")))]
    (is (= (build-clj-version) stamped)
        (str "docs/api is stamped " (pr-str stamped) " but build.clj says "
             (pr-str (build-clj-version)) ". " remedy
             " (regenerate AFTER the last code merge of the release)"))))

(deftest docs-api-docstrings-are-fresh
  (doseq [{:keys [page] :as row} doc-sync-sample]
    (let [f (io/file page)]
      (is (.exists f) (str page " must exist (committed codox output)"))
      (when (.exists f)
        (let [problems (sample-problems row (slurp f))]
          (is (empty? problems) (str/join "\n" problems)))))))
