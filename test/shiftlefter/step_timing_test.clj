(ns shiftlefter.step-timing-test
  "Unit tests for the sl-99u2 deadline substrate: the `step/timing`
   resolution ladder (instance > interface > global > default, per-key,
   with source provenance) and the `poll-until` oracle kernel (success,
   structured timeout record, retry-error classification, sanitization)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.step :as step]))

;; -----------------------------------------------------------------------------
;; Fixtures
;; -----------------------------------------------------------------------------

(def ^:private laddered-ctx
  "Two instances of :web — :admin-web overrides :deadline-ms at the
   instance rung only, so :interval-ms must fall through to the :web
   TYPE entry (interface rung). Global carries both."
  (with-meta {:run/interfaces {:admin-web {:type :web :adapter :etaoin
                                           :timing {:deadline-ms 1000}}
                               :web {:type :web :adapter :etaoin
                                     :timing {:deadline-ms 7000 :interval-ms 250}}}
              :run/timing {:deadline-ms 2000 :interval-ms 100}}
    {:step/interface :admin-web}))

;; -----------------------------------------------------------------------------
;; timing — the resolution ladder
;; -----------------------------------------------------------------------------

(deftest timing-resolves-each-rung-with-source
  (testing "instance rung wins where the instance entry carries the key"
    (let [t (step/timing laddered-ctx)]
      (is (= 1000 (:deadline-ms t)))
      (is (= :instance (:deadline-source t)))))

  (testing "interface (type-entry) rung answers keys the instance lacks"
    (let [t (step/timing laddered-ctx)]
      (is (= 250 (:interval-ms t)))
      (is (= :interface (:interval-source t)))))

  (testing "global rung answers when no interface entry carries the key"
    (let [ctx (with-meta (dissoc laddered-ctx :run/interfaces)
                {:step/interface :admin-web})
          t (step/timing ctx)]
      (is (= [2000 :global] [(:deadline-ms t) (:deadline-source t)]))
      (is (= [100 :global] [(:interval-ms t) (:interval-source t)]))))

  (testing "override rung (non-nil opt) beats everything"
    (let [t (step/timing laddered-ctx {:deadline-ms 42})]
      (is (= [42 :override] [(:deadline-ms t) (:deadline-source t)]))
      ;; nil opt is NOT an override — the nil-rooted dynamic-var idiom
      (is (= :instance (:deadline-source (step/timing laddered-ctx {:deadline-ms nil}))))))

  (testing "caller :defaults rung beats only the framework default"
    (let [t (step/timing {} {:defaults {:deadline-ms 3000 :interval-ms 100}})]
      (is (= [3000 :default] [(:deadline-ms t) (:deadline-source t)]))
      (is (= [100 :default] [(:interval-ms t) (:interval-source t)])))
    (is (= 2000 (:deadline-ms (step/timing (vary-meta laddered-ctx dissoc :step/interface)
                                           {:defaults {:deadline-ms 3000}}))
        ) "config rungs still beat caller defaults"))

  (testing "bare ctx degrades to the framework default (REPL free mode)"
    (let [t (step/timing {})]
      (is (= step/default-timing (select-keys t [:deadline-ms :interval-ms])))
      (is (= :default (:deadline-source t)))
      (is (= :default (:interval-source t)))))

  (testing ":default-type opt supplies the instance when no SVO bound"
    (let [ctx (vary-meta laddered-ctx dissoc :step/interface)]
      (is (= :global (:deadline-source (step/timing ctx))))
      (is (= :instance (:deadline-source (step/timing ctx {:default-type :admin-web})))))))

;; -----------------------------------------------------------------------------
;; poll-until — the oracle kernel
;; -----------------------------------------------------------------------------

(defn- fast-opts [m] (merge {:deadline-ms 150 :interval-ms 10} m))

(deftest poll-until-returns-truthy-result
  (let [calls (atom 0)
        result (step/poll-until {} #(when (<= 3 (swap! calls inc)) :hit)
                                (fast-opts {:deadline-ms 2000}))]
    (is (= :hit result))
    (is (= 3 @calls))))

(deftest poll-until-timeout-record-is-structured-and-legible
  (let [ctx (with-meta {} {:step/text "alice sees the confirmation"})
        ex (try (step/poll-until ctx (constantly false) (fast-opts {}))
                (catch clojure.lang.ExceptionInfo e e))
        data (ex-data ex)]
    (is (= :step/poll-timeout (:type data)))
    (is (>= (:elapsed-ms data) 150))
    (is (= 150 (:deadline-ms data)))
    (is (= :override (:deadline-source data)))
    (is (= 10 (:interval-ms data)))
    (is (false? (:last-value data)))
    (is (re-find #"Timed out after \d+ms \(deadline 150ms via override, interval 10ms\)"
                 (ex-message ex)))
    (is (re-find #"alice sees the confirmation" (ex-message ex))
        "description defaults to the step text")))

(deftest poll-until-until-opt-carries-the-observed-value
  (testing "success returns the observation that satisfied :until"
    (is (= 5 (step/poll-until {} (constantly 5)
                              (fast-opts {:until #(= 5 %)})))))
  (testing "timeout records the last observation"
    (let [ex (try (step/poll-until {} (constantly 3)
                                   (fast-opts {:until #(= 5 %)}))
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= 3 (:last-value (ex-data ex)))))))

(deftest poll-until-retry-error-classification
  (testing "matching errors count as failed polls and land in :last-error"
    (let [ex (try (step/poll-until {} #(throw (ex-info "not yet" {:type :test/transient}))
                                   (fast-opts {:retry-error? #(= :test/transient (:type (ex-data %)))}))
                  (catch clojure.lang.ExceptionInfo e e))
          data (ex-data ex)]
      (is (= :step/poll-timeout (:type data)))
      (is (= "not yet" (get-in data [:last-error :message])))
      (is (= :test/transient (get-in data [:last-error :type])))
      (is (re-find #"Last error: not yet" (ex-message ex)))))

  (testing "non-matching errors propagate immediately"
    (let [calls (atom 0)
          ex (try (step/poll-until {} #(do (swap! calls inc)
                                           (throw (ex-info "boom" {:type :test/fatal})))
                                   (fast-opts {:retry-error? (constantly false)}))
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :test/fatal (:type (ex-data ex))))
      (is (= 1 @calls))))

  (testing "without :retry-error? any exception propagates"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                          (step/poll-until {} #(throw (ex-info "boom" {}))
                                           (fast-opts {}))))))

(deftest poll-until-context-merges-into-record
  (let [ex (try (step/poll-until {} (constantly nil)
                                 (fast-opts {:context {:to-phone "+15551234567"}}))
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= "+15551234567" (:to-phone (ex-data ex))))))

(deftest poll-until-sanitizes-observed-values
  (testing "non-EDN-safe observation becomes a bounded string (fold-in 1)"
    (let [huge (vec (range 10000))
          hostile (Object.)
          ex1 (try (step/poll-until {} (constantly huge)
                                    (fast-opts {:until (constantly false)}))
                   (catch clojure.lang.ExceptionInfo e e))
          ex2 (try (step/poll-until {} (constantly hostile)
                                    (fast-opts {:until (constantly false)}))
                   (catch clojure.lang.ExceptionInfo e e))]
      (doseq [ex [ex1 ex2]]
        (let [lv (:last-value (ex-data ex))]
          (is (string? lv))
          (is (<= (count lv) 520))
          (is (= (ex-data ex) (edn/read-string (pr-str (ex-data ex))))
              "the whole record round-trips as EDN")))))

  (testing "retryable error data keeps its map structure with bounded values"
    (let [ex (try (step/poll-until
                   {} #(throw (ex-info "big" {:type :test/transient
                                              :locator "{:id \"x\"}"
                                              :actual (apply str (repeat 5000 "x"))}))
                   (fast-opts {:retry-error? (constantly true)}))
                  (catch clojure.lang.ExceptionInfo e e))
          data (get-in (ex-data ex) [:last-error :data])]
      (is (map? data) "keyword-keyed ex-data stays queryable")
      (is (= "{:id \"x\"}" (:locator data)))
      (is (<= (count (:actual data)) 520))
      (is (= (ex-data ex) (edn/read-string (pr-str (ex-data ex)))))))

  (testing "non-keyword-keyed error data collapses to a bounded string"
    (let [ex (try (step/poll-until
                   {} #(throw (ex-info "odd" {"stringly" 1 :type :test/transient}))
                   (fast-opts {:retry-error? (constantly true)}))
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (string? (get-in (ex-data ex) [:last-error :data])))
      (is (= (ex-data ex) (edn/read-string (pr-str (ex-data ex))))))))
