(ns shiftlefter.paths-test
  "Tests for the user-cwd path resolver (single source of truth)."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [shiftlefter.paths :as paths]))

(deftest user-cwd-test
  (testing "returns a non-blank string"
    (is (string? (paths/user-cwd)))
    (is (not (str/blank? (paths/user-cwd)))))

  (testing "is SL_USER_CWD when set, else user.dir (default: *user-cwd* unbound)"
    (is (nil? paths/*user-cwd*) "default is nil — cold path unchanged")
    (is (= (paths/user-cwd)
           (or (System/getenv "SL_USER_CWD")
               (System/getProperty "user.dir"))))))

(deftest user-cwd-dynamic-override-test
  (testing "bound *user-cwd* takes precedence (the daemon's per-request seam)"
    (binding [paths/*user-cwd* "/tmp/bound"]
      (is (= "/tmp/bound" (paths/user-cwd)))
      (is (= "/tmp/bound/x.feature" (paths/resolve-user-path "x.feature")))))

  (testing "unbinding restores the SL_USER_CWD/user.dir fallback"
    (is (= (paths/user-cwd)
           (or (System/getenv "SL_USER_CWD")
               (System/getProperty "user.dir"))))))

(deftest resolve-user-path-test
  (testing "absolute paths returned unchanged"
    (is (= "/absolute/path/file.feature"
           (paths/resolve-user-path "/absolute/path/file.feature"))))

  (testing "relative paths resolved against user-cwd"
    (with-redefs [paths/user-cwd (constantly "/tmp/proj")]
      (is (= "/tmp/proj/relative/path.feature"
             (paths/resolve-user-path "relative/path.feature")))
      (is (= "/tmp/proj/smoke.feature"
             (paths/resolve-user-path "smoke.feature"))))))

(deftest resolve-user-paths-test
  (testing "resolves a mix of relative and absolute paths"
    (with-redefs [paths/user-cwd (constantly "/tmp/proj")]
      (is (= ["/tmp/proj/a.feature" "/abs/b.feature" "/tmp/proj/dir/c.feature"]
             (paths/resolve-user-paths ["a.feature" "/abs/b.feature" "dir/c.feature"])))))

  (testing "empty and nil"
    (is (= [] (paths/resolve-user-paths [])))
    (is (= [] (paths/resolve-user-paths nil)))))
