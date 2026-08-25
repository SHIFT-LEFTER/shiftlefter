(ns shiftlefter.install-test
  "End-to-end test of release/install.sh against a fabricated release-zip
   (no real uberjar/java). Validates the install + scaffold-delegation
   behavior: a FRESH install (no sl/ at the caller's cwd) invokes `sl init`
   (the cn7 fold — one write moment); a PRE-EXISTING sl/ skips the scaffold
   and falls back to printing the breadcrumb stanza (k0s AC2 emit path).
   The zip's `sl` is a stub that echoes its argv, so what's asserted here is
   the installer's wiring, not the scaffold itself (that's
   shiftlefter.init-scaffold-test). Self-skips where bash/zip/unzip are
   absent."
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- have? [cmd]
  (try (zero? (:exit (shell/sh "sh" "-c" (str "command -v " cmd))))
       (catch Exception _ false)))

(def ^:private tools-present?
  (and (have? "bash") (have? "zip") (have? "unzip")))

(def ^:private installer
  (str (fs/real-path "release/install.sh")))

(defn- make-fake-zip!
  "Build a minimal release-zip: a shiftlefter-vX/ dir with a stub `sl`, a stub
   jar, and the breadcrumb stanza — the same shape build.clj's release-zip
   produces, but without a real build."
  [dir version]
  (let [stage (fs/path dir (str "shiftlefter-v" version))]
    (fs/create-dirs stage)
    ;; Stub sl that echoes its argv — lets the wiring assertions see that the
    ;; installer invoked `init` without needing a real jar/JVM.
    (spit (str (fs/path stage "sl")) "#!/usr/bin/env bash\necho \"sl stub invoked: $*\"\n")
    (fs/set-posix-file-permissions (fs/path stage "sl")
                                   (fs/str->posix "rwxr-xr-x"))
    (spit (str (fs/path stage (str "shiftlefter-v" version ".jar"))) "stub-jar\n")
    (spit (str (fs/path stage "agents-breadcrumb.md"))
          "## ShiftLefter\n\nrun `sl agent-doc builtins` and `sl orient`.\n")
    (let [zip (str (fs/path dir (str "shiftlefter-v" version ".zip")))]
      (shell/sh "zip" "-q" "-r" zip (str "shiftlefter-v" version) :dir (str dir))
      zip)))

(defn- run-install
  "Run the installer with `cwd` as the caller's working directory — the
   pre-existing-sl/ check reads the cwd, so tests must control it."
  [cwd args]
  (apply shell/sh "bash" installer (concat args [:dir cwd])))

(deftest fresh-install-scaffolds-via-init
  (if-not tools-present?
    (is true "bash/zip/unzip unavailable — skipping installer E2E")
    (let [dir (str (fs/create-temp-dir))]
      (try
        (let [zip (make-fake-zip! dir "9.9.9")
              cwd (str (fs/path dir "proj"))
              _ (fs/create-dirs cwd)
              result (run-install cwd ["--zip" zip])]
          (testing "exits cleanly"
            (is (zero? (:exit result)) (:err result)))
          (testing "places the sl wrapper + jar (k0s AC1, AC8 local-zip)"
            (is (fs/exists? (fs/path cwd "sl" "sl")))
            (is (fs/exists? (fs/path cwd "sl" "shiftlefter-v9.9.9.jar"))))
          (testing "fresh install delegates to sl init (the cn7 fold)"
            (is (str/includes? (:out result) "Scaffolding project files (sl init)"))
            (is (str/includes? (:out result) "sl stub invoked: init")))
          (testing "the scaffold path does not print the paste-me breadcrumb banner"
            (is (not (str/includes? (:out result) "Agent on-ramp breadcrumb"))))
          (testing "prints a PATH instruction without editing shell profiles"
            (is (str/includes? (:out result) "export PATH="))))
        (finally (fs/delete-tree dir))))))

(deftest fresh-install-writes-binaries-only-gitignore
  ;; The F2 AC (sl-zjf5): in a scratch git repo, a fresh install followed by
  ;; `git add -A` stages zero binary artifacts (jar, .jsa CDS archive, sl/lib
  ;; jars) while every authored file stays committable. The zip carries only
  ;; the jar, so the runtime-generated .jsa and a user lib jar (sl-5ee5) are
  ;; fabricated; authored files are hand-made because the stub `sl` scaffolds
  ;; nothing.
  (if-not (and tools-present? (have? "git"))
    (is true "bash/zip/unzip/git unavailable — skipping installer gitignore AC")
    (let [dir (str (fs/create-temp-dir))]
      (try
        (let [zip (make-fake-zip! dir "9.9.9")
              cwd (str (fs/path dir "proj"))]
          (fs/create-dirs cwd)
          (shell/sh "git" "init" "-q" :dir cwd)
          (let [result (run-install cwd ["--zip" zip])]
            (is (zero? (:exit result)) (:err result)))
          (spit (str (fs/path cwd "sl" "shiftlefter-v9.9.9.jsa")) "stub-jsa\n")
          (fs/create-dirs (fs/path cwd "sl" "lib"))
          (spit (str (fs/path cwd "sl" "lib" "dep.jar")) "stub-dep\n")
          (spit (str (fs/path cwd "sl" "shiftlefter.edn")) "{}\n")
          (fs/create-dirs (fs/path cwd "sl" "features"))
          (spit (str (fs/path cwd "sl" "features" "login.feature"))
                "Feature: login\n")
          (shell/sh "git" "add" "-A" :dir cwd)
          (let [staged (->> (shell/sh "git" "status" "--porcelain" :dir cwd)
                            :out
                            str/split-lines
                            (remove str/blank?)
                            (map #(subs % 3))
                            set)]
            (testing "zero binary artifacts staged"
              (is (not-any? #(re-find #"\.jar$|\.jsa$|^sl/lib/" %) staged)
                  (pr-str staged)))
            (testing "authored files and the ignore file itself staged"
              (doseq [f ["sl/shiftlefter.edn"
                         "sl/features/login.feature"
                         "sl/.gitignore"]]
                (is (contains? staged f) (pr-str staged))))))
        (finally (fs/delete-tree dir))))))

(deftest preexisting-gitignore-survives-reinstall
  ;; Write-if-absent: a user's customized sl/.gitignore is authored content —
  ;; the installer never clobbers it (the results-root precedent).
  (if-not tools-present?
    (is true "bash/zip/unzip unavailable — skipping installer E2E")
    (let [dir (str (fs/create-temp-dir))]
      (try
        (let [zip (make-fake-zip! dir "9.9.9")
              cwd (str (fs/path dir "proj"))
              custom "# mine — hands off\n*.jar\n"]
          (fs/create-dirs (fs/path cwd "sl"))
          (spit (str (fs/path cwd "sl" ".gitignore")) custom)
          (let [result (run-install cwd ["--zip" zip])]
            (is (zero? (:exit result)) (:err result))
            (is (= custom (slurp (str (fs/path cwd "sl" ".gitignore")))))))
        (finally (fs/delete-tree dir))))))

(deftest preexisting-sl-skips-scaffold-and-prints-breadcrumb
  (if-not tools-present?
    (is true "bash/zip/unzip unavailable — skipping installer E2E")
    (let [dir (str (fs/create-temp-dir))]
      (try
        (let [zip (make-fake-zip! dir "9.9.9")
              cwd (str (fs/path dir "proj"))]
          (fs/create-dirs (fs/path cwd "sl"))
          (let [result (run-install cwd ["--zip" zip])]
            (testing "still refreshes the tool (idempotent overwrite, k0s AC1)"
              (is (zero? (:exit result)) (:err result))
              (is (fs/exists? (fs/path cwd "sl" "sl"))))
            (testing "skips the scaffold and prints the breadcrumb instead (k0s AC2)"
              (is (str/includes? (:out result) "skipping the project scaffold"))
              (is (not (str/includes? (:out result) "sl stub invoked: init")))
              (is (str/includes? (:out result) "Agent on-ramp breadcrumb"))
              (is (str/includes? (:out result) "sl agent-doc builtins"))
              (is (str/includes? (:out result) "sl orient"))
              (is (not (fs/exists? (fs/path cwd "AGENTS.md"))))))
          (testing "--no-breadcrumb suppresses the stanza on the skip path"
            (let [result (run-install cwd ["--zip" zip "--no-breadcrumb"])]
              (is (zero? (:exit result)) (:err result))
              (is (not (str/includes? (:out result) "Agent on-ramp breadcrumb"))))))
        (finally (fs/delete-tree dir))))))
