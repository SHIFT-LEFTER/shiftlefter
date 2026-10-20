(ns shiftlefter.stepengine.compile-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.config :as config]
            [shiftlefter.stepengine.compile :as compile]
            [shiftlefter.stepengine.bind :as bind]
            [shiftlefter.stepengine.registry :as registry]))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(defn clean-registry-fixture [f]
  (registry/clear-registry!)
  (f)
  (registry/clear-registry!))

(use-fixtures :each clean-registry-fixture)

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn make-step
  "Create a pickle step map for testing."
  [text]
  {:step/id (java.util.UUID/randomUUID)
   :step/text text
   :step/arguments []})

(defn make-pickle
  "Create a pickle map for testing."
  [name steps]
  {:pickle/id (java.util.UUID/randomUUID)
   :pickle/name name
   :pickle/steps (mapv #(if (string? %) (make-step %) %) steps)})

;; -----------------------------------------------------------------------------
;; compile-suite Tests
;; -----------------------------------------------------------------------------

(deftest test-compile-suite-delegates-to-bind
  (testing "compile-suite binds exactly as bind-suite does; the one thing it
            adds for untyped stepdefs is the :stepdef/untyped warning
            (sl-6wh9 — a bare bind has no enforcement levels to judge by)"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (registry/register! #"I add (\d+) more"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 2})
    (let [pickles [(make-pickle "p1" ["I have 5 items"])
                   (make-pickle "p2" ["I add 3 more"])]
          stepdefs (registry/all-stepdefs)
          config {}  ; no config = the built-in defaults
          compile-result (compile/compile-suite config pickles stepdefs)
          bind-result (bind/bind-suite pickles stepdefs)]
      ;; Same structure
      (is (= (:runnable? compile-result) (:runnable? bind-result)))
      (is (= (count (:plans compile-result)) (count (:plans bind-result))))
      (is (= [:stepdef/untyped :stepdef/untyped]
             (mapv :type (-> compile-result :diagnostics :svo-issues))))
      (is (= (-> (:diagnostics compile-result)
                 (update :svo-issues empty)
                 (assoc-in [:counts :svo-issue-count] 0)
                 (assoc-in [:counts :total-issues] 0))
             (:diagnostics bind-result))))))

(deftest test-compile-suite-runnable-plan
  (testing "compile-suite returns runnable plan when all steps match"
    (registry/register! #"I have (\d+) items in my cart"
                        (fn [_n] nil)
                        {:ns 's :file "s.clj" :line 1})
    (registry/register! #"I add (\d+) more items"
                        (fn [_n] nil)
                        {:ns 's :file "s.clj" :line 2})
    (registry/register! #"I should have (\d+) items total"
                        (fn [_n] nil)
                        {:ns 's :file "s.clj" :line 3})
    (let [pickle (make-pickle "basic" ["I have 5 items in my cart"
                                       "I add 3 more items"
                                       "I should have 8 items total"])
          {:keys [plans runnable? diagnostics]} (compile/compile-suite {:runner {}} [pickle] (registry/all-stepdefs))]
      (is runnable?)
      (is (= 1 (count plans)))
      (is (:plan/runnable? (first plans)))
      ;; sl-6wh9: the three untyped stepdefs each warn; warnings count
      ;; toward :total-issues (sl-89ii) without blocking.
      (is (= 3 (-> diagnostics :counts :total-issues)))
      (is (every? #(= :stepdef/untyped (:type %)) (:svo-issues diagnostics))))))

(deftest test-compile-suite-not-runnable-undefined
  (testing "compile-suite returns not runnable when step undefined"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (let [pickle (make-pickle "test" ["I have 5 items" "undefined step"])
          {:keys [runnable? diagnostics]} (compile/compile-suite {:runner {}} [pickle] (registry/all-stepdefs))]
      (is (not runnable?))
      (is (= 1 (-> diagnostics :counts :undefined-count))))))

(deftest test-compile-suite-accepts-full-config
  (testing "compile-suite accepts full config parameter"
    (registry/register! #"a step"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 1})
    (let [config {:runner {:macros {:enabled? false}
                           :step-paths ["steps/"]}}
          pickle (make-pickle "test" ["a step"])
          {:keys [runnable?]} (compile/compile-suite config [pickle] (registry/all-stepdefs))]
      (is runnable?))))

(deftest test-compile-suite-empty-pickles
  (testing "compile-suite handles empty pickle list"
    (let [{:keys [plans runnable? diagnostics]} (compile/compile-suite {:runner {}} [] (registry/all-stepdefs))]
      (is (empty? plans))
      (is runnable?)  ; no issues = runnable
      (is (zero? (-> diagnostics :counts :total-issues))))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria (from Task 2.1 spec)
;; -----------------------------------------------------------------------------

(deftest test-acceptance-criteria-phase1-integration
  (testing "Phase 1 integration unchanged - compile produces executable plans"
    ;; Setup stepdefs matching basic.feature
    (registry/register! #"I have (\d+) items in my cart"
                        (fn [n] {:cart (parse-long n)})
                        {:ns 'steps :file "steps.clj" :line 1})
    (registry/register! #"I add (\d+) more items"
                        (fn [n ctx]
                          (update-in ctx [:scenario :cart] + (parse-long n)))
                        {:ns 'steps :file "steps.clj" :line 2})
    (registry/register! #"I should have (\d+) items total"
                        (fn [n ctx]
                          (assert (= (parse-long n) (get-in ctx [:scenario :cart])))
                          nil)
                        {:ns 'steps :file "steps.clj" :line 3})
    (let [pickle (make-pickle "Basic passing scenario"
                              ["I have 5 items in my cart"
                               "I add 3 more items"
                               "I should have 8 items total"])
          {:keys [plans runnable?]} (compile/compile-suite {:runner {}} [pickle] (registry/all-stepdefs))]
      (is runnable?)
      (is (= 1 (count plans)))
      (is (every? #(= :matched (:status %)) (-> plans first :plan/steps))))))

;; -----------------------------------------------------------------------------
;; Macro Pipeline Tests (Task 2.8)
;; -----------------------------------------------------------------------------

(deftest test-compile-suite-macros-disabled-passthrough
  (testing "macros disabled passes pickles through unchanged"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (let [pickle (make-pickle "test" ["I have 5 items"])
          ;; Macros explicitly disabled
          config {:runner {:macros {:enabled? false}}}
          {:keys [runnable? plans]} (compile/compile-suite config [pickle] (registry/all-stepdefs))]
      (is runnable?)
      (is (= 1 (count plans)))
      ;; No macro metadata on steps
      (is (nil? (-> plans first :plan/steps first :step :step/macro))))))

(deftest test-compile-suite-macros-enabled-with-registry
  (testing "macros enabled loads registry and expands pickles"
    ;; Register stepdefs for macro expansion (matching auth.ini steps)
    (registry/register! #"I visit the login page"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 1})
    (registry/register! #"I enter username \"([^\"]+)\""
                        (fn [_] nil)
                        {:ns 's :file "s.clj" :line 2})
    (registry/register! #"I enter password \"([^\"]+)\""
                        (fn [_] nil)
                        {:ns 's :file "s.clj" :line 3})
    (registry/register! #"I click the login button"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 4})
    (registry/register! #"I should see the welcome message"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 5})
    ;; Use auth.ini macro fixture
    (let [macro-path "test/fixtures/macros/auth.ini"
          config {:runner {:macros {:enabled? true
                                    :registry-paths [macro-path]}}}
          ;; Pickle with macro call
          pickle {:pickle/id (java.util.UUID/randomUUID)
                  :pickle/name "test"
                  :pickle/steps [{:step/id (java.util.UUID/randomUUID)
                                  :step/keyword "Given"
                                  :step/text "login as alice +"
                                  :step/location {:line 5 :column 5}
                                  :step/arguments []}]}
          {:keys [runnable? plans]} (compile/compile-suite config [pickle] (registry/all-stepdefs))]
      (is runnable?)
      (is (= 1 (count plans)))
      ;; Expanded: wrapper + 5 children = 6 steps
      (is (= 6 (count (-> plans first :plan/steps))))
      ;; First step is synthetic wrapper
      (is (true? (-> plans first :plan/steps first :step :step/synthetic?)))
      (is (= :call (-> plans first :plan/steps first :step :step/macro :role))))))

(deftest test-compile-suite-macros-registry-load-error
  (testing "macro registry load error returns not runnable"
    (let [;; Non-existent file
          config {:runner {:macros {:enabled? true
                                    :registry-paths ["nonexistent/macros.ini"]}}}
          pickle (make-pickle "test" ["some step"])
          {:keys [runnable? diagnostics]} (compile/compile-suite config [pickle] (registry/all-stepdefs))]
      (is (not runnable?))
      (is (seq (:macro-errors diagnostics)))
      (is (= :macro/file-not-found (-> diagnostics :macro-errors first :type))))))

(deftest test-compile-suite-macros-expansion-error
  (testing "macro expansion error (undefined macro) returns not runnable"
    ;; Use auth.ini but call undefined macro
    (let [macro-path "test/fixtures/macros/auth.ini"
          config {:runner {:macros {:enabled? true
                                    :registry-paths [macro-path]}}}
          ;; Pickle with undefined macro call
          pickle {:pickle/id (java.util.UUID/randomUUID)
                  :pickle/name "test"
                  :pickle/steps [{:step/id (java.util.UUID/randomUUID)
                                  :step/keyword "Given"
                                  :step/text "undefined macro +"
                                  :step/location {:line 5 :column 5}
                                  :step/arguments []}]}
          {:keys [runnable? diagnostics]} (compile/compile-suite config [pickle] (registry/all-stepdefs))]
      (is (not runnable?))
      (is (seq (:macro-errors diagnostics)))
      (is (= :macro/undefined (-> diagnostics :macro-errors first :type))))))

(deftest test-compile-suite-macros-enabled-no-macro-calls
  (testing "macros enabled but no macro calls works normally"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (let [macro-path "test/fixtures/macros/auth.ini"
          config {:runner {:macros {:enabled? true
                                    :registry-paths [macro-path]}}}
          ;; Regular step (no + suffix)
          pickle (make-pickle "test" ["I have 5 items"])
          {:keys [runnable? plans]} (compile/compile-suite config [pickle] (registry/all-stepdefs))]
      (is runnable?)
      (is (= 1 (count plans)))
      ;; No expansion, just 1 step
      (is (= 1 (count (-> plans first :plan/steps)))))))

(deftest test-compile-suite-macros-multiple-pickles
  (testing "macro expansion works across multiple pickles"
    ;; Register stepdefs for macro expansion (matching auth.ini steps)
    (registry/register! #"I visit the login page"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 1})
    (registry/register! #"I enter username \"([^\"]+)\""
                        (fn [_] nil)
                        {:ns 's :file "s.clj" :line 2})
    (registry/register! #"I enter password \"([^\"]+)\""
                        (fn [_] nil)
                        {:ns 's :file "s.clj" :line 3})
    (registry/register! #"I click the login button"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 4})
    (registry/register! #"I should see the welcome message"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 5})
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 6})
    (let [macro-path "test/fixtures/macros/auth.ini"
          config {:runner {:macros {:enabled? true
                                    :registry-paths [macro-path]}}}
          pickle1 {:pickle/id (java.util.UUID/randomUUID)
                   :pickle/name "with macro"
                   :pickle/steps [{:step/id (java.util.UUID/randomUUID)
                                   :step/keyword "Given"
                                   :step/text "login as alice +"
                                   :step/location {:line 5 :column 5}
                                   :step/arguments []}]}
          pickle2 (make-pickle "without macro" ["I have 5 items"])
          {:keys [runnable? plans]} (compile/compile-suite config [pickle1 pickle2] (registry/all-stepdefs))]
      (is runnable?)
      (is (= 2 (count plans)))
      ;; First pickle: expanded (wrapper + 5 children = 6 steps)
      (is (= 6 (count (-> plans first :plan/steps))))
      ;; Second pickle: not expanded (1 step)
      (is (= 1 (count (-> plans second :plan/steps)))))))

;; -----------------------------------------------------------------------------
;; One mode (sl-6wh9): glossaries, enforcement, untyped stepdefs
;; -----------------------------------------------------------------------------

(deftest test-untyped-stepdef-warns-by-default
  (testing "a matched stepdef with no :svo metadata binds and runs, and the
            planner says so: :stepdef/untyped at :warn (the default)"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (let [config {}  ; no config = the built-in defaults
          pickle (make-pickle "test" ["I have 5 items"])
          {:keys [runnable? diagnostics]} (compile/compile-suite config [pickle] (registry/all-stepdefs))
          [issue :as issues] (:svo-issues diagnostics)]
      (is runnable? "a warning never blocks")
      (is (= 1 (count issues)))
      (is (= :stepdef/untyped (:type issue)))
      (is (= :warn (:severity issue)))
      (is (= "I have 5 items" (get-in issue [:location :step-text])))
      (is (str/includes? (:message issue) "s.clj:1"))
      (is (= 1 (-> diagnostics :counts :svo-issue-count))))))

(deftest test-untyped-stepdef-levels
  (registry/register! #"I have (\d+) items"
                      (fn [n] n)
                      {:ns 's :file "s.clj" :line 1})
  (let [pickle (make-pickle "test" ["I have 5 items"])
        compile-at (fn [level]
                     (compile/compile-suite {:enforcement {:untyped-stepdef level}}
                                            [pickle] (registry/all-stepdefs)))]
    (testing ":error blocks planning"
      (let [{:keys [runnable? diagnostics]} (compile-at :error)]
        (is (not runnable?))
        (is (= :error (-> diagnostics :svo-issues first :severity)))))
    (testing ":off drops the issue"
      (let [{:keys [runnable? diagnostics]} (compile-at :off)]
        (is runnable?)
        (is (empty? (:svo-issues diagnostics)))))))

(deftest test-builtin-steps-are-never-untyped
  (testing "every builtin carries :svo — a builtin-only suite raises no untyped issue"
    (registry/clear-registry!)
    (require 'shiftlefter.stepdefs.browser :reload)
    (let [pickle (make-pickle "test" [":user opens the browser to 'http://x'"])
          {:keys [diagnostics]} (compile/compile-suite {} [pickle] (registry/all-stepdefs))]
      (is (not-any? #(= :stepdef/untyped (:type %)) (:svo-issues diagnostics))))))

(deftest test-enforcement-happy-path
  (testing "a project glossary loads and the enforcement levels reach bind-suite"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (let [config {:enforcement {:unknown-subject :warn :unknown-verb :error
                                :untyped-stepdef :off}
                  :glossaries {:subjects "test/fixtures/glossaries/subjects.edn"
                               :verbs ["test/fixtures/glossaries/verbs-web-project.edn"]}
                  :interfaces {:web {:type :web :adapter :etaoin}}}
          pickle (make-pickle "test" ["I have 5 items"])
          {:keys [runnable? diagnostics]} (compile/compile-suite config [pickle] (registry/all-stepdefs))]
      (is runnable?)
      ;; SVO issues empty (no SVO metadata on our simple step; untyped off)
      (is (= 0 (-> diagnostics :counts :svo-issue-count))))))

(deftest test-glossaries-absent-or-nil-is-an-empty-glossary
  ;; sl-6wh9 ruling #5 (fold-in C4): absence and explicit nil both load as
  ;; EMPTY — never an error; the missing-glossaries error is gone. Paired
  ;; with test-shifted-mode-glossary-file-not-found below: an explicit
  ;; path that is missing stays a hard error.
  (registry/clear-registry!)
  ;; :reload — the ns is already loaded, and the fixture just emptied the
  ;; registry; re-evaluating the defsteps re-registers the builtins.
  (require 'shiftlefter.stepdefs.browser :reload)
  ;; `default-config` IS the no-config-file shape load-config produces
  ;; (interfaces + enforcement present, :glossaries nil) — a bare {} here
  ;; would mean zero interfaces, which is a different (suite-lint) story.
  (let [pickle (make-pickle "test" [":user opens the browser to 'http://x'"])
        stepdefs (registry/all-stepdefs)
        no-file config/default-config]
    (testing "no config file at all: builtin steps bind; the unknown
              subject is the ORDINARY warning against an empty glossary"
      (let [{:keys [runnable? diagnostics]} (compile/compile-suite no-file [pickle] stepdefs)]
        (is runnable?)
        (is (empty? (:errors diagnostics)))
        (is (= [:svo/unknown-subject] (mapv :type (:svo-issues diagnostics))))
        (is (= :warn (-> diagnostics :svo-issues first :severity)))))
    (testing "explicit :glossaries nil — empty on purpose — is the same"
      (let [{:keys [runnable? diagnostics]}
            (compile/compile-suite (assoc no-file :glossaries nil) [pickle] stepdefs)]
        (is runnable?)
        (is (empty? (:errors diagnostics)))
        (is (= [:svo/unknown-subject] (mapv :type (:svo-issues diagnostics))))))
    (testing "the empty glossary still carries the framework default verbs"
      (let [{:keys [diagnostics]} (compile/compile-suite no-file [pickle] stepdefs)]
        (is (not-any? #(= :svo/unknown-verb (:type %)) (:svo-issues diagnostics)))))))

(deftest test-shifted-mode-glossary-file-not-found
  (testing "an explicit glossary path that is missing is a hard error (the
            other half of the C4 pair: absence never warns, a false claim
            always errors)"
    (registry/register! #"a step"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 1})
    (let [config {:enforcement {:unknown-subject :warn}
                  :glossaries {:subjects "nonexistent/subjects.edn"}}
          pickle (make-pickle "test" ["a step"])
          {:keys [runnable? diagnostics]} (compile/compile-suite config [pickle] (registry/all-stepdefs))]
      (is (not runnable?))
      (is (seq (:errors diagnostics)))
      (is (= :svo/glossary-file-not-found (-> diagnostics :errors first :type)))
      (is (= "nonexistent/subjects.edn" (-> diagnostics :errors first :path))))))

(deftest test-shifted-mode-verb-glossary-file-not-found
  (testing "shifted mode + verb glossary file not found returns error"
    (registry/register! #"a step"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 1})
    (let [config {:enforcement {:unknown-verb :error}
                  :glossaries {:subjects "test/fixtures/glossaries/subjects.edn"
                               :verbs ["nonexistent/verbs.edn"]}}
          pickle (make-pickle "test" ["a step"])
          {:keys [runnable? diagnostics]} (compile/compile-suite config [pickle] (registry/all-stepdefs))]
      (is (not runnable?))
      (is (seq (:errors diagnostics)))
      (is (= :svo/glossary-file-not-found (-> diagnostics :errors first :type)))
      (is (= "nonexistent/verbs.edn" (-> diagnostics :errors first :path))))))

;; -----------------------------------------------------------------------------
;; Tier-2 stepdef/glossary cross-check runs on every compile (sl-6wh9)
;; -----------------------------------------------------------------------------

(defn- register-mismatched-stepdef!
  "Register a stepdef whose :svo declares a frame absent from the test
   glossary (verb :swipe only has :default)."
  []
  (registry/register! #"(\w+) swipes the (.+)"
                      (fn [ctx _subject _thing] ctx)
                      {:ns 's :file "s.clj" :line 1}
                      {:interface :web
                       :svo {:subject :$1
                             :verb :swipe
                             :frame :nope
                             :object :$2}}))

(def ^:private ieie-glossaries
  {:subjects "test/fixtures/glossaries/subjects.edn"
   :verbs ["test/fixtures/glossaries/verbs-web-project.edn"]})

(deftest test-stepdef-glossary-mismatch-caught-without-enforcement-block
  (testing "no :enforcement block → Tier-2 still catches the bad frame; the
            old vanilla twin (sl-ieie) that let it through is retired (sl-6wh9)"
    (register-mismatched-stepdef!)
    (let [config {:interfaces {:web {:type :web :adapter :etaoin}}
                  :glossaries ieie-glossaries}
          pickle (make-pickle "test" ["Alice swipes the card"])
          {:keys [runnable? diagnostics]} (compile/compile-suite config [pickle] (registry/all-stepdefs))]
      (is (not runnable?))
      (is (= 1 (count (:stepdef-issues diagnostics))))
      (is (= :stepdef/unknown-frame (-> diagnostics :stepdef-issues first :type))))))

(deftest test-stepdef-glossary-mismatch-caught-with-enforcement-block
  (testing "same stepdef with an :enforcement block → identical result:
            presence means nothing"
    (register-mismatched-stepdef!)
    (let [config {:enforcement {}
                  :interfaces {:web {:type :web :adapter :etaoin}}
                  :glossaries ieie-glossaries}
          pickle (make-pickle "test" ["Alice swipes the card"])
          {:keys [runnable? diagnostics]} (compile/compile-suite config [pickle] (registry/all-stepdefs))]
      (is (not runnable?))
      (is (= 1 (count (:stepdef-issues diagnostics))))
      (is (= :stepdef/unknown-frame (-> diagnostics :stepdef-issues first :type))))))

;; -----------------------------------------------------------------------------
;; File-authoritative :type (sl-67dj) — run-path pins
;; -----------------------------------------------------------------------------

(deftest test-shifted-mode-missing-type-verb-glossary
  (testing "a :type-less verb file is a planning-class error on the run path"
    (registry/register! #"a step"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 1})
    (let [config {:enforcement {:unknown-verb :error}
                  :glossaries {:subjects "test/fixtures/glossaries/subjects.edn"
                               :verbs ["test/fixtures/glossaries/missing-type-verbs.edn"]}}
          pickle (make-pickle "test" ["a step"])
          {:keys [runnable? diagnostics]} (compile/compile-suite config [pickle] (registry/all-stepdefs))]
      (is (not runnable?))
      (is (= :glossary/missing-type (-> diagnostics :errors first :type)))
      (is (= "test/fixtures/glossaries/missing-type-verbs.edn"
             (-> diagnostics :errors first :path))))))

(deftest test-glossary-lints-ride-compile-diagnostics
  (testing "structured glossary-load warnings reach compile diagnostics
            (sl-67dj, Warden P3-13) without blocking the run"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.cljc" :line 1})
    (let [config {:enforcement {}
                  :glossaries {:subjects "test/fixtures/glossaries/subjects.edn"
                               :verbs ["test/fixtures/glossaries/override-verbs.edn"]}
                  :interfaces {:web {:type :web :adapter :etaoin}}}
          pickle (make-pickle "test" ["I have 5 items"])
          {:keys [runnable? diagnostics]} (compile/compile-suite config [pickle] (registry/all-stepdefs))]
      (is runnable? "warnings never block")
      (is (= 1 (count (:glossary-lints diagnostics))))
      (is (= :glossary/override-flag-ignored
             (-> diagnostics :glossary-lints first :type))))))

(deftest test-orphan-type-lint-fed-by-declared-types
  (testing "shifted mode: a project verb file whose DECLARED type no
            configured interface provides trips :glossary/orphan-type —
            the lint reads the loader's actual mounts (sl-67dj)"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (let [config {:enforcement {}
                  :glossaries {:subjects "test/fixtures/glossaries/subjects.edn"
                               :verbs ["test/fixtures/glossaries/verbs-github-web.edn"]}
                  :interfaces {:web {:type :web :adapter :etaoin}}}
          pickle (make-pickle "test" ["I have 5 items"])
          {:keys [runnable? diagnostics]} (compile/compile-suite config [pickle] (registry/all-stepdefs))]
      (is (not runnable?))
      (is (some #(= :glossary/orphan-type (:type %))
                (:suite-lint-issues diagnostics))
          "the declared :github/web mount, unbacked by any interface, is the orphan"))))

(deftest test-orphan-type-lint-runs-without-enforcement-block
  (testing "the orphan-type check runs on every compile (sl-6wh9 — the
            67dj vanilla carve-out is retired: glossaries always load, so
            declared types are always knowable on the run path)"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (let [config {;; no :enforcement block; glossaries load regardless
                  :glossaries {:verbs ["test/fixtures/glossaries/verbs-github-web.edn"]}
                  :interfaces {:web {:type :web :adapter :etaoin}}}
          pickle (make-pickle "test" ["I have 5 items"])
          {:keys [runnable? diagnostics]} (compile/compile-suite config [pickle] (registry/all-stepdefs))]
      (is (not runnable?))
      (is (some #(= :glossary/orphan-type (:type %))
                (:suite-lint-issues diagnostics))
          "the declared :github/web mount, unbacked by any interface, is the orphan"))))

;; -----------------------------------------------------------------------------
;; Interface Annotation Pipeline (sl-1ya)
;; -----------------------------------------------------------------------------

(def ^:private shifted-config-sms-web
  "Minimal Shifted config with :sms and :web interfaces configured."
  {:enforcement {:unknown-subject :warn :unknown-verb :warn :unknown-interface :warn}
   :glossaries {:subjects "test/fixtures/glossaries/subjects.edn"
                :verbs ["test/fixtures/glossaries/verbs-web-project.edn"]}
   :interfaces {:web {:type :web :adapter :etaoin}
                :sms {:type :sms :adapter :mock}}})

(deftest test-annotation-end-to-end-shifted-mode
  (testing "Shifted mode + [:sms] annotation routes to the :sms stepdef"
    ;; Identical regex under two interfaces — registry keying by interface
    ;; (sl-86d) enables this; annotation filter (sl-1ya) picks :sms.
    ;;
    ;; Verbs are chosen to be valid under each stepdef's interface
    ;; glossary post-sl-ups: the SMS side uses :send :to (the canonical
    ;; SMS send verb); the WEB foil uses :click :default (placeholder,
    ;; just exists to prove that annotation routing prefers :sms).
    (registry/register! #"(\S+) sends an SMS to '([^']+)' saying '([^']*)'"
                        (fn [_s _p _b] nil)
                        {:ns 's :file "s.clj" :line 1}
                        {:interface :sms
                         :svo {:subject :$1 :verb :send :frame :to
                               :object nil
                               :args {:to-phone :$2 :body :$3}}})
    (registry/register! #"(\S+) sends an SMS to '([^']+)' saying '([^']*)'"
                        (fn [_s _p _b] nil)
                        {:ns 's :file "s.clj" :line 2}
                        {:interface :web
                         :svo {:subject :$1 :verb :click :frame :default
                               :object "send-button"}})
    (let [pickle (make-pickle
                  "annotated"
                  ["[:sms] alice sends an SMS to '+15551234567' saying 'hi'"])
          {:keys [runnable? plans]} (compile/compile-suite
                                     shifted-config-sms-web
                                     [pickle]
                                     (registry/all-stepdefs))]
      (is runnable?)
      (let [step-result (-> plans first :plan/steps first)]
        (is (= :matched (:status step-result)))
        (is (= :sms (-> step-result :binding :svo :interface)))
        ;; :step/text preserved with annotation visible
        (is (= "[:sms] alice sends an SMS to '+15551234567' saying 'hi'"
               (-> step-result :step :step/text)))))))

(deftest test-annotation-mixed-same-type-instances-end-to-end
  (testing "THE sl-s2oa repro at compile level: two :web-typed instances;
            [:admin] over the shared :web-TYPE vocabulary binds and stamps
            the :admin instance; the unannotated line defaults to :web"
    (registry/register! #"(\S+) opens the browser to '([^']+)'"
                        (fn [_s _u] nil)
                        {:ns 's :file "s.clj" :line 1}
                        {:interface :web
                         :svo {:subject :$1 :verb :navigate :frame :to
                               :object :$2}})
    (let [config (assoc shifted-config-sms-web
                        :interfaces {:web {:type :web :adapter :etaoin}
                                     :admin {:type :web :adapter :playwright}})
          pickle (make-pickle
                  "mixed"
                  ["alice opens the browser to 'http://web.test/'"
                   "[:admin] alice opens the browser to 'http://admin.test/'"])
          {:keys [runnable? plans]} (compile/compile-suite
                                     config
                                     [pickle]
                                     (registry/all-stepdefs))]
      (is runnable?)
      (let [[web-step admin-step] (-> plans first :plan/steps)]
        (is (= :matched (:status web-step)))
        (is (= :web (-> web-step :binding :svo :interface))
            "unannotated defaults to the type-named instance")
        (is (= :matched (:status admin-step)))
        (is (= :admin (-> admin-step :binding :svo :interface))
            "[:admin] selects the renamed same-type instance")
        (is (= :web (-> admin-step :filter-info :resolved-type)))))))

(deftest test-annotation-unknown-interface-fails-compile
  (testing "[:whatsapp] with no :whatsapp in config → :annotation/unknown-interface"
    (registry/register! #"anything"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 1})
    (let [pickle (make-pickle "bad" ["[:whatsapp] anything"])
          {:keys [runnable? diagnostics]} (compile/compile-suite
                                           shifted-config-sms-web
                                           [pickle]
                                           (registry/all-stepdefs))]
      (is (not runnable?))
      (let [errs (:annotation-errors diagnostics)]
        (is (= 1 (count errs)))
        (is (= :annotation/unknown-interface (-> errs first :type)))
        (is (= :whatsapp (-> errs first :declared-interface)))))))

(deftest test-annotation-always-processed
  (testing "there is no mode in which [:foo] is literal step text (sl-6wh9):
            an annotation naming no configured instance is an annotation error
            even under the built-in defaults"
    (registry/register! #"\[:foo\] hello"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 1})
    (let [pickle (make-pickle "annotated" ["[:foo] hello"])
          {:keys [runnable? diagnostics]} (compile/compile-suite
                                           {}  ;; the defaults: only :web exists
                                           [pickle]
                                           (registry/all-stepdefs))]
      (is (not runnable?))
      (is (= :annotation/unknown-interface
             (-> diagnostics :annotation-errors first :type))))))

(deftest test-annotation-undefined-surfaces-other-interface-match
  (testing "[:sms] step with only a :web stepdef → undefined + filter-info"
    (registry/register! #"(\S+) does stuff"
                        (fn [_s] nil)
                        {:ns 's :file "s.clj" :line 1}
                        ;; :verb :click is a placeholder; this test exercises
                        ;; the annotation routing, not the verb itself.
                        {:interface :web
                         :svo {:subject :$1 :verb :click :frame :default
                               :object "stuff"}})
    (let [pickle (make-pickle "xi" ["[:sms] alice does stuff"])
          {:keys [runnable? plans]} (compile/compile-suite
                                     shifted-config-sms-web
                                     [pickle]
                                     (registry/all-stepdefs))]
      (is (not runnable?))
      (let [step-result (-> plans first :plan/steps first)]
        (is (= :undefined (:status step-result)))
        (is (= :sms (-> step-result :filter-info :declared-interface)))
        (is (= [:web] (-> step-result :filter-info :other-interfaces)))))))

(deftest test-annotation-macro-collision
  (testing "[:sms] with macro suffix + macros enabled → :annotation/on-macro-call-unsupported"
    (registry/register! #"anything" (fn [] nil) {:ns 's :file "s.clj" :line 1})
    (let [macro-path "test/fixtures/macros/auth.ini"
          config (assoc shifted-config-sms-web
                        :runner {:macros {:enabled? true
                                          :registry-paths [macro-path]}})
          pickle {:pickle/id (java.util.UUID/randomUUID)
                  :pickle/name "bad-macro"
                  :pickle/steps [{:step/id (java.util.UUID/randomUUID)
                                  :step/keyword "Given"
                                  :step/text "[:sms] login as alice +"
                                  :step/location {:line 5 :column 5}
                                  :step/arguments []}]}
          {:keys [runnable? diagnostics]} (compile/compile-suite
                                           config [pickle]
                                           (registry/all-stepdefs))]
      (is (not runnable?))
      (let [errs (:annotation-errors diagnostics)]
        (is (= 1 (count errs)))
        (is (= :annotation/on-macro-call-unsupported (-> errs first :type)))))))
