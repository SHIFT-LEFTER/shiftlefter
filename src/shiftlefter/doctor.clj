(ns shiftlefter.doctor
  "Machine-side pre-flight probes — `sl doctor` (sl-afsp).

   Doctor is the MACHINE validity lane: does this machine have the binaries,
   version lockstep, machine config, and processes ShiftLefter needs? Its
   project-side sibling is `shiftlefter.orient` (project truth: config,
   corpus, vocabulary). The lanes deliberately do not overlap — doctor never
   validates project config; a probe that needs project config as an INPUT
   (which adapters are configured?) and finds it unreadable reports `:skip`
   and points at `sl orient`.

   Design (Chair-locked, sl-afsp): a single-source-of-truth declarative
   probe registry. `probes` below IS the table of contents — `sl doctor
   --list` prints it, `run-probes` iterates it, and nothing is checked that
   isn't declared there (house precedent: `adapters/registry.clj`
   `default-registry`, `agent_doc.clj` `topics`). Probes exist ONLY for
   failure modes that have already bitten; each entry carries its `:scar`
   (the evidence). A probe with no scar does not belong here.

   Exit contract — doctor's own tiny table, deliberately OUTSIDE the
   run-verdict table (`shiftlefter.runner.verdict`, sl-7c7i): this is a
   MACHINE verdict, not a run verdict.

   | Exit | Meaning                                            |
   |------|----------------------------------------------------|
   | 0    | healthy — no probe failed (skips/warns don't fail) |
   | 1    | issues found — at least one probe failed           |
   | 2    | un-runnable invocation (bad flags; core/dispatch)  |

   Output: human table on stdout; `--edn` emits the full report through the
   pinned printer seam (`reporter/pr-edn-str`) with `:doctor/version`;
   `--list` prints the registry without running anything."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [clojure.string :as str]
            [shiftlefter.adapters.registry :as adapters]
            [shiftlefter.browser.chrome :as chrome]
            [shiftlefter.config.user :as user]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.runner.config :as config]
            [shiftlefter.runner.reporter :as reporter]))

;; -----------------------------------------------------------------------------
;; Specs — probe registry and report shapes
;; -----------------------------------------------------------------------------

(def doctor-version
  "Version of the --edn report shape (house convention: resolution/output-version)."
  1)

(s/def ::probe keyword?)
(s/def ::desc string?)
(s/def ::scar string?)
;; fn? has no generator; spec_health_test generates every :shiftlefter* spec.
(s/def ::fn (s/with-gen fn?
              #(sgen/return (fn [_ctx] {:probe :generated :status :ok}))))

(s/def ::registry-entry
  (s/keys :req-un [::probe ::desc ::scar ::fn]))

(s/def ::registry (s/coll-of ::registry-entry :kind vector?))

(s/def ::status #{:ok :warn :fail :skip})
(s/def ::message string?)
(s/def ::details any?)

(s/def ::probe-result
  (s/keys :req-un [::probe ::status]
          :opt-un [::message ::details]))

(s/def ::probes (s/coll-of ::probe-result))

(s/def ::total nat-int?)
(s/def ::ok nat-int?)
(s/def ::warned nat-int?)
(s/def ::failed nat-int?)
(s/def ::skipped nat-int?)

(s/def ::summary
  (s/keys :req-un [::total ::ok ::warned ::failed ::skipped]))

(s/def :doctor/version pos-int?)
;; The report's top-level :status vocabulary differs from the per-probe one;
;; a fully-qualified spec name disambiguates the un-namespaced key (house
;; precedent: :shiftlefter.counts/error in stepengine/exec.clj).
(s/def :shiftlefter.doctor.report/status #{:healthy :issues})

(s/def ::report
  (s/keys :req [:doctor/version]
          :req-un [:shiftlefter.doctor.report/status ::probes ::summary]))

;; -----------------------------------------------------------------------------
;; Probe result constructor
;; -----------------------------------------------------------------------------

(defn- result
  "Build a probe result map (verify.clj make-check idiom)."
  [probe status & {:keys [message details]}]
  (cond-> {:probe probe :status status}
    message (assoc :message message)
    details (assoc :details details)))

;; -----------------------------------------------------------------------------
;; Probe: :user-config — ~/.shiftlefter/config.edn validity
;; -----------------------------------------------------------------------------

(def ^:private known-user-config-keys
  "The whole machine-config vocabulary (config/user.clj ns docstring)."
  #{:chrome-path :chromedriver-path})

(defn- user-config-problems
  "Structural problems with a parsed machine config. Empty seq = valid."
  [cfg]
  (if-not (map? cfg)
    [{:problem :not-a-map :value (pr-str cfg)}]
    (let [unknown (->> (keys cfg)
                       (remove known-user-config-keys)
                       (map (fn [k] {:problem :unknown-key :key k})))
          paths (for [k [:chrome-path :chromedriver-path]
                      :let [v (get cfg k)]
                      :when (some? v)
                      :let [p (cond
                                (not (string? v))
                                {:problem :not-a-string :key k :value (pr-str v)}

                                (not (fs/exists? v))
                                {:problem :missing-file :key k :path v}

                                (not (fs/executable? v))
                                {:problem :not-executable :key k :path v})]
                      :when p]
                  p)]
      (concat unknown paths))))

(defn- describe-problem [{:keys [problem key path value]}]
  (case problem
    :not-a-map      (str "config is not a map: " value)
    :unknown-key    (str "unknown key " key " (known: :chrome-path :chromedriver-path)")
    :not-a-string   (str key " is not a string: " value)
    :missing-file   (str key " points at a missing file: " path)
    :not-executable (str key " points at a non-executable file: " path)))

(defn- probe-user-config [_ctx]
  (let [path (user/user-config-path)]
    (if-not (fs/exists? path)
      (result :user-config :ok
              :message "no machine config (defaults apply)"
              :details {:path path})
      (let [{cfg :ok err :error} (user/load-user-config-safe)]
        (if err
          (result :user-config :fail
                  :message (str path " does not parse: " (:message err)
                                " — every non-doctor path silently ignores"
                                " this file and falls back to PATH discovery")
                  :details err)
          (let [problems (vec (user-config-problems cfg))
                ;; Unknown keys alone downgrade to WARN (Chair-ruled,
                ;; sl-d3cz): every consumer ignores them, so the config
                ;; still works — but a dead key deserves a flag, not a
                ;; red pre-flight. Invalid VALUES (bad type, missing or
                ;; non-executable file, parse error) still FAIL; a mix
                ;; fails — fail dominates.
                unknown-only? (and (seq problems)
                                   (every? #(= :unknown-key (:problem %))
                                           problems))]
            (cond
              unknown-only?
              (result :user-config :warn
                      :message (str path ": "
                                    (str/join "; " (map describe-problem problems))
                                    " — ignored by every consumer; delete or fix")
                      :details {:path path :problems problems})

              (seq problems)
              (result :user-config :fail
                      :message (str path ": "
                                    (str/join "; " (map describe-problem problems)))
                      :details {:path path :problems problems})

              :else
              (result :user-config :ok
                      :message (str path " valid"
                                    (when (seq cfg)
                                      (str " (" (str/join ", " (map name (keys cfg))) ")")))
                      :details {:path path :config cfg}))))))))

;; -----------------------------------------------------------------------------
;; Probe: :chromedriver-lockstep — presence + Chrome major-version lockstep
;; -----------------------------------------------------------------------------

(defn- version-line
  "First stdout line of `<binary> --version` (trailing parenthetical build
   hash stripped), or nil when the binary can't be executed or exits
   non-zero. Redefinable seam for tests."
  [binary]
  (try
    (let [{:keys [exit out]} (shell/sh binary "--version")]
      (when (zero? exit)
        (some-> (first (str/split-lines (str/trim (str out))))
                (str/replace #"\s*\(.*\)\s*$" ""))))
    (catch java.io.IOException _ nil)))

(defn- major-version
  "First integer run in a version line: \"ChromeDriver 150.0.7654.3\" -> 150."
  [line]
  (some-> (re-find #"\d+" (or line "")) parse-long))

(defn- resolve-chromedriver
  "resolve-chromedriver-path plus the implicit PATH leg etaoin falls through
   to when config yields nothing — doctor must reproduce the whole chain."
  []
  (or (user/resolve-chromedriver-path {})
      (some-> (fs/which "chromedriver") str)))

(defn- project-chromedriver-legs
  "Per-interface chromedriver pins (sl-xjx0, Warden W10 — the yynw scar
   class one leg over): the runtime resolves :path-driver from the
   interface's `[:config :adapter-opts]` BEFORE the machine config, so a
   project pinning a drifted binary made the probe measure the wrong one.
   Returns [{:source <iface-name> :path p} …] for every interface whose
   adapter-opts pin resolves to a path distinct from the machine leg.
   probe-playwright-dep posture: no project / unreadable config → [] —
   doctor never validates project config, the machine leg still runs."
  [project-context]
  (if (or (nil? project-context)
          (= :defaults (:config-source project-context)))
    []
    (let [{:keys [status] :as loaded}
          (config/load-config-safe {:project-context project-context})]
      (if (= :error status)
        []
        (vec
         (for [[iface-name iface-cfg] (sort-by (comp name key)
                                               (config/get-interfaces (:config loaded)))
               :let [opts (get-in iface-cfg [:config :adapter-opts])
                     path (when (:path-driver opts)
                            (user/resolve-chromedriver-path opts))]
               :when path]
           {:source iface-name :path path}))))))

(defn- lockstep-leg
  "Version-lockstep verdict for ONE chromedriver binary: {:ok? bool
   :message str :details map?}. `source` names how this binary was
   resolved (machine config/PATH, or the pinning interface)."
  [driver-path chrome-path source]
  (let [tag (str " [" source "]")]
    (cond
      (not (fs/exists? driver-path))
      {:ok? false
       :message (str "configured chromedriver missing: " driver-path tag)}

      :else
      (let [driver-line (version-line driver-path)
            chrome-line (version-line (str chrome-path))
            driver-major (major-version driver-line)
            chrome-major (major-version chrome-line)]
        (cond
          (nil? driver-major)
          {:ok? false
           :message (str "could not read chromedriver version from "
                         driver-path tag)}

          (nil? chrome-major)
          {:ok? false
           :message (str "could not read Chrome version from " chrome-path)}

          (= driver-major chrome-major)
          {:ok? true
           :message (str driver-line " / " chrome-line " — majors match" tag)
           :details {:source source :chromedriver driver-path
                     :chrome (str chrome-path)}}

          :else
          {:ok? false
           :message (str "major-version drift" tag ": " driver-line " vs "
                         chrome-line " — download the chromedriver"
                         " matching Chrome " chrome-major)
           :details {:source source
                     :chromedriver-major driver-major
                     :chrome-major chrome-major}})))))

(defn- probe-chromedriver-lockstep [{:keys [project-context]}]
  (let [machine-path (resolve-chromedriver)
        chrome-path (chrome/find-binary
                     (select-keys (or (:ok (user/load-user-config-safe)) {})
                                  [:chrome-path]))
        ;; Every DISTINCT binary the runtime could use: the machine leg plus
        ;; per-interface :adapter-opts pins (sl-xjx0, W10).
        legs (into (if machine-path
                     [{:source "machine config / PATH" :path machine-path}]
                     [])
                   (remove #(= machine-path (:path %))
                           (project-chromedriver-legs project-context)))]
    (cond
      (empty? legs)
      (result :chromedriver-lockstep :fail
              :message (str "chromedriver not found: no :chromedriver-path in "
                            (user/user-config-path) ", nothing on PATH, and no"
                            " interface pins :path-driver"))

      (:errors chrome-path)
      (result :chromedriver-lockstep :fail
              :message (str "Chrome not found: "
                            (-> chrome-path :errors first :message))
              :details (:errors chrome-path))

      :else
      (let [checked (mapv #(lockstep-leg (:path %) chrome-path (:source %)) legs)
            failures (remove :ok? checked)]
        (if (seq failures)
          (result :chromedriver-lockstep :fail
                  :message (str/join "; " (map :message failures))
                  :details {:legs (mapv #(select-keys % [:message :details]) checked)})
          (result :chromedriver-lockstep :ok
                  :message (str/join "; " (map :message checked))
                  :details {:legs (mapv :details checked)}))))))

;; -----------------------------------------------------------------------------
;; Probe: :playwright-dep — extra-dep adapters present when configured
;; -----------------------------------------------------------------------------

(defn- probe-playwright-dep [{:keys [project-context]}]
  (if (or (nil? project-context)
          (= :defaults (:config-source project-context)))
    (result :playwright-dep :skip
            :message "no project config — no adapters configured here")
    (let [{:keys [status] :as loaded}
          (config/load-config-safe {:project-context project-context})]
      (if (= :error status)
        ;; The boundary: doctor never validates project config. Unreadable
        ;; config is orient's diagnosis; here it just means no adapter input.
        (result :playwright-dep :skip
                :message "project config unreadable — diagnose with `sl orient`")
        (let [interfaces (sort-by (comp name key)
                                  (config/get-interfaces (:config loaded)))
              gated (filter (fn [[_ iface-cfg]]
                              (:requires-class
                               (adapters/get-adapter (:adapter iface-cfg))))
                            interfaces)
              errors (into []
                           (keep (fn [[iface-name iface-cfg]]
                                   (adapters/adapter-unavailable-error
                                    iface-name (:adapter iface-cfg))))
                           interfaces)]
          (cond
            (empty? gated)
            (result :playwright-dep :skip
                    :message "no configured adapter needs an extra dependency")

            (seq errors)
            (result :playwright-dep :fail
                    :message (str/join "; " (map :message errors))
                    :details {:errors errors})

            :else
            (result :playwright-dep :ok
                    :message (str "extra-dep adapter(s) present on classpath: "
                                  (str/join ", " (map (comp :adapter second) gated))))))))))

;; -----------------------------------------------------------------------------
;; Probe: :daemon-health — daemon.edn vs live pid
;; -----------------------------------------------------------------------------

(defn- pid-alive? [pid]
  (let [handle (java.lang.ProcessHandle/of (long pid))]
    (and (.isPresent handle) (.isAlive ^java.lang.ProcessHandle (.get handle)))))

(defn- current-lib-sig
  "Sorted `name:size` lines for <lib-dir>/*.jar — the Clojure re-expression
   of bin/sl `lib_sig` (must agree: the daemon-bounce decision keys on it)."
  [lib-dir]
  (->> (when (fs/directory? lib-dir) (fs/glob lib-dir "*.jar"))
       (map (fn [jar] (str (fs/file-name jar) ":" (fs/size jar))))
       sort
       (str/join "\n")))

(defn- daemon-staleness
  "Which recorded daemon facts no longer match disk. Empty = fresh.
   Mirrors bin/sl read_daemon's staleness legs (jar identity, lib-sig)."
  [root {:keys [jar-path jar-size]}]
  (let [recorded-sig-file (fs/path root ".shiftlefter" "lib-sig")
        recorded-sig (when (fs/exists? recorded-sig-file)
                       (str/trim (slurp (fs/file recorded-sig-file))))
        live-sig (current-lib-sig (fs/path root "sl" "lib"))]
    (cond-> []
      (and jar-path (or (not (fs/exists? jar-path))
                        (not= jar-size (fs/size jar-path))))
      (conj :jar-changed)

      (not= (or recorded-sig "") live-sig)
      (conj :lib-sig-drift))))

(defn- probe-daemon-health [{:keys [project-context]}]
  (let [root (project-context/instance-root project-context)]
    (if-not root
      (result :daemon-health :skip
              :message "no instance root (no sl/shiftlefter.edn or .git above cwd)")
      (let [edn-path (fs/path root ".shiftlefter" "daemon.edn")]
        (if-not (fs/exists? edn-path)
          (result :daemon-health :ok :message "no daemon running")
          (let [parsed (try
                         {:ok (edn/read-string (slurp (fs/file edn-path)))}
                         (catch RuntimeException e {:error (ex-message e)}))]
            (cond
              (:error parsed)
              (result :daemon-health :fail
                      :message (str edn-path " does not parse: " (:error parsed)
                                    " — `sl daemon stop` to clean"))

              (not (pos-int? (:pid (:ok parsed))))
              (result :daemon-health :fail
                      :message (str edn-path " has no usable :pid"
                                    " — `sl daemon stop` to clean")
                      :details (:ok parsed))

              (not (pid-alive? (:pid (:ok parsed))))
              (result :daemon-health :fail
                      :message (str "stale daemon.edn — pid " (:pid (:ok parsed))
                                    " is dead; next warm run respawns,"
                                    " `sl daemon stop` cleans now")
                      :details (:ok parsed))

              :else
              (let [{:keys [pid port] :as daemon-map} (:ok parsed)
                    stale (daemon-staleness root daemon-map)]
                (result :daemon-health :ok
                        :message (cond-> (str "daemon alive (pid " pid
                                              ", port " port ")")
                                   (seq stale)
                                   (str " — stale vs disk "
                                        (str/join "+" (map name stale))
                                        "; next warm run bounces it"))
                        :details (cond-> daemon-map
                                   (seq stale) (assoc :stale stale)))))))))))

;; -----------------------------------------------------------------------------
;; Probe: :orphan-chromedriver — scan only, never reap (sl-34fu)
;; -----------------------------------------------------------------------------

(defn- list-orphan-chromedriver-pids
  "Pids of chromedriver processes whose parent is gone or PID 1 — the orphan
   discriminator (orphans reparent to init, sl-9vag; a chromedriver serving a
   live run has a live JVM parent and is NOT counted). Pure ProcessHandle —
   no pgrep/lsof dependency. Redefinable seam for tests."
  []
  (->> (iterator-seq (.iterator (java.lang.ProcessHandle/allProcesses)))
       (keep (fn [^java.lang.ProcessHandle handle]
               (let [command (.orElse (.command (.info handle)) nil)
                     parent (.parent handle)]
                 (when (and command
                            (= "chromedriver" (str (fs/file-name command)))
                            (or (not (.isPresent parent))
                                (= 1 (.pid ^java.lang.ProcessHandle (.get parent)))))
                   (.pid handle)))))
       sort
       vec))

(defn- probe-orphan-chromedriver [_ctx]
  (let [pids (list-orphan-chromedriver-pids)]
    (if (empty? pids)
      (result :orphan-chromedriver :ok
              :message "no orphaned chromedriver processes")
      (result :orphan-chromedriver :fail
              :message (str (count pids) " orphaned chromedriver process(es): "
                            (str/join " " pids)
                            " — doctor scans, it does not reap (sl-34fu);"
                            " kill " (str/join " " pids))
              :details {:pids pids}))))

;; -----------------------------------------------------------------------------
;; Probe: :lib-jars — sl/lib user-jar sanity
;; -----------------------------------------------------------------------------
;; Minimal by ruling: readable-zip + lib-sig drift only. Duplicate-ns/class
;; shadowing across lib jars is parked sl-m4nt (which names an "sl doctor-
;; style surface" as one placement option, wait-for-demand).

(defn- zip-readable? [path]
  (try
    (with-open [_ (java.util.zip.ZipFile. (fs/file path))] true)
    (catch java.io.IOException _ false)))

(defn- probe-lib-jars [{:keys [project-context]}]
  (let [root (project-context/instance-root project-context)]
    (if-not root
      (result :lib-jars :skip
              :message "no instance root (no sl/shiftlefter.edn or .git above cwd)")
      (let [lib-dir (fs/path root "sl" "lib")
            jars (when (fs/directory? lib-dir) (sort (fs/glob lib-dir "*.jar")))]
        (if (empty? jars)
          (result :lib-jars :ok :message "no user jars (sl/lib absent or empty)")
          (let [unreadable (remove zip-readable? jars)
                sig-file (fs/path root ".shiftlefter" "lib-sig")
                recorded-sig (when (fs/exists? sig-file)
                               (str/trim (slurp (fs/file sig-file))))
                drift? (and recorded-sig
                            (not= recorded-sig (current-lib-sig lib-dir)))]
            (cond
              (seq unreadable)
              (result :lib-jars :fail
                      :message (str (count unreadable) " unreadable jar(s) in sl/lib: "
                                    (str/join ", " (map fs/file-name unreadable))
                                    " — truncated download?")
                      :details {:unreadable (mapv str unreadable)})

              drift?
              (result :lib-jars :ok
                      :message (str (count jars) " user jar(s) readable; sl/lib"
                                    " changed since daemon spawn — next warm"
                                    " run bounces the daemon"))

              :else
              (result :lib-jars :ok
                      :message (str (count jars) " user jar(s) readable")))))))))

;; -----------------------------------------------------------------------------
;; THE PROBE REGISTRY — the single source of truth (sl-afsp)
;; -----------------------------------------------------------------------------

(def probes
  "The registry IS the table of contents: `sl doctor --list` prints it,
   `run-probes` iterates it, nothing is checked that isn't declared here.
   Display order = vector order (agent-doc topics precedent).

   Entry keys:
   - :probe — id keyword
   - :desc  — one line, what is checked (printed by --list)
   - :scar  — the bite that earned the probe; evidence citation. Scope rule
              (Chair-locked): no probe without a scar.
   - :fn    — (fn [ctx] -> probe-result); ctx = {:project-context <resolved>}"
  [{:probe :user-config
    :desc "~/.shiftlefter/config.edn parses, only known keys, configured binaries exist"
    :scar (str "parse errors are silently swallowed into PATH fallback "
               "(config/user.clj resolve-chromedriver-path) — a typo'd "
               "machine config degrades with zero signal")
    :fn probe-user-config}
   {:probe :chromedriver-lockstep
    :desc "chromedriver present and its major version matches installed Chrome"
    :scar (str "149→150 drift bit on 2026-08-05 (sl-yynw); the Warden "
               "checked lockstep manually — this probe automates that audit line")
    :fn probe-chromedriver-lockstep}
   {:probe :playwright-dep
    :desc "adapters that need an extra dependency (e.g. :playwright) have it on the classpath"
    :scar (str "sl-gcdu: playwright configured but not bundled used to load "
               "clean and die at first provisioning")
    :fn probe-playwright-dep}
   {:probe :daemon-health
    :desc "this project's daemon.edn agrees with a live daemon process"
    :scar (str "DEAD (stale daemon.edn) is the standing failure `sl daemon "
               "status` exists to catch; concurrency floor findings sl-luhn")
    :fn probe-daemon-health}
   {:probe :orphan-chromedriver
    :desc "no chromedriver processes orphaned to PID 1 (scan only — never reaps)"
    :scar (str "six strays measured live on sl-c76w (2026-08-05); leak "
               "confirmed at pid level on sl-34fu — reaping is that bead's, "
               "not doctor's")
    :fn probe-orphan-chromedriver}
   {:probe :lib-jars
    :desc "sl/lib user jars are readable archives; lib-sig drift vs the running daemon"
    :scar (str "seed chartered on sl-afsp: a truncated sl/lib download or a "
               "stale lib-sig means the daemon runs a classpath that no "
               "longer matches disk (bin/sl read_daemon's bounce trigger)")
    :fn probe-lib-jars}])

;; -----------------------------------------------------------------------------
;; Runner
;; -----------------------------------------------------------------------------

(defn- run-probe
  "Run one registry entry, containing any crash as a :fail result — a doctor
   that dies on a weird machine defeats its purpose. Boundary catch is
   deliberate (structured error, per house error-handling rule)."
  [{:keys [probe] :as entry} ctx]
  (try
    ((:fn entry) ctx)
    (catch Exception e
      (result probe :fail
              :message (str "probe crashed: " (ex-message e))
              :details {:type :probe/crashed :class (str (class e))}))))

(defn- summarize [results]
  (let [counts (frequencies (map :status results))]
    {:total (count results)
     :ok (get counts :ok 0)
     :warned (get counts :warn 0)
     :failed (get counts :fail 0)
     :skipped (get counts :skip 0)}))

(defn run-probes
  "Run every probe in the registry against ctx. Returns a ::report:
   {:doctor/version 1 :status :healthy|:issues :probes [...] :summary {...}}.
   :healthy iff no probe failed — skips and warns never fail."
  ([ctx] (run-probes probes ctx))
  ([registry ctx]
   (let [results (mapv #(run-probe % ctx) registry)
         summary (summarize results)]
     {:doctor/version doctor-version
      :status (if (zero? (:failed summary)) :healthy :issues)
      :probes results
      :summary summary})))

(s/fdef run-probes
  :args (s/cat :registry (s/? ::registry) :ctx map?)
  :ret ::report)

;; -----------------------------------------------------------------------------
;; Output
;; -----------------------------------------------------------------------------

(defn- status-label [status]
  (case status :ok "OK" :warn "WARN" :fail "FAIL" :skip "SKIP"))

(defn format-report-human
  "Human table for a ::report (verify.clj print-human idiom)."
  [{:keys [status probes summary]}]
  (let [rows (for [{:keys [probe] :as probe-result} probes]
               (format "  %-6s %-22s %s"
                       (str "[" (status-label (:status probe-result)) "]")
                       (name probe)
                       (or (:message probe-result) "")))
        {:keys [ok warned failed skipped]} summary]
    (str "# Doctor — machine pre-flight\n\n"
         (str/join "\n" rows)
         "\n\nResult: "
         (if (= :healthy status) "healthy" "issues found")
         " (" ok " ok, " warned " warned, " failed " failed, "
         skipped " skipped)")))

(defn format-probe-list
  "The printable table of contents: what doctor does and does not check."
  ([] (format-probe-list probes))
  ([registry]
   (str "sl doctor probes (this registry is the WHOLE check list):\n\n"
        (str/join "\n"
                  (for [{:keys [probe desc]} registry]
                    (format "  %-22s %s" (name probe) desc))))))

(defn registry-listing
  "Registry entries as data for `--list --edn` (fns stripped, scars kept)."
  ([] (registry-listing probes))
  ([registry]
   {:doctor/version doctor-version
    :probes (mapv #(dissoc % :fn) registry)}))

;; -----------------------------------------------------------------------------
;; CLI entry
;; -----------------------------------------------------------------------------

(defn doctor-cmd
  "Run machine pre-flight probes. Returns a CLI exit code (doctor's own
   contract — see ns docstring): 0 healthy, 1 issues found. `--list` prints
   the probe registry without running anything (exit 0); `--edn` makes
   either mode machine-readable via the pinned printer seam.

   `project-context` is the resolved sl-a4a context dispatch already built;
   no-project is first-class — machine-only probes still run, project-scoped
   probes report :skip (orient's :defaults posture)."
  [_arguments {:keys [edn list]} project-context]
  (if list
    (do
      (println (if edn
                 (reporter/pr-edn-str (registry-listing))
                 (format-probe-list)))
      (flush)
      0)
    (let [report (run-probes {:project-context project-context})]
      (println (if edn
                 (reporter/pr-edn-str report)
                 (format-report-human report)))
      (flush)
      (if (= :healthy (:status report)) 0 1))))
