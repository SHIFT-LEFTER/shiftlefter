(ns shiftlefter.sieve.web
  "Reference web SIEVE provider backed by resources/sieve.js.

   The capture path is live-browser, impure, and best-effort. The analysis path
   is deterministic over the saved web evidence payload."
  (:require [clojure.java.io :as io]
            [etaoin.api :as eta]
            [shiftlefter.sieve.contract :as contract]
            [shiftlefter.sieve.nesting :as nesting]
            [shiftlefter.sieve.provider :as provider]))

(def provider-id "shiftlefter.web/sieve-js")
(def provider-version "1")
(def web-evidence-schema :shiftlefter.sieve.web/evidence.v1)
(def web-analysis-inventory-schema :shiftlefter.sieve.web/analysis-inventory.v1)
(def web-candidate-schema :shiftlefter.sieve.web/candidate.v1)

(def ^:private sieve-js-path "sieve.js")

(defn- warning [type message]
  {:type type :message message})

(defn- try-value [f type message fallback]
  (try
    {:value (f) :warnings []}
    (catch RuntimeException e
      {:value fallback
       :warnings [(assoc (warning type message)
                         :exception (.getName (class e)))]})))

(defn execute-sieve-js
  "Run the current browser-injected web SIEVE and return provider inventory."
  [driver]
  (let [sieve-src (slurp (io/resource sieve-js-path))]
    (eta/js-execute driver (str "return " sieve-src))))

(defn- live-browser-inventory [driver]
  (let [inventory-result (try-value #(execute-sieve-js driver)
                                    :sieve.web/inventory-failed
                                    "Failed to execute browser SIEVE inventory."
                                    {})
        cookies-result (try-value #(->> (eta/get-cookies driver)
                                        (map :name)
                                        distinct
                                        vec)
                                  :sieve.web/cookies-unavailable
                                  "Failed to read browser cookie names."
                                  [])
        tabs-result (try-value #(count (eta/get-window-handles driver))
                               :sieve.web/tabs-unavailable
                               "Failed to count browser tabs."
                               1)]
    {:inventory (assoc (:value inventory-result)
                       :cookies (:value cookies-result)
                       :tabs (:value tabs-result))
     :warnings (vec (concat (:warnings inventory-result)
                            (:warnings cookies-result)
                            (:warnings tabs-result)))}))

(defn- live-browser-html [driver]
  (try-value #(eta/js-execute driver "return document.documentElement.outerHTML;")
             :sieve.web/html-unavailable
             "Failed to capture current document HTML."
             nil))

(defn- live-browser-url [driver inventory]
  (or (get-in inventory [:url :raw])
      (get-in inventory ["url" "raw"])
      (:value (try-value #(eta/get-url driver)
                         :sieve.web/url-unavailable
                         "Failed to read current browser URL."
                         nil))))

(defn- live-browser-title [driver inventory]
  (or (:title inventory)
      (get inventory "title")
      (:value (try-value #(eta/get-title driver)
                         :sieve.web/title-unavailable
                         "Failed to read current browser title."
                         nil))))

(defn- web-payload [driver inventory html]
  {:html html
   :url (or (:url inventory) (get inventory "url"))
   :title (live-browser-title driver inventory)
   :viewport (or (:viewport inventory) (get inventory "viewport"))
   :inventory inventory})

(defn capture-web-evidence
  "Capture a web Evidence Snapshot from a live Etaoin driver.

   This function is explicitly best-effort and impure. The browser/page may
   mutate during capture, and unavailable fields are reported as warnings."
  [{:keys [driver interface-name interface-type projection environment source]
    :or {interface-name :web
         interface-type :web
         environment {}
         source {}}}]
  (let [{:keys [inventory warnings]} (live-browser-inventory driver)
        html-result (live-browser-html driver)
        all-warnings (vec (concat warnings (:warnings html-result)))
        payload (web-payload driver inventory (:value html-result))]
    (contract/make-evidence-snapshot
      {:interface {:name interface-name :type interface-type}
       :source (merge {:kind :live-browser
                       :url (live-browser-url driver inventory)}
                      source)
       :capture {:captured-at (contract/now-iso)
                 :mechanism :etaoin-sieve-js
                 :best-effort? true
                 :deterministic? false
                 :warnings all-warnings}
       :environment environment
       :project (when projection (contract/projection-ref projection))
       :payload-schema web-evidence-schema
       :payload payload
       :warnings all-warnings})))

(defn- inventory-elements [snapshot]
  (or (get-in snapshot [:payload :inventory :elements])
      (get-in snapshot [:payload :inventory "elements"])
      []))

(defn- inventory-forms [snapshot]
  (or (get-in snapshot [:payload :inventory :forms])
      (get-in snapshot [:payload :inventory "forms"])
      []))

(defn- inventory-iframes [snapshot]
  (or (get-in snapshot [:payload :inventory :iframes])
      (get-in snapshot [:payload :inventory "iframes"])
      []))

(defn- element-field [element k]
  (or (get element k) (get element (name k))))

(defn- as-keyword [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else value))

(defn- candidate-id [snapshot idx element]
  (str "cand-" (subs (contract/fingerprint
                       {:evidence (:evidence/id snapshot)
                        :idx idx
                        :element element})
                     0 16)))

(defn- element-candidate [snapshot idx element]
  {:candidate/id (candidate-id snapshot idx element)
   ;; Intermediate containment carried for nesting/type-candidates, which sets
   ;; the final :kind / :parent / :children and strips :node (sl-wbn).
   :node {:index (element-field element :index)
          :parent-index (element-field element :parentIndex)
          :depth (element-field element :depth)
          :order (element-field element :order)
          :category (as-keyword (element-field element :category))
          :classes (vec (element-field element :classes))
          :tag (element-field element :tag)}
   :source {:analysis :web-inventory
            :element-index idx}
   :label (element-field element :label)
   :category (as-keyword (element-field element :category))
   :roles (vec (map as-keyword (or (element-field element :roles) [])))
   :confidence {:provider (or (as-keyword (element-field element :confidence))
                              :unknown)}
   :payload-schema web-candidate-schema
   :payload {:tag (element-field element :tag)
             :element-type (element-field element :elementType)
             :aria-role (element-field element :ariaRole)
             :region (element-field element :region)
             :form (element-field element :form)
             :state (or (element-field element :state) {})
             :locators (or (element-field element :locators) {})
             :rect (element-field element :rect)}})

(defn analyze-web-evidence
  "Analyze a saved web Evidence Snapshot without requiring a live browser."
  [snapshot {:keys [projection provider-version provider-config]
             :or {provider-version shiftlefter.sieve.web/provider-version
                  provider-config {}}}]
  (let [elements (vec (inventory-elements snapshot))
        candidates (nesting/type-candidates
                     (mapv #(element-candidate snapshot %1 %2)
                           (range)
                           elements))
        warnings (vec (:warnings snapshot))]
    (contract/make-analysis-result
      {:evidence (contract/evidence-ref snapshot)
       :provider (contract/provider-ref {:id provider-id
                                         :version provider-version
                                         :config provider-config})
       :projection (if projection
                     (contract/projection-ref projection)
                     (or (:project snapshot) {:fingerprint "unknown"}))
       :candidates candidates
       :provider-inventory {:payload-schema web-analysis-inventory-schema
                            :payload (or (get-in snapshot [:payload :inventory])
                                         (get-in snapshot [:payload "inventory"]))}
       :warnings warnings
       :confidence {:basis :provider-reported
                    :candidate-count (count candidates)}
       :alternatives []
       :completeness {:html-present? (boolean (get-in snapshot [:payload :html]))
                      :inventory-present? (map? (or (get-in snapshot [:payload :inventory])
                                                    (get-in snapshot [:payload "inventory"])))
                      :elements (count elements)
                      :forms (count (inventory-forms snapshot))
                      :iframes (count (inventory-iframes snapshot))
                      :live-capture? false}})))

(defn capture-and-analyze-web
  "Capture live web evidence and immediately analyze the saved snapshot value."
  [request]
  (let [snapshot (capture-web-evidence request)
        analysis (analyze-web-evidence snapshot
                                       {:projection (:projection request)
                                        :provider-version (or (:provider-version request)
                                                              provider-version)
                                        :provider-config (:provider-config request)})]
    {:evidence snapshot
     :analysis analysis}))

(defrecord WebSieveProvider []
  provider/ISieveProvider
  (capture-evidence [_ request]
    (capture-web-evidence request))
  (analyze-evidence [_ request]
    (analyze-web-evidence (:evidence request) request)))

(defn make-provider
  "Return the default web SIEVE provider."
  []
  (->WebSieveProvider))
