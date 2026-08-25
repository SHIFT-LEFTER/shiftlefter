(ns shiftlefter.resolution-test
  "sl glossary + sl explain (sl-0gfc). Mirrors orient_test's temp-project
   pattern; the AC centerpiece is config-divergence-test — a divergent
   -c/--config must change the listing exactly as it changes the run's own
   resolution (build-projection), proven by value equality per context."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.core :as core]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.project-projection :as projection]
            [shiftlefter.resolution :as resolution]))

;; -----------------------------------------------------------------------------
;; Fixtures
;; -----------------------------------------------------------------------------

(defn- temp-dir [] (str (fs/create-temp-dir)))

(defn- write-edn! [path value]
  (fs/create-dirs (fs/parent path))
  (spit (str path) (pr-str value)))

(defn- with-temp-project [f]
  (let [dir (temp-dir)]
    (try (f dir) (finally (when (fs/exists? dir) (fs/delete-tree dir))))))

(defn- context [dir]
  (project-context/resolve {:invocation-root dir}))

(defn- shifted-config [overrides]
  (merge {:runner {:macros {:enabled? false}}
          :glossaries {:subjects "glossary/subjects.edn"
                       :verbs {:web "glossary/verbs-web.edn"}
                       :intents "glossary/intents"}
          :interfaces {:web {:type :web :adapter :etaoin :config {:headless true}}}
          :svo {:unknown-subject :error}}
         overrides))

(defn- make-shifted-project!
  "Shifted project exercising every provenance marker: a NEW user verb
   (:frobs → :user-file), a COLLIDING user verb (:click → :config-override),
   untouched builtins (:builtin), subjects, and one intent."
  [dir]
  (write-edn! (fs/path dir "sl" "glossary" "subjects.edn")
              {:subjects {:user {:desc "Application user" :instances [:alice :bob]}
                          :guest {:desc "Visitor"}}})
  (write-edn! (fs/path dir "sl" "glossary" "verbs-web.edn")
              {:verbs {:click {:desc "Project click override"
                               :frames {:default {:args [] :pattern "S clicks O"}}}
                       :frobs {:desc "Frobnicate"
                               :frames {:default {:args [] :pattern "S frobs O"}}}}})
  (write-edn! (fs/path dir "sl" "glossary" "intents" "Login.edn")
              {:intent "Login"
               :description "Login flow"
               :elements {:email {:description "Email input"
                                  :bindings {:web {:css "#email"}}}}})
  (write-edn! (fs/path dir "sl" "shiftlefter.edn") (shifted-config {})))

(defn- run-cmd
  "Invoke a command fn capturing stdout/stderr separately."
  [f args opts ctx]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        code (binding [*out* out *err* err] (f args opts ctx))]
    {:code code :out (str out) :err (str err)}))

(defn- glossary-edn [ctx & [section]]
  (let [r (run-cmd resolution/glossary-cmd
                   (cond-> ["glossary"] section (conj section))
                   {:edn true} ctx)]
    (is (= 0 (:code r)))
    (edn/read-string (:out r))))

;; -----------------------------------------------------------------------------
;; Glossary: sections, provenance, narrowing
;; -----------------------------------------------------------------------------

(deftest glossary-sections-and-provenance-test
  (with-temp-project
    (fn [dir]
      (make-shifted-project! dir)
      (let [value (glossary-edn (context dir))
            web-verbs (->> (get-in value [:sections :verbs])
                           (filter #(= :web (:interface-type %)))
                           first)
            by-verb (into {} (map (juxt :verb :provenance)) (:verbs web-verbs))]
        (testing "all three sections present bare"
          (is (= #{:subjects :instances :verbs :objects}
                 (set (keys (:sections value))))))
        (testing "per-entry provenance markers"
          (is (= :config-override (by-verb :click)) "collision → config-override")
          (is (= :user-file (by-verb :frobs)) "addition → user-file")
          (is (= :builtin (by-verb :doubleclick)) "untouched builtin")
          (is (every? #(= :user-file (:provenance %))
                      (get-in value [:sections :subjects]))))
        (testing "intents render as objects with interface-contributed vocab"
          (is (= ["Login"] (map (comp str :intent)
                                (get-in value [:sections :objects :intents])))
              "intent listed")
          (is (seq (get-in value [:sections :objects :interface-contributed
                                  :keys :names]))
              "closed key set present"))
        (testing "header carries substrate + effective strictness"
          (is (str/ends-with? (get-in value [:substrate :config-path])
                              "sl/shiftlefter.edn"))
          (is (= :discovered (get-in value [:substrate :config-source])))
          (is (contains? (:substrate value) :git-sha) "git key present (nil-safe)")
          (is (= :error (get-in value [:svo-effective :unknown-subject]))))))))

(deftest glossary-narrowing-test
  (with-temp-project
    (fn [dir]
      (make-shifted-project! dir)
      (let [ctx (context dir)]
        (is (= [:subjects :instances]
               (keys (:sections (glossary-edn ctx "subjects")))))
        (is (= [:verbs] (keys (:sections (glossary-edn ctx "verbs")))))
        (is (= [:objects] (keys (:sections (glossary-edn ctx "objects")))))
        (testing "unknown section: usage on stderr, exit 2, stdout clean"
          (let [r (run-cmd resolution/glossary-cmd ["glossary" "bogus"] {} ctx)]
            (is (= 2 (:code r)))
            (is (= "" (:out r)))
            (is (str/includes? (:err r) "Unknown glossary section"))))))))

;; -----------------------------------------------------------------------------
;; THE AC test: a divergent config changes the listing exactly as it
;; changes the run's own resolution.
;; -----------------------------------------------------------------------------

(deftest config-divergence-test
  (with-temp-project
    (fn [dir]
      (make-shifted-project! dir)
      ;; Config B: same project, different glossary set (no user verbs file).
      (write-edn! (fs/path dir "sl" "shiftlefter-b.edn")
                  (shifted-config {:glossaries {:subjects "glossary/subjects.edn"
                                                :intents "glossary/intents"}}))
      (let [ctx-a (context dir)
            ctx-b (project-context/resolve
                   {:invocation-root dir
                    :config-path (str (fs/path dir "sl" "shiftlefter-b.edn"))})
            list-a (glossary-edn ctx-a)
            list-b (glossary-edn ctx-b)
            proj-verbs (fn [ctx]
                         (->> (:verbs (projection/build-projection ctx))
                              (filter #(= :web (:interface-type %)))
                              first :verbs (map (juxt :verb :provenance)) set))
            listed-verbs (fn [value]
                           (->> (get-in value [:sections :verbs])
                                (filter #(= :web (:interface-type %)))
                                first :verbs (map (juxt :verb :provenance)) set))]
        (testing "the two configs produce different listings"
          (is (not= (listed-verbs list-a) (listed-verbs list-b)))
          (is (contains? (listed-verbs list-a) [:frobs :user-file]))
          (is (not (contains? (listed-verbs list-b) [:frobs :user-file]))))
        (testing "each listing equals the run's own resolution for that config"
          (is (= (proj-verbs ctx-a) (listed-verbs list-a)))
          (is (= (proj-verbs ctx-b) (listed-verbs list-b))))
        (testing "fingerprint identity: the listing cites the same resolved state"
          (is (= (:fingerprint (projection/build-projection ctx-a))
                 (:fingerprint list-a))))))))

(deftest dispatch-config-flag-test
  (with-temp-project
    (fn [dir]
      (make-shifted-project! dir)
      (let [out (with-out-str
                  (is (= 0 (core/dispatch
                            ["glossary" "--edn" "-c"
                             (str (fs/path dir "sl" "shiftlefter.edn"))]))))
            value (edn/read-string out)]
        (is (= :explicit (get-in value [:substrate :config-source]))
            "-c reaches the command through the run's own context resolution")))))

;; -----------------------------------------------------------------------------
;; Effective strictness (femd conditional default in the header)
;; -----------------------------------------------------------------------------

(deftest effective-strictness-header-test
  (with-temp-project
    (fn [dir]
      (make-shifted-project! dir)
      (testing "intents configured + no explicit :unknown-object → :warn (femd flip)"
        (let [value (glossary-edn (context dir))]
          (is (= :warn (get-in value [:svo-effective :unknown-object]))
              "the conditional default is only readable from the resolved state")))
      (testing ":strict displays as :error (binder normalization)"
        (write-edn! (fs/path dir "sl" "shiftlefter.edn")
                    (shifted-config {:svo {:unknown-subject :error
                                           :unknown-object :strict}}))
        (let [value (glossary-edn (context dir))]
          (is (= :error (get-in value [:svo-effective :unknown-object]))))))))

;; -----------------------------------------------------------------------------
;; Vanilla + no-project honesty
;; -----------------------------------------------------------------------------

(deftest vanilla-honest-message-test
  (with-temp-project
    (fn [dir]
      (write-edn! (fs/path dir "sl" "shiftlefter.edn")
                  {:interfaces {:web {:type :web :adapter :etaoin}}})
      (let [ctx (context dir)]
        (testing "human: honest message, exit 0"
          (let [r (run-cmd resolution/glossary-cmd ["glossary"] {} ctx)]
            (is (= 0 (:code r)))
            (is (str/includes? (:out r) "Vanilla"))
            (is (str/includes? (:out r) "vocabulary contract applies"))))
        (testing "EDN discriminator"
          (let [r (run-cmd resolution/glossary-cmd ["glossary"] {:edn true} ctx)]
            (is (= 0 (:code r)))
            (is (= :not-applicable (:status (edn/read-string (:out r)))))))
        (testing "explain is Vanilla-gated the same way"
          (let [r (run-cmd resolution/explain-cmd ["explain" "click"] {:edn true} ctx)]
            (is (= 0 (:code r)))
            (is (= :not-applicable (:status (edn/read-string (:out r)))))))))))

(deftest no-project-test
  (with-temp-project
    (fn [dir]
      (let [ctx (context dir)]
        (is (= :defaults (:config-source ctx)) "sanity: no config")
        (let [r (run-cmd resolution/glossary-cmd ["glossary"] {:edn true} ctx)]
          (is (= 0 (:code r)))
          (is (= :no-project (:status (edn/read-string (:out r))))))))))

;; -----------------------------------------------------------------------------
;; Explain: kinds, bindings incl. missing, miss handling
;; -----------------------------------------------------------------------------

(deftest explain-kinds-test
  (with-temp-project
    (fn [dir]
      (make-shifted-project! dir)
      (let [ctx (context dir)
            explain (fn [name*]
                      (let [r (run-cmd resolution/explain-cmd
                                       ["explain" name*] {:edn true} ctx)]
                        [(:code r) (edn/read-string (:out r))]))]
        (testing "verb: frames + per-interface bindings including MISSING"
          (let [[code value] (explain "frobs")
                m (first (:matches value))]
            (is (= 0 code))
            (is (= :verb (:kind m)))
            (is (= :user-file (:provenance m)))
            (is (str/ends-with? (:def-site m) "verbs-web.edn"))
            (let [b (-> m :bindings :interfaces first :bindings first)]
              (is (= :default (:frame b)))
              (is (true? (:missing? b))
                  "a user verb with no stepdef reports the missing binding"))))
        (testing "builtin verb has non-missing bindings with file:line sources"
          (let [[_ value] (explain ":click")
                b (-> value :matches first :bindings :interfaces first
                      :bindings first)]
            (is (false? (:missing? b)))
            (is (some (comp :file :source) (:stepdefs b))
                "stepdef def-sites carry file (and line) — the true file:line tier")))
        (testing "subject type and instance"
          (let [[_ tv] (explain "user")
                [_ iv] (explain "user/alice")]
            (is (= :subject-type (-> tv :matches first :kind)))
            (is (= :subject-instance (-> iv :matches first :kind)))
            (is (str/ends-with? (-> iv :matches first :def-site) "subjects.edn"))))
        (testing "intent"
          (let [[_ value] (explain "Login")]
            (is (= :intent (-> value :matches first :kind)))
            (is (= :user-file (-> value :matches first :provenance)))))
        (testing "key name (interface-contributed)"
          (let [[code value] (explain "Enter")]
            (is (= 0 code))
            (is (= :key (-> value :matches first :kind)))
            (is (= :interface-contributed (-> value :matches first :provenance)))))))))

(deftest explain-miss-test
  (with-temp-project
    (fn [dir]
      (make-shifted-project! dir)
      (let [ctx (context dir)]
        (testing "human miss: stderr suggestion, stdout clean, exit 1"
          (let [r (run-cmd resolution/explain-cmd ["explain" "clik"] {} ctx)]
            (is (= 1 (:code r)))
            (is (= "" (:out r)))
            (is (str/includes? (:err r) "Did you mean"))
            (is (str/includes? (:err r) ":click"))))
        (testing "surface-token reverse lookup: pattern word → verb"
          (let [r (run-cmd resolution/explain-cmd ["explain" "clicks"] {} ctx)]
            (is (= 1 (:code r)))
            (is (str/includes? (:err r) ":click"))))
        (testing "EDN miss: structured on stdout, exit 1"
          (let [r (run-cmd resolution/explain-cmd ["explain" "clik"] {:edn true} ctx)
                value (edn/read-string (:out r))]
            (is (= 1 (:code r)))
            (is (= :miss (:status value)))
            (is (some #{:click} (:suggestions value)))))
        (testing "no NAME: usage, exit 2"
          (let [r (run-cmd resolution/explain-cmd ["explain"] {} ctx)]
            (is (= 2 (:code r)))
            (is (= "" (:out r)))
            (is (str/includes? (:err r) "Usage"))))))))

;; -----------------------------------------------------------------------------
;; Persona surfacing (sl-4e91): docstrings + per-instance wear in listings
;; -----------------------------------------------------------------------------

(deftest persona-surfacing-test
  (with-temp-project
    (fn [dir]
      (make-shifted-project! dir)
      ;; Overwrite subjects with the persona-authored shape: docstring +
      ;; per-instance :wears, a :wears nil opt-out, and a bare keyword.
      (write-edn! (fs/path dir "sl" "glossary" "subjects.edn")
                  {:subjects {:user {:desc "Application user"
                                     :wears :default-jacket
                                     :instances [{:id :alicia
                                                  :desc "Spanish-first admin reviewer"
                                                  :wears :alicia}
                                                 {:id :bob :wears nil}
                                                 :carol]}}})
      (let [ctx (context dir)]
        (testing "sl glossary subjects (human): persona sub-lines in instance order"
          (let [r (run-cmd resolution/glossary-cmd ["glossary" "subjects"] {} ctx)]
            (is (= 0 (:code r)))
            (is (str/includes? (:out r) "instances: alicia, bob, carol"))
            (is (str/includes?
                 (:out r)
                 "- :user/alicia — Spanish-first admin reviewer (wears :alicia)"))
            (is (str/includes? (:out r) "- :user/bob (wears nothing)"))
            (is (not (str/includes? (:out r) ":user/carol"))
                "bare instances get no sub-line")))
        (testing "sl glossary --edn: instance rows carry description + effective wear"
          (let [value (glossary-edn ctx "subjects")
                rows (into {} (map (juxt :instance identity))
                           (get-in value [:sections :instances]))]
            (is (= "Spanish-first admin reviewer" (:description (rows :alicia))))
            (is (= :alicia (:wears (rows :alicia))))
            (is (= :instance (:wears-source (rows :alicia))))
            (is (= :default-jacket (:wears (rows :carol))) "inherited type default")
            (is (= :type-default (:wears-source (rows :carol))))
            (is (not (contains? (rows :bob) :wears))
                ":wears nil opt-out surfaces as wearing nothing")))
        (testing "sl explain user/alicia: docstring + own wear, no inherited marker"
          (let [r (run-cmd resolution/explain-cmd ["explain" "user/alicia"] {} ctx)]
            (is (= 0 (:code r)))
            (is (str/includes? (:out r) "Spanish-first admin reviewer"))
            (is (str/includes? (:out r) "Wears: :alicia"))
            (is (not (str/includes? (:out r) "inherited")))))
        (testing "sl explain user/carol: inherited wear is marked"
          (let [r (run-cmd resolution/explain-cmd ["explain" "user/carol"] {} ctx)]
            (is (= 0 (:code r)))
            (is (str/includes? (:out r)
                               "Wears: :default-jacket (inherited from :user)"))))))))

;; -----------------------------------------------------------------------------
;; v2 (sl-mpu5): usage facet, dead-vocab markers, selection wording,
;; explain-by-locator, duplicates, matrix seam
;; -----------------------------------------------------------------------------

(defn- write-file! [path content]
  (fs/create-dirs (fs/parent path))
  (spit (str path) content))

(defn- make-corpus-project!
  "make-shifted-project! plus: a feature corpus (one multi-actor scenario),
   a user-lib stepdef binding the user verb :frobs, a DEAD user verb
   (:zorps), a dead intent (Orphan, whose locator collides with Login's),
   and Login unbound on a second configured interface type (:sms) so the
   #16 seam has a hole to report."
  [dir]
  (make-shifted-project! dir)
  (write-edn! (fs/path dir "sl" "glossary" "verbs-web.edn")
              {:verbs {:click {:desc "Project click override"
                               :frames {:default {:args [] :pattern "S clicks O"}}}
                       :frobs {:desc "Frobnicate"
                               :frames {:default {:args [] :pattern "S frobs O"}}}
                       :zorps {:desc "Never used"
                               :frames {:default {:args [] :pattern "S zorps O"}}}}})
  (write-edn! (fs/path dir "sl" "glossary" "intents" "Orphan.edn")
              {:intent "Orphan"
               :description "Referenced by nothing"
               :elements {:email {:description "Colliding locator"
                                  :bindings {:web {:css "#email"}}}}})
  (write-file! (fs/path dir "sl" "steps" "frobs_steps.clj")
               (str "(ns frobs-steps\n"
                    "  (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
                    "(defstep #\"^:([\\w./-]+) frobs (.+)$\"\n"
                    "  {:interface :web\n"
                    "   :svo {:subject :$1 :verb :frobs :frame :default :object :$2}}\n"
                    "  [ctx _s _o]\n"
                    "  ctx)\n"))
  (write-file! (fs/path dir "sl" "features" "login.feature")
               (str "Feature: Login\n"
                    "  @auth\n"
                    "  Scenario: Alice logs in\n"
                    "    When :alice clicks Login.email\n"
                    "  Scenario: Alice and Bob together\n"
                    "    When :alice clicks Login.email\n"
                    "    When :bob frobs Login.email\n"))
  (write-edn! (fs/path dir "sl" "shiftlefter.edn")
              (shifted-config
               {:runner {:step-paths ["steps/"] :features ["features/"]
                         :macros {:enabled? false}}
                :interfaces {:web {:type :web :adapter :etaoin :config {:headless true}}
                             :sms {:type :sms :adapter :sms-mock}}})))

(deftest usage-markers-full-corpus-test
  (with-temp-project
    (fn [dir]
      (make-corpus-project! dir)
      (let [ctx (context dir)
            value (glossary-edn ctx)
            subjects (into {} (map (juxt :type identity))
                           (get-in value [:sections :subjects]))
            web-verbs (->> (get-in value [:sections :verbs])
                           (filter #(= :web (:interface-type %))) first :verbs
                           (into {} (map (juxt :verb identity))))
            intents (into {} (map (juxt (comp str :intent) identity))
                          (get-in value [:sections :objects :intents]))]
        (testing "usage block: full corpus, honest counts"
          (is (= :ok (get-in value [:usage :status])))
          (is (= :full-corpus (get-in value [:usage :scope])))
          (is (= 2 (get-in value [:usage :corpus :scenario-count]))))
        (testing "subjects: used type marked, deadness verdict present"
          (is (false? (:unused? (subjects :user))))
          (is (pos? (:use-count (subjects :user))))
          (is (true? (:unused? (subjects :guest))) "guest never acts"))
        (testing "verbs: user verb used via user-lib stepdef; dead user verb
                  flagged; builtins carry counts but NEVER a deadness verdict"
          (is (false? (:unused? (web-verbs :frobs)))
              "parity fold-in: sl/steps stepdef's verb is used")
          (is (true? (:unused? (web-verbs :zorps))))
          (is (contains? (web-verbs :doubleclick) :use-count))
          (is (not (contains? (web-verbs :doubleclick) :unused?))
              "builtin verbs are never dead"))
        (testing "intents: referenced vs orphaned + the #16 seam on TYPES"
          (is (false? (:unused? (intents "Login"))))
          (is (true? (:unused? (intents "Orphan"))))
          (is (= [:sms] (:interfaces-without-bindings (intents "Login")))
              "Login has :web bindings only; configured TYPE :sms is the hole"))
        (testing "human wording: UNUSED only in full-corpus voice"
          (let [r (run-cmd resolution/glossary-cmd ["glossary"] {} ctx)]
            (is (str/includes? (:out r) "[UNUSED]"))
            (is (str/includes? (:out r) "missing bindings: :sms"))
            (is (not (str/includes? (:out r) "not referenced within this selection")))))))))

(deftest selection-wording-discipline-test
  (with-temp-project
    (fn [dir]
      (make-corpus-project! dir)
      (let [ctx (context dir)
            r (run-cmd resolution/glossary-cmd ["glossary"]
                       {:edn true :tags #{"@auth"}} ctx)
            value (edn/read-string (:out r))
            web-verbs (->> (get-in value [:sections :verbs])
                           (filter #(= :web (:interface-type %))) first :verbs
                           (into {} (map (juxt :verb identity))))]
        (testing "selection scope is echoed; the deadness KEY disappears"
          (is (= :selection (get-in value [:usage :scope])))
          (is (= 1 (get-in value [:usage :corpus :scenario-count])))
          (is (not (contains? (web-verbs :frobs) :unused?))
              "no deadness verdicts under a selection — structurally impossible")
          (is (false? (:referenced-in-selection? (web-verbs :frobs)))
              ":frobs lives outside the @auth selection")
          (is (true? (:referenced-in-selection? (web-verbs :click)))))
        (testing "human wording never says unused under a selection"
          (let [rh (run-cmd resolution/glossary-cmd ["glossary"]
                            {:tags #{"@auth"}} ctx)]
            (is (str/includes? (:out rh) "[not referenced within this selection]"))
            (is (not (str/includes? (:out rh) "UNUSED")))))))))

(deftest explain-usage-facet-test
  (with-temp-project
    (fn [dir]
      (make-corpus-project! dir)
      (let [ctx (context dir)
            r (run-cmd resolution/explain-cmd ["explain" "alice"] {:edn true} ctx)
            value (edn/read-string (:out r))
            usage (:usage (first (:matches value)))]
        (testing "subject-instance usage: hits, type rollup, multi-actor, co-occurrence"
          (is (= 0 (:code r)))
          (is (= 2 (get-in usage [:instance :hit-count])))
          (is (= 3 (get-in usage [:type :hit-count])) "type rollup includes bob")
          (is (true? (:multi-actor? usage)))
          (is (= [{:with :bob :scenarios 1}] (:co-occurrence usage)))
          (is (pos? (get-in usage [:instance :hits 0 :line]))
              "hits carry file:line for authoring-by-example"))
        (testing "human rendering carries the usage section"
          (let [rh (run-cmd resolution/explain-cmd ["explain" "alice"] {} ctx)]
            (is (str/includes? (:out rh) "Usage (full corpus): 2 step(s)"))
            (is (str/includes? (:out rh) "co-occurs with :bob"))))
        (testing "intent usage rides explain too"
          (let [ri (run-cmd resolution/explain-cmd ["explain" "Login"] {:edn true} ctx)
                iv (edn/read-string (:out ri))]
            (is (= 3 (get-in (first (:matches iv)) [:usage :hit-count])))))))))

(deftest explain-by-locator-test
  (with-temp-project
    (fn [dir]
      (make-corpus-project! dir)
      (let [ctx (context dir)]
        (testing "hit: owners across intents, structural equality"
          (let [r (run-cmd resolution/explain-cmd
                           ["explain" "{:css \"#email\"}"] {:edn true} ctx)
                value (edn/read-string (:out r))]
            (is (= 0 (:code r)))
            (is (= :ok (:status value)))
            (is (= #{"Login.email" "Orphan.email"}
                   (set (map :address (:owners value)))))))
        (testing "miss: exit 1, free-to-mint message on stderr"
          (let [r (run-cmd resolution/explain-cmd
                           ["explain" "{:css \"#nothing\"}"] {} ctx)]
            (is (= 1 (:code r)))
            (is (str/includes? (:err r) "free to mint"))))
        (testing "unreadable EDN: exit 2"
          (is (= 2 (:code (run-cmd resolution/explain-cmd
                                   ["explain" "{:css"] {} ctx)))))))))

(deftest duplicates-on-demand-test
  (with-temp-project
    (fn [dir]
      (make-corpus-project! dir)
      (let [ctx (context dir)]
        (testing "bare glossary never includes duplicates (on-demand only)"
          (is (not (contains? (:sections (glossary-edn ctx)) :duplicates))))
        (testing "sl glossary duplicates: the collision, cross-intent tier"
          (let [value (glossary-edn ctx "duplicates")
                dup (first (get-in value [:sections :duplicates]))]
            (is (= 1 (count (get-in value [:sections :duplicates]))))
            (is (= {:css "#email"} (:locator dup)))
            (is (= :cross-intent (:tier dup)) "Login + Orphan span intents")
            (is (every? :file (:owners dup)) "def-site files ride the owners")))
        (testing "human rendering names the coincidence tier"
          (let [r (run-cmd resolution/glossary-cmd ["glossary" "duplicates"] {} ctx)]
            (is (= 0 (:code r)))
            (is (str/includes? (:out r) "page-scoped coincidence"))))))))

(deftest corpus-unavailable-suppresses-markers-test
  (with-temp-project
    (fn [dir]
      (make-shifted-project! dir)   ; v1 project — no features dir at all
      (let [value (glossary-edn (context dir))
            subjects (get-in value [:sections :subjects])]
        (is (= :unavailable (get-in value [:usage :status])))
        (is (not-any? #(contains? % :unused?) subjects)
            "no corpus → NO deadness verdicts (false-positive guard)")
        (is (not-any? #(contains? % :use-count) subjects))))))

;; -----------------------------------------------------------------------------
;; Corpus bind × custom registries (sl-hyey, Warden W4 sibling)
;; -----------------------------------------------------------------------------

(defn- make-custom-registry-corpus-project!
  "make-corpus-project! reshaped to the audit's failure scenario: the :web
   interface's adapter (:my-web) exists ONLY as a setup.clj declaration,
   and the :frobs stepdef requires a protocol only that adapter provides.
   Pre-fix the corpus bound against the default registry → spurious
   :stepdef/missing-capability → compile-suite killed every plan → zero
   tuples behind a green header."
  [dir]
  (make-corpus-project! dir)
  (write-file! (fs/path dir "sl" "steps" "frobs_steps.clj")
               (str "(ns frobs-steps\n"
                    "  (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
                    "(defstep #\"^:([\\w./-]+) frobs (.+)$\"\n"
                    "  {:interface :web\n"
                    "   :requires-protocols [:shiftlefter.browser.protocol/IBrowser]\n"
                    "   :svo {:subject :$1 :verb :frobs :frame :default :object :$2}}\n"
                    "  [ctx _s _o]\n"
                    "  ctx)\n"))
  (write-file! (fs/path dir "sl" "setup.clj")
               (str "(ns setup)\n"
                    "(def setups\n"
                    "  [{:label    \"custom-web\"\n"
                    "    :adapters [{:name :my-web\n"
                    "                :type :web\n"
                    "                :provides [:shiftlefter.browser.protocol/IBrowser]}]\n"
                    "    :start    (fn [_config] {})\n"
                    "    :features [\"features/\"]}])\n"))
  (write-edn! (fs/path dir "sl" "shiftlefter.edn")
              (shifted-config
               {:runner {:step-paths ["steps/"] :features ["features/"]
                         :macros {:enabled? false}}
                :interfaces {:web {:type :web :adapter :my-web}}})))

(deftest usage-markers-survive-custom-registry-test
  ;; The W4-sibling kill: an adapter living only in setup.clj must not cost
  ;; the project its usage markers. The corpus now binds against the e0ml
  ;; declared-preview registry, so the capability check passes for real —
  ;; no lint caveat, honest markers.
  (with-temp-project
    (fn [dir]
      (make-custom-registry-corpus-project! dir)
      (let [value (glossary-edn (context dir))
            web-verbs (->> (get-in value [:sections :verbs])
                           (filter #(= :web (:interface-type %))) first :verbs
                           (into {} (map (juxt :verb identity))))]
        (is (= :ok (get-in value [:usage :status])))
        (is (= 2 (get-in value [:usage :corpus :scenario-count]))
            "the corpus really bound — plans were not lint-killed")
        (is (empty? (get-in value [:usage :corpus :suite-lint-issues]))
            "the declared adapter satisfies the capability check outright")
        (is (false? (:unused? (web-verbs :frobs)))
            "the custom-capability verb keeps its honest usage marker")
        (is (true? (:unused? (web-verbs :zorps)))
            "real deadness still detected — markers are live, not blanket")))))

(deftest suite-lint-is-warn-not-kill-on-corpus-bind-test
  ;; A GENUINELY missing capability: gating informs (visible caveat, issues
  ;; in the EDN corpus block) but a listing is not a run — plans survive
  ;; and markers stay computed (the mpu5 vacuity law).
  (with-temp-project
    (fn [dir]
      (make-custom-registry-corpus-project! dir)
      (write-file! (fs/path dir "sl" "steps" "frobs_steps.clj")
                   (str "(ns frobs-steps\n"
                        "  (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
                        "(defstep #\"^:([\\w./-]+) frobs (.+)$\"\n"
                        "  {:interface :web\n"
                        "   :requires-protocols [:nowhere.protocol/INope]\n"
                        "   :svo {:subject :$1 :verb :frobs :frame :default :object :$2}}\n"
                        "  [ctx _s _o]\n"
                        "  ctx)\n"))
      (let [ctx (context dir)
            value (glossary-edn ctx)
            issues (get-in value [:usage :corpus :suite-lint-issues])
            web-verbs (->> (get-in value [:sections :verbs])
                           (filter #(= :web (:interface-type %))) first :verbs
                           (into {} (map (juxt :verb identity))))]
        (is (= :ok (get-in value [:usage :status])))
        (is (= [:stepdef/missing-capability] (mapv :type issues))
            "the genuine gap rides the EDN corpus block as data")
        (is (false? (:unused? (web-verbs :frobs)))
            "plans survived the lint — markers computed, not suppressed")
        (testing "the human header carries the visible caveat"
          (let [r (run-cmd resolution/glossary-cmd ["glossary"] {} ctx)]
            (is (= 0 (:code r)))
            (is (str/includes? (:out r) "suite-lint issue(s) over the corpus"))))))))

(deftest non-suite-lint-errors-still-block-listings-test
  ;; The tolerance is scoped: only suite-lint-stage errors pass the gate.
  ;; A projection error that makes the vocabulary itself untrustworthy
  ;; (here: setup.clj fails to load) still blocks with exit 1.
  (with-temp-project
    (fn [dir]
      (make-custom-registry-corpus-project! dir)
      (write-file! (fs/path dir "sl" "setup.clj") "(ns setup)\n(def setups (")
      (let [r (run-cmd resolution/glossary-cmd ["glossary"] {} (context dir))]
        (is (= 1 (:code r)))
        (is (str/includes? (:err r) "accepted truth failed to load"))))))
