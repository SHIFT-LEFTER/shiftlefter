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

(defn- warn
  "Print warning to stderr."
  [& args]
  (binding [*out* *err*]
    (apply println "WARNING:" args)))

(defn- validate-metadata
  "Validate stepdef metadata shape. Prints warning if suspicious.
   Returns the metadata unchanged."
  [metadata source]
  (when metadata
    ;; Warn if :interface without :svo
    (when (and (:interface metadata) (not (:svo metadata)))
      (warn (str "Step at " (format-source source)
                 " has :interface without :svo - SVO validation will be skipped"))))
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
   (let [sig (pattern-sig pattern)
         pattern-src (.pattern pattern)
         arity (fn-arity f)
         validated-metadata (validate-metadata metadata source)]
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
                    :fn f
                    :metadata validated-metadata}]
       ;; Store in registry
       (swap! registry-atom assoc sig stepdef)
       stepdef))))

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

   Usage (legacy, no metadata):
   ```clojure
   (defstep #\"I have (\\d+) items\"
     [count-str]
     (Integer/parseInt count-str))
   ```

   Usage (with metadata):
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
      (let [metadata second-arg
            args (first rest-args)
            body (rest rest-args)]
        `(register! ~pattern
                    (fn ~args ~@body)
                    ~source-map
                    ~metadata))
      ;; Legacy: second-arg is args vector, rest-args is body
      (let [args second-arg
            body rest-args]
        `(register! ~pattern
                    (fn ~args ~@body)
                    ~source-map
                    nil)))))
