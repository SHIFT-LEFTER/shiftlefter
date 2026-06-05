(ns shiftlefter.stepengine.registry
  "Step definition registry for ShiftLefter runner.

   ## Stepdef Map Shape

   Each registered step definition is stored as:
   ```clojure
   {:stepdef/id \"sd-abc123...\"     ;; deterministic, stable across runs
    :pattern #\"regex\"               ;; compiled Pattern
    :pattern-src \"regex\"            ;; string form for display/matching
    :source {:ns 'my.steps :file \"my/steps.clj\" :line 42}
    :arity 2                         ;; non-variadic only
    :fn #function
    :metadata {:interface :web       ;; optional, nil for legacy steps
               :svo {:subject :$1 :verb :click :object :$2}}}
   ```

   ## Registry Storage

   Keyed by the pair `[pattern-sig, interface-keyword]` where:
   - `pattern-sig` = pattern source string + flags
   - `interface-keyword` = `:interface` metadata value, or `nil` for
     interface-less stepdefs

   This lets the same regex register under multiple interfaces (e.g.,
   `receives a message` under `:sms`, `:whatsapp`, `:email`) — vocabulary
   symmetry across channels. Duplicate detection still fires when the same
   regex is registered twice under the same interface (or twice with no
   interface). The binder disambiguates at bind-time via step-level
   `[:interface]` annotations; see `stepengine.annotations`.

   ## Usage

   ```clojure
   (register! #\"I have (\\d+) items\"
              (fn [count-str] (Integer/parseInt count-str))
              {:ns 'my.steps :file \"steps.clj\" :line 10})

   (all-stepdefs)  ;=> seq of stepdef maps
   (clear-registry!)  ;; for test isolation
   ```"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.tools.logging :as log])
  (:import [java.security MessageDigest]
           [java.util.regex Pattern]))

;; -----------------------------------------------------------------------------
;; Specs — Step :svo metadata structure (Tier 1)
;; -----------------------------------------------------------------------------
;;
;; A step's :svo metadata declares how regex captures map to subject,
;; verb, frame, object, and per-frame args. These specs check structural
;; correctness only — that captures look like :$N, that :verb and :frame
;; are keywords, that :args is a map of arg-name to capture-ref. Whether
;; a verb/frame/args combination actually exists in the glossary is a
;; semantic check (Tier 2), not handled here.
;;
;; See sl-hse for the design discussion.

(s/def ::capture-ref
  (s/with-gen
    (s/and keyword?
           #(re-matches #"\$\d+" (name %)))
    #(gen/fmap (fn [n] (keyword (str "$" n)))
               (gen/large-integer* {:min 1 :max 99}))))

(s/def ::subject ::capture-ref)
(s/def ::verb keyword?)
(s/def ::frame keyword?)
(s/def ::object (s/nilable ::capture-ref))
(s/def ::args (s/map-of keyword? ::capture-ref))

(s/def ::stepdef-svo
  (s/keys :req-un [::subject ::verb ::frame]
          :opt-un [::object ::args]))

;; The :svo key inside step metadata points to a stepdef-svo map. Aliased
;; so `(s/keys :opt-un [::svo])` picks up the un-namespaced :svo key.
(s/def ::svo ::stepdef-svo)

(s/def ::interface keyword?)

;; Capability gating — sl-ewn (introduced) / sl-unz (lifted to suite-load).
;;
;; A step may declare `:requires-protocols [<qualified-keyword> ...]`. At
;; suite-load time (post-bind, dedup-by-stepdef), the framework checks
;; the configured adapter's `:provides` set and fails with
;; `:stepdef/missing-capability` when a required protocol is not in the
;; box. This lets test-seam-only steps (e.g., `simulate-inbound!`
;; requiring `ISMSInbound`) fail with one actionable error per stepdef
;; before any side effects, instead of N times mid-scenario.
(s/def ::requires-protocols (s/coll-of qualified-keyword? :kind vector?))

(s/def ::metadata
  (s/keys :opt-un [::interface ::svo ::requires-protocols]))

;; -----------------------------------------------------------------------------
;; Registry State
;; -----------------------------------------------------------------------------

(defonce ^:private registry-atom
  ;; Registry storage: {[pattern-sig interface-kw-or-nil] -> stepdef-map}
  (atom {}))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- pattern-sig
  "Create a stable signature for a Pattern (for duplicate detection).
   Returns string: \"pattern-string|flags\""
  [^Pattern pattern]
  (str (.pattern pattern) "|" (.flags pattern)))

(defn- registry-key
  "Compute the registry key for a pattern + metadata.
   Returns `[pattern-sig interface-kw-or-nil]`.

   Two stepdefs collide only when BOTH the pattern signature AND the
   interface keyword match (nil counts as its own bucket for legacy steps)."
  [pattern metadata]
  [(pattern-sig pattern) (:interface metadata)])

(defn- sha256-hex
  "Compute SHA-256 hash of string, return as hex."
  [^String s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest digest (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) hash-bytes))))

(defn- stepdef-id
  "Generate deterministic stepdef ID from pattern source.
   Format: \"sd-<first-16-chars-of-sha256>\""
  [pattern-src]
  (str "sd-" (subs (sha256-hex pattern-src) 0 16)))

(defn- fn-arity
  "Extract the arity of a function. Returns nil if variadic.

   For single-arity fns, returns the count.
   For multi-arity fns, returns the arity list (not supported in Phase 1).
   For variadic fns, returns nil (caller should reject)."
  [f]
  (let [methods (.getDeclaredMethods (class f))
        invokes (filter #(= "invoke" (.getName %)) methods)
        arities (map #(count (.getParameterTypes %)) invokes)]
    (cond
      ;; Check for variadic via doInvoke (Clojure variadic impl)
      (some #(= "doInvoke" (.getName %)) methods)
      nil  ;; variadic

      ;; Single arity
      (= 1 (count arities))
      (first arities)

      ;; Multi-arity - return minimum for now (Phase 1 simplification)
      ;; The binder will validate actual arity against captures
      (seq arities)
      (apply min arities)

      ;; Fallback
      :else 0)))

(defn- format-source
  "Format source location for error messages: \"file.clj:line\""
  [{:keys [file line]}]
  (str file ":" line))

(defn- validate-metadata
  "Validate stepdef metadata shape. Emits a tools.logging warn when the
   metadata shape is suspicious. Returns the metadata unchanged."
  [metadata source]
  (when metadata
    ;; Warn if :interface without :svo
    (when (and (:interface metadata) (not (:svo metadata)))
      (log/warnf "Step at %s has :interface without :svo - SVO validation will be skipped"
                 (format-source source)))
    ;; :requires-protocols only makes sense with an :interface; the binder
    ;; needs an interface to look up the configured adapter.
    (when (and (:requires-protocols metadata) (not (:interface metadata)))
      (log/warnf "Step at %s has :requires-protocols without :interface - capability gate cannot fire"
                 (format-source source))))
  metadata)

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn register!
  "Register a step definition.

   Parameters:
   - pattern: compiled regex Pattern
   - f: step function (must be non-variadic)
   - source: map with :ns, :file, :line (and optionally :var)
   - metadata: optional map with :interface and :svo keys

   Throws on:
   - Duplicate pattern (same pattern-sig already registered)
   - Variadic function

   Returns the stepdef map."
  ([pattern f source]
   (register! pattern f source nil))
  ([pattern f source metadata]
   (let [pattern-src (.pattern pattern)
         arity (fn-arity f)
         validated-metadata (validate-metadata metadata source)
         key (registry-key pattern validated-metadata)
         iface (second key)]
     ;; Validate: reject variadic
     (when (nil? arity)
       (throw (ex-info (str "Step definition cannot be variadic: " pattern-src
                            " at " (format-source source))
                       {:type :stepdef/variadic
                        :pattern-src pattern-src
                        :source source})))

     ;; Check for duplicate — same pattern AND same interface
     (when-let [existing (get @registry-atom key)]
       (throw (ex-info (str "Duplicate step definition for pattern: " pattern-src
                            (when iface
                              (str " under interface " iface))
                            "\n  First defined at: " (format-source (:source existing))
                            "\n  Duplicate at: " (format-source source))
                       {:type :stepdef/duplicate
                        :pattern-src pattern-src
                        :interface iface
                        :existing-source (:source existing)
                        :duplicate-source source})))

     ;; Create stepdef map
     (let [stepdef {:stepdef/id (stepdef-id pattern-src)
                    :pattern pattern
                    :pattern-src pattern-src
                    :source source
                    :arity arity
                    :fn f
                    :metadata validated-metadata}]
       ;; Store in registry
       (swap! registry-atom assoc key stepdef)
       stepdef))))

(s/fdef register!
  :args (s/cat :pattern any?
               :f       any?
               :source  any?
               :metadata (s/? (s/nilable ::metadata))))

(defn clear-registry!
  "Clear all registered step definitions. Use for test isolation."
  []
  (reset! registry-atom {})
  nil)

(defn all-stepdefs
  "Return all registered step definitions as a seq of stepdef maps."
  []
  (vals @registry-atom))

(defn find-by-pattern
  "Find a stepdef by its pattern source string. Returns nil if not found."
  [pattern-src]
  (first (filter #(= pattern-src (:pattern-src %)) (all-stepdefs))))

;; -----------------------------------------------------------------------------
;; defstep Macro
;; -----------------------------------------------------------------------------

(defmacro defstep
  "Define and register a step definition.

   ## Basic Usage (ctx-first)

   Step functions that need accumulated state take ctx as FIRST argument:
   ```clojure
   (defstep #\"I have (\\d+) cucumbers\" [ctx n]
     (assoc ctx :cucumbers (parse-long n)))

   (defstep #\"I eat (\\d+)\" [ctx n]
     (update ctx :cucumbers - (parse-long n)))

   (defstep #\"I should have (\\d+) cucumbers\" [ctx n]
     (assert (= (:cucumbers ctx) (parse-long n)))
     ctx)
   ```

   ## Without ctx (captures only)

   Steps that don't need accumulated state can omit ctx:
   ```clojure
   (defstep #\"I print hello\" []
     (println \"hello\"))  ;; returns nil, ctx unchanged
   ```

   ## DataTable / DocString Access

   Use the `shiftlefter.step` helpers to access step arguments:
   ```clojure
   (require '[shiftlefter.step :as step])

   (defstep #\"the following users exist:\" [ctx]
     (when-let [table (step/arguments ctx)]
       (doseq [[name email] (rest (:rows table))]
         (create-user! name email)))
     ctx)
   ```

   ## With SVO Metadata

   For Shifted mode with subject/verb/object validation:
   ```clojure
   (defstep #\"(.*) clicks (.*)\"
     {:interface :web
      :svo {:subject :$1 :verb :click :object :$2}}
     [ctx subject target]
     (click target)
     ctx)
   ```

   Metadata keys:
   - :interface — which interface this step uses (e.g., :web, :api)
   - :svo — subject/verb/object extraction map with :$1, :$2 placeholders

   ## step-meta Local Binding

   When a step has metadata, the macro injects a `step-meta` local binding
   that contains the metadata map. This allows steps to access their own
   metadata at runtime:

   ```clojure
   (defstep #\":([\\w./-]+) clicks (.*)\"
     {:interface :web
      :svo {:subject :$1 :verb :click :object :$2}}
     [ctx subject locator-str]
     ;; step-meta is automatically available:
     ;; {:interface :web :svo {...}}
     (let [interface (:interface step-meta)]
       (resolve-and-click ctx subject locator-str interface)))
   ```

   For legacy steps without metadata, `step-meta` is nil.

   The macro captures source location automatically."
  [pattern second-arg & rest-args]
  (let [file *file*
        line (:line (meta &form))
        ns-sym (ns-name *ns*)
        source-map `{:ns '~ns-sym :file ~file :line ~line}
        ;; Detect if second-arg is a map (metadata) or vector (args)
        has-metadata? (map? second-arg)]
    (if has-metadata?
      ;; With metadata: second-arg is metadata map, first of rest-args is args
      ;; Inject step-meta local binding so step body can access metadata
      (let [metadata second-arg
            args (first rest-args)
            body (rest rest-args)]
        `(register! ~pattern
                    (fn ~args
                      (let [~'step-meta ~metadata]
                        ~@body))
                    ~source-map
                    ~metadata))
      ;; Legacy: second-arg is args vector, rest-args is body
      ;; Inject step-meta as nil for consistency
      (let [args second-arg
            body rest-args]
        `(register! ~pattern
                    (fn ~args
                      (let [~'step-meta nil]
                        ~@body))
                    ~source-map
                    nil)))))
