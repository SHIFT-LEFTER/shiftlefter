(ns shiftlefter.intent.resolve-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.intent.resolve :as resolve]
            [shiftlefter.intent.loader :as loader]
            [babashka.fs :as fs]))

;; -----------------------------------------------------------------------------
;; Parsing Tests
;; -----------------------------------------------------------------------------

;; Parser output shape (sl-tl9): {:intent <name> :path [{:name :index} ...] :raw}.
;; Flat refs are a single-segment path; nested refs carry collection hops.

(defn- only-seg
  "The sole segment of a flat reference's parsed :path."
  [result]
  (-> result :ok :path first))

(deftest parse-basic-reference
  (let [result (resolve/parse-intent-ref "Login.submit")]
    (is (:ok result))
    (is (= "Login" (-> result :ok :intent)))
    (is (= [{:name "submit" :index nil}] (-> result :ok :path)))
    (is (= "Login.submit" (-> result :ok :raw)))))

(deftest parse-reference-with-positive-index
  (testing "Index 1"
    (let [result (resolve/parse-intent-ref "Login.submit[1]")]
      (is (:ok result))
      (is (= 1 (:index (only-seg result))))))

  (testing "Large index"
    (let [result (resolve/parse-intent-ref "Grades.assignment[999]")]
      (is (:ok result))
      (is (= 999 (:index (only-seg result))))))

  (testing "Index 0 (parses; rejected as a HARD error at resolution)"
    (let [result (resolve/parse-intent-ref "Test.item[0]")]
      (is (:ok result))
      (is (= 0 (:index (only-seg result)))))))

(deftest parse-reference-with-negative-index
  (testing "Index -1 (last)"
    (let [result (resolve/parse-intent-ref "Login.item[-1]")]
      (is (:ok result))
      (is (= -1 (:index (only-seg result))))))

  (testing "Index -2 (second from last)"
    (let [result (resolve/parse-intent-ref "Login.item[-2]")]
      (is (:ok result))
      (is (= -2 (:index (only-seg result)))))))

(deftest parse-reference-with-wildcard
  (let [result (resolve/parse-intent-ref "Grades.assignment[*]")]
    (is (:ok result))
    (is (= :all (:index (only-seg result))))))

(deftest parse-nested-reference
  (testing "Collection hops + element, with a mid-path index"
    (let [result (resolve/parse-intent-ref "Bookmarks.tweet[2].quoted.author")]
      (is (:ok result))
      (is (= "Bookmarks" (-> result :ok :intent)))
      (is (= [{:name "tweet" :index 2}
              {:name "quoted" :index nil}
              {:name "author" :index nil}]
             (-> result :ok :path)))))

  (testing "Terminal collection reference (Nth instance)"
    (let [result (resolve/parse-intent-ref "Bookmarks.tweet[2]")]
      (is (:ok result))
      (is (= [{:name "tweet" :index 2}] (-> result :ok :path)))))

  (testing "Single [*] anywhere in the path is allowed"
    (is (:ok (resolve/parse-intent-ref "Bookmarks.tweet[*].author")))
    (is (:ok (resolve/parse-intent-ref "Bookmarks.tweet[*]")))))

(deftest reject-multiple-wildcards
  (testing "More than one [*] is the single-[*] MVP boundary error"
    (let [result (resolve/parse-intent-ref "Grid.row[*].cell[*].value")]
      (is (:error result))
      (is (= :intent/multiple-wildcards (-> result :error :type))))))

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
      (is (= "submit-button" (:name (only-seg result))))))

  (testing "Element with underscores"
    (let [result (resolve/parse-intent-ref "Login.submit_btn")]
      (is (:ok result))
      (is (= "submit_btn" (:name (only-seg result))))))

  (testing "Element with numbers"
    (let [result (resolve/parse-intent-ref "Login.field2")]
      (is (:ok result))
      (is (= "field2" (:name (only-seg result))))))

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
;; Indexing is NOT applied at resolution (sl-nrv)
;; -----------------------------------------------------------------------------
;;
;; The resolver returns the BASE binding for every reference, regardless of
;; index. Index application moved to the browser boundary (the Nth *match* via
;; `IBrowser/query-all` — see shiftlefter.browser.intent-test), which is also
;; the fix for the latent `:nth-child` bug. These tests pin that contract: the
;; resolver never rewrites the selector.

(deftest css-index-not-applied-at-resolution
  (let [intents {:lookup {["Test" "item"] {:bindings {:web {:css ".item"}}}}}]
    (testing "positive index returns the base selector (no :nth-child)"
      (is (= {:css ".item"} (:ok (resolve/resolve-intent-string intents "Test.item[1]" :web))))
      (is (= {:css ".item"} (:ok (resolve/resolve-intent-string intents "Test.item[5]" :web)))))
    (testing "negative index returns the base selector (no :nth-last-child)"
      (is (= {:css ".item"} (:ok (resolve/resolve-intent-string intents "Test.item[-1]" :web))))
      (is (= {:css ".item"} (:ok (resolve/resolve-intent-string intents "Test.item[-2]" :web)))))
    (testing "wildcard and no-index also return the base selector"
      (is (= {:css ".item"} (:ok (resolve/resolve-intent-string intents "Test.item[*]" :web))))
      (is (= {:css ".item"} (:ok (resolve/resolve-intent-string intents "Test.item" :web)))))))

(deftest xpath-index-not-applied-at-resolution
  (let [intents {:lookup {["Test" "item"] {:bindings {:web {:xpath "//div[@class='item']"}}}}}
        base {:xpath "//div[@class='item']"}]
    (is (= base (:ok (resolve/resolve-intent-string intents "Test.item[1]" :web))))
    (is (= base (:ok (resolve/resolve-intent-string intents "Test.item[-1]" :web))))
    (is (= base (:ok (resolve/resolve-intent-string intents "Test.item[-2]" :web))))
    (is (= base (:ok (resolve/resolve-intent-string intents "Test.item[*]" :web))))
    (is (= base (:ok (resolve/resolve-intent-string intents "Test.item" :web))))))

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

          (testing "With index — base binding (index applied at browser boundary)"
            (let [result (resolve/resolve-intent-string intents "Login.submit[2]" :web)]
              (is (= {:css "button.submit"} (:ok result)))))))
      (finally
        (cleanup-temp-dir dir)))))
