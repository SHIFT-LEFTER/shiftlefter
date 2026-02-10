(ns shiftlefter.runner.bundled-capabilities-test
  "Tests proving every bundled library works when loaded via step-loader.

   These tests exercise the real user path: load-file → register → execute.
   Each fixture stepdef in test/fixtures/steps/bundled/ uses a different
   bundled library, proving it's accessible from the uberjar classpath."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.runner.step-loader :as loader]
            [shiftlefter.stepengine.registry :as registry]))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(defn clean-registry-fixture [f]
  (registry/clear-registry!)
  (f)
  (registry/clear-registry!))

(use-fixtures :each clean-registry-fixture)

;; -----------------------------------------------------------------------------
;; Helper
;; -----------------------------------------------------------------------------

(defn- call-step
  "Find a step by pattern and call its function with the given args.
   ctx-first for -> threading. Returns the updated ctx."
  [ctx pattern-src & args]
  (let [stepdef (registry/find-by-pattern pattern-src)]
    (is (some? stepdef) (str "Step not found: " pattern-src))
    (when stepdef
      (apply (:fn stepdef) ctx args))))

;; -----------------------------------------------------------------------------
;; Bundled Library Loading
;; -----------------------------------------------------------------------------

(deftest test-all-bundled-fixtures-load
  (testing "All bundled library fixture stepdefs load without error"
    (let [result (loader/load-step-paths! ["test/fixtures/steps/bundled/"])]
      (is (= :ok (:status result))
          (str "Load failed: " (pr-str (:errors result))))
      ;; 8 files should load (7 lib exercises + clojure_test_steps)
      (is (>= (count (:loaded result)) 8)
          (str "Expected at least 8 files, got " (count (:loaded result))))
      ;; Should have multiple steps registered
      (is (>= (count (registry/all-stepdefs)) 10)
          (str "Expected at least 10 steps, got " (count (registry/all-stepdefs)))))))

;; -----------------------------------------------------------------------------
;; Cheshire (JSON)
;; -----------------------------------------------------------------------------

(deftest test-cheshire-json-round-trip
  (testing "Cheshire JSON encode/decode round-trip from stepdef"
    (loader/load-step-paths! ["test/fixtures/steps/bundled/json_steps.clj"])
    (let [ctx (-> {}
                  (call-step "I encode a map to JSON")
                  (call-step "I decode the JSON back")
                  (call-step "the JSON round-trip should preserve data"))]
      (is (string? (:json-encoded ctx)))
      (is (map? (:json-decoded ctx)))
      (is (= "ShiftLefter" (get-in ctx [:json-decoded :name]))))))

;; -----------------------------------------------------------------------------
;; babashka.fs
;; -----------------------------------------------------------------------------

(deftest test-babashka-fs-operations
  (testing "babashka.fs file operations from stepdef"
    (loader/load-step-paths! ["test/fixtures/steps/bundled/fs_steps.clj"])
    (let [ctx (-> {}
                  (call-step "I create a temp file with babashka.fs")
                  (call-step "the temp file should exist")
                  (call-step "I delete the temp file"))]
      (is (string? (:temp-file ctx)))
      (is (true? (:temp-file-exists ctx)))
      (is (true? (:temp-file-deleted ctx))))))

;; -----------------------------------------------------------------------------
;; core.async
;; -----------------------------------------------------------------------------

(deftest test-core-async-channel-round-trip
  (testing "core.async channel put/take from stepdef"
    (loader/load-step-paths! ["test/fixtures/steps/bundled/async_steps.clj"])
    (let [ctx (-> {}
                  (call-step "I put a value on a core.async channel")
                  (call-step "I take the value from the channel")
                  (call-step "the channel round-trip should preserve the value"))]
      (is (= {:message "hello from core.async"} (:async-received ctx))))))

;; -----------------------------------------------------------------------------
;; spec.alpha
;; -----------------------------------------------------------------------------

(deftest test-spec-alpha-validation
  (testing "spec.alpha validation from stepdef"
    (loader/load-step-paths! ["test/fixtures/steps/bundled/spec_steps.clj"])
    (let [ctx (-> {}
                  (call-step "I validate data against a spec")
                  (call-step "the spec validation should work correctly"))]
      (is (true? (:spec-valid ctx)))
      (is (true? (:spec-invalid ctx)))
      (is (string? (:spec-explain ctx))))))

;; -----------------------------------------------------------------------------
;; test.check (generators)
;; -----------------------------------------------------------------------------

(deftest test-check-generators
  (testing "test.check generators from stepdef"
    (loader/load-step-paths! ["test/fixtures/steps/bundled/gentest_steps.clj"])
    (let [ctx (-> {}
                  (call-step "I generate values from a spec")
                  (call-step "the generated values should be valid"))]
      (is (seq (:generated-values ctx)))
      (is (every? pos-int? (:generated-values ctx))))))

;; -----------------------------------------------------------------------------
;; Java interop
;; -----------------------------------------------------------------------------

(deftest test-java-interop
  (testing "Java stdlib interop from stepdef"
    (loader/load-step-paths! ["test/fixtures/steps/bundled/java_interop_steps.clj"])
    (let [ctx (-> {}
                  (call-step "I use java.time to get today's date")
                  (call-step "I parse a URI with java.net")
                  (call-step "I compute a SHA-256 hash")
                  (call-step "the Java interop results should be correct"))]
      (is (string? (:java-date ctx)))
      (is (pos? (:java-year ctx)))
      (is (= "shiftlefter.dev" (:uri-host ctx)))
      (is (= 64 (count (:sha256-hash ctx)))))))

;; -----------------------------------------------------------------------------
;; etaoin (namespace loads)
;; -----------------------------------------------------------------------------

(deftest test-etaoin-available
  (testing "etaoin namespace loads from stepdef"
    (loader/load-step-paths! ["test/fixtures/steps/bundled/etaoin_steps.clj"])
    (let [ctx (-> {}
                  (call-step "I can require the etaoin namespace")
                  (call-step "etaoin should be available on the classpath"))]
      (is (true? (:etaoin-loaded ctx)))
      (is (pos? (:etaoin-var-count ctx))))))

;; -----------------------------------------------------------------------------
;; Sharp Edges: Unbundled dep error
;; -----------------------------------------------------------------------------

(deftest test-unbundled-dep-error-message
  (testing "Loading a stepdef with unbundled dep gives actionable error"
    (let [result (loader/load-step-paths!
                  ["test/fixtures/bad_stepdefs/bad_require_steps.clj"])]
      (is (= :error (:status result)))
      (is (= 1 (count (:errors result))))
      ;; Error message or cause should mention the missing namespace
      (let [error-info (get-in (first (:errors result)) [:error])
            combined (str (:message error-info) " " (:cause error-info))]
        (is (string? (:message error-info)))
        (is (re-find #"some[./]nonexistent[./]lib" combined)
            (str "Error should mention missing namespace, got: " combined))))))

;; -----------------------------------------------------------------------------
;; Sharp Edges: clojure.test in stepdefs
;; -----------------------------------------------------------------------------

(deftest test-clojure-test-in-stepdefs
  (testing "clojure.test assertions work in stepdefs"
    (loader/load-step-paths! ["test/fixtures/steps/bundled/clojure_test_steps.clj"])
    (let [ctx (call-step {} "I use clojure.test assertions")]
      (is (true? (:clojure-test-works ctx))))))
