(ns shiftlefter.svo.extract
  "SVO extraction from stepdef metadata and captures.

   ## Extraction Process

   Given stepdef metadata:
   ```clojure
   {:interface :web
    :svo {:subject :$1 :verb :click :object :$2}}
   ```

   And captures from step matching:
   ```clojure
   [\"Alice\" \"the login button\"]
   ```

   Produces SVOI map:
   ```clojure
   {:subject :alice              ;; normalized from \"Alice\"
    :verb :click                 ;; literal from metadata
    :object \"the login button\"  ;; from :$2
    :interface :web}             ;; from metadata
   ```

   ## Placeholder Convention

   - `:$1`, `:$2`, etc. reference capture groups (1-indexed)
   - Subject values are normalized (lowercase keyword)
   - Object values are left as-is (strings)
   - Verb and interface are always literal keywords"
  (:require [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Subject Normalization
;; -----------------------------------------------------------------------------

(defn normalize-subject
  "Normalize a subject string to a keyword.

   - Trims whitespace
   - Lowercases
   - Converts to keyword

   Examples:
     \"Alice\" => :alice
     \"ADMIN\" => :admin
     \" Bob \" => :bob
     \"system/test-setup\" => :system/test-setup"
  [s]
  (when (string? s)
    (let [trimmed (str/trim s)
          lower (str/lower-case trimmed)]
      (keyword lower))))

;; -----------------------------------------------------------------------------
;; Placeholder Substitution
;; -----------------------------------------------------------------------------

(defn- placeholder?
  "Returns true if value is a placeholder keyword like :$1, :$2."
  [v]
  (and (keyword? v)
       (str/starts-with? (name v) "$")))

(defn- placeholder-index
  "Extract the index from a placeholder keyword.
   :$1 => 0, :$2 => 1, etc. (0-indexed for vector access)"
  [placeholder]
  (let [n (subs (name placeholder) 1)]
    (dec (parse-long n))))

(defn- substitute-value
  "Substitute a single value, replacing placeholders with captures."
  [v captures]
  (if (placeholder? v)
    (let [idx (placeholder-index v)]
      (if (and (>= idx 0) (< idx (count captures)))
        (nth captures idx)
        {:type :svo/placeholder-out-of-bounds
         :message (str "Placeholder " v " references capture " (inc idx)
                       " but only " (count captures) " captures available")
         :placeholder v
         :index (inc idx)
         :capture-count (count captures)}))
    v))

(defn substitute-placeholders
  "Substitute placeholder keywords with capture values.

   Placeholders are keywords like :$1, :$2 that reference capture groups.
   Non-placeholder values pass through unchanged.

   Parameters:
   - svo-map: Map with possible placeholder values {:subject :$1 :verb :click ...}
   - captures: Vector of captured strings from step matching

   Returns:
   - Substituted map on success
   - Error map with :type if placeholder index out of bounds

   Examples:
     (substitute-placeholders {:subject :$1 :verb :click} [\"Alice\"])
     => {:subject \"Alice\" :verb :click}

     (substitute-placeholders {:subject :$1} [])
     => {:type :svo/placeholder-out-of-bounds ...}"
  [svo-map captures]
  (reduce-kv
   (fn [acc k v]
     (if (:type acc)
       ;; Already errored, short-circuit
       acc
       (let [substituted (substitute-value v captures)]
         (if (:type substituted)
           ;; Error during substitution
           substituted
           (assoc acc k substituted)))))
   {}
   svo-map))

;; -----------------------------------------------------------------------------
;; SVOI Extraction
;; -----------------------------------------------------------------------------

(defn extract-svoi
  "Extract SVOI map from stepdef metadata and captures.

   Parameters:
   - metadata: Stepdef metadata map with :interface and :svo keys
   - captures: Vector of captured strings from step matching

   Returns:
   - nil if metadata is nil or has no :svo key
   - SVOI map {:subject :kw :verb :kw :object any :interface :kw} on success
   - Error map with :type on failure (placeholder out of bounds)

   The :subject value is normalized to a lowercase keyword.
   The :object value is left as-is (string from capture or literal).
   The :verb and :interface are literal keywords from metadata.

   Examples:
     (extract-svoi {:interface :web :svo {:subject :$1 :verb :click :object :$2}}
                   [\"Alice\" \"the button\"])
     => {:subject :alice :verb :click :object \"the button\" :interface :web}

     (extract-svoi nil [\"x\"])
     => nil

     (extract-svoi {:interface :web} [\"x\"])
     => nil"
  [metadata captures]
  (when-let [svo (:svo metadata)]
    (let [interface (:interface metadata)
          substituted (substitute-placeholders svo captures)]
      (if (:type substituted)
        ;; Error during substitution
        substituted
        ;; Success — normalize subject and build SVOI
        (let [subject-raw (:subject substituted)
              subject (if (string? subject-raw)
                        (normalize-subject subject-raw)
                        subject-raw)]
          {:subject subject
           :verb (:verb substituted)
           :object (:object substituted)
           :interface interface})))))
