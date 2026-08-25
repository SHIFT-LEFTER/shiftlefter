(ns shiftlefter.warm-e2e-test
  "Standing regression suite for the warm execution path AT THE SHELL LAYER
   (sl-7wv). Every deftest here shells out to the `sl` wrapper as a subprocess and
   asserts on its exit code / stdout / stderr / the .shiftlefter/daemon.edn
   breadcrumb — the cross-process surface (bash wrapper + spawned daemon JVM +
   socket/bencode + NreplClient + stream multiplexing) that the in-JVM
   daemon-test (sl-rju) cannot reach.

   WHICH wrapper: by default the in-repo `bin/sl` against its flat dev jar
   (bin/shiftlefter.jar). The release zip ships that SAME script (sl-72e), so
   `packaged-wrapper-warm-path` below stages a release-shaped flat dir and proves
   the warm path engages in the PACKAGED artifact — the gap that let a stale,
   pre-warm wrapper false-green. The whole battery can also be pointed at an
   extracted artifact via SL_E2E_WRAPPER / SL_E2E_JAR (used by the 0.5 release
   acceptance bead, sl-release-acceptance-0-5-kun).

   OPT-IN — these spawn real daemon JVMs, so they are NOT in the bare
   `./bin/kaocha` run. Invoke explicitly:

       ./bin/kaocha daemon          # needs a built target/shiftlefter.jar

   Without a jar every test self-skips green (so the suite is safe to invoke on
   a fresh checkout). Each test runs in an isolated temp instance root and reaps
   its daemon on teardown — no shared state, no leaked JVMs.

   The 'index' of which tests belong here is the ^:daemon tag itself; add a
   tagged deftest and it joins the suite (tests.edn :daemon suite, no registry)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [nrepl.core :as nrepl]))

;; -----------------------------------------------------------------------------
;; Paths / gating
;; -----------------------------------------------------------------------------

(def ^:private project-root (System/getProperty "user.dir"))

;; The wrapper + jar under test. Default to the in-repo flat layout — bin/sl loads
;; the bin/shiftlefter.jar symlink that `clj -T:build uberjar` writes (sl-72e).
;; Override both via env to run the SAME battery against an EXTRACTED packaged
;; artifact (release acceptance); the assertions are layout-agnostic.
(def ^:private sl-bin
  (or (System/getenv "SL_E2E_WRAPPER")
      (str (fs/path project-root "bin" "sl"))))
(def ^:private jar-file
  (or (System/getenv "SL_E2E_JAR")
      (str (fs/path project-root "bin" "shiftlefter.jar"))))

(defn- jar-present? [] (.exists (io/file jar-file)))

(defn- skip-without-jar
  "Run body-thunk only when a built jar exists; otherwise record a green skip.
   The whole suite is meaningless against `clojure -M:run` source mode — it
   exercises the JAR-mode warm routing in bin/sl specifically."
  [body-thunk]
  (if (jar-present?)
    (body-thunk)
    (is true "skipped — no target/shiftlefter.jar (build: clj -T:build uberjar)")))

(defn- command-exists? [cmd]
  (zero? (:exit (sh "sh" "-c" (str "command -v " cmd)))))

;; -----------------------------------------------------------------------------
;; Shell-out harness
;; -----------------------------------------------------------------------------

(defn- env-with
  "The inherited process environment with `overrides` applied. clojure.java.shell
   REPLACES the env wholesale when :env is given, so we must merge in PATH/JAVA
   ourselves or bin/sl can't find java."
  [overrides]
  (merge (into {} (System/getenv)) overrides))

(defn- run-sl
  "Shell out to bin/sl from working directory `dir`, returning {:exit :out :err}.
   opts: :env (map merged over the inherited environment, used to set SL_NO_DAEMON),
   :bytes? (capture :out as a raw byte[] for byte-identity checks)."
  [dir args & {:keys [env bytes?]}]
  (let [opts (concat [:dir dir]
                     (when env [:env (env-with env)])
                     (when bytes? [:out-enc :bytes]))]
    (apply sh (concat [sl-bin] args opts))))

(defn- daemon-edn-file [root] (io/file root ".shiftlefter" "daemon.edn"))

(defn- read-daemon
  "Parsed .shiftlefter/daemon.edn for `root`, or nil if no breadcrumb exists."
  [root]
  (let [f (daemon-edn-file root)]
    (when (.exists f) (edn/read-string (slurp f)))))

;; -----------------------------------------------------------------------------
;; Fixtures (written into each temp instance root)
;; -----------------------------------------------------------------------------

(def ^:private good-feature  "Feature: F\n\n  Scenario: S\n    Given a thing\n")
(def ^:private messy-feature "Feature: F\n  Scenario: S\n      Given a thing\n")
;; Multibyte (CJK + astral-plane emoji) — the sl-y3c flush-bug regression guard.
(def ^:private emoji-feature "Feature: 機能 😀\n\n  Scenario: シナリオ\n    Given a 日本語 thing 🎉\n")
(def ^:private ok-feature    "Feature: F\n\n  Scenario: S\n    Given a thing happens\n")
(def ^:private step-src
  (str "(ns s (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
       "(defstep #\"a thing happens\" [] nil)\n"))

(defn- write-feature-fixtures
  "Populate `root` with the standard feature/step fixtures WITHOUT a
   shiftlefter.edn anchor (the config-less first-time-user shape, sl-v7l6)."
  [root]
  (spit (str (fs/path root "good.feature")) good-feature)
  (spit (str (fs/path root "messy.feature")) messy-feature)
  (spit (str (fs/path root "emoji.feature")) emoji-feature)
  (spit (str (fs/path root "ok.feature")) ok-feature)
  (fs/create-dirs (str (fs/path root "steps")))
  (spit (str (fs/path root "steps" "s.clj")) step-src))

(defn- write-fixtures
  "Feature fixtures + the sl/shiftlefter.edn anchor that makes `root` an
   instance root for find_instance_root (the single discovered layout, sl-fcjm)."
  [root]
  (fs/create-dirs (str (fs/path root "sl")))
  (spit (str (fs/path root "sl" "shiftlefter.edn")) "{}\n")
  (write-feature-fixtures root))

(defn- mk-root []
  (let [root (str (fs/create-temp-dir))]
    (write-fixtures root)
    root))

(defn- reap!
  "Stop any daemon for `root` and delete the tree. Best-effort — teardown must
   never mask a test failure."
  [root]
  (try (run-sl root ["daemon" "stop"]) (catch Exception _ nil))
  (try (fs/delete-tree root) (catch Exception _ nil)))

(defn- with-root
  "Run (f root) against a fresh, fixture-populated temp instance root, reaping
   the daemon + deleting the tree afterwards."
  [f]
  (let [root (mk-root)]
    (try (f root) (finally (reap! root)))))

(defn- time-ms [thunk]
  (let [t0 (System/nanoTime)]
    (thunk)
    (quot (- (System/nanoTime) t0) 1000000)))

;; -----------------------------------------------------------------------------
;; 1. Cold -> warm transition: first call spawns, second reuses the SAME daemon
;; -----------------------------------------------------------------------------

(deftest ^:daemon cold-to-warm-reuse
  (skip-without-jar
   (fn []
     (with-root
      (fn [root]
        (let [r1 (run-sl root ["fmt" "--check" "good.feature"])
              d1 (read-daemon root)
              r2 (run-sl root ["fmt" "--check" "good.feature"])
              d2 (read-daemon root)]
          (is (= 0 (:exit r1)))
          (is (= 0 (:exit r2)))
          (is (some? d1) "first call spawned a daemon + wrote daemon.edn")
          (is (some? d2))
          (is (= (:pid d1) (:pid d2)) "second call reused the same pid (no re-spawn)")
          (is (= (:port d1) (:port d2)) "second call reused the same port")))))))

;; -----------------------------------------------------------------------------
;; 2. Exit-code parity warm vs cold for 0 / 1 / 2 / 3 (+ 4 below, sl-7c7i)
;; -----------------------------------------------------------------------------

(deftest ^:daemon exit-code-parity-warm-vs-cold
  (skip-without-jar
   (fn []
     (with-root
      (fn [root]
        (doseq [[label args expected]
                [[:pass     ["fmt" "--check" "good.feature"]    0]
                 [:fail     ["fmt" "--check" "messy.feature"]   1]
                 [:planning ["fmt" "--check" "missing.feature"] 2]
                 [:crash    ["--sl-internal-crash"]             3]]]
          (testing (str label " (expect exit " expected ")")
            (let [warm (run-sl root args)
                  cold (run-sl root args :env {"SL_NO_DAEMON" "1"})]
              (is (= expected (:exit warm)) "warm exit")
              (is (= expected (:exit cold)) "cold exit")
              (is (= (:exit cold) (:exit warm)) "warm == cold")))))))))

(deftest ^:daemon exit-4-parity-warm-vs-cold
  ;; sl-7c7i: run degraded — a scenario :error (here: a Before hook throw)
  ;; exits 4 on both paths. Rides its own fixtures: sl/hooks.clj is
  ;; sibling-of-config and would otherwise be visible to every run in the
  ;; shared root fixture set.
  (skip-without-jar
   (fn []
     (with-root
      (fn [root]
        (spit (str (fs/path root "sl" "hooks.clj"))
              (str "(ns hooks)\n"
                   "(def hooks [{:name \"boom\""
                   " :before (fn [_] (throw (ex-info \"db down\" {})))}])\n"))
        (spit (str (fs/path root "deg.feature"))
              "Feature: D\n\n  @hook=boom\n  Scenario: S\n    Given a thing happens\n")
        (let [args ["run" "deg.feature" "--step-paths" "steps"]
              warm (run-sl root args)
              cold (run-sl root args :env {"SL_NO_DAEMON" "1"})]
          (is (= 4 (:exit warm)) "warm exit")
          (is (= 4 (:exit cold)) "cold exit")))))))

;; -----------------------------------------------------------------------------
;; 3. stdout = EDN / stderr = console separation preserved warm
;; -----------------------------------------------------------------------------

(deftest ^:daemon stdout-edn-stderr-separated-warm
  (skip-without-jar
   (fn []
     (with-root
      (fn [root]
        (let [r (run-sl root ["run" "ok.feature" "--step-paths" "steps" "--edn"])
              parsed (try (edn/read-string (:out r)) (catch Exception _ ::unparseable))]
          (is (= 0 (:exit r)))
          (is (map? parsed) "stdout is one clean, parseable EDN map")
          (is (= :passed (:run/status parsed)) "the EDN carries the run result")
          (is (not (re-find #"\{:run/" (:err r)))
              "the EDN result did not leak onto stderr (streams stay separate)")))))))

;; -----------------------------------------------------------------------------
;; 4. Multibyte output byte-identical warm vs cold (sl-y3c flush-bug guard)
;; -----------------------------------------------------------------------------

(deftest ^:daemon multibyte-byte-identical-warm-vs-cold
  (skip-without-jar
   (fn []
     (with-root
      (fn [root]
        ;; fmt --canonical is deterministic (no run-id), so raw bytes must match.
        (let [warm (run-sl root ["fmt" "--canonical" "emoji.feature"] :bytes? true)
              cold (run-sl root ["fmt" "--canonical" "emoji.feature"]
                           :env {"SL_NO_DAEMON" "1"} :bytes? true)]
          (is (= 0 (:exit warm)) "warm canonical succeeds")
          (is (= 0 (:exit cold)) "cold canonical succeeds")
          (is (java.util.Arrays/equals ^bytes (:out warm) ^bytes (:out cold))
              "CJK + emoji canonical output is byte-identical warm vs cold")))))))

;; -----------------------------------------------------------------------------
;; 5. SL_NO_DAEMON=1 and --no-daemon both route cold (no spawn, no daemon.edn)
;; -----------------------------------------------------------------------------

(deftest ^:daemon no-daemon-routes-cold
  (skip-without-jar
   (fn []
     (testing "SL_NO_DAEMON=1 env var"
       (with-root
        (fn [root]
          (let [r (run-sl root ["fmt" "--check" "good.feature"] :env {"SL_NO_DAEMON" "1"})]
            (is (= 0 (:exit r)))
            (is (nil? (read-daemon root)) "no daemon.edn — ran cold")))))
     (testing "--no-daemon wrapper flag"
       (with-root
        (fn [root]
          (let [r (run-sl root ["--no-daemon" "fmt" "--check" "good.feature"])]
            (is (= 0 (:exit r)))
            (is (nil? (read-daemon root)) "no daemon.edn — ran cold"))))))))

;; -----------------------------------------------------------------------------
;; 6. Bare --help spawns nothing
;; -----------------------------------------------------------------------------

(deftest ^:daemon bare-help-spawns-nothing
  (skip-without-jar
   (fn []
     (with-root
      (fn [root]
        (let [r (run-sl root ["--help"])]
          (is (= 0 (:exit r)))
          (is (nil? (read-daemon root))
              "printing usage must not boot a daemon")))))))

;; -----------------------------------------------------------------------------
;; 7. sl daemon status / stop behave
;; -----------------------------------------------------------------------------

(deftest ^:daemon daemon-status-and-stop
  (skip-without-jar
   (fn []
     (with-root
      (fn [root]
        (run-sl root ["fmt" "--check" "good.feature"])    ; prime a daemon
        (is (some? (read-daemon root)) "precondition: daemon running")
        (testing "status reports the live daemon"
          (let [s (run-sl root ["daemon" "status"])]
            (is (= 0 (:exit s)))
            (is (re-find #"alive" (:out s)) "status says alive")))
        (testing "stop reaps the daemon + removes daemon.edn"
          (let [s (run-sl root ["daemon" "stop"])]
            (is (re-find #"stopped|killed" (:out s)) "stop reports a kill")
            (is (nil? (read-daemon root)) "daemon.edn removed"))))))))

;; -----------------------------------------------------------------------------
;; 8. Jar rebuild mid-session -> next call transparently bounces (new pid)
;; -----------------------------------------------------------------------------

(deftest ^:daemon jar-rebuild-bounces-daemon
  (skip-without-jar
   (fn []
     (with-root
      (fn [root]
        (run-sl root ["fmt" "--check" "good.feature"])
        (let [d1 (read-daemon root)]
          (is (some? d1) "precondition: daemon running")
          ;; Simulate 'jar was rebuilt after the daemon started' WITHOUT touching
          ;; the real shared jar: back-date daemon.edn so the wrapper's
          ;; `JAR_FILE -nt daemon.edn` staleness check fires and forces a respawn.
          (.setLastModified (daemon-edn-file root) 1000000000)  ; ~2001
          (let [r2 (run-sl root ["fmt" "--check" "good.feature"])
                d2 (read-daemon root)]
            (is (= 0 (:exit r2)))
            (is (some? d2) "a fresh daemon was spawned")
            (is (not= (:pid d1) (:pid d2))
                "stale daemon bounced — new pid on the next call"))))))))

;; -----------------------------------------------------------------------------
;; 9. Dev-REPL coexistence: .nrepl-port untouched while a daemon runs
;; -----------------------------------------------------------------------------

(deftest ^:daemon dev-repl-port-untouched
  (skip-without-jar
   (fn []
     (with-root
      (fn [root]
        (let [sentinel (io/file root ".nrepl-port")]
          (spit sentinel "65432")
          (run-sl root ["fmt" "--check" "good.feature"])   ; spawn a daemon
          (is (some? (read-daemon root)) "daemon wrote its own daemon.edn")
          (is (.exists sentinel) ".nrepl-port survives the daemon")
          (is (= "65432" (slurp sentinel)) ".nrepl-port content untouched")))))))

;; -----------------------------------------------------------------------------
;; 10. Multi-instance: two roots + a git worktree each get their OWN daemon
;;     on distinct ports/pids, fully isolated (sl-n2h analysis)
;; -----------------------------------------------------------------------------

(deftest ^:daemon multi-instance-isolation
  (skip-without-jar
   (fn []
     (let [a         (str (fs/create-temp-dir))
           b         (mk-root)
           wt-parent (str (fs/create-temp-dir))
           w         (str (fs/path wt-parent "wt"))]
       (try
         ;; A is a real git repo so we can hang a linked worktree (W) off it.
         (write-fixtures a)
         (sh "git" "init" "-q" "." :dir a)
         (sh "git" "add" "-A" :dir a)
         (sh "git" "-c" "user.email=t@t" "-c" "user.name=t"
             "commit" "-qm" "init" :dir a)
         (sh "git" "worktree" "add" "-q" w :dir a)   ; W inherits the fixtures
         (run-sl a ["fmt" "--check" "good.feature"])
         (run-sl b ["fmt" "--check" "good.feature"])
         (run-sl w ["fmt" "--check" "good.feature"])
         (let [da (read-daemon a) db (read-daemon b) dw (read-daemon w)]
           (is (every? some? [da db dw]) "each root spawned its own daemon")
           (is (= 3 (count (distinct (map :port [da db dw]))))
               "three DISTINCT ports — no cross-talk")
           (is (= 3 (count (distinct (map :pid [da db dw]))))
               "three DISTINCT daemon processes"))
         (finally
           (reap! a) (reap! b) (reap! w)
           (try (sh "git" "worktree" "prune" :dir a) (catch Exception _ nil))
           (try (fs/delete-tree wt-parent) (catch Exception _ nil))))))))

;; -----------------------------------------------------------------------------
;; 11. Perf smoke: warm fmt is dramatically under cold (a 'warm is warm' canary,
;;     NOT a tight latency gate — that lives in sl-s3u / sl-x6r acceptance)
;; -----------------------------------------------------------------------------

(deftest ^:daemon perf-smoke-warm-under-cold
  (skip-without-jar
   (fn []
     (with-root
      (fn [root]
        (run-sl root ["fmt" "--check" "good.feature"])   ; prime: pay the spawn now
        (let [warm-ms (time-ms #(run-sl root ["fmt" "--check" "good.feature"]))
              cold-ms (time-ms #(run-sl root ["fmt" "--check" "good.feature"]
                                        :env {"SL_NO_DAEMON" "1"}))]
          (is (< warm-ms cold-ms)
              (format "warm %dms should beat cold %dms" warm-ms cold-ms))
          ;; Loose, non-flaky margin: warm skips the whole Clojure boot, so it
          ;; should be a fraction of cold. 0.7 leaves enormous headroom.
          (is (< warm-ms (long (* 0.7 cold-ms)))
              (format "warm %dms is not dramatically under cold %dms"
                      warm-ms cold-ms))))))))

;; -----------------------------------------------------------------------------
;; 12. shellcheck the wrapper scripts in CI (skips if shellcheck absent)
;; -----------------------------------------------------------------------------

(deftest ^:daemon shellcheck-wrapper-scripts
  (if-not (command-exists? "shellcheck")
    (is true "skipped — shellcheck not installed")
    (doseq [script ["bin/sl" "bin/sl-dev" "bin/kaocha"]]
      (testing script
        (let [r (sh "shellcheck" (str (fs/path project-root script)))]
          (is (= 0 (:exit r)) (str "shellcheck " script ":\n" (:out r) (:err r))))))))

;; -----------------------------------------------------------------------------
;; 13. Packaged-artifact warm path (sl-72e): the shipped wrapper, in the flat
;;     release layout, with a VERSIONED jar name, must still spawn + reuse a warm
;;     daemon. This is the assertion that catches a stale/pre-warm packaged
;;     wrapper — the in-repo tests above run bin/sl against bin/shiftlefter.jar
;;     (exact name), so only THIS test exercises flat discovery of a
;;     shiftlefter-vX.Y.Z.jar the way an unzipped release does.
;; -----------------------------------------------------------------------------

(defn- run-wrapper
  "Shell out to an arbitrary `wrapper` path from `dir` (mirrors run-sl but for a
   wrapper outside the repo — the extracted/packaged one)."
  [wrapper dir args & {:keys [env]}]
  (apply sh (concat [wrapper] args
                    [:dir dir]
                    (when env [:env (env-with env)]))))

(deftest ^:daemon packaged-wrapper-warm-path
  (skip-without-jar
   (fn []
     ;; Stage exactly what build.clj's release-zip stages: `sl` + a versioned jar,
     ;; flat. (The zip is pure file-copy over this shape, so staging here has the
     ;; same teeth without a multi-minute AOT rebuild inside the test.)
     (let [stage   (str (fs/create-temp-dir))
           staged-sl (str (fs/path stage "sl"))
           ;; Versioned name on purpose — proves the wrapper's flat glob finds a
           ;; release-named jar, which the dev exact-name jar never tests.
           staged-jar (str (fs/path stage "shiftlefter-v0.0.0-e2e.jar"))]
       (try
         (io/copy (io/file sl-bin) (io/file staged-sl))
         (.setExecutable (io/file staged-sl) true false)
         (io/copy (io/file jar-file) (io/file staged-jar))  ; reads through the symlink
         (with-root
          (fn [root]
            (testing "cold->warm reuse through the packaged wrapper"
              (let [r1 (run-wrapper staged-sl root ["fmt" "--check" "good.feature"])
                    d1 (read-daemon root)
                    r2 (run-wrapper staged-sl root ["fmt" "--check" "good.feature"])
                    d2 (read-daemon root)]
                (is (= 0 (:exit r1)) (str "first call failed: " (:err r1)))
                (is (= 0 (:exit r2)))
                (is (some? d1) "packaged wrapper spawned a daemon (warm path present)")
                (is (some? d2))
                (is (= (:pid d1) (:pid d2)) "second call reused the daemon (warm)")))
            (testing "warm is dramatically under cold (the path actually engages)"
              (let [warm-ms (time-ms #(run-wrapper staged-sl root
                                                   ["fmt" "--check" "good.feature"]))
                    cold-ms (time-ms #(run-wrapper staged-sl root
                                                   ["fmt" "--check" "good.feature"]
                                                   :env {"SL_NO_DAEMON" "1"}))]
                (is (< warm-ms (long (* 0.7 cold-ms)))
                    (format "packaged warm %dms not dramatically under cold %dms"
                            warm-ms cold-ms))))
            ;; reap the daemon spawned via the staged wrapper before the tree dies
            (run-wrapper staged-sl root ["daemon" "stop"])))
         (finally
           (try (fs/delete-tree stage) (catch Exception _ nil))))))))

;; -----------------------------------------------------------------------------
;; 14. Config-less git repo (sl-v7l6): wrapper and daemon must agree on the
;;     instance root. Before the fix, the wrapper waited at the git toplevel
;;     while the daemon wrote daemon.edn at the cwd — every call ate the ~10s
;;     wait_for_edn timeout, ran cold, and the daemon leaked where
;;     `sl daemon stop` couldn't find it.
;; -----------------------------------------------------------------------------

(deftest ^:daemon configless-git-repo-warm-path
  (skip-without-jar
   (fn []
     (let [root (str (fs/create-temp-dir))
           sub  (str (fs/path root "sub"))]
       (try
         (fs/create-dirs sub)
         (write-feature-fixtures sub)          ; NO shiftlefter.edn anywhere
         (sh "git" "init" "-q" "." :dir root)
         (let [r1 (run-sl sub ["fmt" "--check" "good.feature"])
               d1 (read-daemon root)
               r2 (run-sl sub ["fmt" "--check" "good.feature"])
               d2 (read-daemon root)]
           (is (= 0 (:exit r1)))
           (is (some? d1) "daemon.edn lands at the GIT TOPLEVEL, where the wrapper waits")
           (is (nil? (read-daemon sub)) "no stray daemon.edn at the invocation cwd")
           (is (= 0 (:exit r2)))
           (is (= (:pid d1) (:pid d2)) "second call reused the daemon — warm, no stall")
           (testing "stop from the subdir finds and reaps the daemon (no leaked JVM)"
             (let [s (run-sl sub ["daemon" "stop"])]
               (is (re-find #"stopped|killed" (:out s)))
               (is (nil? (read-daemon root)) "daemon.edn removed"))))
         (finally
           (reap! root)))))))

;; -----------------------------------------------------------------------------
;; 15. sl/shiftlefter.edn (the single discovered layout, sl-fcjm) in a subdir of
;;     a git repo: both sides anchor at the CONFIG's project root, not the
;;     enclosing git toplevel (the rule mismatch closed by sl-v7l6).
;; -----------------------------------------------------------------------------

(deftest ^:daemon sl-layout-subdir-anchors-at-config
  (skip-without-jar
   (fn []
     (let [root (str (fs/create-temp-dir))
           sub  (str (fs/path root "proj"))]
       (try
         (fs/create-dirs (str (fs/path sub "sl")))
         (write-feature-fixtures sub)
         (spit (str (fs/path sub "sl" "shiftlefter.edn")) "{}\n")
         (sh "git" "init" "-q" "." :dir root)
         (let [r1 (run-sl sub ["fmt" "--check" "good.feature"])
               d1 (read-daemon sub)
               d2 (read-daemon root)]
           (is (= 0 (:exit r1)))
           (is (some? d1) "daemon.edn at the sl-layout project root (the subdir)")
           (is (nil? d2) "not at the enclosing git toplevel"))
         (finally
           (try (run-sl sub ["daemon" "stop"]) (catch Exception _ nil))
           (try (fs/delete-tree root) (catch Exception _ nil))))))))

;; -----------------------------------------------------------------------------
;; 16. sl/lib/*.jar user classpath extension (sl-5ee5): a dropped jar is USABLE
;;     (not merely present) through both real execution paths — the cold floor
;;     (-cp mode) and the warm daemon JVM — and the daemon bounces when the lib
;;     set changes, same treatment as a jar rebuild.
;; -----------------------------------------------------------------------------

(defn- write-lib-jar!
  "Build a tiny jar at `path` containing one Clojure SOURCE ns, `<ns-seg>.core`,
   whose `marker` fn returns `answer`. Pure JDK (JarOutputStream) — no shell,
   no build tooling, mirroring the zero-build-tooling promise of sl/lib itself.
   Distinct jars need distinct ns segments: classpath wildcard order within a
   dir is alphabetical, so a duplicated ns would shadow."
  [path ns-seg answer]
  (let [src (str "(ns " ns-seg ".core)\n"
                 "(defn marker [] \"" answer "\")\n")]
    (with-open [jos (java.util.jar.JarOutputStream.
                     (io/output-stream (io/file path)))]
      (.putNextEntry jos (java.util.jar.JarEntry. (str ns-seg "/core.clj")))
      (.write jos (.getBytes src "UTF-8"))
      (.closeEntry jos))))

(def ^:private lib-feature
  "Feature: L\n\n  Scenario: S\n    Given the lib probe answers\n")

(def ^:private lib-step-src
  ;; The step's ns requires libprobe.core from the sl/lib jar and THROWS unless
  ;; the marker answers — a green run is only possible with the jar on the
  ;; running JVM's classpath.
  (str "(ns libsteps\n"
       "  (:require [libprobe.core :as probe]\n"
       "            [shiftlefter.stepengine.registry :refer [defstep]]))\n"
       "(defstep #\"the lib probe answers\" []\n"
       "  (when-not (= \"LIB-JAR-LOADED\" (probe/marker))\n"
       "    (throw (ex-info \"lib probe wrong answer\" {}))))\n"))

(defn- write-lib-fixtures!
  "Instance root with sl/lib/probe.jar + a feature/step pair that only passes
   when the lib jar is on the classpath of the JVM executing the run."
  [root]
  (fs/create-dirs (str (fs/path root "sl")))
  (spit (str (fs/path root "sl" "shiftlefter.edn")) "{}\n")
  (spit (str (fs/path root "lib.feature")) lib-feature)
  (fs/create-dirs (str (fs/path root "steps")))
  (spit (str (fs/path root "steps" "libsteps.clj")) lib-step-src)
  (fs/create-dirs (str (fs/path root "sl" "lib")))
  (write-lib-jar! (str (fs/path root "sl" "lib" "probe.jar"))
                  "libprobe" "LIB-JAR-LOADED"))

(deftest ^:daemon lib-jar-usable-cold-and-warm
  (skip-without-jar
   (fn []
     (let [root (str (fs/create-temp-dir))]
       (try
         (write-lib-fixtures! root)
         (testing "cold floor (-cp mode): the step requiring the lib ns passes"
           (let [r (run-sl root ["run" "lib.feature" "--step-paths" "steps"]
                           :env {"SL_NO_DAEMON" "1"})]
             (is (= 0 (:exit r)) (str "cold run failed: " (:err r)))))
         (testing "warm path: the daemon JVM carries sl/lib on ITS classpath"
           (let [r (run-sl root ["run" "lib.feature" "--step-paths" "steps"])]
             (is (= 0 (:exit r)) (str "warm run failed: " (:err r)))
             (is (some? (read-daemon root))
                 "run went through a daemon (breadcrumb present)")))
         (finally (reap! root)))))))

;; -----------------------------------------------------------------------------
;; 17. Contention (sl-luhn): a second invocation against a BUSY daemon is
;;     REJECTED — exit 2 with :daemon/busy (stderr one-liner bare, the exit-2
;;     planning-summary shape under --edn) — and the first run completes
;;     undisturbed on the same daemon (no bounce). Chair-ratified REJECT over
;;     silent queueing: a silent block is indistinguishable from a hang.
;; -----------------------------------------------------------------------------

(defn- wait-for-file
  "Poll for `path` to exist, up to timeout-ms; true if it appeared."
  [path timeout-ms]
  (loop [waited 0]
    (cond
      (.exists (io/file path)) true
      (>= waited timeout-ms) false
      :else (do (Thread/sleep 100) (recur (+ waited 100))))))

(deftest ^:daemon busy-daemon-rejects-second-invocation
  (skip-without-jar
   (fn []
     (with-root
      (fn [root]
        ;; Marker paths are ABSOLUTE inside the step source: the step executes
        ;; in the daemon JVM, whose cwd is not the instance root.
        (let [started (str (fs/path root "STARTED"))
              release (str (fs/path root "RELEASE"))]
          (spit (str (fs/path root "slow.feature"))
                "Feature: SLOW\n\n  Scenario: S\n    Given a slow thing\n")
          (spit (str (fs/path root "steps" "slow.clj"))
                (str "(ns slowsteps (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
                     "(defstep #\"a slow thing\" []\n"
                     "  (spit \"" started "\" \"x\")\n"
                     "  (loop [i 0]\n"
                     "    (when (and (< i 600)\n"
                     "               (not (.exists (java.io.File. \"" release "\"))))\n"
                     "      (Thread/sleep 100)\n"
                     "      (recur (inc i)))))\n"))
          (run-sl root ["fmt" "--check" "good.feature"])   ; prime the daemon
          (let [d0  (read-daemon root)
                fut (future (run-sl root ["run" "slow.feature" "--step-paths" "steps"]))]
            (try
              (is (some? d0) "precondition: daemon running before the slow run")
              (is (wait-for-file started 30000)
                  "precondition: the slow run is executing its blocking step")
              (testing "bare reject: exit 2 + the busy one-liner on stderr"
                (let [r (run-sl root ["run" "ok.feature" "--step-paths" "steps"])]
                  (is (= 2 (:exit r)) "second run rejected, not queued")
                  (is (re-find #"another run is in progress" (:err r))
                      "stderr carries the busy line")))
              (testing "--edn reject: the exit-2 planning-summary shape"
                (let [r (run-sl root ["run" "ok.feature" "--step-paths" "steps" "--edn"])
                      summary (try (edn/read-string (:out r))
                                   (catch Exception _ ::unparseable))]
                  (is (= 2 (:exit r)))
                  (is (map? summary) "stdout is one parseable EDN map")
                  (is (= :planning-failed (:run/status summary)))
                  (is (= :daemon/busy
                         (-> summary :planning :errors first :type))
                      ":daemon/busy rides the planning block")))
              (spit release "go")
              (let [r1 (deref fut 90000 :timeout)]
                (is (map? r1) "first run finished (no hang)")
                (is (= 0 (:exit r1))
                    "first run completed undisturbed by the rejected calls"))
              (is (= (:pid d0) (:pid (read-daemon root)))
                  "same daemon throughout — rejects never bounced it")
              (finally
                (spit release "go")
                (deref fut 90000 :timeout))))))))))

;; -----------------------------------------------------------------------------
;; 18. Progressive output streaming (sl-qwf): warm invocations emit output AS
;;     PRODUCED — the first scenario's PASSED line reaches the client's stdout
;;     while the second scenario is still running. `sh` can't observe this (it
;;     buffers to completion), so this harness reads the wrapper's stdout with
;;     per-line arrival timestamps via ProcessBuilder. The scenario names are
;;     multibyte on purpose: the streamed line arriving INTACT mid-run is the
;;     live form of the sl-y3c/x6r multibyte guard.
;; -----------------------------------------------------------------------------

(def ^:private streaming-feature
  (str "Feature: Streaming — ✓\n\n"
       "  Scenario: first — ✓ 😀\n    Given a pause happens\n\n"
       "  Scenario: second — ✓ 😀\n    Given a pause happens\n"))

(def ^:private pause-step-src
  (str "(ns pausesteps (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
       "(defstep #\"a pause happens\" [] (Thread/sleep 1500))\n"))

(defn- run-sl-timestamped
  "Run bin/sl via ProcessBuilder from `dir`, reading output lines with arrival
   timestamps (ms since spawn). Streams are MERGED: the human run report goes
   to stderr by design (stdout is reserved for machine output — cold does the
   same, verified split-stream), and the streaming property under test is
   per-line arrival time, which the merged read preserves.
   Returns {:exit n :end-ms n :lines [{:ms n :line s}]}."
  [dir args]
  (let [pb (doto (ProcessBuilder. ^java.util.List (into [] (cons sl-bin args)))
             (.directory (io/file dir))
             (.redirectErrorStream true))
        t0 (System/nanoTime)
        ms #(quot (- (System/nanoTime) t0) 1000000)
        proc (.start pb)
        lines (with-open [rdr (io/reader (.getInputStream proc))]
                (into [] (map (fn [l] {:ms (ms) :line l})) (line-seq rdr)))]
    (.waitFor proc)
    {:exit (.exitValue proc) :end-ms (ms) :lines lines}))

(deftest ^:daemon warm-output-streams-progressively
  (skip-without-jar
   (fn []
     (with-root
      (fn [root]
        (spit (str (fs/path root "stream.feature")) streaming-feature)
        (spit (str (fs/path root "steps" "pause.clj")) pause-step-src)
        (run-sl root ["fmt" "--check" "good.feature"])   ; prime the daemon
        (is (some? (read-daemon root)) "precondition: daemon running")
        (let [{:keys [exit end-ms lines]}
              (run-sl-timestamped root ["run" "stream.feature" "--step-paths" "steps"])
              first-line (first (filter #(.contains ^String (:line %) "first — ✓ 😀")
                                        lines))]
          (is (= 0 exit) "warm streamed run passes")
          (is (some? first-line) "first scenario's PASSED line reached stdout")
          (is (<= 1000 (- end-ms (:ms first-line)))
              (str "first scenario's line must arrive while the second is still "
                   "running (>=1000ms before exit; the step sleeps 1500ms). "
                   "Arrivals: " (pr-str (map #(select-keys % [:ms]) lines))
                   " end-ms " end-ms))
          (is (some? (read-daemon root))
              "run went through the daemon (this proves the WARM path streamed")))))))

;; -----------------------------------------------------------------------------
;; 19. Mid-stream daemon loss → exit 3, never a cold retry (sl-qwf design
;;     point, automated at sl-d3cz / Warden W25). Once ANYTHING has streamed,
;;     the client's `streamedAnything` gate (NreplClient) must turn a
;;     transport failure into EX_CRASH (3) — a 69 would make bin/sl fall
;;     back to a cold run and DUPLICATE the already-streamed output, the
;;     exact defect the gate exists to prevent. Neither EX_CRASH branch had
;;     automated coverage before this test.
;; -----------------------------------------------------------------------------

(deftest ^:daemon mid-stream-daemon-loss-exits-3-no-duplicate-output
  (skip-without-jar
   (fn []
     (with-root
      (fn [root]
        (let [started2 (str (fs/path root "STARTED2"))
              release  (str (fs/path root "RELEASE"))]
          ;; Scenario 1 passes fast (its PASSED line streams); scenario 2
          ;; blocks on a file marker so the kill lands mid-stream.
          ;; Marker paths are ABSOLUTE: the step runs in the daemon JVM.
          (spit (str (fs/path root "midstream.feature"))
                (str "Feature: Midstream\n\n"
                     "  Scenario: streamed-first\n    Given a quick thing\n\n"
                     "  Scenario: doomed-second\n    Given a blocking thing\n"))
          (spit (str (fs/path root "steps" "mid.clj"))
                (str "(ns midsteps (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
                     "(defstep #\"a quick thing\" [] nil)\n"
                     "(defstep #\"a blocking thing\" []\n"
                     "  (spit \"" started2 "\" \"x\")\n"
                     "  (loop [i 0]\n"
                     "    (when (and (< i 600)\n"
                     "               (not (.exists (java.io.File. \"" release "\"))))\n"
                     "      (Thread/sleep 100)\n"
                     "      (recur (inc i)))))\n"))
          (run-sl root ["fmt" "--check" "good.feature"])   ; prime the daemon
          (let [d0 (read-daemon root)
                fut (future (run-sl-timestamped
                             root ["run" "midstream.feature" "--step-paths" "steps"]))]
            (try
              (is (some? d0) "precondition: daemon running before the run")
              (is (wait-for-file started2 30000)
                  "precondition: scenario 2 is blocking — scenario 1's line
                   already streamed to the client")
              (sh "kill" "-9" (str (:pid d0)))
              (let [{:keys [exit lines] :as r} (deref fut 90000 :timeout)]
                (is (map? r) "client exited after the daemon died (no hang)")
                (when (map? r)
                  (is (= 3 exit)
                      "EX_CRASH: streamed output makes a cold retry unsafe —
                       69 (silent cold fallback) would duplicate it")
                  (is (= 1 (count (filter #(.contains ^String (:line %) "streamed-first")
                                          lines)))
                      (str "scenario 1's line appears EXACTLY once — a wrongful"
                           " cold retry would print it twice. Lines: "
                           (pr-str (map :line lines))))
                  (is (some #(.contains ^String (:line %) "mid-stream") lines)
                      "the client names the mid-stream loss on stderr")))
              (finally
                (spit release "go")
                (deref fut 90000 :timeout))))))))))

(deftest ^:daemon lib-change-bounces-daemon
  (skip-without-jar
   (fn []
     (let [root (str (fs/create-temp-dir))]
       (try
         (write-lib-fixtures! root)
         (run-sl root ["run" "lib.feature" "--step-paths" "steps"])
         (let [d1 (read-daemon root)]
           (is (some? d1) "precondition: daemon running with libs")
           (testing "adding a jar to sl/lib bounces (signature mismatch)"
             (write-lib-jar! (str (fs/path root "sl" "lib" "extra.jar"))
                             "extraprobe" "X")
             (let [r (run-sl root ["run" "lib.feature" "--step-paths" "steps"])
                   d2 (read-daemon root)]
               (is (= 0 (:exit r)))
               (is (some? d2))
               (is (not= (:pid d1) (:pid d2)) "new pid — daemon bounced on lib add")
               (testing "deleting the whole sl/lib dir bounces again (the
                         signature-only-detectable case: nothing left to be
                         newer than daemon.edn)"
                 (fs/delete-tree (str (fs/path root "sl" "lib")))
                 ;; the run itself now needs no lib (fmt does not touch steps)
                 (let [r3 (run-sl root ["fmt" "--check" "lib.feature"])
                       d3 (read-daemon root)]
                   (is (= 0 (:exit r3)))
                   (is (some? d3))
                   (is (not= (:pid d2) (:pid d3))
                       "new pid — daemon bounced on lib removal"))))))
         (finally (reap! root)))))))

;; -----------------------------------------------------------------------------
;; 12. Session hygiene: warm dispatches release their nREPL sessions (sl-xjx0,
;;     Warden W5). Pre-fix the client cloned a session per dispatch and never
;;     sent close — nREPL retains each (with a parked SessionThread) until the
;;     60-min idle reaper. The regression pin: after several warm dispatches
;;     the daemon's retained-session count stays bounded, checked through
;;     nREPL's own ls-sessions op (sessionless, so the probe adds none).
;; -----------------------------------------------------------------------------

(deftest ^:daemon warm-dispatches-release-nrepl-sessions
  (skip-without-jar
   (fn []
     (with-root
       (fn [root]
         (dotimes [_ 3]
           (is (= 0 (:exit (run-sl root ["run" "ok.feature" "--step-paths" "steps"])))))
         (let [{:keys [port]} (read-daemon root)]
           (is (some? port) "daemon breadcrumb carries the nREPL port")
           (with-open [conn (nrepl/connect :host "127.0.0.1" :port port)]
             (let [client (nrepl/client conn 5000)
                   session-count (fn []
                                   (-> (nrepl/message client {:op "ls-sessions"})
                                       first
                                       :sessions
                                       count))
                   ;; close is a buffered fire-and-forget from the client —
                   ;; give the server a beat to process the last one.
                   bounded? (loop [tries 20]
                              (cond
                                (<= (session-count) 1) true
                                (zero? tries) false
                                :else (do (Thread/sleep 100) (recur (dec tries)))))]
               (is bounded?
                   (str "3 warm dispatches must not retain 3 sessions —"
                        " ls-sessions reports " (session-count)
                        " (pre-W5 each dispatch leaked one)"))))))))))

;; -----------------------------------------------------------------------------
;; 13. Custody visibility (sl-s35y, Warden W6 ruled): `sl daemon status` on a
;;     live daemon reports costume custody through the --sl-internal-status
;;     sentinel — the full wrapper -> client -> daemon wiring, no browser
;;     needed (a costume-less daemon truthfully reports none).
;; -----------------------------------------------------------------------------

(deftest ^:daemon daemon-status-lists-costume-custody
  (skip-without-jar
   (fn []
     (with-root
       (fn [root]
         (is (= 0 (:exit (run-sl root ["run" "ok.feature" "--step-paths" "steps"])))
             "warm run spawns the daemon")
         (let [r (run-sl root ["daemon" "status"])]
           (is (= 0 (:exit r)))
           (is (str/includes? (:out r) "alive"))
           (is (str/includes? (:out r) "live costumes: none")
               "the custody query reached the daemon JVM and reported")))))))
