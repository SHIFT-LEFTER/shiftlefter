(ns shiftlefter.runner.did-you-mean-test
  "sl-u2r0 end to end: ONE fixture project with one misspelling per class,
   each class run through `execute!` — the suggestion AND the def-site land
   on the same surface (console and --edn) with the ruled exit code:

   | class                     | exit | level                          |
   |---------------------------|------|--------------------------------|
   | verb in step text (F5)    | 2    | undefined step + :verb-suggestion |
   | actor (F6)                | 0    | :unknown-subject warning + def-site |
   | intent ref (routed 1/S32) | 2    | :svo/unknown-object error + def-site |
   | capture (S30)             | 2    | consumed-without-producer + producer site |
   | config key (F7)           | 0    | :config/misplaced-key warning + config file |

   The warning classes EXECUTE (an :sms interface on the mock SMS adapter;
   the poke step touches nothing) — exit 0 is a real run's verdict, not a
   dry run's. The exit-2 classes stop at planning. The green twin runs
   clean: no :suggestion, no :def-site, no warning anywhere, so a correct
   project's output is unchanged."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.core :as rc]))

(defn- with-temp-project [f]
  ;; Canonical: the runner resolves the config root through symlinks
  ;; (macOS's /var → /private/var), and def-sites carry resolved paths.
  (let [dir (str (fs/canonicalize (fs/create-temp-dir {:prefix "sl-did-you-mean-run-"})))]
    (try (f dir) (finally (fs/delete-tree dir)))))

(defn- spit-file [dir relative content]
  (let [target (fs/path dir relative)]
    (fs/create-dirs (fs/parent target))
    (spit (str target) content)
    (str target)))

(defn- run-captured [opts]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        result (binding [*out* out *err* err]
                 (rc/execute! (merge {:no-color true} opts)))]
    {:result result :out (str out) :err (str err)}))

(defn- read-forms [out]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. out))]
    (loop [acc []]
      (let [form (edn/read {:eof ::eof} rdr)]
        (if (= ::eof form) acc (recur (conj acc form)))))))

(def ^:private config
  {:runner {:step-paths ["steps"]}
   :glossaries {:subjects "glossary/subjects.edn"
                :verbs ["glossary/verbs-sms.edn"]
                :intents "glossary/intents"}
   :interfaces {:sms {:type :sms :adapter :sms-mock}
                :web {:type :web :adapter :etaoin}}})

(def ^:private steps-src
  "(ns dym-steps
     (:require [shiftlefter.stepengine.registry :refer [defstep]]))
   ;; line 3 — the poke stepdef (its def-site for the verb suggestion)
   (defstep #\":([\\w./-]+) pokes (.+)\"
     {:interface :sms
      :svo {:subject :$1 :verb :poke :frame :default :object :$2}}
     [_s _o] nil)")

(defn- project!
  "Write the fixture; returns {:dir :config :config-misplaced :features {...}}."
  [dir]
  (let [sl (fn [rel] (str (fs/path dir "sl" rel)))]
    (spit-file dir "sl/shiftlefter.edn" (pr-str config))
    (spit-file dir "sl/shiftlefter-misplaced.edn"
               (pr-str (assoc config :junit-xml "reports/junit.xml")))
    (spit-file dir "sl/glossary/subjects.edn"
               ";; the actors\n{:subjects\n {:user {:desc \"A user\"\n         :instances [:alice]}}}\n")
    (spit-file dir "sl/glossary/verbs-sms.edn"
               "{:type :sms\n :verbs {:poke {:desc \"Poke\"\n                :frames {:default {:args [] :pattern \"S pokes O\" :object-kind :text}}}}}\n")
    (spit-file dir "sl/glossary/intents/login.edn"
               ";; the login surface\n{:intent \"Login\"\n :elements {:email {:bindings {:web {:id \"email\"}}}\n            :submit {:bindings {:web {:css \"button\"}}}}}\n")
    (spit-file dir "sl/hooks.clj"
               "(ns hooks)\n(def hooks [{:name \"seed\" :global? true :provides [:code]\n             :before (fn [_] {:code \"1234\"})}])\n")
    (spit-file dir "sl/steps/dym.clj" steps-src)
    (spit-file dir "sl/features/green.feature"
               "Feature: Green\n  Scenario: fine\n    Given :user/alice pokes the thing\n")
    (spit-file dir "sl/features/actor.feature"
               "Feature: Actor\n  Scenario: typo\n    Given :user/alcie pokes the thing\n")
    (spit-file dir "sl/features/verb.feature"
               "Feature: Verb\n  Scenario: typo\n    Given :user/alice pkoes the thing\n")
    (spit-file dir "sl/features/intent.feature"
               "Feature: Intent\n  Scenario: typo\n    Given :user/alice clicks Login.submti\n")
    (spit-file dir "sl/features/capture.feature"
               "Feature: Capture\n  Scenario: typo\n    Given :user/alice fills Login.email with {coed}\n")
    {:dir dir
     :config (sl "shiftlefter.edn")
     :config-misplaced (sl "shiftlefter-misplaced.edn")
     :subjects (sl "glossary/subjects.edn")
     :login (sl "glossary/intents/login.edn")
     :hooks (sl "hooks.clj")
     :steps (sl "steps/dym.clj")
     :features {:green (sl "features/green.feature")
                :actor (sl "features/actor.feature")
                :verb (sl "features/verb.feature")
                :intent (sl "features/intent.feature")
                :capture (sl "features/capture.feature")}}))

(defn- run-class
  "Run one class both ways: console and --edn. Returns
   {:exit :err :summary} (the last EDN form)."
  [{:keys [config features]} class & {:keys [config-path]}]
  (let [opts {:paths [(get features class)]
              :config-path (or config-path config)}
        console (run-captured opts)
        edn-run (run-captured (assoc opts :edn true))]
    {:exit (-> console :result :exit-code)
     :edn-exit (-> edn-run :result :exit-code)
     :err (:err console)
     :summary (last (read-forms (:out edn-run)))}))

(deftest one-misspelling-per-class-carries-suggestion-and-def-site
  (with-temp-project
    (fn [dir]
      (let [fx (project! dir)]
        (testing "F5 — verb in step text: exit 2, the did-you-mean on the undefined step, the stepdef as def-site"
          (let [{:keys [exit edn-exit err summary]} (run-class fx :verb)
                issue (-> summary :planning :issues first)]
            (is (= 2 exit)) (is (= 2 edn-exit))
            (is (= "pokes" (-> issue :verb-suggestion :suggestion)))
            (is (= {:file (:steps fx) :line 4} (-> issue :verb-suggestion :def-site)))
            (is (str/includes? err (str "did you mean \"pokes\"? — :([\\w./-]+) pokes (.+) (" (:steps fx) ":4)")))))
        (testing "F6 — actor: exit 0 with the warning, the suggestion and the subjects file:line"
          (let [{:keys [exit edn-exit err summary]} (run-class fx :actor)
                issue (->> summary :diagnostics :svo-issues
                           (filter #(= :svo/unknown-subject (:type %))) first)]
            (is (= 0 exit)) (is (= 0 edn-exit))
            (is (= :warn (:severity issue)))
            (is (= :user/alice (:suggestion issue)))
            ;; :user/alice is a bare instance keyword: its site is the
            ;; :instances vector's line (4), the type's entry is line 3.
            (is (= {:file (:subjects fx) :line 4 :entry :user/alice} (:def-site issue)))
            (is (str/includes? err "WARNING: Unknown subject :user/alcie"))
            (is (str/includes? err "Did you mean: :user/alice?"))
            (is (str/includes? err (str "Declared in: " (:subjects fx) ":4")))))
        (testing "routed item 1 — intent ref: exit 2, the element suggestion and the intent file:line"
          (let [{:keys [exit edn-exit err summary]} (run-class fx :intent)
                issue (->> summary :planning :svo-issues
                           (filter #(= :svo/unknown-object (:type %))) first)]
            (is (= 2 exit)) (is (= 2 edn-exit))
            (is (= :error (:severity issue)))
            (is (= "Login.submit" (:suggestion issue)))
            (is (= {:file (:login fx) :line 4 :entry "Login.submit"} (:def-site issue)))
            (is (str/includes? err "ERROR: Unknown object 'Login.submti'"))
            (is (str/includes? err "Did you mean: Login.submit?"))
            (is (str/includes? err (str "Declared in: " (:login fx) ":4")))))
        (testing "S30 — capture: exit 2, the nearest producer and where it is produced"
          (let [{:keys [exit edn-exit err summary]} (run-class fx :capture)
                issue (->> summary :planning :svo-issues
                           (filter #(= :bindings/consumed-without-producer (:type %))) first)]
            (is (= 2 exit)) (is (= 2 edn-exit))
            (is (= :code (:suggestion issue)))
            (is (= {:file (:hooks fx) :entry :code} (:def-site issue)))
            (is (str/includes? err "Did you mean: {code}?"))
            (is (str/includes? err (str "Produced at: " (:hooks fx))))))
        (testing "F7 — config key: exit 0 with the warning, the suggested path and the config file"
          (let [{:keys [exit edn-exit err summary]} (run-class fx :green :config-path (:config-misplaced fx))
                lint (->> summary :diagnostics :config-lints
                          (filter #(= :config/misplaced-key (:type %))) first)]
            (is (= 0 exit) err) (is (= 0 edn-exit) (pr-str summary))
            (is (= [:runner :report :junit-xml] (:suggested-path lint)))
            (is (= {:file (:config-misplaced fx)} (:def-site lint)))
            (is (str/includes? err "did you mean [:runner :report :junit-xml]?"))
            (is (str/includes? err (str "[" (:config-misplaced fx) "]")))))))))

(deftest the-green-twin-is-untouched
  (with-temp-project
    (fn [dir]
      (let [fx (project! dir)
            {:keys [exit edn-exit err summary]} (run-class fx :green)
            text (pr-str summary)]
        (is (= 0 exit)) (is (= 0 edn-exit))
        (is (not (str/includes? err "WARNING")) "no warning on a correct project")
        (is (not (str/includes? err "Did you mean")))
        (is (not (str/includes? err "Declared in")))
        (is (not (str/includes? text ":suggestion")))
        (is (not (str/includes? text ":def-site")))
        (is (nil? (:diagnostics summary)) "no diagnostics block at all")))))
