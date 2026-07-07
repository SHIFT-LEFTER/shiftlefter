(ns shiftlefter.install-test
  "End-to-end test of release/install.sh against a fabricated release-zip
   (no real uberjar/java). Validates the install + emit-only breadcrumb
   behavior (sl-k0s AC1/AC2/AC8). Self-skips where bash/zip/unzip are absent."
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
    ;; Stub sl whose --help has no `init` line (so a future delegation probe
    ;; would fall through; k0s itself only prints, but we keep the shape).
    (spit (str (fs/path stage "sl")) "#!/usr/bin/env bash\necho 'sl stub --help'\n")
    (fs/set-posix-file-permissions (fs/path stage "sl")
                                   (fs/str->posix "rwxr-xr-x"))
    (spit (str (fs/path stage (str "shiftlefter-v" version ".jar"))) "stub-jar\n")
    (spit (str (fs/path stage "agents-breadcrumb.md"))
          "## ShiftLefter\n\nrun `sl agent-doc builtins` and `sl orient`.\n")
    (let [zip (str (fs/path dir (str "shiftlefter-v" version ".zip")))]
      (shell/sh "zip" "-q" "-r" zip (str "shiftlefter-v" version) :dir (str dir))
      zip)))

(defn- run-install [args]
  (apply shell/sh "bash" installer args))

(deftest installs-from-local-zip-and-emits-breadcrumb
  (if-not tools-present?
    (is true "bash/zip/unzip unavailable — skipping installer E2E")
    (let [dir (str (fs/create-temp-dir))]
      (try
        (let [zip (make-fake-zip! dir "9.9.9")
              target (str (fs/path dir "sl"))
              result (run-install ["--zip" zip "--dir" target])]
          (testing "exits cleanly"
            (is (zero? (:exit result)) (:err result)))
          (testing "places the sl wrapper + jar (AC1, AC8 local-zip)"
            (is (fs/exists? (fs/path target "sl")))
            (is (fs/exists? (fs/path target "shiftlefter-v9.9.9.jar"))))
          (testing "prints the breadcrumb to stdout, does not write an agent file (AC2/AC3)"
            (is (str/includes? (:out result) "Agent on-ramp breadcrumb"))
            (is (str/includes? (:out result) "sl agent-doc builtins"))
            (is (str/includes? (:out result) "sl orient"))
            (is (not (fs/exists? (fs/path dir "AGENTS.md"))))
            (is (not (fs/exists? (fs/path target "AGENTS.md")))))
          (testing "prints a PATH instruction without editing shell profiles"
            (is (str/includes? (:out result) "export PATH="))))
        (testing "re-running is idempotent (AC1)"
          (let [zip (str (fs/path dir "shiftlefter-v9.9.9.zip"))
                target (str (fs/path dir "sl"))
                result (run-install ["--zip" zip "--dir" target])]
            (is (zero? (:exit result)) (:err result))
            (is (fs/exists? (fs/path target "sl")))))
        (testing "--no-breadcrumb suppresses the stanza"
          (let [zip (str (fs/path dir "shiftlefter-v9.9.9.zip"))
                target (str (fs/path dir "sl2"))
                result (run-install ["--zip" zip "--dir" target "--no-breadcrumb"])]
            (is (zero? (:exit result)) (:err result))
            (is (not (str/includes? (:out result) "Agent on-ramp breadcrumb")))))
        (finally (fs/delete-tree dir))))))
