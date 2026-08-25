(ns shiftlefter.projection.usage-index-test
  "The sl-mpu5 corpus reverse index: bind-without-executing, tuple
   extraction, aggregates, deadness join, selection semantics, parse-error
   tolerance — and the plan-verdict fold-in: a USER-LIB stepdef's verb must
   register as used (stepdef-load parity with the runner path)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.project-projection :as projection]
            [shiftlefter.runner.tag-disposition :as tagd]
            [shiftlefter.svo.glossary :as glossary]
            [shiftlefter.projection.usage-index :as usage]))

;; -----------------------------------------------------------------------------
;; Fixtures — mirrors resolution_test's temp-project pattern + a corpus
;; -----------------------------------------------------------------------------

(defn- temp-dir [] (str (fs/create-temp-dir)))

(defn- write-file! [path content]
  (fs/create-dirs (fs/parent path))
  (spit (str path) content))

(defn- write-edn! [path value] (write-file! path (pr-str value)))

(defn- with-temp-project [f]
  (let [dir (temp-dir)]
    (try (f dir) (finally (when (fs/exists? dir) (fs/delete-tree dir))))))

(def ^:private login-feature
  "Feature: Login
  @auth
  Scenario: Alice logs in
    When :alice clicks Login.email
    Then :alice should see 'Welcome'

  Scenario: Alice and Bob together
    When :alice clicks Login.email
    When :bob frobs Login.email
")

(def ^:private frobs-stepdef
  "(ns frobs-steps
  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

(defstep #\"^:([\\w./-]+) frobs (.+)$\"
  {:interface :web
   :svo {:subject :$1 :verb :frobs :frame :default :object :$2}}
  [ctx _subject _object]
  ctx)
")

(defn- make-project!
  "Shifted project with a corpus: two scenarios (one multi-actor), a
   user-lib stepdef in sl/steps/ binding the user verb :frobs, and the
   Login intent both steps reference."
  [dir]
  (write-edn! (fs/path dir "sl" "glossary" "subjects.edn")
              {:subjects {:user {:desc "Application user" :instances [:alice :bob]}}})
  (write-edn! (fs/path dir "sl" "glossary" "verbs-web.edn")
              {:verbs {:frobs {:desc "Frobnicate"
                               :frames {:default {:args [] :pattern "S frobs O"}}}}})
  (write-edn! (fs/path dir "sl" "glossary" "intents" "Login.edn")
              {:intent "Login"
               :description "Login flow"
               :elements {:email {:description "Email input"
                                  :bindings {:web {:css "#email"}}}}})
  (write-file! (fs/path dir "sl" "steps" "frobs_steps.clj") frobs-stepdef)
  (write-file! (fs/path dir "sl" "features" "login.feature") login-feature)
  (write-edn! (fs/path dir "sl" "shiftlefter.edn")
              {:runner {:step-paths ["steps/"] :features ["features/"]
                        :macros {:enabled? false}}
               :glossaries {:subjects "glossary/subjects.edn"
                            :verbs {:web "glossary/verbs-web.edn"}
                            :intents "glossary/intents"}
               :interfaces {:web {:type :web :adapter :etaoin :config {:headless true}}}
               :svo {:unknown-subject :error}}))

(defn- bound-corpus
  "build-projection (loads stepdefs into the registry — the parity path),
   then bind-corpus. Returns [proj corpus glossary-map]."
  [dir & [selection]]
  (let [ctx (project-context/resolve {:invocation-root dir})
        proj (projection/build-projection ctx)
        cfg (:config proj)
        corpus (usage/bind-corpus ctx cfg selection)
        gmap (:ok (glossary/load-all-glossaries-strict (:glossaries cfg)))]
    [proj corpus gmap]))

;; -----------------------------------------------------------------------------
;; Tuples
;; -----------------------------------------------------------------------------

(deftest corpus-binds-and-indexes
  (with-temp-project
    (fn [dir]
      (make-project! dir)
      (let [[proj corpus gmap] (bound-corpus dir)
            tuples (usage/index (:plans corpus) (:config proj) gmap)]
        (testing "corpus stats"
          (is (= 1 (:feature-count corpus)))
          (is (= 2 (:scenario-count corpus)))
          (is (= [] (:parse-errors corpus)))
          (is (zero? (:unmatched-step-count corpus)) "every step binds"))
        (testing "tuple shape: subject, type, verb, interface TYPE, file:line"
          (let [click (first (filter #(= :click (:verb %)) tuples))]
            (is (= :alice (:subject click)))
            (is (= :user (:subject-type click)) "instance resolves to type")
            (is (= :web (:interface-type click)))
            (is (str/ends-with? (:file click) "login.feature"))
            (is (pos-int? (:line click)))
            (is (= "Alice logs in" (:scenario click)))
            (is (some? (:pattern-src click)))))
        (testing "intent-use classification"
          (is (= "Login" (:intent-use (first (filter #(= :click (:verb %)) tuples))))
              "Login.email → Login")
          (let [see (first (filter #(= :see (:verb %)) tuples))]
            (is (nil? (:intent-use see)) "quoted literal is never an intent use")))))))

;; -----------------------------------------------------------------------------
;; Aggregates + the deadness join
;; -----------------------------------------------------------------------------

(deftest aggregates-and-used-sets
  (with-temp-project
    (fn [dir]
      (make-project! dir)
      (let [[proj corpus gmap] (bound-corpus dir)
            tuples (usage/index (:plans corpus) (:config proj) gmap)
            alice (usage/usage-for-subject tuples gmap :alice)]
        (testing "instance hits + type rollup"
          (is (= 3 (get-in alice [:instance :hit-count])) "alice appears in 3 steps")
          (is (= :user (:subject-type alice)))
          (is (= 4 (get-in alice [:type :hit-count])) "type rollup includes bob's step"))
        (testing "multi-actor + co-occurrence"
          (is (true? (:multi-actor? alice)))
          (is (= [{:with :bob :scenarios 1}] (:co-occurrence alice))))
        (testing "used-sets: the deadness join"
          (let [used (usage/used-sets tuples)]
            (is (= #{:alice :bob} (:subjects used)))
            (is (= #{:user} (:subject-types used)))
            (is (contains? (:verbs used) [:web :click]))
            (is (contains? (:verbs used) [:web :frobs])
                "FOLD-IN: user-lib stepdef's verb registers as used — stepdef
                 parity between projection and runner load paths")
            (is (= #{"Login"} (:intents used)))))))))

;; -----------------------------------------------------------------------------
;; Selection + parse-error tolerance
;; -----------------------------------------------------------------------------

(deftest selection-semantics
  (with-temp-project
    (fn [dir]
      (make-project! dir)
      (let [[proj corpus gmap] (bound-corpus dir {:tag-filter {:include (tagd/parse-tag-list "auth")}})
            tuples (usage/index (:plans corpus) (:config proj) gmap)]
        (testing "tag selection uses the run's own disposition semantics"
          (is (= 1 (:scenario-count corpus)) "only the @auth scenario selected")
          (is (= #{"Alice logs in"} (set (map :scenario tuples))))
          (is (not (contains? (:verbs (usage/used-sets tuples)) [:web :frobs]))
              "the un-selected scenario's verb is absent from the selection index"))))))

(deftest parse-error-tolerance
  (with-temp-project
    (fn [dir]
      (make-project! dir)
      (write-file! (fs/path dir "sl" "features" "broken.feature")
                   "Feature broken no colon\n  nonsense\n")
      (let [[proj corpus gmap] (bound-corpus dir)]
        (testing "bad file is a caveat, not a failure"
          (is (seq (:parse-errors corpus)))
          (is (= 2 (:scenario-count corpus)) "good file still binds")
          (is (seq (usage/index (:plans corpus) (:config proj) gmap))))))))
