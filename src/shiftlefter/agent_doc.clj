(ns shiftlefter.agent-doc
  "Jar-packaged agent doctrine documents."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private resource-root "shiftlefter/agent_docs")

(def default-topic "overview")

(def topics
  [{:topic "intro"
    :summary "What ShiftLefter is, the agent surface, and project-fit pointer."}
   {:topic "overview"
    :summary "Operating model and command map for fresh agents."}
   {:topic "authoring"
    :summary "Subjects, instances, Shifted syntax, and step authoring rules."}
   {:topic "vocabulary"
    :summary "Glossaries, interfaces, verbs, intents, and partial vocabulary."}
   {:topic "locators"
    :summary "Intent references, raw locator fallback, and brittleness policy."}
   {:topic "diagnostics"
    :summary "Unknown subject, verb, interface, and object diagnostics."}
   {:topic "sieve"
    :summary "Vocabulary bootstrap and proposal reconciliation workflow."}
   {:topic "builtins"
    :summary "Generated reference: built-in verbs, frames, step patterns, adapters."}])

(defn topic-names
  "Return available agent-doc topic names in display order."
  []
  (mapv :topic topics))

(defn known-topic?
  [topic]
  (boolean (some #(= topic (:topic %)) topics)))

(defn topic-resource-path
  [topic]
  (str resource-root "/" topic ".md"))

(defn load-topic
  "Load a packaged Markdown topic from the classpath."
  [topic]
  (cond
    (not (known-topic? topic))
    {:status :missing-topic
     :topic topic}

    :else
    (let [resource (io/resource (topic-resource-path topic))]
      (if resource
        {:status :ok
         :topic topic
         :content (slurp resource)}
        {:status :missing-resource
         :topic topic
         :resource (topic-resource-path topic)}))))

(defn format-topic-list
  []
  (str/join
   "\n"
   (concat
    ["Available agent-doc topics:"]
    (map (fn [{:keys [topic summary]}]
           (format "  %-12s %s" topic summary))
         topics))))

(defn- print-usage! []
  (binding [*out* *err*]
    (println "Usage: sl agent-doc [topic]")
    (println "       sl agent-doc --list")))

(defn- print-missing-topic! [topic]
  (binding [*out* *err*]
    (println (str "Unknown agent-doc topic: " topic))
    (println)
    (println (format-topic-list))))

(defn- print-missing-resource! [{:keys [topic resource]}]
  (binding [*out* *err*]
    (println (str "Agent-doc resource missing for topic: " topic))
    (println (str "Expected classpath resource: " resource))))

(defn agent-doc-cmd
  "Print packaged agent documentation. Returns a CLI exit code."
  [arguments options]
  (let [args (vec arguments)]
    (cond
      (:list options)
      (do
        (println (format-topic-list))
        0)

      (> (count args) 2)
      (do
        (print-usage!)
        1)

      :else
      (let [topic (or (second args) default-topic)
            result (load-topic topic)]
        (case (:status result)
          :ok
          (do
            (print (:content result))
            (flush)
            0)

          :missing-topic
          (do
            (print-missing-topic! topic)
            1)

          :missing-resource
          (do
            (print-missing-resource! result)
            1))))))
