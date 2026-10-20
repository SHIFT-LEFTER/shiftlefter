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
   unrelated; this ns is the scenario DATA plane.

   LAYERING (intentional, module map F2 / sl-52ly): svo/{validate,
   bindings_lint} require this ns for the token GRAMMAR it defines
   (name charset, token/literal regex fragments) — the shared-classifier
   doctrine: plan-time validation and exec-time resolution must read one
   definition. The law: svo may consume engine-owned vocabulary/grammar
   definitions, never compile/exec/registry machinery."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [shiftlefter.intent.loader :as loader]
            [shiftlefter.intent.resolve :as intent-resolve])
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

(def literal-re-fragment
  "Regex pattern STRING for a nonempty quoted literal (sl-jlqz): a literal
   quote inside is written SQL-style doubled ('' = one ' character), so
   'Don''t' is the text Don't. The doubled pair needs adjacency, which is
   what keeps multiple quoted slots in one step unambiguous. Bare '' (the
   empty literal) stays a no-match — the alternation requires at least one
   unit — and draws the clears hint."
  "'(?:[^']|'')+'")

(def value-re-fragment
  "Central value-slot regex fragment (sl-yh7): a quoted literal — captured
   WITH its quotes; the engine strips them by the frame's :arg-kinds — or a
   {binding} token. Splice into step patterns as (str \"(\" fragment \")\").
   Quoted is always literal (sl-iseq)."
  (str literal-re-fragment "|" token-re-fragment))

(def matcher-re-fragment
  "Central matcher-slot regex fragment (sl-ectw): an inline `/…/` content
   pattern — captured WITH its slashes (grammar-markers clause 4: capture
   with the markers, classify centrally) — or one bare non-space token, the
   `Intent.element` reference to a declared `:shape`. Deliberately
   permissive at bind time: every rejection lives in `resolve-matcher`, the
   one classifier the bindings lint (plan) and the exec normalizer (run)
   both call. Splice as (str \"(\" fragment \")\")."
  "/.+/|[^\\s/]\\S*")

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

(defn undouble-quotes
  "Collapse SQL-style doubled quotes in a literal's INTERIOR ('' -> ',
   sl-jlqz). Authored-surface syntax only: never applied to resolved
   binding values or observed text."
  [s]
  (str/replace s "''" "'"))

(defn double-quotes
  "Inverse of `undouble-quotes` (' -> ''): escape a value for re-wrapping
   in quote delimiters, so strip-then-undouble round-trips to the value."
  [s]
  (str/replace s "'" "''"))

(defn- strip-quotes [s] (undouble-quotes (subs s 1 (dec (count s)))))

;; -----------------------------------------------------------------------------
;; Matcher-slot classification (sl-ectw) — the one classifier
;; -----------------------------------------------------------------------------

(def ^:private inline-matcher-re
  "A matcher slot captured with its slashes: `/…/`."
  #"/(.+)/")

(defn inline-matcher?
  "True when `s` is an inline `/…/` content pattern (with its slashes)."
  [s]
  (and (string? s) (some? (re-matches inline-matcher-re s))))

(defn- shape-ref-error [type message capture extra]
  {:error (merge {:type type :message message :ref capture} extra)})

(defn resolve-matcher
  "Classify one matcher-slot capture (captured WITH its markers, per
   `matcher-re-fragment`) into the regex source the step runs. THE shared
   classifier: the bindings lint calls it at plan time, the exec
   normalizer at run time, so plan and run can never disagree.

   - `/…/`            → {:ok {:pattern <inside> :inline true}}
   - `Intent.element` → the element's declared :shape:
                        {:ok {:pattern <effective source> :ref <capture>
                              :shape {…} :intent :element :def-site {…}}}

   `intents` is the loaded intents map (nil when none are loaded);
   `interface-type` the step's interface TYPE (:web, :sms, …; nil skips
   the binding check). Errors, all `:bindings/*` so the normalizer turns
   them into a structured step failure:

   - :bindings/invalid-matcher-slot          neither form
   - :bindings/unknown-shape-ref             unknown intent/element
   - :bindings/element-has-no-shape          the element declares no :shape
                                             (def-site + the two exits)
   - :bindings/shape-no-binding-for-interface the element has a :shape but
                                             no binding on this interface"
  [capture intents interface-type]
  (cond
    (not (string? capture))
    (shape-ref-error :bindings/invalid-matcher-slot
                     (str "matcher slot must be /…/ or an Intent.element "
                          "reference, got: " (pr-str capture))
                     capture {})

    (inline-matcher? capture)
    {:ok {:pattern (subs capture 1 (dec (count capture))) :inline true}}

    :else
    (let [parsed (intent-resolve/parse-intent-ref capture)]
      (if (:error parsed)
        (shape-ref-error :bindings/invalid-matcher-slot
                         (str "matcher slot " (pr-str capture) " is neither an "
                              "inline /…/ pattern nor an Intent.element "
                              "reference to a declared :shape")
                         capture {})
        (let [{:keys [intent element] :as terminal}
              (intent-resolve/terminal-element intents (:ok parsed))]
          (if-not (and terminal (loader/known-element? intents intent element))
            (shape-ref-error :bindings/unknown-shape-ref
                             (str "matcher slot " capture " names no known "
                                  "intent element")
                             capture {:intent (:intent (:ok parsed))})
            (let [shape-r (loader/get-shape intents intent element)
                  def-site (loader/element-def-site intents intent element)
                  binding-r (when interface-type
                              (loader/get-binding intents intent element
                                                  interface-type))]
              (cond
                (:error shape-r)
                (shape-ref-error :bindings/element-has-no-shape
                                 (str capture " declares no :shape"
                                      (when (:line def-site)
                                        (str " (" (:file def-site) ":"
                                             (:line def-site) ")"))
                                      " — add :shape \"…\" to the element, or "
                                      "write the pattern inline: matching /…/")
                                 capture (cond-> {:intent intent :element element}
                                           def-site (assoc :def-site def-site)))

                (:error binding-r)
                (shape-ref-error :bindings/shape-no-binding-for-interface
                                 (str capture " has a :shape but no "
                                      (name interface-type) " binding — a shape "
                                      "is read through the element's binding on "
                                      "the interface that runs the step")
                                 capture (cond-> {:intent intent :element element
                                                  :interface interface-type
                                                  :available-interfaces
                                                  (:available-interfaces (:error binding-r))}
                                           def-site (assoc :def-site def-site)))

                :else
                {:ok (cond-> {:pattern (:pattern (:ok shape-r))
                              :ref capture
                              :shape (:ok shape-r)
                              :intent intent
                              :element element}
                       def-site (assoc :def-site def-site))}))))))))

(defn normalize-capture
  "Normalize one capture by its bind-time slot kind, against the live
   scenario ctx. This is the ONLY place captured {binding} tokens are
   resolved — stepdef fns stay byte-unchanged and binding-agnostic.

   :value    — quoted literal: strip quotes (quoted is ALWAYS literal,
               sl-iseq — a quoted '{code}' stays the text {code});
               whole token: resolve, stringified.
   :text     — same as :value (sl-2sj6): a content-only O slot (the
               see-title text).
   :location — whole token: resolve and QUOTE-WRAP so the stepdef's
               literal/ref classifier takes its literal path unchanged.
   :matcher  — classified by `resolve-matcher` (sl-ectw): an inline
               `/…/` loses its slashes; an `Intent.element` ref becomes
               the element's declared :shape pattern. The stepdef fn
               receives bare regex source either way. `opts` carries
               `{:intents <loaded> :interface <type>}`; without them a
               ref cannot resolve (bare harnesses pass inline only).
               Matcher-embedded tokens are interpolated later inside
               capture!, where Pattern/quote lives.
   :key / nil — untouched (:key slots admit no tokens; the stepdef
               classifies quoted-vs-ref itself)."
  ([capture kind scenario-ctx]
   (normalize-capture capture kind scenario-ctx nil))
  ([capture kind scenario-ctx opts]
   (case kind
     (:value :text) (cond
                      (quoted-literal? capture) (strip-quotes capture)
                      (token? capture) (str (resolve-token scenario-ctx capture))
                      :else capture)
     :location (if (token? capture)
                 ;; Quote-wrap with interior quotes doubled (sl-jlqz) so the
                 ;; stepdef's strip-then-undouble is the exact inverse — a
                 ;; resolved value containing ' survives the round trip.
                 (str "'" (double-quotes (str (resolve-token scenario-ctx capture))) "'")
                 capture)
     :matcher (let [r (resolve-matcher capture (:intents opts) (:interface opts))]
                (if-let [err (:error r)]
                  (throw (ex-info (:message err) err))
                  (get-in r [:ok :pattern])))
     capture)))

(defn normalize-captures
  "Normalize a captures vector by slot kinds. Returns {:ok captures'} or
   {:error <structured :bindings/* error>} on a failed token resolution or
   an unresolvable matcher slot — the caller fails the step with that
   error (never a raw exception). `opts` (optional) is `{:intents <loaded>
   :interface <type>}` for matcher-slot shape refs (sl-ectw)."
  ([captures slot-kinds scenario-ctx]
   (normalize-captures captures slot-kinds scenario-ctx nil))
  ([captures slot-kinds scenario-ctx opts]
   (if (empty? slot-kinds)
     {:ok captures}
     (try
       {:ok (vec (map-indexed
                  (fn [i capture]
                    (normalize-capture capture (get slot-kinds i) scenario-ctx opts))
                  captures))}
       (catch clojure.lang.ExceptionInfo e
         (let [data (ex-data e)]
           (if (and (keyword? (:type data))
                    (= "bindings" (namespace (:type data))))
             {:error data}
             (throw e))))))))

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
                       (keys (.namedGroups pattern)))})))

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
    {:names (set (map keyword (keys (.namedGroups ^Pattern p))))
     :group-count (-> ^Pattern p (.matcher "") .groupCount)}))

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
