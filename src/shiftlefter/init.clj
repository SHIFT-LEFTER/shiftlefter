(ns shiftlefter.init
  "Project scaffold — the low-profile `sl init` entrypoint (sl-sl-init-scaffold-cn7).

   Help-invisible by ruling: the public `sl init` surface waits for system-wide
   distribution (sl-f1ir). Today it is invoked by the installer
   (release/install.sh) in the same breath that creates sl/ and drops the jar —
   one write moment, one permission ask.

   Semantics (Chair rulings 08-12/08-13/08-21, amended 08-28 sl-7dq5 F1):
   - COARSE BAIL: if `sl/shiftlefter.edn` exists at the instance root, this is
     already a ShiftLefter project — touch NOTHING, say why, exit 0. The config
     file is the project marker (the same anchor bin/sl's find_instance_root
     and shiftlefter.project-context key on). No repair mode, no --force, no
     migration; whole-scaffold all-or-nothing, never per-artifact skip. One
     narrow, named exception (sl-7dq5): an explicit `--write-agents` on a
     bailed scaffold performs ONLY the marker-guarded AGENTS.md write — the
     offer's named command must work after first init.
   - AGENTS.md breadcrumb: OPT-IN via `--write-agents` (the sl-7dq5 F1
     ownership boundary: SL writes unconditionally only inside its own
     namespace — sl/, .shiftlefter/; a user-owned agent file is written only
     as the direct object of an explicit user answer or command). By default
     init prints an offer naming the command instead. When asked, the write
     is create-or-append ONLY, marker-guarded; the stanza text is REUSED
     verbatim from the k0s jar resource, never re-authored. Skip when the
     marker is present — init never edits an existing stanza (upgrades belong
     to a future installer --update).
   - `.shiftlefter/.gitignore` containing `*` is written at init time (the
     folded sl-245y): the runtime litter dir is self-ignoring from birth.
   - Scaffold bodies live as jar resources under shiftlefter/init/ so the
     shipped jar carries them (agents-breadcrumb precedent)."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [shiftlefter.paths :as paths]))

;; -----------------------------------------------------------------------------
;; Specs — scaffold report
;; -----------------------------------------------------------------------------

(s/def ::status #{:scaffolded :bailed})
(s/def ::written (s/coll-of string? :kind vector?))
(s/def ::agents-file #{:created :appended :skipped :not-written})
(s/def ::write-agents? boolean?)
(s/def ::message string?)
(s/def ::report (s/keys :req-un [::status]
                        :opt-un [::written ::agents-file ::message]))

;; -----------------------------------------------------------------------------
;; Scaffold contents
;; -----------------------------------------------------------------------------

(def ^:private scaffold-files
  "Jar-resource-backed scaffold artifacts, in write/report order.
   [relative-target-path resource-path] — targets relative to the instance root."
  [["sl/shiftlefter.edn" "shiftlefter/init/shiftlefter.edn"]
   ["sl/glossary/subjects.edn" "shiftlefter/init/subjects.edn"]
   ["sl/glossary/intents/login.edn.example" "shiftlefter/init/login.edn.example"]
   ["sl/features/login.feature" "shiftlefter/init/login.feature"]])

(def ^:private breadcrumb-resource
  "k0s's single reusable on-ramp stanza (bead AC2: reuse, never re-author)."
  "shiftlefter/onramp/agents-breadcrumb.md")

(def onramp-begin-marker
  "Marker line wrapping the injected stanza in AGENTS.md; its presence means
   init already ran here (or the user pasted the stanza) — skip, never edit."
  "<!-- shiftlefter-onramp-begin -->")

(def onramp-end-marker "<!-- shiftlefter-onramp-end -->")

(defn- resource-text
  "Slurp a jar classpath resource; throws with :type/:message/:location if the
   resource is missing (a broken build, not a user error)."
  [path]
  (if-let [r (io/resource path)]
    (slurp r)
    (throw (ex-info (str "scaffold resource missing from classpath: " path)
                    {:type :init/missing-resource
                     :message (str "scaffold resource missing: " path)
                     :location path}))))

;; -----------------------------------------------------------------------------
;; AGENTS.md breadcrumb
;; -----------------------------------------------------------------------------

(defn- marked-stanza
  "The breadcrumb stanza wrapped in begin/end markers, ready to write."
  []
  (str onramp-begin-marker "\n"
       (str/trim-newline (resource-text breadcrumb-resource))
       "\n" onramp-end-marker "\n"))

(defn- write-breadcrumb!
  "Create-or-append the on-ramp stanza into <root>/AGENTS.md, marker-guarded.
   Returns :created | :appended | :skipped."
  [root]
  (let [f (fs/file root "AGENTS.md")]
    (cond
      (not (fs/exists? f))
      (do (spit f (marked-stanza)) :created)

      (str/includes? (slurp f) onramp-begin-marker)
      :skipped

      :else
      (let [existing (slurp f)
            sep (if (str/ends-with? existing "\n") "\n" "\n\n")]
        (spit f (str existing sep (marked-stanza)))
        :appended))))

;; -----------------------------------------------------------------------------
;; Scaffold
;; -----------------------------------------------------------------------------

(defn already-project?
  "The coarse-bail predicate: sl/shiftlefter.edn exists under `root`. The
   config file — not the bare sl/ directory the installer just created — is
   what makes a tree a ShiftLefter project."
  [root]
  (fs/exists? (fs/path root "sl" "shiftlefter.edn")))

(defn scaffold!
  "Scaffold a ShiftLefter project under `root` (default: the user's cwd).

   Options:
   - :write-agents? — write the AGENTS.md on-ramp stanza (marker-guarded
     create-or-append). Default false (sl-7dq5 F1 ownership boundary): the
     user's agent file is written only on an explicit ask; without it the
     CLI prints an offer instead.

   Returns a ::report:
   - {:status :bailed :message ...} — already a project; nothing touched.
     With :write-agents? the report also carries :agents-file — the one
     narrow bail-arm exception: the explicit ask still performs only the
     marker-guarded AGENTS.md write.
   - {:status :scaffolded :written [...]
      :agents-file :created|:appended|:skipped|:not-written}"
  ([] (scaffold! (paths/user-cwd) {}))
  ([root] (scaffold! root {}))
  ([root {:keys [write-agents?]}]
   (let [root (str root)]
     (when-not (fs/directory? root)
       (throw (ex-info (str "not a directory: " root)
                       {:type :init/bad-root
                        :message (str "not a directory: " root)
                        :location root})))
     (if (already-project? root)
       (cond-> {:status :bailed
                :message (str "sl/shiftlefter.edn already exists — this is "
                              "already a ShiftLefter project; scaffold "
                              "untouched. (Config updates are manual for "
                              "now; see the CHANGELOG on upgrades.)")}
         write-agents? (assoc :agents-file (write-breadcrumb! root)))
       (let [written (reduce (fn [acc [rel resource]]
                               (let [target (fs/file root rel)]
                                 (io/make-parents target)
                                 (spit target (resource-text resource))
                                 (conj acc rel)))
                             []
                             scaffold-files)
             ;; The folded sl-245y: the runtime litter dir self-ignores
             ;; from birth — one write moment, at init.
             gitignore (fs/file root ".shiftlefter" ".gitignore")
             _ (io/make-parents gitignore)
             _ (spit gitignore "*\n")
             written (conj written ".shiftlefter/.gitignore")
             agents (if write-agents? (write-breadcrumb! root) :not-written)
             written (cond-> written
                       (#{:created :appended} agents) (conj "AGENTS.md"))]
         {:status :scaffolded
          :written written
          :agents-file agents})))))

(s/fdef scaffold!
  :args (s/cat :root (s/? (s/nilable string?))
               :opts (s/? (s/nilable (s/keys :opt-un [::write-agents?]))))
  :ret ::report)

;; -----------------------------------------------------------------------------
;; CLI entry
;; -----------------------------------------------------------------------------

(defn- print-agents-outcome!
  "One honest line for the AGENTS.md disposition. `in-written?` true when the
   scaffold arm's per-artifact doseq already printed the create/append line."
  [agents-file in-written?]
  (case agents-file
    :not-written
    (println (str "sl init: AGENTS.md untouched — your agent files are yours. "
                  "To append the ShiftLefter on-ramp stanza (marker-guarded), "
                  "run `sl init --write-agents`."))

    :skipped
    (println "sl init: AGENTS.md already carries the on-ramp stanza — left as-is")

    (:created :appended)
    (when-not in-written?
      (println (str "sl init: "
                    (if (= :created agents-file)
                      "created AGENTS.md"
                      "AGENTS.md — on-ramp stanza appended (existing content untouched)"))))

    nil))

(defn init-cmd
  "The `sl init` command body. Prints one line per artifact; exit 0 on both
   the scaffold and bail arms (bail is a benign no-op by ruling). Honors
   `--write-agents` (sl-7dq5 F1) — without it AGENTS.md is never written and
   the offer is printed instead."
  [options]
  (let [{:keys [status written agents-file message]}
        (scaffold! (paths/user-cwd) {:write-agents? (boolean (:write-agents options))})]
    (case status
      :bailed
      (do
        (println (str "sl init: " message))
        (print-agents-outcome! agents-file false))

      :scaffolded
      (do
        (doseq [rel written]
          (println (str "sl init: created " rel
                        (when (and (= rel "AGENTS.md") (= :appended agents-file))
                          " (stanza appended)"))))
        (print-agents-outcome! agents-file true)
        (println "sl init: scaffold complete — try `sl run --dry-run`")))
    0))
