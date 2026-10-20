(ns shiftlefter.runner.tag-disposition-test
  "Unit tests for the planning-time tag-disposition seam (sl-i608)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
  ;; sl-190x: @wip is reserved now (a never-run hold), so the generic
  ;; third tag is @slow2 here; the hold partition has its own test below.
  (let [p1 (pickle "@fast") p2 (pickle "@slow") p3 (pickle "@fast" "@slow2")]
    (testing "partition on :selected?, order preserved, all keys present"
      (is (= {:selected [p1 p3] :filtered-out [p2] :held []}
             (tagd/apply-dispositions {:include #{"@fast"}} [p1 p2 p3])))
      (is (= {:selected [p1 p2 p3] :filtered-out [] :held []}
             (tagd/apply-dispositions nil [p1 p2 p3])))
      (is (= {:selected [] :filtered-out [p1 p2 p3] :held []}
             (tagd/apply-dispositions {:include #{"@nope"}} [p1 p2 p3]))))
    (testing "exclude wins in the partition too"
      (is (= {:selected [p1] :filtered-out [p2 p3] :held []}
             (tagd/apply-dispositions {:include #{"@fast"} :exclude #{"@slow2"}}
                                      [p1 p2 p3]))))))

;; -----------------------------------------------------------------------------
;; The hold family (sl-190x): @wip, @skip=reason, @blocked=REF
;; -----------------------------------------------------------------------------

(deftest hold-facet-test
  (testing "the three reserved kinds, value kept as :note / :ref"
    (is (= {:reason :wip :tag "@wip" :location {:line 1 :column 1}}
           (tagd/hold-facet (pickle "@wip"))))
    (is (= {:reason :wip :tag "@wip=half-done" :location {:line 1 :column 1} :note "half-done"}
           (tagd/hold-facet (pickle "@wip=half-done")))
        "@wip's value is optional and kept as a note")
    (is (= {:reason :skip :tag "@skip=flaky-on-ci" :location {:line 1 :column 1} :note "flaky-on-ci"}
           (tagd/hold-facet (pickle "@skip=flaky-on-ci"))))
    (is (= {:reason :blocked :tag "@blocked=PROJ-1" :location {:line 1 :column 1} :ref "PROJ-1"}
           (tagd/hold-facet (pickle "@blocked=PROJ-1")))))
  (testing "a bare @skip / @blocked is a hold with no value key"
    (is (= {:reason :skip :tag "@skip" :location {:line 1 :column 1}}
           (tagd/hold-facet (pickle "@skip"))))
    (is (not (contains? (tagd/hold-facet (pickle "@blocked=")) :ref))))
  (testing "the first hold tag in pickle order decides; unknown keys are not holds"
    (is (= :skip (:reason (tagd/hold-facet (pickle "@smoke" "@skip=x" "@wip")))))
    (is (nil? (tagd/hold-facet (pickle "@smoke" "@env=prod" "@hook=reset")))))
  (testing "never-run-hold? — wip and skip deselect, blocked runs"
    (is (tagd/never-run-hold? (tagd/hold-facet (pickle "@wip"))))
    (is (tagd/never-run-hold? (tagd/hold-facet (pickle "@skip=x"))))
    (is (not (tagd/never-run-hold? (tagd/hold-facet (pickle "@blocked=X")))))))

(deftest hold-precedence-test
  (testing "a never-run hold deselects with no rules at all"
    (let [d (tagd/disposition nil (pickle "@smoke" "@wip"))]
      (is (false? (:selected? d)))
      (is (= {:rule :hold :tag "@wip"} (:reason d)))
      (is (= :wip (-> d :hold :reason)) "the facet rides the deselected pickle")))
  (testing "naming the tag in --tags LIFTS the hold (Cucumber --wip): selected and run"
    (is (true? (:selected? (tagd/disposition {:include #{"@wip"}} (pickle "@smoke" "@wip")))))
    (is (true? (:selected? (tagd/disposition {:include #{"@skip=flaky"}} (pickle "@skip=flaky"))))))
  (testing "naming ANOTHER tag the pickle carries does not lift the hold"
    (let [d (tagd/disposition {:include #{"@smoke"}} (pickle "@smoke" "@wip"))]
      (is (false? (:selected? d)))
      (is (= :hold (-> d :reason :rule)))))
  (testing "exclude still wins over a hold; an include miss still reads :include"
    (is (= :exclude (-> (tagd/disposition {:include #{"@wip"} :exclude #{"@wip"}} (pickle "@wip"))
                        :reason :rule)))
    (is (= :include (-> (tagd/disposition {:include #{"@other"}} (pickle "@wip"))
                        :reason :rule))))
  (testing "@blocked RUNS — selected with the facet, xfail is the engine's business"
    (let [d (tagd/disposition nil (pickle "@blocked=PROJ-1"))]
      (is (true? (:selected? d)))
      (is (= "PROJ-1" (-> d :hold :ref)))))
  (testing "--tags @blocked (bare key) selects every blocked scenario and keeps the facet"
    (let [d (tagd/disposition {:include #{"@blocked"}} (pickle "@blocked=PROJ-1"))]
      (is (true? (:selected? d)))
      (is (= :blocked (-> d :hold :reason))))
    (is (false? (:selected? (tagd/disposition {:include #{"@blocked"}} (pickle "@smoke")))))))

(deftest bare-key-match-rule-test
  (testing "a bare key in --tags matches every value-tag of that key"
    (is (:selected? (tagd/disposition {:include #{"@env"}} (pickle "@env=prod"))))
    (is (:selected? (tagd/disposition {:include #{"@skip"}} (pickle "@skip=flaky")))
        "--tags @skip lifts and selects every skip")
    (is (not (:selected? (tagd/disposition {:exclude #{"@env"}} (pickle "@env=prod"))))
        "--skip-tags @env excludes every @env=*"))
  (testing "key=value matches exactly"
    (is (:selected? (tagd/disposition {:include #{"@env=prod"}} (pickle "@env=prod"))))
    (is (not (:selected? (tagd/disposition {:include #{"@env=prod"}} (pickle "@env=staging"))))))
  (testing "a plain tag still matches exactly itself, never a longer name"
    (is (:selected? (tagd/disposition {:include #{"@smoke"}} (pickle "@smoke"))))
    (is (not (:selected? (tagd/disposition {:include #{"@smoke"}} (pickle "@smoketest")))))))

(deftest apply-dispositions-held-partition-test
  (let [p1 (pickle "@fast") p2 (pickle "@wip") p3 (pickle "@skip=slow") p4 (pickle "@blocked=X")]
    (is (= {:selected [p1 p4]
            :filtered-out []
            :held [{:pickle p2 :hold {:reason :wip :tag "@wip" :location {:line 1 :column 1}}}
                   {:pickle p3 :hold {:reason :skip :tag "@skip=slow" :location {:line 1 :column 1}
                                      :note "slow"}}]}
           (tagd/apply-dispositions nil [p1 p2 p3 p4]))
        "holds are their own partition, in pickle order; blocked is selected")
    (let [{:keys [selected filtered-out held]}
          (tagd/apply-dispositions {:include #{"@fast" "@wip" "@blocked"}} [p1 p2 p3 p4])]
      (is (= [p1 p2 p4] selected) "naming lifts wip")
      (is (= [p3] filtered-out)
          "the skip is an include MISS before it is a hold — precedence: exclude, include, hold")
      (is (= [] held)))
    (is (= {:selected [p1 p2 p3 p4] :filtered-out [] :held []}
           (tagd/apply-dispositions {:include #{"@fast" "@wip" "@blocked" "@skip=slow"}}
                                    [p1 p2 p3 p4]))
        "naming @skip=slow too lifts it — nothing is held")))

(deftest tag-grammar-test
  (testing "'@' inside a name and an empty key are rejected at the CLI boundary"
    (is (false? (tagd/valid-tag-set? #{"@a@b"})))
    (is (false? (tagd/valid-tag-set? #{"@=x"})))
    (is (true? (tagd/valid-tag-set? #{"@env=prod" "@hook=a=b"})) "'=' inside a VALUE is fine"))
  (testing "the did-you-mean sentence"
    (is (= "'@' opens a tag and never appears inside one — did you mean @a @b?"
           (tagd/tag-name-problem "@a@b")))
    (is (str/includes? (tagd/tag-name-problem "@=x") "did you mean @<key>=x?"))
    (is (nil? (tagd/tag-name-problem "@fine=v=v")))
    (is (= "@a@b: '@' opens a tag and never appears inside one — did you mean @a @b?"
           (tagd/tag-set-problem #{"@ok" "@a@b"})))))

(deftest tag-lints-test
  (let [p (assoc (pickle "@skip" "@blocked" "@serial=x" "@a@b" "@wip=half" "@skip=fine")
                 :pickle/name "S" :pickle/source-file "f.feature")
        lints (tagd/tag-lints p)]
    (is (= [:tag/reason-required :tag/ref-required :tag/value-ignored :tag/malformed]
           (mapv :type lints))
        "bare @skip, bare @blocked, a valued @serial, a malformed tag — @wip=x and @skip=fine are clean")
    (is (every? #(str/ends-with? (:message %) "(scenario \"S\", f.feature:1)") lints)
        "every lint names where the tag sits")
    (is (str/includes? (:message (first lints)) "write @skip=<why>"))
    (is (= "@blocked" (:tag (second lints))))
    (is (= [] (tagd/tag-lints (pickle "@smoke" "@env=prod" "@hook=reset" "@wip"))))))

;; -----------------------------------------------------------------------------
;; Value-tags + the :hooks facet (sl-esq)
;; -----------------------------------------------------------------------------

(deftest split-value-tag-test
  (testing "splits on the FIRST '='"
    (is (= {:key "hook" :value "reset-db"} (tagd/split-value-tag "@hook=reset-db")))
    (is (= {:key "hook" :value "a=b"} (tagd/split-value-tag "@hook=a=b"))
        "value containing '=' preserved verbatim")
    (is (= {:key "hook" :value ""} (tagd/split-value-tag "@hook="))))
  (testing "plain tags carry no '=' — nil"
    (is (nil? (tagd/split-value-tag "@smoke")))
    (is (nil? (tagd/split-value-tag "@serial")))))

(deftest hooks-facet-test
  (testing "@hook= tags produce the :hooks facet in pickle-tag order"
    (let [d (tagd/disposition nil (pickle "@hook=reset-db" "@smoke" "@hook=screenshot"))]
      (is (true? (:selected? d)))
      (is (= ["reset-db" "screenshot"] (mapv :name (:hooks d))))
      (is (every? :location (:hooks d)) "tag locations ride along for attribution")))
  (testing "no @hook= tags — no :hooks key (absent, not empty)"
    (is (not (contains? (tagd/disposition nil (pickle "@smoke")) :hooks)))
    (is (not (contains? (tagd/disposition nil (pickle)) :hooks))))
  (testing "unknown value-tag KEYS are inert — ordinary tags, no facet"
    (let [d (tagd/disposition nil (pickle "@hok=reset-db" "@blocked=sl-123"))]
      (is (not (contains? d :hooks)))
      (is (true? (:selected? d)))))
  (testing "@hook=a and @hook=b are distinct full tokens — both present"
    (is (= ["a" "b"]
           (mapv :name (:hooks (tagd/disposition nil (pickle "@hook=a" "@hook=b")))))))
  (testing "facet present even on a filtered-out pickle (same as :schedule)"
    (let [d (tagd/disposition {:exclude #{"@wip"}} (pickle "@wip" "@hook=reset-db"))]
      (is (not (:selected? d)))
      (is (= ["reset-db"] (mapv :name (:hooks d))))))
  (testing "value-tags still work as accidental filter queries (opaque tokens)"
    (is (:selected? (tagd/disposition {:include #{"@hook=reset-db"}}
                                      (pickle "@hook=reset-db"))))
    (is (not (:selected? (tagd/disposition {:include #{"@hook=reset-db"}}
                                           (pickle "@hook=other")))))))
