(ns shiftlefter.browser.locators
  "Locator resolution for browser operations.

   All browser stepdefs must call `resolve-locator` before invoking
   browser protocol operations. This provides a seam for future
   PageObject/Object-Glossary dialect support.

   ## Locator Token Forms (0.2.x, Etaoin passthrough)

   Explicit (preferred):
   - `{:css \"...\"}`, `{:xpath \"...\"}`, `{:id \"...\"}`
   - `{:tag \"div\"}`, `{:class \"btn\"}`, `{:name \"email\"}`
   - `{:testid \"btn-submit\"}` — expands to `{:css \"[data-testid='btn-submit']\"}`

   Vector shorthand (normalized to map):
   - `[:css \"...\"]`, `[:xpath \"...\"]`, etc.

   Loose passthrough (backend-dependent):
   - string — passed through as-is
   - keyword — passed through as-is

   ## Return Value

   Success: `{:q <etaoin-query>}`
   Error: `{:errors [{:type :browser/selector-invalid :data {:token ...}}]}`")

;; -----------------------------------------------------------------------------
;; Supported selector types
;; -----------------------------------------------------------------------------

(def ^:private valid-selector-types
  "Set of valid selector type keywords for explicit locators."
  #{:css :xpath :id :tag :class :name :testid})

;; -----------------------------------------------------------------------------
;; Resolution
;; -----------------------------------------------------------------------------

(defn- expand-testid
  "Expand {:testid \"foo\"} → {:css \"[data-testid='foo']\"}.
   Returns the map unchanged if no :testid key."
  [m]
  (if-let [testid (:testid m)]
    {:css (str "[data-testid='" testid "']")}
    m))

(defn- resolve-map
  "Resolve a map-form locator. Must have exactly one valid selector key.
   Expands :testid to :css before resolution."
  [m]
  (let [m (expand-testid m)
        selector-keys (filter valid-selector-types (keys m))]
    (case (count selector-keys)
      0 {:errors [{:type :browser/selector-invalid
                   :message "Map locator has no valid selector key"
                   :data {:token m
                          :valid-keys valid-selector-types}}]}
      1 {:q m}
      ;; 2+ selector keys is ambiguous
      {:errors [{:type :browser/selector-invalid
                 :message "Map locator has multiple selector keys"
                 :data {:token m
                        :found-keys (set selector-keys)}}]})))

(defn- resolve-vector
  "Resolve a vector-form locator like [:css \"...\"]."
  [v]
  (if (and (= 2 (count v))
           (keyword? (first v))
           (string? (second v)))
    (let [[selector-type selector-value] v]
      (if (valid-selector-types selector-type)
        {:q {selector-type selector-value}}
        {:errors [{:type :browser/selector-invalid
                   :message (str "Unknown selector type: " selector-type)
                   :data {:token v
                          :selector-type selector-type
                          :valid-types valid-selector-types}}]}))
    {:errors [{:type :browser/selector-invalid
               :message "Vector locator must be [keyword string]"
               :data {:token v}}]}))

(defn resolve-locator
  "Resolve a locator token to the standard form `{:q <etaoin-query>}`.

   Accepts:
   - Map with selector key: `{:css \"#login\"}` → `{:q {:css \"#login\"}}`
   - Vector shorthand: `[:css \"#login\"]` → `{:q {:css \"#login\"}}`
   - String: `\"#login\"` → `{:q \"#login\"}` (passthrough)
   - Keyword: `:some-id` → `{:q :some-id}` (passthrough)

   Returns:
   - Success: `{:q <etaoin-query>}`
   - Error: `{:errors [{:type :browser/selector-invalid ...}]}`"
  [token]
  (cond
    (map? token)     (resolve-map token)
    (vector? token)  (resolve-vector token)
    (string? token)  {:q token}
    (keyword? token) {:q token}
    :else            {:errors [{:type :browser/selector-invalid
                                :message "Unsupported locator token type"
                                :data {:token token
                                       :type (type token)}}]}))

(defn locator->css
  "Convert a single locator map to a plain CSS selector string.

   Used by nearest-enclosing-instance pruning (§8.1, sl-h7h): the boundary
   filter is `Element.closest(<css>)`, so every boundary selector — and the
   candidate selector when pruning is active — must be expressible as CSS.

   Accepts the inner locator map (NOT the `{:q …}` wrapper): `{:css …}`,
   `{:id …}`, `{:tag …}`, `{:class …}`, `{:name …}`, `{:testid …}`.

   Returns:
   - `{:ok \"<css>\"}`            — convertible
   - `{:error {:type :browser/locator-not-css …}}` — `:xpath`, a bare
     string/keyword, or any non-CSS form (caller fails loud, never silently
     mis-prunes)."
  [m]
  (if-not (map? m)
    {:error {:type :browser/locator-not-css
             :message (str "Locator is not a CSS-expressible map: " (pr-str m))
             :data {:token m}}}
    (let [m (expand-testid m)]   ;; :testid → {:css …} before dispatch
      (cond
        (:css m)   {:ok (:css m)}
        (:id m)    {:ok (str "#" (:id m))}
        (:tag m)   {:ok (:tag m)}
        (:class m) {:ok (str "." (:class m))}
        (:name m)  {:ok (str "[name='" (:name m) "']")}
        :else
        {:error {:type :browser/locator-not-css
                 :message (str "Locator cannot be expressed as CSS (needed for "
                               "boundary pruning): " (pr-str m))
                 :data {:token m}}}))))

(defn valid?
  "Returns true if the resolved locator is valid (has :q, no :errors)."
  [resolved]
  (and (contains? resolved :q)
       (not (contains? resolved :errors))))

(defn errors
  "Returns the errors from a resolved locator, or nil if valid."
  [resolved]
  (:errors resolved))
