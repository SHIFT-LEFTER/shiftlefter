(ns shiftlefter.onramp-test
  "The agent on-ramp breadcrumb (sl-k0s) is emit-only: a single stanza source
   packaged as a jar classpath resource. k0s never writes a user agent file —
   injection is owned by sl init (cn7). These tests pin the single source and
   that it routes to the real surfaces."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private stanza-resource "shiftlefter/onramp/agents-breadcrumb.md")

(deftest stanza-is-a-packaged-resource
  (testing "the single stanza source is on the classpath (AC6) — sl init/sl-tje reuse it"
    (is (some? (io/resource stanza-resource)))))

(deftest stanza-routes-to-real-surfaces
  (let [stanza (slurp (io/resource stanza-resource))]
    (testing "routes a cold agent to the real surfaces (AC2/AC4)"
      (is (str/includes? stanza "sl agent-doc"))
      (is (str/includes? stanza "sl agent-doc builtins"))
      (is (str/includes? stanza "sl orient"))
      (is (str/includes? stanza "sl orient --edn")))
    (testing "states the bootstrap paradox / documented entry point (AC5)"
      (is (str/includes? stanza "bootstrap paradox"))
      (is (str/includes? stanza "entry point")))))
