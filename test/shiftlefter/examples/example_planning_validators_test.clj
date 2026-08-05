(ns shiftlefter.examples.example-planning-validators-test
  "Planning-level validators for shipped examples (sl-ybox).

   Guards the examples that rotted with no suite going red (sl-jsn step
   removal, sl-xso flag rename, sl-hse frames — 2 of 8 shipped examples
   broken while unit+e2e+daemon stayed green): 02b (all three config
   variants), 03, and svo-demo. Planning-level only — dry-run binding
   catches every rot class that actually occurred (removed steps, renamed
   flags, frame changes all surface as planning issues), needs no browser,
   no server (dry-run never orchestrates — proven sl-nart), no env gate.
   Runs in the bare `./bin/kaocha unit`. Live-browser depth stays ^:e2e
   where it already exists (05/06); example 02's health is covered by
   sl-nart's fully-offline acceptance.

   Validators read the SHIPPED example dirs directly — rot detection
   requires the real thing, so no fixture copies.

   The shifted-no-glossary variant asserts planning FAILURE (exit 2): its
   teaching point is that every step is rejected without a glossary, so
   this pins the lesson in both directions — the other validators go red
   if binding breaks, this one goes red if binding is accidentally
   granted."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.project-context :as pc]
            [shiftlefter.runner.core :as rc]))

(defn- plan-example
  "Dry-run the runner's planning path in-process against a shipped example.
   Returns {:exit <int> :status <kw> :plan-count <int>} — plan-count reads
   :plans (flat runs) or the union of group :plans (setup.clj runs, where
   the top-level result has :groups and no :plans). Output is captured so
   green suite runs don't print diagnostic walls."
  [config-path paths]
  (let [result (atom nil)]
    (with-out-str
      (reset! result
              (rc/execute! {:paths paths
                            :project-context (pc/resolve {:config-path config-path})
                            :dry-run true})))
    (let [{:keys [exit-code status plans groups]} @result]
      {:exit exit-code
       :status status
       :plan-count (if groups
                     (reduce + 0 (map #(count (:plans %)) groups))
                     (count plans))})))

;; sl/ layout (sl-fcjm): each example's project lives under <example>/sl/.
(def ^:private ex-02b "examples/02b-browser-multi-actor/sl/")
(def ^:private ex-03 "examples/03-custom-steps/sl/")
(def ^:private ex-svo "examples/svo-demo/sl/")

(deftest multi-actor-primary-config-plans-clean
  (let [{:keys [exit plan-count]} (plan-example (str ex-02b "shiftlefter.edn")
                                                [(str ex-02b "features")])]
    (testing "02b Shifted config binds clean through the setup.clj union"
      (is (= 0 exit))
      (is (pos? plan-count)))))

(deftest multi-actor-vanilla-config-plans-clean
  (let [{:keys [exit plan-count]} (plan-example (str ex-02b "shiftlefter-vanilla.edn")
                                                [(str ex-02b "features")])]
    (testing "02b Vanilla contrast config binds clean (any subject accepted)"
      (is (= 0 exit))
      (is (pos? plan-count)))))

(deftest multi-actor-no-glossary-config-rejects-all
  (let [{:keys [exit status]} (plan-example (str ex-02b "shiftlefter-shifted-no-glossary.edn")
                                            [(str ex-02b "features")])]
    (testing "02b shifted-no-glossary REJECTS every step — the variant's teaching point"
      (is (= 2 exit))
      (is (= :planning-failed status)))))

(deftest custom-steps-plans-clean
  (let [{:keys [exit plan-count]} (plan-example (str ex-03 "shiftlefter.edn")
                                                [(str ex-03 "features")])]
    (testing "03 custom stepdefs load and bind at planning time"
      (is (= 0 exit))
      (is (pos? plan-count)))))

(deftest svo-demo-plans-clean
  (let [{:keys [exit plan-count]} (plan-example (str ex-svo "shiftlefter.edn")
                                                [(str ex-svo "features")])]
    (testing "svo-demo binds and SVO-validates on the canonical glossary layout (sl-q9t)"
      (is (= 0 exit))
      (is (pos? plan-count)))))
