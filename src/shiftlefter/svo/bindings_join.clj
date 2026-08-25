(ns shiftlefter.svo.bindings-join
  "The verb×frame → stepdef join (sl-0gfc).

   One shared join for every surface that asks \"which step patterns
   implement this verb's frames, and which frames have none?\" — the
   builtins agent-doc generator and `sl explain` both render from it.
   Factored from builtins-gen (the original prototype of the
   missing-binding rendering) so the two can never disagree.

   Stepdef maps here are anything carrying `[:metadata :svo {:verb :frame}]`
   — both raw registry stepdefs and projection stepdef entries qualify.")

(defn steps-by-verb-frame
  "Group stepdefs by their [verb frame] SVO metadata pair."
  [stepdefs]
  (group-by (fn [{:keys [metadata]}]
              [(get-in metadata [:svo :verb])
               (get-in metadata [:svo :frame])])
            stepdefs))

(defn frame-bindings
  "For one verb's frames against one set of stepdefs (typically already
   filtered to one interface): a vector of

     {:frame kw :stepdefs [stepdef …] :missing? bool}

   one entry per frame, `:missing? true` when no stepdef implements it —
   the missing binding is data, never silently absent."
  [verb-kw frames stepdefs]
  (let [by-vf (steps-by-verb-frame stepdefs)]
    (mapv (fn [[frame-kw _frame-def]]
            (let [sds (vec (get by-vf [verb-kw frame-kw]))]
              {:frame frame-kw
               :stepdefs sds
               :missing? (empty? sds)}))
          frames)))

(defn interfaces-of-type
  "Configured interface INSTANCE names whose :type is `interface-type`
   (an entry without :type reads as an instance of the type named by its
   own key). Vocabulary is TYPE-keyed and — since sl-s2oa — so is stepdef
   :interface metadata; the instance names exist for execution-target
   selection (the [name] annotation), and per-instance views cross from
   type to instances via this fn."
  [cfg interface-type]
  (->> (:interfaces cfg)
       (keep (fn [[iface-name iface-def]]
               (when (= interface-type (or (:type iface-def) iface-name))
                 iface-name)))
       vec))

(defn stepdefs-for-interface
  "Stepdefs bound to instances of `interface-type`: those whose
   :interface metadata (a TYPE since sl-s2oa) equals it. A nil
   :interface means interface-agnostic — included. All instances of one
   type share the same stepdef set — the vocabulary is welded to the
   type, never to an instance."
  [stepdefs interface-type]
  (filterv (fn [sd]
             (let [iface (get-in sd [:metadata :interface])]
               (or (nil? iface) (= iface interface-type))))
           stepdefs))

(defn verb-interface-bindings
  "Per configured interface INSTANCE of the verb's type: the
   frame-bindings matrix.

     {:interface-type kw
      :interfaces [{:interface name :bindings [frame-bindings …]} …]}

   Every instance of the type reports the SAME stepdef bindings
   (sl-s2oa: vocabulary is type-scoped); the per-instance rows exist so
   `sl explain` surfaces the name→type chain. An empty :interfaces
   vector means no configured interface has this verb's type — reported
   as data, not silently dropped."
  [cfg interface-type verb-kw frames stepdefs]
  {:interface-type interface-type
   :interfaces (mapv (fn [iface-name]
                       {:interface iface-name
                        :bindings (frame-bindings
                                   verb-kw frames
                                   (stepdefs-for-interface stepdefs interface-type))})
                     (interfaces-of-type cfg interface-type))})
