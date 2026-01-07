;; ShiftLefter Gherkin REPL — Working Snippets and Tests
;; Keep this updated with live, runnable examples.
;; All code here should work without errors.

;; New parser with defrecords, source-text, and outline flagging

;; Example: Parse a simple feature with records
(def sample-input "Feature: Login\nScenario: User logs in\nGiven I am on login page\nWhen I enter credentials\nThen I see dashboard")
(def sample-tokens (lexer/lex sample-input))
(def sample-ast (parser/parse sample-tokens))
;; Inspect: (:ast sample-ast) -> vector with Feature record
;; (instance? shiftlefter.gherkin.parser.Feature (first (:ast sample-ast))) -> true
;; (:source-text (first (:ast sample-ast))) -> "Feature: Login"

;; Example: Scenario outline parsed
(def outline-input "Feature: Outline\nScenario Outline: Test\nGiven <x>\nExamples:\n| x |\n| 1 |")
;; note to GPT - even the very trimmed down (def outline-input "Scenario Outline:\n") triggers the behavior.
(def outline-tokens (lexer/lex outline-input))
(def outline-ast (parser/parse outline-tokens))
;; (:errors outline-ast) -> []
;; (:type (first (:scenarios (first (:ast outline-ast))))) -> :scenario-outline
;; (count (:examples (first (:scenarios (first (:ast outline-ast)))))) -> 1

;; Compliance Shims Testing
;; Load compliance namespace
(require '[shiftlefter.gherkin.compliance :as compliance])

;; Example: Tokens shim on minimal feature
(def minimal-content (slurp "compliance/testdata/good/minimal.feature"))
(def minimal-tokens (lexer/lex minimal-content))
(def minimal-tokens-ndjson (compliance/tokens->ndjson minimal-tokens))
;; minimal-tokens-ndjson -> string matching Cucumber .tokens format with correct cols

;; Example: AST shim (note: AST parsing incomplete, so output may have nulls)
(def minimal-ast (:ast (parser/parse minimal-tokens)))
(def minimal-ast-ndjson (compliance/ast->ndjson minimal-ast "../testdata/good/minimal.feature"))
;; minimal-ast-ndjson -> JSON string with gherkinDocument structure

;; Benchmark: Tokens processing
(time (dotimes [_ 1000] (compliance/tokens->ndjson minimal-tokens)))
;; ~fast, transducers compose O(1) overhead

;; Example: End-to-end with tables and docstrings
(def complex-input "Feature: Complex\nBackground: Setup\nGiven base\nScenario: With table\nGiven step\n| a | b |\n| 1 | 2 |\nAnd docstring\n\"\"\"\ncontent\n\"\"\"")
(def complex-tokens (lexer/lex complex-input))
(def complex-ast (parser/parse complex-tokens))
;; (count (:ast complex-ast)) -> 1 (Feature)
;; (count (:scenarios (first (:ast complex-ast)))) -> 1
;; (count (:steps (first (:scenarios (first (:ast complex-ast)))))) -> 3 (background + 2)

;; Benchmark partial lazy parsing
;; (time (doall (take 10 (lexer/lex (apply str (repeat 100 "Given step\n")))))) ; lex lazy

;; Example: Invalid keyword error in description (post-fix)
(def bad-input "Feature: Bad\nScenari: Invalid\nGiven step")
(def bad-tokens (lexer/lex bad-input))
(def bad-result (parser/parse bad-tokens))
;; (:errors bad-result) -> contains {:type :invalid-keyword, :message "Invalid keyword: Scenari:"}

;; Example: Parse step with data table
(def table-input "Feature: Table\nScenario: Test\nGiven data\n| name | age |\n| Alice | 30 |")
(def table-tokens (lexer/lex table-input))
(def table-ast (parser/parse table-tokens))
;; (:argument (first (:steps (first (:scenarios (first (:ast table-ast))))))) -> DataTable record
;; (:rows (:argument (first (:steps (first (:scenarios (first (:ast table-ast)))))))) -> [TableRow, TableRow]

;; Example: Parse step with docstring
(def doc-input "Feature: Doc\nScenario: Test\nGiven content\n\"\"\"\nHello\nWorld\n\"\"\"")
(def doc-tokens (lexer/lex doc-input))
(def doc-ast (parser/parse doc-tokens))
;; (:argument (first (:steps (first (:scenarios (first (:ast doc-ast))))))) -> Docstring record
;; (:content (:argument (first (:steps (first (:scenarios (first (:ast doc-ast)))))))) -> "Hello\nWorld\n"

;; Example: Pickle with arguments
(def pickle-input "Feature: Pickle\nScenario: Args\nGiven table\n| x | y |\n| a | b |\nWhen doc\n```json\n{}\n```")
(def pickle-tokens (lexer/lex pickle-input))
(def pickle-parsed (parser/parse pickle-tokens))
(def pickle-result (pickler/pre-pickles (:ast pickle-parsed) {} "pickle.feature"))
;; (:step/arguments (first (:pickle/steps (first pickle-result)))) -> [["x" "y"] ["a" "b"]]
;; (:step/arguments (second (:pickle/steps (first pickle-result)))) -> {:content "{}\n", :mediaType "json"}

;; Example: Pickle Pipeline Phase Inspection
;; The pickle pipeline has two phases that can be inspected separately:
;;   Phase 1: ast->pickle-plan (structure extraction, no UUIDs)
;;   Phase 2: pickle-plan->pickles (UUID generation, outline expansion)
(def pipeline-input "Feature: Pipeline\nScenario: Demo\nGiven a step")
(def pipeline-tokens (lexer/lex pipeline-input))
(def pipeline-ast (:ast (parser/parse pipeline-tokens)))
;; Phase 1: Extract pickle plan (inspectable structure, no UUIDs)
(def pickle-plan (pickler/ast->pickle-plan pipeline-ast "demo.feature"))
;; (:features pickle-plan) -> [{:name "Pipeline", :tags [], :scenarios [...]}]
;; (:source-file pickle-plan) -> "demo.feature"
;; (-> pickle-plan :features first :scenarios first :steps first :keyword) -> "Given"
;; Phase 2: Generate final pickles with UUIDs
(def final-pickles (pickler/pickle-plan->pickles pickle-plan))
;; (:pickles final-pickles) -> seq of pickles with UUIDs
;; (:errors final-pickles) -> []

;; Example: Tagged and commented feature with blanks
(def tagged-input "# This is a comment\n\n@web @api\nFeature: Tagged Feature\n\n@slow\nScenario: Tagged Scenario\nGiven a step")
(def tagged-tokens (lexer/lex tagged-input))
(def tagged-ast (parser/parse tagged-tokens))
;; Inspect: (:ast tagged-ast) -> [Comment, Blank, Feature with :tags ["web" "api"], Scenario with :tags ["slow"]]
;; (:type (first (:ast tagged-ast))) -> :comment
;; (:tags (first (:scenarios (nth (:ast tagged-ast) 2)))) -> ["slow"]
;; (time (parser/parse (take 50 sample-tokens))) ; partial parse

(require '[shiftlefter.gherkin.lexer :as lexer])
(require '[shiftlefter.gherkin.parser :as parser])
(require '[shiftlefter.gherkin.tokens :as tokens])
(require '[shiftlefter.gherkin.macros :as macros])
(require '[shiftlefter.gherkin.macros.ini :as ini])
(require '[shiftlefter.gherkin.pickler :as pickler])
(require '[clojure.pprint :as pp])

;; -----------------------------------------------------------------------------
;; Sample Gherkin inputs for testing
;; -----------------------------------------------------------------------------
  
(def simple-feature
  "Feature: Login
  As a user
  I want to login
 
  Scenario: Successful login
    Given I am on the login page
    When I enter valid credentials
    Then I should be logged in")

(def feature-with-macro
  "Feature: Macro test
  Scenario: Use macro
    Given Log in as admin +
    Then check result")

(def feature-with-docstring
  "Feature: Docstring test
  Scenario: With docstring
    Given I have a docstring
      \"\"\"
      Some multiline
      content
      \"\"\"
    Then verify")

(def feature-with-table
  "Feature: Table test
  Scenario: With table
    Given users:
      | name | age |
      | Alice| 30  |
      | Bob  | 25  |")

(def feature-with-outline
  "Feature: Outline test
  Scenario Outline: Login with different users
    Given I am on the login page
    When I enter <username> and <password>
    Then I should be logged in as <role>

    Examples:
      | username | password | role  |
      | admin    | pass123  | admin |
      | user     | pass456  | user  |")

(def malformed-feature
  "Feature: Bad
  Background:
   Unknown line
  Scena:rio: Ok
    Given step")

;; -----------------------------------------------------------------------------
;; REPL tests — Simple feature → AST
;; -----------------------------------------------------------------------------

(comment
  ;; Simple feature parsing
  (def tokens (lexer/lex simple-feature))
  (def result (parser/parse tokens))
  (:ast result)  ;; Should show feature with scenario
  (:errors result)  ;; Should be empty

  ;; Golden AST check (manual verification)
  ;; Expected: [{:type :feature, :name "Login", :description "As a user\nI want to login\n\n", :tags [], :background nil, :scenarios [{:type :scenario, :name "Successful login", :tags [], :steps [...], :examples nil, :location ...}], :location ...}]

  ,)

;; -----------------------------------------------------------------------------
;; REPL tests — Macro steps → :macro-step nodes
;; -----------------------------------------------------------------------------
 
(comment
  (def tokens (lexer/lex feature-with-macro))
  (def result (parser/parse tokens))
  (:ast result)
  ;; Check steps: one should be {:type :macro-step, :text "setup", :argument nil, :location ...}

  ,)

;; -----------------------------------------------------------------------------
;; REPL tests — Docstrings → fence preserved
;; -----------------------------------------------------------------------------

(comment
  (def tokens (lexer/lex feature-with-docstring))
  (def result (parser/parse tokens))
  (:ast result)
  ;; Check docstring: {:type :docstring, :content "Some multiline\ncontent\n", :fence :triple-quote, :language nil, :location ...}

  ,)

;; -----------------------------------------------------------------------------
;; REPL tests — Tables preserved
;; -----------------------------------------------------------------------------

(comment
  (def tokens (lexer/lex feature-with-table))
  (def result (parser/parse tokens))
  (:ast result)
  ;; Check table in step argument

  ,)

;; -----------------------------------------------------------------------------
;; REPL tests — Rule keyword parsing
;; -----------------------------------------------------------------------------

(comment
  ;; Basic feature with rules
  (def rule-feature "Feature: Rules demo

  Background:
    Given feature-level setup

  Rule: First rule
    The first rule description

    Background:
      Given rule-level setup

    Scenario: Scenario in first rule
      Given step1

  @rule-tag
  Rule: Second rule
    Scenario: Scenario in second rule
      Given step2
")

  (def tokens (lexer/lex rule-feature))
  (def result (parser/parse tokens))
  (:errors result)  ;; Should be empty

  (def feature (first (:ast result)))
  (parser/get-background feature)       ;; Feature-level background
  (parser/get-rules feature)            ;; Two rules

  (def first-rule (first (parser/get-rules feature)))
  (:name first-rule)                    ;; "First rule"
  (:description first-rule)             ;; "The first rule description\n\n"
  (:tags first-rule)                    ;; []
  (parser/get-background first-rule)    ;; Rule-level background
  (parser/get-scenarios first-rule)     ;; Scenarios in this rule

  (def second-rule (second (parser/get-rules feature)))
  (:name second-rule)                   ;; "Second rule"
  (:tags second-rule)                   ;; ["rule-tag"]

  ,)
 
;; -----------------------------------------------------------------------------
;; REPL tests — Malformed → errors with locations
;; -----------------------------------------------------------------------------

(comment
  (def tokens (lexer/lex malformed-feature))
  (def result (parser/parse tokens))
  (:errors result)  ;; Should have {:type :unexpected-token, :location ..., :token ...}

  ,)

;; -----------------------------------------------------------------------------
;; REPL tests — INI Macro Parser
;; -----------------------------------------------------------------------------

(comment
  ;; Valid macro: login-as-admin
  (ini/parse-macro "test/resources/macros/all-macros.ini" "Log in as admin")
  ;; → {:macro #shiftlefter.gherkin.macros.ini.Macro{:name "Log in as admin", :description "Standard login flow", :steps [...]}, :errors []}

  ;; Valid macro: create-valid-user
  (ini/parse-macro "test/resources/macros/all-macros.ini" "Create valid user")
  ;; → {:macro #shiftlefter.gherkin.macros.ini.Macro{...}, :errors []}

  ;; Valid macro: navigate-to-dashboard
  (ini/parse-macro "test/resources/macros/all-macros.ini" "Navigate to dashboard")
  ;; → {:macro #shiftlefter.gherkin.macros.ini.Macro{...}, :errors []}

  ;; Invalid macro: bad-indent
  (ini/parse-macro "test/resources/macros/all-macros.ini" "bad-indent")
  ;; → {:macro nil, :errors [#shiftlefter.gherkin.macros.ini.ValidationError{:message "Inconsistent indentation in steps", :location nil}]}

  ;; Invalid macro: not-steps
  (ini/parse-macro "test/resources/macros/all-macros.ini" "not-steps")
  ;; → {:macro nil, :errors [#shiftlefter.gherkin.macros.ini.ValidationError{:message "Invalid step line: 'Background: This is not allowed'", :location #shiftlefter.gherkin.macros.ini.Location{:line 48, :col 5}} ...]}

  ,)

(def test-macros-dir "test/resources/macros/")
(let [feature-str "Feature: Test\n  As a user\n  \n  Scenario: Test\n    Given Log in as admin +\n    Then check"
      tokens (lexer/lex feature-str)
      pre-ast (:ast (parser/parse tokens))
      registry (macros/load-macro-registry test-macros-dir)
      expanded (macros/expand-ast pre-ast registry)]
  (pp/pprint expanded))

;; -----------------------------------------------------------------------------
;; REPL tests — Pickler: expanded AST → flat pickles
;; -----------------------------------------------------------------------------

(comment
  ;; Basic pickle generation from expanded AST
  (let [feature-str "Feature: Test Feature\n  As a user\n  \n  Scenario: Test Scenario\n    Given I do something\n    When I do another\n    Then I check result"
        tokens (lexer/lex feature-str)
        pre-ast (:ast (parser/parse tokens))
        registry (macros/load-macro-registry test-macros-dir)
        expanded-ast (macros/expand-ast pre-ast registry)
        pickles (pickler/pickles expanded-ast registry "features/test.feature")]
    (pp/pprint pickles))
  ;; Expected: {:pickles [{:pickle/id ..., :pickle/name "Test Scenario", :pickle/source-file "features/test.feature", :pickle/location {:line 4, :column 1}, :pickle/tags (), :pickle/steps [{:step/id ..., :step/text "I do something", :step/keyword "Given", :step/location {:line 5, :col 5}, :step/source nil} ...]}], :errors []}

  ;; With macro expansion
  (let [feature-str "Feature: Macro Feature\n  Scenario: Macro Scenario\n    Given Log in as admin +\n    Then check"
        tokens (lexer/lex feature-str)
        pre-ast (:ast (parser/parse tokens))
        registry (macros/load-macro-registry test-macros-dir)
        expanded-ast (macros/expand-ast pre-ast registry)
        pickles (pickler/pickles expanded-ast registry "features/macro.feature")]
    (pp/pprint pickles))
  ;; Expected: {:pickles [pickle with expanded steps from macro, :source on each], :errors []}

  ;; Exact macro example for batch 2
  (let [feature-str "Feature: Test\n  As a user\n  \n  Scenario: Test\n    Given Log in as admin +\n    Then check"
        tokens (lexer/lex feature-str)
        pre-ast (:ast (parser/parse tokens))
        registry (macros/load-macro-registry test-macros-dir)
        expanded-ast (macros/expand-ast pre-ast registry)
        pickles (pickler/pickles expanded-ast registry "features/test.feature")
        ]
    (pp/pprint pickles))
  ;; Should match the structure with :source maps preserved on expanded steps

  ;; Scenario Outline with Examples → N pickles
  (let [tokens (lexer/lex feature-with-outline)
        pre-ast (:ast (parser/parse tokens))
        registry (macros/load-macro-registry test-macros-dir)
        expanded-ast (macros/expand-ast pre-ast registry)
        pickles (pickler/pickles expanded-ast registry "features/outline.feature")]
    (pp/pprint pickles))
  ;; Expected: 2 pickles (one per Examples row), each with :pickle/outline-id, steps having :step/arguments as {"username" "admin", "password" "pass123", "role" "admin"} etc., :step/text with <placeholders>
  ;; NOTE:  the above is aspirational.  we do not yet handle Examples yet (and when we do, the given example has 3 rows, not 2.)

  ,)

;; -----------------------------------------------------------------------------
;; REPL tests — Pre-pickles: pre-expansion AST → pickles with macro provenance
;; -----------------------------------------------------------------------------

(comment
  ;; Basic pre-pickles without macros
  (let [feature-str "Feature: Test\n  Scenario: Basic\n    Given regular step\n    Then another"
        tokens (lexer/lex feature-str)
        pre-ast (:ast (parser/parse tokens))
        registry (macros/load-macro-registry test-macros-dir)
        pickles (pickler/pre-pickles pre-ast registry "test.feature")]
    (pp/pprint pickles))
  ;; Expected: one pickle, steps with nil :source

  ;; Pre-pickles with macro
  (let [feature-str "Feature: Test\n  Scenario: Macro\n    Given Log in as admin +\n    Then check"
        tokens (lexer/lex feature-str)
        pre-ast (:ast (parser/parse tokens))
        registry (macros/load-macro-registry test-macros-dir)
        pickles (pickler/pre-pickles pre-ast registry "test.feature")]
    (pp/pprint pickles))
  ;; Expected: one pickle, macro step has :source {:macro-name "Log in as admin", :original-location ...}, others nil

  ;; Pre-pickles with outline stub
  (let [feature-str "Feature: Test\n  Scenario Outline: Stub\n    Given <x>\n  Examples:\n    | x |\n    | 1 |"
        tokens (lexer/lex feature-str)
        pre-ast (:ast (parser/parse tokens))
        registry {}
        pickles (pickler/pre-pickles pre-ast registry "test.feature")]
    (pp/pprint pickles))
  ;; Expected: one pickle with name "Stub [STUBBED - Outlines not yet supported]"

  ;; Output formats
  (let [feature-str "Feature: Test\n  Scenario Outline: Stub\n    Given <x>\n  Examples:\n    | x |\n    | 1 |"
        tokens (lexer/lex feature-str)
        pre-ast (:ast (parser/parse tokens))
        registry {}
        pickles (pickler/pre-pickles pre-ast registry "test.feature")
        edn (pickler/pickles->edn pickles)
        json (pickler/pickles->json pickles)
        ndjson (pickler/pickles->ndjson pickles)]
    (println "EDN:" edn)
    (println "JSON:" json)
    (println "NDJSON:" ndjson))
  ;; Should print valid formats

  ,)

;; Lazy Lexer Demo and Benchmarking Snippets

;; Demo: Basic lazy lex on small input

;; Runner Demo: Execute toy feature
(require '[shiftlefter.runner :as runner]
         '[shiftlefter.gherkin.lexer :as lexer]
         '[shiftlefter.gherkin.parser :as parser]
         '[shiftlefter.gherkin.pickler :as pickler]
         '[clojure.core.async :refer [chan go <!]])

;; Define steps
(runner/defstep #"I am on the login page" [] (runner/see "login page" :pass))
(runner/defstep #"I type \"([^\"]+)\" into \"([^\"]+)\"" [value field] (runner/mock-type field value :pass))
(runner/defstep #"I click \"([^\"]+)\"" [element] (runner/click element :pass))
(runner/defstep #"I see \"([^\"]+)\"" [text] (runner/see text :pass))

;; Parse and run
(def toy-content (slurp "resources/features/toy-login.feature"))
(def toy-tokens (lexer/lex toy-content))
(def toy-ast (parser/parse toy-tokens))
(def toy-pickles (pickler/pre-pickles (:ast toy-ast) {} "toy-login.feature"))
(def event-chan (chan))
(go (loop [] (when-let [e (<! event-chan)] (println "Event:" e) (recur))))
(def results (runner/exec toy-pickles event-chan))
(runner/report results)

;; -----------------------------------------------------------------------------
;; Lexer location and leading-ws examples
;; -----------------------------------------------------------------------------

;; Basic unindented feature
(def basic-input "Feature: Test\nScenario: Basic\nGiven step")
(def basic-tokens (lexer/lex basic-input))
;; (map #(select-keys % [:type :location :leading-ws]) basic-tokens)
;; -> ({:type :feature-line, :location {:line 1, :col 1}, :leading-ws ""}
;;     {:type :scenario-line, :location {:line 2, :col 1}, :leading-ws ""}
;;     {:type :step-line, :location {:line 3, :col 1}, :leading-ws ""})

;; Indented with spaces
(def indented-input "  Feature: Indented\n    Scenario: Test\n      Given step")
(def indented-tokens (lexer/lex indented-input))
;; (map #(select-keys % [:type :location :leading-ws]) indented-tokens)
;; -> ({:type :feature-line, :location {:line 1, :col 3}, :leading-ws "  "}
;;     {:type :scenario-line, :location {:line 2, :col 5}, :leading-ws "    "}
;;     {:type :step-line, :location {:line 3, :col 7}, :leading-ws "      "})

;; Mixed tabs and spaces
(def mixed-input "\tFeature: Tab\n \t Scenario: Mix")
(def mixed-tokens (lexer/lex mixed-input))
;; (map #(select-keys % [:type :location :leading-ws]) mixed-tokens)
;; -> ({:type :feature-line, :location {:line 1, :col 2}, :leading-ws "\t"}
;;     {:type :scenario-line, :location {:line 2, :col 3}, :leading-ws " \t"})

;; Blank lines with whitespace
(def blank-ws-input "  \nFeature: After blank")
(def blank-tokens (lexer/lex blank-ws-input))
;; (map #(select-keys % [:type :location :leading-ws]) blank-tokens)
;; -> ({:type :blank, :location {:line 1, :col 1}, :leading-ws "  "}
;;     {:type :feature-line, :location {:line 2, :col 1}, :leading-ws ""})

;; Benchmark: Time for large indented input
;; (time (doall (lexer/lex (apply str (repeat 1000 "  Given step\n"))))) ; ~lazy, fast

;; -----------------------------------------------------------------------------
;; Spec instrumentation toggle (added for spec-instrumentation-tests--20251222)
;; -----------------------------------------------------------------------------

(require '[clojure.spec.alpha :as s])
(require '[clojure.spec.test.alpha :as stest])
(require '[shiftlefter.test-helpers :as th])

;; Toggle instrumentation on - catches spec violations at call sites
(th/instrument-all)
;; => [shiftlefter.gherkin.pickler/pickles shiftlefter.gherkin.parser/parse ...]

;; Toggle instrumentation off
(th/unstrument-all)

;; Test that instrumentation catches violations:
;; (th/instrument-all)
;; (parser/parse "not-a-seq") ; => throws ExceptionInfo "did not conform to spec"

;; Instrumentation is auto-enabled during ./bin/kaocha runs via tests.edn hooks.

;; -----------------------------------------------------------------------------
;; Spec validation and fuzz testing (added for spec-parser-fns)
;; not functional, expected fixed with `generative-spec-gens--202512XX`
;; -----------------------------------------------------------------------------

(require '[clojure.test.check :as tc])

;; Instrument parse for runtime spec checking (now handled by test-helpers)
;; (stest/instrument `parser/parse)

;; Example: Valid parse call - spec validates return shape
(def valid-parse-result (parser/parse sample-tokens))
(s/valid? (s/get-spec `parser/parse) valid-parse-result) ; Should be true

;; Fuzz parse - generative testing catches arity/shape leaks
(tc/quick-check 50 (s/exercise-fn `parser/parse))

;; Instrument pickles
(stest/instrument `pickler/pickles)

;; Example: Valid pickles call - returns {:pickles [...] :errors [...]}
(def valid-pickles-result (pickler/pickles (:ast sample-ast) {} "sample.feature"))
(s/valid? (s/get-spec `pickler/pickles) valid-pickles-result) ; Should be true

;; Fuzz pickles
(tc/quick-check 50 (s/exercise-fn `pickler/pickles))

;; -----------------------------------------------------------------------------
;; Linter: Run clj-kondo on src and test for static analysis
;; Why: Catches arity leaks, unused bindings pre-runtime; fast CI check.
;; Lesson: Static linting complements dynamic specs—clj-kondo AST-based, no eval overhead.
;; Benchmark: (time (clojure.java.shell/sh "clj" "-M:kondo" "--lint" "src" "test"))
;; Example: Run in REPL to see baseline warnings/errors

;; -----------------------------------------------------------------------------
;; Printer: No-op roundtrip verification
;; The printer reconstructs the original file from tokens by concatenating :raw fields.
;; This proves lossless tokenization without any formatting/transformation.
;; -----------------------------------------------------------------------------

(require '[shiftlefter.gherkin.printer :as printer])

;; Token-level roundtrip: lex -> concat :raw -> original
(def printer-input "Feature: Test\n  @smoke\n  Scenario: Demo\n    Given step\n    | a | b |\n    | 1 | 2 |\n")
(= printer-input (printer/roundtrip printer-input))
;; => true (byte-for-byte match)

;; Check if a string roundtrips ok
(printer/roundtrip-ok? "Feature: Test\n  Scenario: S\n    Given x\n")
;; => true

;; File-based check with parse validation (Policy B)
(printer/fmt-check "resources/features/toy-login.feature")
;; => {:status :ok, :path "resources/features/toy-login.feature"}

;; Parse errors block formatting (Policy B enforcement)
(printer/fmt-check "test/resources/testdata/bad/gpt-tokenizer-stress.feature")
;; => {:status :error, :reason :parse-errors, :path "...", :details [...]}
;; This file has intentional edge cases (inline tag comments, Rule keyword)
;; Token-level roundtrip still works:
(let [content (slurp "test/resources/testdata/bad/gpt-tokenizer-stress.feature")]
  (= content (printer/roundtrip content)))
;; => true

;; CLI usage: sl fmt --check <path>
;; $ clj -M:run fmt --check resources/features/toy-login.feature
;; OK: resources/features/toy-login.feature

;; -----------------------------------------------------------------------------
;; Multi-file validation (v0.1.1-A)
;; Recursively check directories, multiple paths, with summary and exit codes
;; -----------------------------------------------------------------------------

;; Single file (unchanged behavior)
;; $ clj -M:run fmt --check resources/features/toy-login.feature
;; Checking resources/features/toy-login.feature... OK

;; Directory - recursively find and validate all .feature files
;; $ clj -M:run fmt --check resources/features/
;; Checking resources/features/toy-login.feature... OK
;; Checking test/resources/testdata/bad/gpt-tokenizer-stress.feature... INVALID
;;   Line 30: :unexpected-token - Unexpected token: :step-line
;;
;; 2 files checked: 1 valid, 1 invalid

;; Multiple paths
;; $ clj -M:run fmt --check resources/features/ compliance/gherkin/testdata/good/
;; Checking resources/features/toy-login.feature... OK
;; ... (many files)
;; 48 files checked: 47 valid, 1 invalid

;; Full compliance suite
;; $ clj -M:run fmt --check compliance/gherkin/testdata/good/
;; ... (46 files)
;; 46 files checked: 46 valid, 0 invalid

;; Exit codes:
;; 0 = all valid
;; 1 = one or more invalid
;; 2 = no .feature files found, or path doesn't exist

;; REPL testing of internal helpers:
(comment
  (require '[shiftlefter.core :as core])

  ;; find-feature-files - given paths, return all .feature files
  (#'core/find-feature-files ["resources/features/"])
  ;; => ["resources/features/toy-login.feature"]

  ;; check-single-file - check one file
  (#'core/check-single-file "resources/features/toy-login.feature")
  ;; => {:path "resources/features/toy-login.feature", :status :ok}

  ;; check-files - check multiple paths with summary
  (#'core/check-files ["compliance/gherkin/testdata/good/"])
  ;; => {:results [...], :valid 46, :invalid 0, :exit-code 0}

  (#'core/check-files ["nonexistent/"])
  ;; => {:results [], :valid 0, :invalid 0, :not-found 1, :exit-code 2}

  ;; format-single-file - format one file in place
  (#'core/format-single-file "/tmp/test.feature")
  ;; => {:path "/tmp/test.feature", :status :reformatted} or :unchanged or :error

  ;; format-files - format multiple paths with summary
  (#'core/format-files ["resources/features/"])
  ;; => {:results [...], :reformatted 1, :unchanged 0, :errors 0, :exit-code 0}

  ;; CLI usage:
  ;; $ clj -M:run fmt --write resources/features/
  ;; Formatting resources/features/toy-login.feature... reformatted
  ;; 1 file processed: 1 reformatted, 0 unchanged

  ,)

;; -----------------------------------------------------------------------------
;; Canonical Formatter
;; Deterministic formatting with: 2-space indent, aligned tables, no trailing WS
;; -----------------------------------------------------------------------------

;; Format a string to canonical style
(printer/canonical "Feature: Test\nScenario: S1\nGiven step\n|a|bb|\n|ccc|d|\n")
;; => "Feature: Test\n\n  Scenario: S1\n    Given step\n      | a   | bb |\n      | ccc | d  |\n"

;; Idempotence: formatting twice produces same result
(let [input "Feature: Test\nScenario: S1\nGiven step\n"
      once (printer/canonical input)
      twice (printer/canonical once)]
  (= once twice))
;; => true

;; File-based canonical formatting
(printer/fmt-canonical "resources/features/toy-login.feature")
;; => {:status :ok, :path "...", :output "Feature: Toy Login\n\n  Scenario: ..."}

;; Policy B: parse errors block formatting
(printer/fmt-canonical "test/resources/testdata/bad/gpt-tokenizer-stress.feature")
;; => {:status :error, :reason :parse-errors, :details [...]}

;; CLI usage: sl fmt --canonical <path>
;; $ clj -M:run fmt --canonical resources/features/toy-login.feature
;; Feature: Toy Login
;;
;;   Scenario: Successful login
;;     Given I am on the login page
;;     ...

(def small-input "Feature: Demo\nScenario: Test\nGiven step\n")
(def lazy-tokens (lexer/lex small-input))
;; (first lazy-tokens) => first token
;; (second lazy-tokens) => second, etc.
;; (count lazy-tokens) => forces all, but lazy

;; Benchmark: Force evaluation on large input
(def large-input (clojure.string/join "\n" (repeat 10000 "Scenario: Bench\nGiven large step\n")))
(def large-lazy-tokens (lexer/lex large-input))
;; (time (doall large-lazy-tokens)) ; Force and time

;; Runner Demo: Execute toy feature
(require '[shiftlefter.runner :as runner]
         '[shiftlefter.gherkin.lexer :as lexer]
         '[shiftlefter.gherkin.parser :as parser]
         '[shiftlefter.gherkin.pickler :as pickler]
         '[clojure.core.async :refer [chan go <!]])

;; Define steps
(runner/defstep #"I am on the login page" [] (runner/see "login page" :pass))
(runner/defstep #"I type \"([^\"]+)\" into \"([^\"]+)\"" [value field] (runner/mock-type field value :pass))
(runner/defstep #"I click \"([^\"]+)\"" [element] (runner/click element :pass))
(runner/defstep #"I see \"([^\"]+)\"" [text] (runner/see text :pass))

;; Parse and run
(def toy-content (slurp "resources/features/toy-login.feature"))
(def toy-tokens (lexer/lex toy-content))
(def toy-ast (parser/parse toy-tokens))
(def toy-pickles (pickler/pre-pickles toy-ast {} "toy-login.feature"))
(def event-chan (chan))
(go (loop [] (when-let [e (<! event-chan)] (println "Event:" e) (recur))))
(def results (runner/exec toy-pickles event-chan))
(runner/report results)
;; -----------------------------------------------------------------------------
;; Compliance Suite Demo Snippets
;; -----------------------------------------------------------------------------

;; One good file through pipeline + shim write
;; (require '[shiftlefter.gherkin.compliance :as compliance])
;; (def good-file "test/resources/gherkin-testdata/good/minimal.feature") ;; Example path
;; (def content (slurp good-file))
;; (def tokens (lexer/lex content))
;; (def ast (:ast (parser/parse tokens)))
;; (def pickles (pickler/pre-pickles ast {} good-file))
;; (spit "temp.tokens.ndjson" (compliance/tokens->ndjson tokens))
;; (spit "temp.ast.ndjson" (compliance/ast->ndjson ast))
;; (spit "temp.pickles.ndjson" (compliance/pickles->ndjson pickles))
;; ;; Writes NDJSON files for diff

;; One bad file showing errors
;; (def bad-file "test/resources/gherkin-testdata/bad/missing_feature_name.feature")
;; (def bad-content (slurp bad-file))
;; (def bad-tokens (lexer/lex bad-content))
;; (def bad-result (parser/parse bad-tokens))
;; (println "Errors:" (:errors bad-result))
;; ;; Should show non-empty errors

;; Diff example on known-passing file
;; (def expected-tokens (slurp "test/resources/gherkin-testdata/good/minimal.tokens.ndjson"))
;; (def our-tokens (slurp "temp.tokens.ndjson"))
;; (compliance/diff-ndjson "temp.tokens.ndjson" "test/resources/gherkin-testdata/good/minimal.tokens.ndjson")
;; ;; Returns true if byte-identical

;; Multi-tag example: lexer preserves vec internally, shim outputs separate TagLines
;; (def multi-tag-content "  @slow @ui\nFeature: Test")
;; (def multi-tokens (lexer/lex multi-tag-content))
;; (println "Tokens:" multi-tokens)  ;; Shows single :tag-line with value ["slow" "ui"]
;; (def shim-output (compliance/tokens->ndjson multi-tokens))
;; (println "Shim NDJSON:\n" shim-output)  ;; Separate (line:col)TagLine for each tag

   ;; COMPLIANCE SUITE EXECUTION

   ;; Run full compliance suite
   (require 'shiftlefter.gherkin.compliance)
   (def compliance-report (shiftlefter.gherkin.compliance/run-compliance "compliance/testdata"))
   ;; → {:good {:total 46, :passes 3, :fails ["compliance/testdata/good/several_examples.feature" ...]} :bad {:total 11, :passes 6, :fails ["compliance/testdata/bad/invalid_language.feature" ...]}}

   ;; View good stats
   (:good compliance-report)
   ;; → {:total 46, :passes 3, :fails [...]}

   ;; View bad stats
   (:bad compliance-report)
   ;; → {:total 11, :passes 6, :fails [...]}

   ;; Compute percentages
   (let [good (:good compliance-report) bad (:bad compliance-report)]
     {:good-percent (* 100.0 (/ (:passes good) (:total good)))
      :bad-percent (* 100.0 (/ (:passes bad) (:total bad)))})
   ;; → {:good-percent 6.521739130434782, :bad-percent 54.54545454545455}

   ;; Promote baseline (run in shell)
   ;; ./bin/promote-baseline
   ;; Copies compliance-report.md to compliance-baseline.md and commits

  (def gpt-content (slurp "resources/features/gpt-request.feature"))
  (def toy-tokens (lexer/lex toy-content))

  ;; =============================================================================
  ;; i18n / LANGUAGE SUPPORT
  ;; =============================================================================

  ;; French feature file
  (def french-feature
    "#language:fr
Fonctionnalité: Test en français

  Scénario: Mon premier test
    Soit un exemple
    Quand je fais quelque chose
    Alors ça marche")

  (def french-tokens (lexer/lex french-feature))
  ;; First token is :language-header with value "fr"
  (first french-tokens)
  ;; => {:type :language-header, :value "fr", ...}

  ;; Second token is :feature-line using French "Fonctionnalité"
  (second french-tokens)
  ;; => {:type :feature-line, :value "Test en français", :keyword-text "Fonctionnalité", ...}

  (def french-result (parser/parse french-tokens))
  (empty? (:errors french-result)) ;; => true
  (def french-ast (first (:ast french-result)))
  (:name french-ast) ;; => "Test en français"
  (map :keyword (:steps (first (parser/get-scenarios french-ast))))
  ;; => ("Given" "When" "Then")

  ;; Emoji feature file
  (def emoji-feature
    "# language: em
📚: Emoji Feature

  📕: Test Scenario
    😐 something happens")

  (def emoji-result (parser/parse (lexer/lex emoji-feature)))
  (empty? (:errors emoji-result)) ;; => true
  (:name (first (:ast emoji-result))) ;; => "Emoji Feature"

  ;; Check available dialects
  (require '[shiftlefter.gherkin.dialect :as dialect])
  (keys @dialect/official-dialects)
  ;; => ("af" "am" "an" "ar" "ast" "az" "bg" "bm" "bs" "ca" ...)

  ;; Get French dialect keywords
  (dialect/get-dialect "fr")
  ;; => [["Etant donné que " :given] ["Étant donné que " :given] ["Scénario" :scenario] ...]

  ;; Match a French keyword
  (dialect/match-step-keyword "Soit un test" (dialect/get-dialect "fr"))
  ;; => {:keyword :given, :matched "Soit ", :text "un test"}

  ;; =============================================================================
  ;; PICKLE PROJECTION (Cucumber compliance format)
  ;; =============================================================================

  ;; Pickle projection transforms internal pickle format to Cucumber's expected JSON.
  ;; Key differences from internal format:
  ;; - Wrapped in {"pickle": {...}}
  ;; - Sequential string IDs (continuing from AST)
  ;; - astNodeIds linking back to AST nodes
  ;; - type instead of keyword (Context/Action/Outcome)

  ;; Minimal feature pickle projection
  (def minimal-content (slurp "compliance/testdata/good/minimal.feature"))
  (def minimal-tokens (lexer/lex minimal-content))
  (def minimal-result (parser/parse minimal-tokens))
  (def minimal-ast (:ast minimal-result))
  (def minimal-uri "../testdata/good/minimal.feature")

  ;; Get AST with ID mapping
  (def ast-result (compliance/ast->ndjson-with-ids minimal-ast minimal-uri))
  (:id-map ast-result)
  ;; => {[4 5] 0, [3 3] 1} - step at line 4 col 5 has ID 0, scenario at line 3 col 3 has ID 1
  (:next-id ast-result)
  ;; => 2 - next available ID for pickles

  ;; Generate pickles
  (def pickles (pickler/pre-pickles minimal-ast {} "minimal.feature"))
  (first pickles)
  ;; => {:pickle/id #uuid "...", :pickle/name "minimalistic", :pickle/steps [...]}

  ;; Project to Cucumber format (5th param is AST for background lookup)
  (def cucumber-pickles (compliance/pickles->ndjson-with-ids pickles (:id-map ast-result) (:next-id ast-result) minimal-uri minimal-ast))
  (println cucumber-pickles)
  ;; => {"pickle":{"astNodeIds":["1"],"id":"3","language":"en",...,"steps":[{"astNodeIds":["0"],"id":"2","type":"Context",...}]}}

  ;; Verify it matches expected
  (def expected (slurp "compliance/testdata/good/minimal.feature.pickles.ndjson"))
  (= cucumber-pickles expected)
  ;; => true (for minimal.feature)

  ;; Keyword type mapping (internal):
  ;; Given/Soit/Étant → "Context"
  ;; When/Quand → "Action"
  ;; Then/Alors → "Outcome"
  ;; And/But → "Conjunction"

  ;; -----------------------------------------------------------------------------
  ;; Background Merge Projection
  ;;
  ;; Cucumber expects background steps prepended to each scenario's pickle.
  ;; Our pickler keeps backgrounds separate. The compliance projection merges them.
  ;; -----------------------------------------------------------------------------

  ;; Example: Feature with background
  (def bg-content (slurp "compliance/testdata/good/background.feature"))
  (def bg-tokens (lexer/lex bg-content))
  (def bg-result (parser/parse bg-tokens))
  (def bg-ast (:ast bg-result))
  (def bg-uri "../testdata/good/background.feature")

  ;; Check the AST structure
  (count (:children (first bg-ast)))
  ;; => 3 (Background + 2 Scenarios)
  (:type (first (:children (first bg-ast))))
  ;; => :background

  ;; Our pickles don't include background steps
  (def bg-pickles (pickler/pre-pickles bg-ast {} "background.feature"))
  (count (:pickle/steps (first bg-pickles)))
  ;; => 1 (just the scenario step)

  ;; But compliance projection merges them
  (def bg-ast-result (compliance/ast->ndjson-with-ids bg-ast bg-uri))
  (def bg-cucumber-pickles (compliance/pickles->ndjson-with-ids bg-pickles (:id-map bg-ast-result) (:next-id bg-ast-result) bg-uri bg-ast))

  ;; Parse the result to inspect
  (require '[cheshire.core :as json])
  (def first-pickle (json/parse-string (first (clojure.string/split-lines bg-cucumber-pickles)) true))
  (count (:steps (:pickle first-pickle)))
  ;; => 2 (background step + scenario step)
  (:text (first (:steps (:pickle first-pickle))))
  ;; => "the minimalism inside a background" (from Background)
  (:text (second (:steps (:pickle first-pickle))))
  ;; => "the minimalism" (from Scenario)

;; -----------------------------------------------------------------------------
;; RT2: strip-trailing-eol helper (CRLF/CR-aware EOL stripping)
;; -----------------------------------------------------------------------------

(require '[shiftlefter.gherkin.io :as io])

;; Basic stripping - all EOL types handled
(io/strip-trailing-eol "hello\n")      ; => "hello"
(io/strip-trailing-eol "hello\r\n")    ; => "hello"
(io/strip-trailing-eol "hello\r")      ; => "hello"
(io/strip-trailing-eol "hello")        ; => "hello" (no change)

;; Only strips ONE trailing EOL - important for multi-line content
(io/strip-trailing-eol "a\nb\n")       ; => "a\nb"
(io/strip-trailing-eol "a\r\nb\r\n")   ; => "a\r\nb"

;; Nil handling - returns empty string
(io/strip-trailing-eol nil)            ; => ""

;; Verify CR doesn't leak into parsed step text
(require '[shiftlefter.gherkin.lexer :as lexer])
(require '[shiftlefter.gherkin.parser :as parser])

(let [input "Feature: Test\r\nScenario: CRLF\r\nGiven a step\r\n"
      result (parser/parse (lexer/lex input))
      step (first (:steps (first (:children (first (:ast result))))))]
  {:text (:text step)
   :ends-with-cr? (clojure.string/ends-with? (:text step) "\r")})
;; => {:text "a step", :ends-with-cr? false}