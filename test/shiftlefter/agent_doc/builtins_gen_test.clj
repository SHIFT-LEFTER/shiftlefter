(ns shiftlefter.agent-doc.builtins-gen-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [shiftlefter.agent-doc.builtins-gen :as gen]
   [shiftlefter.stepengine.registry :as registry]))

;; live-sources clears + reloads the global step registry. Clear after each
;; test so we don't leave it populated for sibling namespaces in the run.
(use-fixtures :each (fn [t] (t) (registry/clear-registry!)))

(deftest determinism-test
  (testing "generating twice from the live registries is byte-identical (AC1)"
    (is (= (gen/generate) (gen/generate)))))

(deftest live-coverage-test
  (testing "the generated reference covers both built-in interfaces (AC2/AC4)"
    (let [md (gen/generate)]
      (is (str/includes? md "## Interface `:web`"))
      (is (str/includes? md "## Interface `:sms`"))
      (testing "verbs, frames, step patterns, and lane markers are present"
        (is (str/includes? md "#### `:click` — Click on an element"))
        (is (str/includes? md "frame `:with`: `S fills O with VALUE`"))
        (is (str/includes? md "`:fill`/`:with` `[:web]`"))
        (is (str/includes? md "`:send`/`:to` `[:sms]`")))
      (testing "adapters and their provided protocols are present"
        (is (str/includes? md "`:etaoin`"))
        (is (str/includes? md ":shiftlefter.browser.protocol/IBrowser"))
        (is (str/includes? md "`:sms-mock`")))
      (testing "capability lane (requires-protocols) is surfaced for gated steps"
        (is (str/includes? md "requires `:shiftlefter.sms.protocol/ISMS`"))))))

(deftest checked-in-resource-up-to-date-test
  (testing "the committed builtins.md equals fresh generation (drift guard, AC1/AC5)"
    (let [resource (io/resource "shiftlefter/agent_docs/builtins.md")]
      (is (some? resource) "builtins.md must be a packaged classpath resource")
      (is (= (slurp resource) (gen/generate))
          "builtins.md is stale — regenerate with `clojure -T:build gen-builtins`"))))

;; ---------------------------------------------------------------------------
;; render is a pure function of its data, so drift can be proven from synthetic
;; inputs without mutating the live registries (AC5).
;; ---------------------------------------------------------------------------

(def ^:private base-sources
  {:verbs {:web {:click {:desc "Click on an element"
                         :frames {:default {:args [] :pattern "S clicks O"}}}}}
   :stepdefs [{:pattern-src ":(.+) clicks (.+)"
               :metadata {:interface :web
                          :svo {:verb :click :frame :default}}}]
   :adapters {:etaoin {:provides [:shiftlefter.browser.protocol/IBrowser]}}})

(deftest render-purity-test
  (testing "render is deterministic for fixed data"
    (is (= (gen/render base-sources) (gen/render base-sources)))))

(deftest render-drift-test
  (testing "adding a verb changes the output (AC5)"
    (let [with-verb (assoc-in base-sources [:verbs :web :hover]
                              {:desc "Hover over an element"
                               :frames {:default {:args [] :pattern "S hovers over O"}}})]
      (is (not= (gen/render base-sources) (gen/render with-verb)))
      (is (str/includes? (gen/render with-verb) "`:hover` — Hover over an element"))))

  (testing "adding a step pattern changes the output (AC5)"
    (let [with-step (update base-sources :stepdefs conj
                            {:pattern-src ":(.+) hovers over (.+)"
                             :metadata {:interface :web
                                        :svo {:verb :hover :frame :default}}})]
      (is (not= (gen/render base-sources) (gen/render with-step)))
      (is (str/includes? (gen/render with-step) ":(.+) hovers over (.+)")))))
