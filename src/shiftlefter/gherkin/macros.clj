(ns shiftlefter.gherkin.macros
  "Pass 2 macro expansion for ShiftLefter Gherkin parser."
  (:require [clojure.java.io :as jio]
            [clojure.string :as str]
            [shiftlefter.gherkin.io :as io]))

(defn- find-macro-files
  "Find all .ini files in root-dir."
  [root-dir]
  (let [dir (jio/file root-dir)]
    (when (.exists dir)
      (filter #(and (.isFile %)
                    (re-matches #".*\.ini$" (.getName %)))
              (file-seq dir)))))

(defn- parse-all-macros-in-file
  "Parse all macros from a single INI file."
  [file-path]
  (try
    (let [lines (str/split (io/slurp-utf8 (str file-path)) #"\n")
          name-indices (keep-indexed #(when (str/starts-with? %2 "name = ") %1) lines)]
      (for [start-idx name-indices]
        (let [macro-name (str/trim (subs (nth lines start-idx) (count "name = ")))
              next-idx (or (first (for [i (range (inc start-idx) (count lines))
                                         :when (str/starts-with? (nth lines i) "name = ")]
                                     i))
                            (count lines))
              macro-lines (take (- next-idx start-idx) (drop start-idx lines))
              macro-lines (rest macro-lines) ; skip the name line
              description-line (first (filter #(clojure.string/starts-with? % "description =") macro-lines))
              description (when description-line (clojure.string/trim (subs description-line (count "description ="))))
              steps-idx (first (keep-indexed #(when (clojure.string/starts-with? %2 "steps =") %1) macro-lines))
              steps-lines (when steps-idx (drop (inc steps-idx) macro-lines))
              steps-lines (take-while #(not (clojure.string/blank? %)) steps-lines)
              steps-lines (remove clojure.string/blank? steps-lines)
              indent-error (when (seq steps-lines)
                             (let [indents (map #(count (re-find #"^[ \t]*" %)) steps-lines)]
                               (cond
                                 (not (apply = indents)) "Inconsistent indentation in steps"
                                 (<= (first indents) 0) "All step lines must be indented"
                                 :else nil)))]
          (if indent-error
            {:error {:message indent-error :macro macro-name}}
            (let [parsed-steps (for [[idx line] (map-indexed vector steps-lines)
                                     :let [line-num (+ start-idx idx 2) ; approximate
                                           trimmed (clojure.string/trim line)
                                           indent (count (re-find #"^[ \t]*" line))]
                                     :when (> indent 0)]
                                 (if-let [[_ keyword text] (re-matches #"^(Given|When|Then|And|But|\*) (.+)" trimmed)]
                                   {:step {:keyword keyword :text text :location {:line line-num :col (inc indent)}}}
                                   {:error {:message (str "Invalid step line: '" trimmed "'") :macro macro-name}}))
                  errors (keep :error parsed-steps)
                  steps (keep :step parsed-steps)]
              (if (and description (seq steps-lines) (empty? errors))
                {:macro {:name macro-name :description description :steps steps}}
                {:error {:message (str "Validation errors for macro '" macro-name "': "
                                        (cond-> []
                                                (not description) (conj "Missing description")
                                                (not (seq steps-lines)) (conj "Missing steps block")
                                                (seq errors) (into (map :message errors))))
                         :macro macro-name}}))))))
    (catch Exception e
      {:error {:message (.getMessage e)}})))

(defn load-macro-registry
  "Load macros from root-dir into a registry map.
   (load-macro-registry root-dir) → registry"
  [root-dir]
  (let [files (find-macro-files root-dir)
        all-macros (mapcat parse-all-macros-in-file files)
        valid-macros (keep :macro all-macros)
        errors (keep :error all-macros)]
    (when (seq errors)
      (println "Macro loading errors:" errors)) ; or log
    (into {} (map (juxt :name identity) valid-macros))))

(defn- expand-node
  "Recursively expand a single AST node."
  [node registry]
  (case (:type node)
    :macro-step
    (let [macro-name (:text node)
          macro-def (get registry macro-name)]
      (if macro-def
        ;; Create macro-block
        (for [step (:steps macro-def)]
          (assoc step
                 :source {:macro-name macro-name :original-location (:location node)}))
        ;; If macro not found, error
        [{:error {:message (str "Unknown macro: " macro-name)
                  :location (:location node)}}]))

    :scenario
    [(update node :steps (fn [steps]
                           (vec (mapcat #(expand-node % registry) steps))))]

    :background
    [(update node :steps (fn [steps]
                           (vec (mapcat #(expand-node % registry) steps))))]

    :rule
    [(update node :children (fn [children]
                              (mapcat #(expand-node % registry) children)))]

    :feature
    [(update node :children (fn [children]
                              (mapcat #(expand-node % registry) children)))]

    ;; Other nodes pass through unchanged
    [node]))

(defn expand-ast
  "Expand macro-step nodes in pre-ast using registry.
   (expand-ast pre-ast registry) → expanded-ast"
  [pre-ast registry]
  (mapcat #(expand-node % registry) pre-ast))