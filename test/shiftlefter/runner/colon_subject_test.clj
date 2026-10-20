(ns shiftlefter.runner.colon-subject-test
  "sl-74c2 — a user stepdef that captures the subject WITH its leading
   colon (`(\\S+)` where the builtins use `:(\\S+)`) must mean the same
   subject, and every EDN summary must stay readable:

   - normalize-subject strips one leading ':' — `:user` in the feature is
     the glossary's :user, no unknown-subject issue;
   - the compile-suite diagnostics are scrubbed at attach, so the dry-run
     summary and the exit-2 planning block (which read diagnostics raw)
     can never emit an unreadable token;
   - a misspelled `:usr` keeps its did-you-mean (:suggestion :user + the
     :def-site, sl-u2r0) and the console names :usr, never ::usr;
   - a project on the builtin `:(\\S+)` shape is byte-identical.

   Driven through `runner/execute!` on a real temp project."
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

(def ^:private colon-capturing-steps
  ";; The user's pattern captures the colon: (\\S+) where the builtins write :(\\S+)
   (ns steps.colon
     (:require [shiftlefter.stepengine.registry :refer [defstep]]))
   (defstep #\"^(\\S+) counts to (\\d+)$\"
     {:svo {:subject :$1 :verb :count :frame :to}}
     [ctx _subject n]
     (assoc ctx :n (parse-long n)))")

(def ^:private builtin-shape-steps
  "(ns steps.colon
     (:require [shiftlefter.stepengine.registry :refer [defstep]]))
   (defstep #\"^:(\\S+) counts to (\\d+)$\"
     {:svo {:subject :$1 :verb :count :frame :to}}
     [ctx _subject n]
     (assoc ctx :n (parse-long n)))")

(def ^:private subjects-glossary
  "{:subjects {:user {:desc \"the counter\"}}}")

(def ^:private known-feature
  "Feature: C\n  Scenario: One\n    Given :user counts to 1\n")

(def ^:private misspelled-feature
  "Feature: C\n  Scenario: One\n    Given :user counts to 1\n  Scenario: Two\n    Given :usr counts to 2\n")

(defn- with-project
  [{:keys [feature steps config]} f]
  (let [dir (str (fs/create-temp-dir {:prefix "sl-colon-"}))
        sl-dir (fs/path dir "sl")]
    (try
      (fs/create-dirs (fs/path sl-dir "steps"))
      (fs/create-dirs (fs/path sl-dir "features"))
      (fs/create-dirs (fs/path sl-dir "glossary"))
      (spit (str (fs/path sl-dir "features" "c.feature")) feature)
      (spit (str (fs/path sl-dir "steps" "colon.clj")) steps)
      (spit (str (fs/path sl-dir "glossary" "subjects.edn")) subjects-glossary)
      (spit (str (fs/path sl-dir "shiftlefter.edn")) (pr-str (or config {})))
      (f {:config-path (str (fs/path sl-dir "shiftlefter.edn"))
          :steps (str (fs/path sl-dir "steps"))
          :features (str (fs/path sl-dir "features"))})
      (finally
        (fs/delete-tree dir)))))

(defn- execute
  [{:keys [config-path steps features]} opts]
  (registry/clear-registry!)
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        result (binding [*out* out *err* err]
                 (runner/execute! (merge {:paths [features]
                                          :config-path config-path
                                          :step-paths [steps]
                                          :no-color true}
                                         opts)))]
    {:result result
     :out (str out)
     :summary (when (:edn opts) (edn/read-string (str out)))
     :err (str err)}))

(deftest a-colon-captured-known-subject-is-the-known-subject
  (with-project {:feature known-feature :steps colon-capturing-steps}
    (fn [p]
      (testing "executed --edn: readable, exit 0, no unknown-subject issue at all"
        (let [{:keys [result summary]} (execute p {:edn true})]
          (is (= 0 (:exit-code result)))
          (is (= :passed (:run/status summary)))
          (is (not (contains? summary :diagnostics)) "the glossary's :user is known")))
      (testing "--dry-run --edn: readable (was `Invalid token: ::user`)"
        (let [{:keys [result summary]} (execute p {:edn true :dry-run true})]
          (is (= 0 (:exit-code result)))
          (is (= :dry-run (:run/status summary)))
          (is (not (contains? summary :diagnostics))))))))

(deftest a-colon-captured-misspelled-subject-keeps-its-did-you-mean
  (with-project {:feature misspelled-feature :steps colon-capturing-steps}
    (fn [p]
      (testing "executed --edn: the issue names :usr (never ::usr) with :suggestion :user + :def-site"
        (let [{:keys [result summary out]} (execute p {:edn true})]
          (is (= 0 (:exit-code result)))
          (let [[issue :as issues] (get-in summary [:diagnostics :svo-issues])]
            (is (= 1 (count issues)) "only :usr is unknown; :user is not")
            (is (= :svo/unknown-subject (:type issue)))
            (is (= :usr (:subject issue)))
            (is (= :user (:suggestion issue)))
            (is (str/ends-with? (get-in issue [:def-site :file]) "subjects.edn"))
            (is (= :user (get-in issue [:def-site :entry]))))
          (is (not (str/includes? out "::")) "no unreadable token anywhere in the stream")))
      (testing "--dry-run --edn: readable, same issue"
        (let [{:keys [summary out]} (execute p {:edn true :dry-run true})]
          (is (= :usr (-> summary :diagnostics :svo-issues first :subject)))
          (is (not (str/includes? out "::")))))
      (testing "console: the warning names :usr and its did-you-mean"
        (let [{:keys [err]} (execute p {})]
          (is (str/includes? err ":usr"))
          (is (not (str/includes? err "::usr")))
          (is (str/includes? err "Unknown subject :usr in step \":usr counts to 2\""))
          (is (str/includes? err "Did you mean: :user?"))
          (is (str/includes? err "Declared in: ")))))))

(deftest exit-2-planning-block-reads-back
  (with-project {:feature misspelled-feature :steps colon-capturing-steps
                 :config {:enforcement {:unknown-subject :error}}}
    (fn [p]
      (let [{:keys [result summary out]} (execute p {:edn true})]
        (is (= 2 (:exit-code result)))
        (is (= :planning-failed (:run/status summary)))
        (is (= :usr (-> summary :planning :svo-issues first :subject)))
        (is (= :user (-> summary :planning :svo-issues first :suggestion)))
        (is (not (str/includes? out "::")))))))

(deftest the-builtin-shape-is-byte-identical
  ;; The control: a stepdef on the builtins' :(\S+) shape sees no change —
  ;; the same summary keys, the same console, nothing new on either.
  (with-project {:feature known-feature :steps builtin-shape-steps}
    (fn [p]
      (let [{:keys [result summary]} (execute p {:edn true})]
        (is (= 0 (:exit-code result)))
        (is (= #{:run/id :run/exit-code :run/status :run/seed :run/results-path :counts}
               (set (keys summary)))))
      (let [{:keys [err]} (execute p {})]
        (is (re-matches #"run seed: \d+\nPASSED One\n\n1 scenario\(s\): 1 passed\n" err)
            (str "got: " err))))))
