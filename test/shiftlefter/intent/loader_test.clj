(ns shiftlefter.intent.loader-test
  (:require [clojure.test :refer [deftest is]]
            [shiftlefter.intent.loader :as loader]
            [babashka.fs :as fs]))

;; -----------------------------------------------------------------------------
;; Test Fixtures — Temp Directory with Intent Files
;; -----------------------------------------------------------------------------

(defn- create-temp-intents-dir
  "Create a temp directory with intent files for testing."
  [files]
  (let [dir (fs/create-temp-dir {:prefix "intents-test-"})]
    (doseq [[filename content] files]
      (spit (fs/file dir filename) content))
    dir))

(defn- cleanup-temp-dir
  "Remove temp directory and contents."
  [dir]
  (fs/delete-tree dir))

;; -----------------------------------------------------------------------------
;; Valid Intent Loading
;; -----------------------------------------------------------------------------

(deftest load-valid-intent-file
  (let [dir (create-temp-intents-dir
             {"login.edn"
              (pr-str {:intent "Login"
                       :description "User authentication"
                       :elements
                       {:email {:description "Email field"
                                :bindings {:web {:css "#email"}}}
                        :submit {:description "Submit button"
                                 :bindings {:web {:css "button[type=submit]"}
                                            :mobile {:accessibility-id "login-btn"}}}}})})]
    (try
      (let [result (loader/load-all-intents (str dir))]
        (is (:ok result) "Should load successfully")
        (is (= ["Login"] (:intents (:ok result))))
        (is (= {:css "#email"}
               (:ok (loader/get-binding (:ok result) "Login" "email" :web))))
        (is (= {:accessibility-id "login-btn"}
               (:ok (loader/get-binding (:ok result) "Login" "submit" :mobile)))))
      (finally
        (cleanup-temp-dir dir)))))

(deftest load-multiple-intent-files
  (let [dir (create-temp-intents-dir
             {"login.edn"
              (pr-str {:intent "Login"
                       :elements
                       {:submit {:bindings {:web {:css "#login-submit"}}}}})
              "checkout.edn"
              (pr-str {:intent "Checkout"
                       :elements
                       {:pay-button {:bindings {:web {:css "#pay"}}}}})})]
    (try
      (let [result (loader/load-all-intents (str dir))]
        (is (:ok result))
        (is (= 2 (count (:intents (:ok result)))))
        (is (loader/known-intent? (:ok result) "Login"))
        (is (loader/known-intent? (:ok result) "Checkout"))
        (is (loader/known-element? (:ok result) "Login" "submit"))
        (is (loader/known-element? (:ok result) "Checkout" "pay-button")))
      (finally
        (cleanup-temp-dir dir)))))

(deftest load-empty-directory
  (let [dir (fs/create-temp-dir {:prefix "intents-empty-"})]
    (try
      (let [result (loader/load-all-intents (str dir))]
        (is (:ok result))
        (is (empty? (:intents (:ok result))))
        (is (empty? (:lookup (:ok result)))))
      (finally
        (cleanup-temp-dir dir)))))

(deftest load-nonexistent-directory
  (let [result (loader/load-all-intents "/nonexistent/path/intents")]
    (is (:ok result) "Missing directory should be OK (intents optional)")
    (is (empty? (:intents (:ok result))))))

;; -----------------------------------------------------------------------------
;; Casing Validation
;; -----------------------------------------------------------------------------

(deftest reject-lowercase-intent-name
  (let [dir (create-temp-intents-dir
             {"bad.edn"
              (pr-str {:intent "login"  ;; should be "Login"
                       :elements
                       {:submit {:bindings {:web {:css "#submit"}}}}})})]
    (try
      (let [result (loader/load-all-intents (str dir))]
        (is (:errors result))
        (is (= :intent/invalid-name
               (-> result :errors first :type))))
      (finally
        (cleanup-temp-dir dir)))))

(deftest reject-uppercase-element-name
  (let [dir (create-temp-intents-dir
             {"bad.edn"
              (pr-str {:intent "Login"
                       :elements
                       {:Submit {:bindings {:web {:css "#submit"}}}}})})]  ;; should be :submit
    (try
      (let [result (loader/load-all-intents (str dir))]
        (is (:errors result))
        (is (= :intent/invalid-element-name
               (-> result :errors first :type))))
      (finally
        (cleanup-temp-dir dir)))))

(deftest accept-valid-casing-variants
  (let [dir (create-temp-intents-dir
             {"valid.edn"
              (pr-str {:intent "FrequentlyBoughtTogether"
                       :elements
                       {:add-to-cart {:bindings {:web {:css "#add"}}}
                        :item_image {:bindings {:web {:css ".img"}}}
                        :submit2 {:bindings {:web {:css "#s2"}}}}})})]
    (try
      (let [result (loader/load-all-intents (str dir))]
        (is (:ok result) (str "Should accept valid casing: " (:errors result))))
      (finally
        (cleanup-temp-dir dir)))))

;; -----------------------------------------------------------------------------
;; Structure Validation
;; -----------------------------------------------------------------------------

(deftest reject-missing-intent-key
  (let [dir (create-temp-intents-dir
             {"bad.edn"
              (pr-str {:elements {:submit {:bindings {:web {:css "#s"}}}}})})]
    (try
      (let [result (loader/load-all-intents (str dir))]
        (is (:errors result))
        (is (= :intent/missing-intent-key
               (-> result :errors first :type))))
      (finally
        (cleanup-temp-dir dir)))))

(deftest reject-missing-elements-key
  (let [dir (create-temp-intents-dir
             {"bad.edn"
              (pr-str {:intent "Login"})})]
    (try
      (let [result (loader/load-all-intents (str dir))]
        (is (:errors result))
        (is (= :intent/missing-elements
               (-> result :errors first :type))))
      (finally
        (cleanup-temp-dir dir)))))

(deftest reject-missing-bindings
  (let [dir (create-temp-intents-dir
             {"bad.edn"
              (pr-str {:intent "Login"
                       :elements
                       {:submit {:description "No bindings!"}}})})]
    (try
      (let [result (loader/load-all-intents (str dir))]
        (is (:errors result))
        (is (= :intent/missing-bindings
               (-> result :errors first :type))))
      (finally
        (cleanup-temp-dir dir)))))

(deftest reject-empty-bindings
  (let [dir (create-temp-intents-dir
             {"bad.edn"
              (pr-str {:intent "Login"
                       :elements
                       {:submit {:bindings {}}}})})]
    (try
      (let [result (loader/load-all-intents (str dir))]
        (is (:errors result))
        (is (= :intent/missing-bindings
               (-> result :errors first :type))))
      (finally
        (cleanup-temp-dir dir)))))

;; -----------------------------------------------------------------------------
;; Duplicate Detection
;; -----------------------------------------------------------------------------

(deftest reject-duplicate-intent-names
  (let [dir (create-temp-intents-dir
             {"login1.edn"
              (pr-str {:intent "Login"
                       :elements {:a {:bindings {:web {:css "#a"}}}}})
              "login2.edn"
              (pr-str {:intent "Login"  ;; duplicate!
                       :elements {:b {:bindings {:web {:css "#b"}}}}})})]
    (try
      (let [result (loader/load-all-intents (str dir))]
        (is (:errors result))
        (is (= :intent/duplicate-intent
               (-> result :errors first :type))))
      (finally
        (cleanup-temp-dir dir)))))

;; -----------------------------------------------------------------------------
;; Query Functions
;; -----------------------------------------------------------------------------

(deftest get-binding-errors
  (let [dir (create-temp-intents-dir
             {"login.edn"
              (pr-str {:intent "Login"
                       :elements
                       {:submit {:bindings {:web {:css "#submit"}}}}})})]
    (try
      (let [result (loader/load-all-intents (str dir))
            intents (:ok result)]
        ;; Unknown intent/element
        (let [err (loader/get-binding intents "Unknown" "submit" :web)]
          (is (:error err))
          (is (= :intent/unknown-element (-> err :error :type))))
        ;; Unknown element
        (let [err (loader/get-binding intents "Login" "unknown" :web)]
          (is (:error err))
          (is (= :intent/unknown-element (-> err :error :type))))
        ;; No binding for interface
        (let [err (loader/get-binding intents "Login" "submit" :mobile)]
          (is (:error err))
          (is (= :intent/no-binding-for-interface (-> err :error :type)))
          (is (= [:web] (-> err :error :available-interfaces)))))
      (finally
        (cleanup-temp-dir dir)))))

(deftest elements-of-intent-query
  (let [dir (create-temp-intents-dir
             {"login.edn"
              (pr-str {:intent "Login"
                       :elements
                       {:email {:bindings {:web {:css "#e"}}}
                        :password {:bindings {:web {:css "#p"}}}
                        :submit {:bindings {:web {:css "#s"}}}}})})]
    (try
      (let [result (loader/load-all-intents (str dir))
            intents (:ok result)
            elements (loader/elements-of-intent intents "Login")]
        (is (= 3 (count elements)))
        (is (every? #{"email" "password" "submit"} elements)))
      (finally
        (cleanup-temp-dir dir)))))

;; -----------------------------------------------------------------------------
;; EDN Parse Errors
;; -----------------------------------------------------------------------------

(deftest reject-invalid-edn
  (let [dir (create-temp-intents-dir
             {"bad.edn" "{:intent \"Login\" :elements {invalid edn here}}"})]
    (try
      (let [result (loader/load-all-intents (str dir))]
        (is (:errors result))
        (is (= :intent/parse-failed
               (-> result :errors first :type))))
      (finally
        (cleanup-temp-dir dir)))))

;; -----------------------------------------------------------------------------
;; Collection Flag
;; -----------------------------------------------------------------------------

(deftest collection-flag-preserved
  (let [dir (create-temp-intents-dir
             {"grades.edn"
              (pr-str {:intent "Grades"
                       :elements
                       {:assignment {:collection true
                                     :bindings {:web {:css ".assignment-row"}}}}})})]
    (try
      (let [result (loader/load-all-intents (str dir))
            intents (:ok result)
            entry (get (:lookup intents) ["Grades" "assignment"])]
        (is (:collection entry) "Collection flag should be preserved"))
      (finally
        (cleanup-temp-dir dir)))))
