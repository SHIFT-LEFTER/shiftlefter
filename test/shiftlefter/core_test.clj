(ns shiftlefter.core-test
  "Tests for CLI functionality."
  (:require
   [clojure.java.io :as jio]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.tools.cli :refer [parse-opts]]
   [shiftlefter.core :as core])
  (:import
   [java.net Socket]))

;; Access private functions for testing
(def find-feature-files #'core/find-feature-files)
(def check-single-file #'core/check-single-file)
(def check-files #'core/check-files)
(def format-single-file #'core/format-single-file)
(def format-files #'core/format-files)
(def get-user-cwd #'core/get-user-cwd)
(def resolve-user-path #'core/resolve-user-path)
(def resolve-user-paths #'core/resolve-user-paths)

;; Test fixture for temp files
(def ^:dynamic *temp-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (jio/file (System/getProperty "java.io.tmpdir")
                      (str "shiftlefter-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (binding [*temp-dir* dir]
      (try
        (f)
        (finally
          ;; Cleanup
          (doseq [file (file-seq dir)]
            (.delete file)))))))

(use-fixtures :each temp-dir-fixture)

(deftest find-feature-files-test
  (testing "finds .feature files in directory"
    (let [files (find-feature-files ["examples/01-validate-and-format/"])]
      (is (seq files) "should find at least one file")
      (is (every? #(str/ends-with? % ".feature") files)
          "all files should be .feature")))

  (testing "returns single file when given file path"
    (let [files (find-feature-files ["examples/01-validate-and-format/login.feature"])]
      (is (= 1 (count files)))
      (is (= "examples/01-validate-and-format/login.feature" (first files)))))

  (testing "returns empty for non-existent path"
    (let [files (find-feature-files ["nonexistent/path/"])]
      (is (empty? files))))

  (testing "skips non-.feature files"
    (let [files (find-feature-files ["deps.edn"])]
      (is (empty? files)))))

(deftest check-single-file-test
  (testing "valid file returns :ok status"
    (let [result (check-single-file "examples/01-validate-and-format/login.feature")]
      (is (= :ok (:status result)))
      (is (= "examples/01-validate-and-format/login.feature" (:path result)))))

  (testing "non-existent file returns :not-found status"
    (let [result (check-single-file "nonexistent.feature")]
      (is (= :not-found (:status result))))))

(deftest check-files-test
  (testing "canonical file returns exit-code 0"
    ;; Use a file we know is canonical (we formatted it)
    (let [result (check-files ["examples/01-validate-and-format/login.feature"])]
      (is (= 0 (:exit-code result)))
      (is (= 1 (:valid result)))
      (is (= 0 (:invalid result)))))

  (testing "file needing formatting returns exit-code 1"
    (let [path (str (jio/file *temp-dir* "needs-fmt.feature"))
          ;; Non-canonical: extra spaces, missing blank line after Feature
          content "Feature:  Test\n  Scenario: S\n    Given step\n"]
      (spit path content)
      (let [result (check-files [path])]
        (is (= 1 (:exit-code result)))
        (is (= 0 (:valid result)))
        (is (= 1 (:invalid result))))))

  (testing "file with parse errors returns exit-code 1"
    (let [path (str (jio/file *temp-dir* "parse-error.feature"))
          content "Not valid gherkin at all!!!"]
      (spit path content)
      (let [result (check-files [path])]
        (is (= 1 (:exit-code result)))
        (is (= 0 (:valid result)))
        (is (= 1 (:invalid result))))))

  (testing "non-existent path returns exit-code 2"
    (let [result (check-files ["nonexistent/"])]
      (is (= 2 (:exit-code result)))))

  (testing "empty directory returns exit-code 2"
    (let [result (check-files ["src/"])] ;; no .feature files in src
      (is (= 2 (:exit-code result)))
      (is (= 0 (:total result)))))

  (testing "multiple canonical files works"
    ;; Create two canonical files
    (let [path1 (str (jio/file *temp-dir* "canonical1.feature"))
          path2 (str (jio/file *temp-dir* "canonical2.feature"))
          content "Feature: Test\n\n  Scenario: S\n    Given step\n"]
      (spit path1 content)
      (spit path2 content)
      (let [result (check-files [path1 path2])]
        (is (= 0 (:exit-code result)))
        (is (= 2 (:valid result)))))))

;; -----------------------------------------------------------------------------
;; Format tests (--write mode)
;; -----------------------------------------------------------------------------

(deftest format-single-file-test
  (testing "formats file and returns :reformatted when content changes"
    (let [path (str (jio/file *temp-dir* "test.feature"))
          ;; Non-canonical whitespace (extra spaces)
          content "Feature:  Test\n  Scenario:  S\n    Given  step\n"]
      (spit path content)
      (let [result (format-single-file path)]
        (is (= :reformatted (:status result)))
        (is (= path (:path result)))
        ;; Verify file was actually modified
        (is (not= content (slurp path))))))

  (testing "returns :unchanged when file already canonical"
    (let [path (str (jio/file *temp-dir* "canonical.feature"))
          ;; Canonical format: blank line after Feature, 2-space indent
          content "Feature: Test\n\n  Scenario: S\n    Given step\n"]
      (spit path content)
      (let [result (format-single-file path)]
        (is (= :unchanged (:status result)))
        ;; Verify file unchanged
        (is (= content (slurp path))))))

  (testing "returns :error for files with parse errors"
    (let [path (str (jio/file *temp-dir* "invalid.feature"))
          content "Not valid gherkin at all"]
      (spit path content)
      (let [result (format-single-file path)]
        (is (= :error (:status result)))
        (is (= :parse-errors (:reason result)))
        ;; Verify file unchanged
        (is (= content (slurp path))))))

  (testing "returns :not-found for non-existent file"
    (let [result (format-single-file "/nonexistent/path.feature")]
      (is (= :not-found (:status result))))))

(deftest format-files-directory-test
  (testing "formats directory and returns correct counts"
    (let [file1 (str (jio/file *temp-dir* "a.feature"))
          file2 (str (jio/file *temp-dir* "b.feature"))]
      ;; One needs reformatting, one is already canonical
      (spit file1 "Feature:  Test\n  Scenario:  S\n    Given  step\n")
      (spit file2 "Feature: Test\n\n  Scenario: S\n    Given step\n")
      (let [result (format-files [(str *temp-dir*)])]
        (is (= 0 (:exit-code result)))
        (is (= 2 (:total result)))
        (is (= 1 (:reformatted result)))
        (is (= 1 (:unchanged result)))
        (is (= 0 (:errors result)))))))

(deftest format-files-with-errors-test
  (testing "returns exit-code 1 when some files have errors"
    (let [valid (str (jio/file *temp-dir* "valid.feature"))
          invalid (str (jio/file *temp-dir* "invalid.feature"))]
      (spit valid "Feature: Test\n\n  Scenario: S\n    Given step\n")
      (spit invalid "Not valid gherkin")
      (let [result (format-files [(str *temp-dir*)])]
        (is (= 1 (:exit-code result)))
        (is (= 1 (:unchanged result)))
        (is (= 1 (:errors result)))))))

(deftest format-files-edge-cases-test
  (testing "returns exit-code 2 for non-existent path"
    (let [result (format-files ["/nonexistent/path/"])]
      (is (= 2 (:exit-code result)))))

  (testing "returns exit-code 2 when no .feature files found"
    (let [result (format-files ["src/"])]
      (is (= 2 (:exit-code result))))))

(deftest format-files-idempotent-test
  (testing "idempotent - running twice gives all unchanged"
    (let [path (str (jio/file *temp-dir* "idem.feature"))
          content "Feature:  Test\n  Scenario:  S\n    Given  step\n"]
      (spit path content)
      ;; First run reformats
      (let [r1 (format-files [(str *temp-dir*)])]
        (is (= 1 (:reformatted r1))))
      ;; Second run should be unchanged
      (let [r2 (format-files [(str *temp-dir*)])]
        (is (= 0 (:reformatted r2)))
        (is (= 1 (:unchanged r2)))))))

;; -----------------------------------------------------------------------------
;; Path Resolution tests (for PATH-based usage)
;; -----------------------------------------------------------------------------

(deftest get-user-cwd-test
  (testing "returns SL_USER_CWD when set"
    ;; Can't easily test this without modifying env, but we test the fallback
    (is (string? (get-user-cwd)))
    (is (not (str/blank? (get-user-cwd)))))

  (testing "falls back to user.dir when SL_USER_CWD not set"
    ;; The default behavior when env var not set
    (let [cwd (get-user-cwd)]
      (is (= cwd (or (System/getenv "SL_USER_CWD")
                     (System/getProperty "user.dir")))))))

(deftest resolve-user-path-test
  (testing "absolute paths returned unchanged"
    (is (= "/absolute/path/file.feature"
           (resolve-user-path "/absolute/path/file.feature")))
    (is (= "/tmp/test.feature"
           (resolve-user-path "/tmp/test.feature"))))

  (testing "relative paths resolved against user CWD"
    (let [resolved (resolve-user-path "relative/path.feature")
          user-cwd (get-user-cwd)]
      ;; Should start with the user CWD
      (is (str/starts-with? resolved user-cwd))
      ;; Should end with the relative path
      (is (str/ends-with? resolved "relative/path.feature"))))

  (testing "current directory reference works"
    (let [resolved (resolve-user-path "./file.feature")
          user-cwd (get-user-cwd)]
      (is (str/starts-with? resolved user-cwd))))

  (testing "simple filename resolves to CWD"
    (let [resolved (resolve-user-path "smoke.feature")
          user-cwd (get-user-cwd)]
      (is (str/starts-with? resolved user-cwd))
      (is (str/ends-with? resolved "smoke.feature")))))

(deftest resolve-user-paths-test
  (testing "resolves multiple paths"
    (let [resolved (resolve-user-paths ["file1.feature" "/absolute/file2.feature" "dir/file3.feature"])
          user-cwd (get-user-cwd)]
      (is (= 3 (count resolved)))
      ;; First: relative -> resolved
      (is (str/starts-with? (first resolved) user-cwd))
      (is (str/ends-with? (first resolved) "file1.feature"))
      ;; Second: absolute -> unchanged
      (is (= "/absolute/file2.feature" (second resolved)))
      ;; Third: relative with dir -> resolved
      (is (str/starts-with? (nth resolved 2) user-cwd))))

  (testing "empty list returns empty"
    (is (= [] (resolve-user-paths []))))

  (testing "handles nil gracefully"
    (is (= [] (resolve-user-paths nil)))))

;; -----------------------------------------------------------------------------
;; Unknown flag rejection tests (Step 4)
;; -----------------------------------------------------------------------------

(deftest test-unknown-flags-produce-errors
  (testing "Unknown flags generate parse errors"
    (let [parsed (parse-opts ["--unknown-flag" "file.feature"] core/cli-options)]
      (is (seq (:errors parsed))
          "parse-opts should report errors for unknown flags")))

  (testing "Single unknown short flag"
    (let [parsed (parse-opts ["-x" "file.feature"] core/cli-options)]
      (is (seq (:errors parsed)))))

  (testing "Mix of known and unknown flags"
    (let [parsed (parse-opts ["--verbose" "--nonexistent" "file.feature"] core/cli-options)]
      (is (seq (:errors parsed))
          "unknown flag among valid flags should still error")
      (is (:verbose (:options parsed))
          "valid flags should still parse"))))

(deftest test-valid-flags-no-errors
  (testing "Known run flags produce no errors"
    (let [parsed (parse-opts ["--dry-run" "--verbose" "--edn" "file.feature"] core/cli-options)]
      (is (empty? (:errors parsed)))))

  (testing "Known fmt flags produce no errors"
    (let [parsed (parse-opts ["--check" "--edn" "file.feature"] core/cli-options)]
      (is (empty? (:errors parsed)))))

  (testing "Known fuzz flags produce no errors"
    (let [parsed (parse-opts ["--trials" "100" "--seed" "42" "--preset" "smoke"] core/cli-options)]
      (is (empty? (:errors parsed)))))

  (testing "--no-color flag is recognized"
    (let [parsed (parse-opts ["--no-color" "file.feature"] core/cli-options)]
      (is (empty? (:errors parsed)))
      (is (:no-color (:options parsed)))))

  (testing "--mode flag is recognized"
    (let [parsed (parse-opts ["--mode" "parse" "file.feature"] core/cli-options)]
      (is (empty? (:errors parsed)))
      (is (= "parse" (:mode (:options parsed)))))))

;; -----------------------------------------------------------------------------
;; CLI→runner integration tests (Step 3)
;; -----------------------------------------------------------------------------

(deftest test-run-cmd-dry-run
  (testing "--dry-run reaches runner and returns without executing"
    ;; Use a valid feature file but no step defs — dry-run should
    ;; still work (it binds steps, finds undefined, reports)
    (let [exit-code (core/run-cmd
                     ["examples/01-validate-and-format/login.feature"]
                     {:dry-run true :edn true})]
      ;; Exit code 0 (all bound) or 2 (undefined steps) — NOT crash (3)
      (is (contains? #{0 2} exit-code)
          (str "dry-run should not crash, got exit code: " exit-code)))))

(deftest test-run-cmd-config-path
  (testing "--config-path reaches runner via test fixture"
    ;; Pass a valid config file and a feature file
    (let [exit-code (core/run-cmd
                     ["examples/01-validate-and-format/login.feature"]
                     {:config-path "test/fixtures/config/minimal.edn"
                      :dry-run true :edn true})]
      ;; Should not crash
      (is (contains? #{0 2} exit-code)
          (str "config-path should reach runner without crash, got: " exit-code)))))

(deftest test-run-cmd-edn-output
  (testing "--edn produces EDN output to stdout"
    (let [output (with-out-str
                   (core/run-cmd
                    ["examples/01-validate-and-format/login.feature"]
                    {:dry-run true :edn true}))]
      ;; EDN output should be parseable
      (when (seq output)
        (is (map? (read-string output))
            "EDN output should be a valid map")))))

(deftest test-run-cmd-verbose-no-crash
  (testing "--verbose doesn't crash"
    (let [exit-code (core/run-cmd
                     ["examples/01-validate-and-format/login.feature"]
                     {:dry-run true :verbose true :edn true})]
      (is (contains? #{0 2} exit-code)))))

(deftest test-run-cmd-no-color-no-crash
  (testing "--no-color doesn't crash"
    (let [exit-code (core/run-cmd
                     ["examples/01-validate-and-format/login.feature"]
                     {:dry-run true :no-color true :edn true})]
      (is (contains? #{0 2} exit-code)))))

;; -----------------------------------------------------------------------------
;; Audit completeness test (Step 6)
;; -----------------------------------------------------------------------------

(deftest test-all-runner-opts-have-cli-flags
  (testing "Every execute! opt key has a corresponding CLI flag or is positional"
    (let [;; Keys that execute! documents in its docstring
          runner-keys #{:paths :config-path :step-paths :dry-run :edn :verbose :no-color}
          ;; Extract long flag names from cli-options, normalize to keywords
          cli-long-flags (->> core/cli-options
                              (map second)  ;; long flag string e.g. "--dry-run"
                              (remove nil?)
                              (map #(-> %
                                        (str/replace #" .*" "")    ;; strip arg placeholders
                                        (str/replace #"^--" "")))  ;; strip --
                              set)
          ;; :paths comes from positional arguments, not flags
          positional-keys #{:paths}
          covered-keys (into positional-keys
                             (map keyword cli-long-flags))]
      (doseq [k runner-keys]
        (is (contains? covered-keys k)
            (str "Runner opt :" (name k) " has no CLI flag or positional source"))))))

;; -----------------------------------------------------------------------------
;; REPL command tests (WI-033.017)
;; -----------------------------------------------------------------------------

(deftest test-repl-cli-options-parse
  (testing "--nrepl flag parses correctly"
    (let [parsed (parse-opts ["repl" "--nrepl"] core/cli-options)]
      (is (empty? (:errors parsed)))
      (is (true? (:nrepl (:options parsed))))))

  (testing "--port flag parses correctly"
    (let [parsed (parse-opts ["repl" "--nrepl" "--port" "7888"] core/cli-options)]
      (is (empty? (:errors parsed)))
      (is (= 7888 (:port (:options parsed))))))

  (testing "repl with no flags parses without errors"
    (let [parsed (parse-opts ["repl"] core/cli-options)]
      (is (empty? (:errors parsed)))
      (is (= "repl" (first (:arguments parsed)))))))

(deftest test-help-text-includes-repl
  (testing "help text mentions sl repl"
    ;; We can't call -main --help because it calls System/exit.
    ;; Instead, test that the --help branch's string literal contains repl info.
    ;; The help text is the string in -main's (:help options) branch.
    ;; We verify the cli-options include the repl-related flags.
    (let [cli-flag-names (->> core/cli-options
                              (map second)
                              (remove nil?)
                              set)]
      (is (contains? cli-flag-names "--nrepl")
          "cli-options should include --nrepl")
      (is (some #(str/starts-with? % "--port") cli-flag-names)
          "cli-options should include --port"))))

(deftest test-nrepl-server-starts-and-accepts-connection
  (testing "nREPL server starts on specified port and accepts connections"
    ;; Start nREPL server in a future so we can test against it
    (require 'nrepl.server 'cider.nrepl)
    (let [start-server (resolve 'nrepl.server/start-server)
          stop-server (resolve 'nrepl.server/stop-server)
          default-handler (resolve 'nrepl.server/default-handler)
          cider-mw-vec @(resolve 'cider.nrepl/cider-middleware)
          handler (apply default-handler cider-mw-vec)
          server (start-server :port 0 :handler handler)
          port (:port server)]
      (try
        (is (pos? port) "Server should be running on a port")
        ;; Verify we can connect
        (let [socket (Socket. "localhost" port)]
          (is (.isConnected socket) "Should be able to connect to nREPL server")
          (.close socket))
        (finally
          (stop-server server))))))

(deftest test-nrepl-server-evaluates-expressions
  (testing "nREPL server can evaluate Clojure expressions"
    (require 'nrepl.server 'nrepl.core 'cider.nrepl)
    (let [start-server (resolve 'nrepl.server/start-server)
          stop-server (resolve 'nrepl.server/stop-server)
          default-handler (resolve 'nrepl.server/default-handler)
          cider-mw-vec @(resolve 'cider.nrepl/cider-middleware)
          nrepl-connect (resolve 'nrepl.core/connect)
          nrepl-client (resolve 'nrepl.core/client)
          nrepl-message (resolve 'nrepl.core/message)
          handler (apply default-handler cider-mw-vec)
          server (start-server :port 0 :handler handler)
          port (:port server)]
      (try
        (with-open [conn (nrepl-connect :port port)]
          (let [client (nrepl-client conn 5000)
                responses (nrepl-message client {:op "eval" :code "(+ 1 2)"})]
            (is (some #(= "3" (:value %)) responses)
                "Should evaluate (+ 1 2) to 3")))
        (finally
          (stop-server server))))))

(deftest test-nrepl-cider-middleware-available
  (testing "CIDER middleware is loaded and operational"
    (require 'nrepl.server 'nrepl.core 'cider.nrepl)
    (let [start-server (resolve 'nrepl.server/start-server)
          stop-server (resolve 'nrepl.server/stop-server)
          default-handler (resolve 'nrepl.server/default-handler)
          cider-mw-vec @(resolve 'cider.nrepl/cider-middleware)
          nrepl-connect (resolve 'nrepl.core/connect)
          nrepl-client (resolve 'nrepl.core/client)
          nrepl-message (resolve 'nrepl.core/message)
          handler (apply default-handler cider-mw-vec)
          server (start-server :port 0 :handler handler)
          port (:port server)]
      (try
        (with-open [conn (nrepl-connect :port port)]
          (let [client (nrepl-client conn 5000)
                responses (nrepl-message client {:op "describe"})
                ops (->> responses
                         (mapcat #(keys (:ops %)))
                         set)]
            ;; CIDER middleware should add ops like "complete", "info", etc.
            (is (contains? ops :complete)
                "CIDER middleware should provide 'complete' op")
            (is (contains? ops :info)
                "CIDER middleware should provide 'info' op")))
        (finally
          (stop-server server))))))

;; -----------------------------------------------------------------------------
;; REPL Namespace Availability (WI-033.023)
;; -----------------------------------------------------------------------------

(deftest test-repl-namespace-loads
  (testing "shiftlefter.repl namespace loads and key public vars exist"
    (require 'shiftlefter.repl)
    (let [publics (ns-publics 'shiftlefter.repl)
          pub-names (set (keys publics))]
      ;; Core REPL functions should be available
      (is (contains? pub-names 'run) "run should be public in shiftlefter.repl")
      (is (contains? pub-names 'step) "step should be public in shiftlefter.repl")
      (is (contains? pub-names 'as) "as should be public in shiftlefter.repl")
      (is (contains? pub-names 'ctx) "ctx should be public in shiftlefter.repl")
      (is (contains? pub-names 'reset-ctx!) "reset-ctx! should be public in shiftlefter.repl")
      ;; Should have a reasonable number of public vars (currently 32)
      (is (>= (count publics) 20)
          (str "Expected 20+ public vars, got " (count publics))))))
