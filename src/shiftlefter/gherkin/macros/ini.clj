(ns shiftlefter.gherkin.macros.ini
  "INI-style macro parser for non-technical users."
  (:require [clojure.string :as str]
            [shiftlefter.gherkin.io :as io]))

(defrecord Location [line col])

(defrecord Step [keyword text location])

(defrecord Macro [name description steps])

(defrecord ValidationError [message location])

;; (defn- trim-leading [s]
;;  (str/replace s #"^[ \t]+" ""))

(defn- parse-step [line line-num]
  (let [trimmed (str/trim line)
        indent (count (re-find #"^[ \t]*" line))]
    (if (zero? indent)
      {:error (ValidationError. "Step line has no indentation" (Location. line-num 1))}
      (if-let [[_ keyword text] (re-matches #"^(Given|When|Then|And|But|\*) (.+)" trimmed)]
        {:step (Step. keyword text (Location. line-num (inc indent)))}
        {:error (ValidationError. (str "Invalid step line: '" trimmed "'") (Location. line-num (inc indent)))}))))

(defn- validate-steps-indent [step-lines]
  (let [indents (map #(count (re-find #"^[ \t]*" %)) step-lines)]
    (if (apply = indents)
      (if (> (first indents) 0)
        nil
        "All step lines must be indented")
      "Inconsistent indentation in steps")))

(defn parse-macro
  "Given a file path and macro name, return validated macro or errors."
  [file-path macro-name]
  (try
    (let [lines (str/split (io/slurp-utf8 (str file-path)) #"\n")
          start-idx (first (keep-indexed #(when (str/starts-with? %2 (str "name = " macro-name)) %1) lines))]
      (if-not start-idx
        {:macro nil :errors [(ValidationError. (str "Macro '" macro-name "' not found") nil)]}
        (let [next-idx (or (first (for [i (range (inc start-idx) (count lines))
                                         :when (str/starts-with? (nth lines i) "name = ")]
                                     i))
                             (count lines))
              macro-lines (take (- next-idx start-idx) (drop start-idx lines))
              macro-lines (rest macro-lines) ; skip the name line
              description-line (first (filter #(str/starts-with? % "description =") macro-lines))
              description (when description-line (str/trim (subs description-line (count "description ="))))
              steps-idx (first (keep-indexed #(when (str/starts-with? %2 "steps =") %1) macro-lines))
              steps-lines (when steps-idx (drop (inc steps-idx) macro-lines))
              steps-lines (take-while #(not (str/blank? %)) steps-lines) ; until blank or end
              steps-lines (remove str/blank? steps-lines)
              indent-error (when (seq steps-lines) (validate-steps-indent steps-lines))]
          (if indent-error
            {:macro nil :errors [(ValidationError. indent-error nil)]}
            (let [parsed-steps (map-indexed #(parse-step %2 (inc (+ start-idx %1))) steps-lines)
                  errors (keep :error parsed-steps)
                  steps (keep :step parsed-steps)]
              (if (and description (seq steps-lines) (empty? errors))
                {:macro (Macro. macro-name description steps) :errors []}
                {:macro nil :errors (cond-> []
                                             (not description) (conj (ValidationError. "Missing description" nil))
                                             (not (seq steps-lines)) (conj (ValidationError. "Missing steps block" nil))
                                             (seq errors) (into errors))}))))))
    (catch Exception e
      {:macro nil :errors [(ValidationError. (.getMessage e) nil)]})))