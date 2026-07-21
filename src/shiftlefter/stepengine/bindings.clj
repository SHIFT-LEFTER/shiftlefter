(ns shiftlefter.stepengine.bindings
  "Scenario data plane: named bindings (sl-yh7).

   Bindings are scenario-scoped name→value pairs living under the reserved
   ctx key `:sl/bindings` — flat lowerCamel names, plain edn-safe values,
   forward-only flow, dead at scenario end. Producers: capture steps (regex
   named groups), hook contributions (conforming bare keys, mirrored by the
   hook weave), future :ctx-seed (mmfy G6). Consumers: `{name(.path)*}`
   tokens in literal-admitting step-text slots, resolved by the engine at
   exec time. Cross-step rebinding is last-write-wins (a chained receive
   re-produces `{code}` naturally). Provenance (which step/hook produced a
   binding) is recorded in run evidence, never in the map itself.

   PUBLIC SURFACE: `capture!` — the one helper hand-rolled interfaces call
   with whatever text they fetched. Capture is assert-plus-bind: no match =
   step failure ('it fails as though I could not see that text'). Everything
   else here is engine/in-repo machinery.

   Binding-name charset [A-Za-z][A-Za-z0-9]* is forced by Java group names
   and adopted for the whole plane. NOTE: 'bindings' elsewhere in this
   codebase also names the intent element locator-bindings map — these are
   unrelated; this ns is the scenario DATA plane."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str])
  (:import [java.util.regex Pattern PatternSyntaxException]))

;; -----------------------------------------------------------------------------
;; The reserved ctx key
;; -----------------------------------------------------------------------------

(def bindings-key
  "Reserved root ctx key for the scenario data plane. Deliberately not
   :cap/*-namespaced (capability enumeration/cleanup must ignore it) —
   same species as :run/interfaces (sl-3jr4), but scenario-mutated."
  :sl/bindings)

;; -----------------------------------------------------------------------------
;; Specs
;; -----------------------------------------------------------------------------

(def ^:private name-re
  "The binding-name charset, forced by Java named-group names."
  #"[A-Za-z][A-Za-z0-9]*")

(defn valid-binding-name?
  "True for a bare (non-namespaced) keyword matching the binding charset.
   Used both for capture-produced names and for deciding which hook
   contribution keys mirror into the data plane."
  [k]
  (and (keyword? k)
       (nil? (namespace k))
       (some? (re-matches name-re (name k)))))

;; with-gen so spec-health can exercise the spec (predicate-only specs
;; have no built-in generator).
(s/def ::binding-name
  (s/with-gen valid-binding-name?
    #(gen/elements [:code :orderNumber :sessionToken :userId :resetLink])))
(s/def ::bindings (s/map-of ::binding-name any?))

;; -----------------------------------------------------------------------------
;; Token grammar — {name(.path)*}
;; -----------------------------------------------------------------------------

(def token-re-fragment
  "Regex pattern STRING for a binding token, for splicing into step-pattern
   alternations (the same composition style as stepdefs/browser locator-re).
   Lexically disjoint from raw EDN locators, which open with `{:`."
  "\\{[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z][A-Za-z0-9]*)*\\}")

(def value-re-fragment
  "Central value-slot regex fragment (sl-yh7): a quoted literal — captured
   WITH its quotes; the engine strips them by the frame's :arg-kinds — or a
   {binding} token. Splice into step patterns as (str \"(\" fragment \")\").
   Quoted is always literal (sl-iseq)."
  (str "'[^']+'|" token-re-fragment))

(def ^:private whole-token-re
  (re-pattern "\\{([A-Za-z][A-Za-z0-9]*)((?:\\.[A-Za-z][A-Za-z0-9]*)*)\\}"))

(def ^:private embedded-token-re
  (re-pattern token-re-fragment))

(defn token?
  "True when s is exactly one binding token, e.g. \"{code}\" or
   \"{seed.userId}\"."
  [s]
  (and (string? s) (some? (re-matches whole-token-re s))))

(defn parse-token
  "\"{seed.userId}\" -> {:name :seed :path [:userId]}. nil when s is not
   a whole token."
  [s]
  (when (string? s)
    (when-let [[_ nm path] (re-matches whole-token-re s)]
      {:name (keyword nm)
       :path (if (str/blank? path)
               []
               (mapv keyword (rest (str/split path #"\."))))})))

(defn embedded-tokens
  "All token occurrences inside s (a matcher-slot string), in order.
   Returns parsed tokens; empty seq when none."
  [s]
  (map parse-token (re-seq embedded-token-re (or s ""))))

;; -----------------------------------------------------------------------------
;; Resolution
;; -----------------------------------------------------------------------------

(defn- unresolved-error [token-str {:keys [name path]} bindings reason]
  (ex-info (str "Unresolved binding token " token-str " — " reason)
           {:type :bindings/unresolved
            :message (str "Unresolved binding token " token-str " — " reason)
            :token token-str
            :name name
            :path path
            :known (vec (sort (map clojure.core/name (keys bindings))))}))

(defn resolve-token
  "Resolve a whole token string against the ctx's :sl/bindings.
   Dots descend map-valued bindings (hook contributions) only. Throws
   structured :bindings/unresolved on a missing name, a non-map descent,
   or a missing path key."
  [ctx token-str]
  (let [{:keys [name path] :as tok} (parse-token token-str)
        bindings (get ctx bindings-key {})]
    (when-not tok
      (throw (ex-info (str "Not a binding token: " (pr-str token-str))
                      {:type :bindings/unresolved
                       :message (str "Not a binding token: " (pr-str token-str))
                       :token token-str})))
    (when-not (contains? bindings name)
      (throw (unresolved-error token-str tok bindings "no such binding")))
    (reduce (fn [v k]
              (cond
                (not (map? v))
                (throw (unresolved-error token-str tok bindings
                                         (str "cannot descend ." (clojure.core/name k)
                                              " into a non-map value")))
                (not (contains? v k))
                (throw (unresolved-error token-str tok bindings
                                         (str "no key ." (clojure.core/name k)
                                              " in bound map")))
                :else (get v k)))
            (get bindings name)
            path)))

;; -----------------------------------------------------------------------------
;; Merging (the single write point)
;; -----------------------------------------------------------------------------

(defn merge-bindings
  "Merge name→value pairs into ctx's :sl/bindings. Last-write-wins on
   re-produced names (ruled: chained receives rebind {code}). Validates
   names at the boundary."
  [ctx m]
  (when-not (s/valid? ::bindings m)
    (throw (ex-info (str "Invalid binding names: " (s/explain-str ::bindings m))
                    {:type :bindings/invalid-names
                     :message "Binding names must be bare lowerCamel keywords"
                     :explain (s/explain-data ::bindings m)})))
  (update ctx bindings-key merge m))

(defn conforming-keys
  "The subset of map m whose keys are valid binding names — the hook-weave
   mirror rule (namespaced/nonconforming keys stay machinery)."
  [m]
  (into {} (filter (comp valid-binding-name? key)) m))

;; -----------------------------------------------------------------------------
;; Capture normalization — the exec-side seam (engine machinery)
;; -----------------------------------------------------------------------------

(defn- quoted-literal? [s]
  (and (string? s)
       (>= (count s) 2)
       (str/starts-with? s "'")
       (str/ends-with? s "'")))

(defn- strip-quotes [s] (subs s 1 (dec (count s))))

(defn normalize-capture
  "Normalize one capture by its bind-time slot kind, against the live
   scenario ctx. This is the ONLY place captured {binding} tokens are
   resolved — stepdef fns stay byte-unchanged and binding-agnostic.

   :value    — quoted literal: strip quotes (quoted is ALWAYS literal,
               sl-iseq — a quoted '{code}' stays the text {code});
               whole token: resolve, stringified.
   :location — whole token: resolve and QUOTE-WRAP so the stepdef's
               literal/ref classifier takes its literal path unchanged.
   :matcher / nil — untouched (matcher-embedded tokens are interpolated
               inside capture!, where Pattern/quote lives)."
  [capture kind scenario-ctx]
  (case kind
    :value (cond
             (quoted-literal? capture) (strip-quotes capture)
             (token? capture) (str (resolve-token scenario-ctx capture))
             :else capture)
    :location (if (token? capture)
                (str "'" (resolve-token scenario-ctx capture) "'")
                capture)
    capture))

(defn normalize-captures
  "Normalize a captures vector by slot kinds. Returns {:ok captures'} or
   {:error <structured :bindings/* error>} on a failed token resolution —
   the caller fails the step with that error (never a raw exception)."
  [captures slot-kinds scenario-ctx]
  (if (empty? slot-kinds)
    {:ok captures}
    (try
      {:ok (vec (map-indexed
                 (fn [i capture]
                   (normalize-capture capture (get slot-kinds i) scenario-ctx))
                 captures))}
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (if (and (keyword? (:type data))
                   (= "bindings" (namespace (:type data))))
            {:error data}
            (throw e)))))))

;; -----------------------------------------------------------------------------
;; Pattern compilation + named-group matching (in-repo machinery)
;; -----------------------------------------------------------------------------

(defn interpolate-matcher
  "Replace embedded {name(.path)*} tokens in a matcher pattern string with
   the Pattern/quote'd string of the resolved value. 'My binding is a regex
   fragment' is a non-goal — interpolation is always literal. Throws
   :bindings/unresolved for a missing binding."
  [ctx pattern-str]
  (str/replace pattern-str embedded-token-re
               (fn [token-str]
                 (Pattern/quote (str (resolve-token ctx token-str))))))

(defn compile-pattern
  "Interpolate embedded tokens against ctx, then Pattern/compile. Throws
   structured :bindings/invalid-pattern on a bad regex (including Java's
   duplicate-named-group error)."
  ^Pattern [ctx pattern-str]
  (let [interpolated (interpolate-matcher ctx pattern-str)]
    (try
      (Pattern/compile interpolated)
      (catch PatternSyntaxException e
        (throw (ex-info (str "Invalid capture pattern: " (.getMessage e))
                        {:type :bindings/invalid-pattern
                         :message (.getMessage e)
                         :pattern pattern-str}
                        e))))))

;; -----------------------------------------------------------------------------
;; Named-group scanning (Java-floor shim)
;; -----------------------------------------------------------------------------

;; DISMISSAL CANDIDATE (sl-k7gl): Pattern.namedGroups() is public only since
;; Java 20 and the documented floor is Java 11, so group names are hand-scanned
;; from the pattern source instead. When the documented floor rises to Java 21+,
;; delete `named-group-names` (and its helpers) and call the real API at both
;; call sites — retirement trigger and rationale live on sl-k7gl.

(defn- ascii-letter? [c]
  (or (<= (int \a) (int c) (int \z))
      (<= (int \A) (int c) (int \Z))))

(defn- ascii-alnum? [c]
  (or (ascii-letter? c)
      (<= (int \0) (int c) (int \9))))

(defn- scan-group-name
  "When index i in s starts a named group `(?<name>`, returns
   [name index-after-close-angle]; nil otherwise — a following `=`/`!`
   (lookbehind) fails the leading-letter check. Assumes (.charAt s i) is
   an open paren."
  [^String s i]
  (let [n (.length s)
        start (+ i 3)]
    (when (and (< start n)
               (= \? (.charAt s (inc i)))
               (= \< (.charAt s (+ i 2)))
               (ascii-letter? (.charAt s start)))
      (let [end (loop [j (inc start)]
                  (if (and (< j n) (ascii-alnum? (.charAt s j)))
                    (recur (inc j))
                    j))]
        (when (and (< end n) (= \> (.charAt s end)))
          [(subs s start end) (inc end)])))))

(defn named-group-names
  "The named-group names of a VALID pattern source string, in source
   order: `(?<name>` occurrences outside character classes and \\Q..\\E
   quoting. Both call sites compile the string first, so malformed input
   cannot reach this scanner. Semantics pinned against the real parser:
   `]` immediately after `[`/`[^` (even across an empty \\Q\\E) is a
   literal member, `[` inside a class nests. Known non-goal: (?x)
   COMMENTS mode, where a #-comment can swallow a group — matcher slots
   are single-line in practice."
  [^String s]
  (let [n (.length s)]
    (loop [i 0 quoting? false depth 0 opened? false names []]
      (if (>= i n)
        names
        (let [c (.charAt s i)
              next-c (when (< (inc i) n) (.charAt s (inc i)))]
          (cond
            ;; Inside \Q..\E: only \E exits; quoted content clears opened?.
            quoting?
            (if (and (= \\ c) (= \E next-c))
              (recur (+ i 2) false depth opened? names)
              (recur (inc i) true depth false names))

            (= \\ c)
            (if (= \Q next-c)
              (recur (+ i 2) true depth opened? names)
              (recur (+ i 2) false depth false names))

            (= \[ c)
            (recur (inc i) false (inc depth) true names)

            ;; ^ directly after [ keeps the class in its just-opened state.
            (and (= \^ c) opened?)
            (recur (inc i) false depth true names)

            (and (= \] c) (pos? depth) (not opened?))
            (recur (inc i) false (dec depth) false names)

            (and (= \( c) (zero? depth))
            (if-let [[nm after] (scan-group-name s i)]
              (recur (long after) false depth false (conj names nm))
              (recur (inc i) false depth false names))

            :else
            (recur (inc i) false depth false names)))))))

(defn match-named
  "Match a compiled pattern against text (find semantics). On a hit,
   returns {:full <match> :bindings {name value}} with one entry per
   PARTICIPATING named group (an optional group that did not participate
   binds nothing). nil on no match."
  [^Pattern pattern text]
  (let [m (.matcher pattern (str text))]
    (when (.find m)
      {:full (.group m 0)
       :bindings (into {}
                       (keep (fn [^String nm]
                               (when-some [v (.group m nm)]
                                 [(keyword nm) v])))
                       (named-group-names (.pattern pattern)))})))

(defn pattern-group-info
  "Static (plan-time) analysis of a matcher pattern string: the binding
   names its named groups would produce, plus the total group count for
   the unnamed-only-groups notice. Embedded consumer tokens are
   neutralized before compilation (their values are unknown at plan
   time). Throws :bindings/invalid-pattern on a bad regex — including
   Java's duplicate-named-group error, making duplicates a planning
   error (sl-yh7 AC2).

   Returns {:names #{kw} :group-count n}."
  [pattern-str]
  (let [neutralized (str/replace pattern-str embedded-token-re "x")
        p (try (Pattern/compile neutralized)
               (catch PatternSyntaxException e
                 (throw (ex-info (str "Invalid capture pattern: " (.getMessage e))
                                 {:type :bindings/invalid-pattern
                                  :message (.getMessage e)
                                  :pattern pattern-str}
                                 e))))]
    {:names (set (map keyword (named-group-names (.pattern p))))
     :group-count (-> p (.matcher "") .groupCount)}))

(defn pattern-binding-names
  "The binding names a pattern string would produce — see
   `pattern-group-info`."
  [pattern-str]
  (:names (pattern-group-info pattern-str)))

(defn attempt-capture
  "Non-throwing capture core for pollers (the SMS receive loop). Returns
   {:ctx ctx' :full <match> :bindings {...}} on a hit, nil on no match."
  [ctx pattern-str text]
  (let [p (compile-pattern ctx pattern-str)]
    (when-let [{:keys [full bindings]} (match-named p text)]
      {:ctx (merge-bindings ctx bindings)
       :full full
       :bindings bindings})))

;; -----------------------------------------------------------------------------
;; The public helper
;; -----------------------------------------------------------------------------

(defn- text-excerpt [text]
  (let [s (str text)]
    (if (> (count s) 200) (str (subs s 0 200) "…") s)))

(defn capture!
  "Match pattern-str (a Java regex, may contain (?<name>...) named groups
   and embedded {binding} tokens, which interpolate as quoted literals)
   against text. On a match, merge every participating named group into
   the scenario data plane and return the updated ctx.

   Capture is assert-plus-bind: NO MATCH IS A STEP FAILURE — throws a
   structured :bindings/capture-failure ('it fails as though I could not
   see that text'). Interface-generic: call it with whatever text your
   interface fetched. Pollers that need try-again semantics (e.g. the SMS
   receive timeout ladder) wrap `attempt-capture` instead and convert
   exhaustion into their own structured failure."
  [ctx pattern-str text]
  (or (:ctx (attempt-capture ctx pattern-str text))
      (throw (ex-info (str "Capture failed: pattern /" pattern-str
                           "/ did not match")
                      {:type :bindings/capture-failure
                       :message (str "Capture failed: pattern /" pattern-str
                                     "/ did not match the observed text")
                       :pattern pattern-str
                       :text (text-excerpt text)}))))

;; -----------------------------------------------------------------------------
;; fdefs
;; -----------------------------------------------------------------------------

(s/fdef capture!
  :args (s/cat :ctx map? :pattern-str string? :text (s/nilable string?))
  :ret map?)

(s/fdef merge-bindings
  :args (s/cat :ctx map? :m map?)
  :ret map?)

(s/fdef resolve-token
  :args (s/cat :ctx map? :token-str string?)
  :ret any?)
