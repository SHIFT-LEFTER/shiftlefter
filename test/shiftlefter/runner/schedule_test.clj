(ns shiftlefter.runner.schedule-test
  "Unit tests for the plan-level scheduling facet (sl-q9wp)."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.schedule :as schedule]))

(defn- step
  "A minimal bound step whose SVO the target walk reads."
  [iface subject & {:keys [wears]}]
  {:step {:step/text (str subject " does something")}
   :binding {:svo (cond-> {:interface iface :subject subject}
                    wears (assoc :wears wears))}})

(defn- plan
  [{:keys [tags steps]}]
  {:plan/pickle {:pickle/id (java.util.UUID/randomUUID)
                 :pickle/tags (mapv (fn [n] {:name n}) (or tags []))}
   :plan/steps (vec steps)
   :plan/runnable? true})

(def ^:private interfaces
  {:web {:type :web :adapter :etaoin :shared-impl? false}
   :sms {:type :sms :adapter :sms-mock :shared-impl? true}})

(deftest plan-schedule-reasons-test
  (testing "no gate, no facet"
    (is (nil? (schedule/plan-schedule
               (plan {:steps [(step :web :alice)]}) interfaces))))
  (testing ":tag — @serial via the disposition seam"
    (is (= {:serial? true :reason :tag}
           (schedule/plan-schedule
            (plan {:tags ["@serial"] :steps [(step :web :alice)]})
            interfaces))))
  (testing ":costume — any step whose SVO wears a costume"
    (is (= {:serial? true :reason :costume}
           (schedule/plan-schedule
            (plan {:steps [(step :web :alice)
                           (step :web :bob :wears "gabriel-chrome")]})
            interfaces))))
  (testing ":shared-impl — any target on a :shared-impl? interface"
    (is (= {:serial? true :reason :shared-impl}
           (schedule/plan-schedule
            (plan {:steps [(step :sms :alice)]}) interfaces))))
  (testing "steps without :interface never gate"
    (is (nil? (schedule/plan-schedule
               (plan {:steps [{:step {} :binding {}}]}) interfaces)))))

(deftest plan-schedule-precedence-test
  (testing ":tag wins over :costume and :shared-impl"
    (is (= {:serial? true :reason :tag}
           (schedule/plan-schedule
            (plan {:tags ["@serial"]
                   :steps [(step :web :alice :wears "gabriel-chrome")
                           (step :sms :alice)]})
            interfaces))))
  (testing ":costume wins over :shared-impl"
    (is (= {:serial? true :reason :costume}
           (schedule/plan-schedule
            (plan {:steps [(step :web :alice :wears "gabriel-chrome")
                           (step :sms :alice)]})
            interfaces)))))

(deftest attach-schedules-test
  (let [free   (plan {:steps [(step :web :alice)]})
        tagged (plan {:tags ["@serial"] :steps [(step :web :alice)]})
        sms    (plan {:steps [(step :sms :bob)]})
        [free' tagged' sms'] (schedule/attach-schedules [free tagged sms]
                                                        interfaces)]
    (testing "facet attached where a gate fires"
      (is (= {:serial? true :reason :tag} (:plan/schedule tagged')))
      (is (= {:serial? true :reason :shared-impl} (:plan/schedule sms'))))
    (testing "no facet, no key — the plan is untouched"
      (is (= free free'))
      (is (not (contains? free' :plan/schedule))))
    (testing "additive: everything else on the plan survives"
      (is (= (:plan/pickle tagged) (:plan/pickle tagged')))
      (is (= (:plan/steps sms) (:plan/steps sms'))))))

(deftest auto-serial-counts-test
  (let [plans (schedule/attach-schedules
               [(plan {:steps [(step :web :alice)]})
                (plan {:tags ["@serial"] :steps [(step :web :alice)]})
                (plan {:steps [(step :web :a :wears "c1")]})
                (plan {:steps [(step :web :b :wears "c2")]})
                (plan {:steps [(step :sms :alice)]})]
               interfaces)]
    (is (= {:costume 2 :shared-impl 1} (schedule/auto-serial-counts plans))
        "@serial (:tag) is user intent, not an auto gate — excluded")
    (is (= {} (schedule/auto-serial-counts
               [(plan {:steps [(step :web :alice)]})])))))

;; -----------------------------------------------------------------------------
;; [:hook <name>] source (sl-esq AC8)
;; -----------------------------------------------------------------------------

(defn- with-hooks [p hooks] (assoc p :plan/hooks hooks))

(deftest plan-schedule-hook-source-test
  (testing "a resolved :requires-serial hook auto-serializes the plan"
    (is (= {:serial? true :reason [:hook "reset-db"]}
           (schedule/plan-schedule
            (with-hooks (plan {:steps [(step :web :alice)]})
              [{:name "audit"}
               {:name "reset-db" :requires-serial true}])
            interfaces))))
  (testing "hooks without :requires-serial make no scheduling claim"
    (is (nil? (schedule/plan-schedule
               (with-hooks (plan {:steps [(step :web :alice)]})
                 [{:name "audit"}])
               interfaces))))
  (testing "precedence: :tag > :hook"
    (is (= {:serial? true :reason :tag}
           (schedule/plan-schedule
            (with-hooks (plan {:tags ["@serial"] :steps [(step :web :alice)]})
              [{:name "reset-db" :requires-serial true}])
            interfaces))))
  (testing "precedence: :hook > :costume"
    (is (= {:serial? true :reason [:hook "reset-db"]}
           (schedule/plan-schedule
            (with-hooks (plan {:steps [(step :web :alice :wears "c1")]})
              [{:name "reset-db" :requires-serial true}])
            interfaces)))))

(deftest auto-serial-counts-include-hooks
  (let [plans (schedule/attach-schedules
               [(with-hooks (plan {:steps [(step :web :alice)]})
                  [{:name "reset-db" :requires-serial true}])
                (plan {:steps [(step :web :a :wears "c1")]})]
               interfaces)]
    (is (= {:hook 1 :costume 1} (schedule/auto-serial-counts plans)))))
