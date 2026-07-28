(ns shiftlefter.stepengine.bindings-test
  "Scenario data plane core (sl-yh7): token grammar, capture!, resolution,
   merge semantics. Named-group detection (sl-qzhn shim, retired sl-k7gl):
   fixed edge cases through the public pattern-binding-names seam."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.stepengine.bindings :as b]))

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
;; Named-group detection (Pattern.namedGroups(), JDK 20+ — sl-k7gl)
;; -----------------------------------------------------------------------------

;; These fixed cases pin OUR integration seam — neutralize embedded tokens,
;; compile, read the compiled pattern's named groups, keywordize — through the
;; public `pattern-binding-names`. Historical note: until sl-k7gl these
;; semantics came from a hand-rolled scanner (Java-floor shim, sl-qzhn) pinned
;; against the real API by a generative cross-check; the floor raise to 21
;; (sl-yqgv) made Pattern.namedGroups() the implementation, so the cross-check's
;; oracle comparison became vacuous and was retired with the scanner.

(deftest named-group-fixed-edge-cases
  (testing "plain groups"
    (is (= #{:a :b} (b/pattern-binding-names "(?<a>x)(?<b>y)?")))
    (is (= #{} (b/pattern-binding-names "just (positional) x?"))))
  (testing "lookaround is not a named group"
    (is (= #{:a} (b/pattern-binding-names "(?<=x)(?<a>y)(?<!z)"))))
  (testing "escapes"
    (is (= #{} (b/pattern-binding-names "\\(?<a>x")) "escaped paren")
    (is (= #{:a} (b/pattern-binding-names "\\\\(?<a>x)")) "escaped backslash, then a real group"))
  (testing "character classes"
    (is (= #{} (b/pattern-binding-names "[(?<a>)]")) "class members are literal")
    (is (= #{} (b/pattern-binding-names "[a[(?<b>]]")) "nested class")
    (is (= #{} (b/pattern-binding-names "[](?<a>x)]")) "leading ] is a literal member")
    (is (= #{:a} (b/pattern-binding-names "[]](?<a>x)")) "class {]} closes, then a group")
    (is (= #{:a} (b/pattern-binding-names "[^]](?<a>x)")) "negated leading-] class"))
  (testing "\\Q..\\E quoting"
    (is (= #{} (b/pattern-binding-names "\\Q(?<a>x)\\E")) "quoted group text is literal")
    (is (= #{:a} (b/pattern-binding-names "\\Q(\\E(?<a>x)")) "group after quote ends")
    (is (= #{:a} (b/pattern-binding-names "[\\Q\\E]](?<a>x)"))
        "empty quote preserves the leading-] literal slot")
    (is (= #{:a} (b/pattern-binding-names "[\\Qz\\E]](?<a>x)"))
        "quoted content consumes the leading-] literal slot"))
  (testing "(?x) COMMENTS mode — fixed by scanner retirement (sl-k7gl)"
    (is (= #{:a} (b/pattern-binding-names "(?x)(?<a>x) # (?<b>y)"))
        "a #-commented group is not a group; the shim reported a phantom :b")))

(deftest capture-with-hostile-interpolated-value
  (testing "exec path: a Pattern/quote'd binding value containing group syntax"
    (let [ctx {b/bindings-key {:order "(?<evil>x)["}}
          res (b/attempt-capture ctx "num (?<n>\\d+) {order}" "num 9 (?<evil>x)[")]
      (is (= {:n "9"} (:bindings res)) "only the real group binds"))))

(deftest capture-in-comments-mode
  (testing "exec path: (?x) with a commented-out group binds without crashing"
    ;; Pre-sl-k7gl the shim reported the phantom group and Matcher.group threw
    ;; IllegalArgumentException here.
    (is (= {:n "42"} (:bindings (b/attempt-capture {} "(?x)(?<n>\\d+) # (?<phantom>y)" "num 42"))))))
