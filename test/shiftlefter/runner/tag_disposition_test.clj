(ns shiftlefter.runner.tag-disposition-test
  "Unit tests for the planning-time tag-disposition seam (sl-i608)."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.tag-disposition :as tagd]))

(defn- pickle [& tag-names]
  {:pickle/tags (mapv (fn [n] {:name n :location {:line 1 :column 1}}) tag-names)})

;; -----------------------------------------------------------------------------
;; CLI-boundary helpers
;; -----------------------------------------------------------------------------

(deftest normalize-tag-test
  (is (= "@smoke" (tagd/normalize-tag "smoke")))
  (is (= "@smoke" (tagd/normalize-tag "@smoke")))
  (is (= "@smoke" (tagd/normalize-tag "  smoke "))))

(deftest parse-tag-list-test
  (testing "comma-separated, whitespace-tolerant, @ optional"
    (is (= #{"@a" "@b" "@c"} (tagd/parse-tag-list "a,@b , c")))
    (is (= #{"@smoke"} (tagd/parse-tag-list "smoke"))))
  (testing "blanks dropped; all-blank input yields empty set"
    (is (= #{"@a"} (tagd/parse-tag-list "a,,")))
    (is (= #{} (tagd/parse-tag-list "")))
    (is (= #{} (tagd/parse-tag-list " , ")))))

(deftest valid-tag-set-test
  (is (true? (tagd/valid-tag-set? #{"@a" "@b"})))
  (is (false? (tagd/valid-tag-set? #{})) "empty set invalid at CLI boundary")
  (is (false? (tagd/valid-tag-set? #{"@a b"})) "internal whitespace rejected")
  (is (false? (tagd/valid-tag-set? #{"@"})) "bare @ rejected")
  (is (false? (tagd/valid-tag-set? ["@a"])) "must be a set"))

;; -----------------------------------------------------------------------------
;; execute!-boundary validation
;; -----------------------------------------------------------------------------

(deftest rules-error-test
  (testing "valid shapes"
    (is (nil? (tagd/rules-error nil)))
    (is (nil? (tagd/rules-error {:include #{"@a"}})))
    (is (nil? (tagd/rules-error {:include #{"@a"} :exclude #{"@b"}}))))
  (testing "invalid shapes produce structured errors"
    (let [err (tagd/rules-error {:include #{"a"}})]
      (is (= :tag-filter/invalid (:type err)) "un-normalized tag (no @)")
      (is (string? (:message err))))
    (is (some? (tagd/rules-error {:include ["@a"]})) "vector, not set")
    (is (some? (tagd/rules-error {:exclude #{"@a b"}})) "whitespace in tag")))

;; -----------------------------------------------------------------------------
;; The seam
;; -----------------------------------------------------------------------------

(deftest disposition-test
  (testing "nil rules select everything"
    (is (= {:selected? true} (tagd/disposition nil (pickle "@a"))))
    (is (= {:selected? true} (tagd/disposition nil (pickle)))))
  (testing "include is OR: any matching tag selects"
    (is (:selected? (tagd/disposition {:include #{"@a" "@z"}} (pickle "@a" "@b"))))
    (is (not (:selected? (tagd/disposition {:include #{"@z"}} (pickle "@a")))))
    (is (not (:selected? (tagd/disposition {:include #{"@a"}} (pickle))))
        "untagged pickle is filtered out by an include filter"))
  (testing "exclude: any matching tag filters out"
    (is (not (:selected? (tagd/disposition {:exclude #{"@wip"}} (pickle "@a" "@wip")))))
    (is (:selected? (tagd/disposition {:exclude #{"@wip"}} (pickle "@a")))))
  (testing "exclude wins over include"
    (let [d (tagd/disposition {:include #{"@fast"} :exclude #{"@wip"}}
                              (pickle "@fast" "@wip"))]
      (is (not (:selected? d)))
      (is (= :exclude (get-in d [:reason :rule])))
      (is (= #{"@wip"} (get-in d [:reason :matched])))))
  (testing "include-miss reason payload"
    (let [d (tagd/disposition {:include #{"@fast"}} (pickle "@slow"))]
      (is (= :include (get-in d [:reason :rule])))
      (is (= #{"@fast"} (get-in d [:reason :wanted])))))
  (testing "empty sets are unconstrained (programmatic edge)"
    (is (:selected? (tagd/disposition {:include #{} :exclude #{}} (pickle "@a"))))))

(deftest disposition-open-map-test
  ;; Addendum 2(b): the disposition is an open map, never an enum keyword.
  (let [d (tagd/disposition nil (pickle "@a"))]
    (is (map? d))
    (is (boolean? (:selected? d)))))

(deftest disposition-schedule-facet-test
  ;; sl-q9wp: @serial produces the scheduling facet, independent of rules.
  (testing "@serial carries the facet"
    (is (= {:serial? true :reason :tag}
           (:schedule (tagd/disposition nil (pickle "@serial")))))
    (is (= {:serial? true :reason :tag}
           (:schedule (tagd/disposition nil (pickle "@a" "@serial"))))))
  (testing "no @serial, no facet key"
    (is (not (contains? (tagd/disposition nil (pickle "@a")) :schedule)))
    (is (not (contains? (tagd/disposition nil (pickle)) :schedule))))
  (testing "facet is orthogonal to selection"
    (let [d (tagd/disposition {:exclude #{"@serial"}} (pickle "@serial"))]
      (is (not (:selected? d)) "still filterable by rules")
      (is (= {:serial? true :reason :tag} (:schedule d))
          "facet present even on a filtered-out pickle"))
    (is (= {:serial? true :reason :tag}
           (:schedule (tagd/disposition {:include #{"@serial"}}
                                        (pickle "@serial")))))))

(deftest apply-dispositions-test
  (let [p1 (pickle "@fast") p2 (pickle "@slow") p3 (pickle "@fast" "@wip")]
    (testing "partition on :selected?, order preserved, both keys present"
      (is (= {:selected [p1 p3] :filtered-out [p2]}
             (tagd/apply-dispositions {:include #{"@fast"}} [p1 p2 p3])))
      (is (= {:selected [p1 p2 p3] :filtered-out []}
             (tagd/apply-dispositions nil [p1 p2 p3])))
      (is (= {:selected [] :filtered-out [p1 p2 p3]}
             (tagd/apply-dispositions {:include #{"@nope"}} [p1 p2 p3]))))
    (testing "exclude wins in the partition too"
      (is (= {:selected [p1] :filtered-out [p2 p3]}
             (tagd/apply-dispositions {:include #{"@fast"} :exclude #{"@wip"}}
                                      [p1 p2 p3]))))))
