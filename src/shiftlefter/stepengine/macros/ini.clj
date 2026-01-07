(ns shiftlefter.stepengine.macros.ini
  "INI-style macro parser for ShiftLefter.

   Parses macro definitions from INI files. Each macro has:
   - name = <macro name>
   - description = <description>
   - steps =
       Given step text
       When another step
       Then final step

   Uses standard Location [line column] from shiftlefter.gherkin.location."
  (:require [clojure.string :as str]
            [shiftlefter.gherkin.io :as io]
            [shiftlefter.gherkin.location :as loc]))

;; -----------------------------------------------------------------------------
;; Step Parsing
;; -----------------------------------------------------------------------------

(defn- parse-step
  "Parse a single step line.
   Returns {:step {...}} or {:error {...}}."
  [line line-num]
  (let [trimmed (str/trim line)
        indent (count (re-find #"^[ \t]*" line))]
    (if (zero? indent)
      {:error {:message "Step line has no indentation"
               :location (loc/->Location line-num 1)}}
      (if-let [[_ keyword text] (re-matches #"^(Given|When|Then|And|But|\*) (.+)" trimmed)]
        {:step {:step/keyword keyword
                :step/text text
                :step/location (loc/->Location line-num (inc indent))}}
        {:error {:message (str "Invalid step line: '" trimmed "'")
                 :location (loc/->Location line-num (inc indent))}}))))

(defn- validate-steps-indent
  "Check that all step lines have consistent indentation.
   Returns nil if valid, error message string if invalid."
  [step-lines]
  (let [indents (map #(count (re-find #"^[ \t]*" %)) step-lines)]
    (cond
      (not (apply = indents)) "Inconsistent indentation in steps"
      (<= (first indents) 0) "All step lines must be indented"
      :else nil)))

;; -----------------------------------------------------------------------------
;; Macro Parsing
;; -----------------------------------------------------------------------------

(defn- find-macro-blocks
  "Find all macro block start indices in lines.
   Returns seq of [start-idx macro-name] pairs."
  [lines]
  (keep-indexed
   (fn [idx line]
     (when (str/starts-with? line "name = ")
       [idx (str/trim (subs line (count "name = ")))]))
   lines))

(defn- extract-macro-lines
  "Extract lines for a single macro block.
   Returns the lines from start-idx until the next 'name =' or EOF."
  [lines start-idx]
  (let [remaining (drop start-idx lines)
        next-macro-offset (first (keep-indexed
                                  (fn [idx line]
                                    (when (and (> idx 0) (str/starts-with? line "name = "))
                                      idx))
                                  remaining))]
    (if next-macro-offset
      (take next-macro-offset remaining)
      remaining)))

(defn- parse-macro-block
  "Parse a single macro block from its lines.
   Returns {:macro {...}} or {:error {...}}."
  [lines start-idx macro-name file-path]
  (let [macro-lines (rest (extract-macro-lines lines start-idx)) ; skip name line
        ;; Extract description
        description-line (first (filter #(str/starts-with? % "description =") macro-lines))
        description (when description-line
                      (str/trim (subs description-line (count "description ="))))
        ;; Extract steps block
        steps-idx (first (keep-indexed
                          (fn [idx line]
                            (when (str/starts-with? line "steps =") idx))
                          macro-lines))
        steps-lines (when steps-idx
                      (->> (drop (inc steps-idx) macro-lines)
                           (take-while #(not (str/blank? %)))
                           (remove str/blank?)))
        ;; Validate indentation
        indent-error (when (seq steps-lines)
                       (validate-steps-indent steps-lines))]
    (cond
      indent-error
      {:error {:type :macro/invalid-steps
               :message indent-error
               :macro-name macro-name
               :file file-path}}

      (not description)
      {:error {:type :macro/missing-description
               :message (str "Missing description for macro '" macro-name "'")
               :macro-name macro-name
               :file file-path}}

      (not (seq steps-lines))
      {:error {:type :macro/missing-steps
               :message (str "Missing steps block for macro '" macro-name "'")
               :macro-name macro-name
               :file file-path}}

      :else
      (let [parsed-steps (map-indexed
                          (fn [idx line]
                            (parse-step line (+ start-idx idx 2))) ; +2 for name line offset
                          steps-lines)
            errors (keep :error parsed-steps)
            steps (keep :step parsed-steps)]
        (if (seq errors)
          {:error {:type :macro/invalid-steps
                   :message (str "Step parsing errors in macro '" macro-name "'")
                   :macro-name macro-name
                   :file file-path
                   :step-errors (vec errors)}}
          {:macro {:macro/key macro-name
                   :macro/definition {:file file-path
                                      :location (loc/->Location (inc start-idx) 1)}
                   :macro/description description
                   :macro/steps (vec steps)}})))))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn parse-file
  "Parse all macros from an INI file.

   Returns:
   {:macros [{:macro/key ... :macro/definition ... :macro/steps ...} ...]
    :errors [{:type ... :message ... :file ...} ...]}"
  [file-path]
  (try
    (let [content (io/slurp-utf8 (str file-path))]
      (if (:status content)
        ;; slurp-utf8 returned an error
        {:macros []
         :errors [{:type :macro/file-read-error
                   :message (:message content)
                   :file file-path}]}
        (let [lines (str/split content #"\n")
              macro-blocks (find-macro-blocks lines)
              results (map (fn [[start-idx macro-name]]
                             (parse-macro-block lines start-idx macro-name file-path))
                           macro-blocks)]
          {:macros (vec (keep :macro results))
           :errors (vec (keep :error results))})))
    (catch Exception e
      {:macros []
       :errors [{:type :macro/file-read-error
                 :message (ex-message e)
                 :file file-path}]})))
