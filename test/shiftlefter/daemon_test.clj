(ns shiftlefter.daemon-test
  "Standing in-JVM tests for the warm-execution daemon (sl-rju). The
   cross-process / shell-through-bin-sl layer is sl-7wv; this ns owns the
   in-JVM half. `call-with-daemon` is the reusable harness — start serve! on a
   temp root, drive it, tear down — that sl-7wv and future in-process drivers
   can lean on instead of bespoke verification."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [babashka.fs :as fs]
            [clojure.java.io :as jio]
            [clojure.java.shell :as shell]
            [nrepl.core :as nrepl]
            [nrepl.server :as nrepl-server]
            [nrepl.transport :as transport]
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
   teardown is explicit. `:err` (a java.io.Writer) captures the daemon's own
   stderr — the sweep's reap lines ride the *err* serve! was called with
   (sl-vjve W26) — instead of swallowing it."
  [opts f]
  (let [root (or (:root opts) (str (fs/create-temp-dir)))
        jar  (or (:jar-path opts)
                 (let [j (str (fs/path root "fake.jar"))] (spit j "x") j))
        edn  (jio/file root ".shiftlefter" "daemon.edn")
        serve-opts (merge {:idle-timeout-ms 600000}
                          (dissoc opts :err)
                          {:root root :jar-path jar :port 0})
        fut  (future (binding [*out* (java.io.StringWriter.)
                               *err* (or (:err opts) (java.io.StringWriter.))]
                       (daemon/serve! serve-opts)))]
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
;; dispatch! — contention: busy = REJECT, never a silent queue (sl-luhn)
;; -----------------------------------------------------------------------------

(defn- capturing
  "Run thunk with *out*/*err* captured; return {:ret r :out s :err s}."
  [thunk]
  (let [ow (java.io.StringWriter.)
        ew (java.io.StringWriter.)]
    (binding [*out* ow *err* ew]
      (let [r (thunk)]
        {:ret r :out (str ow) :err (str ew)}))))

(deftest dispatch-busy-rejects-second-invocation
  ;; tryLock semantics at the unit level (the cross-process half lives in the
  ;; ^:daemon warm-e2e suite): while dispatch A executes a run whose step
  ;; blocks on a release file, dispatch B is rejected with exit 2 /
  ;; :daemon/busy — stderr one-liner bare, the exit-2 planning-summary shape
  ;; under --edn — and A completes undisturbed. Marker paths are ABSOLUTE:
  ;; the step runs with the test JVM's cwd, not the bound *user-cwd*.
  (let [proj    (str (fs/create-temp-dir))
        started (str (fs/path proj "STARTED"))
        release (str (fs/path proj "RELEASE"))
        sdir    (str (fs/path proj "steps"))]
    (fs/create-dirs sdir)
    (spit (str (fs/path proj "f.feature"))
          "Feature: F\n\n  Scenario: S\n    Given a slow thing\n")
    (spit (str (fs/path sdir "slow.clj"))
          (str "(ns slow (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
               "(defstep #\"a slow thing\" []\n"
               "  (spit \"" started "\" \"x\")\n"
               "  (loop [i 0]\n"
               "    (when (and (< i 300)\n"
               "               (not (.exists (java.io.File. \"" release "\"))))\n"
               "      (Thread/sleep 50)\n"
               "      (recur (inc i)))))\n"))
    (let [fut (future (silently #(daemon/dispatch!
                                  {:argv ["run" "f.feature" "--step-paths" "steps"]
                                   :cwd proj})))]
      (try
        (is (wait-for #(.exists (jio/file started)) 15000)
            "precondition: the first run is executing its blocking step")
        (testing "bare busy reject: exit 2 + one stderr line"
          (let [{:keys [ret err]} (capturing #(daemon/dispatch!
                                              {:argv ["fmt" "--check" "f.feature"]
                                               :cwd proj}))]
            (is (= {:exit 2} ret) "second dispatch rejected, not queued")
            (is (re-find #"another run is in progress" err)
                "stderr carries the busy one-liner")))
        (testing "--edn busy reject: exit-2 planning-summary shape on stdout"
          (let [{:keys [ret out err]} (capturing #(daemon/dispatch!
                                                  {:argv ["run" "f.feature" "--edn"]
                                                   :cwd proj}))
                summary (edn/read-string out)]
            (is (= {:exit 2} ret))
            (is (= 2 (:run/exit-code summary)))
            (is (= :planning-failed (:run/status summary)))
            (is (= :daemon/busy (-> summary :planning :errors first :type))
                "structured error type rides the planning block")
            (is (not (re-find #"another run is in progress" err))
                "stderr line suppressed under --edn (machine contract)")))
        (spit release "go")
        (is (= {:exit 0} (deref fut 30000 :timeout))
            "first run completed undisturbed by the rejected dispatches")
        (finally
          (spit release "go")
          (deref fut 30000 :timeout)
          (fs/delete-tree proj))))))

;; -----------------------------------------------------------------------------
;; serve! — orphan-session sweep (sl-ntlx)
;; -----------------------------------------------------------------------------
;;
;; Wire-level tests against the daemon's real nREPL port. All session
;; assertions are MEMBERSHIP, never emptiness: nREPL's session registry is
;; JVM-global, so when this suite runs inside a REPL JVM other sessions
;; legitimately coexist.

(defn- nrepl-sessions
  "Session-id set via a fresh ls-sessions exchange on `port`."
  [port]
  (with-open [conn (nrepl/connect :port port)]
    (-> (nrepl/client conn 5000)
        (nrepl/message {:op "ls-sessions"})
        nrepl/combine-responses
        :sessions
        set)))

(defn- clone-session
  "Clone a session over an open nREPL connection; return its id."
  [client]
  (:new-session (nrepl/combine-responses (nrepl/message client {:op "clone"}))))

(deftest sweep-reaps-orphaned-session
  ;; The sl-ntlx scenario end-to-end: a client clones a session and dies
  ;; without sending close (abrupt socket drop = the Ctrl-C kill). The
  ;; two-strike sweep must close it; nothing else reaps it (server.clj only
  ;; closes the transport on EOF).
  (call-with-daemon {:session-sweep-ms 150}
    (fn [{:keys [data]}]
      (let [port (:port data)
            orphan (with-open [conn (nrepl/connect :port port)]
                     (let [client (nrepl/client conn 5000)
                           id (clone-session client)]
                       (is (contains? (set (:sessions (nrepl/combine-responses
                                                       (nrepl/message client {:op "ls-sessions"}))))
                           id)
                           "precondition: session registered before the kill")
                       id))]
        ;; with-open dropped the socket with no close op — the kill.
        (is (string? orphan))
        (is (wait-for #(not (contains? (nrepl-sessions port) orphan)) 5000)
            "two-strike sweep reaps the orphaned session")))))

(deftest sweep-spares-active-dispatch-and-closed-sessions
  ;; A real warm-shaped exchange under an aggressive sweep: the dispatching
  ;; session must survive its own run (in-flight dispatch holds the lock, so
  ;; ticks skip), deliver its value intact, and vanish promptly on the
  ;; well-behaved W5 close — no sweep involved.
  (let [proj (str (fs/create-temp-dir))
        sdir (str (fs/path proj "steps"))]
    (fs/create-dirs sdir)
    (spit (str (fs/path proj "f.feature"))
          "Feature: F\n\n  Scenario: S\n    Given a napping thing\n")
    (spit (str (fs/path sdir "nap.clj"))
          (str "(ns nap (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
               "(defstep #\"a napping thing\" [] (Thread/sleep 400))\n"))
    (call-with-daemon {:session-sweep-ms 50}
      (fn [{:keys [data]}]
        (let [port (:port data)]
          (with-open [conn (nrepl/connect :port port)]
            (let [client (nrepl/client conn 30000)
                  id     (clone-session client)
                  code   (str "(shiftlefter.daemon/dispatch! {:argv [\"run\" \"f.feature\""
                              " \"--step-paths\" \"steps\"] :cwd " (pr-str proj) "})")
                  resp   (nrepl/combine-responses
                          (nrepl/message client {:op "eval" :session id :code code}))]
              (is (= "{:exit 0}" (first (:value resp)))
                  "run outlives many sweep periods and returns its value intact")
              (nrepl/combine-responses (nrepl/message client {:op "close" :session id}))
              (is (not (contains? (nrepl-sessions port) id))
                  "well-behaved close removes the session immediately"))))))))

(deftest sweep-only-reaps-daemon-ledgered-sessions
  ;; The ledger boundary: a session cloned on a DIFFERENT nREPL server in the
  ;; same JVM shares the global registry but was never cloned through the
  ;; daemon's handler — the sweep must never touch it, however long it leaks.
  (with-open [other (nrepl-server/start-server :port 0)]
    (call-with-daemon {:session-sweep-ms 50}
      (fn [{:keys [data]}]
        (let [foreign (with-open [conn (nrepl/connect :port (:port other))]
                        (clone-session (nrepl/client conn 5000)))]
          (Thread/sleep 300) ;; several sweep periods
          (is (contains? (nrepl-sessions (:port data)) foreign)
              "session cloned outside the daemon's handler is never swept")
          ;; hygiene: close the foreign session so the test JVM doesn't leak it
          (with-open [conn (nrepl/connect :port (:port other))]
            (nrepl/combine-responses
             (nrepl/message (nrepl/client conn 5000)
                            {:op "close" :session foreign}))))))))

;; -----------------------------------------------------------------------------
;; sl-vjve (Warden W25/W26/W28) — the sweep's lock discipline, pinned
;; -----------------------------------------------------------------------------

(defn- orphan-session!
  "Clone a session on `port` and drop the socket without a close op — the
   Ctrl-C kill shape. Returns the orphaned id."
  [port]
  (with-open [conn (nrepl/connect :port port)]
    (clone-session (nrepl/client conn 5000))))

(deftest sweep-two-strike-invariant
  ;; W28: the invariant the sweep section documents but nothing asserted —
  ;; a fresh clone SURVIVES the first tick (one strike) and is gone after the
  ;; second. 200 ms ticks; sampled at 250 ms (inside tick 2's window, after
  ;; tick 1 has run) and 500 ms (after tick 2).
  (call-with-daemon {:session-sweep-ms 200}
    (fn [{:keys [data]}]
      (let [port (:port data)
            id   (orphan-session! port)]
        (Thread/sleep 250)
        (is (contains? (nrepl-sessions port) id)
            "one strike: a session seen at a single tick is never closed")
        (Thread/sleep 250)
        (is (not (contains? (nrepl-sessions port) id))
            "two strikes: gone after the second tick")))))

(deftest sweep-never-busy-rejects-a-dispatch
  ;; W25: the sweep tick used to tryLock dispatch-lock, so a dispatch landing
  ;; on a tick was rejected with "another run is in progress" — a lie
  ;; (Warden's repro: 1 spurious reject in 3000 under a 1 ms sweep). The tick
  ;; now takes only the sweep-gate and READS the run lock. Deliberately run
  ;; with a live session in the ledger — the warm client's own session is
  ;; always ledgered, so the empty-ledger short-circuit alone would not
  ;; cover the real path.
  (call-with-daemon {:session-sweep-ms 1}
    (fn [{:keys [data]}]
      (with-open [conn (nrepl/connect :port (:port data))]
        (let [client (nrepl/client conn 5000)
              _held  (clone-session client)
              n      500
              busy   (->> (repeatedly n #(capturing
                                          (fn [] (daemon/dispatch! {:argv ["--version"]
                                                                    :cwd (abs-cwd)}))))
                          (filter #(str/includes? (:err %) "another run is in progress"))
                          count)]
          (is (zero? busy)
              (str busy " of " n " dispatches were busy-rejected by the sweep tick")))))))

(deftest sweep-reap-names-each-session-on-stderr
  ;; W26: a reap was silent — nothing named the session the daemon closed.
  ;; Exactly one line per reaped id, on the daemon's own stderr (the *err*
  ;; serve! was called with; daemon.log under bin/sl).
  (let [err (java.io.StringWriter.)]
    (call-with-daemon {:session-sweep-ms 100 :err err}
      (fn [{:keys [data]}]
        (let [port (:port data)
              id   (orphan-session! port)]
          (is (wait-for #(not (contains? (nrepl-sessions port) id)) 5000)
              "precondition: the orphan was reaped")
          (let [lines (->> (str/split-lines (str err))
                           (filter #(str/includes? % "reaped orphaned session")))]
            (is (= 1 (count lines)) (str "one reap line expected; stderr:\n" err))
            (is (str/includes? (first lines) id) "the line names the reaped id")
            (is (str/includes? (first lines) "without closing it")
                "and says what it means: the client died mid-run")))))))

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

;; -----------------------------------------------------------------------------
;; sl-78u4 (Warden W1) — the Java dispatch client READS the session-close ack
;; -----------------------------------------------------------------------------

(def ^:private client-jar
  ;; The client class rides the uberjar (build.clj b/javac); the same env
  ;; override warm_e2e_test honors points this at a packaged artifact.
  (or (System/getenv "SL_E2E_JAR")
      (str (fs/path (System/getProperty "user.dir") "bin" "shiftlefter.jar"))))

(deftest ^:daemon dispatch-client-waits-for-the-close-ack
  ;; The deterministic twin of warm_e2e_test/daemon-log-stays-empty-after-
  ;; warm-runs, which can only catch the race on a tick the daemon loses.
  ;; Here the daemon's own handler DELAYS the close op and probes the
  ;; client's socket before answering. The sl-xjx0 client (no ack-wait) has
  ;; already closed its socket — the probe write throws, the very failure
  ;; nREPL logged to daemon.log on healthy warm runs. The sl-78u4 client is
  ;; still reading for the ack: the probe lands. The real jar's client is
  ;; what gets exercised, against this JVM's daemon.
  (if-not (fs/exists? client-jar)
    (is true "skipped — no built jar (build: clj -T:build uberjar)")
    (let [probe (atom [])
          ledger-var (ns-resolve 'shiftlefter.daemon 'wrap-session-ledger)
          orig @ledger-var
          delayed (fn [handler ledger]
                    (let [wrapped (orig handler ledger)]
                      (fn [{:keys [op] :as msg}]
                        (when (= "close" op)
                          (Thread/sleep 250)
                          (swap! probe conj
                                 (try (transport/send (:transport msg)
                                                      {:id "probe" :status ["probe"]})
                                      :open
                                      (catch Throwable _ :closed))))
                        (wrapped msg))))]
      (with-redefs-fn {ledger-var delayed}
        (fn []
          (call-with-daemon {}
            (fn [{:keys [root data]}]
              (spit (str (fs/path root "ok.feature"))
                    "Feature: F\n\n  Scenario: S\n    Given a thing\n")
              (let [java (str (fs/path (System/getProperty "java.home") "bin" "java"))
                    {:keys [exit err]} (shell/sh java "-cp" client-jar
                                                 "shiftlefter.client.NreplClient"
                                                 (str (:port data)) root
                                                 "fmt" "--check" "ok.feature")]
                (is (= 0 exit) (str "warm fmt --check through the jar's client; stderr: " err))
                (is (wait-for #(seq @probe) 3000) "the daemon saw the close op")
                (is (= [:open] @probe)
                    "the client's socket is still open when the daemon answers the close — it waited for the ack")))))))))
