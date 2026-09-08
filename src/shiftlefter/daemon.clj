(ns shiftlefter.daemon
  "Warm execution path: per-project daemon serving CLI dispatches over the
   bundled nREPL server. Design: notes/sl-n2h/warm-execution-path-design.md.

   VERSION-INTERNAL: this namespace and the daemon.edn format are a private
   protocol between bin/sl and the jar it ships with. They are NOT part of
   the locked 0.5 user surface and may change in any release. The locked
   surface is the `sl daemon serve|status|stop` commands, `--no-daemon`,
   and SL_NO_DAEMON.

   Wiring note for implementers: shiftlefter.core must NOT require this ns
   at the top level — the `daemon` command branch in core/dispatch does a
   runtime (require 'shiftlefter.daemon), same pattern as repl-cmd's nREPL
   require, so the cold path never pays for it. THIS ns may freely require
   core and nrepl.server: it is only ever loaded inside a serving JVM."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [clojure.java.io :as jio]
            [babashka.fs :as fs]
            [nrepl.server :as nrepl]
            [nrepl.transport :as transport]
            [shiftlefter.core :as core]
            [shiftlefter.paths :as paths]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.costume :as costume]
            [shiftlefter.runner.report.edn :as report-edn]
            [shiftlefter.runner.verdict :as verdict]
            [shiftlefter.intent.state :as intent-state])
  (:import [java.io File PrintWriter]
           [java.nio.file Files CopyOption StandardCopyOption]
           [java.util.concurrent Executors ScheduledExecutorService
            ScheduledFuture TimeUnit ThreadFactory]
           [java.util.concurrent.locks ReentrantLock]
           [java.time Instant]))

;; -----------------------------------------------------------------------------
;; Specs — request/response protocol
;; -----------------------------------------------------------------------------

(s/def ::argv
  ;; Exactly the argv the cold CLI would receive. Never an eval form: agents
  ;; get one invocation grammar, warm or cold.
  (s/coll-of string? :kind vector? :min-count 1))

(s/def ::cwd
  ;; Absolute path of the caller's working directory. dispatch! binds
  ;; shiftlefter.paths/*user-cwd* to this for the duration of the run, so one
  ;; daemon serves every subdirectory of a project correctly. (A JVM cannot
  ;; chdir; this seam is the replacement.) Custom gen: random strings are almost
  ;; never absolute paths, so `such-that` alone can't satisfy the predicate —
  ;; build absolute paths directly from alphanumeric segments.
  (s/with-gen
    (s/and string? #(.isAbsolute (java.io.File. ^String %)))
    #(gen/fmap (fn [s] (str "/tmp/" s)) (gen/string-alphanumeric))))

(s/def ::request
  (s/keys :req-un [::argv ::cwd]))

(s/def ::exit
  ;; The CLI exit-code contract (shiftlefter.runner.verdict, sl-7c7i):
  ;; 0=pass, 1=fail, 2=planning error, 3=crash, 4=run degraded.
  verdict/exit-codes)

(s/def ::response
  ;; stdout/stderr are NOT in the response — they stream as nREPL :out/:err
  ;; messages so the client preserves the stdout=EDN / stderr=console split
  ;; and can emit progressively during long runs.
  (s/keys :req-un [::exit]))

;; -----------------------------------------------------------------------------
;; Specs — daemon.edn port/metadata file (.shiftlefter/daemon.edn)
;; -----------------------------------------------------------------------------

(s/def ::port pos-int?)
(s/def ::pid pos-int?)
(s/def ::jar-path string?)
;; mtime+size, NOT a content hash: the wrapper stats these on every call and
;; the jar is ~42MB (hashing would cost more than a warm dispatch).
(s/def ::jar-mtime pos-int?)
(s/def ::jar-size pos-int?)
(s/def ::java string?)            ;; path of the java executable that spawned us
(s/def ::started-at string?)      ;; ISO-8601
(s/def ::idle-timeout-min pos-int?)

(s/def ::port-file
  (s/keys :req-un [::port ::pid ::jar-path ::jar-mtime ::jar-size
                   ::java ::started-at ::idle-timeout-min]))

;; -----------------------------------------------------------------------------
;; Daemon control state (singleton per JVM)
;; -----------------------------------------------------------------------------
;;
;; DEFONCE INVENTORY (`rg defonce src/`, audited sl-rju 2026-06-13) — the
;; per-dispatch reset story. dispatch! resets exactly the global state that
;; *accumulates* across runs; everything else is either immutable/idempotent or
;; not on the CLI path. When you add a new `defonce` atom anywhere in src/, decide
;; which bucket it lands in and, if it accumulates, reset it in dispatch! below.
;;
;;   RESET PER DISPATCH (accumulating user state):
;;     - stepengine.registry/registry-atom  -> registry/clear-registry!
;;     - intent.state/intents-atom           -> intent-state/clear-intents!
;;   SAFE TO PERSIST (immutable / idempotent caches, identical every run):
;;     - gherkin.dialect/*  (defonce delays over Cucumber i18n.json — pure data)
;;     - webdriver.playwright.browser/imports-loaded?  (one-shot import latch)
;;   NOT ON THE CLI DISPATCH PATH (REPL-API only):
;;     - repl/{session-ctx,named-contexts,connected-subjects,repl-config}
;;   DAEMON-SCOPED USER STATE, PERSISTENT BY DESIGN (sl-s35y, Warden W6):
;;     - costume/live-drivers — a worn costume's driver surviving between
;;       warm invocations IS costume persistence working (sl-tss7/sl-34fu);
;;       NEVER reset per dispatch. Reapers: costume destroy/relaunch flows,
;;       `sl daemon stop`, JVM exit. Inspect: `sl daemon status`
;;       (custody-status! below).
;;   DAEMON CONTROL (this ns — lifecycle, not user state; reset by serve!):
;;     - daemon-state, shutdown-hook-registered (below)
;;     - the per-serve! session ledger + :sweep-marked strike set (sl-ntlx) live
;;       INSIDE daemon-state, not as defonces — recreated by each serve!
;;
;; Scope today: airtight across invocations of ONE project (per-instance daemon).
;; The post-0.5 jar-anchored-shared-daemon option (one JVM, many projects) would
;; raise the bar to "airtight across DIFFERENT projects": any cache keyed by
;; anything other than *user-cwd* becomes a cross-project leak. This inventory is
;; what makes that future evaluation cheap — keep it exhaustive.

(def ^:private default-idle-timeout-min 60)

(def ^:private default-session-sweep-ms
  "Orphan-session sweep period (sl-ntlx). Version-internal constant, not a
   user knob: a leaked session is reaped within at most two periods."
  (* 5 60 1000))

(defonce ^{:private true
           :doc "Active daemon control, or nil. Singleton per JVM (one daemon
                 per instance root; this ns loads only in a serving JVM). Keys:
                 :server :scheduler :timeout-ms :edn-file :done :idle-gen
                 :idle-future :session-ledger :sweep-marked. serve! replaces it
                 on each start; stop clears it."}
  daemon-state (atom nil))

(defonce ^{:private true
           :doc "CAS latch: register the daemon.edn-cleanup shutdown hook exactly
                 once per JVM (tests start/stop serve! repeatedly)."}
  shutdown-hook-registered (atom false))

;; Two locks, each with exactly one job (sl-luhn; split at sl-vjve W25):
;;
;;   dispatch-lock — ONE RUN AT A TIME. Held for the whole of dispatch!;
;;   global registries make concurrent dispatch unsafe (parallelism is
;;   post-0.5, F6). The idle-reap task and stop! take it BLOCKING, so a reap
;;   can never fire mid-dispatch and a graceful stop waits for the in-flight
;;   run. dispatch! itself only TRY-acquires (sl-luhn, Chair-ratified): a
;;   second invocation against a busy daemon is REJECTED with exit 2 /
;;   :daemon/busy instead of queueing silently — a silent queue is
;;   indistinguishable from a hang for the agents this surface serves.
;;   Only another RUN can ever hold this lock against a dispatch: the
;;   orphan sweep never acquires it (it used to tryLock it per tick, and a
;;   dispatch landing on a tick was told "another run is in progress" — a
;;   lie; Warden W25, repro notes/warden/0.5.6-batch-audit/busy-race.clj).
;;
;;   sweep-gate — ADMISSION vs SWEEP. Serializes the instant a dispatch is
;;   admitted (its dispatch-lock tryLock) against one sweep tick's
;;   strike-and-close, so a tick can never close a session a dispatch is
;;   about to run on (nREPL's close interrupt-stops the session thread
;;   mid-eval — the sweep stays abort-neutral by construction). dispatch!
;;   holds it only across that tryLock — released before running or
;;   rejecting, so a second run never queues behind a first. A tick holds
;;   it for one strike-and-close: microseconds, since the close is an
;;   interrupt with no join.
;;
;;   LOCK ORDER: sweep-gate, then dispatch-lock — never the reverse.
;;   dispatch! takes the gate, then TRIES the run lock; the tick takes the
;;   gate and only READS the run lock's state (isLocked), never acquiring
;;   it. TICK NEVER BLOCKS: every lock the sweep touches is a tryLock, and
;;   a tick with nothing ledgered takes no lock at all — the shared
;;   scheduler thread (which also serves the idle reaper) is never parked,
;;   and the split cannot deadlock by construction.
(def ^:private ^ReentrantLock dispatch-lock (ReentrantLock.))
(def ^:private ^ReentrantLock sweep-gate (ReentrantLock.))

;; -----------------------------------------------------------------------------
;; Helpers — filesystem / metadata
;; -----------------------------------------------------------------------------

(defn- print-throwable!
  "Print a stack trace to the Clojure *err* var (NOT System/err), so it streams
   as an nREPL :err message to the warm client and reaches a direct caller's err."
  [^Throwable t]
  (.printStackTrace t (PrintWriter. ^java.io.Writer *err* true)))

(defn- instance-root
  "Daemon anchor resolved through project-context/instance-root (the rule shared
   with bin/sl find_instance_root, sl-v7l6). Nil when nothing anchors — serve!
   then falls back to user-cwd, where the wrapper runs cold anyway."
  ([] (instance-root (paths/user-cwd)))
  ([start]
   (project-context/instance-root
    (project-context/resolve {:invocation-root start}))))

(defn- self-jar
  "The jar this code loaded from, or nil in dev/test (a classes directory, not a
   jar). Production `sl daemon serve` always runs from the uberjar."
  ^File []
  (try
    (let [src (.. (Class/forName "shiftlefter.core")
                  getProtectionDomain getCodeSource getLocation)]
      (when src
        (let [f (File. (.toURI src))]
          (when (and (.isFile f) (str/ends-with? (.getName f) ".jar")) f))))
    (catch Throwable _ nil)))

(defn- jar-meta
  "Resolve {:jar-path :jar-mtime :jar-size} from (:jar-path opts) override else
   the running uberjar. Throws if neither resolves to a real file — dev/test must
   pass :jar-path so daemon.edn stays spec-valid."
  [opts]
  (let [^File f (or (some-> ^String (:jar-path opts) File.) (self-jar))]
    (when-not (and f (.isFile f))
      (throw (ex-info "serve!: cannot resolve jar path (dev/test must pass :jar-path)"
                      {:type :daemon/no-jar :jar-path (:jar-path opts)})))
    {:jar-path  (.getCanonicalPath f)
     :jar-mtime (.lastModified f)
     :jar-size  (.length f)}))

(defn- java-exe []
  (str (fs/path (System/getProperty "java.home") "bin" "java")))

(defn- write-edn!
  "Write `data` to `file` atomically: spit to a temp sibling, then ATOMIC_MOVE
   over the target. A reader never sees a half-written daemon.edn."
  [^File file data]
  (let [dir (.getParentFile file)]
    ;; daemon.edn's parent is the instance root's .shiftlefter — self-ignore
    ;; at the creation site (sl-7dq5 F9): a hand-copied jar's first run must
    ;; leave git status clean, not only an installer-scaffolded project.
    (project-context/ensure-self-ignore! (str dir))
    (let [tmp (File/createTempFile "daemon" ".edn" dir)]
      (spit tmp (pr-str data))
      (Files/move (.toPath tmp) (.toPath file)
                  (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                          StandardCopyOption/REPLACE_EXISTING])))))

(defn- delete-edn! [^File f]
  (when (and f (.exists f)) (.delete f)))

(defn- emit-breadcrumb!
  "One factual line to *err* on spawn, suppressed when output is not a terminal
   (CI/scripts stay clean). System/console is the best-effort isatty probe pure
   Java offers pre-JDK22: non-nil only when stdin AND stdout are a console."
  []
  (when (System/console)
    (binding [*out* *err*]
      (println "ShiftLefter daemon started — sl daemon status"))))

;; -----------------------------------------------------------------------------
;; Idle timer + lifecycle
;; -----------------------------------------------------------------------------

(defn- scheduler ^ScheduledExecutorService []
  (Executors/newSingleThreadScheduledExecutor
   (reify ThreadFactory
     (newThread [_ r]
       (doto (Thread. ^Runnable r "sl-daemon-idle") (.setDaemon true))))))

(declare stop-daemon!)

(defn- idle-fire!
  "Idle-reap candidate for generation `gen`. Takes the dispatch lock (so it can't
   fire mid-dispatch) and reaps only if `gen` is still current — a dispatch that
   landed after this task was scheduled bumps the generation and supersedes it."
  [gen]
  (.lock dispatch-lock)
  (try
    (let [st @daemon-state]
      (when (and st (= gen (:idle-gen st)))
        (stop-daemon! "idle timeout")))
    (finally (.unlock dispatch-lock))))

(defn- reset-idle-timer!
  "Cancel the pending reap and schedule a fresh one. No-op when no daemon is
   active (dispatch! is callable directly in tests, with no serve! running)."
  []
  (when-let [st @daemon-state]
    (when-let [^ScheduledFuture old (:idle-future st)]
      (.cancel old false))
    (let [gen (inc (:idle-gen st 0))
          ^ScheduledExecutorService sched (:scheduler st)
          fut (.schedule sched ^Runnable (fn [] (idle-fire! gen))
                         (long (:timeout-ms st)) TimeUnit/MILLISECONDS)]
      (swap! daemon-state assoc :idle-gen gen :idle-future fut))))

(defn- stop-daemon!
  "Idempotent shutdown: cancel the timer, stop the scheduler + nREPL server,
   delete daemon.edn, unblock serve!, clear the singleton. Called by the idle
   reaper (under the dispatch lock) and — via stop! — by an external stop."
  [reason]
  (when-let [st @daemon-state]
    (reset! daemon-state nil)
    (when-let [^ScheduledFuture f (:idle-future st)] (.cancel f false))
    (when-let [^ScheduledExecutorService sched (:scheduler st)] (.shutdownNow sched))
    (when-let [server (:server st)] (nrepl/stop-server server))
    (delete-edn! (:edn-file st))
    (deliver (:done st) reason)))

;; -----------------------------------------------------------------------------
;; Orphan-session sweep (sl-ntlx)
;; -----------------------------------------------------------------------------
;;
;; A warm client killed mid-invocation (Ctrl-C — docs/daemon.md § Aborting a
;; run) never sends its W5 `op close`, and nREPL does not close sessions on
;; transport close: one retained session + parked SessionThread per interrupted
;; invocation. The reap must happen daemon-side, and only while the session is
;; IDLE — in nREPL 1.7.0 a session close interrupt-stops its SessionThread even
;; mid-eval, so a close fired at kill time would abort the still-running
;; dispatch (abort semantics are sl-617t's design work; this sweep is
;; deliberately abort-neutral). An idle orphan's thread parks in queue.take and
;; exits cleanly on the interrupt.
;;
;; Two safety mechanisms:
;;   - LEDGER, not a registry sweep: nREPL's session registry is JVM-global
;;     (and private). serve! wraps its server's handler to record only the
;;     sessions cloned THROUGH THIS DAEMON — an in-JVM embedder (the test
;;     fixture; a dev REPL sharing the JVM) can never have ITS sessions reaped.
;;   - TWO-STRIKE close under the sweep-gate: a session is closed only when
;;     seen at two consecutive ticks that each found the run lock FREE (read,
;;     never taken — see the lock comment block). An in-flight dispatch holds
;;     the run lock (ticks skip), a fresh clone is at most one strike old, and
;;     the strike set is rebuilt from the ledger every successful tick, so ids
;;     that close normally are pruned, never re-swept. Every reaped id is
;;     named on the daemon's stderr (Warden W26): a reap is rare and a minor
;;     sign of ill health — a client died mid-run without closing its session.
;;     Any session on this port that is not a warm client is subject to the
;;     same two-strike rule, mid-eval included (docs/daemon.md § Aborting).

(defn- clone-sniffing-transport
  "Delegate to `inner`, recording any :new-session id (a clone response) into
   `ledger` on the way out."
  [inner ledger]
  (reify transport/Transport
    (recv [_] (transport/recv inner))
    (recv [_ timeout] (transport/recv inner timeout))
    (send [this msg]
      (when-let [id (:new-session msg)]
        (swap! ledger conj id))
      (transport/send inner msg)
      this)))

(def ^:private noop-transport
  "Transport for sweep-synthesized ops: responses are dropped, nothing recv'd."
  (reify transport/Transport
    (recv [_] nil)
    (recv [_ _timeout] nil)
    (send [this _msg] this)))

(defn- wrap-session-ledger
  "Wrap the daemon server's nREPL handler so `ledger` (atom of a set) tracks
   exactly the session ids this daemon created: clone responses add (via a
   sniffing transport — the id is only in the response), close requests remove.
   At the wrapper's altitude :session is still the raw id string; the session
   middleware resolves it to the session object further in."
  [handler ledger]
  (fn [{:keys [op session] :as msg}]
    (case op
      "clone" (handler (update msg :transport clone-sniffing-transport ledger))
      "close" (do (when (string? session)
                    (swap! ledger disj session))
                  (handler msg))
      (handler msg))))

(defn- reap-line
  "The one stderr line per reaped session (Warden W26). Rare by design, and
   a minor sign of ill health: the only way a session earns two strikes is
   that its client died mid-run without closing it."
  [id]
  (str "sl daemon: reaped orphaned session " id
       " — its client exited without closing it (Ctrl-C, a killed process,"
       " or a cancelled CI job)"))

(defn- sweep-orphan-sessions!
  "One sweep tick: close every ledgered session seen at the previous
   successful tick too (two strikes), naming each reaped id on *err*.

   Ledger empty => nothing to do and no lock touched (the daemon's usual
   idle state). Otherwise TRY the sweep-gate — a dispatch mid-admission wins
   and the tick skips — and under it READ dispatch-lock: a run in flight
   skips the tick too (strikes never advance during a run; reaping can
   wait). The tick never acquires dispatch-lock and never blocks — the
   lock comment block above is the contract. Closes ride the public close
   op through the daemon's own wrapped handler, which prunes the ledger.
   The outer catch is load-bearing: scheduleWithFixedDelay silently cancels
   the task on an escaped exception — the sweep must never throw."
  []
  (try
    (when-let [st @daemon-state]
      (let [ledger (:session-ledger st)]
        (when (and (seq @ledger) (.tryLock sweep-gate))
          (try
            (when-not (.isLocked dispatch-lock)
              (let [handler (:handler (:server st))
                    doomed  (filter (:sweep-marked st #{}) @ledger)]
                (doseq [id doomed]
                  (handler {:op "close" :session id :id "sl-daemon-sweep"
                            :transport noop-transport})
                  (binding [*out* *err*]
                    (println (reap-line id))))
                (swap! daemon-state assoc :sweep-marked @ledger)))
            (finally (.unlock sweep-gate))))))
    (catch Throwable t
      (print-throwable! t))))

(defn custody-status!
  "Print this daemon's costume custody (sl-s35y, Warden W6 ruled): one
   line per live costume held by this JVM, off costume/custody-snapshot.
   Read-only — the inspection half of the persistence design (a worn
   costume surviving between warm invocations IS costume persistence
   working, sl-tss7; `sl daemon stop` releases them). Evaluated by the
   wrapper's `sl daemon status` via the --sl-internal-status sentinel."
  []
  (let [entries (costume/custody-snapshot)]
    (if (empty? entries)
      (println "live costumes: none")
      (do
        (println (str "live costumes (" (count entries) ") — held by this"
                      " daemon; released by `sl daemon stop` (or costume"
                      " destroy/relaunch):"))
        (doseq [{:keys [costume driver-alive?]} entries]
          (println (str "  " costume " — chromedriver "
                        (case driver-alive?
                          true "alive"
                          false "DEAD (stale handle)"
                          "unknown"))))))
    (flush)))

(defn stop!
  "Stop the running daemon if any (test/programmatic teardown). External callers
   normally kill the pid from daemon.edn; the shutdown hook then deletes the file.
   Returns the stop reason, or nil if no daemon was running."
  []
  (.lock dispatch-lock)
  (try
    (when @daemon-state (stop-daemon! "stopped"))
    (finally (.unlock dispatch-lock))))

(defn- ensure-shutdown-hook!
  "Register, once per JVM, a hook that deletes daemon.edn on exit — the backstop
   for an external `sl daemon stop` that kills the pid (sl-x6r). Reads the current
   daemon-state at exit time, so one hook covers every serve!/stop cycle."
  []
  (when (compare-and-set! shutdown-hook-registered false true)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (some-> @daemon-state :edn-file delete-edn!))))))

;; -----------------------------------------------------------------------------
;; API
;; -----------------------------------------------------------------------------

(def ^:private busy-message
  "another run is in progress for this project; retry when it completes")

(defn- reject-busy!
  "Busy reject (sl-luhn, Chair-ratified over message-and-wait): the dispatch
   lock is held by an in-flight invocation, so this one exits 2 with the
   structured error :daemon/busy instead of queueing silently. Output goes
   to the *out*/*err* bound at eval time (the nREPL session's streaming
   writers since sl-qwf): with --edn in
   argv, the existing exit-2 planning-summary shape carries the error on
   stdout (stderr stays silent, matching report-planning-error!'s
   suppressed-under-edn convention); otherwise one stderr line. No
   :run/seed — nothing was seeded (ruled at plan verdict, consistent with
   the sl-08f7 runner-path guarantee). Exit 2 rides the normal value path,
   NEVER 69: a 69 would make bin/sl retry cold and create the exact
   mixed warm+cold concurrency this reject exists to prevent."
  [argv]
  (let [err {:type :daemon/busy :message busy-message}]
    (if (some #(= "--edn" %) argv)
      (report-edn/prn-summary
       (report-edn/build-summary (str (java.util.UUID/randomUUID)) 2 nil
                                 {:diagnostics {:errors [err]}}))
      (binding [*out* *err*]
        (println (str "sl: " busy-message))))
    {:exit 2}))

(defn dispatch!
  "Execute one CLI invocation inside the warm JVM. Returns ::response.

   Contract (in order):
   1. Serialize — one dispatch at a time (dispatch-lock, TRY-acquire); global
      registries make concurrent dispatch unsafe (parallelism is post-0.5, F6).
      Lock busy => REJECT, exit 2 with :daemon/busy (sl-luhn; see reject-busy!).
      The tryLock happens under sweep-gate (taken blocking, released the
      instant the try resolves — before running or rejecting), so an orphan
      sweep tick can never masquerade as a busy run and a tick can never
      close the session this dispatch is about to run on (sl-vjve W25; lock
      order sweep-gate -> dispatch-lock, see the lock comment block).
      Uniform across all warm commands by construction — per-command queueing
      would re-introduce the silent block. The reject path does NOT touch the
      idle timer: reset-idle-timer! mutates daemon-state unsynchronized, and
      the in-flight dispatch resets it on completion anyway.
   2. Reset accumulating global state: registry/clear-registry! +
      intent-state/clear-intents!. Persistent-safe caches (gherkin.dialect delays,
      playwright import latch) are left alone. The full defonce inventory and the
      reset rationale live in this ns's `daemon-state` comment block — keep it
      there when a new global atom is added.
   3. Bind shiftlefter.paths/*user-cwd* to (:cwd request) for the call.
   4. Call shiftlefter.core/dispatch (the de-exited -main body). `repl` (and, as
      belt-and-suspenders to the wrapper's denylist, `daemon serve`) is rejected
      with exit 2 BEFORE dispatch — a daemon cannot host an interactive session;
      a :repl sentinel leaking back is also coerced to 2.
   5. Catch Throwable -> {:exit 3} (the locked crash code), stack to *err*.
   6. Reset the idle timer (in a finally — any dispatch, even a rejected one,
      counts as activity)."
  [request]
  (.lock sweep-gate)
  (if-not (try (.tryLock dispatch-lock) (finally (.unlock sweep-gate)))
    (reject-busy! (:argv request))
    (try
      (let [argv (:argv request)
            cmd  (first argv)]
        (cond
          (= cmd "repl")
          {:exit 2}

          (and (= cmd "daemon") (= (second argv) "serve"))
          {:exit 2}

          :else
          (do
            (registry/clear-registry!)
            (intent-state/clear-intents!)
            (let [result (binding [paths/*user-cwd* (:cwd request)]
                           (core/dispatch argv))]
              {:exit (if (= :repl result) 2 result)}))))
      (catch Throwable t
        (print-throwable! t)
        {:exit 3})
      (finally
        (reset-idle-timer!)
        (.unlock dispatch-lock)))))

(s/fdef dispatch!
  :args (s/cat :request ::request)
  :ret ::response)

(defn serve!
  "Start the daemon and BLOCK until idle-reaped or stopped; return exit code 0.

   Plain nREPL server (default handler, NO CIDER middleware — that's dev-REPL
   freight). Writes .shiftlefter/daemon.edn atomically at the instance root and
   NEVER touches .nrepl-port: the dev REPL and the daemon coexist (resolves the
   sl-y3c friction note). Arms the idle timer (default 60 min). Idle expiry,
   `stop!`, and an external pid-kill all delete daemon.edn.

   No System/exit here — the JVM is owned by `-main`, which exits 0 when serve!
   returns. That is also what lets the in-JVM test fixture run serve! on a thread,
   drive it, and idle-reap it without killing the test JVM.

   On spawn, emit ONE breadcrumb to *err* (tty-gated): `ShiftLefter daemon started
   — sl daemon status`. Never on warm dispatches — the wrapper stays silent there.

   Also arms the orphan-session sweep (sl-ntlx): a periodic tick on the same
   scheduler that two-strike-closes sessions whose warm client died without
   sending the W5 close. See the sweep section above for the safety story.

   opts (all optional):
     :idle-timeout-min N  user-facing knob (default 60); recorded in daemon.edn.
     :idle-timeout-ms M   TEST-ONLY seam — sub-second reap, bypasses the minute
                          granularity. Not a CLI flag, not in the locked surface.
     :session-sweep-ms M  TEST-ONLY seam — orphan-sweep period (default 5 min).
                          Not a CLI flag, not in the locked surface.
     :root DIR            instance root override (default: resolved anchor, else
                          user-cwd). Tests point this at a temp dir.
     :jar-path PATH       jar metadata override; required in dev/test where there
                          is no uberjar to introspect.
     :port P              nREPL port (default 0 ⇒ OS-assigned, no collisions)."
  [opts]
  (let [timeout-min (or (:idle-timeout-min opts) default-idle-timeout-min)
        timeout-ms  (or (:idle-timeout-ms opts) (* (long timeout-min) 60 1000))
        sweep-ms    (long (or (:session-sweep-ms opts) default-session-sweep-ms))
        root        (or (:root opts) (instance-root) (paths/user-cwd))
        edn-file    (jio/file root ".shiftlefter" "daemon.edn")
        jm          (jar-meta opts)
        ledger      (atom #{})
        server      (nrepl/start-server :port (or (:port opts) 0)
                                        :handler (wrap-session-ledger
                                                  (nrepl/default-handler) ledger))
        sched       (scheduler)
        done        (promise)]
    (write-edn! edn-file
                (merge {:port             (:port server)
                        :pid              (.pid (java.lang.ProcessHandle/current))
                        :java             (java-exe)
                        :started-at       (str (Instant/now))
                        :idle-timeout-min (long timeout-min)}
                       jm))
    (reset! daemon-state {:server         server
                          :scheduler      sched
                          :timeout-ms     timeout-ms
                          :edn-file       edn-file
                          :done           done
                          :idle-gen       0
                          :idle-future    nil
                          :session-ledger ledger
                          :sweep-marked   #{}})
    (ensure-shutdown-hook!)
    (reset-idle-timer!)
    ;; Orphan-session sweep tick (sl-ntlx). Cancelled by stop-daemon!'s
    ;; scheduler shutdownNow; deliberately NO sweep at stop — the stop! eval's
    ;; own session is mid-eval, and closing it would interrupt-stop the thread
    ;; running the shutdown. The tick runs under the *err* serve! was called
    ;; with (the daemon's own stderr — daemon.log under bin/sl; a captured
    ;; writer in the in-JVM harness), so reap lines (W26) land where the
    ;; daemon's stderr goes rather than on the scheduler thread's root binding.
    (let [err *err*]
      (.scheduleWithFixedDelay sched
                               ^Runnable (fn [] (binding [*err* err]
                                                  (sweep-orphan-sessions!)))
                               sweep-ms sweep-ms TimeUnit/MILLISECONDS))
    (emit-breadcrumb!)
    @done       ;; block until idle-reaped or stopped
    0))

(s/fdef serve!
  :args (s/cat :opts (s/nilable (s/keys :opt-un [::idle-timeout-min])))
  :ret ::exit)
