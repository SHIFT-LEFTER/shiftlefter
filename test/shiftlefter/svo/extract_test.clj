(ns shiftlefter.svo.extract-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.svo.extract :as extract]))

;; -----------------------------------------------------------------------------
;; normalize-subject Tests
;; -----------------------------------------------------------------------------

(deftest normalize-subject-test
  (testing "lowercases and keywordizes"
    (is (= :alice (extract/normalize-subject "Alice")))
    (is (= :admin (extract/normalize-subject "ADMIN")))
    (is (= :bob (extract/normalize-subject "bob"))))

  (testing "trims whitespace"
    (is (= :alice (extract/normalize-subject " Alice ")))
    (is (= :bob (extract/normalize-subject "  Bob\t"))))

  (testing "preserves namespaced keywords"
    (is (= :system/test-setup (extract/normalize-subject "system/test-setup")))
    (is (= :admin/super (extract/normalize-subject "ADMIN/SUPER"))))

  (testing "returns nil for non-strings"
    (is (nil? (extract/normalize-subject nil)))
    (is (nil? (extract/normalize-subject 123)))
    (is (nil? (extract/normalize-subject :already-keyword)))))

;; -----------------------------------------------------------------------------
;; substitute-placeholders Tests
;; -----------------------------------------------------------------------------

(deftest substitute-placeholders-test
  (testing "replaces single placeholder"
    (is (= {:subject "Alice" :verb :click}
           (extract/substitute-placeholders
            {:subject :$1 :verb :click}
            ["Alice"]))))

  (testing "replaces multiple placeholders"
    (is (= {:subject "Alice" :verb :click :object "the button"}
           (extract/substitute-placeholders
            {:subject :$1 :verb :click :object :$2}
            ["Alice" "the button"]))))

  (testing "leaves non-placeholders unchanged"
    (is (= {:subject :alice :verb :click :object "literal"}
           (extract/substitute-placeholders
            {:subject :alice :verb :click :object "literal"}
            ["ignored"]))))

  (testing "handles mixed placeholders and literals"
    (is (= {:subject "Bob" :verb :fill :object "the field" :extra :value}
           (extract/substitute-placeholders
            {:subject :$1 :verb :fill :object :$2 :extra :value}
            ["Bob" "the field"]))))

  (testing "returns error for out-of-bounds placeholder"
    (let [result (extract/substitute-placeholders
                  {:subject :$1 :verb :click :object :$2}
                  ["Alice"])]
      (is (= :svo/placeholder-out-of-bounds (:type result)))
      (is (= :$2 (:placeholder result)))
      (is (= 2 (:index result)))
      (is (= 1 (:capture-count result)))))

  (testing "returns error for $0 placeholder"
    ;; $0 would try index -1
    (let [result (extract/substitute-placeholders
                  {:subject :$0}
                  ["Alice"])]
      (is (= :svo/placeholder-out-of-bounds (:type result)))))

  (testing "handles empty captures with no placeholders"
    (is (= {:verb :click :object "literal"}
           (extract/substitute-placeholders
            {:verb :click :object "literal"}
            [])))))

;; -----------------------------------------------------------------------------
;; extract-svoi Tests
;; -----------------------------------------------------------------------------

(deftest extract-svoi-test
  (testing "extracts full SVOI from metadata and captures"
    (let [metadata {:interface :web
                    :svo {:subject :$1 :verb :click :object :$2}}
          captures ["Alice" "the button"]
          result (extract/extract-svoi metadata captures)]
      (is (= :alice (:subject result)))
      (is (= :click (:verb result)))
      (is (= "the button" (:object result)))
      (is (= :web (:interface result)))))

  (testing "normalizes subject from string capture"
    (let [result (extract/extract-svoi
                  {:interface :web :svo {:subject :$1 :verb :see}}
                  ["ADMIN"])]
      (is (= :admin (:subject result)))))

  (testing "preserves literal keyword subject"
    (let [result (extract/extract-svoi
                  {:interface :web :svo {:subject :system/setup :verb :init}}
                  [])]
      (is (= :system/setup (:subject result)))))

  (testing "returns nil for nil metadata"
    (is (nil? (extract/extract-svoi nil ["Alice"]))))

  (testing "returns nil for metadata without :svo"
    (is (nil? (extract/extract-svoi {:interface :web} ["Alice"]))))

  (testing "returns nil for empty metadata"
    (is (nil? (extract/extract-svoi {} ["Alice"]))))

  (testing "returns error for placeholder out of bounds"
    (let [result (extract/extract-svoi
                  {:interface :web :svo {:subject :$1 :verb :click :object :$3}}
                  ["Alice" "button"])]
      (is (= :svo/placeholder-out-of-bounds (:type result)))
      (is (= :$3 (:placeholder result)))))

  (testing "handles object as literal string"
    (let [result (extract/extract-svoi
                  {:interface :api :svo {:subject :$1 :verb :get :object "/api/users"}}
                  ["system"])]
      (is (= :system (:subject result)))
      (is (= :get (:verb result)))
      (is (= "/api/users" (:object result)))
      (is (= :api (:interface result)))))

  (testing "handles nil object"
    (let [result (extract/extract-svoi
                  {:interface :web :svo {:subject :$1 :verb :wait :object nil}}
                  ["Alice"])]
      (is (= :alice (:subject result)))
      (is (= :wait (:verb result)))
      (is (nil? (:object result))))))

;; -----------------------------------------------------------------------------
;; Edge Cases
;; -----------------------------------------------------------------------------

(deftest edge-cases-test
  (testing "subject with special characters normalizes"
    (is (= :alice-smith (extract/normalize-subject "Alice-Smith")))
    (is (= :user_1 (extract/normalize-subject "USER_1"))))

  (testing "unicode subjects normalize"
    (is (= :maría (extract/normalize-subject "María")))
    (is (= :北京 (extract/normalize-subject "北京"))))

  (testing "empty string subject"
    (is (= (keyword "") (extract/normalize-subject ""))))

  (testing "placeholder $10 works"
    (let [captures (vec (map str (range 10)))]
      (is (= {:val "9"}
             (extract/substitute-placeholders {:val :$10} captures))))))
