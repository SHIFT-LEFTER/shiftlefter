(ns shiftlefter.sieve.nesting
  "Deterministic nested/collection perception over web SIEVE candidates (sl-wbn).

   This is the INTELLIGENCE the dumb sieve.js extractor defers to: it decides what
   is a collection, a nested widget, a parent-child link, and a parent-anchored
   selector — over the containment (`:node` {:index :parent-index :tag :classes
   :category}) carried on each raw candidate. Pure and deterministic: same input
   candidates -> same typed candidate vector.

   Output mirrors the live intent model (intent/loader.clj, examples/06
   product-card.edn): every node is a first-class candidate reusing :candidate/id,
   typed :element / :component / :collection / :widget, linked by :parent/:children
   id refs (flat adjacency vector, not literal nesting). A :component with a nested
   :widget :selector \".rating\" maps onto :collections {:rating {:count {:max 1}}};
   a :collection with :cardinality :many onto :collections {… :cardinality :many}.

   First cut on the deterministic fixture; robust general boundary detection is
   post-0.5. Ambiguous structure is surfaced per-node via :ambiguous, never
   flattened silently (dde AC6)."
  (:require [shiftlefter.sieve.contract :as contract]))

(defn- node [c] (:node c))
(defn- nidx [c] (:index (node c)))
(defn- pidx [c] (:parent-index (node c)))
(defn- ncat [c] (:category (node c)))
(defn- nclasses [c] (vec (:classes (node c))))
(defn- ntag [c] (:tag (node c)))
(defn- structure? [c] (= :structure (ncat c)))
(defn- signature [c] [(ntag c) (nclasses c)])

(defn- analyzable?
  "True when the snapshot carried containment (new extractor). Older snapshots
   without :index degrade to a flat element-only result."
  [candidates]
  (some #(some? (nidx %)) candidates))

(defn- strip [c kind]
  (-> c (assoc :kind kind :parent nil :children []) (dissoc :node)))

(defn- degrade
  "No containment available: every candidate is a flat :element."
  [candidates]
  (mapv #(strip % :element) candidates))

(defn- first-class
  "First CSS class of a node, or nil."
  [c]
  (first (nclasses c)))

(defn- widget-selector
  "Parent-anchored selector for a structural widget: its own class, else its id."
  [c]
  (if-let [cls (first-class c)]
    {:web {:css (str "." cls)}}
    (when-let [id (get-in c [:payload :locators :id])]
      {:web {:css (str "#" id)}})))

(defn- collection-selector
  "Parent-anchored selector locating members within their parent, mirroring
   examples/06 (\".results .s-result-item\")."
  [parent-c member-c]
  (let [pcls (when parent-c (first-class parent-c))
        mcls (first-class member-c)]
    (cond
      (and pcls mcls) {:web {:css (str "." pcls " ." mcls)}}
      mcls {:web {:css (str "." mcls)}}
      :else nil)))

(defn type-candidates
  "Type and link raw web candidates into the adjacency-vector candidate shape."
  [candidates]
  (if-not (analyzable? candidates)
    (degrade candidates)
    (let [by-index (into {} (map (juxt nidx identity)) candidates)
          ;; Children indices per parent, in document order (candidates are
          ;; emitted doc-ordered by the extractor).
          kids (reduce (fn [m c] (update m (pidx c) (fnil conj []) (nidx c))) {} candidates)
          ;; A structural wrapper that is the sole child of its parent is pure
          ;; layout (e.g. div.card-body): suppress it and re-parent its children.
          passthrough? (fn [i] (let [c (by-index i)]
                                 (and (structure? c)
                                      (= 1 (count (kids (pidx c)))))))
          passthrough-set (into #{} (filter passthrough?) (keys by-index))
          eff-parent (fn eff-parent [i]
                       (let [p (pidx (by-index i))]
                         (cond (nil? p) nil
                               (passthrough-set p) (eff-parent p)
                               :else p)))
          survivors (filterv #(not (passthrough-set (nidx %))) candidates)
          surv-idx (mapv nidx survivors)
          ;; Effective children (after collapsing passthrough wrappers).
          eff-children (reduce (fn [m i] (update m (eff-parent i) (fnil conj []) i))
                               {} surv-idx)
          non-leaf? (fn [i] (boolean (seq (get eff-children i))))
          ;; Collections: >=2 effective-siblings sharing tag+class that are
          ;; themselves non-leaf (real components, not incidental same-tag leaves
          ;; like nav links or login inputs).
          collections (vec (for [[parent child-idxs] (sort-by (comp str key)
                                                              (group-by eff-parent surv-idx))
                                 [sig members] (sort-by (comp pr-str key)
                                                        (group-by #(signature (by-index %))
                                                                  child-idxs))
                                 :when (and parent
                                            (>= (count members) 2)
                                            (every? non-leaf? members)
                                            (seq (second sig)))]
                             {:parent parent :signature sig :members (vec (sort members))}))
          collection-sigs (into #{} (map :signature) collections)
          member->coll (into {} (for [coll collections
                                      m (:members coll)]
                                  [m coll]))
          coll-id (fn [coll]
                    (contract/candidate-id
                      {:collection (mapv #(:candidate/id (by-index %)) (:members coll))}))
          ;; Final parent id for a survivor: its collection if it is a member,
          ;; else the candidate id of its effective parent.
          final-parent-id (fn [i]
                            (if-let [coll (member->coll i)]
                              (coll-id coll)
                              (when-let [ep (eff-parent i)]
                                (:candidate/id (by-index ep)))))
          kind-of (fn [i]
                    (let [c (by-index i)]
                      (cond
                        (structure? c) :widget
                        (member->coll i) :component
                        (and (non-leaf? i) (collection-sigs (signature c))) :component
                        :else :element)))
          ambiguous-of (fn [i]
                         (let [c (by-index i)]
                           (when (and (non-leaf? i)
                                      (not (member->coll i))
                                      (collection-sigs (signature c)))
                             {:reason :isolated-collection-member})))
          widget-extras (fn [c]
                          (cond-> {}
                            (widget-selector c) (assoc :selector (widget-selector c))
                            true (assoc :count {:max 1})))
          ->survivor (fn [c]
                       (let [i (nidx c)]
                         (cond-> (-> c
                                     (assoc :kind (kind-of i)
                                            :parent (final-parent-id i))
                                     (dissoc :node))
                           (= :widget (kind-of i)) (merge (widget-extras c))
                           (ambiguous-of i) (assoc :ambiguous (ambiguous-of i)))))
          collection-node (fn [coll]
                            (let [parent-c (by-index (:parent coll))
                                  member-c (by-index (first (:members coll)))
                                  sel (collection-selector parent-c member-c)]
                              (cond-> {:candidate/id (coll-id coll)
                                       :kind :collection
                                       :parent (:candidate/id parent-c)
                                       :cardinality :many
                                       :count {:observed (count (:members coll))}
                                       :payload-schema (:payload-schema member-c)}
                                sel (assoc :selector sel))))
          surv-nodes (mapv ->survivor survivors)
          coll-nodes (mapv collection-node
                           (sort-by #(first (:members %)) collections))
          ordered (into surv-nodes coll-nodes)
          ;; Children = inverse of :parent, in the order nodes appear.
          children-of (reduce (fn [m n]
                                (if (:parent n)
                                  (update m (:parent n) (fnil conj []) (:candidate/id n))
                                  m))
                              {} ordered)]
      (mapv #(assoc % :children (get children-of (:candidate/id %) []))
            ordered))))
