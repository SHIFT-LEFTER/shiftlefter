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
