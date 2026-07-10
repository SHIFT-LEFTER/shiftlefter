(ns shiftlefter.runner.tag-disposition
  "Planning-time tag-disposition seam (sl-i608).

   The SINGLE evaluation point where a pickle's tags are read and a
   disposition decided. The runner never touches :pickle/tags and never
   sees a tag name — it threads opaque rules into `disposition` /
   `apply-dispositions` and reads facets off the returned map.

   The disposition is an OPEN MAP, never an enum: today `{:selected? bool}`
   (plus :reason when filtered out). Future consumers add facets additively —
   sl-q9wp's scheduling (`{:schedule {:serial-group ...}}`) and the sl-zsay
   tag-semantics registry (`{:blocked-on ...}`, `{:annotations [...]}`) —
   each pipeline stage reading its own facet from this one seam.

   MVP rules: `{:include #{tag} :exclude #{tag}}` of normalized `@`-prefixed
   strings. Include is OR (any match selects); exclude wins over include;
   an absent/empty key is unconstrained; nil rules = everything selected."
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Specs
;; -----------------------------------------------------------------------------

(s/def ::tag-name
  (s/with-gen
    (s/and string?
           #(str/starts-with? % "@")
           #(not (str/blank? (subs % 1)))
           #(not (re-find #"\s" %)))
    #(gen/fmap (fn [s] (str "@" s))
               (gen/not-empty (gen/string-alphanumeric)))))

(s/def ::include (s/coll-of ::tag-name :kind set?))
(s/def ::exclude (s/coll-of ::tag-name :kind set?))
(s/def ::rules (s/nilable (s/keys :opt-un [::include ::exclude])))

(s/def ::selected? boolean?)
(s/def ::reason (s/nilable map?))
;; The scheduling facet (sl-q9wp): {:serial? true :reason :tag|:costume|
;; :shared-impl}. This seam only ever produces :reason :tag (the @serial
;; tag); the auto-serial gates in runner/schedule.clj produce the same
;; facet shape from plan inspection — the scheduler consumes one facet
;; and never knows why.
(s/def :shiftlefter.schedule/serial? boolean?)
(s/def :shiftlefter.schedule/reason #{:tag :costume :shared-impl})
(s/def ::schedule (s/keys :req-un [:shiftlefter.schedule/serial?
                                   :shiftlefter.schedule/reason]))
;; Open map by design (addendum 2b): future facets (:blocked-on,
;; :annotations) are additive keys — never a disposition-type enum.
;; :schedule landed with sl-q9wp.
(s/def ::disposition (s/keys :req-un [::selected?] :opt-un [::reason ::schedule]))

;; -----------------------------------------------------------------------------
;; CLI-boundary helpers (liberal: accept tags with or without `@`)
;; -----------------------------------------------------------------------------

(defn normalize-tag
  "Trim `s` and prepend `@` if absent. `--tags smoke` == `--tags @smoke`."
  [s]
  (let [t (str/trim s)]
    (if (str/starts-with? t "@") t (str "@" t))))

(defn parse-tag-list
  "Comma-separated tag list -> set of normalized tag names (CLI :parse-fn)."
  [s]
  (into #{}
        (comp (map str/trim) (remove str/blank?) (map normalize-tag))
        (str/split s #",")))

(defn valid-tag-set?
  "Non-empty set of valid tag names (CLI :validate predicate)."
  [tags]
  (boolean (and (set? tags) (seq tags) (every? #(s/valid? ::tag-name %) tags))))

;; -----------------------------------------------------------------------------
;; execute!-boundary validation (strict: rules must already be normalized)
;; -----------------------------------------------------------------------------

(defn rules-error
  "nil when `rules` is a valid ::rules value, else a structured error map."
  [rules]
  (when-not (s/valid? ::rules rules)
    {:type :tag-filter/invalid
     :message (str "Invalid :tag-filter " (s/explain-str ::rules rules))}))

;; -----------------------------------------------------------------------------
;; The seam
;; -----------------------------------------------------------------------------

(defn- pickle-tag-names
  "The ONLY place :pickle/tags is read for selection. Inheritance
   (feature -> rule -> scenario -> examples-block) is already flattened
   into the pickle by the pickler."
  [pickle]
  (into #{} (map :name) (:pickle/tags pickle)))

(def serial-tag
  "The scheduling escape-hatch tag (sl-q9wp): a tagged scenario is
   individually EXCLUSIVE — no other scenario may run alongside it. At the
   feature level it marks each scenario individually, NOT an atomic block."
  "@serial")

(defn disposition
  "THE tag-disposition seam: read one pickle's tags, decide its disposition.
   Returns an open map — `{:selected? true}` or
   `{:selected? false :reason {:rule ...}}`. Exclude wins over include;
   include is OR; empty/absent key = unconstrained; nil rules = selected.

   A pickle tagged @serial additionally carries the scheduling facet
   `{:schedule {:serial? true :reason :tag}}` (sl-q9wp), independent of
   filter rules."
  [rules pickle]
  (let [names (pickle-tag-names pickle)
        {:keys [include exclude]} rules
        excluded (when (seq exclude) (not-empty (set/intersection names exclude)))
        base (cond
               excluded
               {:selected? false :reason {:rule :exclude :matched excluded}}

               (and (seq include) (empty? (set/intersection names include)))
               {:selected? false :reason {:rule :include :wanted include}}

               :else {:selected? true})]
    (cond-> base
      (contains? names serial-tag)
      (assoc :schedule {:serial? true :reason :tag}))))

(s/fdef disposition
  :args (s/cat :rules ::rules :pickle map?)
  :ret ::disposition)

(s/def ::selected vector?)
(s/def ::filtered-out vector?)

(defn apply-dispositions
  "Evaluate the seam once per pickle; partition on the :selected? facet.
   Returns {:selected [pickles...] :filtered-out [pickles...]}, order
   preserved, both keys always present."
  [rules pickles]
  (let [{sel true filt false}
        (group-by #(:selected? (disposition rules %)) pickles)]
    {:selected (vec sel) :filtered-out (vec filt)}))

(s/fdef apply-dispositions
  :args (s/cat :rules ::rules :pickles (s/coll-of map?))
  :ret (s/keys :req-un [::selected ::filtered-out]))
