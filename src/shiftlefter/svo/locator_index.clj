(ns shiftlefter.svo.locator-index
  "The locator→intents reverse multimap (sl-mpu5, sl-0gfc addendum 4).

   Folds the loaded intents structure — element :bindings, region :roots,
   collection :selectors — into an index keyed by [interface locator-EDN].
   Equality is STRUCTURAL equality of the EDN locator value (Clojure map
   equality: formatting/key-order immune, free). Semantic aliasing (two
   XPaths, one element) is explicitly out of scope — DOM-oracle territory,
   sieve-era, never built here.

   Two consumers: explain-by-locator (`sl explain '{:id \"x\"}'` — the
   debugging + authoring lookup, and the sieve-flow proposal-time check),
   and the ON-DEMAND duplicates report (`sl glossary duplicates` — never
   always-on: same-locator-different-page is the NORM, default-warn would
   be a fatigue machine). Confidence tiers from structure: same-intent
   collisions (Login.foo vs Login.bar) are high-confidence real; cross-
   intent = likely page-scoped coincidence."
  (:require [clojure.string :as str]))

(defn- element-occurrences [lookup]
  (for [[[intent element] entry] lookup
        [iface loc] (:bindings entry)
        :when loc]
    {:interface iface :locator loc :role :element
     :intent intent :address (str intent "." element)}))

(defn- region-occurrences [regions]
  (for [[intent region] regions
        :let [root (:root region)]
        :when (map? root)
        [iface loc] root
        :when loc]
    {:interface iface :locator loc :role :root
     :intent intent :address intent}))

(defn- collection-occurrences [regions]
  (for [[intent region] regions
        [coll-name coll-def] (:collections region)
        :let [selector (:selector coll-def)]
        :when (map? selector)
        [iface loc] selector
        :when loc]
    {:interface iface :locator loc :role :selector
     :intent intent :address (str intent "." (name coll-name))}))

(defn occurrences
  "Every locator occurrence in the loaded intents structure, flat.
   `intents` is the loader's :ok map ({:lookup :regions :intent-files ...})."
  [intents]
  (vec (concat (element-occurrences (:lookup intents))
               (region-occurrences (:regions intents))
               (collection-occurrences (:regions intents)))))

(defn build
  "The reverse multimap: [interface locator-EDN] → [occurrence ...].
   Scoped per interface — the same value under :web and :mobile addresses
   different documents and is not a collision."
  [intents]
  (group-by (juxt :interface :locator) (occurrences intents)))

(defn lookup-locator
  "Owning occurrences of one locator EDN value, across all interfaces.
   Structural equality — the caller's parsed EDN, not its formatting."
  [index locator-edn]
  (vec (for [[[iface loc] occs] index
             :when (= loc locator-edn)
             occ occs]
         (assoc occ :interface iface))))

(defn duplicates
  "Collision groups: one locator value owned by 2+ addresses within one
   interface. Each group carries its confidence tier:
   :high — all owners share one intent (same-namespace, Login.foo vs
   Login.bar — high-confidence real duplicate); :cross-intent — owners
   span intents (likely page-scoped coincidence, listed second).
   `intent-files` (loader :intent-files) adds def-site files per owner."
  [intents]
  (let [files (:intent-files intents)]
    (->> (build intents)
         (keep (fn [[[iface loc] occs]]
                 (when (> (count occs) 1)
                   {:interface iface
                    :locator loc
                    :tier (if (= 1 (count (distinct (map :intent occs))))
                            :high
                            :cross-intent)
                    :owners (mapv (fn [o]
                                    (cond-> (select-keys o [:address :role])
                                      (get files (:intent o))
                                      (assoc :file (get files (:intent o)))))
                                  occs)})))
         (sort-by (juxt #(if (= :high (:tier %)) 0 1)
                        #(str/join "," (map :address (:owners %)))))
         vec)))
