(ns shiftlefter.agent-doc.examples-gen
  "Generator for the packaged `examples` agent-doc topic (sl-c9qj).

   Renders worked vocabulary examples — configs, glossaries, intents, feature
   files, and a setup.clj — VERBATIM from the shipped `examples/` projects,
   each snippet carrying a provenance line naming its source file. Because the
   snippets are byte-copies of files the example planning validators and e2e
   tests keep true, the topic cannot drift into fiction: worked snippets that
   cannot rot.

   Why this exists: a jar-only consumer has no `examples/` tree. The ocz drive
   runs showed an agent burning tool calls unzipping the jar to infer EDN
   shapes (run 1), and a quality jump once real examples were visible (run 2).
   This topic ships run 2's advantage through `sl agent-doc examples`.

   Generated, never hand-edited: same single-source discipline as
   `builtins-gen`. `render` is a pure function of its data so it is
   determinism- and drift-testable; `live-sources` slurps the manifest files
   from the working tree (build/dev/public-export repos all ship `examples/`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Snippet manifest
;; -----------------------------------------------------------------------------

(def manifest
  "Ordered sections of the topic. Each snippet is included verbatim from
   `:path` (repo-root relative). Framing prose lives here in code — reviewed
   like the builtins preamble — while snippet CONTENT always comes from the
   example files themselves."
  [{:title "Minimal config"
    :framing (str "The smallest useful config. It lives at `sl/shiftlefter.edn` "
                  "— the ONLY discovered location; everything ShiftLefter "
                  "(config, glossaries, features, steps) lives under `sl/`. "
                  "Everything beyond the interface map (glossaries, "
                  "`:enforcement`, step paths) is opt-in.")
    :snippets [{:path "examples/02-browser-zero-code/sl/shiftlefter.edn"
                :lang "edn"}]}
   {:title "Config with glossaries wired"
    :framing (str "The canonical glossary layout: `glossary/` next to the "
                  "config (so `sl/glossary/`), subjects in "
                  "`glossary/subjects.edn`, plus `:enforcement` levels "
                  "and a macro registry under `:runner :macros`. The "
                  "glossary path is CONVENTIONAL — a config with no "
                  "`:glossaries` key discovers it automatically; this "
                  "example spells it explicitly for teaching. Note there is "
                  "no project verbs glossary: the built-in interface verbs "
                  "cover the example, which is the normal case.")
    :snippets [{:path "examples/svo-demo/sl/shiftlefter.edn"
                :lang "edn"}]}
   {:title "Subjects glossary"
    :framing (str "Subject types with optional `:instances` (session handles "
                  "— `:user/alice`) and singletons (`:admin`, `:guest`).")
    :snippets [{:path "examples/svo-demo/sl/glossary/subjects.edn"
                :lang "edn"}]}
   {:title "Macro registry (INI)"
    :framing (str "Domain actions are MACROS, not interface verbs: \"logs "
                  "in\" is a contraction of several interface steps (fill, "
                  "fill, click) wearing a domain name, authored as data in "
                  "an INI registry and invoked from Gherkin with a trailing "
                  "` +` provenance marker (`When Alice logs in +`). Enable "
                  "via `:runner {:macros {:enabled? true :registry-paths "
                  "[...]}}`. The match is fixed text today (no subject/"
                  "argument parameterization — named-argument macros are "
                  "roadmapped); never declare a domain action as a glossary "
                  "verb, which types a word nothing can run.")
    :snippets [{:path "examples/svo-demo/sl/macros/login.ini"
                :lang "ini"}]}
   {:title "Intent with elements"
    :framing (str "The simplest intent shape: named elements with "
                  "per-interface locator bindings. Steps then address "
                  "`Login.email` instead of a raw locator.")
    :snippets [{:path "examples/04-sms-2fa/sl/glossary/intents/login.edn"
                :lang "edn"}]}
   {:title "Config using the conventional intents directory"
    :framing (str "Intents load from a directory of EDN files. The "
                  "conventional `glossary/intents/` next to the config is "
                  "discovered automatically (no `:glossaries` key needed); "
                  "an explicit `:glossaries {:intents \"glossary/intents\"}` "
                  "overrides the convention wholesale.")
    :snippets [{:path "examples/06-nested-parent-anchored/sl/shiftlefter.edn"
                :lang "edn"}]}
   {:title "Parent-anchored nested intents"
    :framing (str "One reused component (`ProductCard`) appearing under "
                  "three different wrappers: the parent's `:collections` "
                  "supply the container `:selector`, the child keeps only "
                  "its inner selectors, and features address nested regions "
                  "semantically (`Dashboard.featured[1].rating.stars`) — "
                  "prefer this over flat union selectors.")
    :snippets [{:path "examples/06-nested-parent-anchored/sl/glossary/intents/dashboard.edn"
                :lang "edn"}
               {:path "examples/06-nested-parent-anchored/sl/glossary/intents/product-card.edn"
                :lang "edn"}
               {:path "examples/06-nested-parent-anchored/sl/glossary/intents/rating.edn"
                :lang "edn"}]}
   {:title "Feature: intent refs and nested addresses"
    :framing (str "Built-in steps driving the parent-anchored intents above. "
                  "Note the single-quoted literals (double quotes are "
                  "reserved) and that no selector ever appears in step text.")
    :snippets [{:path "examples/06-nested-parent-anchored/sl/features/catalog.feature"
                :lang "gherkin"}]}
   {:title "Feature: raw-locator fallback"
    :framing (str "Built-in steps can also take raw locator maps inline "
                  "(`{:id \"username\"}`) when no intent exists yet — a "
                  "legitimate fallback; see `sl agent-doc locators` for the "
                  "brittleness policy.")
    :snippets [{:path "examples/02-browser-zero-code/sl/features/login.feature"
                :lang "gherkin"}]}
   {:title "Run-level orchestration: setup.clj"
    :framing (str "The setup.clj convention: run-level fixture orchestration "
                  "(start/stop a server around chosen features), not a step "
                  "definition — the feature files stay zero custom stepdefs.")
    :snippets [{:path "examples/02-browser-zero-code/sl/setup.clj"
                :lang "clojure"}]}])

;; -----------------------------------------------------------------------------
;; Rendering
;; -----------------------------------------------------------------------------

(def ^:private preamble
  (str/join
   "\n"
   ["# ShiftLefter Worked Examples"
    ""
    "Complete, runnable file shapes — config, glossaries, intents, features,"
    "and run-level orchestration — copied VERBATIM from the framework's shipped"
    "`examples/` projects. A consumer project has only the jar, so these worked"
    "shapes are supplied here as packaged reference; each snippet names its"
    "source file."
    ""
    "This file is GENERATED from the example files themselves (which the"
    "example validation tests keep true). Do not edit it by hand — edit the"
    "source example and regenerate with `clojure -T:build gen-examples`."]))

(defn- snippet-block
  "Render one snippet: provenance line + fenced verbatim content."
  [{:keys [path lang content]}]
  (str "_Verbatim from `" path "`._\n"
       "\n"
       "```" lang "\n"
       (str/trimr content) "\n"
       "```"))

(defn- section-block
  "Render one section: heading, framing prose, then each snippet block."
  [{:keys [title framing snippets]}]
  (str/join
   "\n\n"
   (concat [(str "## " title) framing]
           (map snippet-block snippets))))

(defn render
  "Render the worked-examples Markdown from already-captured sections.

   Pure: given the same `sections` (manifest entries whose snippets carry
   `:content`) it returns byte-identical output, so it is determinism- and
   drift-testable without touching the filesystem."
  [sections]
  (str (str/join "\n\n" (cons preamble (map section-block sections))) "\n"))

;; -----------------------------------------------------------------------------
;; Live capture
;; -----------------------------------------------------------------------------

(defn live-sections
  "Capture the manifest with each snippet's `:content` slurped from the
   working tree (repo-root-relative paths). Throws naming the path if a
   manifest file is missing — a silent placeholder would defeat the topic."
  []
  (mapv (fn [section]
          (update section :snippets
                  (partial mapv
                           (fn [{:keys [path] :as snippet}]
                             (let [f (io/file path)]
                               (when-not (.exists f)
                                 (throw (ex-info "examples topic source file missing"
                                                 {:type :examples-gen/missing-source
                                                  :path path})))
                               (assoc snippet :content (slurp f)))))))
        manifest))

(defn generate
  "Generate the worked-examples Markdown from the live example files."
  []
  (render (live-sections)))

(defn write-doc!
  "Exec entry point: write the generated topic to `:out`.

   Invoked from the build via `clojure -X` so generation runs on the project
   classpath, same as `builtins-gen`/`agent-md-gen`."
  [{:keys [out]}]
  (spit out (generate))
  (println (str "Generated " out)))
