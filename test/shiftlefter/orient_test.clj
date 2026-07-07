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
  (write-edn! (fs/path dir "glossary" "subjects.edn")
              {:subjects {:user {:desc "Application user" :instances [:alice :bob]}
                          :guest {:desc "Visitor"}}})
  (write-text! (fs/path dir "steps" "login.clj")
               "(ns login.steps
                  (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                (defstep #\"a project custom step\" [ctx] ctx)")
  (write-edn! (fs/path dir "shiftlefter.edn")
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
          (is (str/includes? out "## What would fail"))
          (is (str/includes? out "## Validate / run"))
          (is (str/includes? out "sl agent-doc builtins")))
        (testing "stays thin: no vocabulary/stepdef inventories or doctrine prose"
          ;; counts mention the words, but the actual entries must not appear.
          (is (not (str/includes? out ":alice")))
          (is (not (str/includes? out "a project custom step")))
          (is (not (str/includes? out "S clicks O"))))))))

;; -----------------------------------------------------------------------------
;; Mode line (sl-hjnp)
;; -----------------------------------------------------------------------------

(deftest mode-line-vanilla-test
  (with-temp-project
    (fn [dir]
      (make-project! dir)  ; make-project!'s config has no :svo → vanilla
      (let [out (with-out-str
                  (is (= 0 (orient/orient-cmd ["orient"] {} (context dir)))))]
        (is (str/includes? out "Mode: Vanilla — SVO validation OFF"))
        (is (str/includes? out "add :svo to enable"))
        (is (not (str/includes? out "Mode: Shifted")))))))

(deftest mode-line-shifted-test
  (with-temp-project
    (fn [dir]
      (make-project! dir)
      (write-edn! (fs/path dir "shiftlefter.edn")
                  {:runner {:step-paths ["steps/"] :macros {:enabled? false}}
                   :glossaries {:subjects "glossary/subjects.edn"}
                   :interfaces {:web {:type :web :adapter :etaoin :config {:headless true}}}
                   :svo {:unknown-subject :warn}})
      (let [out (with-out-str
                  (is (= 0 (orient/orient-cmd ["orient"] {} (context dir)))))]
        (is (str/includes? out "Mode: Shifted — SVO validation ON"))
        (is (not (str/includes? out "Mode: Vanilla")))))))

(deftest mode-in-edn-test
  (with-temp-project
    (fn [dir]
      (make-project! dir)
      (let [out (with-out-str
                  (is (= 0 (orient/orient-cmd ["orient"] {:edn true} (context dir)))))
            value (edn/read-string out)]
        (is (= :vanilla (:mode value)))))))

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

;; -----------------------------------------------------------------------------
;; CLI dispatch + config-root behavior (AC1)
;; -----------------------------------------------------------------------------

(deftest dispatch-test
  (with-temp-project
    (fn [dir]
      (make-project! dir)
      (testing "dispatches through core/dispatch and resolves project context"
        (let [out (with-out-str
                    (is (= 0 (core/dispatch ["orient" "-c" (str (fs/path dir "shiftlefter.edn"))]))))]
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
      (write-edn! (fs/path dir "glossary" "subjects.edn") {:subjects {}})
      (write-edn! (fs/path dir "shiftlefter.edn")
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
            (is (str/includes? out "Create shiftlefter.edn"))
            (is (str/includes? out "sl agent-doc overview"))
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
        (write-edn! (fs/path dir "glossary" "subjects.edn")
                    {:subjects {:user {:desc "Application user" :instances [:alice :bob]}
                                :guest {:desc "Visitor"}
                                :admin {:desc "Administrator"}}})
        (let [fp2 (-> (with-out-str (orient/orient-cmd ["orient"] {:edn true} (context dir)))
                      edn/read-string :fingerprint)]
          (is (not= fp1 fp2)
              "fingerprint must change when accepted truth changes"))))))
