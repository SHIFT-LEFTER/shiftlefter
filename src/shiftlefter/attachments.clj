(ns shiftlefter.attachments
  "The attachments contract (sl-l9nj, N5 stage A).

   An attachment is a PURE-DATA REFERENCE in the envelope — bytes never
   inline (payload-by-reference). The ref map uses `:attachment/*` keys;
   `:attachment/kind` and `:attachment/path` are required, everything else
   optional. Refs ride the per-step and scenario `:attachments` vectors
   (extend-never-break; reporter invariant 3 holds by construction).

   Path portability contract (Chair-ratified, group-root-relative):
   - a RELATIVE `:attachment/path` is group-root-relative — portable; the
     HTML report renders it inline because report.html sits at the group
     root (single implicit group: the run dir itself).
   - an ABSOLUTE path under the group root is relativized at finalize time
     (hooks naturally build paths from the absolute `:artifacts-dir`).
   - an ABSOLUTE path outside the group root stays verbatim — allowed,
     legible, non-portable (reporters render it as plain text, never a
     link/img).
   - a relative path with NO group root (`:defaults` config — no run dir)
     is unverifiable: the ref is kept but marked `:attachment/error`
     (record the miss legibly; never guess against cwd).

   The After-return contract (`::after-return`) is FULL-STRICT (Chair
   ruling 2026-08-03, Before symmetry): nil = no claim; anything else must
   be a map CLOSED to `:attachments` holding spec-valid refs. Typo'd key,
   stray sibling key, malformed ref, or non-map => :hook/invalid-return
   => scenario :error — a claim that drops its witness converts verified
   to vacuous, so malformed claims die loudly.

   `:attachment/excerpt` is bounded (the D3 mailed-alone triage courtesy):
   `finalize-refs!` truncates to `excerpt-cap` chars with a marker. The
   file is the evidence; the excerpt is a courtesy — truncation, not error.

   This ns is a LEAF: requireable from both stepengine (return validation,
   source stamping) and runner (envelope, reporters) — no stepengine->
   runner edge exists or may be added here."
  (:require [babashka.fs :as fs]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str])
  (:import [java.nio.file FileAlreadyExistsException Files Path]
           [java.nio.file.attribute FileAttribute]))

;; -----------------------------------------------------------------------------
;; Specs — the ref shape (design doc §2, verbatim key set)

(s/def :attachment/kind keyword?)
;; Non-empty string; with-gen because (s/and string? seq) has no reliable
;; built-in generator path (spec-health exercises every spec).
(s/def :attachment/path
  (s/with-gen (s/and string? seq)
    #(gen/not-empty (gen/string-alphanumeric))))
(s/def :attachment/media-type string?)
(s/def :attachment/bytes nat-int?)
;; Provenance: sl-3q9r's :capture-policy value is spec'd now (the contract
;; is stage A's deliverable); hook claims are ALWAYS stamped [:hook <name>]
;; by the framework — provenance is not self-asserted.
(s/def :attachment/source
  (s/or :policy #{:capture-policy}
        :hook (s/tuple #{:hook} string?)))
(s/def :attachment/subject keyword?)
(s/def :attachment/interface keyword?)
(s/def :attachment/duration-ms
  (s/with-gen (s/and number? (complement neg?))
    #(gen/one-of [(gen/large-integer* {:min 0}) (gen/double* {:min 0 :NaN? false})])))
(s/def :attachment/excerpt string?)
(s/def :attachment/error string?)

(s/def ::ref
  (s/keys :req [:attachment/kind :attachment/path]
          :opt [:attachment/media-type :attachment/bytes :attachment/source
                :attachment/subject :attachment/interface
                :attachment/duration-ms :attachment/excerpt
                :attachment/error]))

(s/def ::attachments (s/coll-of ::ref :kind vector?))

;; The After-return claim: closed to :attachments (lean-strict — a stray
;; sibling key is a malformed claim, not tolerated decoration). The s/keys
;; generator emits only declared keys, so the closed-map predicate holds
;; under spec-health generation.
(s/def ::after-return
  (s/and (s/keys :req-un [::attachments])
         #(= #{:attachments} (set (keys %)))))

;; -----------------------------------------------------------------------------
;; Pure helpers

(def excerpt-cap
  "Bound on `:attachment/excerpt` (chars). The envelope never holds bulk
   text — the excerpt exists so a mailed-alone report still triages."
  4096)

(def ^:private truncation-marker "\n…[excerpt truncated]")

(defn truncate-excerpt
  "Bound the ref's `:attachment/excerpt` to `excerpt-cap` chars, marked.
   Refs without an excerpt pass through. Pure."
  [ref]
  (let [e (:attachment/excerpt ref)]
    (if (and e (> (count e) excerpt-cap))
      (assoc ref :attachment/excerpt
             (str (subs e 0 (- excerpt-cap (count truncation-marker)))
                  truncation-marker))
      ref)))

(s/fdef truncate-excerpt :args (s/cat :ref map?) :ret map?)

(defn slug
  "Filesystem-safe name from arbitrary text: lowercase, runs of anything
   outside [a-z0-9] collapse to a single `-`, edges trimmed, capped at 60
   chars. Blank input (or blank result) falls back to `fallback`
   (1-arity: \"scenario\"). Pure."
  ([s] (slug s "scenario"))
  ([s fallback]
   (let [cleaned (-> (str s)
                     str/lower-case
                     (str/replace #"[^a-z0-9]+" "-")
                     (str/replace #"^-+|-+$" ""))
         capped (if (> (count cleaned) 60) (subs cleaned 0 60) cleaned)
         trimmed (str/replace capped #"-+$" "")]
     (if (str/blank? trimmed) fallback trimmed))))

(s/fdef slug
  :args (s/cat :s (s/nilable string?) :fallback (s/? (s/and string? seq)))
  :ret (s/and string? seq))

(defn- as-path ^Path [s]
  (.normalize (fs/path s)))

(defn- absolute-path? [s]
  (.isAbsolute (as-path s)))

(defn relativize-under
  "Relative path string (forward slashes) of `p` under `root`, or nil when
   `p` is not under `root`. Both absolute path strings. (Public since
   sl-7dq5: the runner relativizes the run's results path against the
   config root for the envelope's :run/results-path.)"
  [root p]
  (let [rootp (as-path root)
        pp (as-path p)]
    (when (.startsWith pp rootp)
      (-> (.relativize rootp pp) str (str/replace "\\" "/")))))

(defn normalize-ref
  "Apply the path portability contract to one ref (see ns docstring):
   absolute-under-root -> relativized; absolute-outside -> verbatim;
   relative with root -> verbatim (already group-root-relative); relative
   with NO root -> marked `:attachment/error` (unverifiable claim; an
   error the hook already reported is never clobbered). Pure."
  [group-root ref]
  (let [p (:attachment/path ref)]
    (cond
      (absolute-path? p)
      (if-let [rel (and group-root (relativize-under group-root p))]
        (assoc ref :attachment/path rel)
        ref)

      group-root ref

      :else
      (update ref :attachment/error
              #(or % "no run directory to resolve against")))))

(s/fdef normalize-ref
  :args (s/cat :group-root (s/nilable string?) :ref ::ref)
  :ret ::ref)

(defn resolve-ref-path
  "Absolute path string for a ref path: absolute -> itself; relative ->
   resolved against `group-root`; relative with nil root -> nil. Pure."
  [group-root path]
  (cond
    (absolute-path? path) (str (as-path path))
    group-root (str (as-path (fs/path group-root path)))
    :else nil))

(s/fdef resolve-ref-path
  :args (s/cat :group-root (s/nilable string?) :path (s/and string? seq))
  :ret (s/nilable string?))

(defn stamp-source
  "Framework-stamp a hook claim's provenance: `:attachment/source` becomes
   `[:hook <name>]`, ALWAYS overwriting — provenance is not self-asserted.
   Pure."
  [hook-name ref]
  (assoc ref :attachment/source [:hook hook-name]))

(s/fdef stamp-source
  :args (s/cat :hook-name string? :ref ::ref)
  :ret ::ref)

;; -----------------------------------------------------------------------------
;; Effectful — the scenario artifacts dir and claim finalization

(defn ensure-scenario-dir!
  "Create `<group-root>/attachments/<scenario-slug>/`, returning the
   absolute path string. Race-safe under parallel scenario execution
   (sl-q9wp): the parent is created idempotently, the scenario dir with
   `createDirectory` so a name collision (scenario-outline examples share
   a pickle name, possibly concurrently) loses LOUDLY and falls back to
   `<slug>-<first 8 of pickle-id>` — pickle UUIDs are unique per run, so
   the fallback cannot collide. A nil `pickle-id` (hand-built test plans)
   degrades to an idempotent shared dir."
  [group-root scenario-name pickle-id]
  (let [parent (fs/path group-root "attachments")
        base (fs/path parent (slug scenario-name))]
    (Files/createDirectories parent (make-array FileAttribute 0))
    (if (nil? pickle-id)
      (str (fs/create-dirs base))
      (try
        (str (Files/createDirectory ^Path base (make-array FileAttribute 0)))
        (catch FileAlreadyExistsException _
          (let [suffixed (fs/path parent (str (slug scenario-name) "-"
                                              (subs (str pickle-id) 0 8)))]
            (str (fs/create-dirs suffixed))))))))

(s/fdef ensure-scenario-dir!
  :args (s/cat :group-root (s/and string? seq)
               :scenario-name (s/nilable string?)
               :pickle-id any?)
  :ret (s/and string? seq))

(defn finalize-refs!
  "Finalize validated, source-stamped claim refs against the world:
   truncate excerpts, normalize paths (portability contract), then check
   each resolvable file — present files get `:attachment/bytes` filled
   when absent (cheap honesty); missing files get `:attachment/error`
   (the reference is data; the file is world — outcome untouched). A ref
   the hook already marked `:attachment/error` (a self-reported failed
   capture) skips the file check and is never clobbered.

   Returns {:refs [finalized...] :missing [path...]} — `:missing` is only
   the NEWLY-marked misses, so the caller owns exactly one stderr warn per
   miss."
  [group-root refs]
  (reduce
   (fn [acc ref]
     (let [ref (->> ref truncate-excerpt (normalize-ref group-root))
           path (:attachment/path ref)
           abs (resolve-ref-path group-root path)]
       (cond
         (:attachment/error ref)
         (update acc :refs conj ref)

         (and abs (fs/exists? abs))
         (update acc :refs conj
                 (cond-> ref
                   (nil? (:attachment/bytes ref))
                   (assoc :attachment/bytes (fs/size abs))))

         :else
         (-> acc
             (update :refs conj
                     (assoc ref :attachment/error (str "file not found: " path)))
             (update :missing conj path)))))
   {:refs [] :missing []}
   refs))

(s/def ::refs ::attachments)
(s/def ::missing (s/coll-of string? :kind vector?))

(s/fdef finalize-refs!
  :args (s/cat :group-root (s/nilable string?) :refs (s/coll-of ::ref))
  :ret (s/keys :req-un [::refs ::missing]))
