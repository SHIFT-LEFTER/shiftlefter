(ns shiftlefter.runner.results
  "The run-results directory convention (sl-y3wd, N5 D7).

   Every executing run in a real project gets a standing output home:

     <config-root>/results/<stamp>/

   Under the sl/ layout that is `sl/results/`; under the (pre-fcjm) root
   layout it is `<project>/results/` and converges when layout C is forced.
   `<stamp>` is the run's `:started-at` (UTC) compacted filesystem-safe —
   `2026-07-12T15:30:42` → `20260712T153042Z` — no colons, Windows-safe,
   sortable. On a same-second collision (a second `sl run` in the same
   second) the dir gets a short run-id suffix; run-ids are UUIDs, so the
   suffixed name cannot collide.

   One dir per runner INVOCATION: under setup.clj orchestration all groups
   share the invocation's run-id and this dir (the first executing group's
   `:started-at` stamps it). Where per-group artifacts land INSIDE the run
   dir (the sliver of sl-oobu this bead absorbs): `<run-dir>/<group-slug>/`
   — a paper convention until a writer exists (sl-l9nj).

   The results root self-ignores: a `results/.gitignore` containing `*` is
   written at creation (the Maven target/ pattern) — repo hygiene, not
   security. No-config (`:defaults`) invocations create nothing: the
   convention is a project convention.

   This bead writes nothing INTO the dir — the attachments contract
   (sl-l9nj) is the first producer; the zztu viewer browses the
   directory-of-stamped-runs."
  (:require [babashka.fs :as fs]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [shiftlefter.project-context :as project-context])
  (:import [java.nio.file FileAlreadyExistsException Files Path]
           [java.nio.file.attribute FileAttribute]))

;; The run-start-timestamp shape: ISO local datetime, UTC by construction.
(s/def ::started-at
  (s/with-gen (s/and string?
                     #(re-matches #"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}" %))
    #(gen/elements ["2026-07-12T15:30:42" "2026-01-01T00:00:00"])))
(s/def ::run-id string?)
;; with-gen so spec-health can exercise the spec (regex predicates have no
;; built-in generator).
(s/def ::stamp
  (s/with-gen (s/and string? #(re-matches #"[0-9]{8}T[0-9]{6}Z" %))
    #(gen/elements ["20260712T153042Z" "20260101T000000Z" "20991231T235959Z"])))

(defn stamp
  "Compact a run-ctx `:started-at` (ISO local datetime, UTC by construction
   — see `run-start-timestamp`) into the run-dir name: strip `-` and `:`,
   append `Z`. Pure."
  [started-at]
  (str (.replaceAll ^String started-at "[-:]" "") "Z"))

(s/fdef stamp
  :args (s/cat :started-at ::started-at)
  :ret ::stamp)

(defn results-root
  "The project's results root: `<config-root>/results` via the existing
   config-root path-class rule (N2). Pure — resolves, does not create."
  [project-context]
  (project-context/resolve-config-path project-context "results"))

(def ^:private gitignore-content
  ;; `*` ignores everything under results/ INCLUDING this file itself —
  ;; created run dirs are git-silent even in repos that track nothing else.
  "*\n")

(defn- ensure-results-root!
  "Create the results root and its self-ignoring .gitignore. Idempotent."
  [root]
  (fs/create-dirs root)
  (let [gi (fs/path root ".gitignore")]
    (when-not (fs/exists? gi)
      (spit (fs/file gi) gitignore-content)))
  root)

(defn- create-run-dir!
  "Atomically create `<root>/<stamp>`; on a same-second collision fall back
   to `<root>/<stamp>-<first 8 of run-id>`. Returns the created path string.
   `createDirectory` (not `create-dirs`) on the first attempt so the
   exists-already race loses loudly and multi-JVM invocations stay safe."
  [root stamp run-id]
  (let [primary ^Path (fs/path root stamp)]
    (try
      (str (Files/createDirectory primary (make-array FileAttribute 0)))
      (catch FileAlreadyExistsException _
        (let [suffixed (fs/path root (str stamp "-" (subs run-id 0 8)))]
          (str (fs/create-dirs suffixed)))))))

(defn ensure-run-dir!
  "Return this invocation's run-results directory (absolute path string),
   creating it on first call; nil when the context has no real config
   (`:defaults` — no project, no standing results home).

   `state` is a per-invocation atom (nil-initialized): the first executing
   run-scope creates and caches the dir from ITS `started-at`; later groups
   in the same invocation get the cached dir."
  [state project-context started-at run-id]
  ;; Groups execute sequentially within an invocation (run-with-setups!
  ;; mapv), so plain read-then-reset is race-free; no side effects in swap!.
  (when-not (= :defaults (:config-source project-context))
    (or (:results-dir @state)
        (let [root (ensure-results-root! (results-root project-context))
              dir (create-run-dir! root (stamp started-at) run-id)]
          (reset! state {:results-dir dir})
          dir))))

(s/fdef ensure-run-dir!
  :args (s/cat :state #(instance? clojure.lang.Atom %)
               :project-context map?
               :started-at ::started-at
               :run-id ::run-id)
  :ret (s/nilable string?))
