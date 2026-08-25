(ns shiftlefter.doctor-test
  "sl doctor (sl-afsp) — registry shape, per-probe units (machine-isolated),
   runner/exit contract, output modes, dispatch wiring.

   Machine isolation follows the house patterns: `System/setProperty
   \"user.home\"` swap (config/user_test) for anything reading
   ~/.shiftlefter, `with-redefs` on doctor's probe seams (costume_test
   style) for shell/process reads, and injected registries for the runner
   (adapter-registry precedent)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.adapters.registry :as adapters]
            [shiftlefter.core :as core]
            [shiftlefter.doctor :as doctor]
            [shiftlefter.project-context :as project-context])
  (:import [java.util.zip ZipEntry ZipOutputStream]))

;; -----------------------------------------------------------------------------
;; Fixtures
;; -----------------------------------------------------------------------------

(defn- temp-dir [] (str (fs/create-temp-dir {:prefix "shiftlefter-doctor-test-"})))

(defn- with-temp-dir [f]
  (let [dir (temp-dir)]
    (try (f dir) (finally (when (fs/exists? dir) (fs/delete-tree dir))))))

(defn- with-temp-home
  "Run f with user.home pointed at a fresh temp dir (restored in finally)."
  [f]
  (with-temp-dir
    (fn [dir]
      (let [original-home (System/getProperty "user.home")]
        (try
          (System/setProperty "user.home" dir)
          (f dir)
          (finally
            (System/setProperty "user.home" original-home)))))))

(defn- write-text! [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn- write-executable! [path]
  (write-text! path "#!/bin/sh\n")
  (.setExecutable (fs/file path) true)
  (str path))

(defn- write-zip!
  "Write a minimal valid zip archive at path."
  [path]
  (fs/create-dirs (fs/parent path))
  (with-open [out (ZipOutputStream. (java.io.FileOutputStream. (fs/file path)))]
    (.putNextEntry out (ZipEntry. "entry.txt"))
    (.write out (.getBytes "hello"))
    (.closeEntry out))
  (str path))

(defn- make-project!
  "Minimal sl/ project so project-context resolves :discovered."
  ([dir] (make-project! dir {:runner {:features ["features/"]}}))
  ([dir config]
   (write-text! (fs/path dir "sl" "shiftlefter.edn") (pr-str config))))

(defn- context [dir]
  (project-context/resolve {:invocation-root dir}))

(defn- probe-result-for
  "Run one named probe from the real registry against ctx."
  [probe-id ctx]
  (let [entry (first (filter #(= probe-id (:probe %)) doctor/probes))]
    (is (some? entry) (str "probe " probe-id " is registered"))
    ((:fn entry) ctx)))

(def ^:private own-pid (.pid (java.lang.ProcessHandle/current)))

(defn- dead-pid
  "A pid guaranteed dead: spawn `true`, wait for exit, return its pid."
  []
  (let [process (.start (ProcessBuilder. ["true"]))]
    (.waitFor process)
    (.pid process)))

;; -----------------------------------------------------------------------------
;; Registry shape — the declarative contract
;; -----------------------------------------------------------------------------

(deftest registry-shape-test
  (testing "registry conforms to its spec"
    (is (s/valid? :shiftlefter.doctor/registry doctor/probes)
        (s/explain-str :shiftlefter.doctor/registry doctor/probes)))
  (testing "probe ids are unique"
    (is (apply distinct? (map :probe doctor/probes))))
  (testing "every probe carries its scar (the bitten-things scope rule)"
    (doseq [{:keys [probe scar]} doctor/probes]
      (is (seq scar) (str probe " has a non-empty :scar")))))

;; -----------------------------------------------------------------------------
;; Probe: :user-config
;; -----------------------------------------------------------------------------

(deftest probe-user-config-test
  (testing "missing file is healthy (defaults apply)"
    (with-temp-home
      (fn [_dir]
        (let [{:keys [status message]} (probe-result-for :user-config {})]
          (is (= :ok status))
          (is (str/includes? message "no machine config"))))))

  (testing "parse error fails loudly — the silent-swallow scar"
    (with-temp-home
      (fn [dir]
        (write-text! (fs/path dir ".shiftlefter" "config.edn") "{:broken }}}")
        (let [{:keys [status message]} (probe-result-for :user-config {})]
          (is (= :fail status))
          (is (str/includes? message "does not parse"))))))

  (testing "unknown keys WARN with the known vocabulary named — the config
            still works, so no red pre-flight (Chair-ruled, sl-d3cz)"
    (with-temp-home
      (fn [dir]
        (write-text! (fs/path dir ".shiftlefter" "config.edn")
                     (pr-str {:chromedriver-pth "/tmp/x"}))
        (let [{:keys [status message]} (probe-result-for :user-config {})]
          (is (= :warn status))
          (is (str/includes? message "unknown key :chromedriver-pth"))))))

  (testing "a warn-only report is :healthy — warns keep exit 0"
    (with-temp-home
      (fn [dir]
        (write-text! (fs/path dir ".shiftlefter" "config.edn")
                     (pr-str {:sieve {:dead true}}))
        (let [entry (first (filter #(= :user-config (:probe %)) doctor/probes))
              report (doctor/run-probes [entry] {})]
          (is (= :warn (-> report :probes first :status)))
          (is (= :healthy (:status report)))
          (is (= 1 (-> report :summary :warned)))
          (is (= 0 (-> report :summary :failed)))))))

  (testing "an unknown key BESIDE an invalid value still fails — fail dominates"
    (with-temp-home
      (fn [dir]
        (write-text! (fs/path dir ".shiftlefter" "config.edn")
                     (pr-str {:chromedriver-pth "/tmp/x"
                              :chromedriver-path "/nonexistent/chromedriver"}))
        (let [{:keys [status message]} (probe-result-for :user-config {})]
          (is (= :fail status))
          (is (str/includes? message "unknown key :chromedriver-pth"))
          (is (str/includes? message "missing file"))))))

  (testing "configured path that doesn't exist fails"
    (with-temp-home
      (fn [dir]
        (write-text! (fs/path dir ".shiftlefter" "config.edn")
                     (pr-str {:chromedriver-path "/nonexistent/chromedriver"}))
        (let [{:keys [status message]} (probe-result-for :user-config {})]
          (is (= :fail status))
          (is (str/includes? message "missing file"))))))

  (testing "valid config with an executable binary passes"
    (with-temp-home
      (fn [dir]
        (let [driver (write-executable! (fs/path dir "chromedriver"))]
          (write-text! (fs/path dir ".shiftlefter" "config.edn")
                       (pr-str {:chromedriver-path driver}))
          (let [{:keys [status message]} (probe-result-for :user-config {})]
            (is (= :ok status))
            (is (str/includes? message "valid"))))))))

;; -----------------------------------------------------------------------------
;; Probe: :chromedriver-lockstep
;; -----------------------------------------------------------------------------

(deftest probe-chromedriver-lockstep-test
  (with-temp-home
    (fn [dir]
      (let [driver (write-executable! (fs/path dir "chromedriver"))
            chrome (write-executable! (fs/path dir "chrome"))]
        (write-text! (fs/path dir ".shiftlefter" "config.edn")
                     (pr-str {:chromedriver-path driver :chrome-path chrome}))

        (testing "matching majors pass"
          (with-redefs [doctor/version-line
                        (fn [binary]
                          (if (= binary driver) "ChromeDriver 150.0.1.2" "Google Chrome 150.0.9.9"))]
            (let [{:keys [status message]} (probe-result-for :chromedriver-lockstep {})]
              (is (= :ok status))
              (is (str/includes? message "majors match")))))

        (testing "major drift fails, naming both versions"
          (with-redefs [doctor/version-line
                        (fn [binary]
                          (if (= binary driver) "ChromeDriver 149.0.1.2" "Google Chrome 150.0.9.9"))]
            (let [{:keys [status message]} (probe-result-for :chromedriver-lockstep {})]
              (is (= :fail status))
              (is (str/includes? message "major-version drift"))
              (is (str/includes? message "149"))
              (is (str/includes? message "150")))))

        (testing "unreadable version output fails"
          (with-redefs [doctor/version-line (constantly nil)]
            (let [{:keys [status]} (probe-result-for :chromedriver-lockstep {})]
              (is (= :fail status))))))))

  (testing "no chromedriver anywhere fails"
    (with-temp-home
      (fn [_dir]
        (with-redefs [doctor/resolve-chromedriver (constantly nil)]
          (let [{:keys [status message]} (probe-result-for :chromedriver-lockstep {})]
            (is (= :fail status))
            (is (str/includes? message "chromedriver not found"))))))))

(deftest probe-chromedriver-lockstep-project-pin-test
  ;; sl-xjx0 (Warden W10, the yynw scar class one leg over): the runtime
  ;; resolves :path-driver from the interface's [:config :adapter-opts]
  ;; BEFORE the machine config — a project pinning a drifted binary used to
  ;; get [OK] measured against the wrong one. The probe now checks every
  ;; distinct resolution and names the pinning interface on failure.
  (with-temp-home
    (fn [dir]
      (let [machine-driver (write-executable! (fs/path dir "chromedriver"))
            pinned-driver  (write-executable! (fs/path dir "pinned-chromedriver"))
            chrome         (write-executable! (fs/path dir "chrome"))
            versions       (atom {})]
        (write-text! (fs/path dir ".shiftlefter" "config.edn")
                     (pr-str {:chromedriver-path machine-driver :chrome-path chrome}))
        (write-text! (fs/path dir "proj" "sl" "shiftlefter.edn")
                     (pr-str {:interfaces
                              {:web {:type :web :adapter :etaoin
                                     :config {:adapter-opts
                                              {:path-driver pinned-driver}}}}}))
        (reset! versions {machine-driver "ChromeDriver 150.0.1.2"
                          pinned-driver  "ChromeDriver 149.0.1.2"
                          (str chrome)   "Google Chrome 150.0.9.9"})
        (with-redefs [doctor/version-line (fn [binary] (get @versions (str binary)))]
          (let [ctx {:project-context (context (str (fs/path dir "proj")))}]
            (testing "drifted interface pin fails, naming the interface"
              (let [{:keys [status message]} (probe-result-for :chromedriver-lockstep ctx)]
                (is (= :fail status))
                (is (str/includes? message "[:web]") "the pinning interface is named")
                (is (str/includes? message "149"))))
            (testing "pin in lockstep too: ok, both legs reported"
              (swap! versions assoc pinned-driver "ChromeDriver 150.0.5.5")
              (let [{:keys [status message]} (probe-result-for :chromedriver-lockstep ctx)]
                (is (= :ok status))
                (is (str/includes? message "machine config / PATH"))
                (is (str/includes? message "[:web]"))))
            (testing "no project context: machine leg only, as before"
              (let [{:keys [status]} (probe-result-for :chromedriver-lockstep {})]
                (is (= :ok status))))))))))

;; -----------------------------------------------------------------------------
;; Probe: :playwright-dep
;; -----------------------------------------------------------------------------

(deftest probe-playwright-dep-test
  (testing "no project config skips"
    (with-temp-dir
      (fn [dir]
        (let [{:keys [status]} (probe-result-for :playwright-dep
                                                 {:project-context (context dir)})]
          (is (= :skip status))))))

  (testing "configured extra-dep adapter present on classpath passes"
    (with-temp-dir
      (fn [dir]
        (make-project! dir {:interfaces {:web {:type :web :adapter :playwright :config {}}}})
        (with-redefs [adapters/class-available? (constantly true)]
          (let [{:keys [status message]} (probe-result-for :playwright-dep
                                                           {:project-context (context dir)})]
            (is (= :ok status))
            (is (str/includes? message "playwright")))))))

  (testing "configured extra-dep adapter absent fails with the not-bundled remedy"
    (with-temp-dir
      (fn [dir]
        (make-project! dir {:interfaces {:web {:type :web :adapter :playwright :config {}}}})
        (with-redefs [adapters/class-available? (constantly false)]
          (let [{:keys [status message]} (probe-result-for :playwright-dep
                                                           {:project-context (context dir)})]
            (is (= :fail status))
            (is (str/includes? message "not bundled")))))))

  (testing "only fully-bundled adapters configured skips"
    (with-temp-dir
      (fn [dir]
        (make-project! dir {:interfaces {:web {:type :web :adapter :etaoin :config {}}}})
        (let [{:keys [status message]} (probe-result-for :playwright-dep
                                                         {:project-context (context dir)})]
          (is (= :skip status))
          (is (str/includes? message "no configured adapter")))))))

;; -----------------------------------------------------------------------------
;; Probe: :daemon-health
;; -----------------------------------------------------------------------------

(deftest probe-daemon-health-test
  (testing "no instance root skips (temp dir has no config and no .git)"
    (with-temp-dir
      (fn [dir]
        (let [{:keys [status]} (probe-result-for :daemon-health
                                                 {:project-context (context dir)})]
          (is (= :skip status))))))

  (testing "no daemon.edn is healthy"
    (with-temp-dir
      (fn [dir]
        (make-project! dir)
        (let [{:keys [status message]} (probe-result-for :daemon-health
                                                         {:project-context (context dir)})]
          (is (= :ok status))
          (is (str/includes? message "no daemon running"))))))

  (testing "live pid reports alive"
    (with-temp-dir
      (fn [dir]
        (make-project! dir)
        (write-text! (fs/path dir ".shiftlefter" "daemon.edn")
                     (pr-str {:port 59999 :pid own-pid}))
        (let [{:keys [status message]} (probe-result-for :daemon-health
                                                         {:project-context (context dir)})]
          (is (= :ok status))
          (is (str/includes? message "daemon alive"))))))

  (testing "dead pid fails as stale daemon.edn"
    (with-temp-dir
      (fn [dir]
        (make-project! dir)
        (write-text! (fs/path dir ".shiftlefter" "daemon.edn")
                     (pr-str {:port 59999 :pid (dead-pid)}))
        (let [{:keys [status message]} (probe-result-for :daemon-health
                                                         {:project-context (context dir)})]
          (is (= :fail status))
          (is (str/includes? message "stale daemon.edn"))))))

  (testing "malformed daemon.edn fails"
    (with-temp-dir
      (fn [dir]
        (make-project! dir)
        (write-text! (fs/path dir ".shiftlefter" "daemon.edn") "{:pid }}}")
        (let [{:keys [status message]} (probe-result-for :daemon-health
                                                         {:project-context (context dir)})]
          (is (= :fail status))
          (is (str/includes? message "does not parse")))))))

;; -----------------------------------------------------------------------------
;; Probe: :orphan-chromedriver
;; -----------------------------------------------------------------------------

(deftest probe-orphan-chromedriver-test
  (testing "no orphans is healthy"
    (with-redefs [doctor/list-orphan-chromedriver-pids (constantly [])]
      (let [{:keys [status]} (probe-result-for :orphan-chromedriver {})]
        (is (= :ok status)))))

  (testing "orphans fail, scan-only, remediation names sl-34fu and the pids"
    (with-redefs [doctor/list-orphan-chromedriver-pids (constantly [4242 4243])]
      (let [{:keys [status message details]} (probe-result-for :orphan-chromedriver {})]
        (is (= :fail status))
        (is (str/includes? message "sl-34fu"))
        (is (str/includes? message "4242"))
        (is (= [4242 4243] (:pids details)))))))

;; -----------------------------------------------------------------------------
;; Probe: :lib-jars
;; -----------------------------------------------------------------------------

(deftest probe-lib-jars-test
  (testing "no sl/lib is healthy"
    (with-temp-dir
      (fn [dir]
        (make-project! dir)
        (let [{:keys [status message]} (probe-result-for :lib-jars
                                                         {:project-context (context dir)})]
          (is (= :ok status))
          (is (str/includes? message "no user jars"))))))

  (testing "readable jars pass"
    (with-temp-dir
      (fn [dir]
        (make-project! dir)
        (write-zip! (fs/path dir "sl" "lib" "extra.jar"))
        (let [{:keys [status message]} (probe-result-for :lib-jars
                                                         {:project-context (context dir)})]
          (is (= :ok status))
          (is (str/includes? message "1 user jar(s) readable"))))))

  (testing "a truncated jar fails, named"
    (with-temp-dir
      (fn [dir]
        (make-project! dir)
        (write-zip! (fs/path dir "sl" "lib" "good.jar"))
        (write-text! (fs/path dir "sl" "lib" "truncated.jar") "PK-nope")
        (let [{:keys [status message]} (probe-result-for :lib-jars
                                                         {:project-context (context dir)})]
          (is (= :fail status))
          (is (str/includes? message "truncated.jar"))
          (is (not (str/includes? message "good.jar"))))))))

;; -----------------------------------------------------------------------------
;; Runner — statuses, exit mapping, crash containment
;; -----------------------------------------------------------------------------

(defn- stub-entry [probe status]
  {:probe probe
   :desc (str "stub " (name probe))
   :scar "test stub"
   :fn (fn [_ctx] {:probe probe :status status})})

(deftest run-probes-test
  (testing "all ok/skip is healthy; failure counts flip to issues"
    (let [healthy (doctor/run-probes [(stub-entry :a :ok) (stub-entry :b :skip)] {})
          sick (doctor/run-probes [(stub-entry :a :ok) (stub-entry :b :fail)] {})]
      (is (= :healthy (:status healthy)))
      (is (= {:total 2 :ok 1 :warned 0 :failed 0 :skipped 1} (:summary healthy)))
      (is (= :issues (:status sick)))
      (is (= {:total 2 :ok 1 :warned 0 :failed 1 :skipped 0} (:summary sick)))))

  (testing "a warn is counted and never flips :healthy"
    (let [report (doctor/run-probes [(stub-entry :a :ok) (stub-entry :b :warn)] {})]
      (is (= :healthy (:status report)))
      (is (= {:total 2 :ok 1 :warned 1 :failed 0 :skipped 0} (:summary report)))))

  (testing "reports conform to the spec"
    (is (s/valid? :shiftlefter.doctor/report
                  (doctor/run-probes [(stub-entry :a :ok)] {}))))

  (testing "a crashing probe is contained as a structured :fail"
    (let [registry [{:probe :boom :desc "boom" :scar "test stub"
                     :fn (fn [_ctx] (throw (ex-info "kapow" {})))}]
          report (doctor/run-probes registry {})
          [probe-result] (:probes report)]
      (is (= :issues (:status report)))
      (is (= :fail (:status probe-result)))
      (is (str/includes? (:message probe-result) "probe crashed: kapow")))))

;; -----------------------------------------------------------------------------
;; Output modes + CLI exits
;; -----------------------------------------------------------------------------

(deftest format-probe-list-test
  (testing "the registry listing names every probe with its desc, runs nothing"
    (let [throwing [{:probe :never-run :desc "listed but never executed"
                     :scar "test stub"
                     :fn (fn [_ctx] (throw (ex-info "must not run" {})))}]
          listing (doctor/format-probe-list throwing)]
      (is (str/includes? listing "never-run"))
      (is (str/includes? listing "listed but never executed")))
    (let [listing (doctor/format-probe-list)]
      (doseq [{:keys [probe]} doctor/probes]
        (is (str/includes? listing (name probe)))))))

(deftest doctor-cmd-list-test
  (testing "--list exits 0 and prints the registry, not probe results"
    (with-temp-dir
      (fn [dir]
        (let [out (with-out-str
                    (is (= 0 (core/dispatch ["doctor" "--list"]))))]
          (is (str/includes? out "user-config"))
          (is (str/includes? out "chromedriver-lockstep"))
          (is (not (str/includes? out "[OK]")))
          (is (not (str/includes? out "[FAIL]"))))
        (testing "--list --edn is readable data with scars, no fns"
          (let [out (with-out-str
                      (is (= 0 (doctor/doctor-cmd [] {:list true :edn true}
                                                  (context dir)))))
                data (edn/read-string out)]
            (is (= 1 (:doctor/version data)))
            (is (= (mapv :probe doctor/probes) (mapv :probe (:probes data))))
            (is (every? (comp seq :scar) (:probes data)))
            (is (every? #(not (contains? % :fn)) (:probes data)))))))))

(deftest doctor-cmd-edn-report-test
  (testing "--edn emits a versioned, readable report; exit tracks :status"
    (with-temp-home
      (fn [_home]
        (with-temp-dir
          (fn [dir]
            ;; hermetic-ish: fresh home (no machine config), no project.
            ;; Statuses vary by machine (real chromedriver/Chrome probing),
            ;; so assert the shape and the exit/status agreement only.
            (let [exit (volatile! nil)
                  out (with-out-str
                        (vreset! exit (doctor/doctor-cmd [] {:edn true}
                                                        (context dir))))
                  report (edn/read-string out)]
              (is (= 1 (:doctor/version report)))
              (is (s/valid? :shiftlefter.doctor/report report)
                  (s/explain-str :shiftlefter.doctor/report report))
              (is (= (if (= :healthy (:status report)) 0 1) @exit)))))))))
