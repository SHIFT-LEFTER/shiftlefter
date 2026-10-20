(ns shiftlefter.svo.did-you-mean-test
  "sl-u2r0: did-you-mean and def-site travel together on every path.

   (a) the line index (shiftlefter.edn-lines): lines on nested maps and
       vectors; a mismatching or unreadable pass degrades to nil.
   (b) glossary + intents loaders record :def-sites (file:line per entry;
       instances under their qualified form; builtin verbs as resources).
   (c) the validator: subject / verb / interface issues carry :def-site
       (the suggestion's entry, else the section file); intent refs —
       unknown intent, unknown element, no binding for the interface — are
       errors on their own severity with suggestion + def-site; the C2
       heuristic both ways (ref-shaped => error, free text => the knob's
       level with the :object-kind :text advice); raw locators on the
       :raw-locator kind with the locator-index did-you-mean.
   (d) the bindings lint names the suggested producer's site.
   (e) the undefined-step verb suggestion (Fable 5): rematch-gated, never
       from quoted spans or annotations (C3), def-site = the stepdef.
   (f) the renderers: console prints the def-site and did-you-mean lines;
       EDN passes :verb-suggestion and :def-site through."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.edn-lines :as edn-lines]
            [shiftlefter.intent.loader :as loader]
            [shiftlefter.intent.state :as intent-state]
            [shiftlefter.runner.report.console :as console]
            [shiftlefter.runner.report.edn :as report-edn]
            [shiftlefter.stepengine.bind :as bind]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.svo.bindings-lint :as lint]
            [shiftlefter.svo.glossary :as glossary]
            [shiftlefter.svo.validate :as validate]))

(defmacro ^:private with-err-str [& body]
  `(let [sw# (java.io.StringWriter.)]
     (binding [*err* sw#] ~@body)
     (str sw#)))

(defn- with-temp-dir [f]
  (let [dir (fs/create-temp-dir {:prefix "sl-did-you-mean-"})]
    (try (f (str dir)) (finally (fs/delete-tree dir)))))

;; -----------------------------------------------------------------------------
;; (a) the line index
;; -----------------------------------------------------------------------------

(deftest line-index-positions-nested-collections
  (let [content "{:subjects\n {:user {:desc \"x\"\n         :instances [:alice :bob]}\n  :admin {:desc \"y\"}}}"
        data (edn/read-string content)
        indexed (edn-lines/index content data)]
    (is (some? indexed) "the pass vouched: same value as the EDN read")
    (is (= 1 (edn-lines/line-of indexed [])))
    (is (= {:user 2 :admin 4} (edn-lines/entry-lines indexed [:subjects])))
    (is (= 3 (edn-lines/line-of indexed [:subjects :user :instances])))
    (is (= {} (edn-lines/entry-lines indexed [:nope])) "an absent map is empty")))

(deftest line-index-degrades-to-nil-when-it-cannot-vouch
  (is (nil? (edn-lines/index "{:a" {:a 1})) "unreadable text: nil, no throw")
  (is (nil? (edn-lines/index "{:a 1}" {:a 2})) "a value that differs from the EDN read: nil")
  (is (nil? (edn-lines/line-of nil [:a])) "no index: no line")
  (is (= {} (edn-lines/entry-lines nil [:a]))))

;; -----------------------------------------------------------------------------
;; (b) loaders
;; -----------------------------------------------------------------------------

(def ^:private subjects-src
  ";; comment line 1\n{:subjects\n {:user {:desc \"The user\"\n         :instances [:alice\n                     {:id :bob :desc \"Bob\"}]}\n  :admin {:desc \"Admin\"}}}\n")

(def ^:private verbs-src
  "{:type :web\n :verbs {:smash {:desc \"Smash\" :frames {:default {:args [] :pattern \"S smashes O\"}}}\n         :poke {:desc \"Poke\" :frames {:default {:args [] :pattern \"S pokes O\"}}}}}\n")

(deftest glossary-loader-records-def-sites
  (with-temp-dir
    (fn [dir]
      (let [subjects (str (fs/path dir "subjects.edn"))
            verbs (str (fs/path dir "verbs-web.edn"))]
        (spit subjects subjects-src)
        (spit verbs verbs-src)
        (doseq [[label gl] [["lenient" (glossary/load-all-glossaries {:subjects subjects :verbs [verbs]})]
                            ["strict" (:ok (glossary/load-all-glossaries-strict {:subjects subjects :verbs [verbs]}))]]]
          (testing (str label ": subjects — types, a bare instance (its vector's line), a persona map (its own line)")
            (let [s (get-in gl [:def-sites :subjects])]
              (is (= {:file subjects :line 3} (:user s)))
              (is (= {:file subjects :line 4} (:user/alice s)))
              (is (= {:file subjects :line 5} (:user/bob s)))
              (is (= {:file subjects :line 6} (:admin s)))))
          (testing (str label ": project verbs by line; builtin verbs named as resources")
            (is (= {:file verbs :line 2} (get-in gl [:def-sites :verbs :web :smash])))
            (is (= {:file verbs :line 3} (get-in gl [:def-sites :verbs :web :poke])))
            (let [click (get-in gl [:def-sites :verbs :web :click])]
              (is (= "shiftlefter/glossaries/verbs-web.edn (builtin resource)" (:file click)))
              (is (pos-int? (:line click))))))))))

(deftest intents-loader-records-def-sites
  (with-temp-dir
    (fn [dir]
      (spit (str (fs/path dir "login.edn"))
            ";; the login surface\n{:intent \"Login\"\n :elements {:email {:bindings {:web {:id \"email\"}}}\n            :submit {:bindings {:web {:css \"button\"}}}}}\n")
      (let [{:keys [ok]} (loader/load-all-intents dir)
            site (get-in ok [:def-sites "Login"])]
        (is (= (str (fs/path dir "login.edn")) (:file site)))
        (is (= 2 (:line site)))
        (is (= {"email" 3 "submit" 4} (:elements site)))))))

;; -----------------------------------------------------------------------------
;; (c) the validator
;; -----------------------------------------------------------------------------

(def ^:private glossary-with-sites
  {:subjects {:alice {:desc "A"} :bob {:desc "B"}}
   :verbs {:web {:click {:desc "Click" :frames {:default {:args [] :pattern "S clicks O"}}}
                 :fill {:desc "Fill"}}}
   :sources {:subjects "sl/glossary/subjects.edn"
             :verbs {:web "sl/glossary/verbs-web.edn"}}
   :def-sites {:subjects {:alice {:file "sl/glossary/subjects.edn" :line 3}
                          :bob {:file "sl/glossary/subjects.edn" :line 4}}
               :verbs {:web {:click {:file "sl/glossary/verbs-web.edn" :line 2}
                             :fill {:file "sl/glossary/verbs-web.edn" :line 3}}}}})

(def ^:private interfaces {:web {:type :web :adapter :etaoin}})

(defn- issue-of [result type]
  (first (filter #(= type (:type %)) (:issues result))))

(deftest subject-verb-interface-issues-carry-def-sites
  (testing "a near-miss subject: suggestion + the suggested entry's site"
    (let [issue (issue-of (validate/validate-svo glossary-with-sites interfaces
                                                 {:subject :alcie :verb :click :interface :web})
                          :svo/unknown-subject)]
      (is (= :alice (:suggestion issue)))
      (is (= {:file "sl/glossary/subjects.edn" :line 3 :entry :alice} (:def-site issue)))
      (is (str/includes? (validate/format-svo-issue issue)
                         "Declared in: sl/glossary/subjects.edn:3"))))
  (testing "no near miss: the section file alone — where to declare it"
    (let [issue (issue-of (validate/validate-svo glossary-with-sites interfaces
                                                 {:subject :zzzzzz :verb :click :interface :web})
                          :svo/unknown-subject)]
      (is (nil? (:suggestion issue)))
      (is (= {:file "sl/glossary/subjects.edn"} (:def-site issue)))
      (is (str/includes? (validate/format-svo-issue issue)
                         "Declare it in: sl/glossary/subjects.edn"))))
  (testing "a near-miss verb: the verb file and line"
    (let [issue (issue-of (validate/validate-svo glossary-with-sites interfaces
                                                 {:subject :alice :verb :clikc :interface :web})
                          :svo/unknown-verb)]
      (is (= :click (:suggestion issue)))
      (is (= {:file "sl/glossary/verbs-web.edn" :line 2 :entry :click} (:def-site issue)))))
  (testing "a verb with no project file for its type names the builtin resource"
    (let [issue (issue-of (validate/validate-svo (dissoc glossary-with-sites :sources :def-sites)
                                                 interfaces
                                                 {:subject :alice :verb :zzzz :interface :web})
                          :svo/unknown-verb)]
      (is (= {:file "shiftlefter/glossaries/verbs-web.edn (builtin resource)"}
             (:def-site issue)))))
  (testing "an unknown interface names the config file under [:interfaces]"
    (let [issue (issue-of (validate/validate-svo glossary-with-sites interfaces
                                                 {:subject :alice :verb :click :interface :wb}
                                                 {:config-path "sl/shiftlefter.edn"})
                          :svo/unknown-interface)]
      (is (= :web (:suggestion issue)))
      (is (= {:file "sl/shiftlefter.edn" :path [:interfaces] :entry :web} (:def-site issue)))
      (is (str/includes? (validate/format-svo-issue issue)
                         "Declared in: sl/shiftlefter.edn under [:interfaces]"))))
  (testing "without a config path the interface issue has no def-site (never a fake one)"
    (let [issue (issue-of (validate/validate-svo glossary-with-sites interfaces
                                                 {:subject :alice :verb :click :interface :wb})
                          :svo/unknown-interface)]
      (is (nil? (:def-site issue))))))

(defn- with-login-intents
  "Login (email/submit bound :web; code bound :sms only) into intent-state."
  [f]
  (with-temp-dir
    (fn [dir]
      (spit (str (fs/path dir "login.edn"))
            "{:intent \"Login\"\n :elements {:email {:bindings {:web {:id \"email\"}}}\n            :submit {:bindings {:web {:css \"button[type=submit]\"}}}\n            :code {:bindings {:sms {:pattern \"\\\\d+\"}}}}}\n")
      (spit (str (fs/path dir "signup.edn"))
            "{:intent \"Signup\"\n :elements {:name {:bindings {:web {:id \"name\"}}}}}\n")
      (intent-state/reload-intents! dir)
      (try (f dir) (finally (intent-state/clear-intents!))))))

(defn- object-issue [object level]
  (issue-of (validate/validate-svo glossary-with-sites interfaces
                                   {:subject :alice :verb :click :frame :default
                                    :object object :interface :web}
                                   {:unknown-object level})
            :svo/unknown-object))

(deftest unresolvable-intent-refs-are-errors-with-suggestion-and-def-site
  (with-login-intents
    (fn [dir]
      (let [login (str (fs/path dir "login.edn"))]
        (testing "unknown intent under :warn: an ERROR with the nearest intent + its file:line"
          (let [issue (object-issue "Logn.submit" :warn)]
            (is (= :error (:severity issue)))
            (is (= "Login" (:suggestion issue)))
            (is (= {:file login :line 1 :entry "Login"} (:def-site issue)))
            (is (str/includes? (:message issue) "Did you mean: Login?"))))
        (testing "unknown element: the nearest element of that intent + the element's line"
          (let [issue (object-issue "Login.submti" :warn)]
            (is (= :error (:severity issue)))
            (is (= "Login.submit" (:suggestion issue)))
            (is (= {:file login :line 3 :entry "Login.submit"} (:def-site issue)))))
        (testing "Sol 32: no binding for the selected interface — the interfaces that DO bind, the element's line"
          (let [issue (object-issue "Login.code" :warn)]
            (is (= :error (:severity issue)))
            (is (nil? (:suggestion issue)))
            (is (= [:sms] (:known issue)))
            (is (= {:file login :line 4} (:def-site issue)))
            (is (str/includes? (validate/format-svo-issue issue) "Bindings exist for: :sms"))
            (is (str/includes? (validate/format-svo-issue issue)
                               (str "Declare it in: " login ":4")))))
        (testing "a resolvable ref is clean"
          (is (nil? (object-issue "Login.submit" :warn))))
        (testing ":off still declines every object check"
          (is (nil? (object-issue "Logn.submit" :off))))))))

(deftest ref-shaped-heuristic-both-ways
  (with-login-intents
    (fn [_dir]
      (testing "ref-shaped and malformed (no whitespace, uppercase start): an error"
        (let [issue (object-issue "Login..submit" :warn)]
          (is (= :error (:severity issue)))))
      (testing "free text (a space) on an intent slot: the knob's level, with the fix named"
        (let [issue (object-issue "the big button" :warn)]
          (is (nil? (:severity issue)) "no own severity — the :unknown-object level decides")
          (is (str/includes? (:message issue) "declare :object-kind :text"))))
      (testing "lowercase start: free text, but a capitalized twin that resolves is the did-you-mean"
        (let [issue (object-issue "login.submit" :warn)]
          (is (nil? (:severity issue)))
          (is (= "Login.submit" (:suggestion issue)))
          (is (str/includes? (:message issue) "Did you mean: Login.submit?"))))
      (testing "the classifier itself"
        (is (validate/ref-shaped? "Login.submit"))
        (is (validate/ref-shaped? "Login..submit"))
        (is (not (validate/ref-shaped? "the big button")))
        (is (not (validate/ref-shaped? "login.submit")))))))

(deftest raw-locators-ride-their-own-level-with-a-locator-index-did-you-mean
  (with-login-intents
    (fn [dir]
      (let [run (fn [raw-level]
                  (issue-of (validate/validate-svo glossary-with-sites interfaces
                                                   {:subject :alice :verb :click :frame :default
                                                    :object "{:css \"button[type=submit]\"}" :interface :web}
                                                   {:unknown-object :warn :raw-locator raw-level})
                            :svo/raw-locator-disallowed))]
        (testing ":off — allowed silently, whatever :unknown-object says"
          (is (nil? (run :off))))
        (testing ":warn / :error — the issue, with the intent that binds the same locator"
          (doseq [level [:warn :error]]
            (let [issue (run level)]
              (is (some? issue) (str "issue at " level))
              (is (nil? (:severity issue)) "the :raw-locator level decides the severity")
              (is (= "Login.submit" (:suggestion issue)))
              (is (= {:file (str (fs/path dir "login.edn")) :line 3 :entry "Login.submit"}
                     (:def-site issue)))
              (is (str/includes? (validate/format-svo-issue issue) "Did you mean: Login.submit?")))))
        (testing "a locator nobody binds: the issue without a suggestion"
          (let [issue (issue-of (validate/validate-svo glossary-with-sites interfaces
                                                       {:subject :alice :verb :click :frame :default
                                                        :object "{:css \"#nope\"}" :interface :web}
                                                       {:raw-locator :warn})
                                :svo/raw-locator-disallowed)]
            (is (some? issue))
            (is (nil? (:suggestion issue)))))))))

;; -----------------------------------------------------------------------------
;; (d) captures
;; -----------------------------------------------------------------------------

(defn- mk-step [text line captures kinds]
  {:status :matched
   :step {:step/id (java.util.UUID/randomUUID)
          :step/text text
          :step/location {:line line :column 3}}
   :binding {:captures captures :slot-kinds kinds}})

(deftest capture-did-you-mean-names-the-producing-step
  (let [issues (lint/check-plans
                [{:plan/pickle {:pickle/source-file "features/reset.feature"}
                  :plan/runnable? true
                  :plan/steps [(mk-step "receive" 7 ["'p'" "/(?<code>\\d+)/"] [:value :matcher])
                               (mk-step "fill" 9 ["Login.code" "{coed}"] [nil :value])]}])
        err (first (filter #(= :bindings/consumed-without-producer (:type %)) issues))]
    (is (= :code (:suggestion err)))
    (is (= {:file "features/reset.feature" :line 7 :entry :code} (:def-site err)))
    (is (str/includes? (validate/format-svo-issue err) "Produced at: features/reset.feature:7"))))

(deftest capture-did-you-mean-names-the-hook-file-for-a-provides-seed
  (let [issues (lint/check-plans
                [{:plan/pickle {:pickle/source-file "features/reset.feature"}
                  :plan/runnable? true
                  :plan/hooks [{:name "seed" :provides [:token]
                                :registration {:path "sl/hooks.clj"}}]
                  :plan/steps [(mk-step "fill" 9 ["Login.code" "{tokne}"] [nil :value])]}])
        err (first (filter #(= :bindings/consumed-without-producer (:type %)) issues))]
    (is (= :token (:suggestion err)))
    (is (= {:file "sl/hooks.clj" :entry :token} (:def-site err)))))

;; -----------------------------------------------------------------------------
;; (e) the undefined-step verb suggestion (Fable 5)
;; -----------------------------------------------------------------------------

(defn- with-stepdefs [f]
  (registry/clear-registry!)
  (try
    (registry/register! #"^(.+) clicks (.+)$" (fn [_ _ _] nil)
                        {:ns 's :file "sl/steps/s.clj" :line 12}
                        {:interface :web :svo {:subject :$1 :verb :click :object :$2}})
    (registry/register! #"^(.+) fills (.+) with '(.+)'$" (fn [_ _ _ _] nil)
                        {:ns 's :file "sl/steps/s.clj" :line 20}
                        {:interface :web :svo {:subject :$1 :verb :fill :object :$2}})
    (f)
    (finally (registry/clear-registry!))))

(defn- undefined [text]
  (bind/bind-step {:step/id (java.util.UUID/randomUUID) :step/text text :step/arguments []}
                  (registry/all-stepdefs) nil))

(deftest verb-suggestion-rematches-and-names-the-stepdef
  (with-stepdefs
    (fn []
      (let [r (undefined ":user/alice clikcs Login.submit")]
        (is (= :undefined (:status r)))
        (is (= {:token "clikcs" :suggestion "clicks"
                :repaired-text ":user/alice clicks Login.submit"
                :pattern-src "^(.+) clicks (.+)$"
                :def-site {:file "sl/steps/s.clj" :line 12}}
               (:verb-suggestion r)))))))

(deftest verb-suggestion-needs-a-rematch
  (with-stepdefs
    (fn []
      (testing "a near word whose repair still matches nothing: no suggestion (never a guess)"
        (is (nil? (:verb-suggestion (undefined "clicksy")))))
      (testing "a step with no near word at all"
        (is (nil? (:verb-suggestion (undefined ":user/alice navigates home"))))))))

(deftest verb-suggestion-never-reads-quoted-spans-or-annotations
  (with-stepdefs
    (fn []
      (testing "C3: a literal's content is never a token — 'clikcs' inside quotes suggests nothing"
        (is (nil? (:verb-suggestion
                   (undefined ":user/alice fills Login.email with 'clikcs'")))))
      (testing "C3: the interface annotation is stripped before tokens are taken"
        (let [r (bind/bind-step {:step/id (java.util.UUID/randomUUID)
                                 :step/text "[:web] :user/alice clikcs Login.submit"
                                 :step/declared-interface :web
                                 :step/arguments []}
                                (registry/all-stepdefs)
                                {:interfaces {:web {:type :web :adapter :etaoin}}})]
          (is (= "clikcs" (-> r :verb-suggestion :token)))
          (is (= ":user/alice clicks Login.submit" (-> r :verb-suggestion :repaired-text))
              "the repair is of the stripped text; 'web' was never a token"))))))

;; -----------------------------------------------------------------------------
;; (f) renderers
;; -----------------------------------------------------------------------------

(deftest console-renders-the-undefined-did-you-mean-line
  (let [diagnostics {:undefined [{:step {:step/text "alice clikcs Login.submit"}
                                  :source-file "f.feature"
                                  :verb-suggestion {:token "clikcs" :suggestion "clicks"
                                                    :repaired-text "alice clicks Login.submit"
                                                    :pattern-src "^(.+) clicks (.+)$"
                                                    :def-site {:file "sl/steps/s.clj" :line 12}}}]
                     :ambiguous [] :invalid-arity []
                     :counts {:undefined-count 1 :ambiguous-count 0 :invalid-arity-count 0 :total-issues 1}}
        out (with-err-str (console/print-diagnostics! diagnostics {:no-color true}))]
    (is (str/includes? out "did you mean \"clicks\"? — ^(.+) clicks (.+)$ (sl/steps/s.clj:12)"))))

(deftest console-renders-def-sites-on-svo-issues
  (let [diagnostics {:svo-issues [{:type :svo/unknown-subject :subject :alcie :known [:alice]
                                   :suggestion :alice :severity :warn
                                   :def-site {:file "sl/glossary/subjects.edn" :line 3 :entry :alice}
                                   :location {:step-text "When :alcie clicks" :uri "f.feature" :line 3}}]
                     :counts {:svo-issue-count 1 :total-issues 1}}
        out (with-err-str (console/print-warnings! diagnostics {:no-color true}))]
    (is (str/includes? out "Did you mean: :alice?"))
    (is (str/includes? out "Declared in: sl/glossary/subjects.edn:3"))))

(deftest edn-passes-verb-suggestion-and-def-sites-through
  (let [vs {:token "clikcs" :suggestion "clicks" :repaired-text "alice clicks Login.submit"
            :def-site {:file "sl/steps/s.clj" :line 12}}
        site {:file "sl/glossary/subjects.edn" :line 3 :entry :alice}
        diagnostics {:undefined [{:step {:step/id #uuid "00000000-0000-0000-0000-000000000009"
                                         :step/text "alice clikcs Login.submit"}
                                  :verb-suggestion vs}]
                     :ambiguous [] :invalid-arity []
                     :svo-issues [{:type :svo/unknown-subject :subject :alcie :suggestion :alice
                                   :def-site site}]
                     :config-lints [{:type :config/misplaced-key :key :step-paths
                                     :suggested-path [:runner :step-paths]
                                     :def-site {:file "sl/shiftlefter.edn"}}]
                     :counts {:undefined-count 1 :ambiguous-count 0 :invalid-arity-count 0
                              :svo-issue-count 1 :total-issues 2}}
        summary (report-edn/build-summary "run" 2 nil {:diagnostics diagnostics})]
    (is (= vs (-> summary :planning :issues first :verb-suggestion)))
    (is (= site (-> summary :planning :svo-issues first :def-site)))
    (is (= {:file "sl/shiftlefter.edn"} (-> summary :planning :config-lints first :def-site)))))
