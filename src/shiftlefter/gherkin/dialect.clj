(ns shiftlefter.gherkin.dialect
  (:require [clojure.spec.alpha :as s]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Keyword type mappings (JSON key -> canonical keyword)
;; -----------------------------------------------------------------------------

(def ^:private json-key->canonical
  {"feature"         :feature
   "background"      :background
   "scenario"        :scenario
   "scenarioOutline" :scenario-outline
   "examples"        :examples
   "rule"            :rule
   "given"           :given
   "when"            :when
   "then"            :then
   "and"             :and
   "but"             :but})

;; -----------------------------------------------------------------------------
;; Load official dialects from i18n.json
;; -----------------------------------------------------------------------------

(defonce official-dialects-raw
  (delay
    (let [resource (io/resource "shiftlefter/gherkin/i18n.json")]
      (if-not resource
        (throw (ex-info "Missing shiftlefter/gherkin/i18n.json — fetch via curl" {}))
        (json/parse-stream (io/reader resource) true)))))

(defn- build-keyword-lookup
  "Build a list of [prefix canonical-keyword] pairs for a language.
   Sorted by prefix length descending for longest-match-first."
  [lang-data]
  (->> (for [[json-key prefixes] lang-data
             :let [canonical (get json-key->canonical (name json-key))]
             :when canonical
             prefix prefixes]
         [prefix canonical])
       (sort-by (comp - count first))
       vec))

(defonce official-dialects
  (delay
    (into {}
          (for [[lang data] @official-dialects-raw]
            [(name lang) (build-keyword-lookup data)]))))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn get-dialect
  "Return the keyword lookup list for a language code (e.g., 'fr', 'en', 'em').
   Each entry is [prefix canonical-keyword], sorted longest-first."
  [lang]
  (get @official-dialects lang))

(defn match-keyword
  "Try to match text against dialect keyword prefixes.
   Returns {:keyword :given :matched \"Soit \"} or nil if no match.
   For colon-keywords (Feature:, Scenario:), text should include the colon."
  [text dialect]
  (some (fn [[prefix canonical]]
          (when (str/starts-with? text prefix)
            {:keyword canonical :matched prefix}))
        dialect))

(defn match-step-keyword
  "Match step keywords (Given/When/Then/And/But/*).
   Returns {:keyword :given :matched \"Soit \" :text \"the rest\"} or nil.
   Step text is trimmed of trailing whitespace (Gherkin semantic behavior).
   Star keywords return :star (not the canonical keyword they matched against)."
  [text dialect]
  (let [step-keywords #{:given :when :then :and :but}]
    (some (fn [[prefix canonical]]
            (when (and (step-keywords canonical)
                       (str/starts-with? text prefix))
              ;; Star keyword gets special :star canonical, not :but/:and/etc
              (let [actual-keyword (if (str/starts-with? prefix "*") :star canonical)]
                {:keyword actual-keyword
                 :matched prefix
                 ;; Trim trailing whitespace - Cucumber does this semantically
                 :text (str/trimr (subs text (count prefix)))})))
          dialect)))

(defn match-block-keyword
  "Match block keywords (Feature/Background/Scenario/etc.) that end with colon.
   Text should be trimmed. Returns {:keyword :feature :matched \"Fonctionnalité\" :name \"...\"} or nil."
  [text dialect]
  (let [block-keywords #{:feature :background :scenario :scenario-outline :examples :rule}]
    (some (fn [[prefix canonical]]
            (when (and (block-keywords canonical)
                       (str/starts-with? text prefix))
              ;; Check for colon after keyword
              (let [after-kw (subs text (count prefix))]
                (when (str/starts-with? (str/triml after-kw) ":")
                  (let [after-colon (str/replace-first (str/triml after-kw) #"^:" "")]
                    {:keyword canonical
                     :matched (str/trim prefix)
                     :name (str/trim after-colon)})))))
          dialect)))

(defn language-exists?
  "Check if a language code is supported."
  [lang]
  (contains? @official-dialects lang))

;; -----------------------------------------------------------------------------
;; Legacy API (for backward compatibility)
;; -----------------------------------------------------------------------------

(def ^:dynamic *dialect*
  "Current dialect map. Bind or set! to override."
  nil)

(defn english-only-dialect
  "Minimal dialect with only English keywords."
  []
  {"en"
   {"feature"             :feature
    "business need"       :feature
    "ability"             :feature

    "background"          :background

    "scenario"            :scenario
    "example"             :example
    "scenario outline"    :scenario-outline

    "rule"                :rule

    "examples"            :examples
    "scenarios"           :examples

    "given"              :given
    "when"               :when
    "then"               :then
    "and"                :and
    "but"                :but

    "*"                   :step

    "# language:"         :language-header
    "@"                   :tag
    "\"\"\""              :docstring-separator
    "| "                  :table-row}})

(defn current-dialect
  "Return the active dialect (defaults to English-only for MVP)."
  []
  (or *dialect* (english-only-dialect)))

(defn keyword-for
  "Given a string and optional language, return canonical keyword.
   DEPRECATED: Use match-keyword with get-dialect instead."
  ([s] (keyword-for s :en))
  ([s lang]
   (get-in (current-dialect) [lang (str/lower-case s)])))

;; -----------------------------------------------------------------------------
;; Specs
;; -----------------------------------------------------------------------------

(s/def ::lang keyword?)
(s/def ::gherkin-string string?)
(s/def ::canonical-keyword
  #{:feature :background :scenario :example :scenario-outline :rule :examples
    :given :when :then :and :but :step
    :language-header :tag :docstring-separator :table-row})

(s/def ::dialect (s/map-of ::lang (s/map-of ::gherkin-string ::canonical-keyword)))

(s/fdef keyword-for
  :args (s/cat :s string? :lang (s/? keyword?))
  :ret (s/nilable ::canonical-keyword))
