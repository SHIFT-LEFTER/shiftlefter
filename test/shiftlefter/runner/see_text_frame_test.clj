(ns shiftlefter.runner.see-text-frame-test
  "sl-90x5 acceptance (1): a project WITH an intents glossary — which flips
   :unknown-object from :off to :warn (sl-femd) — runs example 04's most
   basic assertion, `:user/alice should see 'Enter Verification Code'`,
   with ZERO SVO warnings under the flipped default and under explicit
   :error (the config-level blocking level). The built-in :see :on-page frame used to declare no
   :object-kind, so the free-text object was validated as an intent ref
   and warned on every use (two warnings on example 04's green run).

   Dry-run through `execute!` — the planning stage is where SVO validation
   fires, and no browser is needed. A control project with a KINDLESS
   custom frame proves the harness still sees the warning, so a silent
   result here means the frame is right, not that the check is blind."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.core :as rc]))

(defn- with-temp-project [f]
  (let [dir (str (fs/canonicalize (fs/create-temp-dir {:prefix "sl-see-text-frame-"})))]
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
                 (rc/execute! (merge {:no-color true :dry-run true} opts)))]
    {:result result :out (str out) :err (str err)}))

(defn- read-forms [out]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. out))]
    (loop [acc []]
      (let [form (edn/read {:eof ::eof} rdr)]
        (if (= ::eof form) acc (recur (conj acc form)))))))

(def ^:private base-config
  ;; Intents present → :unknown-object flips to :warn unless the user sets
  ;; it (the sl-femd conditional default). :sms carries the control verb.
  {:runner {:step-paths ["steps"]}
   :glossaries {:subjects "glossary/subjects.edn"
                :verbs ["glossary/verbs-sms.edn"]
                :intents "glossary/intents"}
   :interfaces {:web {:type :web :adapter :etaoin}
                :sms {:type :sms :adapter :sms-mock}}})

(def ^:private control-steps-src
  "(ns see-text-frame-steps
     (:require [shiftlefter.stepengine.registry :refer [defstep]]))
   (defstep #\":([\\w./-]+) pokes (.+)\"
     {:interface :sms
      :svo {:subject :$1 :verb :poke :frame :default :object :$2}}
     [_s _o] nil)")

(defn- project!
  "The fixture: example 04's assertion verbatim, plus a control feature on
   a custom frame that declares NO :object-kind (the shape :on-page had)."
  [dir enforcement]
  (let [sl (fn [rel] (str (fs/path dir "sl" rel)))
        config (cond-> base-config enforcement (assoc :enforcement enforcement))]
    (spit-file dir "sl/shiftlefter.edn" (pr-str config))
    (spit-file dir "sl/glossary/subjects.edn"
               "{:subjects {:user {:desc \"A user\" :instances [:alice]}}}\n")
    (spit-file dir "sl/glossary/verbs-sms.edn"
               "{:type :sms\n :verbs {:poke {:desc \"Poke\"\n                :frames {:default {:args [] :pattern \"S pokes O\"}}}}}\n")
    (spit-file dir "sl/glossary/intents/login.edn"
               "{:intent \"Login\"\n :elements {:email {:bindings {:web {:id \"email\"}}}}}\n")
    (spit-file dir "sl/steps/see_text_frame_steps.clj" control-steps-src)
    (spit-file dir "sl/features/see_text.feature"
               (str "Feature: Example 04's assertion\n"
                    "  Scenario: the most basic built-in assertion\n"
                    "    Then :user/alice should see 'Enter Verification Code'\n"
                    "    And :user/alice should see 'Code verified for alice'\n"))
    (spit-file dir "sl/features/control.feature"
               (str "Feature: Control\n"
                    "  Scenario: a kindless custom frame still warns\n"
                    "    Given :user/alice pokes the thing\n"))
    {:config (sl "shiftlefter.edn")
     :see-text (sl "features/see_text.feature")
     :control (sl "features/control.feature")}))

(defn- unknown-object-issues
  "Every :svo/unknown-object issue the EDN summary carries, from either
   home: warn-level under :diagnostics, error-level under :planning."
  [summary]
  (->> (concat (-> summary :diagnostics :svo-issues)
               (-> summary :planning :svo-issues))
       (filter #(= :svo/unknown-object (:type %)))
       vec))

(defn- run-feature [fx feature]
  (let [opts {:paths [(get fx feature)] :config-path (:config fx)}
        console (run-captured opts)
        edn-run (run-captured (assoc opts :edn true))]
    {:exit (-> console :result :exit-code)
     :err (:err console)
     :issues (unknown-object-issues (last (read-forms (:out edn-run))))}))

(deftest should-see-text-is-silent-in-a-project-with-intents
  (testing "flipped default (:unknown-object :warn via the intents glossary)"
    (with-temp-project
      (fn [dir]
        (let [fx (project! dir nil)
              {:keys [exit err issues]} (run-feature fx :see-text)]
          (is (= 0 exit) err)
          (is (= [] issues))
          (is (not (str/includes? err "Unknown object")) err)
          (is (not (str/includes? err "SVO validation warnings")) err)
          (testing "control: a kindless frame on the same project still warns"
            (let [{:keys [issues err]} (run-feature fx :control)]
              (is (= 1 (count issues)))
              (is (= "the thing" (:object (first issues))))
              (is (str/includes? err "Unknown object 'the thing'"))))))))
  (testing "explicit :error (the config-level blocking level; :strict is the validator-level alias, exercised by grammar-table-test)"
    (with-temp-project
      (fn [dir]
        (let [fx (project! dir {:unknown-object :error})
              {:keys [exit err issues]} (run-feature fx :see-text)]
          (is (= 0 exit) err)
          (is (= [] issues))
          (is (not (str/includes? err "Unknown object")) err)
          (testing "control: :error makes the kindless frame a planning error"
            (let [{:keys [exit issues]} (run-feature fx :control)]
              (is (= 2 exit))
              (is (= 1 (count issues))))))))))
