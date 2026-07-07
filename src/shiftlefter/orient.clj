(ns shiftlefter.orient
  "`sl orient` — the dynamic project-validity / project-state query (sl-jim).

   Orient answers the one discovery question grep cannot: \"given the accepted
   working-tree truth, what would fail?\" It is a thin RENDERER over the Project
   Knowledge Projection (`project-projection/build-projection`) — it never
   re-implements loading and never synthesizes accepted vocabulary.

   Lane (decisions/agent-surface.md): orient is the DYNAMIC validity lane. It
   points to `sl agent-doc` (including the generated `builtins` topic) for
   STATIC framework knowledge and leaves in-repo project vocab to grep. It does
   NOT re-render subjects/verbs/intents/stepdefs/macros inventories.

   Two modes:
   - default (thin): resolved context, counts, static-validation diagnostics,
     exact validate/run commands, and pointers to packaged agent-doc topics.
     A menu, not a meal — for the editing (Trench-like) agent.
   - --edn: a one-shot dump of the whole projection value, for a planning
     (Tower-like) or disposable sub-agent reasoning over the project's whole
     shape / negative space."
  (:require [clojure.string :as str]
            [shiftlefter.agent-doc :as agent-doc]
            [shiftlefter.project-projection :as projection]))

;; Diagnostics are byte-bounded so the thin render stays a menu even on a
;; project with many validation failures; the omission count keeps it honest.
(def ^:private max-diagnostics 20)

;; -----------------------------------------------------------------------------
;; No-config / brownfield bootstrap (AC11)
;; -----------------------------------------------------------------------------

(defn- bootstrap-next-steps
  "Actionable next steps for a project with no ShiftLefter config. Points only
   at surfaces that exist today — there is no `sl init`/`sl sieve` command."
  []
  ["Create shiftlefter.edn at the project root (or sl/shiftlefter.edn)."
   "Read `sl agent-doc overview` to learn the operating model."
   "Use `sl agent-doc sieve` for the vocabulary bootstrap workflow."])

(defn- render-bootstrap
  "Honest, actionable orientation for a brownfield project with no config.
   This is a first-class state, not an error (sl-jim AC11)."
  []
  (str/join
   "\n"
   (concat
    ["# No ShiftLefter project here yet"
     ""
     "This directory has no ShiftLefter config, so there is no accepted project"
     "truth to orient against. To get started:"
     ""]
    (map #(str "- " %) (bootstrap-next-steps))
    [""])))

(defn- bootstrap-edn
  "Machine-readable discriminator for the no-config state — the programmatic
   signal a tool keys on (the exit code stays 0: brownfield is the designed
   entry point, not a failure)."
  [project-context]
  {:status :no-project
   :config-source (:config-source project-context)
   :project-root (:project-root project-context)
   :next-steps (bootstrap-next-steps)})

;; -----------------------------------------------------------------------------
;; Thin (default) render
;; -----------------------------------------------------------------------------

(defn- context-lines [project-context]
  (concat
   ["## Project context"
    ""]
   (keep (fn [[label k]]
           (when-let [v (get project-context k)]
             (str "- " label ": " v)))
         [["project-root" :project-root]
          ["config-root" :config-root]
          ["config-path" :config-path]
          ["layout" :layout]
          ["config-source" :config-source]])))

(defn- counts-lines [counts]
  ;; Counts, not inventories — the menu. The agent greps the repo (or reads
  ;; `sl agent-doc builtins`) for the actual entries.
  ["## Counts"
   ""
   (str "- subjects: " (:subjects counts)
        " · instances: " (:instances counts)
        " · interfaces: " (:interfaces counts))
   (str "- verbs: " (:verbs counts)
        " · intents: " (:intents counts)
        " · stepdefs: " (:stepdefs counts)
        " · macros: " (:macros counts))
   (str "- diagnostics: " (:diagnostics counts))])

(defn- diagnostic-line [{:keys [stage severity type message]}]
  (str "- [" (name (or severity :error)) "/" (name (or stage :?)) "] "
       (or type "")
       (when message (str " — " message))))

(defn- diagnostics-lines [diagnostics]
  (let [total (count diagnostics)
        shown (take max-diagnostics diagnostics)
        omitted (- total (count shown))]
    (concat
     ["## What would fail (static validation)"
      ""]
     (if (zero? total)
       ["No static-validation diagnostics. Accepted project truth loads clean."]
       (concat
        (map diagnostic-line shown)
        (when (pos? omitted)
          [(str "- … and " omitted " more (byte-bounded; use `--edn` for all).")]))))))

(defn- commands-lines [validation-commands]
  (concat
   ["## Validate / run"
    ""]
   (if (seq validation-commands)
     (map #(str "- `" % "`") validation-commands)
     ["- (no validation commands available)"])))

(defn- pointers-lines []
  ;; Pulled live from the topic registry so new topics (e.g. builtins) always
  ;; appear — orient routes to STATIC knowledge, it does not carry it.
  (concat
   ["## Static knowledge (read these)"
    ""]
   (map #(str "- `sl agent-doc " % "`") (agent-doc/topic-names))))

(defn- mode-line
  "One-line mode statement (sl-hjnp) — the silent-vanilla guard. Nil when the
   projection has no :mode (config never loaded)."
  [mode]
  (case mode
    :shifted "Mode: Shifted — SVO validation ON (:svo present in config)"
    :vanilla "Mode: Vanilla — SVO validation OFF (no :svo in config; add :svo to enable)"
    nil))

(defn- render-thin
  "Render the thin default orientation from a complete projection."
  [projection-value]
  (let [view (projection/project-view projection-value {:detail :summary})]
    (str
     (str/join
      "\n"
      (concat
       ["# Orientation"
        ""
        (str "Status: " (name (or (:status projection-value) :unknown))
             " · fingerprint: " (or (:fingerprint projection-value) "n/a"))]
       (keep identity [(mode-line (:mode projection-value))])
       [""]
       (context-lines (:project-context projection-value))
       [""]
       (counts-lines (:counts view))
       [""]
       (diagnostics-lines (:diagnostics projection-value))
       [""]
       (commands-lines (:validation-commands projection-value))
       [""]
       (pointers-lines)))
     "\n")))

;; -----------------------------------------------------------------------------
;; Command entry
;; -----------------------------------------------------------------------------

(defn- emit-edn!
  "Dumb one-shot emit of a value as reader-readable EDN on stdout."
  [value]
  (prn value))

(defn orient-cmd
  "Render project orientation. Returns a CLI exit code.

   - 0: oriented successfully (thin or --edn), or no-config bootstrap.
   - 1: project found but the projection failed to load (`:status :error`).
   - 2: orient cannot resolve a usable context (ambiguous config).

   `project-context` is the resolved sl-a4a context dispatch already built."
  [_arguments {:keys [edn]} project-context]
  (let [config-source (:config-source project-context)]
    (cond
      ;; AC11 — brownfield: no real config. First-class bootstrap, exit 0.
      (= :defaults config-source)
      (do
        (if edn
          (emit-edn! (bootstrap-edn project-context))
          (print (render-bootstrap)))
        (flush)
        0)

      ;; Ambiguous config — orient cannot pick a project to orient against.
      (= :error config-source)
      (do
        (binding [*out* *err*]
          (println "Cannot orient: ambiguous ShiftLefter config.")
          (doseq [d (:diagnostics project-context)]
            (println (str "  " (or (:message d) (:type d))))))
        2)

      :else
      (let [projection-value (projection/build-projection project-context)]
        (if edn
          (do (emit-edn! projection-value) (flush) 0)
          (do
            (print (render-thin projection-value))
            (flush)
            (if (= :error (:status projection-value)) 1 0)))))))
