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
            [shiftlefter.core :as core]
            [shiftlefter.paths :as paths]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.intent.state :as intent-state])
  (:import [java.io File PrintWriter]
           [java.nio.file Files CopyOption StandardCopyOption]
           [java.util.concurrent Executors ScheduledExecutorService
            ScheduledFuture TimeUnit ThreadFactory]
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
  ;; Locked CLI exit-code contract: 0=pass, 1=fail, 2=planning error, 3=crash.
  #{0 1 2 3})

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
;;     - repl/{session-ctx,named-contexts,surfaces,connected-subjects,repl-config}
;;   DAEMON CONTROL (this ns — lifecycle, not user state; reset by serve!):
;;     - daemon-state, shutdown-hook-registered (below)
;;
;; Scope today: airtight across invocations of ONE project (per-instance daemon).
;; The post-0.5 jar-anchored-shared-daemon option (one JVM, many projects) would
;; raise the bar to "airtight across DIFFERENT projects": any cache keyed by
;; anything other than *user-cwd* becomes a cross-project leak. This inventory is
;; what makes that future evaluation cheap — keep it exhaustive.

(def ^:private default-idle-timeout-min 60)

(defonce ^{:private true
           :doc "Active daemon control, or nil. Singleton per JVM (one daemon
                 per instance root; this ns loads only in a serving JVM). Keys:
                 :server :scheduler :timeout-ms :edn-file :done :idle-gen
                 :idle-future. serve! replaces it on each start; stop clears it."}
  daemon-state (atom nil))

(defonce ^{:private true
           :doc "CAS latch: register the daemon.edn-cleanup shutdown hook exactly
                 once per JVM (tests start/stop serve! repeatedly)."}
  shutdown-hook-registered (atom false))

;; One monitor serializes dispatch!; global registries make concurrent dispatch
;; unsafe (parallelism is post-0.5, F6). The idle-reap task also takes this lock,
;; so a reap can never fire mid-dispatch.
(def ^:private dispatch-lock (Object.))

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
    (.mkdirs dir)
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
  (locking dispatch-lock
    (let [st @daemon-state]
      (when (and st (= gen (:idle-gen st)))
        (stop-daemon! "idle timeout")))))

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

(defn stop!
  "Stop the running daemon if any (test/programmatic teardown). External callers
   normally kill the pid from daemon.edn; the shutdown hook then deletes the file.
   Returns the stop reason, or nil if no daemon was running."
  []
  (locking dispatch-lock
    (when @daemon-state (stop-daemon! "stopped"))))

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

(defn dispatch!
  "Execute one CLI invocation inside the warm JVM. Returns ::response.

   Contract (in order):
   1. Serialize — one dispatch at a time (dispatch-lock); global registries make
      concurrent dispatch unsafe (parallelism is post-0.5, F6).
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
  (locking dispatch-lock
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
        (reset-idle-timer!)))))

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

   opts (all optional):
     :idle-timeout-min N  user-facing knob (default 60); recorded in daemon.edn.
     :idle-timeout-ms M   TEST-ONLY seam — sub-second reap, bypasses the minute
                          granularity. Not a CLI flag, not in the locked surface.
     :root DIR            instance root override (default: resolved anchor, else
                          user-cwd). Tests point this at a temp dir.
     :jar-path PATH       jar metadata override; required in dev/test where there
                          is no uberjar to introspect.
     :port P              nREPL port (default 0 ⇒ OS-assigned, no collisions)."
  [opts]
  (let [timeout-min (or (:idle-timeout-min opts) default-idle-timeout-min)
        timeout-ms  (or (:idle-timeout-ms opts) (* (long timeout-min) 60 1000))
        root        (or (:root opts) (instance-root) (paths/user-cwd))
        edn-file    (jio/file root ".shiftlefter" "daemon.edn")
        jm          (jar-meta opts)
        server      (nrepl/start-server :port (or (:port opts) 0)
                                        :handler (nrepl/default-handler))
        done        (promise)]
    (write-edn! edn-file
                (merge {:port             (:port server)
                        :pid              (.pid (java.lang.ProcessHandle/current))
                        :java             (java-exe)
                        :started-at       (str (Instant/now))
                        :idle-timeout-min (long timeout-min)}
                       jm))
    (reset! daemon-state {:server     server
                          :scheduler  (scheduler)
                          :timeout-ms timeout-ms
                          :edn-file   edn-file
                          :done       done
                          :idle-gen   0
                          :idle-future nil})
    (ensure-shutdown-hook!)
    (reset-idle-timer!)
    (emit-breadcrumb!)
    @done       ;; block until idle-reaped or stopped
    0))

(s/fdef serve!
  :args (s/cat :opts (s/nilable (s/keys :opt-un [::idle-timeout-min])))
  :ret ::exit)
