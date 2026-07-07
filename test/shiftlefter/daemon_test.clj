(ns shiftlefter.daemon-test
  "Standing in-JVM tests for the warm-execution daemon (sl-rju). The
   cross-process / shell-through-bin-sl layer is sl-7wv; this ns owns the
   in-JVM half. `call-with-daemon` is the reusable harness — start serve! on a
   temp root, drive it, tear down — that sl-7wv and future in-process drivers
   can lean on instead of bespoke verification."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [babashka.fs :as fs]
            [clojure.java.io :as jio]
            [shiftlefter.core :as core]
            [shiftlefter.daemon :as daemon]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.intent.state :as intent-state]))

;; -----------------------------------------------------------------------------
;; Fixtures / helpers
;; -----------------------------------------------------------------------------

(use-fixtures :once
  (fn [f]
    ;; Enforce the request/response + daemon.edn contracts at test time: every
    ;; dispatch!/serve! call below is checked against its fdef.
    (stest/instrument `[daemon/dispatch! daemon/serve!])
    (try (f)
         (finally (stest/unstrument `[daemon/dispatch! daemon/serve!])))))

(defn- silently
  "Run thunk with *out*/*err* swallowed — the daemon's command output is the
   subject of other layers, not these exit-code assertions."
  [thunk]
  (binding [*out* (java.io.StringWriter.)
            *err* (java.io.StringWriter.)]
    (thunk)))

(defn- wait-for
  "Poll pred up to timeout-ms; return true if it became truthy."
  [pred timeout-ms]
  (loop [waited 0]
    (cond
      (pred) true
      (>= waited timeout-ms) false
      :else (do (Thread/sleep 20) (recur (+ waited 20))))))

(defn call-with-daemon
  "Reusable in-JVM daemon harness. Starts serve! on a background thread over a
   temp instance root (with a throwaway jar so daemon.edn is spec-valid), waits
   for daemon.edn, then calls (f ctx) and tears down via stop!.

   ctx keys: :root (string), :edn-file (java.io.File), :data (parsed daemon.edn).
   opts are merged into the serve! call (e.g. override :idle-timeout-ms). The
   default idle timeout is large so the harness never self-reaps mid-test —
   teardown is explicit."
  [opts f]
  (let [root (or (:root opts) (str (fs/create-temp-dir)))
        jar  (or (:jar-path opts)
                 (let [j (str (fs/path root "fake.jar"))] (spit j "x") j))
        edn  (jio/file root ".shiftlefter" "daemon.edn")
        serve-opts (merge {:idle-timeout-ms 600000} opts
                          {:root root :jar-path jar :port 0})
        fut  (future (silently #(daemon/serve! serve-opts)))]
    (try
      (when-not (wait-for #(.exists edn) 4000)
        (throw (ex-info "daemon.edn never appeared" {:root root})))
      (f {:root root :edn-file edn :data (edn/read-string (slurp edn))})
      (finally
        (daemon/stop!)
        (deref fut 4000 :timeout)))))

(defn- abs-cwd [] (System/getProperty "user.dir"))

;; -----------------------------------------------------------------------------
;; serve! — daemon.edn + .nrepl-port coexistence
;; -----------------------------------------------------------------------------

(deftest serve-writes-spec-valid-daemon-edn
  (call-with-daemon {}
    (fn [{:keys [data]}]
      (is (s/valid? :shiftlefter.daemon/port-file data)
          (s/explain-str :shiftlefter.daemon/port-file data))
      (is (pos-int? (:port data)) "OS-assigned port recorded")
      (is (= 60 (:idle-timeout-min data)) "default idle timeout in minutes"))))

(deftest serve-never-touches-nrepl-port
  ;; The dev REPL's .nrepl-port must survive a daemon start/stop — the daemon
  ;; writes only .shiftlefter/daemon.edn, never .nrepl-port (sl-y3c friction).
  (let [root (str (fs/create-temp-dir))
        sentinel (jio/file root ".nrepl-port")]
    (spit sentinel "12345")
    (call-with-daemon {:root root}
      (fn [_]
        (is (.exists sentinel) ".nrepl-port survives daemon start")
        (is (= "12345" (slurp sentinel)) ".nrepl-port content untouched")))
    (is (.exists sentinel) ".nrepl-port survives daemon stop")
    (is (= "12345" (slurp sentinel)))
    (is (not (.exists (jio/file root ".shiftlefter" "daemon.edn")))
        "daemon.edn deleted on stop")))

;; -----------------------------------------------------------------------------
;; dispatch! — isolation (registry/intents do not accumulate)
;; -----------------------------------------------------------------------------

(deftest dispatch-resets-accumulating-global-state
  ;; Pollute the two accumulating registries, then run any dispatch and assert
  ;; they were cleared — the per-dispatch reset contract (step 2), directly.
  (registry/register! (re-pattern "daemon-test-sentinel-step")
                      (fn [_] nil)
                      {:ns 'daemon-test :file "daemon_test.clj" :line 1})
  (intent-state/reload-intents! (str (fs/create-temp-dir)))
  (is (seq (registry/all-stepdefs)) "precondition: registry polluted")
  (is (intent-state/intents-loaded?) "precondition: intents loaded")
  (silently #(daemon/dispatch! {:argv ["--help"] :cwd (abs-cwd)}))
  (is (empty? (registry/all-stepdefs)) "registry cleared by dispatch!")
  (is (not (intent-state/intents-loaded?)) "intents cleared by dispatch!"))

(deftest dispatch-edited-stepdef-binds-new-definition
  ;; The literal acceptance scenario: edit a stepdef between two dispatches; the
  ;; second binds the NEW file. If the registry accumulated, the stale "step
  ;; alpha" definition would still match and the second run would wrongly pass.
  (let [proj (str (fs/create-temp-dir))
        sdir (str (fs/path proj "steps"))
        sd   (str (fs/path sdir "s.clj"))
        run  (fn [] (silently #(daemon/dispatch!
                                {:argv ["run" "f.feature" "--step-paths" "steps" "--dry-run"]
                                 :cwd proj})))]
    (fs/create-dirs sdir)
    (spit (str (fs/path proj "f.feature"))
          "Feature: F\n\n  Scenario: S\n    Given step alpha\n")
    (spit sd "(ns s (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n(defstep #\"step alpha\" [] nil)\n")
    (is (= {:exit 0} (run)) "first dispatch: stepdef matches, binds")
    (spit sd "(ns s (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n(defstep #\"step beta\" [] nil)\n")
    (is (= {:exit 2} (run))
        "second dispatch: edited stepdef no longer matches; old def did not persist")))

;; -----------------------------------------------------------------------------
;; dispatch! — per-request *user-cwd* binding
;; -----------------------------------------------------------------------------

(deftest dispatch-binds-user-cwd-per-request
  (let [proj (str (fs/create-temp-dir))
        sub  (str (fs/path proj "sub"))]
    (fs/create-dirs sub)
    (spit (str (fs/path sub "x.feature"))
          "Feature: F\n\n  Scenario: S\n    Given a thing\n")
    (testing "relative path resolves against the bound cwd (the subdir)"
      (is (= {:exit 0}
             (silently #(daemon/dispatch! {:argv ["fmt" "--check" "x.feature"] :cwd sub})))))
    (testing "same relative path from the parent cwd does NOT resolve (proves cwd drives it)"
      (is (= {:exit 2}
             (silently #(daemon/dispatch! {:argv ["fmt" "--check" "x.feature"] :cwd proj})))))))

(deftest dispatch-resolves-project-context-per-request
  (let [mk-project (fn [step-text]
                     (let [proj (str (fs/create-temp-dir))]
                       (fs/create-dirs (fs/path proj "sl" "steps"))
                       (spit (str (fs/path proj "sl" "shiftlefter.edn"))
                             (pr-str {:runner {:step-paths ["steps"]
                                               :allow-pending? false}}))
                       (spit (str (fs/path proj "sl" "steps" "steps.clj"))
                             (str "(ns steps.generated\n"
                                  "  (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
                                  "(defstep #\"" step-text "\" [] nil)\n"))
                       (spit (str (fs/path proj "f.feature"))
                             (str "Feature: F\n\n  Scenario: S\n    Given " step-text "\n"))
                       proj))
        proj-a (mk-project "alpha project step")
        proj-b (mk-project "beta project step")]
    (try
      (is (= {:exit 0}
             (silently #(daemon/dispatch! {:argv ["run" "f.feature" "--dry-run"]
                                            :cwd proj-a})))
          "first request uses project A's sl/ config root")
      (is (= {:exit 0}
             (silently #(daemon/dispatch! {:argv ["run" "f.feature" "--dry-run"]
                                            :cwd proj-b})))
          "second request recomputes context and uses project B's sl/ config root")
      (finally
        (fs/delete-tree proj-a)
        (fs/delete-tree proj-b)))))

;; -----------------------------------------------------------------------------
;; dispatch! — exit parity vs cold
;; -----------------------------------------------------------------------------

(deftest dispatch-exit-parity-with-cold
  (let [tmp  (str (fs/create-temp-dir))
        good (str (fs/path tmp "good.feature"))
        bad  (str (fs/path tmp "bad.feature"))
        cwd  (abs-cwd)
        warm (fn [argv] (silently #(daemon/dispatch! {:argv argv :cwd cwd})))
        cold (fn [argv] (silently #(core/dispatch argv)))]
    (spit good "Feature: F\n\n  Scenario: S\n    Given a thing\n")
    (spit bad  "this is not gherkin at all {{{\n")
    (doseq [[label argv] {:pass    ["fmt" "--check" good]
                          :fail    ["fmt" "--check" bad]
                          :planning ["fmt" "--check" (str (fs/path tmp "missing.feature"))]
                          ;; sl-von: unknown command is exit 2 cold; warm must match.
                          :unknown ["bogus-command"]}]
      (testing (str "cold==warm for " label)
        (is (= {:exit (cold argv)} (warm argv))
            (str label ": warm {:exit n} mirrors cold n"))))
    (testing "all four exit codes are spec-valid responses"
      (is (every? #(s/valid? :shiftlefter.daemon/response (warm %))
                  [["fmt" "--check" good] ["fmt" "--check" bad] ["bogus"]])))
    (testing "crash → exit 3 (warm-only by contract: cold -main also maps uncaught → 3,
              but that path lives in -main's System/exit and can't run in-process)"
      (with-redefs [core/dispatch (fn [_] (throw (RuntimeException. "boom")))]
        (is (= {:exit 3} (silently #(daemon/dispatch! {:argv ["run" "x"] :cwd cwd}))))))))

(deftest dispatch-rejects-interactive-commands
  (let [cwd (abs-cwd)]
    (is (= {:exit 2} (silently #(daemon/dispatch! {:argv ["repl"] :cwd cwd})))
        "repl cannot run in the daemon")
    (is (= {:exit 2} (silently #(daemon/dispatch! {:argv ["daemon" "serve"] :cwd cwd})))
        "daemon serve cannot recurse into the daemon")))

;; -----------------------------------------------------------------------------
;; serve! — idle timeout reaps and cleans up
;; -----------------------------------------------------------------------------

(deftest idle-timeout-reaps-and-cleans-up
  (let [root (str (fs/create-temp-dir))
        jar  (let [j (str (fs/path root "fake.jar"))] (spit j "x") j)
        edn  (jio/file root ".shiftlefter" "daemon.edn")
        fut  (future (silently #(daemon/serve! {:root root :jar-path jar
                                                :idle-timeout-ms 300 :port 0})))]
    (try
      (is (wait-for #(.exists edn) 3000) "daemon.edn written on start")
      (is (= 0 (deref fut 3000 :timeout)) "serve! returns 0 after idle reap")
      (is (not (.exists edn)) "daemon.edn deleted on idle reap")
      (finally
        (daemon/stop!)
        (deref fut 1000 :timeout)))))
