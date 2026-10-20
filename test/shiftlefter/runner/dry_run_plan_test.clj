(ns shiftlefter.runner.dry-run-plan-test
  "sl-xvae: the dry-run step plan — one per-step record computed at bind time
   (bind/resolved-record), rendered as :step/resolved inside the dry-run EDN
   summary's :plan and as the console `plan:` block under -v.

   Fixture: test/fixtures/plan-project (typed :web steps, one untyped setup
   step, subjects + intents glossaries, one macro that expands to three
   steps). The goldens in report_golden_test pin the exact bytes; this
   namespace pins the SHAPE and the rules (coverage, absence, provenance,
   the vocabulary join, byte-identity without -v)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.project-projection :as projection]
            [shiftlefter.runner.core :as rc]
            [shiftlefter.stepengine.bind :as bind]))

;; -----------------------------------------------------------------------------
;; bind/resolved-record — the record itself
;; -----------------------------------------------------------------------------

(def ^:private typed-binding
  {:stepdef/id "sd-1"
   :pattern-src "^(\\w+) fills the (\\w+) field$"
   :source {:ns 'fixtures.plan-steps :file "steps/plan_steps.clj" :line 11}
   :captures ["Alice" "username"]
   :metadata {:interface :web :svo {:subject :$1 :verb :fill :frame :with :object :$2}}
   :svo {:subject :alice :verb :fill :frame :with :object "username" :interface :web}
   :arity-ok? true})

(deftest resolved-record-typed-step
  (testing "a typed bound step resolves to the stepdef half AND the triple, with both interface keys"
    (is (= {:pattern-src "^(\\w+) fills the (\\w+) field$"
            :stepdef-source {:ns 'fixtures.plan-steps :file "steps/plan_steps.clj" :line 11}
            :subject :alice :verb :fill :frame :with :object "username"
            :interface :web :interface-type :web}
           (bind/resolved-record {:status :matched :step {} :binding typed-binding})))))

(deftest resolved-record-instance-vs-type
  (testing ":interface is the resolved execution INSTANCE, :interface-type the stepdef's type"
    (let [r (bind/resolved-record {:status :matched :step {}
                                   :binding (assoc-in typed-binding [:svo :interface] :admin-console)})]
      (is (= :admin-console (:interface r)))
      (is (= :web (:interface-type r))))))

(deftest resolved-record-untyped-step
  (testing "an untyped stepdef yields the stepdef half only — SVO keys absent, never nil"
    (let [r (bind/resolved-record {:status :matched :step {}
                                   :binding (-> typed-binding (assoc :svo nil) (dissoc :metadata))})]
      (is (= #{:pattern-src :stepdef-source} (set (keys r))))
      (is (not (contains? r :verb))))))

(deftest resolved-record-absent-when-not-matched
  (testing "wrappers and unbound steps carry no record"
    (is (nil? (bind/resolved-record {:status :synthetic :step {:step/synthetic? true} :binding nil})))
    (is (nil? (bind/resolved-record {:status :undefined :step {} :binding nil})))
    (is (nil? (bind/resolved-record {:status :ambiguous :step {} :binding nil :alternatives []})))))

;; -----------------------------------------------------------------------------
;; The fixture project through the runner
;; -----------------------------------------------------------------------------

(def ^:private fixture-root "test/fixtures/plan-project")

(def ^:private base-opts
  {:paths [(str fixture-root "/sl/features/plan.feature")]
   :config-path (str fixture-root "/sl/shiftlefter.edn")
   :step-paths [(str fixture-root "/sl/steps/")]
   :dry-run true :no-color true :seed 42})

(defn- run-captured [opts]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        result (binding [*out* out *err* err]
                 (rc/execute! (merge base-opts opts)))]
    {:result result :out (str out) :err (str err)}))

(defn- dry-run-summary []
  (let [{:keys [result out]} (run-captured {:edn true})]
    (is (= 0 (:exit-code result)))
    (edn/read-string out)))

(deftest edn-plan-shape-and-coverage
  (let [summary (dry-run-summary)
        plan (:plan summary)
        scenario (first plan)
        steps (:steps scenario)
        by-text (into {} (map (juxt :step/text identity)) steps)]
    (testing ":plan is always present on a dry-run summary, in plan order, one entry per scenario"
      (is (vector? plan))
      (is (= 1 (count plan)))
      (is (= "Login through the macro" (:scenario/name scenario)))
      (is (str/ends-with? (:uri scenario) "plan-project/sl/features/plan.feature"))
      (is (= 6 (:line scenario)))
      (is (= 6 (count steps)) "background step + wrapper + 3 expanded + 1 plain"))
    (testing "every bound step carries :step/resolved; the untyped one carries the stepdef half only"
      (let [setup (get by-text "the login page is loaded")]
        (is (= "^the login page is loaded$" (get-in setup [:step/resolved :pattern-src])))
        (is (= 'steps.plan-steps (get-in setup [:step/resolved :stepdef-source :ns])))
        (is (= 7 (get-in setup [:step/resolved :stepdef-source :line])))
        (is (not (contains? (:step/resolved setup) :verb)))
        (is (not (contains? setup :step/macro)))))
    (testing "the typed plain step resolves the full triple"
      (is (= {:subject :alice :verb :see :frame :visible :object "dashboard"
              :interface :web :interface-type :web}
             (-> (get by-text "Alice sees the dashboard") :step/resolved
                 (select-keys [:subject :verb :frame :object :interface :interface-type])))))
    (testing "the macro wrapper carries provenance and NO record"
      (let [wrapper (get by-text "Alice logs in +")]
        (is (not (contains? wrapper :step/resolved)))
        (is (= {:key "Alice logs in" :role :call :step-count 3}
               (select-keys (:step/macro wrapper) [:key :role :step-count])))
        (is (= 7 (get-in wrapper [:step/macro :call-site :line])))
        (is (str/ends-with? (get-in wrapper [:step/macro :call-site :file]) "plan.feature"))
        ;; sl-mk0t (Warden W5): the wrapper names its macro's definition
        ;; file — the same fact console -v prints beside the wrapper line.
        (is (str/ends-with? (get-in wrapper [:step/macro :definition :file]) "plan.ini")
            "the wrapper carries :definition {:file}, matching console -v")))
    (testing "each expanded step carries its own record AND the call-site beside it (J-c)"
      (let [expanded (filter #(= :expanded (get-in % [:step/macro :role])) steps)]
        (is (= 3 (count expanded)))
        (is (= [0 1 2] (mapv #(get-in % [:step/macro :index]) expanded)))
        (is (every? #(= 7 (get-in % [:step/macro :call-site :line])) expanded))
        (is (every? #(str/ends-with? (get-in % [:step/macro :definition-step :file]) "plan.ini") expanded))
        (is (= [:fill :fill :click] (mapv #(get-in % [:step/resolved :verb]) expanded)))
        (is (= ["username" "password" "login button"]
               (mapv #(get-in % [:step/resolved :object]) expanded)))
        (is (every? #(= :web (get-in % [:step/resolved :interface-type])) expanded))))))

(deftest edn-plan-joins-explain-vocabulary-without-a-second-call
  (testing "every typed :step/resolved finds its verb in the project's vocabulary by [:interface-type :verb]"
    (let [ctx (project-context/resolve {:invocation-root fixture-root})
          proj (projection/build-projection ctx)
          vocabulary (set (for [{:keys [interface-type verbs]} (:verbs proj)
                                v verbs]
                            [interface-type (:verb v)]))
          typed (for [scenario (:plan (dry-run-summary))
                      step (:steps scenario)
                      :let [r (:step/resolved step)]
                      :when (:verb r)]
                  [(:interface-type r) (:verb r)])]
      (is (= 4 (count typed)))
      (is (seq vocabulary))
      (doseq [k typed]
        (is (contains? vocabulary k) (str "no vocabulary entry for " (pr-str k)))))))

(deftest console-listing-under-verbose-only
  (testing "-v prints the plan: block, per scenario, each step → triple + def site; expanded steps indented (sl-jima)"
    (let [{:keys [err]} (run-captured {:verbose true})]
      (is (str/includes? err "plan:\n  Login through the macro\n"))
      (is (str/includes? err "    the login page is loaded  →  (untyped)  ("))
      (is (str/includes? err "plan-project/sl/steps/plan_steps.clj:7)\n"))
      (is (str/includes? err "    Alice logs in +  →  macro \"Alice logs in\" ("))
      (is (str/includes? err "), 3 step(s)"))
      (is (str/includes? err "      Alice clicks the login button  →  :alice :click [:default] \"login button\" via :web  ("))
      (is (str/includes? err "plan-project/sl/steps/plan_steps.clj:21)\n"))
      (is (str/includes? err "    Alice sees the dashboard  →  :alice :see [:visible] \"dashboard\" via :web  ("))
      ;; The SVO warning block above the plan quotes the untyped stepdef's
      ;; pattern, so the absence check reads the plan: block alone.
      (let [plan-block (subs err (str/index-of err "plan:"))]
        (is (not (str/includes? plan-block "^the login page is loaded$")) "the pattern source is -vv's, not -v's")
        (is (not (str/includes? plan-block "clicks the (.+)$"))))
      (is (not (str/includes? err "pattern:")))))
  (testing "-v as a count of 1 renders exactly what a bare true renders (programmatic callers)"
    (is (= (:err (run-captured {:verbose true}))
           (:err (run-captured {:verbose 1})))))
  (testing "-vv keeps every -v line and adds one `pattern:` sub-line under each bound step, two deeper"
    (let [v1 (:err (run-captured {:verbose 1}))
          v2 (:err (run-captured {:verbose 2}))
          strip-patterns (fn [s] (->> (str/split-lines s)
                                      (remove #(str/includes? % "pattern: "))
                                      (str/join "\n")))]
      (is (= (str/trim-newline v1) (strip-patterns v2)))
      (is (str/includes? v2 "  →  (untyped)  (") "the -v line is unchanged under -vv")
      (is (str/includes? v2 "plan_steps.clj:7)\n      pattern: ^the login page is loaded$\n"))
      (is (str/includes? v2 "plan_steps.clj:21)\n        pattern: ^(\\w+) clicks the (.+)$\n"))
      (is (= 5 (count (re-seq #"pattern: " v2))) "one per bound step: background + 3 expanded + 1 plain")
      (is (str/includes? v2 "3 step(s)\n      Alice fills") "the macro wrapper gains no pattern line")))
  (testing "without -v the console dry-run output carries no listing (byte-identity is the goldens' job)"
    (let [{:keys [err]} (run-captured {})]
      (is (not (str/includes? err "plan:")))
      (is (not (str/includes? err "→")))
      (is (str/includes? err "1 scenario(s) bound successfully (dry run)")))))
