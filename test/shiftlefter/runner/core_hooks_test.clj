(ns shiftlefter.runner.core-hooks-test
  "End-to-end tests of hooks.clj discovery + @hook= resolution through the
   runner pipeline (sl-esq, planning half).

   Wires runner.core/execute! against a temp project containing a
   shiftlefter.edn, a hooks.clj, and features carrying @hook= tags.
   Execution-weave behavior (Befores/Afters actually firing) is covered in
   stepengine/exec_hooks_test.clj; here we verify the PLANNING contract:
   discovery, unknown-name planning errors, and :plan/hooks on the result."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.core :as core]))

;; -----------------------------------------------------------------------------
;; Temp-project helpers (same idiom as core_setup_test.clj)
;; -----------------------------------------------------------------------------

(defn- with-temp-project [f]
  (let [dir (str (fs/create-temp-dir {:prefix "sl-core-hooks-test-"}))]
    (try
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(defn- spit-file [dir relative content]
  (let [target (fs/path dir relative)]
    (fs/create-dirs (fs/parent target))
    (spit (str target) content)
    (str target)))

(defn- run-captured
  "Run execute! capturing stdout+stderr; returns {:result :out :err}."
  [opts]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        result (binding [*out* out *err* err]
                 (core/execute! (merge {:no-color true} opts)))]
    {:result result :out (str out) :err (str err)}))

(def ^:private trivial-feature
  "@hook=reset-db
Feature: Hooked
  Scenario: Trivial
    Given a trivial hooked assertion
")

(def ^:private trivial-steps
  "(ns hooked-steps
     (:require [shiftlefter.stepengine.registry :refer [defstep]]))
   (defstep #\"a trivial hooked assertion\" [] nil)")

(def ^:private simple-hooks
  "(ns hooks)
   (def hooks
     [{:name \"reset-db\" :before (fn [_] nil)}])")

(defn- project! [dir feature hooks-content]
  (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
  (let [fpath (spit-file dir "features/hooked.feature" feature)]
    (spit-file dir "steps/hooked.clj" trivial-steps)
    (when hooks-content
      (spit-file dir "hooks.clj" hooks-content))
    fpath))

;; -----------------------------------------------------------------------------
;; Tests
;; -----------------------------------------------------------------------------

(deftest hooks-clj-discovered-and-attached
  (testing "a valid @hook= run passes; :plan/hooks rides the result's plans"
    (with-temp-project
      (fn [dir]
        (let [fpath (project! dir trivial-feature simple-hooks)
              {:keys [result]} (run-captured
                                {:paths [fpath]
                                 :config-path (str (fs/path dir "shiftlefter.edn"))
                                 :step-paths [(str (fs/path dir "steps"))]})]
          (is (= 0 (:exit-code result)) (str "got " result))
          (let [plan (-> result :result :scenarios first :plan)]
            (is (= ["reset-db"] (mapv :name (:plan/hooks plan)))
                ":plan/hooks resolved and attached")))))))

(deftest missing-hooks-clj-is-silently-fine
  (testing "no hooks.clj + no @hook= tags — plans untouched, run green"
    (with-temp-project
      (fn [dir]
        (let [fpath (project! dir (str/replace trivial-feature "@hook=reset-db\n" "")
                              nil)
              {:keys [result]} (run-captured
                                {:paths [fpath]
                                 :config-path (str (fs/path dir "shiftlefter.edn"))
                                 :step-paths [(str (fs/path dir "steps"))]})]
          (is (= 0 (:exit-code result)))
          (is (nil? (-> result :result :scenarios first :plan :plan/hooks))))))))

(deftest unknown-hook-name-is-planning-error
  (testing "@hook=<typo> = exit 2 naming the tag and its file:line"
    (with-temp-project
      (fn [dir]
        (let [fpath (project! dir (str/replace trivial-feature
                                               "@hook=reset-db" "@hook=rest-db")
                              simple-hooks)
              {:keys [result err]} (run-captured
                                    {:paths [fpath]
                                     :config-path (str (fs/path dir "shiftlefter.edn"))
                                     :step-paths [(str (fs/path dir "steps"))]})]
          (is (= 2 (:exit-code result)))
          (is (= :planning-failed (:status result)))
          (is (str/includes? err "@hook=rest-db"))
          (is (str/includes? err "hooked.feature:1") "tag's file:line named")
          (is (str/includes? err "reset-db") "known hooks listed"))))))

(deftest hook-tag-without-hooks-clj-is-planning-error
  (testing "@hook= with NO hooks.clj fails loudly at planning"
    (with-temp-project
      (fn [dir]
        (let [fpath (project! dir trivial-feature nil)
              {:keys [result err]} (run-captured
                                    {:paths [fpath]
                                     :config-path (str (fs/path dir "shiftlefter.edn"))
                                     :step-paths [(str (fs/path dir "steps"))]})]
          (is (= 2 (:exit-code result)))
          (is (str/includes? err "no hooks.clj found")))))))

(deftest malformed-hooks-clj-is-planning-error
  (testing "duplicate :name = exit 2 before anything runs"
    (with-temp-project
      (fn [dir]
        (let [fpath (project! dir trivial-feature
                              "(ns hooks)
                               (def hooks
                                 [{:name \"reset-db\" :before (fn [_] nil)}
                                  {:name \"reset-db\" :after (fn [_] nil)}])")
              {:keys [result err]} (run-captured
                                    {:paths [fpath]
                                     :config-path (str (fs/path dir "shiftlefter.edn"))
                                     :step-paths [(str (fs/path dir "steps"))]})]
          (is (= 2 (:exit-code result)))
          (is (str/includes? err "duplicate")))))))

(deftest before-failure-is-red-run-with-error-rendering
  (testing "a throwing :before = exit 1, scenario :error, console shows the
            hook failure with dual attribution and the error summary segment"
    (with-temp-project
      (fn [dir]
        (let [fpath (project! dir trivial-feature
                              "(ns hooks)
                               (def hooks
                                 [{:name \"reset-db\"
                                   :before (fn [_] (throw (ex-info \"db down\" {})))}])")
              {:keys [result err]} (run-captured
                                    {:paths [fpath]
                                     :config-path (str (fs/path dir "shiftlefter.edn"))
                                     :step-paths [(str (fs/path dir "steps"))]})]
          (is (= 1 (:exit-code result)) "red run — never exit 2")
          (is (= :error (-> result :result :scenarios first :status)))
          (is (str/includes? err "ERROR Trivial") "live status line renders ERROR")
          (is (str/includes? err "Hook 'reset-db' failed"))
          (is (str/includes? err "db down"))
          (is (str/includes? err "registered at:"))
          (is (str/includes? err "tagged at:"))
          (is (str/includes? err "1 error") "summary segment present"))))))

(deftest unknown-hook-name-edn-summary
  (testing "--edn planning error carries the structured :hooks/unknown-name"
    (with-temp-project
      (fn [dir]
        (let [fpath (project! dir (str/replace trivial-feature
                                               "@hook=reset-db" "@hook=nope")
                              simple-hooks)
              {:keys [result out]} (run-captured
                                    {:paths [fpath]
                                     :config-path (str (fs/path dir "shiftlefter.edn"))
                                     :step-paths [(str (fs/path dir "steps"))]
                                     :edn true})]
          (is (= 2 (:exit-code result)))
          (is (str/includes? out ":hooks/unknown-name")))))))
