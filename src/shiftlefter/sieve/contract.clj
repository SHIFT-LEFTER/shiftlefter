(ns shiftlefter.sieve.contract
  "Public internal SIEVE evidence, analysis, interpretation, and proposal shapes.

   Live capture is best-effort and may depend on mutable interface state.
   Evidence snapshots are immutable captured inputs. Analysis results are
   deterministic for the same evidence fingerprint, projection fingerprint,
   provider identity/version, and provider config fingerprint."
  (:require [clojure.spec.alpha :as s])
  (:import [java.security MessageDigest]
           [java.time Instant]
           [java.util UUID]))

(def evidence-snapshot-version 1)
(def analysis-result-version 1)
(def interpretation-version 1)
(def proposal-version 1)

(defn now-iso
  "Return the current wall-clock time as an ISO-8601 string."
  []
  (str (Instant/now)))

(defn new-id
  "Return an opaque stable-looking ID with the given prefix."
  [prefix]
  (str prefix "-" (UUID/randomUUID)))

(defn- sha256-hex [bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest digest bytes)]
    (apply str (map #(format "%02x" %) hash-bytes))))

(defn- canonical-key-compare [a b]
  (compare (pr-str a) (pr-str b)))

(defn canonical-form
  "Return a recursively sorted representation suitable for stable fingerprints."
  [value]
  (cond
    (map? value)
    (into (sorted-map-by canonical-key-compare)
          (map (fn [[k v]] [k (canonical-form v)]))
          value)

    (vector? value)
    (mapv canonical-form value)

    (sequential? value)
    (mapv canonical-form value)

    (set? value)
    (mapv canonical-form (sort-by pr-str value))

    :else value))

(defn fingerprint
  "Return a SHA-256 hex fingerprint for an arbitrary EDN value."
  [value]
  (sha256-hex (.getBytes (pr-str (canonical-form value)) "UTF-8")))

(defn provider-config-fingerprint
  "Return a stable fingerprint for provider config."
  [config]
  (fingerprint (or config {})))

(defn candidate-id
  "Return a deterministic candidate ID from a seed value. Used for both
   element candidates (seeded by evidence + element) and synthesized
   structural candidates (collection/widget, seeded by members)."
  [seed]
  (str "cand-" (subs (fingerprint seed) 0 16)))

(defn projection-ref
  "Return the accepted project projection reference carried by SIEVE artifacts."
  [projection]
  {:projection/id (:projection/id projection)
   :projection/version (:projection/version projection)
   :fingerprint (:fingerprint projection)
   :source (:source projection)
   :status (:status projection)})

(defn provider-ref
  "Return normalized provider identity for analysis results."
  [{:keys [id version config config-fingerprint]}]
  {:id id
   :version version
   :config-fingerprint (or config-fingerprint (provider-config-fingerprint config))})

(defn evidence-ref
  "Return the stable cross-reference for an evidence snapshot."
  [snapshot]
  {:id (:evidence/id snapshot)
   :fingerprint (:content/fingerprint snapshot)
   :schema-version (:schema/version snapshot)})

(defn analysis-ref
  "Return the stable cross-reference for an analysis result."
  [analysis]
  {:id (:analysis/id analysis)
   :fingerprint (:content/fingerprint analysis)
   :schema-version (:schema/version analysis)})

(defn evidence-content
  "Return the immutable content covered by the evidence fingerprint."
  [snapshot]
  (select-keys snapshot
               [:schema/version
                :interface
                :source
                :capture
                :environment
                :project
                :payload-schema
                :payload
                :warnings]))

(defn with-evidence-identity
  "Attach content fingerprint and ID to an evidence snapshot.

   Existing IDs are preserved. Missing IDs are content-derived so promoted
   fixtures remain stable across loads."
  [snapshot]
  (let [content-fp (fingerprint (evidence-content snapshot))]
    (assoc snapshot
           :content/fingerprint content-fp
           :evidence/id (or (:evidence/id snapshot)
                            (str "ev-" (subs content-fp 0 16))))))

(defn make-evidence-snapshot
  "Build an Evidence Snapshot map.

   The payload is provider-specific and identified by :payload-schema. Web DOM,
   screenshot, geometry, and locator data belong in that payload, not in the
   interface-neutral top-level shape."
  [{:keys [interface source capture environment project payload-schema payload warnings id]}]
  (with-evidence-identity
    (cond-> {:schema/version evidence-snapshot-version
             :interface interface
             :source source
             :capture capture
             :environment (or environment {})
             :project project
             :payload-schema payload-schema
             :payload payload
             :warnings (vec warnings)}
      id (assoc :evidence/id id))))

(defn analysis-content
  "Return the deterministic content covered by the analysis fingerprint."
  [analysis]
  (select-keys analysis
               [:schema/version
                :evidence
                :provider
                :projection
                :candidates
                :provider-inventory
                :warnings
                :confidence
                :alternatives
                :completeness]))

(defn with-analysis-identity
  "Attach deterministic content fingerprint and ID to an analysis result."
  [analysis]
  (let [content-fp (fingerprint (analysis-content analysis))]
    (assoc analysis
           :content/fingerprint content-fp
           :analysis/id (or (:analysis/id analysis)
                            (str "ar-" (subs content-fp 0 16))))))

(defn make-analysis-result
  "Build an Analysis Result map."
  [{:keys [evidence provider projection candidates provider-inventory warnings
           confidence alternatives completeness id]}]
  (with-analysis-identity
    (cond-> {:schema/version analysis-result-version
             :evidence evidence
             :provider provider
             :projection projection
             :candidates (vec candidates)
             :provider-inventory (or provider-inventory {})
             :warnings (vec warnings)
             :confidence (or confidence {})
             :alternatives (vec alternatives)
             :completeness (or completeness {})}
      id (assoc :analysis/id id))))

(defn make-interpretation
  "Build revisable interpretation session state over immutable analysis output."
  [{:keys [session-id analysis target claims status history id]}]
  {:schema/version interpretation-version
   :interpretation/id (or id (new-id "interp"))
   :session/id session-id
   :analysis analysis
   :target target
   :claims (vec claims)
   :status (or status :draft)
   :history (vec history)})

(def empty-reconciliation
  "Default reconciliation diff carried by a single-observation proposal."
  {:retained [] :new [] :disappeared [] :changed []})

(defn make-proposal-result
  "Build a Proposal Result carrying reviewable claims and intended writes.

   :reconciliation (optional) carries the two-observation semantic diff
   {:retained :new :disappeared :changed} (sl-bun). Single-observation
   proposals default it empty, so the key is always present and additive."
  [{:keys [session-id interpretations selected rejected unresolved conflicting
           intended-writes diagnostics reconciliation status id]}]
  {:schema/version proposal-version
   :proposal/id (or id (new-id "proposal"))
   :session/id session-id
   :interpretations (vec interpretations)
   :selected (vec selected)
   :rejected (vec rejected)
   :unresolved (vec unresolved)
   :conflicting (vec conflicting)
   :intended-writes (vec intended-writes)
   :diagnostics (vec diagnostics)
   :reconciliation (or reconciliation empty-reconciliation)
   :status (or status :draft)})

(s/def ::non-empty-string (s/and string? seq))
(s/def ::schema-version pos-int?)
(s/def ::fingerprint ::non-empty-string)
(s/def ::id ::non-empty-string)
(s/def ::name keyword?)
(s/def ::type keyword?)
(s/def ::interface (s/keys :req-un [::name ::type]))
(s/def ::kind keyword?)
(s/def ::source (s/keys :req-un [::kind]))
(s/def ::version ::non-empty-string)
(s/def ::config-fingerprint ::fingerprint)
(s/def ::captured-at ::non-empty-string)
(s/def ::mechanism keyword?)
(s/def ::best-effort? boolean?)
(s/def ::deterministic? boolean?)
(s/def ::warnings (s/coll-of map? :kind vector?))
(s/def ::capture
  (s/keys :req-un [::captured-at ::mechanism ::best-effort? ::deterministic?]
          :opt-un [::warnings]))
(s/def ::environment map?)
(s/def ::project map?)
(s/def ::payload-schema keyword?)
(s/def ::payload map?)
(s/def :evidence/id ::id)
(s/def :analysis/id ::id)
(s/def :interpretation/id ::id)
(s/def :proposal/id ::id)
(s/def :content/fingerprint ::fingerprint)
(s/def :schema/version ::schema-version)
(s/def ::evidence-snapshot
  (s/keys :req-un [::interface ::source ::capture ::environment ::project
                   ::payload-schema ::payload ::warnings]
          :req [:schema/version :evidence/id :content/fingerprint]))

(s/def ::evidence
  (s/keys :req-un [::id ::fingerprint]
          :opt-un [::schema-version]))
(s/def ::provider
  (s/keys :req-un [::id ::version ::config-fingerprint]))
(s/def ::projection
  (s/keys :req-un [::fingerprint]))
;; Typed candidate nodes (sl-wbn). Every structural node — element / component /
;; collection / widget — is a first-class candidate reusing :candidate/id as both
;; node identity and reconcile key. The shape is a flat adjacency vector: :parent
;; and :children carry candidate IDs, NOT literal nesting, so it fingerprints
;; cleanly and reconcile matches by id without tree-walking. Mirrors the live
;; intent model (intent/loader.clj, examples/06 product-card.edn): a :component
;; with :children + a nested :widget :intent "Rating" maps onto :intent +
;; :collections {:rating {:intent "Rating" :selector ... :count {:max 1}}}.
(s/def :candidate/id ::id)
(s/def ::candidate-kind #{:element :component :collection :widget})
(s/def ::parent (s/nilable :candidate/id))
(s/def ::children (s/coll-of :candidate/id :kind vector?))
(s/def ::selector (s/map-of keyword? map? :min-count 1)) ; mirrors intent ::interface-locator
(s/def ::cardinality #{:one :many})
(s/def ::count map?)
(s/def ::ambiguous (s/keys :req-un [::reason]))
(s/def ::reason keyword?)
(s/def ::candidate
  (s/and (s/keys :req [:candidate/id]
                 :opt-un [::parent ::children ::selector ::cardinality
                          ::count ::ambiguous ::confidence])
         #(or (nil? (:kind %)) (s/valid? ::candidate-kind (:kind %)))))
(s/def ::candidates (s/coll-of ::candidate :kind vector?))
(s/def ::provider-inventory map?)
(s/def ::confidence map?)
(s/def ::alternatives (s/coll-of map? :kind vector?))
(s/def ::completeness map?)
(s/def ::analysis-result
  (s/keys :req-un [::evidence ::provider ::projection ::candidates
                   ::provider-inventory ::warnings ::confidence
                   ::alternatives ::completeness]
          :req [:schema/version :analysis/id :content/fingerprint]))

(s/def :session/id ::id)
(s/def ::analysis map?)
(s/def ::target map?)
(s/def ::claims (s/coll-of map? :kind vector?))
(s/def ::status keyword?)
(s/def ::history (s/coll-of map? :kind vector?))
(s/def ::interpretation
  (s/keys :req-un [::analysis ::target ::claims ::status ::history]
          :req [:schema/version :interpretation/id :session/id]))

(s/def ::interpretations (s/coll-of map? :kind vector?))
(s/def ::selected (s/coll-of map? :kind vector?))
(s/def ::rejected (s/coll-of map? :kind vector?))
(s/def ::unresolved (s/coll-of map? :kind vector?))
(s/def ::conflicting (s/coll-of map? :kind vector?))
(s/def ::intended-writes (s/coll-of map? :kind vector?))
(s/def ::diagnostics (s/coll-of map? :kind vector?))
;; Two-observation reconciliation diff (sl-bun). Each bucket is a vector of
;; per-candidate diff claims; :new uses the keyword key intentionally.
(s/def ::retained (s/coll-of map? :kind vector?))
(s/def ::new (s/coll-of map? :kind vector?))
(s/def ::disappeared (s/coll-of map? :kind vector?))
(s/def ::changed (s/coll-of map? :kind vector?))
(s/def ::reconciliation
  (s/keys :req-un [::retained ::new ::disappeared ::changed]))
(s/def ::proposal-result
  (s/keys :req-un [::interpretations ::selected ::rejected ::unresolved
                   ::conflicting ::intended-writes ::diagnostics
                   ::reconciliation ::status]
          :req [:schema/version :proposal/id :session/id]))
