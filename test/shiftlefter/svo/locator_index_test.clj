(ns shiftlefter.svo.locator-index-test
  "sl-mpu5 addendum-4 locator reverse index: structural equality, roles,
   duplicate grouping, confidence tiers, def-site files."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.svo.locator-index :as locator-index]))

(def ^:private intents
  "Loader-shaped :ok map. Collisions planted: {:css \"#submit\"} owned by
   Login.go and Login.submit (same intent → :high) AND by Cart.checkout
   (cross-intent); {:id \"list\"} owned as Search root + Search.rows
   selector (same intent, mixed roles)."
  {:lookup {["Login" "submit"] {:intent "Login" :element "submit"
                                :bindings {:web {:css "#submit"}}}
            ["Login" "go"] {:intent "Login" :element "go"
                            :bindings {:web {:css "#submit"}}}
            ["Cart" "checkout"] {:intent "Cart" :element "checkout"
                                 :bindings {:web {:css "#submit"}}}
            ["Login" "email"] {:intent "Login" :element "email"
                               :bindings {:web {:css "#email"}
                                          :mobile {:css "#email"}}}}
   :regions {"Search" {:root {:web {:id "list"}}
                       :collections {:rows {:intent "Row"
                                            :selector {:web {:id "list"}}}}
                       :elements #{}}
             "Login" {:root :unrooted :collections {} :elements #{"submit" "go" "email"}}}
   :intent-files {"Login" "sl/glossary/intents/Login.edn"
                  "Cart" "sl/glossary/intents/Cart.edn"
                  "Search" "sl/glossary/intents/Search.edn"}})

(deftest lookup-is-structural
  (testing "key order / formatting immune — same EDN value, any shape"
    (let [index (locator-index/build intents)
          hits (locator-index/lookup-locator index {:css "#email"})]
      (is (= #{[:web "Login.email"] [:mobile "Login.email"]}
             (set (map (juxt :interface :address) hits)))
          "both interface bindings found for one value")))
  (testing "roles: element, root, selector"
    (let [index (locator-index/build intents)
          hits (locator-index/lookup-locator index {:id "list"})]
      (is (= #{[:root "Search"] [:selector "Search.rows"]}
             (set (map (juxt :role :address) hits)))))))

(deftest duplicates-groups-and-tiers
  (let [dups (locator-index/duplicates intents)
        by-loc (group-by :locator dups)
        submit (first (get by-loc {:css "#submit"}))
        list-dup (first (get by-loc {:id "list"}))]
    (testing "collision = 2+ owners of one value within one interface"
      (is (= 2 (count dups)))
      (is (= 3 (count (:owners submit)))))
    (testing "tiers: cross-intent owners → coincidence tier; same-intent → high"
      (is (= :cross-intent (:tier submit)) "Login + Cart span intents")
      (is (= :high (:tier list-dup)) "Search root vs Search.rows — same namespace"))
    (testing "high-confidence groups sort first"
      (is (= :high (:tier (first dups)))))
    (testing "owners carry def-site files from :intent-files"
      (is (every? :file (:owners submit)))
      (is (= "sl/glossary/intents/Search.edn" (-> list-dup :owners first :file))))
    (testing "cross-interface same value is NOT a collision"
      (is (nil? (get by-loc {:css "#email"}))
          ":web and :mobile #email address different documents"))))
