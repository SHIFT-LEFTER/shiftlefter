(ns shiftlefter.intent.resolve-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.intent.resolve :as resolve]
            [shiftlefter.intent.loader :as loader]
            [babashka.fs :as fs]))

;; -----------------------------------------------------------------------------
;; Parsing Tests
;; -----------------------------------------------------------------------------

(deftest parse-basic-reference
  (let [result (resolve/parse-intent-ref "Login.submit")]
    (is (:ok result))
    (is (= "Login" (-> result :ok :intent)))
    (is (= "submit" (-> result :ok :element)))
    (is (nil? (-> result :ok :index)))))

(deftest parse-reference-with-positive-index
  (testing "Index 1"
    (let [result (resolve/parse-intent-ref "Login.submit[1]")]
      (is (:ok result))
      (is (= 1 (-> result :ok :index)))))

  (testing "Large index"
    (let [result (resolve/parse-intent-ref "Grades.assignment[999]")]
      (is (:ok result))
      (is (= 999 (-> result :ok :index)))))

  (testing "Index 0"
    (let [result (resolve/parse-intent-ref "Test.item[0]")]
      (is (:ok result))
      (is (= 0 (-> result :ok :index))))))

(deftest parse-reference-with-negative-index
  (testing "Index -1 (last)"
    (let [result (resolve/parse-intent-ref "Login.item[-1]")]
      (is (:ok result))
      (is (= -1 (-> result :ok :index)))))

  (testing "Index -2 (second from last)"
    (let [result (resolve/parse-intent-ref "Login.item[-2]")]
      (is (:ok result))
      (is (= -2 (-> result :ok :index))))))

(deftest parse-reference-with-wildcard
  (let [result (resolve/parse-intent-ref "Grades.assignment[*]")]
    (is (:ok result))
    (is (= :all (-> result :ok :index)))))

(deftest parse-valid-naming-variants
  (testing "Multi-word PascalCase intent"
    (let [result (resolve/parse-intent-ref "FrequentlyBoughtTogether.item")]
      (is (:ok result))
      (is (= "FrequentlyBoughtTogether" (-> result :ok :intent)))))

  (testing "Intent with numbers"
    (let [result (resolve/parse-intent-ref "Page2.submit")]
      (is (:ok result))
      (is (= "Page2" (-> result :ok :intent)))))

  (testing "Element with hyphens"
    (let [result (resolve/parse-intent-ref "Login.submit-button")]
      (is (:ok result))
      (is (= "submit-button" (-> result :ok :element)))))

  (testing "Element with underscores"
    (let [result (resolve/parse-intent-ref "Login.submit_btn")]
      (is (:ok result))
      (is (= "submit_btn" (-> result :ok :element)))))

  (testing "Element with numbers"
    (let [result (resolve/parse-intent-ref "Login.field2")]
      (is (:ok result))
      (is (= "field2" (-> result :ok :element)))))

  (testing "Intent with hyphen"
    (let [result (resolve/parse-intent-ref "Buy-Box.submit")]
      (is (:ok result))
      (is (= "Buy-Box" (-> result :ok :intent)))))

  (testing "Intent with underscore"
    (let [result (resolve/parse-intent-ref "Buy_Box.submit")]
      (is (:ok result))
      (is (= "Buy_Box" (-> result :ok :intent))))))

;; -----------------------------------------------------------------------------
;; Parse Rejection Tests
;; -----------------------------------------------------------------------------

(deftest reject-lowercase-intent
  (let [result (resolve/parse-intent-ref "login.submit")]
    (is (:error result))
    (is (= :intent/invalid-reference (-> result :error :type)))))

(deftest reject-uppercase-element
  (let [result (resolve/parse-intent-ref "Login.Submit")]
    (is (:error result))
    (is (= :intent/invalid-reference (-> result :error :type)))))

(deftest reject-no-element
  (let [result (resolve/parse-intent-ref "Login")]
    (is (:error result))
    (is (= :intent/invalid-reference (-> result :error :type)))))

(deftest reject-no-intent
  (let [result (resolve/parse-intent-ref ".submit")]
    (is (:error result))
    (is (= :intent/invalid-reference (-> result :error :type)))))

(deftest reject-empty-index
  (let [result (resolve/parse-intent-ref "Login.submit[]")]
    (is (:error result))
    (is (= :intent/invalid-reference (-> result :error :type)))))

(deftest reject-non-numeric-index
  (let [result (resolve/parse-intent-ref "Login.submit[abc]")]
    (is (:error result))
    (is (= :intent/invalid-reference (-> result :error :type)))))

(deftest reject-raw-edn
  (testing "Map locator should not parse as intent ref"
    (let [result (resolve/parse-intent-ref "{:css \"#submit\"}")]
      (is (:error result)))))

(deftest reject-empty-string
  (let [result (resolve/parse-intent-ref "")]
    (is (:error result))))

(deftest reject-whitespace
  (let [result (resolve/parse-intent-ref "  ")]
    (is (:error result))))

;; -----------------------------------------------------------------------------
;; intent-ref? Detection
;; -----------------------------------------------------------------------------

(deftest intent-ref-detection
  (testing "Intent references"
    (is (resolve/intent-ref? "Login.submit"))
    (is (resolve/intent-ref? "Login.submit[1]")))

  (testing "Raw EDN (not intent refs)"
    (is (not (resolve/intent-ref? "{:css \"#foo\"}")))
    (is (not (resolve/intent-ref? "{:id \"login\"}"))))

  (testing "Edge cases"
    (is (not (resolve/intent-ref? "")))
    (is (not (resolve/intent-ref? nil)))
    (is (not (resolve/intent-ref? "   ")))))

;; -----------------------------------------------------------------------------
;; CSS Index Application
;; -----------------------------------------------------------------------------

(deftest apply-css-index-positive
  (let [intents {:lookup {["Test" "item"] {:bindings {:web {:css ".item"}}}}}]
    (testing "Index 1"
      (let [result (resolve/resolve-intent-string intents "Test.item[1]" :web)]
        (is (:ok result))
        (is (= {:css ".item:nth-child(1)"} (:ok result)))))

    (testing "Index 5"
      (let [result (resolve/resolve-intent-string intents "Test.item[5]" :web)]
        (is (:ok result))
        (is (= {:css ".item:nth-child(5)"} (:ok result)))))))

(deftest apply-css-index-negative
  (let [intents {:lookup {["Test" "item"] {:bindings {:web {:css ".item"}}}}}]
    (testing "Index -1 (last)"
      (let [result (resolve/resolve-intent-string intents "Test.item[-1]" :web)]
        (is (:ok result))
        (is (= {:css ".item:nth-last-child(1)"} (:ok result)))))

    (testing "Index -2 (second from last)"
      (let [result (resolve/resolve-intent-string intents "Test.item[-2]" :web)]
        (is (:ok result))
        (is (= {:css ".item:nth-last-child(2)"} (:ok result)))))))

(deftest apply-css-index-wildcard
  (let [intents {:lookup {["Test" "item"] {:bindings {:web {:css ".item"}}}}}
        result (resolve/resolve-intent-string intents "Test.item[*]" :web)]
    (is (:ok result))
    (is (= {:css ".item"} (:ok result)) "Wildcard should not modify selector")))

(deftest apply-css-index-none
  (let [intents {:lookup {["Test" "item"] {:bindings {:web {:css ".item"}}}}}
        result (resolve/resolve-intent-string intents "Test.item" :web)]
    (is (:ok result))
    (is (= {:css ".item"} (:ok result)) "No index should not modify selector")))

;; -----------------------------------------------------------------------------
;; XPath Index Application
;; -----------------------------------------------------------------------------

(deftest apply-xpath-index-positive
  (let [intents {:lookup {["Test" "item"] {:bindings {:web {:xpath "//div[@class='item']"}}}}}
        result (resolve/resolve-intent-string intents "Test.item[1]" :web)]
    (is (:ok result))
    (is (= {:xpath "(//div[@class='item'])[1]"} (:ok result)))))

(deftest apply-xpath-index-negative
  (let [intents {:lookup {["Test" "item"] {:bindings {:web {:xpath "//div[@class='item']"}}}}}]
    (testing "Last item"
      (let [result (resolve/resolve-intent-string intents "Test.item[-1]" :web)]
        (is (:ok result))
        (is (= {:xpath "(//div[@class='item'])[last()]"} (:ok result)))))

    (testing "Second from last"
      (let [result (resolve/resolve-intent-string intents "Test.item[-2]" :web)]
        (is (:ok result))
        (is (= {:xpath "(//div[@class='item'])[last()-1]"} (:ok result)))))))

;; -----------------------------------------------------------------------------
;; Resolution Error Cases
;; -----------------------------------------------------------------------------

(deftest resolve-unknown-intent
  (let [intents {:lookup {}}
        result (resolve/resolve-intent-string intents "Unknown.submit" :web)]
    (is (:error result))
    (is (= :intent/unknown-element (-> result :error :type)))))

(deftest resolve-unknown-element
  (let [intents {:lookup {["Login" "submit"] {:bindings {:web {:css "#s"}}}}}
        result (resolve/resolve-intent-string intents "Login.unknown" :web)]
    (is (:error result))
    (is (= :intent/unknown-element (-> result :error :type)))))

(deftest resolve-missing-interface-binding
  (let [intents {:lookup {["Login" "submit"] {:bindings {:web {:css "#s"}}}}}
        result (resolve/resolve-intent-string intents "Login.submit" :mobile)]
    (is (:error result))
    (is (= :intent/no-binding-for-interface (-> result :error :type)))))

;; -----------------------------------------------------------------------------
;; Full Integration with Loader
;; -----------------------------------------------------------------------------

(defn- create-temp-intents-dir [files]
  (let [dir (fs/create-temp-dir {:prefix "intents-resolve-test-"})]
    (doseq [[filename content] files]
      (spit (fs/file dir filename) content))
    dir))

(defn- cleanup-temp-dir [dir]
  (fs/delete-tree dir))

(deftest full-integration-test
  (let [dir (create-temp-intents-dir
             {"login.edn"
              (pr-str {:intent "Login"
                       :elements
                       {:email {:bindings {:web {:css "#email"}
                                           :mobile {:accessibility-id "email-field"}}}
                        :submit {:bindings {:web {:css "button.submit"}}}}})})]
    (try
      (let [load-result (loader/load-all-intents (str dir))]
        (is (:ok load-result) "Should load successfully")
        (let [intents (:ok load-result)]

          (testing "Basic resolution"
            (let [result (resolve/resolve-intent-string intents "Login.email" :web)]
              (is (= {:css "#email"} (:ok result)))))

          (testing "Mobile interface"
            (let [result (resolve/resolve-intent-string intents "Login.email" :mobile)]
              (is (= {:accessibility-id "email-field"} (:ok result)))))

          (testing "With index"
            (let [result (resolve/resolve-intent-string intents "Login.submit[2]" :web)]
              (is (= {:css "button.submit:nth-child(2)"} (:ok result)))))))
      (finally
        (cleanup-temp-dir dir)))))
