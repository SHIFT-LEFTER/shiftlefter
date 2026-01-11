(ns shiftlefter.svo.validate
  "SVO validation against glossaries and configuration.

   ## Validation Process

   Given an extracted SVOI map:
   ```clojure
   {:subject :alice
    :verb :click
    :object \"the button\"
    :interface :web}
   ```

   Validates:
   1. Subject is known in glossary
   2. Verb is known for the interface type
   3. Interface is defined in config

   Returns:
   ```clojure
   {:valid? true :issues []}
   ;; or
   {:valid? false
    :issues [{:type :svo/unknown-subject
              :subject :alcie
              :known [:alice :admin]
              :suggestion :alice}]}
   ```

   ## Usage

   ```clojure
   (validate-svoi glossaries interfaces svoi)
   ```"
  (:require [shiftlefter.svo.glossary :as glossary]))

;; -----------------------------------------------------------------------------
;; Levenshtein Distance (for typo suggestions)
;; -----------------------------------------------------------------------------

(defn- levenshtein
  "Compute Levenshtein edit distance between two strings."
  [s1 s2]
  (let [len1 (count s1)
        len2 (count s2)]
    (cond
      (zero? len1) len2
      (zero? len2) len1
      :else
      (let [cost-fn (fn [i j]
                      (if (= (nth s1 (dec i)) (nth s2 (dec j))) 0 1))
            matrix (vec (for [i (range (inc len1))]
                          (vec (for [j (range (inc len2))]
                                 (cond
                                   (zero? i) j
                                   (zero? j) i
                                   :else 0)))))
            fill-cell (fn [m i j]
                        (assoc-in m [i j]
                                  (min (inc (get-in m [(dec i) j]))
                                       (inc (get-in m [i (dec j)]))
                                       (+ (get-in m [(dec i) (dec j)])
                                          (cost-fn i j)))))]
        (get-in (reduce (fn [m [i j]]
                          (fill-cell m i j))
                        matrix
                        (for [i (range 1 (inc len1))
                              j (range 1 (inc len2))]
                          [i j]))
                [len1 len2])))))

(defn- suggest-similar
  "Find the most similar keyword from candidates.
   Returns the best match if distance <= max-distance, else nil."
  [target candidates max-distance]
  (when (and target (seq candidates))
    (let [target-str (name target)
          scored (map (fn [kw]
                        {:keyword kw
                         :distance (levenshtein target-str (name kw))})
                      candidates)
          best (apply min-key :distance scored)]
      (when (<= (:distance best) max-distance)
        (:keyword best)))))

;; -----------------------------------------------------------------------------
;; Validation Functions
;; -----------------------------------------------------------------------------

(defn- validate-subject
  "Validate subject against glossary.
   Returns issue map or nil if valid."
  [glossary subject]
  (when subject
    (when-not (glossary/known-subject? glossary subject)
      (let [known (glossary/known-subjects glossary)
            suggestion (suggest-similar subject known 2)]
        {:type :svo/unknown-subject
         :subject subject
         :known known
         :suggestion suggestion}))))

(defn- validate-verb
  "Validate verb against glossary for interface type.
   Returns issue map or nil if valid."
  [glossary interface-type verb]
  (when verb
    (when-not (glossary/known-verb? glossary interface-type verb)
      (let [known (glossary/known-verbs glossary interface-type)
            suggestion (suggest-similar verb known 2)]
        {:type :svo/unknown-verb
         :verb verb
         :interface-type interface-type
         :known known
         :suggestion suggestion}))))

(defn- validate-interface
  "Validate interface against config.
   Returns issue map or nil if valid."
  [interfaces interface-name]
  (when interface-name
    (when-not (contains? interfaces interface-name)
      (let [known (vec (keys interfaces))
            suggestion (suggest-similar interface-name known 2)]
        {:type :svo/unknown-interface
         :interface interface-name
         :known known
         :suggestion suggestion}))))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn validate-svoi
  "Validate an SVOI map against glossaries and interface config.

   Parameters:
   - glossary: Loaded glossary from `svo.glossary/load-all-glossaries`
   - interfaces: Interface config map from `config/get-interfaces`
   - svoi: Extracted SVOI map {:subject :verb :object :interface}

   Returns:
   - {:valid? true :issues []} if all checks pass
   - {:valid? false :issues [...]} if any check fails

   Issue types:
   - :svo/unknown-subject — subject not in glossary
   - :svo/unknown-verb — verb not known for interface type
   - :svo/unknown-interface — interface not defined in config

   Each issue includes :known (alternatives) and :suggestion (typo fix)."
  [glossary interfaces svoi]
  (let [{:keys [subject verb interface]} svoi
        ;; Look up interface type from config
        interface-def (get interfaces interface)
        interface-type (:type interface-def)
        ;; Collect issues
        issues (keep identity
                     [(validate-subject glossary subject)
                      ;; Only validate verb if interface is known
                      (when interface-type
                        (validate-verb glossary interface-type verb))
                      (validate-interface interfaces interface)])]
    {:valid? (empty? issues)
     :issues (vec issues)}))

(defn valid?
  "Returns true if SVOI is valid according to validate-svoi."
  [glossary interfaces svoi]
  (:valid? (validate-svoi glossary interfaces svoi)))

;; -----------------------------------------------------------------------------
;; Error Message Formatting
;; -----------------------------------------------------------------------------

(defn- format-known-list
  "Format a list of known values for display.
   Shows first N items, indicates if more exist."
  [known max-display]
  (let [items (take max-display known)
        remaining (- (count known) max-display)
        formatted (clojure.string/join ", " (map #(str ":" (name %)) items))]
    (if (pos? remaining)
      (str formatted ", ... (+" remaining " more)")
      formatted)))

(defn- format-suggestion
  "Format a typo suggestion if present."
  [suggestion]
  (when suggestion
    (str "Did you mean: :" (name suggestion) "?")))

(defn format-unknown-subject
  "Format an :svo/unknown-subject issue as a human-readable error message.

   Example output:
   ERROR: Unknown subject :alcie in step \"When Alcie clicks the button\"
          at features/login.feature:12
          Known subjects: :alice, :admin, :guest, :system/test-setup
          Did you mean: :alice?"
  [issue]
  (let [{:keys [subject known suggestion location]} issue
        {:keys [step-text uri line]} location]
    (str "Unknown subject :" (name subject)
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         (when (seq known)
           (str "\n       Known subjects: " (format-known-list known 8)))
         (when suggestion
           (str "\n       " (format-suggestion suggestion))))))

(defn format-unknown-verb
  "Format an :svo/unknown-verb issue as a human-readable error message.

   Example output:
   ERROR: Unknown verb :smash in step \"When Alice smashes the button\"
          at features/login.feature:15
          Interface :web (type :web)
          Known verbs for :web: :click, :fill, :see, :navigate, :submit"
  [issue]
  (let [{:keys [verb interface-type known suggestion location interface-name]} issue
        {:keys [step-text uri line]} location]
    (str "Unknown verb :" (name verb)
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         (when interface-name
           (str "\n       Interface :" (name interface-name)
                (when interface-type (str " (type :" (name interface-type) ")"))))
         (when (seq known)
           (str "\n       Known verbs"
                (when interface-type (str " for :" (name interface-type)))
                ": " (format-known-list known 8)))
         (when suggestion
           (str "\n       " (format-suggestion suggestion))))))

(defn format-unknown-interface
  "Format an :svo/unknown-interface issue as a human-readable error message.

   Example output:
   ERROR: Unknown interface :foobar in step \"When Alice clicks the button\"
          at features/login.feature:18
          Configured interfaces: :web, :api
          Add to shiftlefter.edn: {:interfaces {:foobar {:type ... :adapter ...}}}"
  [issue]
  (let [{:keys [interface known suggestion location]} issue
        {:keys [step-text uri line]} location]
    (str "Unknown interface :" (name interface)
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         (when (seq known)
           (str "\n       Configured interfaces: " (format-known-list known 8)))
         (when suggestion
           (str "\n       " (format-suggestion suggestion)))
         (str "\n       Add to shiftlefter.edn: {:interfaces {:" (name interface)
              " {:type ... :adapter ...}}}"))))

(defn format-provisioning-failed
  "Format an :svo/provisioning-failed issue as a human-readable error message.

   Example output:
   ERROR: Provisioning failed for interface :web
          at features/login.feature:10
          Adapter :etaoin error: Browser not installed
          Configured interfaces: :web, :api"
  [issue]
  (let [{:keys [interface adapter adapter-error message known location]} issue
        {:keys [step-text uri line]} location]
    (str "Provisioning failed for interface :" (name interface)
         (when step-text (str " in step \"" step-text "\""))
         (when (and uri line) (str "\n       at " uri ":" line))
         (cond
           adapter-error (str "\n       Adapter :" (name adapter) " error: " adapter-error)
           message (str "\n       " message))
         (when (seq known)
           (str "\n       Configured interfaces: " (format-known-list known 8))))))

(defn format-svo-issue
  "Format any SVO issue by dispatching on :type.

   Supported types:
   - :svo/unknown-subject
   - :svo/unknown-verb
   - :svo/unknown-interface
   - :svo/provisioning-failed

   Returns formatted string or generic fallback for unknown types."
  [issue]
  (case (:type issue)
    :svo/unknown-subject (format-unknown-subject issue)
    :svo/unknown-verb (format-unknown-verb issue)
    :svo/unknown-interface (format-unknown-interface issue)
    :svo/provisioning-failed (format-provisioning-failed issue)
    ;; Fallback for unknown types
    (str "SVO issue: " (pr-str issue))))
