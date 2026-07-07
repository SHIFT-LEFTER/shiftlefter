(ns shiftlefter.browser.intent
  "Browser-aware resolution of an intent reference to a concrete IBrowser target.

   This is the seam where intent addressing meets the live DOM — and the ONLY
   layer that understands nesting and indexing (design §1). A reference is walked
   segment by segment, scoping each hop within the previous element handle:

   - **Flat, non-indexed** (`Login.submit`, document scope) → a pure query target
     `{:q <binding>}`, no browser call — exactly the sl-nrv behavior.
   - **Indexed** (`Login.submit[2]`, `[-1]`, `[*]`) → resolved against the live
     page: `IBrowser/query-all` returns ALL matches, then `apply-index` takes the
     Nth — which is also the fix for the flat `:nth-child` bug.
   - **Nested** (`Bookmarks.tweet[2].quoted.author`) → a recursive scoped walk
     (design §8): resolve the top intent's root scope, find each collection's
     instances within the current scope (by the parent's `:selector`, else the
     referenced component's `:root`), pick the indexed/sole instance, descend, and
     finally find the element within the innermost instance.

   Returns one of:
   - `{:q <query>}`        — flat, non-indexed, document-scoped (pure path)
   - `{:el <handle>}`      — a single located element
   - `[{:el …} …]`         — a `[*]` whole-collection fan-out (document order)
   - `{:errors [ … ]}`     — parse failure, unknown segment, missing anchor, a
                             HARD index error (`[0]`/out-of-range), an ambiguous or
                             empty collection, etc. Always loud, never a silent nil.

   Point-in-time: resolves against the DOM as it is NOW. Staleness across
   re-renders / virtualized scroll is the consumer's problem (design §8), never
   this resolver's.

   Pure-resolution callers that only need existence (e.g. SVO validation) keep
   using `shiftlefter.intent.resolve` directly; this namespace is for the browser
   step path that must act on the actual element."
  (:require [clojure.string :as str]
            [shiftlefter.intent.resolve :as ir]
            [shiftlefter.intent.loader :as loader]
            [shiftlefter.browser.protocol :as bp]
            [shiftlefter.browser.locators :as locators]))

(defn- error
  [type message data]
  {:errors [{:type type :message message :data data}]})

(defn- errors?
  "True if `x` is an `{:errors […]}` failure value."
  [x]
  (and (map? x) (contains? x :errors)))

(defn- apply-index
  "Pick the target(s) named by `index` from the vector of `{:el}` `matches`.
   1-based positive, negative-from-end, `:all` → whole vector. Out-of-range and
   `[0]` are HARD errors (never a silent nil), per design §5."
  [matches index ref-str]
  (let [n (count matches)]
    (cond
      (= :all index) matches

      (zero? index)
      (error :intent/zero-index
             (str ref-str ": index [0] is invalid — intent indices are 1-based")
             {:ref ref-str :index 0})

      :else
      (let [i (if (pos? index) (dec index) (+ n index))]
        (if (and (>= i 0) (< i n))
          (nth matches i)
          (error :intent/index-out-of-range
                 (format "%s: index %d is out of range — %d match%s found"
                         ref-str index n (if (= 1 n) "" "es"))
                 {:ref ref-str :index index :count n}))))))

;; -----------------------------------------------------------------------------
;; Locator helpers
;; -----------------------------------------------------------------------------

(defn- located-root
  "The per-interface locator map of `root` if it concretely self-locates (not
   the :unrooted sentinel, not nil); else nil."
  [root]
  (when (and (map? root) (seq root)) root))

(defn- anchor-query
  "Resolve the query that locates a collection's instances for `interface`:
   the collection's own `:selector` if present, else the referenced component's
   `:root`. Returns a `{:q …}` target or an `{:errors …}` failure."
  [intents coll interface ref-str coll-name]
  (let [selector (get-in coll [:selector interface])
        comp-root (located-root (loader/get-root intents (:intent coll)))
        locator (or selector (get comp-root interface))]
    (if (nil? locator)
      (error :intent/no-anchor-for-interface
             (str ref-str ": collection '" coll-name "' has no :selector and its "
                  "component '" (:intent coll) "' has no :root binding for "
                  (name interface))
             {:ref ref-str :collection coll-name :interface interface})
      (locators/resolve-locator locator))))

;; -----------------------------------------------------------------------------
;; Collection hop — pick the indexed / sole instance within `scope`
;; -----------------------------------------------------------------------------

(defn- sole-instance
  "A no-index collection hop yields exactly one instance. Empty → loud error;
   `:cardinality :many` with >1 → ambiguous error; otherwise the first."
  [instances coll ref-str coll-name]
  (let [n (count instances)]
    (cond
      (zero? n)
      (error :intent/empty-collection
             (str ref-str ": collection '" coll-name "' matched no instances")
             {:ref ref-str :collection coll-name})

      (and (= :many (get coll :cardinality :many)) (> n 1))
      (error :intent/ambiguous-collection
             (str ref-str ": collection '" coll-name "' matched " n
                  " instances but no index was given — add [n] or [*]")
             {:ref ref-str :collection coll-name :count n})

      :else (first instances))))

(defn- collection-instances
  "Find a collection's instances within `scope` for `interface`, excluding any
   match inside a nested instance boundary (§8.1, via `boundary-css` — the
   union of the PARENT component's declared collection selectors). Returns the
   vector of `{:el}` matches, or an `{:errors …}` failure if the anchor can't be
   resolved.

   The anchor here is always a boundary member (it is one of the parent's
   collections), so it is CSS-validated at load — `query-all-pruned` never
   fails its candidate-CSS check at this site."
  [browser intents coll interface scope ref-str coll-name boundary-css]
  (let [anchor (anchor-query intents coll interface ref-str coll-name)]
    (cond
      (errors? anchor) anchor
      (locators/errors anchor) {:errors (locators/errors anchor)}
      :else (bp/query-all-pruned browser scope anchor boundary-css))))

;; -----------------------------------------------------------------------------
;; Element hop — the terminal element within `scope`
;; -----------------------------------------------------------------------------

(defn- boundary-css-error
  "When pruning is active (`boundary-css` non-blank), the element binding drives
   `querySelectorAll` and so must be CSS-expressible. Returns an `{:errors …}`
   failure if it isn't, else nil. (Boundaries themselves are load-validated; an
   element binding is not, so this is the user-facing guard.)"
  [resolved boundary-css ref-str name cur]
  (when-not (str/blank? boundary-css)
    (let [r (locators/locator->css (:q resolved))]
      (when (:error r)
        (error :intent/element-binding-not-css
               (str ref-str ": element '" name "' in intent '" cur "' must use a "
                    "CSS :web binding because '" cur "' declares nested collections "
                    "(nearest-enclosing-instance pruning needs CSS)")
               {:ref ref-str :element name :intent cur
                :binding (:q resolved)})))))

(defn- element-target
  "Resolve the terminal element `name` of intent `cur` within `scope` for
   `interface`, honoring `index`, excluding matches inside nested instance
   boundaries (§8.1, via `boundary-css` — the union of `cur`'s declared
   collection selectors).

   Backward-compat (sl-nrv): document scope + no index AND no active boundary →
   a pure `{:q binding}` with NO browser call. A non-blank boundary forces the
   query path even at document scope, so a polluting nested match is pruned
   rather than silently returned as the first hit. Otherwise find all matches
   within `scope` and take the Nth (`index`) or the sole match (no index;
   empty/ambiguous are loud errors)."
  [browser intents cur name index scope interface ref-str boundary-css]
  (let [binding-result (loader/get-binding intents cur name interface)]
    (if (:error binding-result)
      {:errors [(:error binding-result)]}
      (let [resolved (locators/resolve-locator (:ok binding-result))
            css-err  (when-not (locators/errors resolved)
                       (boundary-css-error resolved boundary-css ref-str name cur))]
        (cond
          (locators/errors resolved) {:errors (locators/errors resolved)}

          ;; Element binding must be CSS when pruning is active.
          css-err css-err

          ;; Pure flat path: whole-document, non-indexed, AND nothing to prune.
          (and (= :document scope) (nil? index) (str/blank? boundary-css)) resolved

          :else
          (let [matches (bp/query-all-pruned browser scope resolved boundary-css)]
            (if (nil? index)
              (let [n (count matches)]
                (cond
                  (zero? n)
                  (error :intent/element-not-found
                         (str ref-str ": element '" name "' matched no elements")
                         {:ref ref-str :element name})
                  (> n 1)
                  (error :intent/ambiguous-element
                         (str ref-str ": element '" name "' matched " n
                              " elements but no index was given — add [n]")
                         {:ref ref-str :element name :count n})
                  :else (first matches)))
              (apply-index matches index ref-str))))))))

;; -----------------------------------------------------------------------------
;; The walk
;; -----------------------------------------------------------------------------

(declare walk)

(defn- fan-out
  "Map the remaining `segs` over each instance of a `[*]` collection hop, threading
   the referenced component as the current intent. Single-[*] guarantees no nested
   fan-out, so each sub-walk yields a single `{:el}` (or an error). Returns the
   vector of resolved targets, or the first error encountered."
  [browser intents segs component instances interface ref-str]
  (let [results (mapv #(walk browser intents segs component % interface ref-str)
                      instances)]
    (or (first (filter errors? results)) results)))

(defn- walk
  "Resolve `segs` (remaining path segments) of intent `cur`, scoped within
   `scope` (a single `:document` or `{:el}`), for `interface`. Returns a resolved
   target, a `[*]` vector, or an `{:errors …}` failure. `ref-str` is the original
   address, for error messages."
  [browser intents segs cur scope interface ref-str]
  (let [{nm :name index :index} (first segs)
        rest-segs (next segs)
        terminal? (nil? rest-segs)
        coll (loader/get-collection intents cur nm)
        ;; §8.1: the boundary set for resolving WITHIN an instance of `cur` is
        ;; the union of `cur`'s declared collection selectors. Used at both the
        ;; collection-instances and element sites below.
        boundary-css (loader/get-boundary-css intents cur interface)]
    (cond
      ;; ---- Collection hop -------------------------------------------------
      coll
      (let [instances (collection-instances browser intents coll interface
                                            scope ref-str nm boundary-css)]
        (cond
          (errors? instances) instances

          ;; [*] — the whole collection. Terminal → the instance vector itself;
          ;; otherwise fan the remaining hops out over each instance.
          (= :all index)
          (if terminal?
            instances
            (fan-out browser intents rest-segs (:intent coll) instances
                     interface ref-str))

          :else
          (let [chosen (if index
                         (apply-index instances index ref-str)
                         (sole-instance instances coll ref-str nm))]
            (cond
              (errors? chosen) chosen
              terminal? chosen
              :else (walk browser intents rest-segs (:intent coll) chosen
                          interface ref-str)))))

      ;; ---- Element hop (terminal) ----------------------------------------
      (loader/known-element? intents cur nm)
      (if terminal?
        (element-target browser intents cur nm index scope interface ref-str boundary-css)
        (error :intent/non-terminal-element
               (str ref-str ": '" nm "' is an element in intent '" cur
                    "' and cannot have children")
               {:ref ref-str :segment nm :intent cur}))

      ;; ---- Unknown segment ------------------------------------------------
      :else
      (error :intent/unknown-segment
             (str ref-str ": unknown segment '" nm "' in intent '" cur "'")
             {:ref ref-str :segment nm :intent cur}))))

(defn- root-scope
  "The starting scope for `intent`: if it declares a concrete :root, the first
   element matching that root in the document; else `:document`. Returns a scope
   (`:document` or `{:el}`) or an `{:errors …}` failure (root matched nothing)."
  [browser intents intent interface ref-str]
  (let [root (located-root (loader/get-root intents intent))
        locator (get root interface)]
    (if (nil? locator)
      :document
      (let [resolved (locators/resolve-locator locator)]
        (if (locators/errors resolved)
          {:errors (locators/errors resolved)}
          (let [matches (bp/query-all browser :document resolved)]
            (if (seq matches)
              (first matches)
              (error :intent/root-not-found
                     (str ref-str ": intent '" intent "' root matched no element")
                     {:ref ref-str :intent intent}))))))))

(defn resolve-target
  "Resolve an intent reference string to a concrete IBrowser target using the
   live `browser`. See the namespace docstring for the full return contract.

   `intents` is the loaded map from `loader/load-all-intents` — a `:regions`-less
   map (flat-only) degrades to flat behavior, so legacy callers are unaffected."
  [browser intents ref-str interface]
  (let [parsed (ir/parse-intent-ref ref-str)]
    (if (:error parsed)
      {:errors [(:error parsed)]}
      (let [{:keys [intent path]} (:ok parsed)
            scope (root-scope browser intents intent interface ref-str)]
        (if (errors? scope)
          scope
          (walk browser intents path intent scope interface ref-str))))))
