(ns shiftlefter.step-timing-test
  "Unit tests for the sl-99u2 deadline substrate: the `step/timing`
   resolution ladder (instance > interface > global > default, per-key,
   with source provenance) and the `poll-until` oracle kernel (success,
   structured timeout record, retry-error classification, sanitization)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.step :as step]
            [shiftlefter.stepengine.exec :as exec]))

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

;; -----------------------------------------------------------------------------
;; sl-afs9 (F14): the empty timeout teaches the idiom; Rider C inheritance
;; -----------------------------------------------------------------------------

(defn- timeout-data [pred opts]
  (try (step/poll-until {} pred (fast-opts opts))
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(def ^:private the-hint
  "pred returned nil to continue; return the observation and pass :until to populate :last-value")

(deftest poll-until-hint-fires-only-on-the-nil-idiom
  (testing "nil-to-continue with no :until → :last-value nil AND the hint"
    (let [data (timeout-data (constantly nil) {})]
      (is (contains? data :last-value))
      (is (nil? (:last-value data)))
      (is (= the-hint (:hint data)))))
  (testing "false is a judged observation — no hint (the sl-99u2 pin stays)"
    (let [data (timeout-data (constantly false) {})]
      (is (false? (:last-value data)))
      (is (not (contains? data :hint)))))
  (testing "observe-and-judge: :last-value is the last observation, no hint"
    (let [data (timeout-data (constantly {:state "running" :pct 85})
                             {:until #(= "done" (:state %))})]
      (is (= (pr-str {:state "running" :pct 85}) (:last-value data)))
      (is (not (contains? data :hint)))))
  (testing "an :until that never accepted a nil observation still hints nothing — :until was supplied"
    (let [data (timeout-data (constantly nil) {:until some?})]
      (is (nil? (:last-value data)))
      (is (not (contains? data :hint)))))
  (testing "the retry path never hints — :last-error is the channel"
    (let [data (timeout-data #(throw (ex-info "not yet" {:type :test/transient}))
                             {:retry-error? (constantly true)})]
      (is (contains? data :last-error))
      (is (not (contains? data :last-value)))
      (is (not (contains? data :hint)))))
  (testing "the record still round-trips as EDN with the hint aboard"
    (let [data (timeout-data (constantly nil) {})]
      (is (= data (edn/read-string (pr-str data)))))))

(deftest poll-until-inherits-the-class-from-a-witness-that-never-answered
  (testing "Rider C: every attempt threw a classified error → the timeout carries the class"
    (doseq [c [:observation :sut :assertion]]
      (let [data (timeout-data #(throw (ex-info "refused" {:type :t/refused :error/class c}))
                               {:retry-error? #(= :t/refused (:type (ex-data %)))})]
        (is (= :step/poll-timeout (:type data)))
        (is (= c (:error/class data)) (str c " is inherited"))
        (is (= c (get-in data [:last-error :data :error/class]))
            "the sanitized last-error data still shows it too"))))
  (testing "an unclassified retry error inherits nothing"
    (let [data (timeout-data #(throw (ex-info "not yet" {:type :test/transient}))
                             {:retry-error? (constantly true)})]
      (is (not (contains? data :error/class)))))
  (testing "an unrecognized class value is ignored"
    (let [data (timeout-data #(throw (ex-info "x" {:type :t :error/class :bogus}))
                             {:retry-error? (constantly true)})]
      (is (not (contains? data :error/class)))))
  (testing "a timeout with ANY :last-value is never classified — the witness was answering"
    (let [data (timeout-data (constantly {:state "running"})
                             {:until #(= "done" (:state %))})]
      (is (contains? data :last-value))
      (is (not (contains? data :error/class)))))
  (testing "the class set is the one literal the runner's spec uses"
    (is (= #{:assertion :sut :observation} step/error-classes))))

;; -----------------------------------------------------------------------------
;; sl-78u4 (Warden W2): EVER-ANSWERED wins the verdict; the :poll-counts digest
;; -----------------------------------------------------------------------------

(defn- scripted
  "A predicate that plays `script` in order: a fn entry is CALLED (throw
   from it), anything else is returned. Repeats the last entry."
  [script]
  (let [n (atom -1)]
    (fn []
      (let [i (swap! n inc)
            entry (nth script (min i (dec (count script))))]
        (if (fn? entry) (entry) entry)))))

(defn- dark [] (throw (ex-info "refused" {:type :t/refused :error/class :observation})))
(def ^:private retry-refused {:retry-error? #(= :t/refused (:type (ex-data %)))})

(deftest poll-until-ever-answered-wins-the-verdict
  (testing "two real observations, then a dark throw at the deadline edge —
            the witness TALKED: no class (assertion territory, exit 1), both
            channels side by side, and the digest confesses the history
            (Warden CASE A; before the fix: :observation, :last-value gone)"
    (let [data (timeout-data (scripted [{:state "running" :pct 40} {:pct 80} dark])
                             (merge retry-refused
                                    {:until #(= "done" (:state %))
                                     :deadline-ms 60 :interval-ms 20}))]
      (is (not (contains? data :error/class)))
      (is (= "{:pct 80}" (:last-value data)) "the most recent ANSWER (sanitized), not nil")
      (is (= :t/refused (get-in data [:last-error :type])) "and the last throw beside it")
      ;; The final attempt lands at the deadline edge, so the throw count is
      ;; clock-dependent — assert the shape, exact where it is exact.
      (let [{:keys [attempts answered threw observation-throws]} (:poll-counts data)]
        (is (= 2 answered))
        (is (pos? threw))
        (is (= threw observation-throws) "every throw was the dark one")
        (is (= attempts (+ answered threw)) "no nil returns in this history"))))
  (testing "two dark throws, then a nil answer — never answered: :observation
            (Warden CASE B; before the fix: no class at all)"
    (let [data (timeout-data (scripted [dark dark nil])
                             (merge retry-refused {:deadline-ms 60 :interval-ms 20}))]
      (is (= :observation (:error/class data)))
      (is (contains? data :last-value) "the nil return is still the last value")
      (is (nil? (:last-value data)))
      (is (= :t/refused (get-in data [:last-error :type])))
      (is (= the-hint (:hint data)) "nil-idiom hint still teaches")
      (let [{:keys [attempts answered threw observation-throws]} (:poll-counts data)]
        (is (= 0 answered))
        (is (= 2 threw observation-throws))
        (is (>= attempts 3) "two throws plus at least the nil return"))))
  (testing "a dark throw ANYWHERE in a never-answered history wins over a
            later unclassified retry error"
    (let [data (timeout-data (scripted [dark #(throw (ex-info "later" {:type :t/refused}))])
                             (merge retry-refused {:deadline-ms 40 :interval-ms 20}))]
      (is (= :observation (:error/class data)))
      (is (= 1 (get-in data [:poll-counts :observation-throws])))))
  (testing "`false` is a judged observation — an answer, never classified"
    (let [data (timeout-data (scripted [false dark])
                             (merge retry-refused {:deadline-ms 40 :interval-ms 20}))]
      (is (not (contains? data :error/class)))
      (is (= 1 (get-in data [:poll-counts :answered])))))
  (testing "the digest rides every timeout — the plain nil-idiom one too"
    (let [data (timeout-data (constantly nil) {:deadline-ms 30 :interval-ms 10})]
      (is (= 0 (get-in data [:poll-counts :answered])))
      (is (= 0 (get-in data [:poll-counts :threw])))
      (is (pos? (get-in data [:poll-counts :attempts])))
      (is (not (contains? data :last-error))))))

;; -----------------------------------------------------------------------------
;; sl-tcsq: passing oracles carry :poll-counts — the per-step accumulator
;; -----------------------------------------------------------------------------

(defn- answers-on
  "A predicate that returns nil until its Nth call, then the call number."
  [n]
  (let [calls (atom 0)]
    (fn [] (let [c (swap! calls inc)] (when (>= c n) c)))))

(deftest poll-until-folds-its-digest-into-the-bound-accumulator
  (testing "a passing poll records the digest the timeout record would have"
    (let [polls (atom nil)
          v (binding [step/*poll-counts* polls]
              (step/poll-until {} (answers-on 3) (fast-opts {})))]
      (is (= 3 v))
      (is (= {:attempts 3 :answered 1 :threw 0 :observation-throws 0} @polls))))
  (testing "two polls in one binding SUM — the step's total, not the last poll"
    (let [polls (atom nil)]
      (binding [step/*poll-counts* polls]
        (step/poll-until {} (answers-on 2) (fast-opts {}))
        (step/poll-until {} (answers-on 1) (fast-opts {})))
      (is (= {:attempts 3 :answered 2 :threw 0 :observation-throws 0} @polls))))
  (testing "a timeout records too — the step record says how long it tried"
    (let [polls (atom nil)]
      (binding [step/*poll-counts* polls]
        (is (thrown? clojure.lang.ExceptionInfo
                     (step/poll-until {} (constantly nil)
                                      (fast-opts {:deadline-ms 20 :interval-ms 5})))))
      (is (pos? (:attempts @polls)))
      (is (= 0 (:answered @polls)))))
  (testing "retry-classified throws count into the summed digest"
    (let [polls (atom nil)]
      (binding [step/*poll-counts* polls]
        (step/poll-until {} (scripted [dark {:ok true}])
                         (merge retry-refused (fast-opts {}))))
      (is (= {:attempts 2 :answered 1 :threw 1 :observation-throws 1} @polls))))
  (testing "unbound (bare REPL poll): nothing recorded, nothing thrown"
    (is (nil? step/*poll-counts*))
    (is (= 2 (step/poll-until {} (answers-on 2) (fast-opts {}))))))

(deftest invoke-step-lifts-poll-counts-onto-the-result
  (let [ctx {:step {:step/text "an oracle"} :scenario {}}
        polling {:arity 1
                 :fn (fn [c] (step/poll-until c (answers-on 3) (fast-opts {})) c)}
        plain {:arity 1 :fn (fn [c] c)}
        timing-out {:arity 1
                    :fn (fn [c] (step/poll-until c (constantly nil)
                                                (fast-opts {:deadline-ms 20 :interval-ms 5})))}]
    (testing "a passing oracle's result carries the summed digest"
      (let [r (exec/invoke-step polling [] ctx)]
        (is (= :passed (:status r)))
        (is (= {:attempts 3 :answered 1 :threw 0 :observation-throws 0}
               (:poll-counts r)))))
    (testing "a step that never polled carries no key at all"
      (is (not (contains? (exec/invoke-step plain [] ctx) :poll-counts))))
    (testing "a timed-out oracle carries it beside its error"
      (let [r (exec/invoke-step timing-out [] ctx)]
        (is (= :failed (:status r)))
        (is (= :step/poll-timeout (-> r :error :data :type)))
        (is (pos? (-> r :poll-counts :attempts)))
        (is (= (-> r :error :data :poll-counts) (:poll-counts r))
            "one poll: the record's digest and the step's digest agree")))))
