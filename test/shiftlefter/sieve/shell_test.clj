(ns shiftlefter.sieve.shell-test
  "Headless walking-skeleton proof (sl-toddler-shell-adaptation-o4x): the
   classify/rename/decide interpretation maps deterministically onto a Proposal
   Result over the jl2 contract, with no live browser."
  (:require [babashka.fs :as fs]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.sieve.contract :as contract]
            [shiftlefter.sieve.shell :as shell]
            [shiftlefter.sieve.store :as store]
            [shiftlefter.sieve.web :as web]))

(def ^:private fixture-path "test/fixtures/sieve/web-login-snapshot.edn")

(defn- analysis-from-fixture []
  (web/analyze-web-evidence (store/load-fixture fixture-path)
                            {:projection {:fingerprint "projection-fp"}}))

(def ^:private claims
  [{:element-index 0 :category "clickable" :name "sign-in" :intent "Login" :decision "accept"}
   {:element-index 1 :category "typable" :name "email" :intent "Login" :decision "reject"}
   {:element-index 2 :category "readable" :decision "ambiguous"}
   {:element-index 3 :category "chrome"}])               ;; no decision -> no bucket

(defn- build [analysis]
  (shell/build-proposal-result analysis claims
                               {:session-id "sess-o4x"
                                :interpretation-id "interp-o4x"
                                :proposal-id "proposal-o4x"
                                :page-url "https://example.test/login"}))

(deftest interpretation-buckets-by-decision
  (let [proposal (build (analysis-from-fixture))]
    (testing "decisions partition into selected / rejected / unresolved"
      (is (= 1 (count (:selected proposal))))
      (is (= 1 (count (:rejected proposal))))
      (is (= 1 (count (:unresolved proposal)))))
    (testing "every touched element stays in the interpretation, decided or not"
      (is (= 4 (count (get-in proposal [:interpretations 0 :claims])))))
    (testing "Apply Proposal is out of scope: no intended writes"
      (is (= [] (:intended-writes proposal))))))

(deftest claims-reference-candidates-without-mutating-them
  (let [analysis (analysis-from-fixture)
        proposal (build analysis)
        claim0 (first (get-in proposal [:interpretations 0 :claims]))
        candidate0 (first (:candidates analysis))]
    (is (= {:candidate/id (:candidate/id candidate0) :kind (:kind candidate0)}
           (:candidate claim0)))
    (is (= :clickable (:classification claim0)))
    (is (= "sign-in" (:name claim0)))
    (testing "the source analysis candidates are untouched by interpretation"
      (is (= analysis (analysis-from-fixture))))))

(deftest proposal-is-deterministic-and-contract-valid
  (let [analysis (analysis-from-fixture)]
    (testing "same analysis + claims + pinned ids -> identical proposal"
      (is (= (build analysis) (build analysis))))
    (testing "result conforms to the contract Proposal Result spec"
      (is (s/valid? ::contract/proposal-result (build analysis))))))

(deftest proposal-round-trips-through-the-store
  (let [tmp (str (fs/create-temp-dir))
        proposal (build (analysis-from-fixture))]
    (store/save-proposal-result! tmp proposal)
    (is (= proposal (store/load-proposal-result tmp "proposal-o4x"))
        "persisted Proposal Result reloads identically from .shiftlefter/sieve layout")))
