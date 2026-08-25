(ns shiftlefter.project-projection-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.project-projection :as projection]))

(defn- temp-dir []
  (str (fs/create-temp-dir)))

(defn- delete-tree! [dir]
  (when (fs/exists? dir)
    (fs/delete-tree dir)))

(defn- write-edn! [path value]
  (fs/create-dirs (fs/parent path))
  (spit (str path) (pr-str value)))

(defn- write-text! [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn- with-temp-project [f]
  (let [dir (temp-dir)]
    (try
      (f dir)
      (finally
        (delete-tree! dir)))))

(defn- project-context [dir]
  (project-context/resolve {:invocation-root dir}))

(defn- make-basic-project! [dir]
  ;; sl/ layout (sl-fcjm): config + corpus under <project>/sl/.
  (write-edn! (fs/path dir "sl" "glossary" "subjects.edn")
              {:subjects {:user {:desc "Application user"
                                  :instances [:alice :bob]}
                          :guest {:desc "Visitor"}}})
  (write-edn! (fs/path dir "sl" "glossary" "intents" "Login.edn")
              {:intent "Login"
               :description "Login flow"
               :elements {:email {:description "Email input"
                                  :bindings {:web {:css "#email"}}}
                          :submit {:description "Submit button"
                                   :bindings {:web {:css "button[type=submit]"}}}}})
  (write-text! (fs/path dir "sl" "steps" "login.clj")
               "(ns login.steps
                  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

                (defstep #\"a project custom step\"
                  [ctx]
                  ctx)")
  (write-edn! (fs/path dir "sl" "shiftlefter.edn")
              {:runner {:step-paths ["steps/"]
                        :allow-pending? false
                        :macros {:enabled? false}}
               :glossaries {:subjects "glossary/subjects.edn"
                            :intents "glossary/intents"}
               :interfaces {:web {:type :web
                                   :adapter :etaoin
                                   :config {:headless true}}}
               :svo {:unknown-subject :error
                     :unknown-verb :error
                     :unknown-interface :error
                     :unknown-object :strict}}))

(deftest projection-requires-real-config-by-default
  (with-temp-project
    (fn [dir]
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})]
        (is (= :error (:status p)))
        (is (= :project-context/config-required
               (-> p :diagnostics first :type)))))))

(deftest projection-can-explicitly-use-default-config
  (with-temp-project
    (fn [dir]
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree
                                            :allow-defaults? true})]
        (is (= :ok (:status p)))
        (is (= :working-tree (:source p)))
        (is (= 1 (:projection/version p)))
        (is (string? (:fingerprint p)))
        (is (some #(= :web (:name %)) (:interfaces p)))
        (is (seq (:verbs p)))))))

(deftest projection-surfaces-config-lint-as-warn
  ;; sl-hlkz: the same lints the runner prints as stderr notices ride the
  ;; projection as :warn config diagnostics (runner parity per the sl-hjnp
  ;; rule), so orient surfaces them WITHOUT flipping :status.
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (write-edn! (fs/path dir "sl" "shiftlefter.edn")
                  {:step-paths ["steps/"]  ; misplaced — the evidence case
                   :mystery-knob true      ; unknown
                   :runner {:step-paths ["steps/"]
                            :allow-pending? false
                            :macros {:enabled? false}}})
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})
            config-warns (filter #(and (= :config (:stage %))
                                       (= :warn (:severity %)))
                                 (:diagnostics p))]
        (is (= :ok (:status p)) "warn-severity lints never fail orientation")
        (is (= #{:step-paths :mystery-knob} (set (map :key config-warns))))
        (is (= [:runner :step-paths]
               (->> config-warns
                    (filter #(= :step-paths (:key %)))
                    first
                    :suggested-path)))))))

(deftest projection-includes-project-knowledge
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})]
        (is (= :ok (:status p)))
        (is (= #{:user :guest} (set (map :type (:subjects p)))))
        (is (= #{:alice :bob} (set (map :instance (:instances p)))))
        (is (= [:web] (mapv :name (:interfaces p))))
        (is (some #(= :web (:interface-type %)) (:verbs p)))
        (is (= ["Login"] (mapv :intent (:intents p))))
        (is (some #(= "Login.email" %)
                  (-> p :intents first :representative-addresses)))
        (is (= [] (:macros p)))
        (is (some #(= :config (:kind %)) (:inputs p)))
        (is (some #(= :glossary/subjects (:kind %)) (:inputs p)))
        (is (some #(= :glossary/intents (:kind %)) (:inputs p)))
        (is (some #(= :stepdef (:kind %)) (:inputs p)))
        (is (every? #(not (contains? % :fn)) (:stepdefs p)))
        (is (every? #(not (contains? % :pattern)) (:stepdefs p)))))))

(deftest projection-loads-macros-in-normalized-form
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (write-text! (fs/path dir "sl" "macros" "auth.ini")
                   "name = login as alice
description = Login flow
steps =
  Given :user/alice opens the browser to 'http://example.test'
  When :user/alice fills Login.email with 'a@example.test'

")
      (write-edn! (fs/path dir "sl" "shiftlefter.edn")
                  {:runner {:step-paths ["steps/"]
                            :allow-pending? false
                            :macros {:enabled? true
                                     :registry-paths ["macros/auth.ini"]}}
                   :glossaries {:subjects "glossary/subjects.edn"
                                :intents "glossary/intents"}
                   :interfaces {:web {:type :web
                                       :adapter :etaoin
                                       :config {}}}
                   :svo {:unknown-subject :warn
                         :unknown-verb :warn
                         :unknown-interface :error
                         :unknown-object :strict}})
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})
            m (first (:macros p))]
        (is (= :ok (:status p)))
        (is (= :text-expansion (:kind m)))
        (is (= 1 (:representation-version m)))
        (is (= "login as alice" (-> m :invocation :key)))
        (is (= 2 (count (:expansion m))))
        (is (some #(= :macro (:kind %)) (:inputs p)))))))

(deftest projection-surfaces-step-load-failure
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (write-text! (fs/path dir "sl" "steps" "broken.clj")
                   "(ns broken.steps
                      (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                    (throw (ex-info \"boom\" {:why :test}))")
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})]
        (is (= :error (:status p)))
        (is (some #(= :step-load/failed (:type %)) (:diagnostics p)))
        (is (some #(str/includes? (:path %) "broken.clj") (:diagnostics p)))))))

;; -----------------------------------------------------------------------------
;; Adapter metadata provenance (sl-hyey, Warden W4)
;;
;; DELIBERATE SUPERSEDE: this block replaces `projection-does-not-load-setup`
;; (79a99327, 06-17 — the projection's birth, pre-e0ml). The declare/bind
;; split obsoleted that pin: the runner load-files setup.clj on every
;; --dry-run (layout doctrine: keep its top level pure), and the projection
;; now reads the e0ml :adapters declarations so :adapter-metadata reflects
;; the registry the project actually resolves — provenance-honest, never
;; guessed from the hardcoded default registry.
;; -----------------------------------------------------------------------------

(defn- interface-metadata
  "The :adapter-metadata of the named interface in projection p."
  [p iface-name]
  (->> (:interfaces p)
       (filter #(= iface-name (:name %)))
       first
       :adapter-metadata))

(defn- write-sms-project!
  "make-basic-project! plus an :sms interface on the :sms-mock adapter."
  [dir]
  (make-basic-project! dir)
  (write-edn! (fs/path dir "sl" "shiftlefter.edn")
              {:runner {:step-paths ["steps/"]
                        :allow-pending? false
                        :macros {:enabled? false}}
               :glossaries {:subjects "glossary/subjects.edn"
                            :intents "glossary/intents"}
               :interfaces {:web {:type :web :adapter :etaoin}
                            :sms {:type :sms :adapter :sms-mock}}
               :svo {:unknown-subject :error
                     :unknown-verb :error
                     :unknown-interface :error
                     :unknown-object :strict}}))

(def ^:private declared-sms-setup
  "An examples/04-shaped setup.clj: :sms-mock (a builtin NAME) re-declared
   with :provides — the override case that produced W4's live lie."
  "(ns setup)
   (def setups
     [{:label    \"sms\"
       :adapters [{:name :sms-mock
                   :provides [:shiftlefter.sms.protocol/ISMS
                              :shiftlefter.sms.protocol/ISMSInbound]}]
       :start    (fn [_config] {})
       :features [\"features/\"]}])")

(deftest adapter-metadata-defaults-carry-builtin-provenance
  ;; No setup.clj: metadata = the default registry, full fields — the
  ;; unchanged-behavior pin (plus the additive :provenance/:capture-kinds).
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})
            m (interface-metadata p :web)]
        (is (= :ok (:status p)))
        (is (= [] (:adapter-declarations p)))
        (is (= {:known? true
                :provenance :builtin-default
                :impl-key :browser
                :provides [:shiftlefter.browser.protocol/IBrowser]
                :capture-kinds [:console :page-source :screenshot]
                :has-on-provision? false}
               m))))))

(deftest adapter-metadata-declared-is-provenance-honest
  ;; Declared adapter (builtin-name override, the 04 shape): only the
  ;; declaration's data half is known — runtime fields absent and NAMED
  ;; unknown, never inherited from the default entry (W4's live lie: the
  ;; default :sms-mock has :on-provision, example 04's live registry
  ;; drops it).
  (with-temp-project
    (fn [dir]
      (write-sms-project! dir)
      (write-text! (fs/path dir "sl" "setup.clj") declared-sms-setup)
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})
            m (interface-metadata p :sms)]
        (is (= :ok (:status p)))
        (is (= [{:name :sms-mock
                 :provides [:shiftlefter.sms.protocol/ISMS
                            :shiftlefter.sms.protocol/ISMSInbound]}]
               (:adapter-declarations p)))
        (is (= {:known? true
                :provenance :declared
                :provides [:shiftlefter.sms.protocol/ISMS
                           :shiftlefter.sms.protocol/ISMSInbound]
                :capture-kinds []
                :runtime-unknown [:impl-key :has-on-provision?]}
               m)
            "declared entry: data half only, runtime fields absent and marked")
        (is (= :builtin-default (:provenance (interface-metadata p :web)))
            "undeclared builtin beside it keeps full default fields")))))

(deftest adapter-metadata-novel-declared-adapter-is-known
  ;; Novel name (absent from the default registry): previously
  ;; {:known? false} with a diagnostic — the declaration makes it known.
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (write-edn! (fs/path dir "sl" "shiftlefter.edn")
                  {:runner {:step-paths ["steps/"]
                            :allow-pending? false
                            :macros {:enabled? false}}
                   :glossaries {:subjects "glossary/subjects.edn"
                                :intents "glossary/intents"}
                   :interfaces {:mail {:type :email :adapter :my-mail}}
                   :svo {:unknown-subject :error
                         :unknown-verb :error
                         :unknown-interface :error
                         :unknown-object :strict}})
      (write-text! (fs/path dir "sl" "setup.clj")
                   "(ns setup)
                    (def setups
                      [{:adapters [{:name :my-mail
                                    :type :email
                                    :provides [:shiftlefter.email.protocol/IEmail]}]
                        :start    (fn [_config] {})
                        :features [\"features/\"]}])")
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})
            m (interface-metadata p :mail)]
        (is (true? (:known? m)))
        (is (= :declared (:provenance m)))
        (is (= [:shiftlefter.email.protocol/IEmail] (:provides m)))
        (is (= [:impl-key :has-on-provision?] (:runtime-unknown m)))))))

(deftest adapter-metadata-reads-declared-runtime-fields
  ;; sl-kkoh (the hyey follow-up): where the W20 declaration SPEAKS, the
  ;; metadata upgrades from :runtime-unknown to the declared value —
  ;; including declared-nil :impl-key, the deliberate-absence fact that
  ;; is NOT the same as unknown. Fields the declaration is silent about
  ;; stay marked; the key disappears when nothing is unknown.
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (write-edn! (fs/path dir "sl" "shiftlefter.edn")
                  {:runner {:step-paths ["steps/"]
                            :allow-pending? false
                            :macros {:enabled? false}}
                   :glossaries {:subjects "glossary/subjects.edn"
                                :intents "glossary/intents"}
                   :interfaces {:mail {:type :email :adapter :my-mail}
                                :post {:type :email :adapter :my-post}
                                :half {:type :email :adapter :my-half}}
                   :svo {:unknown-subject :error
                         :unknown-verb :error
                         :unknown-interface :error
                         :unknown-object :strict}})
      (write-text! (fs/path dir "sl" "setup.clj")
                   "(ns setup)
                    (def setups
                      [{:adapters [{:name :my-mail
                                    :provides [:shiftlefter.email.protocol/IEmail]
                                    :impl-key nil
                                    :on-provision? true}
                                   {:name :my-post
                                    :provides [:shiftlefter.email.protocol/IEmail]
                                    :impl-key :client
                                    :on-provision? false}
                                   {:name :my-half
                                    :provides [:shiftlefter.email.protocol/IEmail]
                                    :impl-key nil}]
                        :start    (fn [_config] {})
                        :features [\"features/\"]}])")
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})
            mail (interface-metadata p :mail)
            post (interface-metadata p :post)
            half (interface-metadata p :half)]
        (is (= :ok (:status p)))
        ;; both fields declared: no :runtime-unknown key at all
        (is (= {:known? true
                :provenance :declared
                :provides [:shiftlefter.email.protocol/IEmail]
                :capture-kinds []
                :impl-key nil
                :has-on-provision? true}
               mail)
            "declared-nil :impl-key is PRESENT as nil — deliberate absence")
        (is (= :client (:impl-key post)))
        (is (false? (:has-on-provision? post)))
        (is (not (contains? post :runtime-unknown)))
        ;; half-declared: only the silent field stays unknown
        (is (nil? (:impl-key half)))
        (is (contains? half :impl-key))
        (is (= [:has-on-provision?] (:runtime-unknown half)))
        (is (not (contains? half :has-on-provision?)))))))

(deftest adapter-metadata-broken-setup-is-error-with-default-fallback
  ;; Runner parity: a project whose setup.clj cannot load dies at planning,
  ;; so the projection reports :error — and metadata falls back to the
  ;; default registry (what is still known).
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (write-text! (fs/path dir "sl" "setup.clj")
                   "(ns setup)\n(def setups (")
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})]
        (is (= :error (:status p)))
        (is (some #(and (= :setup (:stage %)) (= :error (:severity %)))
                  (:diagnostics p)))
        (is (= :builtin-default (:provenance (interface-metadata p :web))))))))

(deftest projection-fingerprint-changes-with-setup-content
  (with-temp-project
    (fn [dir]
      (write-sms-project! dir)
      (write-text! (fs/path dir "sl" "setup.clj") declared-sms-setup)
      (let [ctx (project-context dir)
            p1 (projection/build-projection ctx {:source :working-tree})]
        (is (some #(= :setup (:kind %)) (:inputs p1))
            "setup.clj rides the projection inputs")
        (write-text! (fs/path dir "sl" "setup.clj")
                     (str/replace declared-sms-setup "\"sms\"" "\"sms2\""))
        (let [p2 (projection/build-projection ctx {:source :working-tree})]
          (is (not= (:fingerprint p1) (:fingerprint p2))))))))

(deftest projection-fingerprint-changes-with-relevant-input
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (let [ctx (project-context dir)
            p1 (projection/build-projection ctx {:source :working-tree})]
        (write-edn! (fs/path dir "sl" "glossary" "subjects.edn")
                    {:subjects {:user {:desc "Application user"
                                        :instances [:alice :bob :carol]}}})
        (let [p2 (projection/build-projection ctx {:source :working-tree})]
          (is (not= (:fingerprint p1) (:fingerprint p2))))))))

(deftest project-view-is-deterministic-and-reports-omissions
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})
            v1 (projection/project-view p {:include [:subjects :interfaces]
                                           :detail :full
                                           :budget {:max-items 1}})
            v2 (projection/project-view p {:include [:subjects :interfaces]
                                           :detail :full
                                           :budget {:max-items 1}})]
        (is (= v1 v2))
        (is (= #{:projection/id :projection/version :fingerprint :source :status
                 :mode :counts :subjects :interfaces}
               (set (keys v1))))
        (is (= 1 (count (get-in v1 [:subjects :items]))))
        (is (= 1 (get-in v1 [:subjects :omission :omitted-count])))))))

;; -----------------------------------------------------------------------------
;; Mode visibility + mode-respecting validation (sl-hjnp)
;; -----------------------------------------------------------------------------

(defn- write-mismatched-stepdef!
  "A stepdef whose :svo declares a frame absent from the framework-default
   web verb glossary (:click has no :nope frame). Tier-2 catches it in
   Shifted mode; the runner ignores it in Vanilla."
  [dir]
  (write-text! (fs/path dir "sl" "steps" "mismatched.clj")
               "(ns mismatched.steps
                  (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                (defstep #\"(\\w+) taps the (.+)\"
                  {:interface :web
                   :svo {:subject :$1 :verb :click :frame :nope :object :$2}}
                  [ctx _subject _thing]
                  ctx)"))

(defn- drop-svo-from-config!
  "Rewrite the temp project's shiftlefter.edn without its :svo key — the
   vanilla twin of make-basic-project!."
  [dir]
  (write-edn! (fs/path dir "sl" "shiftlefter.edn")
              {:runner {:step-paths ["steps/"]
                        :allow-pending? false
                        :macros {:enabled? false}}
               :glossaries {:subjects "glossary/subjects.edn"
                            :intents "glossary/intents"}
               :interfaces {:web {:type :web
                                   :adapter :etaoin
                                   :config {:headless true}}}}))

(deftest projection-shifted-mode-validates-stepdefs
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (write-mismatched-stepdef! dir)
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})]
        (is (= :shifted (:mode p)))
        (is (= :error (:status p)))
        (is (some #(and (= :stepdefs (:stage %))
                        (= :stepdef/unknown-frame (:type %)))
                  (:diagnostics p)))))))

(deftest projection-vanilla-mode-skips-stepdef-validation
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (write-mismatched-stepdef! dir)
      (drop-svo-from-config! dir)
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})]
        (is (= :vanilla (:mode p)))
        ;; The runner would ignore the mismatch in vanilla — so must we.
        (is (not-any? #(= :stepdefs (:stage %)) (:diagnostics p)))
        (is (= :ok (:status p)))))))

(deftest projection-vanilla-glossary-failure-is-warn
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (drop-svo-from-config! dir)
      ;; Break the subjects glossary path — the runner never loads it in
      ;; vanilla, so orientation must not fail; it warns and falls back to
      ;; framework defaults.
      (fs/delete (fs/path dir "sl" "glossary" "subjects.edn"))
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})]
        (is (= :vanilla (:mode p)))
        (is (= :ok (:status p)))
        (is (some #(and (= :glossary (:stage %)) (= :warn (:severity %)))
                  (:diagnostics p)))
        ;; Framework-default verbs still projected for inventory
        (is (seq (:verbs p)))))))

(deftest projection-shifted-glossary-failure-still-errors
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (fs/delete (fs/path dir "sl" "glossary" "subjects.edn"))
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})]
        (is (= :shifted (:mode p)))
        (is (= :error (:status p)))
        (is (some #(and (= :glossary (:stage %)) (= :error (:severity %)))
                  (:diagnostics p)))))))

;; -----------------------------------------------------------------------------
;; :validation-commands emit consumer-correct invocations (sl-10s)
;; -----------------------------------------------------------------------------

(deftest validation-commands-consumer-discovered
  ;; A temp project has no bin/sl at its root → consumer context. sl/shiftlefter.edn
  ;; resolves :discovered, so no --config flag is needed.
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (let [cmds (:validation-commands
                  (projection/build-projection (project-context dir)
                                               {:source :working-tree}))]
        ;; AC4 hard gate: no repo wrappers leak to a consumer agent.
        (is (not-any? #(str/includes? % "./bin/kaocha") cmds))
        (is (not-any? #(str/includes? % "./bin/sl") cmds))
        (is (every? #(str/starts-with? % "sl run") cmds))
        (is (some #(= "sl run --dry-run" %) cmds))
        (is (not-any? #(str/includes? % "--config") cmds))))))

(deftest validation-commands-consumer-explicit
  ;; Explicit config path → the sl run forms carry --config <path>.
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (let [ctx (project-context/resolve {:invocation-root dir
                                          :config-path "sl/shiftlefter.edn"})
            cmds (:validation-commands
                  (projection/build-projection ctx {:source :working-tree}))]
        (is (= :explicit (:config-source ctx)) "sanity: explicit config")
        (is (every? #(str/starts-with? % "sl run") cmds))
        (is (every? #(str/includes? % "--config") cmds))
        (is (not-any? #(str/includes? % "./bin/") cmds))))))

(deftest validation-commands-repo-context
  ;; A bin/sl at the project root marks the ShiftLefter source repo → repo
  ;; wrappers remain (no regression on AC2).
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (write-text! (fs/path dir "bin" "sl") "#!/usr/bin/env bash\n")
      (let [cmds (:validation-commands
                  (projection/build-projection (project-context dir)
                                               {:source :working-tree}))]
        (is (some #(= "./bin/kaocha unit" %) cmds))
        (is (some #(str/includes? % "./bin/sl run --dry-run") cmds))))))

(deftest renamed-interface-stepdef-validation-fires
  ;; sl-s2oa end-to-end (supersedes the sl-sfb1 shape): stepdef :interface
  ;; metadata is the interface TYPE, so a bad-verb stepdef declaring
  ;; :interface :web is checked directly even when the config names its
  ;; only instance :storefront — the composition sfb1 fixed by resolution
  ;; is now the model itself.
  (with-temp-project
    (fn [dir]
      (write-edn! (fs/path dir "sl" "glossary" "subjects.edn")
                  {:subjects {:user {:desc "User" :instances [:alice]}}})
      (write-text! (fs/path dir "sl" "steps" "custom.clj")
                   "(ns custom.steps
                      (:require [shiftlefter.stepengine.registry :refer [defstep]]))

                    (defstep #\"storefront bogus step\"
                      {:interface :web
                       :svo {:subject :$1 :verb :not-a-verb :frame :default :object :$2}}
                      [ctx]
                      ctx)")
      (write-edn! (fs/path dir "sl" "shiftlefter.edn")
                  {:runner {:step-paths ["steps/"] :macros {:enabled? false}}
                   :glossaries {:subjects "glossary/subjects.edn"}
                   :interfaces {:storefront {:type :web :adapter :etaoin}}
                   :svo {:unknown-subject :error}})
      (let [p (projection/build-projection (project-context dir))
            unknown-verb-issues (filter #(= :stepdef/unknown-verb (:type %))
                                        (:diagnostics p))]
        (is (seq unknown-verb-issues)
            "the tier-2 check fires in a renamed-instance config")
        (is (= :web (:interface-type (first unknown-verb-issues)))
            "issue carries the type")))))
