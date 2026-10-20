(ns shiftlefter.runner.unshifted-suite-test
  "sl-6wh9 — the retirement's runner surfaces, driven through
   `runner/execute!` on real temp projects:

   - the SUMMARY LINE: every selected step undefined → exit 2 carries the
     :unshifted-suite inventory (top-level EDN key beside :planning; two
     console lines);
   - the MIXED-STATE HINT: some scenarios plan, some do not → the exit-2
     :planning block carries :plannability and the console names
     --hold-unplannable; never on a structural exit 2;
   - --hold-unplannable: the plannable selection runs (exit 0 when green),
     the held scenarios ride :held + :counts :held on EDN, the console
     verdict line, and JUnit (<skipped>); with nothing plannable the
     summary line still fires.

   Fixtures are REAL-MESSY on purpose: a Cucumber-shaped corpus (`I …`
   steps, no subject tokens, an outline) is what an unshifted suite looks
   like when someone copies one into sl/features/."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.runner.core :as runner]
            [shiftlefter.stepengine.registry :as registry]))

(defn- clean-registry-fixture [f]
  (registry/clear-registry!)
  (f)
  (registry/clear-registry!))

(use-fixtures :each clean-registry-fixture)

;; -----------------------------------------------------------------------------
;; Fixtures
;; -----------------------------------------------------------------------------

(def ^:private cucumber-login
  "Feature: Login
  Scenario: Successful login
    Given I am on the login page
    When I enter \"alice\" and \"secret\"
    And I press the login button
    Then I should see the dashboard

  Scenario Outline: Rejected logins
    Given I am on the login page
    When I enter \"<user>\" and \"<password>\"
    And I press the login button
    Then I should see \"<message>\"

    Examples:
      | user  | password | message          |
      | alice | wrong    | Invalid password |
      | nobody| secret   | Unknown user     |
")

(def ^:private cucumber-cart
  "Feature: Cart
  Scenario: Add an item
    Given I am on the login page
    When I add \"Widget\" to the cart
    Then the cart should have 1 item
")

(def ^:private typed-steps
  ";; A tiny typed step library: subject checked against the glossary, no
   ;; :interface (nothing to provision), so the scenarios run in-process.
   (ns steps.counting
     (:require [shiftlefter.stepengine.registry :refer [defstep]]))
   (defstep #\"^:(\\S+) counts to (\\d+)$\"
     {:svo {:subject :$1 :verb :count :frame :to}}
     [ctx _subject n]
     (assoc ctx :n (parse-long n)))
   (defstep #\"^:(\\S+) should have counted (\\d+)$\"
     {:svo {:subject :$1 :verb :check :frame :count}}
     [ctx _subject n]
     (when-not (= (parse-long n) (:n ctx))
       (throw (ex-info \"count mismatch\" {})))
     ctx)")

(def ^:private mixed-feature
  "Feature: Counting
  Scenario: One
    Given :user counts to 3
    Then :user should have counted 3

  Scenario: Two
    Given :user counts to 5
    Then :user should have counted 5

  Scenario: Not shifted yet
    Given I eat some cake
    Then I should be happy
")

(def ^:private subjects-glossary
  "{:subjects {:user {:desc \"the counter\"}}}")

(defn- with-project
  "Write an sl/-layout temp project: `features` is {relative-name text},
   `steps` an optional steps file text, `config` the shiftlefter.edn map.
   Calls `f` with paths; cleans up."
  [{:keys [features steps config glossary]} f]
  (let [dir (str (fs/create-temp-dir {:prefix "sl-unshifted-"}))
        sl-dir (fs/path dir "sl")]
    (try
      (fs/create-dirs (fs/path sl-dir "steps"))
      (fs/create-dirs (fs/path sl-dir "features"))
      (doseq [[name text] features]
        (spit (str (fs/path sl-dir "features" name)) text))
      (when steps
        (spit (str (fs/path sl-dir "steps" "counting.clj")) steps))
      (when glossary
        (fs/create-dirs (fs/path sl-dir "glossary"))
        (spit (str (fs/path sl-dir "glossary" "subjects.edn")) glossary))
      (spit (str (fs/path sl-dir "shiftlefter.edn")) (pr-str (or config {})))
      (f {:dir dir
          :config-path (str (fs/path sl-dir "shiftlefter.edn"))
          :steps (str (fs/path sl-dir "steps"))
          :features (str (fs/path sl-dir "features"))})
      (finally
        (fs/delete-tree dir)))))

(defn- execute
  "execute! capturing stdout/stderr. `opts` merge over the project's paths."
  [{:keys [config-path steps features]} opts]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        result (binding [*out* out *err* err]
                 (runner/execute! (merge {:paths [features]
                                          :config-path config-path
                                          :step-paths [steps]
                                          :no-color true}
                                         opts)))]
    {:result result
     :summary (when (:edn opts) (edn/read-string (str out)))
     :err (str err)}))

;; -----------------------------------------------------------------------------
;; The summary line
;; -----------------------------------------------------------------------------

(deftest unshifted-corpus-gets-the-summary-line
  (with-project {:features {"login.feature" cucumber-login
                            "cart.feature" cucumber-cart}}
    (fn [p]
      (testing "--edn: exit 2, :unshifted-suite top-level beside :planning"
        (let [{:keys [result summary]} (execute p{:dry-run true :edn true})]
          (is (= 2 (:exit-code result)))
          (is (= :planning-failed (:run/status summary)))
          (is (map? (:planning summary)))
          (is (= {:features 2 :scenarios 4 :distinct-steps 10}
                 (:unshifted-suite summary))
              "2 files, 1 + 2 outline rows + 1 scenarios, 10 distinct step
               texts (outline rows expand to distinct texts)")
          (is (not (contains? (:planning summary) :plannability))
              "nothing plans — no mixed-state fact")))
      (testing "console: the two placeholder lines after the diagnostics"
        (let [{:keys [result err]} (execute p{:dry-run true})]
          (is (= 2 (:exit-code result)))
          (is (str/includes? err "Undefined steps:"))
          (is (str/includes? err "This reads as an unshifted suite: every selected step is undefined."))
          (is (str/includes? err "2 feature file(s), 4 scenario(s), 10 distinct step text(s)"))
          (is (str/includes? err "executes only typed steps"))
          (is (str/includes? err "To shift this suite, run: sl agent-doc adoption")
              "sl-d4pf: the pointer is a command that works from the jar alone, not a repo path")
          (is (not (str/includes? err "docs/adoption.md")))
          (is (not (str/includes? err "--hold-unplannable"))
              "no hint when nothing would run under the flag"))))))

(deftest unshifted-corpus-with-no-config-file-at-all
  ;; The step-1 walk of ruling #5: features copied in, no config, no
  ;; glossary — the summary line, not a missing-glossaries error.
  (let [dir (str (fs/create-temp-dir {:prefix "sl-unshifted-bare-"}))
        feature (str (fs/path dir "login.feature"))]
    (try
      (spit feature cucumber-login)
      (let [out (java.io.StringWriter.)
            err (java.io.StringWriter.)
            result (binding [*out* out *err* err]
                     (runner/execute! {:paths [feature]
                                       :step-paths [dir]
                                       :dry-run true :edn true :no-color true}))
            summary (edn/read-string (str out))]
        (is (= 2 (:exit-code result)))
        (is (= {:features 1 :scenarios 3 :distinct-steps 8}
               (:unshifted-suite summary)))
        (is (not-any? #(= :svo/missing-glossaries-config (:type %))
                      (get-in summary [:planning :errors]))
            "the missing-glossaries error is gone"))
      (finally
        (fs/delete-tree dir)))))

;; -----------------------------------------------------------------------------
;; The mixed-state hint
;; -----------------------------------------------------------------------------

(deftest mixed-corpus-gets-the-hint
  (with-project {:features {"counting.feature" mixed-feature}
                 :steps typed-steps
                 :glossary subjects-glossary}
    (fn [p]
      (testing "--edn: exit 2 with :plannability in :planning, no summary line"
        (let [{:keys [result summary]} (execute p{:dry-run true :edn true})]
          (is (= 2 (:exit-code result)))
          (is (= {:plannable 2 :unplannable 1 :total 3}
                 (get-in summary [:planning :plannability])))
          (is (not (contains? summary :unshifted-suite)))))
      (testing "console: the hint names the counts and the flag"
        (let [{:keys [err]} (execute p{:dry-run true})]
          (is (str/includes? err "2 of 3 scenario(s) plan; 1 are held by undefined, ambiguous or binding-error steps."))
          (is (str/includes? err "To run the 2 now and list the 1, pass --hold-unplannable."))
          (is (not (str/includes? err "unshifted suite"))))))))

(deftest one-typo-in-many-still-gets-the-hint
  ;; The 39-of-40 case (ruling #5): the fact is true, so the hint fires;
  ;; suppressing it on small counts would be the machine guessing intent.
  (with-project {:features {"counting.feature"
                            (str "Feature: Many\n"
                                 (str/join (for [i (range 1 40)]
                                             (str "  Scenario: S" i "\n"
                                                  "    Given :user counts to " i "\n")))
                                 "  Scenario: Typo\n    Given :user counst to 1\n")}
                 :steps typed-steps
                 :glossary subjects-glossary}
    (fn [p]
      (let [{:keys [result summary]} (execute p{:dry-run true :edn true})]
        (is (= 2 (:exit-code result)))
        (is (= {:plannable 39 :unplannable 1 :total 40}
               (get-in summary [:planning :plannability])))))))

(deftest structural-exit-2-carries-neither-fact
  (with-project {:features {"counting.feature" mixed-feature}
                 :steps typed-steps
                 :glossary subjects-glossary
                 :config {:enforcement {:unknown-subject :warm}}}
    (fn [p]
      (let [{:keys [result summary err]} (execute p{:dry-run true :edn true})]
        (is (= 2 (:exit-code result)))
        (is (= [:config/invalid-enforcement-level]
               (mapv :type (get-in summary [:planning :errors]))))
        (is (not (contains? summary :unshifted-suite)))
        (is (not (contains? (:planning summary) :plannability)))
        (is (not (str/includes? err "--hold-unplannable")))))))

;; -----------------------------------------------------------------------------
;; --hold-unplannable
;; -----------------------------------------------------------------------------

(deftest hold-unplannable-runs-what-plans
  (with-project {:features {"counting.feature" mixed-feature}
                 :steps typed-steps
                 :glossary subjects-glossary}
    (fn [{:keys [dir] :as p}]
      (testing "dry-run --edn: exit 0, the held record and count"
        (let [{:keys [result summary]} (execute p{:dry-run true :edn true
                                                :hold-unplannable true})]
          (is (= 0 (:exit-code result)))
          (is (= :dry-run (:run/status summary)))
          (is (= {:scenarios 2 :steps 4 :held 1} (:counts summary)))
          (let [[held :as helds] (:held summary)]
            (is (= 1 (count helds)))
            (is (= "Not shifted yet" (:scenario held)))
            (is (= "Counting" (:feature held)))
            (is (= :unplannable (:reason held)))
            (is (= 2 (:undefined held)))
            (is (= 0 (:ambiguous held)))
            (is (str/ends-with? (:uri held) "counting.feature"))
            (is (= 10 (:line held))))))
      (testing "dry-run console: the held notice"
        (let [{:keys [err]} (execute p{:dry-run true :hold-unplannable true})]
          (is (str/includes? err "2 scenario(s) bound successfully (dry run)"))
          (is (str/includes? err "1 scenario(s) held:"))
          (is (str/includes? err "Not shifted yet"))
          (is (str/includes? err "; not plannable (2 undefined, 0 ambiguous))"))))
      (testing "live run: exit 0, :held on the summary, the verdict line, JUnit <skipped>"
        (let [junit (str (fs/path dir "results.xml"))
              {:keys [result summary]} (execute p{:edn true :hold-unplannable true
                                                :junit-xml junit})]
          (is (= 0 (:exit-code result)))
          (is (= :passed (:run/status summary)))
          (is (= 2 (get-in summary [:counts :passed])))
          (is (= 1 (get-in summary [:counts :held])))
          (is (= ["Not shifted yet"] (mapv :scenario (:held summary))))
          (let [xml (slurp junit)]
            (is (str/includes? xml "<testcase name=\"Not shifted yet\" classname=\"Counting\""))
            (is (str/includes? xml "<skipped message=\"held: not plannable (2 undefined, 0 ambiguous step(s))\"/>"))
            (is (str/includes? xml "tests=\"3\""))
            (is (str/includes? xml "skipped=\"1\""))))
        (let [{:keys [result err]} (execute p{:hold-unplannable true})]
          (is (= 0 (:exit-code result)))
          (is (str/includes? err "3 scenario(s): 2 passed, 1 held")))))))

(deftest hold-unplannable-with-nothing-plannable-is-the-summary-line
  (with-project {:features {"login.feature" cucumber-login}}
    (fn [p]
      (let [{:keys [result summary err]} (execute p{:dry-run true :edn true
                                                  :hold-unplannable true})]
        (is (= 2 (:exit-code result)))
        (is (= {:features 1 :scenarios 3 :distinct-steps 8}
               (:unshifted-suite summary)))
        (is (not (contains? summary :held)))
        (is (not (str/includes? err "unshifted suite"))
            "EDN mode: the summary line stays off stderr (the envelope carries it)")))))

(deftest hold-unplannable-is-inert-on-a-clean-suite
  (with-project {:features {"counting.feature"
                            "Feature: Clean\n  Scenario: One\n    Given :user counts to 1\n"}
                 :steps typed-steps
                 :glossary subjects-glossary}
    (fn [p]
      (let [{:keys [result summary]} (execute p{:dry-run true :edn true
                                              :hold-unplannable true})]
        (is (= 0 (:exit-code result)))
        (is (not (contains? summary :held)))
        (is (not (contains? (:counts summary) :held)))))))
