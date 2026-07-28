(ns shiftlefter.agent-doc.examples-gen-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [shiftlefter.agent-doc :as agent-doc]
   [shiftlefter.agent-doc.examples-gen :as gen]))

(deftest determinism-test
  (testing "generating twice from the working tree is byte-identical"
    (is (= (gen/generate) (gen/generate)))))

(deftest registry-test
  (testing "the examples topic is registered and its resource path resolves"
    (is (agent-doc/known-topic? "examples"))
    (is (= :ok (:status (agent-doc/load-topic "examples"))))))

(deftest live-coverage-test
  (testing "the generated topic covers every AC3 shape with provenance (AC2/AC3)"
    (let [md (gen/generate)]
      (testing "every snippet carries a provenance line"
        (is (= (count (mapcat :snippets gen/manifest))
               (count (re-seq #"_Verbatim from `examples/" md)))))
      (testing "minimal config"
        (is (str/includes? md "examples/02-browser-zero-code/shiftlefter.edn")))
      (testing "subjects + verbs glossaries (canonical layout)"
        (is (str/includes? md "examples/svo-demo/glossary/subjects.edn"))
        (is (str/includes? md "examples/svo-demo/glossary/verbs/web.edn")))
      (testing "intents, including a parent-anchored nested address"
        (is (str/includes? md "examples/04-sms-2fa/glossary/intents/login.edn"))
        (is (str/includes? md "Dashboard.featured[1].rating.stars")))
      (testing "an SVO feature with builtin steps"
        (is (str/includes? md "examples/06-nested-parent-anchored/features/catalog.feature")))
      (testing "setup.clj orchestration"
        (is (str/includes? md "examples/02-browser-zero-code/setup.clj"))))))

(deftest checked-in-resource-up-to-date-test
  (testing "the committed examples.md equals fresh generation (drift guard, AC2)"
    (let [resource (io/resource "shiftlefter/agent_docs/examples.md")]
      (is (some? resource) "examples.md must be a packaged classpath resource")
      (is (= (slurp resource) (gen/generate))
          "examples.md is stale — regenerate with `clojure -T:build gen-examples`"))))

(deftest missing-source-test
  (testing "a manifest path that doesn't exist throws loudly, never a placeholder"
    (with-redefs [gen/manifest [{:title "Ghost"
                                 :framing "x"
                                 :snippets [{:path "examples/does-not-exist.edn"
                                             :lang "edn"}]}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"examples topic source file missing"
                            (gen/live-sections))))))

;; ---------------------------------------------------------------------------
;; render is a pure function of its data, so drift can be proven from synthetic
;; inputs without touching the filesystem.
;; ---------------------------------------------------------------------------

(def ^:private base-sections
  [{:title "Minimal config"
    :framing "One interface."
    :snippets [{:path "examples/x/shiftlefter.edn"
                :lang "edn"
                :content "{:interfaces {:web {:adapter :etaoin}}}"}]}])

(deftest render-purity-test
  (testing "render is deterministic for fixed data"
    (is (= (gen/render base-sections) (gen/render base-sections)))))

(deftest render-drift-test
  (testing "changing snippet content changes the output (hand-edit detectable)"
    (let [edited (assoc-in base-sections
                           [0 :snippets 0 :content]
                           "{:interfaces {:web {:adapter :playwright}}}")]
      (is (not= (gen/render base-sections) (gen/render edited)))
      (is (str/includes? (gen/render edited) ":playwright"))))

  (testing "adding a section changes the output"
    (let [added (conj base-sections
                      {:title "Subjects"
                       :framing "Actors."
                       :snippets [{:path "examples/x/glossary/subjects.edn"
                                   :lang "edn"
                                   :content "{:subjects {:admin {:desc \"a\"}}}"}]})]
      (is (not= (gen/render base-sections) (gen/render added)))
      (is (str/includes? (gen/render added) "## Subjects")))))
