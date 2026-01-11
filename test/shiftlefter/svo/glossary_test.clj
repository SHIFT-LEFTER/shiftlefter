(ns shiftlefter.svo.glossary-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.svo.glossary :as glossary]))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(def fixtures-path "test/fixtures/glossaries")

(defn fixture-path [filename]
  (str fixtures-path "/" filename))

;; -----------------------------------------------------------------------------
;; load-glossary Tests
;; -----------------------------------------------------------------------------

(deftest load-glossary-from-file
  (testing "loads valid subject glossary"
    (let [g (glossary/load-glossary (fixture-path "subjects.edn"))]
      (is (map? g))
      (is (contains? g :subjects))
      (is (= 4 (count (:subjects g))))
      (is (contains? (:subjects g) :alice))
      (is (contains? (:subjects g) :admin))
      (is (contains? (:subjects g) :guest))
      (is (contains? (:subjects g) :system/test-setup))))

  (testing "loads valid verb glossary"
    (let [g (glossary/load-glossary (fixture-path "verbs-web-project.edn"))]
      (is (map? g))
      (is (= :web (:type g)))
      (is (contains? (:verbs g) :swipe))
      (is (contains? (:verbs g) :pinch))))

  (testing "returns error for missing file"
    (let [result (glossary/load-glossary "nonexistent.edn")]
      (is (= :glossary/file-not-found (:type result)))
      (is (string? (:message result)))
      (is (= "nonexistent.edn" (:path result))))))

;; -----------------------------------------------------------------------------
;; load-default-glossaries Tests
;; -----------------------------------------------------------------------------

(deftest load-default-glossaries-test
  (testing "loads framework defaults"
    (let [g (glossary/load-default-glossaries)]
      (is (map? g))
      (is (= {} (:subjects g)) "no default subjects")
      (is (contains? (:verbs g) :web) "has web verbs")
      (is (contains? (get-in g [:verbs :web]) :click))
      (is (contains? (get-in g [:verbs :web]) :fill))
      (is (contains? (get-in g [:verbs :web]) :navigate)))))

(deftest load-default-verbs-test
  (testing "loads web verbs from classpath"
    (let [g (glossary/load-default-verbs :web)]
      (is (some? g))
      (is (= :web (:type g)))
      (is (contains? (:verbs g) :click))
      (is (contains? (:verbs g) :fill))))

  (testing "returns nil for unknown type"
    (is (nil? (glossary/load-default-verbs :unknown)))))

;; -----------------------------------------------------------------------------
;; merge-glossaries Tests
;; -----------------------------------------------------------------------------

(deftest merge-glossaries-test
  (testing "project extends defaults"
    (let [defaults {:subjects {:alice {:desc "default alice"}}
                    :verbs {:web {:click {:desc "click"}}}}
          project {:subjects {:bob {:desc "project bob"}}
                   :verbs {:web {:swipe {:desc "swipe"}}}}
          merged (glossary/merge-glossaries defaults project)]
      (is (= #{:alice :bob} (set (keys (:subjects merged)))))
      (is (= #{:click :swipe} (set (keys (get-in merged [:verbs :web])))))))

  (testing "project overrides with flag"
    (let [defaults {:subjects {:alice {:desc "default alice"}}
                    :verbs {:web {:click {:desc "click"}}}}
          project {:subjects {:bob {:desc "only bob"}}
                   :verbs {:web {:swipe {:desc "only swipe"}}}
                   :override-defaults true}
          merged (glossary/merge-glossaries defaults project)]
      (is (= #{:bob} (set (keys (:subjects merged)))))
      (is (= #{:swipe} (set (keys (get-in merged [:verbs :web])))))))

  (testing "project values override same keys"
    (let [defaults {:subjects {:alice {:desc "old desc"}}
                    :verbs {:web {:click {:desc "old click"}}}}
          project {:subjects {:alice {:desc "new desc"}}
                   :verbs {:web {:click {:desc "new click"}}}}
          merged (glossary/merge-glossaries defaults project)]
      (is (= "new desc" (get-in merged [:subjects :alice :desc])))
      (is (= "new click" (get-in merged [:verbs :web :click :desc]))))))

;; -----------------------------------------------------------------------------
;; load-all-glossaries Tests
;; -----------------------------------------------------------------------------

(deftest load-all-glossaries-test
  (testing "loads from config paths and merges with defaults"
    (let [config {:subjects (fixture-path "subjects.edn")
                  :verbs {:web (fixture-path "verbs-web-project.edn")}}
          g (glossary/load-all-glossaries config)]
      ;; Project subjects loaded
      (is (contains? (:subjects g) :alice))
      (is (contains? (:subjects g) :admin))
      ;; Default web verbs present
      (is (contains? (get-in g [:verbs :web]) :click))
      (is (contains? (get-in g [:verbs :web]) :fill))
      ;; Project web verbs merged in
      (is (contains? (get-in g [:verbs :web]) :swipe))
      (is (contains? (get-in g [:verbs :web]) :pinch))))

  (testing "handles nil paths gracefully"
    (let [config {:subjects nil
                  :verbs {:web nil}}
          g (glossary/load-all-glossaries config)]
      ;; Still has defaults
      (is (= {} (:subjects g)))
      (is (contains? (get-in g [:verbs :web]) :click))))

  (testing "handles empty config"
    (let [g (glossary/load-all-glossaries {})]
      ;; Just defaults
      (is (= {} (:subjects g)))
      (is (contains? (get-in g [:verbs :web]) :click)))))

;; -----------------------------------------------------------------------------
;; Query Function Tests
;; -----------------------------------------------------------------------------

(deftest known-subject?-test
  (let [g {:subjects {:alice {:desc "alice"}
                      :admin {:desc "admin"}}}]
    (testing "returns true for known subjects"
      (is (true? (glossary/known-subject? g :alice)))
      (is (true? (glossary/known-subject? g :admin))))

    (testing "returns false for unknown subjects"
      (is (false? (glossary/known-subject? g :bob)))
      (is (false? (glossary/known-subject? g :alcie))))))

(deftest known-verb?-test
  (let [g {:verbs {:web {:click {:desc "click"}
                         :fill {:desc "fill"}}
                   :api {:get {:desc "get"}
                         :post {:desc "post"}}}}]
    (testing "returns true for known verbs"
      (is (true? (glossary/known-verb? g :web :click)))
      (is (true? (glossary/known-verb? g :web :fill)))
      (is (true? (glossary/known-verb? g :api :get))))

    (testing "returns false for unknown verbs"
      (is (false? (glossary/known-verb? g :web :smash)))
      (is (false? (glossary/known-verb? g :api :click))))

    (testing "returns false for unknown interface type"
      (is (false? (glossary/known-verb? g :sms :send))))))

(deftest known-subjects-test
  (let [g {:subjects {:alice {} :admin {} :guest {}}}]
    (testing "returns all subject keywords"
      (let [subjects (glossary/known-subjects g)]
        (is (= 3 (count subjects)))
        (is (= #{:alice :admin :guest} (set subjects))))))

  (testing "returns empty vector for no subjects"
    (is (= [] (glossary/known-subjects {:subjects {}})))))

(deftest known-verbs-test
  (let [g {:verbs {:web {:click {} :fill {} :see {}}
                   :api {:get {} :post {}}}}]
    (testing "returns verbs for interface type"
      (let [web-verbs (glossary/known-verbs g :web)]
        (is (= 3 (count web-verbs)))
        (is (= #{:click :fill :see} (set web-verbs))))
      (let [api-verbs (glossary/known-verbs g :api)]
        (is (= 2 (count api-verbs)))
        (is (= #{:get :post} (set api-verbs)))))

    (testing "returns empty vector for unknown type"
      (is (= [] (glossary/known-verbs g :sms))))))

(deftest subject-info-test
  (let [g {:subjects {:alice {:desc "Standard customer" :role :customer}}}]
    (testing "returns info map for known subject"
      (let [info (glossary/subject-info g :alice)]
        (is (= "Standard customer" (:desc info)))
        (is (= :customer (:role info)))))

    (testing "returns nil for unknown subject"
      (is (nil? (glossary/subject-info g :bob))))))

(deftest verb-info-test
  (let [g {:verbs {:web {:click {:desc "Click element" :needs-locator true}}}}]
    (testing "returns info map for known verb"
      (let [info (glossary/verb-info g :web :click)]
        (is (= "Click element" (:desc info)))
        (is (true? (:needs-locator info)))))

    (testing "returns nil for unknown verb"
      (is (nil? (glossary/verb-info g :web :smash))))

    (testing "returns nil for unknown interface type"
      (is (nil? (glossary/verb-info g :api :click))))))
