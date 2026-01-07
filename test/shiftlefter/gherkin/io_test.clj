(ns shiftlefter.gherkin.io-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.gherkin.io :as io]))

;; -----------------------------------------------------------------------------
;; UTF-8 Enforcement Tests (IO1)
;; -----------------------------------------------------------------------------

(deftest read-file-utf8-valid
  (testing "Valid UTF-8 file reads successfully"
    (let [result (io/read-file-utf8 "examples/quickstart/features/toy-login.feature")]
      (is (= :ok (:status result)))
      (is (string? (:content result)))
      (is (= "examples/quickstart/features/toy-login.feature" (:path result))))))

(deftest read-file-utf8-invalid
  (testing "Invalid UTF-8 file returns distinct error (AC2)"
    (let [result (io/read-file-utf8 "test/fixtures/gherkin/encoding/invalid-utf8.feature")]
      (is (= :error (:status result)))
      (is (= :io/utf8-decode-failed (:reason result)))
      (is (string? (:message result)))
      (is (= "test/fixtures/gherkin/encoding/invalid-utf8.feature" (:path result)))
      ;; AC4: useful location (at least file-level)
      (is (= 1 (get-in result [:location :line])))
      (is (= 1 (get-in result [:location :column]))))))

(deftest read-file-utf8-not-found
  (testing "Missing file returns file-not-found error"
    (let [result (io/read-file-utf8 "nonexistent/file.feature")]
      (is (= :error (:status result)))
      (is (= :io/file-not-found (:reason result)))
      (is (string? (:message result)))
      (is (= "nonexistent/file.feature" (:path result))))))

(deftest slurp-utf8-valid
  (testing "slurp-utf8 returns string for valid file"
    (let [content (io/slurp-utf8 "examples/quickstart/features/toy-login.feature")]
      (is (string? content))
      (is (pos? (count content))))))

(deftest slurp-utf8-invalid-throws
  (testing "slurp-utf8 throws on invalid UTF-8"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"UTF-8"
                          (io/slurp-utf8 "test/fixtures/gherkin/encoding/invalid-utf8.feature")))))

(deftest slurp-utf8-not-found-throws
  (testing "slurp-utf8 throws on missing file"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"File not found"
                          (io/slurp-utf8 "nonexistent/file.feature")))))

;; -----------------------------------------------------------------------------
;; strip-trailing-eol Tests (RT2)
;; -----------------------------------------------------------------------------

(deftest strip-trailing-eol-lf
  (testing "Strips trailing LF"
    (is (= "hello" (io/strip-trailing-eol "hello\n")))
    (is (= "" (io/strip-trailing-eol "\n")))))

(deftest strip-trailing-eol-crlf
  (testing "Strips trailing CRLF"
    (is (= "hello" (io/strip-trailing-eol "hello\r\n")))
    (is (= "" (io/strip-trailing-eol "\r\n")))))

(deftest strip-trailing-eol-cr
  (testing "Strips trailing CR (classic Mac)"
    (is (= "hello" (io/strip-trailing-eol "hello\r")))
    (is (= "" (io/strip-trailing-eol "\r")))))

(deftest strip-trailing-eol-no-eol
  (testing "Returns unchanged when no trailing EOL"
    (is (= "hello" (io/strip-trailing-eol "hello")))
    (is (= "" (io/strip-trailing-eol "")))))

(deftest strip-trailing-eol-only-one
  (testing "Strips only one trailing EOL (leaves earlier ones)"
    (is (= "a\nb" (io/strip-trailing-eol "a\nb\n")))
    (is (= "a\r\nb" (io/strip-trailing-eol "a\r\nb\r\n")))
    (is (= "a\rb" (io/strip-trailing-eol "a\rb\r")))))

(deftest strip-trailing-eol-nil
  (testing "Handles nil input (returns empty string)"
    (is (= "" (io/strip-trailing-eol nil)))))

(deftest strip-trailing-eol-embedded-eol
  (testing "Does not strip embedded EOL characters"
    (is (= "\nhello" (io/strip-trailing-eol "\nhello\n")))
    (is (= "\r\nhello" (io/strip-trailing-eol "\r\nhello\r\n")))
    (is (= "\rhello" (io/strip-trailing-eol "\rhello\r")))))
