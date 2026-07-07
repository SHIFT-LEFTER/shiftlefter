(ns shiftlefter.sieve.reconcile
  "Deterministic two-observation reconciliation — the SIEVE semantic diff (sl-bun).

   Pure Clojure over two Analysis Results' typed candidate trees (sl-wbn shape):
   same two inputs -> same reconciled claim set. NOT a port of LeftGlove's
   diff.js; the browser shell stays interaction-only.

   ## Cross-observation matching (decisions/sieve.md, 2026-06-19)

   Raw `:candidate/id` is PER-OBSERVATION identity (web.clj seeds it off the
   evidence id + element index + element content), so it is NOT stable across
   observations. Cross-observation correspondence uses a derived, membership-
   independent key:

   - anchored nodes (a stable testid/id)  -> `[:anchor <anchor>]`
   - synthesized nodes (collection/widget, no anchor)
                                          -> `[:struct <parent-corr> <kind> <selector>]`
   - anchorless leaves                    -> `[:pos <parent-corr> <kind> <ordinal>]`

   A node's correspondence key is membership-independent, so a collection keeps
   its identity as members come and go — a membership change shows up as a
   `:changed` structural delta on `:children-corrs`, not as disappeared+new.

   ## Diff buckets

   `:retained` / `:new` / `:disappeared` / `:changed`. A `:changed` claim carries
   `:deltas`, each tagged `:leaf` (a `:label` text change) or `:structural`
   (`:kind` / `:selector` / `:parent-corr` / `:children-corrs` / `:cardinality` /
   `:count`). A single structural event can yield two correlated claims (e.g. a
   member `:disappeared` AND its collection `:changed` via a shrunk
   `:children-corrs`) — both are kept, never deduped.

   wbn's per-node `:ambiguous` flag is carried through, never flattened (dde AC6).
   Limitations: positional matching for anchorless leaves is order-fragile, and
   content-level reuse (a widget reappearing under a new anchor) reads as `:new` —
   general entity resolution is post-0.5 (sl-bun AC8)."
  (:require [clojure.set :as set]))

(def ^:private synthesized? #{:collection :widget})

;; Fields whose change is a leaf (content) delta; everything else compared is
;; structural.
(def ^:private leaf-field? #{:label})

(def ^:private compared-fields
  [:kind :label :selector :cardinality :count :parent-corr :children-corrs])

(defn- anchor
  "Stable cross-observation anchor for a candidate: its testid, else its id."
  [c]
  (or (get-in c [:payload :locators :testid])
      (get-in c [:payload :locators :id])))

(defn- by-id-index [analysis]
  (into {} (map (juxt :candidate/id identity)) (:candidates analysis)))

(defn- ordinals
  "Map candidate-id -> ordinal among same (:parent, :kind) siblings, in document
   order. Anchorless leaves use this for positional correspondence."
  [analysis]
  (into {}
        (mapcat (fn [[_ sibs]]
                  (map-indexed (fn [i c] [(:candidate/id c) i]) sibs)))
        (group-by (juxt :parent :kind) (:candidates analysis))))

(defn- corr-key
  "Derived, membership-independent correspondence key for one candidate id."
  [idx ords id]
  (let [c (get idx id)
        parent-key (if-let [p (:parent c)] (corr-key idx ords p) :root)]
    (cond
      (anchor c) [:anchor (anchor c)]
      (synthesized? (:kind c)) [:struct parent-key (:kind c) (:selector c)]
      :else [:pos parent-key (:kind c) (get ords id)])))

(defn- corr-keys
  "Map every candidate id in an analysis to its correspondence key."
  [analysis]
  (let [idx (by-id-index analysis)
        ords (ordinals analysis)]
    (into {}
          (map (fn [c] [(:candidate/id c) (corr-key idx ords (:candidate/id c))]))
          (:candidates analysis))))

(defn- comparable
  "The view of a candidate that counts for change detection. Volatile fields
   (rect, raw :candidate/id, evidence id) are deliberately excluded; structural
   refs are compared by correspondence key, not per-observation id."
  [ck c]
  {:kind (:kind c)
   :label (:label c)
   :selector (:selector c)
   :cardinality (:cardinality c)
   :count (:count c)
   :parent-corr (when (:parent c) (ck (:parent c)))
   :children-corrs (mapv ck (:children c))})

(defn- deltas
  "Field-level changes between two comparable views, each tagged leaf/structural."
  [av bv]
  (vec (for [f compared-fields
             :when (not= (get av f) (get bv f))]
         {:field f :from (get av f) :to (get bv f)
          :delta-kind (if (leaf-field? f) :leaf :structural)})))

(defn- claim
  "A single-sided diff claim (retained / new / disappeared)."
  [ck diff c]
  (cond-> {:diff diff
           :corr-key (ck (:candidate/id c))
           :candidate/id (:candidate/id c)
           :kind (:kind c)
           :anchor (anchor c)}
    (:ambiguous c) (assoc :ambiguous (:ambiguous c))))

(defn- changed-claim
  "A :changed claim spanning both observations, carrying categorized deltas."
  [ck-b ca cb ds]
  (cond-> {:diff :changed
           :corr-key (ck-b (:candidate/id cb))
           :candidate/id (:candidate/id cb)
           :prior-candidate/id (:candidate/id ca)
           :kind (:kind cb)
           :anchor (anchor cb)
           :deltas ds}
    (:ambiguous cb) (assoc :ambiguous (:ambiguous cb))))

(defn- reconcile-common
  "Reconcile the candidates whose correspondence key is present in both: each is
   :retained (identical comparable view) or :changed (with deltas)."
  [ck-a ck-b a-by-key b-by-key common]
  (for [k common
        :let [ca (a-by-key k) cb (b-by-key k)
              ds (deltas (comparable ck-a ca) (comparable ck-b cb))]]
    (if (seq ds)
      (changed-claim ck-b ca cb ds)
      (claim ck-b :retained cb))))

(defn- sort-claims [claims]
  (vec (sort-by (comp pr-str :corr-key) claims)))

(defn reconcile
  "Reconcile two Analysis Results into a deterministic diff:
   {:retained [..] :new [..] :disappeared [..] :changed [..]}.

   Matching is by derived correspondence key (see ns docstring). Same two inputs
   -> same output; each bucket is sorted by correspondence key."
  [analysis-a analysis-b]
  (let [ck-a (corr-keys analysis-a)
        ck-b (corr-keys analysis-b)
        a-by-key (into {} (map (fn [c] [(ck-a (:candidate/id c)) c]))
                       (:candidates analysis-a))
        b-by-key (into {} (map (fn [c] [(ck-b (:candidate/id c)) c]))
                       (:candidates analysis-b))
        a-keys (set (keys a-by-key))
        b-keys (set (keys b-by-key))
        by-diff (group-by :diff
                          (reconcile-common ck-a ck-b a-by-key b-by-key
                                            (set/intersection a-keys b-keys)))]
    {:retained (sort-claims (:retained by-diff))
     :changed (sort-claims (:changed by-diff))
     :disappeared (sort-claims (map #(claim ck-a :disappeared (a-by-key %))
                                    (set/difference a-keys b-keys)))
     :new (sort-claims (map #(claim ck-b :new (b-by-key %))
                            (set/difference b-keys a-keys)))}))
