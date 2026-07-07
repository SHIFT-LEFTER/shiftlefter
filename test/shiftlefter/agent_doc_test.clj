(ns shiftlefter.agent-doc-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [shiftlefter.agent-doc :as agent-doc]))

(deftest topics-test
  (testing "topics are stable and include the default overview"
    (is (= ["intro" "overview" "authoring" "vocabulary" "locators" "diagnostics" "sieve" "builtins"]
           (agent-doc/topic-names)))
    (is (= "overview" agent-doc/default-topic))))

(deftest load-topic-test
  (testing "known topics load packaged Markdown resources"
    (let [result (agent-doc/load-topic "overview")]
      (is (= :ok (:status result)))
      (is (= "overview" (:topic result)))
      (is (str/includes? (:content result) "# ShiftLefter Agent Overview"))))

  (testing "missing topic is reported as data"
    (is (= {:status :missing-topic
            :topic "missing"}
           (agent-doc/load-topic "missing")))))

(deftest builtins-topic-test
  (testing "the generated builtins topic is a registered, packaged resource"
    (is (agent-doc/known-topic? "builtins"))
    (let [result (agent-doc/load-topic "builtins")]
      ;; :ok (not :missing-resource) proves the checked-in resource is on the
      ;; classpath — the same lookup the jar uses (AC3/AC7).
      (is (= :ok (:status result)))
      (is (str/includes? (:content result) "# ShiftLefter Built-in Vocabulary"))
      (is (str/includes? (:content result) "Interface `:web`"))
      (is (str/includes? (:content result) "Interface `:sms`")))))

(deftest format-topic-list-test
  (testing "list output includes every topic"
    (let [output (agent-doc/format-topic-list)]
      (is (str/includes? output "Available agent-doc topics:"))
      (doseq [topic (agent-doc/topic-names)]
        (is (str/includes? output topic))))))

(deftest agent-doc-cmd-test
  (testing "default command prints overview"
    (let [out (with-out-str
                (is (= 0 (agent-doc/agent-doc-cmd ["agent-doc"] {}))))]
      (is (str/includes? out "# ShiftLefter Agent Overview"))))

  (testing "--list prints topic list"
    (let [out (with-out-str
                (is (= 0 (agent-doc/agent-doc-cmd ["agent-doc"] {:list true}))))]
      (is (str/includes? out "Available agent-doc topics:"))
      (is (str/includes? out "authoring"))))

  (testing "named topic prints that topic"
    (let [out (with-out-str
                (is (= 0 (agent-doc/agent-doc-cmd ["agent-doc" "locators"] {}))))]
      (is (str/includes? out "# ShiftLefter Locator Policy"))))

  (testing "missing topic writes error to stderr"
    (let [out (java.io.StringWriter.)
          err (java.io.StringWriter.)
          code (binding [*out* out
                         *err* err]
                 (agent-doc/agent-doc-cmd ["agent-doc" "nope"] {}))]
      (is (= 1 code))
      (is (= "" (str out)))
      (is (str/includes? (str err) "Unknown agent-doc topic: nope"))
      (is (str/includes? (str err) "Available agent-doc topics:"))))

  (testing "extra positional arguments write usage to stderr"
    (let [out (java.io.StringWriter.)
          err (java.io.StringWriter.)
          code (binding [*out* out
                         *err* err]
                 (agent-doc/agent-doc-cmd ["agent-doc" "one" "two"] {}))]
      (is (= 1 code))
      (is (= "" (str out)))
      (is (str/includes? (str err) "Usage: sl agent-doc [topic]")))))
