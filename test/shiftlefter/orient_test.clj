(ns shiftlefter.orient-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.core :as core]
            [shiftlefter.orient :as orient]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.project-projection :as projection]))

;; -----------------------------------------------------------------------------
;; Fixtures — mirrors project_projection_test's temp-project pattern.
;; -----------------------------------------------------------------------------

(defn- temp-dir [] (str (fs/create-temp-dir)))

(defn- write-edn! [path value]
  (fs/create-dirs (fs/parent path))
  (spit (str path) (pr-str value)))

(defn- write-text! [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn- with-temp-project [f]
  (let [dir (temp-dir)]
    (try (f dir) (finally (when (fs/exists? dir) (fs/delete-tree dir))))))

(defn- context [dir]
  (project-context/resolve {:invocation-root dir}))

(defn- make-project! [dir]
  ;; sl/ layout (sl-fcjm): config + corpus under <project>/sl/, corpus paths
  ;; config-root-relative.
  (write-edn! (fs/path dir "sl" "glossary" "subjects.edn")
              {:subjects {:user {:desc "Application user" :instances [:alice :bob]}
                          :guest {:desc "Visitor"}}})
  (write-text! (fs/path dir "sl" "steps" "login.clj")
               "(ns login.steps
                  (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                (defstep #\"a project custom step\" [ctx] ctx)")
  (write-edn! (fs/path dir "sl" "shiftlefter.edn")
              {:runner {:step-paths ["steps/"] :macros {:enabled? false}}
               :glossaries {:subjects "glossary/subjects.edn"}
               :interfaces {:web {:type :web :adapter :etaoin :config {:headless true}}}}))

;; -----------------------------------------------------------------------------
;; Default thin render (AC2)
;; -----------------------------------------------------------------------------

(deftest thin-render-test
  (with-temp-project
    (fn [dir]
      (make-project! dir)
      (let [out (with-out-str
                  (is (= 0 (orient/orient-cmd ["orient"] {} (context dir)))))]
        (testing "renders context, counts, commands, and agent-doc pointers"
          (is (str/includes? out "# Orientation"))
          (is (str/includes? out "## Project context"))
          (is (str/includes? out "config-source: :discovered"))
          (is (str/includes? out "## Counts"))
          (is (str/includes? out "## Project checks"))
          (is (str/includes? out "Project checks: ok")
              "the top line names the section it summarizes — never a bare 'Status'")
          (is (str/includes? out "Checked: config, glossaries, step files"))
          (is (str/includes? out "Not checked: your features — orient does not read them."))
          (is (str/includes? out "No diagnostics from these checks."))
          (is (not (str/includes? out "loads clean"))
              "no sentence reads as a verdict on the whole project (sl-a55s)")
          (is (str/includes? out "## Validate / run"))
          (is (str/includes? out "sl agent-doc builtins")))
        (testing "stays thin: no vocabulary/stepdef inventories or doctrine prose"
          ;; counts mention the words, but the actual entries must not appear.
          (is (not (str/includes? out ":alice")))
          (is (not (str/includes? out "a project custom step")))
          (is (not (str/includes? out "S clicks O"))))))))

;; -----------------------------------------------------------------------------
;; Orient's lane (sl-a55s, Chair ruling 2026-09-21): the project WITHOUT the
;; features. Corpus-free checks stay; corpus-relative ones are dry-run's.
;; -----------------------------------------------------------------------------

(defn- orient-of [dir]
  (let [code (atom nil)
        out (with-out-str (reset! code (orient/orient-cmd ["orient"] {} (context dir))))]
    {:code @code :out out
     :projection (projection/build-projection (context dir))}))

(deftest orphan-glossary-type-still-surfaces-test
  (testing "AC1: :glossary/orphan-type needs no features — it stays in orient"
    (with-temp-project
      (fn [dir]
        (make-project! dir)
        (write-edn! (fs/path dir "sl" "glossary" "verbs-github.edn")
                    {:type :github/web
                     :verbs {:fork {:desc "Fork a repository"
                                    :frames {:default {:args [] :pattern "S forks O"}}}}})
        (write-edn! (fs/path dir "sl" "shiftlefter.edn")
                    {:runner {:step-paths ["steps/"] :macros {:enabled? false}}
                     :glossaries {:subjects "glossary/subjects.edn"
                                  :verbs ["glossary/verbs-github.edn"]}
                     :interfaces {:web {:type :web :adapter :etaoin}}})
        (let [{:keys [code out projection]} (orient-of dir)]
          (is (= [:glossary/orphan-type]
                 (mapv :type (filter #(= :suite-lint (:stage %)) (:diagnostics projection)))))
          (is (= :error (:status projection)))
          (is (= 1 code) "orient's exit ladder is unchanged")
          (is (str/includes? out ":glossary/orphan-type"))
          (is (str/includes? out "Project checks: error")))))))

(deftest api-only-project-sees-no-noise-test
  (testing "AC3: browser builtins are REGISTERED but unused and no :web
            interface is configured — orient says nothing about them (the
            old sl-s2oa filter existed only to hide these false alarms; with
            the corpus-relative checks gone there is nothing to hide)"
    (with-temp-project
      (fn [dir]
        (make-project! dir)
        (write-edn! (fs/path dir "sl" "shiftlefter.edn")
                    {:runner {:step-paths ["steps/"] :macros {:enabled? false}}
                     :glossaries {:subjects "glossary/subjects.edn"}
                     :interfaces {:sms {:type :sms :adapter :sms-mock}}})
        (let [{:keys [code out projection]} (orient-of dir)]
          (is (some #(= :web (get-in % [:metadata :interface])) (:stepdefs projection))
              "precondition: browser stepdefs are registered")
          (is (= [] (:diagnostics projection)))
          (is (= 0 code))
          (is (str/includes? out "No diagnostics from these checks.")))))))

(deftest missing-capability-is-not-orients-to-report-test
  (testing "J-d, the ruling applied: :stepdef/missing-capability is defined
            over a MATCHED stepdef — a fact about the features. A stepdef
            whose configured adapter lacks its protocol made orient exit 1;
            orient does not read features, so it now exits 0 (sl run
            --dry-run still exits 2 when a feature matches that stepdef)"
    (with-temp-project
      (fn [dir]
        (make-project! dir)
        (write-text! (fs/path dir "sl" "steps" "frobs.clj")
                     "(ns frobs.steps
                        (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                      (defstep #\"a step needing a protocol nobody provides\"
                        {:interface :web
                         :requires-protocols [:nowhere.protocol/INope]}
                        [ctx] ctx)")
        (let [{:keys [code projection]} (orient-of dir)]
          (is (not-any? #(#{:stepdef/missing-capability :stepdef/undefined-interface} (:type %))
                        (:diagnostics projection)))
          (is (= :ok (:status projection)))
          (is (= 0 code)))))))

;; -----------------------------------------------------------------------------
;; No mode line, no :mode key (sl-6wh9 — the sl-hjnp mode line is retired)
;; -----------------------------------------------------------------------------

(deftest no-mode-line-test
  (with-temp-project
    (fn [dir]
      (make-project! dir)
      (let [out (with-out-str
                  (is (= 0 (orient/orient-cmd ["orient"] {} (context dir)))))]
        (is (not (str/includes? out "Mode:")))
        (is (not (str/includes? out "Vanilla")))))))

(deftest enforcement-effective-in-edn-test
  (with-temp-project
    (fn [dir]
      (make-project! dir)
      (let [out (with-out-str
                  (is (= 0 (orient/orient-cmd ["orient"] {:edn true} (context dir)))))
            value (edn/read-string out)]
        (is (not (contains? value :mode)))
        (is (= :warn (get-in value [:enforcement-effective :unknown-subject]))
            "the effective levels are always present, block or no block")))))

;; -----------------------------------------------------------------------------
;; --edn full projection (AC4) + shared zvo source
;; -----------------------------------------------------------------------------

(deftest edn-render-test
  (with-temp-project
    (fn [dir]
      (make-project! dir)
      (let [out (with-out-str
                  (is (= 0 (orient/orient-cmd ["orient"] {:edn true} (context dir)))))
            value (edn/read-string out)]
        (testing "emits the full projection value, reader-readable, one shot"
          (is (= :ok (:status value)))
          (is (string? (:fingerprint value)))
          (is (contains? value :projection/id))
          (is (seq (:subjects value)))
          (is (seq (:verbs value)))
          (is (seq (:stepdefs value))))
        (testing "shares the zvo source — fingerprint matches build-projection"
          (is (= (:fingerprint (projection/build-projection (context dir)))
                 (:fingerprint value))))))))

(deftest edn-adapter-metadata-provenance-test
  ;; sl-hyey (Warden W4): the provenance-honest metadata reaches the --edn
  ;; surface verbatim — a declared adapter shows the declaration's data
  ;; half with the runtime fields absent and NAMED unknown, never stamped
  ;; off the hardcoded default registry.
  (with-temp-project
    (fn [dir]
      (make-project! dir)
      (write-edn! (fs/path dir "sl" "shiftlefter.edn")
                  {:runner {:step-paths ["steps/"] :macros {:enabled? false}}
                   :glossaries {:subjects "glossary/subjects.edn"}
                   :interfaces {:web {:type :web :adapter :etaoin}
                                :sms {:type :sms :adapter :sms-mock}}})
      (write-text! (fs/path dir "sl" "setup.clj")
                   "(ns setup)
                    (def setups
                      [{:adapters [{:name :sms-mock
                                    :provides [:shiftlefter.sms.protocol/ISMS]}]
                        :start    (fn [_config] {})
                        :features [\"features/\"]}])")
      (let [out (with-out-str
                  (is (= 0 (orient/orient-cmd ["orient"] {:edn true} (context dir)))))
            value (edn/read-string out)
            by-name (into {} (map (juxt :name :adapter-metadata)) (:interfaces value))]
        (is (= {:known? true
                :provenance :declared
                :provides [:shiftlefter.sms.protocol/ISMS]
                :capture-kinds []
                :runtime-unknown [:impl-key :has-on-provision?]}
               (by-name :sms)))
        (is (= :builtin-default (:provenance (by-name :web))))
        (is (= [{:name :sms-mock :provides [:shiftlefter.sms.protocol/ISMS]}]
               (:adapter-declarations value))
            "the pure-data declarations ride the EDN dump")))))

;; -----------------------------------------------------------------------------
;; CLI dispatch + config-root behavior (AC1)
;; -----------------------------------------------------------------------------

(deftest dispatch-test
  (with-temp-project
    (fn [dir]
      (make-project! dir)
      (testing "dispatches through core/dispatch and resolves project context"
        (let [out (with-out-str
                    (is (= 0 (core/dispatch ["orient" "-c" (str (fs/path dir "sl" "shiftlefter.edn"))]))))]
          (is (str/includes? out "# Orientation"))
          (is (str/includes? out "config-source: :explicit")))))))

;; -----------------------------------------------------------------------------
;; Partial/empty vocabulary (AC6) — honest, no synthesized truth
;; -----------------------------------------------------------------------------

(deftest empty-vocabulary-test
  (with-temp-project
    (fn [dir]
      ;; Valid config with an empty subject glossary: vocabulary is genuinely
      ;; empty, and orient must report that honestly — zero subjects, a SIEVE
      ;; pointer — without inventing accepted truth.
      (write-edn! (fs/path dir "sl" "glossary" "subjects.edn") {:subjects {}})
      (write-edn! (fs/path dir "sl" "shiftlefter.edn")
                  {:runner {:macros {:enabled? false}}
                   :glossaries {:subjects "glossary/subjects.edn"}
                   :interfaces {:web {:type :web :adapter :etaoin}}})
      (let [out (with-out-str
                  (is (= 0 (orient/orient-cmd ["orient"] {} (context dir)))))]
        (is (str/includes? out "subjects: 0") "empty vocabulary reported honestly")
        (is (str/includes? out "sl agent-doc sieve") "routes to the bootstrap workflow")
        (is (not (str/includes? out ":alice")) "no synthesized subjects")))))

;; -----------------------------------------------------------------------------
;; No-config bootstrap (AC11)
;; -----------------------------------------------------------------------------

(deftest no-config-bootstrap-test
  (with-temp-project
    (fn [dir]
      (let [ctx (context dir)]
        (is (= :defaults (:config-source ctx)) "sanity: no config in temp dir")
        (testing "default mode: honest bootstrap, not a raw projection error, exit 0"
          (let [out (with-out-str
                      (is (= 0 (orient/orient-cmd ["orient"] {} ctx))))]
            (is (str/includes? out "No ShiftLefter project here yet"))
            (is (str/includes? out "Create sl/shiftlefter.edn"))
            (is (str/includes? out "sl agent-doc overview"))
            (is (str/includes? out "sl agent-doc examples")
                "advertises the worked-examples topic (sl-c9qj)")
            (is (not (str/includes? out "config-required")))))
        (testing "--edn mode: machine discriminator :no-project, exit 0"
          (let [out (with-out-str
                      (is (= 0 (orient/orient-cmd ["orient"] {:edn true} ctx))))
                value (edn/read-string out)]
            (is (= :no-project (:status value)))
            (is (= :defaults (:config-source value)))
            (is (seq (:next-steps value)))))))))

;; -----------------------------------------------------------------------------
;; Applied working-tree change reflected in refreshed projection (AC9)
;; -----------------------------------------------------------------------------

(deftest refreshed-projection-test
  (with-temp-project
    (fn [dir]
      (make-project! dir)
      (let [fp1 (-> (with-out-str (orient/orient-cmd ["orient"] {:edn true} (context dir)))
                    edn/read-string :fingerprint)]
        ;; Apply a working-tree change: add a subject.
        (write-edn! (fs/path dir "sl" "glossary" "subjects.edn")
                    {:subjects {:user {:desc "Application user" :instances [:alice :bob]}
                                :guest {:desc "Visitor"}
                                :admin {:desc "Administrator"}}})
        (let [fp2 (-> (with-out-str (orient/orient-cmd ["orient"] {:edn true} (context dir)))
                      edn/read-string :fingerprint)]
          (is (not= fp1 fp2)
              "fingerprint must change when accepted truth changes"))))))

;; -----------------------------------------------------------------------------
;; sl-mk0t (Warden W2): the thin render surfaces the intents sub-error
;; -----------------------------------------------------------------------------

(deftest intents-load-error-surfaces-the-first-sub-error-test
  (testing "the [error/intents] line carries the first sub-error's message and
            def-site, not only 'Failed to load intents from <dir>'"
    (with-temp-project
      (fn [dir]
        (make-project! dir)
        (write-text! (fs/path dir "sl" "glossary" "intents" "login.edn")
                     (str "{:intent \"Login\"\n"
                          " :elements\n"
                          " {:submit {:bindings {:web {:css \"#submit\"}}}\n"
                          "  :code {:bindings {:web {:css \"#code\"}}\n"
                          "         :shape \"code is (?<x>\\\\d+)\"}}}"))
        (write-edn! (fs/path dir "sl" "shiftlefter.edn")
                    {:runner {:step-paths ["steps/"] :macros {:enabled? false}}
                     :glossaries {:subjects "glossary/subjects.edn"
                                  :intents "glossary/intents"}
                     :interfaces {:web {:type :web :adapter :etaoin}}})
        (let [{:keys [code out]} (orient-of dir)
              line (first (filter #(str/includes? % "[error/intents]") (str/split-lines out)))]
          (is (= 1 code) "orient's exit ladder is unchanged")
          (is (some? line))
          (is (str/includes? line ":intent/load-failed — Failed to load intents from"))
          (is (str/includes? line ":shape on element code names its one group \"x\"")
              "the first sub-error's message rides the line")
          (is (re-find #"\(Declared at: \S+/sl/glossary/intents/login\.edn:4\)" line)
              "and its def-site")
          (is (not (str/includes? line "more;"))
              "one sub-error, no remainder count"))))))
