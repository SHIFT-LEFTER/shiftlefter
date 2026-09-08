(ns shiftlefter.runner.config-truth-test
  "CLI-level pins for the config-truth class (sl-a7sx — 0.5.6 Warden fix
   wave 1, findings W-B1 / W-B2 / W33 / W23): every config shape the 0.5.6
   entries advertise reaches the ADVERTISED planning error on BOTH surfaces
   (`sl run --dry-run` and `sl orient`) instead of a crash (exit 3) or a
   lie (dry-run green, then exit 1 live). Each test drives a real temp
   project through `runner/execute!` or `core/dispatch` — the unit-level
   `normalize` pins in config_test never exercised the wiring, which is
   exactly where the audit found the crashes.

   Surfaces and their exit ladders: the runner exits 2 for a planning
   error; `orient` exits 1 for a projection :status :error (its own
   documented ladder — 2 there means an ambiguous context)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.core :as core]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.project-projection :as projection]
            [shiftlefter.runner.core :as runner]
            [shiftlefter.stepengine.registry :as registry]))

(defn- clean-registry-fixture [f]
  (registry/clear-registry!)
  (f)
  (registry/clear-registry!))

(use-fixtures :each clean-registry-fixture)

;; example-02's feature: builtin browser steps only — the shape the audit
;; matrix drove (a project with no stepdefs of its own).
(def ^:private browser-feature
  "examples/02-browser-zero-code/sl/features/login.feature")

(defn- with-project
  "Write an sl/-layout temp project holding `config` beside an EMPTY steps
   dir and the builtin-browser feature; call `f` with the paths; clean up."
  [config f]
  (let [dir (str (fs/create-temp-dir {:prefix "sl-config-truth-"}))
        sl-dir (fs/path dir "sl")
        feature (fs/path sl-dir "features" "login.feature")]
    (try
      (fs/create-dirs (fs/path sl-dir "steps"))
      (fs/create-dirs (fs/parent feature))
      (fs/copy browser-feature feature)
      (spit (str (fs/path sl-dir "shiftlefter.edn")) (pr-str config))
      (f {:dir dir
          :config-path (str (fs/path sl-dir "shiftlefter.edn"))
          :steps (str (fs/path sl-dir "steps"))
          :feature (str feature)})
      (finally
        (fs/delete-tree dir)))))

(defn- run-dry-edn
  "`sl run --dry-run --edn` through execute!; returns the parsed first
   stdout form, stderr, and the result map."
  [{:keys [config-path steps feature]}]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        result (binding [*out* out *err* err]
                 (runner/execute! {:paths [feature]
                                   :config-path config-path
                                   :step-paths [steps]
                                   :dry-run true
                                   :edn true
                                   :no-color true}))]
    {:result result
     :summary (edn/read-string (str out))
     :err (str err)}))

(defn- planning-error-types [summary]
  (mapv :type (get-in summary [:planning :errors])))

(defn- orient
  "`sl orient -c <config>` through core/dispatch; returns exit code + thin
   render."
  [{:keys [config-path]}]
  (let [out (java.io.StringWriter.)
        code (binding [*out* out *err* (java.io.StringWriter.)]
               (core/dispatch ["orient" "-c" config-path]))]
    {:code code :out (str out)}))

(defn- projection-of [{:keys [config-path]}]
  (projection/build-projection
   (project-context/resolve {:config-path config-path})))

;; A feature that binds AND runs without any interface — for the W23 live
;; run that must still write the reports the dry run only notices.
(def ^:private simple-feature "test/fixtures/features/simple.feature")
(def ^:private fixture-steps "test/fixtures/steps/")

(defn- run-captured
  "execute! with `opts`, capturing stdout+stderr; returns {:result :out :err}."
  [opts]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        result (binding [*out* out *err* err]
                 (runner/execute! (merge {:no-color true} opts)))]
    {:result result :out (str out) :err (str err)}))

(defn- notice-lines [err]
  (filterv #(str/starts-with? % "dry run: report(s) not written")
           (str/split-lines err)))

;; -----------------------------------------------------------------------------
;; W-B1 — :interfaces nil is the twin of {} (explicitly ZERO interfaces)
;; -----------------------------------------------------------------------------

(deftest interfaces-nil-is-the-twin-of-empty-map
  (testing "dry-run: nil and {} both fail suite-lint with
            :stepdef/undefined-interface (exit 2) — before the fix nil
            passed dry-run green and the live run exited 1 with
            :browser/no-session, a verdict about the app for a config error"
    (doseq [ifaces [nil {}]]
      (with-project {:interfaces ifaces}
        (fn [p]
          (let [{:keys [result summary]} (run-dry-edn p)]
            (is (= 2 (:exit-code result)) (str "interfaces " (pr-str ifaces)))
            (is (= :planning-failed (:run/status summary)))
            (is (some #(= :stepdef/undefined-interface (:type %))
                      (get-in summary [:planning :suite-lint-issues]))
                "the lint issue rides the planning block's :suite-lint-issues"))))))
  (testing "orient: nil and {} produce the SAME projection — one
            zero-interfaces shape (the run-path lint is a used-stepdef
            lint; orient's s2oa filter shows neither, and that parity is
            the ruling's claim)"
    (with-project {:interfaces nil}
      (fn [nil-p]
        (with-project {:interfaces {}}
          (fn [empty-p]
            (let [a (projection-of nil-p)
                  b (projection-of empty-p)]
              (is (= :ok (:status a) (:status b)))
              (is (= [] (:interfaces a) (:interfaces b)))
              (is (= (mapv :type (:diagnostics a))
                     (mapv :type (:diagnostics b))))
              (is (= 0 (:code (orient nil-p)) (:code (orient empty-p)))))))))))

;; -----------------------------------------------------------------------------
;; W-B2 — a present-but-non-map :interfaces reaches :config/invalid-interfaces
;; -----------------------------------------------------------------------------

(deftest non-map-interfaces-is-a-planning-error-on-both-surfaces
  (testing "run --dry-run: exit 2 naming :config/invalid-interfaces — was
            exit 3 (nth / Character→Map$Entry / ISeq-from-Keyword crashes)
            because the adapter probe ran before normalize's errors were
            consumed"
    (doseq [bad [[:web] "web" :default]]
      (with-project {:interfaces bad}
        (fn [p]
          (let [{:keys [result summary err]} (run-dry-edn p)]
            (is (= 2 (:exit-code result)) (str "interfaces " (pr-str bad)))
            (is (= [:config/invalid-interfaces] (planning-error-types summary)))
            (is (str/includes? (-> summary :planning :errors first :message)
                               (pr-str bad))
                "the message quotes the found value")
            (is (not (str/includes? err "crash"))))))))
  (testing "orient: exit 1 (projection :error) with the [error/config] line —
            was exit 3 with a raw stack trace through configured-types"
    (with-project {:interfaces [:web]}
      (fn [p]
        (let [{:keys [code out]} (orient p)]
          (is (= 1 code))
          (is (str/includes? out "[error/config] :config/invalid-interfaces"))
          (is (str/includes? out "interfaces: 0")
              "renders the rest against zero interfaces"))))))

;; -----------------------------------------------------------------------------
;; W33 — the retired type-keyed :verbs map reaches :config/invalid-glossaries
;; -----------------------------------------------------------------------------

(def ^:private legacy-verbs-map {:web "glossary/verbs/web.edn"})

(deftest retired-verbs-map-is-a-planning-error-on-both-surfaces
  (testing "run --dry-run, Shifted AND Vanilla: exit 2 quoting the found
            value and naming the path-list form — was exit 3 (MapEntry
            as-file coercion) because the resolver ran before normalize"
    (doseq [cfg [{:svo {} :glossaries {:verbs legacy-verbs-map}}
                 {:glossaries {:verbs legacy-verbs-map}}]]
      (with-project cfg
        (fn [p]
          (let [{:keys [result summary]} (run-dry-edn p)
                msg (-> summary :planning :errors first :message)]
            (is (= 2 (:exit-code result)) (pr-str cfg))
            (is (= [:config/invalid-glossaries] (planning-error-types summary)))
            (is (str/includes? msg (pr-str legacy-verbs-map)))
            (is (str/includes? msg "{:verbs [\"glossary/verbs/web.edn\"]}")
                "names the list form the user should write"))))))
  (testing "a string :verbs is the same planning error (it used to iterate
            the string's characters)"
    (with-project {:glossaries {:verbs "glossary/verbs/web.edn"}}
      (fn [p]
        (let [{:keys [result summary]} (run-dry-edn p)]
          (is (= 2 (:exit-code result)))
          (is (= [:config/invalid-glossaries] (planning-error-types summary)))))))
  (testing "orient (Shifted, the strict loader): exit 1 with the
            [error/config] line — was a raw trace through normalize-path"
    (with-project {:svo {} :glossaries {:verbs legacy-verbs-map}}
      (fn [p]
        (let [{:keys [code out]} (orient p)]
          (is (= 1 code))
          (is (str/includes? out "[error/config] :config/invalid-glossaries")))))))

;; -----------------------------------------------------------------------------
;; W23 — dry-run + CONFIG-declared reports: noticed, not written (F8 reversed)
;; -----------------------------------------------------------------------------

(def ^:private report-config
  ;; The shape `sl init` scaffolds — the config mirror F8's flag-only
  ;; refusal never saw.
  {:runner {:step-paths ["steps/"]
            :report {:html "report.html" :junit-xml "results.xml"}}})

(deftest dry-run-with-config-declared-reports-proceeds-with-a-notice
  (with-project report-config
    (fn [{:keys [dir config-path]}]
      (let [html (fs/path dir "sl" "report.html")
            junit (fs/path dir "sl" "results.xml")
            base {:paths [simple-feature] :step-paths [fixture-steps]
                  :config-path config-path}]
        (testing "--edn: exit 0, :run/status :dry-run, the additive
                  :run/reports-not-written vector of KINDS, no files —
                  before the fix: success line, exit 0, silence, no file"
          (let [{:keys [result out]} (run-captured (assoc base :dry-run true :edn true))
                summary (edn/read-string out)]
            (is (= 0 (:exit-code result)))
            (is (= :dry-run (:run/status summary)))
            (is (= [:html :junit-xml] (:run/reports-not-written summary)))
            (is (not (fs/exists? html)))
            (is (not (fs/exists? junit)))))
        (testing "console: exactly ONE stderr notice naming both resolved paths"
          (let [{:keys [result err]} (run-captured (assoc base :dry-run true))
                notices (notice-lines err)]
            (is (= 0 (:exit-code result)))
            (is (str/includes? err "bound successfully (dry run)"))
            (is (= 1 (count notices)))
            ;; canonicalize: the notice names the RESOLVED path (macOS
            ;; /private/var vs the temp dir's /var spelling)
            (is (str/includes? (first notices)
                               (str "html " (fs/canonicalize html))))
            (is (str/includes? (first notices)
                               (str "junit-xml " (fs/canonicalize junit))))
            (is (not (fs/exists? html)))))
        (testing "a LIVE run with the same config still writes both files —
                  the mirror is a real path, the notice is not a refusal"
          (let [{:keys [result err]} (run-captured base)]
            (is (= 0 (:exit-code result)))
            (is (empty? (notice-lines err)))
            (is (fs/exists? html))
            (is (fs/exists? junit))))))))

(deftest dry-run-without-declared-reports-is-byte-identical
  (testing "no report declared → no key, no notice (the goldens' shape)"
    (with-project {:runner {:step-paths ["steps/"]}}
      (fn [{:keys [config-path]}]
        (let [base {:paths [simple-feature] :step-paths [fixture-steps]
                    :config-path config-path :dry-run true}
              {:keys [out]} (run-captured (assoc base :edn true))
              {:keys [err]} (run-captured base)]
          (is (not (contains? (edn/read-string out) :run/reports-not-written)))
          (is (empty? (notice-lines err))))))))

;; -----------------------------------------------------------------------------
;; sl-3bqv — framework-owned closed sets error instead of ignoring
;; -----------------------------------------------------------------------------

(deftest svo-level-typo-is-a-planning-error-on-both-surfaces
  (testing "run --dry-run: :warm → exit 2 naming :config/invalid-svo-level with
            did-you-mean — used to flow through as non-blocking"
    (with-project {:svo {:unknown-subject :warm}}
      (fn [p]
        (let [{:keys [result summary]} (run-dry-edn p)
              msg (-> summary :planning :errors first :message)]
          (is (= 2 (:exit-code result)))
          (is (= [:config/invalid-svo-level] (planning-error-types summary)))
          (is (str/includes? msg "did you mean :warn?"))))))
  (testing "orient: exit 1 with the [error/config] line"
    (with-project {:svo {:unknown-verb :errror}}
      (fn [p]
        (let [{:keys [code out]} (orient p)]
          (is (= 1 code))
          (is (str/includes? out "[error/config] :config/invalid-svo-level")))))))

(deftest hook-entry-unknown-key-is-a-planning-error
  (testing "an unknown key in a hooks.clj entry → exit 2 :hooks/unknown-key
            (was stripped silently)"
    (with-project {:runner {:step-paths ["steps/"]}}
      (fn [{:keys [dir] :as p}]
        (spit (str (fs/path dir "sl" "hooks.clj"))
              "(ns hooks)\n(def hooks [{:name \"a\" :before (fn [_] nil) :requires_serial true}])")
        (let [{:keys [result summary]} (run-dry-edn p)]
          (is (= 2 (:exit-code result)))
          (is (= [:hooks/unknown-key] (planning-error-types summary)))
          (is (str/includes? (-> summary :planning :errors first :message) ":requires_serial")))))))
