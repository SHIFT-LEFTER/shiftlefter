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
  (write-edn! (fs/path dir "glossary" "subjects.edn")
              {:subjects {:user {:desc "Application user"
                                  :instances [:alice :bob]}
                          :guest {:desc "Visitor"}}})
  (write-edn! (fs/path dir "glossary" "intents" "Login.edn")
              {:intent "Login"
               :description "Login flow"
               :elements {:email {:description "Email input"
                                  :bindings {:web {:css "#email"}}}
                          :submit {:description "Submit button"
                                   :bindings {:web {:css "button[type=submit]"}}}}})
  (write-text! (fs/path dir "steps" "login.clj")
               "(ns login.steps
                  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

                (defstep #\"a project custom step\"
                  [ctx]
                  ctx)")
  (write-edn! (fs/path dir "shiftlefter.edn")
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
      (write-edn! (fs/path dir "shiftlefter.edn")
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
      (write-text! (fs/path dir "macros" "auth.ini")
                   "name = login as alice
description = Login flow
steps =
  Given :user/alice opens the browser to 'http://example.test'
  When :user/alice fills Login.email with 'a@example.test'

")
      (write-edn! (fs/path dir "shiftlefter.edn")
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
      (write-text! (fs/path dir "steps" "broken.clj")
                   "(ns broken.steps
                      (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                    (throw (ex-info \"boom\" {:why :test}))")
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})]
        (is (= :error (:status p)))
        (is (some #(= :step-load/failed (:type %)) (:diagnostics p)))
        (is (some #(str/includes? (:path %) "broken.clj") (:diagnostics p)))))))

(deftest projection-does-not-load-setup
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (write-text! (fs/path dir "setup.clj")
                   "(ns setup)
                    (spit \"setup-was-loaded.txt\" \"bad\")
                    (def setups [])")
      (let [p (projection/build-projection (project-context dir)
                                           {:source :working-tree})]
        (is (= :ok (:status p)))
        (is (not (fs/exists? (fs/path dir "setup-was-loaded.txt"))))))))

(deftest projection-fingerprint-changes-with-relevant-input
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (let [ctx (project-context dir)
            p1 (projection/build-projection ctx {:source :working-tree})]
        (write-edn! (fs/path dir "glossary" "subjects.edn")
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
  (write-text! (fs/path dir "steps" "mismatched.clj")
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
  (write-edn! (fs/path dir "shiftlefter.edn")
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
      (fs/delete (fs/path dir "glossary" "subjects.edn"))
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
      (fs/delete (fs/path dir "glossary" "subjects.edn"))
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
  ;; A temp project has no bin/sl at its root → consumer context. config at the
  ;; root resolves :discovered, so no --config flag is needed.
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
        (is (some #(= "sl run features --dry-run" %) cmds))
        (is (not-any? #(str/includes? % "--config") cmds))))))

(deftest validation-commands-consumer-explicit
  ;; Explicit config path → the sl run forms carry --config <path>.
  (with-temp-project
    (fn [dir]
      (make-basic-project! dir)
      (let [ctx (project-context/resolve {:invocation-root dir
                                          :config-path "shiftlefter.edn"})
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
        (is (some #(str/includes? % "./bin/sl run features") cmds))))))
