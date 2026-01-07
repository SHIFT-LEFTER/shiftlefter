(ns shiftlefter.gherkin.lexer-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [shiftlefter.gherkin.lexer :as lexer]))

(deftest lex-basic-feature
  (let [input "Feature: Hello World\n"
        tokens (lexer/lex input)]
    (is (= 2 (count tokens)))
    (is (= :feature-line (:type (first tokens))))
    (is (= 1 (:column (:location (first tokens)))))
    (is (= "" (:leading-ws (first tokens))))
    (is (= :eof (:type (second tokens))))))

(deftest lex-with-language-switch
  (let [input "# language: en\nFeature: Test\n"
        tokens (lexer/lex input)]
    (is (= 3 (count tokens)))
    (is (= :language-header (:type (first tokens))))
    (is (= "en" (:value (first tokens))))
    (is (= :feature-line (:type (second tokens))))
    (is (= :eof (:type (last tokens))))))

(deftest lex-blank-lines
  (let [input "\nFeature: Test\n\n"
        tokens (lexer/lex input)]
    ;; Now correctly preserves trailing blank line: blank, feature, blank, eof
    (is (= 4 (count tokens)))
    (is (= :blank (:type (first tokens))))
    (is (= :feature-line (:type (second tokens))))
    (is (= :blank (:type (nth tokens 2))))
    (is (= :eof (:type (nth tokens 3))))))

(deftest lazy-lex-is-seq
  (let [input "Feature: Test\n"
        tokens (lexer/lex input)]
    (is (seq? tokens))))

(deftest lazy-lex-large-input
  (let [large-input (clojure.string/join "\n" (repeat 1000 "Scenario: Test\nGiven foo\n"))
        tokens (lexer/lex large-input)]
    (is (seq? tokens))
    (let [first-token (first tokens)]
      (is (= :scenario-line (:type first-token))))))

(deftest lazy-lex-lang-switch-mid
  (let [input "# language: en\nFeature: English\n# language: fr\nFonctionnalité: French\n"
        tokens (lexer/lex input)]
    (is (= 5 (count tokens)))
    (is (= :language-header (:type (first tokens))))
    (is (= :feature-line (:type (second tokens))))
    (is (= :language-header (:type (nth tokens 2))))
    ;; With i18n support, French "Fonctionnalité" is now recognized as :feature-line
    (is (= :feature-line (:type (nth tokens 3))))
    (is (= "French" (:value (nth tokens 3))))))

(deftest lex-with-indent-spaces
  (let [input "  Feature: Indented\n    Scenario: Test\n"
        tokens (lexer/lex input)]
    (is (= 3 (count tokens)))
    (let [feature-token (first tokens)]
      (is (= :feature-line (:type feature-token)))
      (is (= 3 (:column (:location feature-token))))
      (is (= "  " (:leading-ws feature-token))))
    (let [scenario-token (second tokens)]
      (is (= :scenario-line (:type scenario-token)))
      (is (= 5 (:column (:location scenario-token))))
      (is (= "    " (:leading-ws scenario-token))))))

(deftest lex-with-indent-tabs
  (let [input "\tFeature: Tabbed\n\t\tScenario: Test\n"
        tokens (lexer/lex input)]
    (is (= 3 (count tokens)))
    (let [feature-token (first tokens)]
      (is (= :feature-line (:type feature-token)))
      (is (= 2 (:column (:location feature-token))))
      (is (= "\t" (:leading-ws feature-token))))
    (let [scenario-token (second tokens)]
      (is (= :scenario-line (:type scenario-token)))
      (is (= 3 (:column (:location scenario-token))))
      (is (= "\t\t" (:leading-ws scenario-token))))))

(deftest lex-blank-with-ws
  (let [input "  \nFeature: Test\n"
        tokens (lexer/lex input)]
    (is (= 3 (count tokens)))
    (let [blank-token (first tokens)]
      (is (= :blank (:type blank-token)))
      (is (= 1 (:column (:location blank-token))))
      (is (= "  " (:leading-ws blank-token))))))

(deftest lex-mixed-indent
  (let [input " \t Feature: Mixed\n"
        tokens (lexer/lex input)]
    (is (= 2 (count tokens)))
    (let [feature-token (first tokens)]
      (is (= :feature-line (:type feature-token)))
      (is (= 4 (:column (:location feature-token))))
      (is (= " \t " (:leading-ws feature-token))))))

(deftest lex-tags
  (let [input "@slow @web\nFeature: Tagged\n"
        tokens (lexer/lex input)]
    (is (= 3 (count tokens)))
    (let [tag-token (first tokens)]
      (is (= :tag-line (:type tag-token)))
      (is (= ["slow" "web"] (:tags (:value tag-token)))))
    (is (= :feature-line (:type (second tokens))))))

(deftest lex-comments
  (let [input "# This is a comment\nFeature: Commented\n"
        tokens (lexer/lex input)]
    (is (= 3 (count tokens)))
    (let [comment-token (first tokens)]
      (is (= :comment (:type comment-token)))
      (is (= "# This is a comment" (:value comment-token))))
    (is (= :feature-line (:type (second tokens))))))

;; -----------------------------------------------------------------------------
;; Tag Line Trailing Content Tests - Item 3: lexer-tag-line-trailing-junk
;; -----------------------------------------------------------------------------

(deftest tag-line-trailing-comment-allowed
  (testing "Tag line with trailing #comment is valid (Gherkin spec allows inline comments)"
    (let [input "@smoke #comment\nFeature: Test\n"
          tokens (lexer/lex input)
          tag-token (first tokens)
          val (:value tag-token)]
      (is (= :tag-line (:type tag-token)))
      (is (= ["smoke"] (:tags val)) "Tags should be extracted")
      (is (nil? (:error val)) "No error for inline comments after tags"))))

(deftest tag-line-trailing-junk-text
  (testing "Tag line with trailing text produces error"
    (let [input "@smoke foo\nFeature: Test\n"
          tokens (lexer/lex input)
          tag-token (first tokens)
          val (:value tag-token)]
      (is (= :tag-line (:type tag-token)))
      (is (= ["smoke"] (:tags val)))
      (is (= "Trailing content after tags: foo" (:error val))))))

(deftest tag-line-trailing-junk-whitespace-in-tag
  (testing "Tag with whitespace in it (@a tag containing whitespace) produces error"
    (let [input "  @a tag containing whitespace\n"
          tokens (lexer/lex input)
          tag-token (first tokens)
          val (:value tag-token)]
      (is (= :tag-line (:type tag-token)))
      (is (= ["a"] (:tags val)) "Only @a is parsed as a tag")
      (is (= "Trailing content after tags: tag containing whitespace" (:error val))))))

(deftest tag-line-valid-no-error
  (testing "Valid tag lines have no error"
    (let [input "@slow @web\nFeature: Test\n"
          tokens (lexer/lex input)
          tag-token (first tokens)
          val (:value tag-token)]
      (is (= :tag-line (:type tag-token)))
      (is (= ["slow" "web"] (:tags val)))
      (is (nil? (:error val)) "No error for valid tag line"))))

(deftest tag-line-valid-trailing-whitespace
  (testing "Tag line with only trailing whitespace is valid"
    (let [input "@smoke   \nFeature: Test\n"
          tokens (lexer/lex input)
          tag-token (first tokens)
          val (:value tag-token)]
      (is (= :tag-line (:type tag-token)))
      (is (= ["smoke"] (:tags val)))
      (is (nil? (:error val)) "Trailing whitespace is allowed"))))

(deftest comment-only-at-line-start
  (testing "# only recognized as comment at line start"
    (let [input "Feature: Test # not a comment\n"
          tokens (lexer/lex input)
          feature-token (first tokens)]
      (is (= :feature-line (:type feature-token)))
      (is (str/includes? (:value feature-token) "# not a comment")
          "# in middle of line is NOT a comment, it's part of the value"))))

(deftest comment-indented-at-line-start
  (testing "Indented # at line start is still a comment"
    (let [input "  # Indented comment\nFeature: Test\n"
          tokens (lexer/lex input)
          comment-token (first tokens)]
      (is (= :comment (:type comment-token)))
      (is (= "# Indented comment" (:value comment-token)))
      (is (= "  " (:leading-ws comment-token))))))

;; -----------------------------------------------------------------------------
;; Table Row Lexing Tests - Item 1: lexer-table-row-raw-and-escaped-pipes
;; -----------------------------------------------------------------------------

(deftest table-row-basic
  (testing "Basic table row with simple cells"
    (let [input "Feature: Test\n  Scenario: Test\n    Given a table\n      | a | b | c |\n"
          tokens (lexer/lex input)
          table-token (first (filter #(= :table-row (:type %)) tokens))
          val (:value table-token)]
      (is (= :table-row (:type table-token)))
      (is (= ["a" "b" "c"] (:cells val)))
      (is (= "      | a | b | c |" (:raw val))))))

(deftest table-row-escaped-pipe
  (testing "Escaped pipe \\| is decoded to | in cell value"
    (let [input "      | a |  b | c\\|d |\n"
          tokens (lexer/lex input)
          table-token (first tokens)
          val (:value table-token)]
      (is (= :table-row (:type table-token)))
      (is (= ["a" "b" "c|d"] (:cells val)) "Escaped pipe should become literal |")
      (is (= "      | a |  b | c\\|d |" (:raw val)) "Raw should preserve escaped form"))))

(deftest table-row-empty-cells
  (testing "Empty cells are preserved"
    (let [input "| a | | c |\n"
          tokens (lexer/lex input)
          table-token (first tokens)
          val (:value table-token)]
      (is (= :table-row (:type table-token)))
      (is (= ["a" "" "c"] (:cells val)) "Empty cell should be preserved as empty string"))))

(deftest table-row-ugly-spacing
  (testing "Ugly spacing - cells are trimmed, raw preserves original"
    (let [input "|a|  b |   c   |\n"
          tokens (lexer/lex input)
          table-token (first tokens)
          val (:value table-token)]
      (is (= ["a" "b" "c"] (:cells val)) "Cells should be trimmed")
      (is (= "|a|  b |   c   |" (:raw val)) "Raw should preserve ugly spacing"))))

(deftest table-row-trailing-spaces
  (testing "Trailing spaces in cells and on line"
    (let [input "| a  | b   |  \n"
          tokens (lexer/lex input)
          table-token (first tokens)
          val (:value table-token)]
      (is (= ["a" "b"] (:cells val)) "Cells should be trimmed of trailing spaces")
      (is (= "| a  | b   |  " (:raw val)) "Raw should preserve trailing spaces"))))

(deftest table-row-tabs
  (testing "Tabs in table row"
    (let [input "|\ta\t|\tb\t|\n"
          tokens (lexer/lex input)
          table-token (first tokens)
          val (:value table-token)]
      (is (= ["a" "b"] (:cells val)) "Cells should be trimmed of tabs")
      (is (= "|\ta\t|\tb\t|" (:raw val)) "Raw should preserve tabs"))))

(deftest table-row-leading-indentation
  (testing "Leading indentation is captured in leading-ws and raw"
    (let [input "      | admin | secret |\n"
          tokens (lexer/lex input)
          table-token (first tokens)
          val (:value table-token)]
      (is (= ["admin" "secret"] (:cells val)))
      (is (= "      | admin | secret |" (:raw val)) "Raw includes leading indentation")
      (is (= "      " (:leading-ws table-token)) "leading-ws captures indentation"))))

(deftest table-row-torture-test
  (testing "Tokenizer torture: ugly spacing + escaped pipe + trailing"
    (let [input "|a|  b | c\\|d |  \n"
          tokens (lexer/lex input)
          table-token (first tokens)
          val (:value table-token)]
      (is (= ["a" "b" "c|d"] (:cells val)) "All edge cases handled correctly")
      (is (= "|a|  b | c\\|d |  " (:raw val)) "Raw preserves everything"))))

;; -----------------------------------------------------------------------------
;; Raw Preservation / No-op Print Tests - Item 2: lexer-token-index-and-raw
;; -----------------------------------------------------------------------------

(deftest token-idx-monotonic
  (testing "Token :idx is monotonic starting at 0"
    (let [input "Feature: Test\n  Scenario: foo\n    Given bar\n"
          tokens (lexer/lex input)
          indices (map :idx tokens)]
      (is (= [0 1 2 3] indices) "Indices should be 0, 1, 2, 3 (eof)"))))

(deftest raw-roundtrip-simple
  (testing "Joining :raw fields reproduces original input"
    (let [input "Feature: Hello\n  Scenario: World\n    Given foo\n"
          tokens (lexer/lex input)
          ;; Exclude EOF token's empty :raw
          non-eof (butlast tokens)
          reconstructed (apply str (map :raw non-eof))]
      (is (= input reconstructed) "Raw fields should reproduce original"))))

(deftest raw-roundtrip-with-blanks
  (testing "Raw preservation works with blank lines"
    (let [input "\nFeature: Test\n\n  Scenario: foo\n"
          tokens (lexer/lex input)
          non-eof (butlast tokens)
          reconstructed (apply str (map :raw non-eof))]
      (is (= input reconstructed)))))

(deftest raw-roundtrip-with-comments
  (testing "Raw preservation works with comments"
    (let [input "# Comment here\nFeature: Test\n  # Another comment\n"
          tokens (lexer/lex input)
          non-eof (butlast tokens)
          reconstructed (apply str (map :raw non-eof))]
      (is (= input reconstructed)))))

(deftest raw-roundtrip-with-tags
  (testing "Raw preservation works with tags"
    (let [input "@slow @web\nFeature: Tagged\n  @smoke\n  Scenario: foo\n"
          tokens (lexer/lex input)
          non-eof (butlast tokens)
          reconstructed (apply str (map :raw non-eof))]
      (is (= input reconstructed)))))

(deftest raw-roundtrip-with-tables
  (testing "Raw preservation works with data tables"
    (let [input "Feature: Test\n  Scenario: foo\n    Given a table\n      | a | b |\n      | 1 | 2 |\n"
          tokens (lexer/lex input)
          non-eof (butlast tokens)
          reconstructed (apply str (map :raw non-eof))]
      (is (= input reconstructed)))))

(deftest raw-roundtrip-no-trailing-newline
  (testing "Raw preservation works without trailing newline"
    (let [input "Feature: Test\n  Scenario: foo"
          tokens (lexer/lex input)
          non-eof (butlast tokens)
          reconstructed (apply str (map :raw non-eof))]
      (is (= input reconstructed)))))

;; -----------------------------------------------------------------------------
;; RT1: Lossless EOL Preservation Tests
;; -----------------------------------------------------------------------------

(defn- roundtrip
  "Helper: lex input and join :raw fields (excluding EOF)"
  [input]
  (apply str (map :raw (butlast (lexer/lex input)))))

(deftest raw-roundtrip-lf-only
  (testing "Roundtrip preserves LF line endings (AC2)"
    (let [input "Feature: LF Test\n  Scenario: S1\n    Given step\n"]
      (is (= input (roundtrip input))))))

(deftest raw-roundtrip-crlf-only
  (testing "Roundtrip preserves CRLF line endings (AC2)"
    (let [input "Feature: CRLF Test\r\n  Scenario: S1\r\n    Given step\r\n"]
      (is (= input (roundtrip input))))))

(deftest raw-roundtrip-cr-only
  (testing "Roundtrip preserves CR line endings (AC2)"
    (let [input "Feature: CR Test\r  Scenario: S1\r    Given step\r"]
      (is (= input (roundtrip input))))))

(deftest raw-roundtrip-mixed-eol
  (testing "Roundtrip preserves mixed EOL types (AC2)"
    (let [input "Feature: Mixed EOL\n  Scenario: LF line\r\n    Given crlf step\r    And cr step\n"]
      (is (= input (roundtrip input))))))

(deftest raw-roundtrip-file-lf
  (testing "Roundtrip file with LF line endings"
    (let [input (slurp "test/fixtures/gherkin/eol-types/lf-only.feature")]
      (is (= input (roundtrip input))))))

(deftest raw-roundtrip-file-crlf
  (testing "Roundtrip file with CRLF line endings"
    (let [input (slurp "test/fixtures/gherkin/eol-types/crlf-only.feature")]
      (is (= input (roundtrip input))))))

(deftest raw-roundtrip-file-cr
  (testing "Roundtrip file with CR line endings"
    (let [input (slurp "test/fixtures/gherkin/eol-types/cr-only.feature")]
      (is (= input (roundtrip input))))))

(deftest raw-roundtrip-file-mixed
  (testing "Roundtrip file with mixed EOL types"
    (let [input (slurp "test/fixtures/gherkin/eol-types/mixed-eol.feature")]
      (is (= input (roundtrip input))))))