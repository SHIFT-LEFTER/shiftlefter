(ns shiftlefter.intent.loader-test
  (:require [clojure.test :refer [deftest is testing]]
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

;; -----------------------------------------------------------------------------
;; :collections / :root nesting schema (sl-tl9)
;; -----------------------------------------------------------------------------

(deftest loads-collections-and-root-into-regions
  (testing "A self-rooted Bookmarks+Tweet pair loads; :regions is populated"
    (let [dir (create-temp-intents-dir
               {"bookmarks.edn"
                (pr-str {:intent "Bookmarks"
                         :root {:web {:css "[aria-label^='Timeline: Bookmarks']"}}
                         :collections {:tweet {:intent "Tweet" :cardinality :many}}
                         :elements {:title {:bindings {:web {:css "h2"}}}}})
                "tweet.edn"
                (pr-str {:intent "Tweet"
                         :reusable true
                         :root {:web {:css "article[data-testid='tweet']"}}
                         :elements {:author {:bindings {:web {:css "[data-testid='User-Name']"}}}}
                         :collections {:quoted {:intent "Tweet" :optional true :count {:max 1}}}})})]
      (try
        (let [result (loader/load-all-intents (str dir))
              intents (:ok result)]
          (is (:ok result) "Should load successfully")
          (is (= #{"Bookmarks" "Tweet"} (set (keys (:regions intents)))))
          (is (= {:intent "Tweet" :cardinality :many}
                 (loader/get-collection intents "Bookmarks" "tweet")))
          (is (= {:web {:css "article[data-testid='tweet']"}}
                 (loader/get-root intents "Tweet")))
          (is (true? (:reusable (loader/get-region intents "Tweet")))
              "the :reusable marker is preserved")
          ;; :lookup stays element-keyed and still works for nested elements.
          (is (= {:css "[data-testid='User-Name']"}
                 (:ok (loader/get-binding intents "Tweet" "author" :web)))))
        (finally
          (cleanup-temp-dir dir))))))

(deftest flat-intents-load-unchanged-no-root-required
  (testing "An existing flat intent (no :root, no :collections) still loads"
    (let [dir (create-temp-intents-dir
               {"login.edn"
                (pr-str {:intent "Login"
                         :elements {:submit {:bindings {:web {:css "button"}}}}})})]
      (try
        (let [result (loader/load-all-intents (str dir))
              intents (:ok result)]
          (is (:ok result) "Flat files need no migration")
          (is (nil? (loader/get-root intents "Login")))
          (is (= {} (:collections (loader/get-region intents "Login")))))
        (finally
          (cleanup-temp-dir dir))))))

(deftest collections-only-intent-loads-without-elements
  (testing "§7.2 — a collections-only intent may omit :elements entirely"
    (let [dir (create-temp-intents-dir
               {"timeline.edn"
                (pr-str {:intent "Timeline"
                         :collections {:tweet {:intent "Tweet" :cardinality :many}}})
                "tweet.edn"
                (pr-str {:intent "Tweet"
                         :root {:web {:css "article[data-testid='tweet']"}}
                         :elements {:author {:bindings {:web {:css "[data-testid='User-Name']"}}}}})})]
      (try
        (let [result (loader/load-all-intents (str dir))
              intents (:ok result)]
          (is (:ok result) "collections-only intent loads with no :elements key")
          (is (nil? (:errors result)))
          (is (= #{} (:elements (loader/get-region intents "Timeline")))
              "its element set is empty")
          (is (= {:tweet {:intent "Tweet" :cardinality :many}}
                 (:collections (loader/get-region intents "Timeline")))))
        (finally
          (cleanup-temp-dir dir))))))

(deftest no-selector-no-root-is-a-loud-load-error
  (testing "§7.5 — a collection referencing a rootless component with no :selector"
    (let [dir (create-temp-intents-dir
               {"fbt.edn"
                (pr-str {:intent "FrequentlyBoughtTogether"
                         :collections {:item {:intent "ProductCard"}}
                         :elements {:heading {:bindings {:web {:css "h3"}}}}})
                "product-card.edn"
                (pr-str {:intent "ProductCard"
                         :elements {:price {:bindings {:web {:css ".a-price"}}}}})})]
      (try
        (let [result (loader/load-all-intents (str dir))]
          (is (:errors result))
          (is (= :intent/missing-anchor (-> result :errors first :type)))
          (is (= (str "FrequentlyBoughtTogether.item references ProductCard, "
                      "which declares no :root and was given no :selector "
                      "— add one or the other.")
                 (-> result :errors first :message))))
        (finally
          (cleanup-temp-dir dir))))))

(deftest parent-selector-satisfies-anchor
  (testing "A rootless component is fine when the parent supplies a :selector"
    (let [dir (create-temp-intents-dir
               {"fbt.edn"
                (pr-str {:intent "FrequentlyBoughtTogether"
                         :collections {:item {:intent "ProductCard"
                                              :selector {:web {:css ".fbt-item"}}}}
                         :elements {:heading {:bindings {:web {:css "h3"}}}}})
                "product-card.edn"
                (pr-str {:intent "ProductCard"
                         :elements {:price {:bindings {:web {:css ".a-price"}}}}})})]
      (try
        (is (:ok (loader/load-all-intents (str dir))))
        (finally
          (cleanup-temp-dir dir))))))

(deftest unrooted-does-not-satisfy-anchor
  (testing ":unrooted is NOT a concrete root — still a load error without :selector"
    (let [dir (create-temp-intents-dir
               {"fbt.edn"
                (pr-str {:intent "FrequentlyBoughtTogether"
                         :collections {:item {:intent "ProductCard"}}
                         :elements {:heading {:bindings {:web {:css "h3"}}}}})
                "product-card.edn"
                (pr-str {:intent "ProductCard"
                         :root :unrooted
                         :elements {:price {:bindings {:web {:css ".a-price"}}}}})})]
      (try
        (is (= :intent/missing-anchor
               (-> (loader/load-all-intents (str dir)) :errors first :type)))
        (finally
          (cleanup-temp-dir dir))))))

(deftest unknown-referenced-component-is-a-load-error
  (testing "A collection referencing an unloaded intent fails loud"
    (let [dir (create-temp-intents-dir
               {"timeline.edn"
                (pr-str {:intent "Timeline"
                         :collections {:tweet {:intent "Tweet"}}
                         :elements {:heading {:bindings {:web {:css "h2"}}}}})})]
      (try
        (is (= :intent/unknown-component
               (-> (loader/load-all-intents (str dir)) :errors first :type)))
        (finally
          (cleanup-temp-dir dir))))))

(deftest collection-element-name-collision-rejected
  (testing "A name used as both element and collection is a loud error"
    (let [dir (create-temp-intents-dir
               {"x.edn"
                (pr-str {:intent "X"
                         :root {:web {:css "#x"}}
                         :collections {:item {:intent "X"}}
                         :elements {:item {:bindings {:web {:css ".item"}}}}})})]
      (try
        (is (= :intent/name-collision
               (-> (loader/load-all-intents (str dir)) :errors first :type)))
        (finally
          (cleanup-temp-dir dir))))))

(deftest invalid-collection-shape-rejected
  (testing "A collection missing its :intent reference is rejected"
    (let [dir (create-temp-intents-dir
               {"x.edn"
                (pr-str {:intent "X"
                         :collections {:item {:cardinality :many}}
                         :elements {:heading {:bindings {:web {:css "h1"}}}}})})]
      (try
        (is (= :intent/missing-collection-intent
               (-> (loader/load-all-intents (str dir)) :errors first :type)))
        (finally
          (cleanup-temp-dir dir))))))

;; -----------------------------------------------------------------------------
;; §8.1 instance-boundary precompute + load validation (sl-h7h)
;; -----------------------------------------------------------------------------

(deftest computes-web-boundary-union-from-collections
  (testing "a component's :web boundary is the CSS union of its collections'
            effective instance selectors (:selector else component :root)"
    (let [dir (create-temp-intents-dir
               {"post.edn"
                (pr-str {:intent "Post"
                         :root {:web {:css "article[data-testid='post']"}}
                         :elements {:author {:bindings {:web {:css "[data-testid='author']"}}}}
                         :collections {:quoted  {:intent "Post" :optional true}
                                       :comment {:intent "Comment" :cardinality :many}}})
                "comment.edn"
                (pr-str {:intent "Comment"
                         :root {:web {:css "[data-testid='comment']"}}
                         :elements {:body {:bindings {:web {:css "[data-testid='body']"}}}}})})]
      (try
        (let [intents (:ok (loader/load-all-intents (str dir)))]
          ;; quoted -> Post root; comment -> Comment root. Union, in order.
          (is (= "article[data-testid='post'], [data-testid='comment']"
                 (loader/get-boundary-css intents "Post" :web)))
          ;; Comment declares no collections → empty boundary (nothing to prune).
          (is (= "" (loader/get-boundary-css intents "Comment" :web))))
        (finally
          (cleanup-temp-dir dir))))))

(deftest boundary-selector-prefers-collection-selector-over-root
  (testing "a parent :selector overrides the component :root in the boundary union"
    (let [dir (create-temp-intents-dir
               {"fbt.edn"
                (pr-str {:intent "FBT"
                         :collections {:item {:intent "ProductCard"
                                              :selector {:web {:css ".fbt-item"}}}}
                         :elements {:heading {:bindings {:web {:css "h3"}}}}})
                "product-card.edn"
                (pr-str {:intent "ProductCard"
                         :root {:web {:css ".never-used-as-boundary"}}
                         :elements {:price {:bindings {:web {:css ".a-price"}}}}})})]
      (try
        (let [intents (:ok (loader/load-all-intents (str dir)))]
          (is (= ".fbt-item" (loader/get-boundary-css intents "FBT" :web))))
        (finally
          (cleanup-temp-dir dir))))))

(deftest xpath-boundary-selector-is-a-loud-load-error
  (testing "acceptance #4 — a boundary whose :web selector is XPath (closest()
            can't use it) fails load with a precise message"
    (let [dir (create-temp-intents-dir
               {"feed.edn"
                (pr-str {:intent "Feed"
                         :root {:web {:css "[data-testid='feed']"}}
                         :collections {:post {:intent "Post"
                                              :selector {:web {:xpath "//article"}}}}
                         :elements {}})
                "post.edn"
                (pr-str {:intent "Post"
                         :root {:web {:css "article"}}
                         :elements {:author {:bindings {:web {:css ".a"}}}}})})]
      (try
        (let [result (loader/load-all-intents (str dir))]
          (is (:errors result))
          (is (= :intent/boundary-not-css (-> result :errors first :type)))
          (is (= "Feed" (-> result :errors first :intent)))
          (is (= :post (-> result :errors first :collection))))
        (finally
          (cleanup-temp-dir dir))))))

(deftest unknown-interface-boundary-is-blank
  (testing "get-boundary-css returns \"\" for an unknown intent or non-:web
            interface (no pruning rather than a crash)"
    (let [dir (create-temp-intents-dir
               {"login.edn"
                (pr-str {:intent "Login"
                         :elements {:submit {:bindings {:web {:css "button"}}}}})})]
      (try
        (let [intents (:ok (loader/load-all-intents (str dir)))]
          (is (= "" (loader/get-boundary-css intents "Login" :web)))
          (is (= "" (loader/get-boundary-css intents "Login" :mobile)))
          (is (= "" (loader/get-boundary-css intents "Nonexistent" :web))))
        (finally
          (cleanup-temp-dir dir))))))

;; -----------------------------------------------------------------------------
;; :location — named locations (sl-3jr4)
;; -----------------------------------------------------------------------------

(deftest location-loads-into-region
  (testing "An intent may declare :location; get-location returns it per-interface"
    (let [dir (create-temp-intents-dir
               {"feed.edn"
                (pr-str {:intent "Feed"
                         :location {:web {:path "/feed"}}
                         :elements {:title {:bindings {:web {:css "h1"}}}}})})]
      (try
        (let [result (loader/load-all-intents (str dir))
              intents (:ok result)]
          (is (:ok result) "Should load successfully")
          (is (= {:web {:path "/feed"}} (loader/get-location intents "Feed")))
          (is (= {:web {:path "/feed"}}
                 (:location (loader/get-region intents "Feed")))))
        (finally
          (cleanup-temp-dir dir))))))

(deftest location-is-optional-for-component-intents
  (testing "AC 8 — an intent without :location loads clean; get-location is nil"
    (let [dir (create-temp-intents-dir
               {"card.edn"
                (pr-str {:intent "ProductCard"
                         :elements {:price {:bindings {:web {:css ".price"}}}}})})]
      (try
        (let [result (loader/load-all-intents (str dir))
              intents (:ok result)]
          (is (:ok result) "No :location, no complaint")
          (is (nil? (loader/get-location intents "ProductCard")))
          (is (nil? (loader/get-location intents "Nonexistent"))))
        (finally
          (cleanup-temp-dir dir))))))

(deftest location-open-map-binding-is-valid
  (testing "Forward-compat guard (sl-4mv8): a binding map without :path is legal"
    (let [dir (create-temp-intents-dir
               {"feed.edn"
                (pr-str {:intent "Feed"
                         :location {:web {}}
                         :elements {:title {:bindings {:web {:css "h1"}}}}})})]
      (try
        (let [result (loader/load-all-intents (str dir))]
          (is (:ok result) "Open map: extra/absent keys are not a load error")
          (is (= {:web {}} (loader/get-location (:ok result) "Feed"))))
        (finally
          (cleanup-temp-dir dir))))))

(deftest invalid-location-shapes-rejected
  (testing "Bare strings, non-map bindings, and bad paths are loud load errors"
    (doseq [bad-location ["\"/feed\""                 ; bare string, not a map
                          "{:web \"/feed\"}"          ; binding is a string
                          "{}"                        ; empty per-interface map
                          "{:web {:path \"feed\"}}"   ; path missing leading /
                          "{:web {:path 42}}"]]       ; path not a string
      (let [dir (create-temp-intents-dir
                 {"feed.edn"
                  (str "{:intent \"Feed\" :location " bad-location
                       " :elements {:title {:bindings {:web {:css \"h1\"}}}}}")})]
        (try
          (let [result (loader/load-all-intents (str dir))]
            (is (:errors result) (str "Should reject :location " bad-location))
            (is (= :intent/invalid-location (-> result :errors first :type))
                (str "Error type for " bad-location)))
          (finally
            (cleanup-temp-dir dir)))))))
