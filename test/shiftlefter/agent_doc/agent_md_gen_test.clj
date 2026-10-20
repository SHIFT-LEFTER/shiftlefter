(ns shiftlefter.agent-doc.agent-md-gen-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [shiftlefter.agent-doc :as agent-doc]
   [shiftlefter.agent-doc.agent-md-gen :as gen]))

(deftest determinism-test
  (testing "generation is deterministic"
    (is (= (gen/generate) (gen/generate)))))

(deftest checked-in-doc-up-to-date-test
  (testing "the committed docs/AGENT.md equals fresh generation (drift guard, AC1)"
    (let [f (io/file "docs/AGENT.md")]
      (is (.exists f) "docs/AGENT.md must exist")
      (is (= (slurp f) (gen/generate))
          "docs/AGENT.md is stale — run `clojure -T:build gen-agent-md`"))))

(deftest standalone-pages-up-to-date-test
  (testing "sl-d4pf: each standalone projection (docs/adoption.md) equals fresh
            generation from its packaged topic — one text, drift-guarded like AGENT.md"
    (doseq [[topic path] gen/standalone-pages]
      (let [f (io/file path)]
        (is (.exists f) (str path " must exist"))
        (is (= (slurp f) (gen/generate-standalone topic))
            (str path " is stale — run `clojure -T:build gen-agent-md`"))
        (is (str/starts-with? (slurp f) "<!-- GENERATED")
            "the banner names it as generated")
        (is (str/ends-with? (slurp f) (:content (agent-doc/load-topic topic)))
            "below the banner the page IS the topic source, verbatim")))))

(deftest render-standalone-purity-test
  (testing "render-standalone is pure and puts the banner above the source's H1"
    (let [out (gen/render-standalone "a" "# A\n\nBody A.\n")]
      (is (= out (gen/render-standalone "a" "# A\n\nBody A.\n")))
      (is (str/starts-with? out "<!-- GENERATED"))
      (is (str/includes? out "`sl agent-doc a`"))
      (is (str/ends-with? out "# A\n\nBody A.\n")))))

(deftest single-h1-test
  (testing "the derived guide has exactly one H1 (topic headings are demoted)"
    (let [h1s (->> (str/split-lines (gen/generate))
                   (filter #(re-find #"^# [^#]" %)))]
      (is (= 1 (count h1s)) (str "expected one H1, got " (vec h1s))))))

(deftest covers-every-topic-test
  (testing "the guide inlines every registered agent-doc topic"
    (let [doc (gen/generate)]
      (doseq [topic (agent-doc/topic-names)
              :let [{:keys [content]} (agent-doc/load-topic topic)
                    title (first (str/split-lines content))]]
        (is (str/includes? doc (str "#" title))
            (str "topic '" topic "' title missing from AGENT.md"))))))

;; render is a pure function of its input, so drift is provable from synthetic
;; topics without touching the live resources.
(deftest render-purity-test
  (testing "render is pure"
    (let [topics [{:topic "a" :content "# A\n\nBody A."}]]
      (is (= (gen/render topics) (gen/render topics))))))

(deftest render-strips-read-next-and-demotes-test
  (let [topics [{:topic "a"
                 :content "# A\n\n## Sub\n\nBody.\n\nRead next:\n\n- `sl agent-doc b`"}]
        out (gen/render topics)]
    (testing "Read next nav is stripped"
      (is (not (str/includes? out "Read next:")))
      (is (not (str/includes? out "sl agent-doc b"))))
    (testing "headings are demoted one level"
      (is (str/includes? out "## A"))
      (is (str/includes? out "### Sub")))))
