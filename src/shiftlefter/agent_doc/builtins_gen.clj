(ns shiftlefter.agent-doc.builtins-gen
  "Generator for the packaged `builtins` agent-doc topic (sl-6po).

   Renders a Markdown reference of the framework's built-in vocabulary —
   verbs/frames, the concrete step patterns that implement them, and the
   adapters that back each interface — from the framework's
   project-INDEPENDENT registries/resources, NOT from the zvo project
   projection (which requires a project config that build time has none of):

   - VERBS + FRAMES: `svo.glossary/load-default-glossaries` reads the shipped
     `verbs-web.edn` + `verbs-sms.edn`. The reference is largely a
     deterministic render of these declarations.
   - STEP PATTERNS: requiring `stepdefs.browser`/`stepdefs.sms` registers them;
     `stepengine.registry/all-stepdefs` then exposes each pattern with its
     `:svo`/`:interface`/`:requires-protocols` metadata.
   - ADAPTERS: `adapters.registry/default-registry` maps each adapter to the
     protocols it `:provides`.

   Generated, never hand-edited: the build regenerates it so it cannot drift
   from the code (same single-source-of-truth discipline as version.edn).
   `render` is a pure function of its data so it is determinism- and
   drift-testable; `live-sources`/`generate` capture from the live registries."
  (:require [clojure.string :as str]
            [shiftlefter.adapters.registry :as adapters]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.svo.glossary :as glossary]))

;; -----------------------------------------------------------------------------
;; Adapter -> interface map
;; -----------------------------------------------------------------------------

(def ^:private adapter-interfaces
  "Which interface family each built-in adapter backs. This is the one
   hand-maintained seam in the generator — add an entry here when shipping
   an adapter for a new interface, the same way you add to
   `glossary/default-verbs-resources` for a new default glossary. Adapters
   absent from this map fall into an 'other' bucket so nothing is dropped
   silently."
  {:etaoin     :web
   :playwright :web
   :sms-mock   :sms
   :sms-twilio :sms})

;; -----------------------------------------------------------------------------
;; Rendering helpers
;; -----------------------------------------------------------------------------

(defn- sorted-by-name
  "Sort a seq of keywords (or [keyword val] map entries) by keyword name for
   byte-stable output."
  [coll]
  (if (and (seq coll) (map-entry? (first coll)))
    (sort-by (comp name key) coll)
    (sort-by name coll)))

(defn- arg-cell
  "Render one arg slot, annotating its value kind when the frame declares
   one (sl-yh7): :value slots take a quoted literal or a {binding} token;
   :matcher slots are regex source whose named groups produce bindings."
  [arg arg-kinds]
  (case (get arg-kinds arg)
    :value   (str "`" arg "` (literal or `{binding}`)")
    :matcher (str "`" arg "` (regex; `(?<name>...)` produces `{name}` bindings)")
    (str "`" arg "`")))

(defn- frame-line
  "Render one verb frame as a Markdown bullet: surface pattern, the arg slots
   it introduces (with their value kinds, sl-yh7), any implicit object, and
   the O-slot kind when the frame declares one (sl-3jr4 — agents navigate by
   this doc, so a :location slot says whether it takes an intent ref, a
   literal URL, a {binding} token, or several)."
  [frame-kw {:keys [args pattern implicit-object object-kind location-refs?
                    arg-kinds]}]
  (str "- frame `" frame-kw "`: `" pattern "`"
       (when (seq args)
         (str " — args " (str/join ", " (map #(arg-cell % arg-kinds) args))))
       (when implicit-object
         (str " — implicit object `" implicit-object "`"))
       (when object-kind
         (str " — O: " (if (= object-kind :location)
                         (if location-refs?
                           "intent ref (bare PascalCase name), literal URL, or `{binding}`"
                           "literal URL")
                         (str "`" object-kind "`"))))))

(defn- verb-block
  "Render one verb: its description heading and a bullet per frame."
  [verb-kw {:keys [desc frames]}]
  (str/join
   "\n"
   (concat
    [(str "#### `" verb-kw "` — " desc)
     ""]
    (map (fn [[frame-kw frame]] (frame-line frame-kw frame))
         (sorted-by-name frames)))))

(defn- adapter-line
  "Render one adapter as a Markdown bullet listing the protocols it provides."
  [adapter-kw provides]
  (str "- `" adapter-kw "`"
       (if (seq provides)
         (str " — provides " (str/join ", " (map #(str "`" % "`")
                                                  (sort-by str provides))))
         " — provides nothing declared")))

(defn- step-line
  "Render one built-in step pattern: its regex source, the verb/frame it maps
   to, the interface lane marker, and any required capability protocols."
  [{:keys [pattern-src metadata]}]
  (let [{:keys [interface svo requires-protocols]} metadata]
    (str "- `" pattern-src "`"
         (when svo
           (str " → `" (:verb svo) "`/`" (:frame svo) "`"))
         (when interface
           (str " `[" interface "]`"))
         (when (seq requires-protocols)
           (str " — requires " (str/join ", " (map #(str "`" % "`")
                                                   (sort-by str requires-protocols))))))))

;; -----------------------------------------------------------------------------
;; Per-interface section
;; -----------------------------------------------------------------------------

(defn- interface-section
  "Render the full section for one interface: its adapters, verbs, and the
   concrete built-in step patterns, all sorted for stable output."
  [interface verbs adapters-for-iface stepdefs-for-iface]
  (str/join
   "\n"
   (concat
    [(str "## Interface `" interface "`")
     ""
     "### Adapters"
     ""]
    (if (seq adapters-for-iface)
      (map (fn [[adapter-kw entry]] (adapter-line adapter-kw (:provides entry)))
           (sorted-by-name adapters-for-iface))
      ["_No built-in adapters._"])
    [""
     "### Verbs"
     ""]
    (interpose ""
               (map (fn [[verb-kw entry]] (verb-block verb-kw entry))
                    (sorted-by-name verbs)))
    [""
     "### Built-in step patterns"
     ""]
    (if (seq stepdefs-for-iface)
      (map step-line (sort-by :pattern-src stepdefs-for-iface))
      ["_No built-in step patterns._"]))))

;; -----------------------------------------------------------------------------
;; Top-level render
;; -----------------------------------------------------------------------------

(def ^:private preamble
  (str/join
   "\n"
   ["# ShiftLefter Built-in Vocabulary"
    ""
    "Generated reference for the framework's built-in verbs, frames, step"
    "patterns, and adapters. A consumer project has only the jar, so this"
    "vocabulary cannot be discovered by grepping the project — it is supplied"
    "here as static, packaged reference."
    ""
    "This file is GENERATED from the framework's default glossaries"
    "(`verbs-web.edn`, `verbs-sms.edn`), the step registry, and the adapter"
    "registry. Do not edit it by hand — edit the source and regenerate with"
    "`clojure -T:build gen-builtins`."
    ""
    "Sections are grouped by interface for targeted subreads. Within each"
    "interface: the adapters that back it, the verbs and their frames (each"
    "frame is one accepted argument shape), then the concrete built-in step"
    "patterns and the verb/frame they map to."]))

(defn render
  "Render the built-in vocabulary Markdown from already-captured source data.

   Pure: given the same `sources` it returns byte-identical output, so it is
   determinism- and drift-testable without touching the live registries.

   `sources`:
   - `:verbs`    — `{interface-kw {verb-kw {:desc ... :frames {...}}}}`
                   (e.g. `glossary/load-default-glossaries`'s `:verbs`)
   - `:stepdefs` — seq of stepdef maps (`registry/all-stepdefs`)
   - `:adapters` — `{adapter-kw {:provides [...] ...}}` (`default-registry`)"
  [{:keys [verbs stepdefs adapters]}]
  (let [steps-by-iface (group-by #(get-in % [:metadata :interface]) stepdefs)
        adapters-by-iface (group-by (fn [[adapter-kw _]]
                                      (get adapter-interfaces adapter-kw :other))
                                    adapters)
        interfaces (->> (concat (keys verbs)
                                (keys steps-by-iface)
                                (keys adapters-by-iface))
                        (remove nil?)
                        distinct
                        (sort-by name))
        sections (map (fn [interface]
                        (interface-section
                         interface
                         (get verbs interface)
                         (into {} (get adapters-by-iface interface))
                         (get steps-by-iface interface)))
                      interfaces)]
    (str (str/join "\n\n" (cons preamble sections)) "\n")))

;; -----------------------------------------------------------------------------
;; Live capture
;; -----------------------------------------------------------------------------

(defn live-sources
  "Capture the built-in vocabulary sources from the live framework registries.

   Resets the step registry and re-requires the built-in stepdef namespaces so
   the captured set is exactly the framework's built-ins — never project steps
   that happen to be loaded. This mutates the global step registry (same
   clear-then-reload sequence `step_loader/load-step-paths!` uses); call from a
   build JVM or a test that owns/clears the registry."
  []
  (registry/clear-registry!)
  (require 'shiftlefter.stepdefs.browser :reload)
  (require 'shiftlefter.stepdefs.sms :reload)
  {:verbs (:verbs (glossary/load-default-glossaries))
   :stepdefs (vec (registry/all-stepdefs))
   :adapters adapters/default-registry})

(defn generate
  "Generate the built-in vocabulary Markdown from the live registries."
  []
  (render (live-sources)))

(defn write-doc!
  "Exec entry point: write the generated reference to `:out`.

   Invoked from the build via `clojure -X` so generation runs on the project
   classpath (the `-T:build` tool classpath does not see the framework). Used
   by both the `gen-builtins` task (writes the checked-in resource) and
   `uberjar` (refreshes the jar copy)."
  [{:keys [out]}]
  (spit out (generate))
  (println (str "Generated " out)))
