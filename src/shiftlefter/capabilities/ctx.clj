(ns shiftlefter.capabilities.ctx
  "Generic capability helpers for ctx management.

   Capabilities are stored in ctx under `:cap/<interface-name>` with the shape:

   ```clj
   {:cap/web {:impl <browser-instance>
              :mode :ephemeral}}
   ```

   ## Capability Shape

   - `:impl` — the capability implementation (e.g., EtaoinBrowser)
   - `:mode` — `:ephemeral` (created per scenario) or `:persistent` (reused)
   - `:cleanup-handle` — optional. The value cleanup passes to the adapter's
     `:cleanup` fn, when it differs from `:impl`. Wrapping adapters (browser,
     sl-091) expose the bare protocol impl to steps via `:impl` but need the
     full factory result (carrying the driver) at teardown; that result lives
     here. Absent when impl and handle coincide (e.g. SMS) — cleanup then
     falls back to `:impl`.

   ## Interface Names

   Interface names are keywords like `:web`, `:api`, `:sms`, `:email`.
   They correspond to interface config keys in `shiftlefter.edn`.

   ## Usage

   ```clojure
   (require '[shiftlefter.capabilities.ctx :as cap])

   ;; Check if capability exists
   (cap/capability-present? ctx :web)  ;; => false

   ;; Store a capability
   (def ctx2 (cap/assoc-capability ctx :web browser :ephemeral))

   ;; Retrieve it
   (cap/get-capability ctx2 :web)  ;; => browser

   ;; Check mode
   (cap/get-capability-mode ctx2 :web)  ;; => :ephemeral
   ```"
  (:require [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Key Construction
;; -----------------------------------------------------------------------------

(defn capability-key
  "Construct the ctx key for a capability.

   1-arity: interface only — :web => :cap/web
   2-arity: interface + subject — :web :alice => :cap/web.alice

   Subject-keyed capabilities allow multiple instances per interface
   (e.g., separate browsers for Alice and Bob)."
  ([interface-name]
   (keyword "cap" (name interface-name)))
  ([interface-name subject]
   (if subject
     (keyword "cap" (str (name interface-name) "." (name subject)))
     (keyword "cap" (name interface-name)))))

;; -----------------------------------------------------------------------------
;; Predicates
;; -----------------------------------------------------------------------------

(defn capability-present?
  "Returns true if ctx has a capability for the given interface name.

   2-arity: checks interface only (:cap/web)
   3-arity: checks interface+subject (:cap/web.alice)"
  ([ctx interface-name]
   (let [k (capability-key interface-name)]
     (boolean (get ctx k))))
  ([ctx interface-name subject]
   (let [k (capability-key interface-name subject)]
     (boolean (get ctx k)))))

;; -----------------------------------------------------------------------------
;; Accessors
;; -----------------------------------------------------------------------------

(defn get-capability
  "Returns the capability implementation for interface name, or nil if not present.

   Returns the :impl value, not the full capability map.
   3-arity version checks subject-keyed capability."
  ([ctx interface-name]
   (let [k (capability-key interface-name)]
     (get-in ctx [k :impl])))
  ([ctx interface-name subject]
   (let [k (capability-key interface-name subject)]
     (get-in ctx [k :impl]))))

(defn get-capability-mode
  "Returns the capability mode (:ephemeral or :persistent), or nil if not present."
  [ctx interface-name]
  (let [k (capability-key interface-name)]
    (get-in ctx [k :mode])))

(defn get-capability-entry
  "Returns the full capability entry map {:impl ... :mode ...}, or nil if not present."
  ([ctx interface-name]
   (let [k (capability-key interface-name)]
     (get ctx k)))
  ([ctx interface-name subject]
   (let [k (capability-key interface-name subject)]
     (get ctx k))))

;; -----------------------------------------------------------------------------
;; Mutators (pure — return new ctx)
;; -----------------------------------------------------------------------------

(defn assoc-capability
  "Store a capability in ctx for the given interface name.

   Parameters:
   - ctx: The context map
   - interface-name: Keyword like :web, :api
   - impl: The capability implementation
   - mode: :ephemeral or :persistent
   - subject: Optional subject keyword for per-subject storage

   Returns updated ctx with capability stored under :cap/<interface-name>
   or :cap/<interface-name>.<subject>."
  ([ctx interface-name impl mode]
   (let [k (capability-key interface-name)]
     (assoc ctx k {:impl impl :mode mode})))
  ([ctx interface-name impl mode subject]
   (let [k (capability-key interface-name subject)]
     (assoc ctx k {:impl impl :mode mode}))))

(defn dissoc-capability
  "Remove a capability from ctx for the given interface name.

   Returns updated ctx with capability removed."
  [ctx interface-name]
  (let [k (capability-key interface-name)]
    (dissoc ctx k)))

(defn update-capability
  "Update the implementation of an existing capability.

   Preserves the mode. Returns ctx unchanged if capability doesn't exist."
  [ctx interface-name new-impl]
  (let [k (capability-key interface-name)]
    (if (get ctx k)
      (assoc-in ctx [k :impl] new-impl)
      ctx)))

;; -----------------------------------------------------------------------------
;; Subject Key Parsing
;; -----------------------------------------------------------------------------

(defn parse-capability-name
  "Parse a capability name into interface and optional subject.

   :web        => {:interface :web :subject nil}
   :web.alice  => {:interface :web :subject :alice}

   Used by cleanup to find the right interface config for subject-keyed capabilities."
  [cap-name]
  (let [n (name cap-name)
        dot-idx (.indexOf n ".")]
    (if (neg? dot-idx)
      {:interface cap-name :subject nil}
      {:interface (keyword (subs n 0 dot-idx))
       :subject (keyword (subs n (inc dot-idx)))})))

;; -----------------------------------------------------------------------------
;; Enumeration
;; -----------------------------------------------------------------------------

(defn all-capabilities
  "Returns a map of all capabilities in ctx.

   Result shape: {:web {:impl ... :mode ...} :api {...} ...}"
  [ctx]
  (reduce-kv
   (fn [acc k v]
     (if (and (keyword? k)
              (= "cap" (namespace k)))
       (assoc acc (keyword (name k)) v)
       acc))
   {}
   ctx))

(defn capability-names
  "Returns a vector of interface names that have capabilities in ctx."
  [ctx]
  (vec (keys (all-capabilities ctx))))

(defn ephemeral-capabilities
  "Returns interface names of all ephemeral capabilities in ctx."
  [ctx]
  (->> (all-capabilities ctx)
       (filter (fn [[_k v]] (= :ephemeral (:mode v))))
       (map first)
       vec))

(defn find-existing-shared-impl
  "Find an existing impl for `interface-name` across any subject-keyed
   entry in ctx, returning it (or nil if none exists).

   Used by `:shared-impl?` interfaces during provisioning: when Alice's
   SMS step provisions :cap/sms.alice, Bob's subsequent SMS step finds
   that impl here and reuses it under :cap/sms.bob — one Twilio HTTP
   client across all subject entries in the scenario."
  [ctx interface-name]
  (let [iface-key (keyword "cap" (name interface-name))
        prefix    (str (name interface-name) ".")
        match?    (fn [k]
                    (and (keyword? k)
                         (= "cap" (namespace k))
                         (or (= k iface-key)
                             (str/starts-with? (name k) prefix))))]
    (some (fn [[k v]] (when (match? k) (:impl v))) ctx)))

(defn persistent-capabilities
  "Returns interface names of all persistent capabilities in ctx."
  [ctx]
  (->> (all-capabilities ctx)
       (filter (fn [[_k v]] (= :persistent (:mode v))))
       (map first)
       vec))
