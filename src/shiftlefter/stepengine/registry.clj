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
    :fn #function}
   ```

   ## Registry Storage

   Keyed by pattern signature (pattern-src + flags) to detect duplicates
   even when Pattern objects are distinct instances.

   ## Usage

   ```clojure
   (register! #\"I have (\\d+) items\"
              (fn [count-str] (Integer/parseInt count-str))
              {:ns 'my.steps :file \"steps.clj\" :line 10})

   (all-stepdefs)  ;=> seq of stepdef maps
   (clear-registry!)  ;; for test isolation
   ```"
  (:import [java.security MessageDigest]
           [java.util.regex Pattern]))

;; -----------------------------------------------------------------------------
;; Registry State
;; -----------------------------------------------------------------------------

(defonce ^:private registry-atom
  ;; Registry storage: {pattern-sig -> stepdef-map}
  (atom {}))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- pattern-sig
  "Create a stable signature for a Pattern (for duplicate detection).
   Returns string: \"pattern-string|flags\""
  [^Pattern pattern]
  (str (.pattern pattern) "|" (.flags pattern)))

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

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn register!
  "Register a step definition.

   Parameters:
   - pattern: compiled regex Pattern
   - f: step function (must be non-variadic)
   - source: map with :ns, :file, :line (and optionally :var)

   Throws on:
   - Duplicate pattern (same pattern-sig already registered)
   - Variadic function

   Returns the stepdef map."
  [pattern f source]
  (let [sig (pattern-sig pattern)
        pattern-src (.pattern pattern)
        arity (fn-arity f)]
    ;; Validate: reject variadic
    (when (nil? arity)
      (throw (ex-info (str "Step definition cannot be variadic: " pattern-src
                           " at " (format-source source))
                      {:type :stepdef/variadic
                       :pattern-src pattern-src
                       :source source})))

    ;; Check for duplicate
    (when-let [existing (get @registry-atom sig)]
      (throw (ex-info (str "Duplicate step definition for pattern: " pattern-src
                           "\n  First defined at: " (format-source (:source existing))
                           "\n  Duplicate at: " (format-source source))
                      {:type :stepdef/duplicate
                       :pattern-src pattern-src
                       :existing-source (:source existing)
                       :duplicate-source source})))

    ;; Create stepdef map
    (let [stepdef {:stepdef/id (stepdef-id pattern-src)
                   :pattern pattern
                   :pattern-src pattern-src
                   :source source
                   :arity arity
                   :fn f}]
      ;; Store in registry
      (swap! registry-atom assoc sig stepdef)
      stepdef)))

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

   Usage:
   (defstep #\"I have (\\d+) items\"
     [count-str]
     (Integer/parseInt count-str))

   (defstep #\"I click \\\"([^\\\"]+)\\\"\"
     [element ctx]
     (click element)
     ctx)

   The macro captures source location automatically."
  [pattern args & body]
  (let [file *file*
        line (:line (meta &form))
        ns-sym (ns-name *ns*)]
    `(register! ~pattern
                (fn ~args ~@body)
                {:ns '~ns-sym
                 :file ~file
                 :line ~line})))
