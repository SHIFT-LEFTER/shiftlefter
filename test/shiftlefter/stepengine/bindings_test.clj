(ns shiftlefter.stepengine.bindings-test
  "Scenario data plane core (sl-yh7): token grammar, capture!, resolution,
   merge semantics. Named-group scanner (sl-qzhn): fixed edge cases +
   generative cross-check against the real Pattern.namedGroups on JDK 20+."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [shiftlefter.stepengine.bindings :as b])
  (:import [java.util.regex Pattern PatternSyntaxException]))

;; -----------------------------------------------------------------------------
;; Token grammar
;; -----------------------------------------------------------------------------

(deftest token-grammar
  (testing "whole tokens"
    (is (b/token? "{code}"))
    (is (b/token? "{orderNumber}"))
    (is (b/token? "{seed.userId}"))
    (is (b/token? "{seed.a.b}")))
  (testing "non-tokens are lexically disjoint"
    (is (not (b/token? "{:id \"code\"}")))   ; raw EDN locator
    (is (b/token? "{Code}")
        "leading uppercase is charset-legal (Java-forced); lowerCamel is convention")
    (is (not (b/token? "{snake_case}")))
    (is (not (b/token? "{kebab-case}")))
    (is (not (b/token? "{a.}")))
    (is (not (b/token? "code")))
    (is (not (b/token? "'{code}'")) "quoted is always literal — not a token"))
  (testing "parse"
    (is (= {:name :code :path []} (b/parse-token "{code}")))
    (is (= {:name :seed :path [:userId :zip]} (b/parse-token "{seed.userId.zip}")))
    (is (nil? (b/parse-token "{:id \"x\"}"))))
  (testing "embedded tokens"
    (is (= [{:name :order :path []} {:name :seed :path [:x]}]
           (b/embedded-tokens "id (?<n>\\d+) {order} and {seed.x}")))
    (is (empty? (b/embedded-tokens "no tokens, just {:edn \"here\"}")))))

;; -----------------------------------------------------------------------------
;; capture! — assert-plus-bind
;; -----------------------------------------------------------------------------

(deftest capture-produces-bindings
  (let [ctx (b/capture! {} "code is: (?<code>\\d{6})" "your code is: 987654 thanks")]
    (is (= {:code "987654"} (b/bindings-key ctx)))))

(deftest capture-multiple-named-groups-all-bind
  (let [ctx (b/capture! {} "(?<user>[a-z]+)/(?<pin>\\d+)" "alice/1234")]
    (is (= {:user "alice" :pin "1234"} (b/bindings-key ctx)))))

(deftest capture-optional-nonparticipating-group-binds-nothing
  (let [ctx (b/capture! {} "(?<a>\\d+)(-(?<opt>[a-z]+))?" "77")]
    (is (= {:a "77"} (b/bindings-key ctx)))))

(deftest capture-failure-is-structured
  (let [e (try (b/capture! {} "(?<code>\\d{6})" "no digits here")
               (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :bindings/capture-failure (:type e)))
    (is (= "(?<code>\\d{6})" (:pattern e)))
    (is (string? (:text e)) "carries an excerpt of the observed text")))

(deftest capture-duplicate-name-is-invalid-pattern
  (let [e (try (b/capture! {} "(?<a>x)|(?<a>y)" "x")
               (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :bindings/invalid-pattern (:type e)))))

(deftest capture-rebinding-is-last-write-wins
  (let [ctx (-> {}
                (b/capture! "(?<code>\\d+)" "first 111")
                (b/capture! "(?<code>\\d+)" "second 222"))]
    (is (= {:code "222"} (b/bindings-key ctx)))))

(deftest capture-unnamed-groups-bind-nothing
  (let [ctx (b/capture! {} "code: (\\d+)" "code: 42")]
    (is (empty? (b/bindings-key ctx)) "positional groups never bind")))

;; -----------------------------------------------------------------------------
;; Resolution
;; -----------------------------------------------------------------------------

(deftest resolve-bare-and-dotted
  (let [ctx {b/bindings-key {:code "987654" :seed {:userId 42}}}]
    (is (= "987654" (b/resolve-token ctx "{code}")))
    (is (= 42 (b/resolve-token ctx "{seed.userId}")))))

(deftest resolve-missing-name-is-structured-with-known
  (let [ctx {b/bindings-key {:code "1"}}
        e (try (b/resolve-token ctx "{coed}")
               (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :bindings/unresolved (:type e)))
    (is (= ["code"] (:known e)) "carries known names for did-you-mean")))

(deftest resolve-bad-descent-is-structured
  (let [ctx {b/bindings-key {:code "flat"}}]
    (is (= :bindings/unresolved
           (try (b/resolve-token ctx "{code.x}")
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
        ) "descending into a non-map value")
    (is (= :bindings/unresolved
           (try (b/resolve-token {b/bindings-key {:seed {}}} "{seed.missing}")
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))

;; -----------------------------------------------------------------------------
;; Matcher interpolation — regex-quoted literal
;; -----------------------------------------------------------------------------

(deftest matcher-interpolation-is-quoted-literal
  (let [ctx {b/bindings-key {:order "A-1.2"}}]
    (is (= "order (?<x>\\d+) for \\QA-1.2\\E"
           (b/interpolate-matcher ctx "order (?<x>\\d+) for {order}")))
    (testing "the interpolated pattern matches the literal, dot not wild"
      (is (some? (b/attempt-capture ctx "num (?<n>\\d+) {order}" "num 9 A-1.2")))
      (is (nil? (b/attempt-capture ctx "num (?<n>\\d+) {order}" "num 9 A-1x2"))))))

(deftest matcher-interpolation-missing-binding-throws
  (is (= :bindings/unresolved
         (try (b/interpolate-matcher {} "see {ghost}")
              (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))

;; -----------------------------------------------------------------------------
;; Merge boundary + mirror rule
;; -----------------------------------------------------------------------------

(deftest merge-validates-names-at-the-boundary
  (is (= :bindings/invalid-names
         (try (b/merge-bindings {} {:seed/userId 1})
              (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
  (is (= :bindings/invalid-names
         (try (b/merge-bindings {} {:kebab-name 1})
              (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))

(deftest conforming-keys-is-the-mirror-rule
  (is (= {:userId 1 :ok2 4}
         (b/conforming-keys {:userId 1 :seed/x 2 :bad-name 3 :ok2 4}))))

;; -----------------------------------------------------------------------------
;; Static-plane helper
;; -----------------------------------------------------------------------------

(deftest pattern-binding-names-enumerates-producers
  (is (= #{:a :b} (b/pattern-binding-names "(?<a>x)(?<b>y)?")))
  (is (= #{} (b/pattern-binding-names "just (positional)")))
  (testing "embedded consumer tokens are neutralized, not compiled"
    (is (= #{:n} (b/pattern-binding-names "(?<n>\\d+) for {order}"))))
  (testing "bad regex throws structured planning error"
    (is (= :bindings/invalid-pattern
           (try (b/pattern-binding-names "(?<a>x)|(?<a>y)")
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))

;; -----------------------------------------------------------------------------
;; Named-group scanner (Java-floor shim — sl-qzhn, retirement sl-k7gl)
;; -----------------------------------------------------------------------------

(deftest scanner-fixed-edge-cases
  (testing "plain groups, in source order"
    (is (= ["a" "b"] (b/named-group-names "(?<a>x)(?<b>y)?")))
    (is (= [] (b/named-group-names "just (positional) x?"))))
  (testing "lookaround is not a named group"
    (is (= ["a"] (b/named-group-names "(?<=x)(?<a>y)(?<!z)"))))
  (testing "escapes"
    (is (= [] (b/named-group-names "\\(?<a>x")) "escaped paren")
    (is (= ["a"] (b/named-group-names "\\\\(?<a>x)")) "escaped backslash, then a real group"))
  (testing "character classes"
    (is (= [] (b/named-group-names "[(?<a>)]")) "class members are literal")
    (is (= [] (b/named-group-names "[a[(?<b>]]")) "nested class")
    (is (= [] (b/named-group-names "[](?<a>x)]")) "leading ] is a literal member")
    (is (= ["a"] (b/named-group-names "[]](?<a>x)")) "class {]} closes, then a group")
    (is (= ["a"] (b/named-group-names "[^]](?<a>x)")) "negated leading-] class"))
  (testing "\\Q..\\E quoting"
    (is (= [] (b/named-group-names "\\Q(?<a>x)\\E")) "quoted group text is literal")
    (is (= ["a"] (b/named-group-names "\\Q(\\E(?<a>x)")) "group after quote ends")
    (is (= ["a"] (b/named-group-names "[\\Q\\E]](?<a>x)"))
        "empty quote preserves the leading-] literal slot")
    (is (= ["a"] (b/named-group-names "[\\Qz\\E]](?<a>x)"))
        "quoted content consumes the leading-] literal slot")))

(deftest capture-with-hostile-interpolated-value
  (testing "exec path: a Pattern/quote'd binding value containing group syntax"
    (let [ctx {b/bindings-key {:order "(?<evil>x)["}}
          res (b/attempt-capture ctx "num (?<n>\\d+) {order}" "num 9 (?<evil>x)[")]
      (is (= {:n "9"} (:bindings res)) "only the real group binds"))))

;; Pattern.namedGroups() — public on JDK 20+, absent before. nil on the
;; documented Java 11+ floor, where the cross-check self-skips (the fixed
;; cases above still run everywhere).
(def ^:private jdk-named-groups-method
  (try (.getMethod Pattern "namedGroups" (make-array Class 0))
       (catch NoSuchMethodException _ nil)))

(defn- jdk-named-group-names [^Pattern p]
  (set (keys (.invoke jdk-named-groups-method p (object-array 0)))))

(def ^:private scanner-fragment-gen
  "Regex fragments whose concatenations exercise every scanner state:
   literals/metachars, escapes, character classes (nested, leading-]),
   \\Q..\\E quoting with hostile content, named groups, lookaround."
  (gen/one-of
   [(gen/elements ["abc" "x 1" "-" "." "+" "\\d" "\\w+" "\\(" "\\[" "\\]"
                   "x?" "(?:y)" "(?=z)" "(?<=z)" "(?<!q)" "|"])
    (gen/elements ["[abc]" "[^a-z]" "[]]" "[^]]" "[a[b]]" "[(?<c>)]"
                   "[a&&[b]]" "[\\]]" "[a-fA-F0-9]"])
    (gen/elements ["\\Q(?<q>x)\\E" "\\Q[(\\E" "\\Q\\E" "\\Q+*?\\E"])
    (gen/fmap (fn [[nm inner]] (str "(?<" nm ">" inner ")"))
              (gen/tuple (gen/elements ["a" "b2" "code" "orderNum" "X"])
                         (gen/elements ["x" "\\d+" "[a-z]*" "y|z"])))]))

(def ^:private scanner-pattern-gen
  (gen/fmap str/join (gen/vector scanner-fragment-gen 0 8)))

(defspec scanner-agrees-with-jdk-named-groups 500
  (prop/for-all [pattern-str scanner-pattern-gen]
    (let [compiled (try (Pattern/compile pattern-str)
                        (catch PatternSyntaxException _ nil))]
      (or (nil? jdk-named-groups-method) ;; JDK < 20 — no API to cross-check
          (nil? compiled)                ;; invalid pattern — out of scanner scope
          (= (set (b/named-group-names pattern-str))
             (jdk-named-group-names compiled))))))
