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
        (is (str/includes? md "frame `:with` — schema (not a step): S fills O with VALUE"))
        (is (str/includes? md "`:fill`/`:with` `[:web]`"))
        (is (str/includes? md "`:send`/`:to` `[:sms]`")))
      (testing "every frame is paired with a quotable bind-with example (sl-bqo)"
        (is (str/includes? md "  - bind with: `:alice opens the browser to '/login'`"))
        (is (str/includes? md "  - bind with: `:alice sends an SMS to '+15550100' saying 'On my way'`"))
        (is (not (str/includes? md "bind with: ``"))
            "no bind-with line may render an empty example")
        (is (not (re-find #"- frame `[^`]+`: `" md))
            "the old code-formatted frame rendering must not reappear"))
      (testing "adapters and their provided protocols are present"
        (is (str/includes? md "`:etaoin`"))
        (is (str/includes? md ":shiftlefter.browser.protocol/IBrowser"))
        (is (str/includes? md "`:sms-mock`")))
      (testing "not-bundled adapters carry the extra-dep pointer (sl-gcdu)"
        (is (str/includes?
             md
             (str "(not bundled — add com.microsoft.playwright/playwright "
                  "{:mvn/version \"1.50.0\"} to your deps.edn, or drop the "
                  "resolved jars in sl/lib/; see docs/INSTALL-TIERS.md)")))
        (is (not (str/includes? md "`:etaoin` — provides `:shiftlefter.browser.protocol/IBrowser` (not bundled"))
            "bundled adapters gain no pointer"))
      (testing "capability lane (requires-protocols) is surfaced for gated steps"
        (is (str/includes? md "requires `:shiftlefter.sms.protocol/ISMS`")))
      (testing ":location slot kinds are rendered (sl-3jr4 / sl-q81m / sl-yh7)"
        (is (str/includes? md "S navigates to O — O: intent ref (bare PascalCase name), literal URL, or `{binding}`"))
        (is (str/includes? md "S is on O — O: intent ref (bare PascalCase name), literal URL, or `{binding}`"))
        (is (str/includes? md "S is on exactly O — O: literal URL")))
      (testing ":arg-kinds are rendered (sl-yh7 AC11 — regen is not a no-op)"
        (is (str/includes? md "`:value` (literal or `{binding}`)"))
        (is (str/includes? md "`:match` (regex; `(?<name>...)` produces `{name}` bindings)"))))))

(deftest builtin-examples-bind-test
  ;; sl-bqo AC 1, machine-checked: every built-in stepdef ships a
  ;; :doc/example that BINDS — full-string re-matches, the binder's own
  ;; match semantics (bind.clj). An agent that copies a quoted bind-with
  ;; example from the generated doc can never author a non-binding step.
  (testing "every built-in stepdef has a :doc/example that binds to its own pattern"
    (let [stepdefs (:stepdefs (gen/live-sources))]
      (is (seq stepdefs))
      (doseq [{:keys [pattern pattern-src metadata]} stepdefs]
        (let [example (:doc/example metadata)]
          (testing pattern-src
            (is (string? example) "built-in stepdef missing :doc/example")
            (when (string? example)
              (is (re-matches pattern example)
                  ":doc/example does not full-match its own pattern"))))))))

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
                          :svo {:verb :click :frame :default}
                          :doc/example ":alice clicks {:id \"x\"}"}}]
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
      (is (str/includes? (gen/render with-step) ":(.+) hovers over (.+)"))))

  (testing "declaring :object-kind on a frame changes the output (sl-3jr4)"
    (let [locate (fn [frame] (assoc-in base-sources
                                       [:verbs :web :navigate]
                                       {:desc "Navigate to a URL"
                                        :frames {:to frame}}))
          literal-only (locate {:args [] :pattern "S navigates to O"
                                :object-kind :location})
          with-refs (locate {:args [] :pattern "S navigates to O"
                             :object-kind :location :location-refs? true})]
      (is (str/includes? (gen/render literal-only)
                         "S navigates to O — O: literal URL"))
      (is (str/includes? (gen/render with-refs)
                         "— O: intent ref (bare PascalCase name), literal URL, or `{binding}`"))
      (is (not= (gen/render literal-only) (gen/render with-refs)))))

  (testing "declaring :arg-kinds on a frame changes the output (sl-yh7 AC11)"
    (let [with-kind (assoc-in base-sources [:verbs :web :fill]
                              {:desc "Enter text"
                               :frames {:with {:args [:value]
                                               :arg-kinds {:value :value}
                                               :pattern "S fills O with VALUE"}}})
          without-kind (assoc-in base-sources [:verbs :web :fill]
                                 {:desc "Enter text"
                                  :frames {:with {:args [:value]
                                                  :pattern "S fills O with VALUE"}}})]
      (is (str/includes? (gen/render with-kind) "`:value` (literal or `{binding}`)"))
      (is (not= (gen/render with-kind) (gen/render without-kind))))))
