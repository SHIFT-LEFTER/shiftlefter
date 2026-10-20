(ns shiftlefter.runner.lint-first-pass-test
  "sl-8ezu (Warden W1, v0.5.7 batch audit) — the data-plane lint result is
   never hidden behind another planning failure, and a scenario held for a
   binding error says so:

   - a suite with an SVO :error AND a shape error exits 2 with BOTH issues
     on the console and in --edn :planning (the shape error with its
     def-site), and the mixed-state hint names binding-error steps;
   - --hold-unplannable carries the cause onto the held entry: EDN
     :binding-errors [{:type :def-site}], the console held line and the
     JUnit <skipped> message name the error type; an undefined-only hold
     renders exactly as before (no :binding-errors key).

   Fixture = the Warden's a1 probe shape, driven through `runner/execute!`
   on a real temp project: an :sms-mock interface (executes in-process), a
   declared :web interface (never provisioned at planning), a `Reset`
   intent with one shaped and one unshaped element, a `Login` intent."
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
;; Fixture project
;; -----------------------------------------------------------------------------

(def ^:private config
  {:interfaces {:sms {:type :sms :adapter :sms-mock}
                :web {:type :web :adapter :etaoin}}})

(def ^:private reset-intent
  ;; :plain is line 4 — the def-site every pin below names.
  "{:intent \"Reset\"\n :elements {:smsCode {:bindings {:sms {}}\n                      :shape \"verification code is: (\\\\d{6})\"}\n            :plain {:bindings {:sms {}}}}}\n")

(def ^:private login-intent
  "{:intent \"Login\"\n :elements {:email  {:bindings {:web {:id \"email\"}}}\n            :submit {:bindings {:web {:css \"button\"}}}}}\n")

(def ^:private features
  {"shape.feature"
   "Feature: Shape\n  Scenario: no shape\n    When :user/alice receives an SMS to '+15550001111' matching Reset.plain\n"
   "svo.feature"
   "Feature: Svo\n  Scenario: unknown object\n    When :user/alice clicks Login.submti\n"
   "clean.feature"
   "Feature: Clean\n  Scenario: inbox empty\n    Then :user/alice should see 0 messages to '+15550002222'\n"
   "unshifted.feature"
   "Feature: Unshifted\n  Scenario: cucumber\n    Given I am on the login page\n    And I press the button\n"})

(defn- with-project
  "Write the sl/-layout temp project (canonical path: def-sites carry
   resolved paths) and call `f` with {:dir :config-path :reset :feature}."
  [f]
  (let [dir (str (fs/canonicalize (fs/create-temp-dir {:prefix "sl-lint-first-pass-"})))
        sl (fn [& parts] (str (apply fs/path dir "sl" parts)))]
    (try
      (fs/create-dirs (sl "features"))
      (fs/create-dirs (sl "glossary" "intents"))
      (spit (sl "shiftlefter.edn") (pr-str config))
      (spit (sl "glossary" "subjects.edn")
            "{:subjects {:user {:desc \"A user\" :instances [:alice]}}}\n")
      (spit (sl "glossary" "intents" "reset.edn") reset-intent)
      (spit (sl "glossary" "intents" "login.edn") login-intent)
      (doseq [[name text] features]
        (spit (sl "features" name) text))
      (f {:dir dir
          :config-path (sl "shiftlefter.edn")
          :reset (sl "glossary" "intents" "reset.edn")
          :feature (fn [name] (sl "features" name))})
      (finally
        (fs/delete-tree dir)))))

(defn- execute
  "execute! over the named features, capturing stdout/stderr."
  [{:keys [config-path feature]} names opts]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        result (binding [*out* out *err* err]
                 (runner/execute! (merge {:paths (mapv feature names)
                                          :config-path config-path
                                          :no-color true}
                                         opts)))]
    {:result result
     :summary (when (:edn opts) (edn/read-string (str out)))
     :err (str err)}))

(defn- by-scenario [held name]
  (first (filter #(= name (:scenario %)) held)))

;; -----------------------------------------------------------------------------
;; The first planning exit carries the lint result
;; -----------------------------------------------------------------------------

(deftest svo-error-does-not-hide-the-shape-error
  (with-project
    (fn [{:keys [reset] :as p}]
      (let [names ["shape.feature" "svo.feature" "clean.feature"]]
        (testing "--edn: exit 2, both issue classes in :planning, the shape error's def-site"
          (let [{:keys [result summary]} (execute p names {:dry-run true :edn true})
                issues (get-in summary [:planning :svo-issues])
                shape (first (filter #(= :bindings/element-has-no-shape (:type %)) issues))]
            (is (= 2 (:exit-code result)))
            (is (= :planning-failed (:run/status summary)))
            (is (= #{:svo/unknown-object :bindings/element-has-no-shape}
                   (set (map :type issues))))
            (is (= 2 (count issues)))
            (is (= :error (:severity shape)))
            (is (= {:file reset :line 4} (:def-site shape)))
            (is (= 2 (get-in summary [:planning :counts :svo-issue-count])))
            (is (= 2 (get-in summary [:planning :counts :total-issues])))
            ;; The unknown-object scenario counts as PLANNABLE: enforcement
            ;; issues are the vocabulary-contract class, deliberately outside
            ;; classify-plans (sl-6wh9) — only the shape error holds.
            (is (= {:plannable 2 :unplannable 1 :total 3}
                   (get-in summary [:planning :plannability])))))
        (testing "console: both issues print, the hint names binding-error steps"
          (let [{:keys [result err]} (execute p names {:dry-run true})]
            (is (= 2 (:exit-code result)))
            (is (str/includes? err "Unknown object 'Login.submti'"))
            (is (str/includes? err "Reset.plain declares no :shape"))
            (is (str/includes? err (str "Declared at: " reset ":4")))
            (is (str/includes? err "2 of 3 scenario(s) plan; 1 are held by undefined, ambiguous or binding-error steps."))
            (is (str/includes? err "To run the 2 now and list the 1, pass --hold-unplannable."))))))))

;; -----------------------------------------------------------------------------
;; --hold-unplannable names the cause
;; -----------------------------------------------------------------------------

(deftest held-entry-names-the-binding-error
  (with-project
    (fn [{:keys [dir reset] :as p}]
      (let [names ["shape.feature" "clean.feature" "unshifted.feature"]]
        (testing "dry-run --edn: the held record carries :binding-errors with the def-site; the undefined hold carries none"
          (let [{:keys [result summary]} (execute p names {:dry-run true :edn true
                                                           :hold-unplannable true})
                held (:held summary)
                shape (by-scenario held "no shape")
                cucumber (by-scenario held "cucumber")]
            (is (= 0 (:exit-code result)))
            (is (= :dry-run (:run/status summary)))
            (is (= {:scenarios 1 :steps 1 :held 2} (:counts summary)))
            (is (= :unplannable (:reason shape)))
            (is (= 0 (:undefined shape)))
            (is (= 0 (:ambiguous shape)))
            (is (= [{:type :bindings/element-has-no-shape
                     :def-site {:file reset :line 4}}]
                   (:binding-errors shape)))
            (is (= 2 (:undefined cucumber)))
            (is (not (contains? cucumber :binding-errors)))
            (is (empty? (filter #(= :bindings/element-has-no-shape (:type %))
                                (get-in summary [:diagnostics :svo-issues])))
                "the held scenario's error rides its entry, not the kept run's :svo-issues")))
        (testing "dry-run console: the held line names the error type; the undefined line is unchanged"
          (let [{:keys [result err]} (execute p names {:dry-run true :hold-unplannable true})]
            (is (= 0 (:exit-code result)))
            (is (str/includes? err "2 scenario(s) held:"))
            (is (str/includes? err "; not plannable (0 undefined, 0 ambiguous, 1 binding error(s): element-has-no-shape))"))
            (is (str/includes? err "; not plannable (2 undefined, 0 ambiguous))"))))
        (testing "live run: exit 0, JUnit <skipped> names the error, the verdict counts the holds"
          (let [junit (str (fs/path dir "results.xml"))
                {:keys [result summary]} (execute p names {:edn true :hold-unplannable true
                                                           :junit-xml junit})]
            (is (= 0 (:exit-code result)))
            (is (= :passed (:run/status summary)))
            (is (= 1 (get-in summary [:counts :passed])))
            (is (= 2 (get-in summary [:counts :held])))
            (is (= [{:type :bindings/element-has-no-shape :def-site {:file reset :line 4}}]
                   (:binding-errors (by-scenario (:held summary) "no shape"))))
            (let [xml (slurp junit)]
              (is (str/includes? xml "<skipped message=\"held: not plannable (0 undefined, 0 ambiguous step(s), 1 binding error(s): element-has-no-shape)\"/>"))
              (is (str/includes? xml "<skipped message=\"held: not plannable (2 undefined, 0 ambiguous step(s))\"/>"))
              (is (str/includes? xml "tests=\"3\""))
              (is (str/includes? xml "skipped=\"2\""))))
          (let [{:keys [result err]} (execute p names {:hold-unplannable true})]
            (is (= 0 (:exit-code result)))
            (is (str/includes? err "3 scenario(s): 1 passed, 2 held"))))))))
