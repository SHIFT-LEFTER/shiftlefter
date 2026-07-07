(ns shiftlefter.browser.target
  "The resolved-target shape that flows from resolution into IBrowser ops.

   A **resolved target** is one of:

   ```clj
   {:q  <query>}    ; find-by-query in the current document
   {:el <handle>}   ; act on / scope within an already-located element
   ```

   plus a **vector** of `{:el …}` targets, produced only by a `[*]` (whole-
   collection) intent reference. Handles are backend-native and opaque — see
   `shiftlefter.browser.protocol`.

   This namespace owns the boundary specs (acceptance #6) and the small set of
   predicates/guards shared by the adapters, the resolver, and the stepdefs.
   It deliberately holds no browser logic — just the shape."
  (:require [clojure.spec.alpha :as s]))

;; -----------------------------------------------------------------------------
;; Boundary specs
;; -----------------------------------------------------------------------------

;; A handle is backend-native and opaque to everything outside an adapter:
;; an etaoin element-id (string) or a Playwright Locator. We do not constrain
;; its type beyond "present" — inspecting it would leak the backend.
(s/def ::handle some?)

;; A query is a resolved locator's selector map: {:css ...}/{:xpath ...}/...
(s/def ::query map?)

;; Value specs named for the LITERAL target keys, so `s/keys :req-un` binds
;; `:el` / `:q` exactly (and the specs stay generatable for spec-health).
(s/def ::el ::handle)
(s/def ::q ::query)

(s/def ::el-target (s/keys :req-un [::el]))
(s/def ::q-target (s/keys :req-un [::q]))

;; A single resolved target: either a query target or an element target.
(s/def ::target (s/or :query ::q-target :element ::el-target))

;; A scope for query-all: the whole document (`:document`/nil) or within a
;; located element. NOTE: `nil` needs its own branch — `(#{:document nil} nil)`
;; returns nil (falsy) because the set membership *value* is nil.
(s/def ::scope (s/or :document #{:document}
                     :nil      nil?
                     :element  ::el-target))

;; The return of query-all: a vector of element targets.
(s/def ::targets (s/coll-of ::el-target :kind vector?))

;; -----------------------------------------------------------------------------
;; Predicates
;; -----------------------------------------------------------------------------

(defn el-target?
  "True if `t` is an element target `{:el <handle>}`."
  [t]
  (and (map? t) (contains? t :el)))

(defn query-target?
  "True if `t` is a query target `{:q <query>}`."
  [t]
  (and (map? t) (contains? t :q)))

(defn targets?
  "True if `t` is a vector of element targets (the `[*]` whole-collection shape)."
  [t]
  (vector? t))

(defn target?
  "True if `t` is any valid resolved target: a single target or a vector of them."
  [t]
  (or (query-target? t) (el-target? t) (targets? t)))

;; -----------------------------------------------------------------------------
;; Boundary validation (acceptance #6) — used by the adapters at query-all entry
;; -----------------------------------------------------------------------------

(defn check-query-all-args!
  "Validate `query-all` arguments at the IBrowser boundary. Throws a structured
   `:browser/invalid-query-all` on a malformed scope or locator. Returns nil.

   - scope must be `:document`/nil or an `{:el …}` element target.
   - locator must be a `{:q …}` query target."
  [scope locator]
  (when-not (s/valid? ::scope scope)
    (throw (ex-info (str "query-all: invalid scope " (pr-str scope))
                    {:type :browser/invalid-query-all
                     :message "scope must be :document/nil or {:el <handle>}"
                     :location "query-all"
                     :scope scope})))
  (when-not (query-target? locator)
    (throw (ex-info (str "query-all: locator must be a {:q <query>} target, got "
                         (pr-str locator))
                    {:type :browser/invalid-query-all
                     :message "locator must be {:q <query>}"
                     :location "query-all"
                     :locator locator})))
  nil)

;; -----------------------------------------------------------------------------
;; Cardinality guard
;; -----------------------------------------------------------------------------

(defn ensure-single
  "Return `t` if it is a single resolved target. If `t` is a `[*]` collection
   vector, throw a loud `:browser/target-cardinality` error — a single-target
   verb (click, fill, get-text, …) cannot act on a whole collection.

   Distributing a verb over `[*]` is an assertion-layer feature (deferred to the
   nesting phase), so we fail loudly rather than silently acting on the first.

   `location` is a short string naming the call site, used in the structured
   error (e.g. the verb/step name)."
  [t location]
  (if (targets? t)
    (throw (ex-info (str "A `[*]` reference yields a collection of " (count t)
                         " elements; this step needs a single element. "
                         "Index it (e.g. [1], [-1]) or use a collection-aware step.")
                    {:type :browser/target-cardinality
                     :message "single-target step given a [*] collection"
                     :location location
                     :count (count t)}))
    t))
