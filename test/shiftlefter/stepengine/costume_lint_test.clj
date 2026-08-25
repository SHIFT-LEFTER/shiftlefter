(ns shiftlefter.stepengine.costume-lint-test
  "Plan-time costume lints (sl-1206): wrong-interface wear, same-scenario
   shared wear, undeclared-costume warn. The xbm/s7t runtime guards stay
   (exec_test) — these are the static front door."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.stepengine.costume-lint :as lint]))

(def interfaces
  {:storefront {:type :web :adapter :etaoin}
   :admin      {:type :web :adapter :etaoin}
   :sms        {:type :sms :adapter :sms-mock}})

(defn- mk-step
  [text subject instance wears]
  {:status :matched
   :step {:step/id (java.util.UUID/randomUUID)
          :step/text text
          :step/location {:line 7 :column 3}}
   :binding {:svo (cond-> {:subject subject :verb :click
                           :object :Login.submit :interface instance}
                    wears (assoc :wears wears))}})

(defn- mk-plan [steps]
  {:plan/pickle {:pickle/source-file "features/f.feature"}
   :plan/steps (vec steps)
   :plan/runnable? true})

(defn- by-type [issues t] (filterv #(= t (:type %)) issues))

(deftest declared-match-is-green
  (testing "costume declared for the provisioned instance: no issues"
    (is (empty? (lint/check-plans
                 [(mk-plan [(mk-step "shop" :alice :storefront :alice-jacket)])]
                 {:alice-jacket {:interface :storefront}}
                 interfaces)))))

(deftest declared-mismatch-errors-with-did-you-mean
  (let [costumes {:finance  {:interface :admin}
                  :finances {:interface :storefront}}
        issues (lint/check-plans
                [(mk-plan [(mk-step "shop" :alice :storefront :finance)])]
                costumes interfaces)
        err (first (by-type issues :costume/wrong-interface))]
    (is (some? err))
    (is (= :error (:severity err)))
    (is (= :finance (:costume err)))
    (is (= :admin (:declared-interface err)))
    (is (= :storefront (:interface err)))
    (is (= :finances (:suggestion err))
        "did-you-mean over costumes declared for the provisioned instance")
    (is (re-find #"did you mean :finances" (:message err)))
    (is (= "features/f.feature" (-> err :location :uri)))
    (is (= "shop" (-> err :location :step-text)))))

(deftest undeclared-costume-warns-and-teaches
  (let [issues (lint/check-plans
                [(mk-plan [(mk-step "shop" :alice :storefront :mystery)])]
                {} interfaces)
        warn (first (by-type issues :costume/undeclared))]
    (is (= 1 (count issues)))
    (is (some? warn))
    (is (= :warn (:severity warn)))
    (is (re-find #":costumes \{:mystery \{:interface :storefront\}\}"
                 (:message warn))
        "warn text teaches the exact declaration to add")))

(deftest undeclared-warn-dedupes-suite-wide
  (testing "one teaching warn per costume name, first location wins"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step "shop" :alice :storefront :mystery)
                             (mk-step "browse" :alice :storefront :mystery)])
                   (mk-plan [(mk-step "pay" :bob :storefront :mystery)])]
                  {} interfaces)
        warns (by-type issues :costume/undeclared)]
      (is (= 1 (count warns)))
      (is (= "shop" (-> warns first :location :step-text))))))

(deftest same-scenario-shared-wears-errors
  (testing "two personas, one costume, one scenario (R4)"
    (let [costumes {:finance {:interface :storefront}}
          issues (lint/check-plans
                  [(mk-plan [(mk-step "shop" :alice :storefront :finance)
                             (mk-step "pay" :bob :storefront :finance)])]
                  costumes interfaces)
          err (first (by-type issues :costume/shared-wears))]
      (is (some? err))
      (is (= :error (:severity err)))
      (is (= :finance (:costume err)))
      (is (= [:alice :bob] (:wearers err)))
      (is (re-find #"share identity through the application" (:message err))
          "distinct subjects keep the identity-sharing diagnosis")
      (is (= "shop" (-> err :location :step-text))))))

(deftest shared-wears-across-scenarios-is-fine
  (testing "the same costume in DIFFERENT scenarios never trips lint B"
    (let [costumes {:finance {:interface :storefront}}]
      (is (empty? (lint/check-plans
                   [(mk-plan [(mk-step "shop" :alice :storefront :finance)])
                    (mk-plan [(mk-step "pay" :bob :storefront :finance)])]
                   costumes interfaces))))))

(deftest same-persona-two-interfaces-errors
  (testing "runtime-parity: targets dedup by [interface subject], so one
            persona wearing across two interfaces trips lint B too"
    (let [costumes {:finance {:interface :storefront}}
          issues (lint/check-plans
                  [(mk-plan [(mk-step "shop" :alice :storefront :finance)
                             (mk-step "admin" :alice :admin :finance)])]
                  costumes interfaces)
          shared (by-type issues :costume/shared-wears)]
      (is (= 1 (count shared)))
      (is (= [:alice :alice] (:wearers (first shared))))
      (testing "sl-xjx0 (W15): the message diagnoses ONE wearer across
                interfaces, not identity-sharing"
        (let [msg (:message (first shared))]
          (is (re-find #"worn by :alice across 2 interfaces" msg))
          (is (not (re-find #"share identity" msg))
              "the multi-subject prescription does not fire here"))))))

(deftest type-as-name-fallback-is-skipped
  (testing "unconfigured interface (bare TYPE keyword on the svo): lint A
            skips rather than false-positive"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step "shop" :alice :web :finance)])]
                  {:finance {:interface :admin}}
                  interfaces)]
      (is (empty? (by-type issues :costume/wrong-interface))))))

(deftest no-costumes-key-undeclared-warns-shared-wears-still-errors
  (testing "nil costumes map: undeclared is a warn and wrong-interface
            cannot fire, but shared-wears still BLOCKS — it is
            declaration-independent (two actors in one Chrome profile is
            a hazard whether or not the costume is declared)"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step "shop" :alice :storefront :finance)
                             (mk-step "pay" :bob :storefront :finance)])]
                  nil interfaces)]
      (is (seq (by-type issues :costume/undeclared)))
      (is (empty? (by-type issues :costume/wrong-interface)))
      ;; shared-wears is independent of declaration — two actors in one
      ;; Chrome profile is a hazard whether or not the costume is declared
      (is (seq (by-type issues :costume/shared-wears))))))

(deftest wearless-plans-are-silent
  (is (empty? (lint/check-plans
               [(mk-plan [(mk-step "shop" :alice :storefront nil)])]
               {:finance {:interface :admin}}
               interfaces))))

(deftest cross-type-wear-message-is-satisfiable
  ;; sl-xjx0 (W15): a wearing persona acting on a DIFFERENT-TYPE interface
  ;; used to get "declare the costume for :sms" — unsatisfiable (flips
  ;; forever) and dangerous (a Chrome profile handed to an SMS adapter).
  (let [costumes {:finance {:interface :storefront}}
        issues (lint/check-plans
                [(mk-plan [(mk-step "text" :alice :sms :finance)])]
                costumes interfaces)
        err (first (by-type issues :costume/wrong-interface))]
    (is (some? err))
    (is (= :error (:severity err)) "the RULE is unchanged — message only")
    (let [msg (:message err)]
      (is (re-find #"single-interface" msg))
      (is (re-find #"non-wearing persona" msg))
      (is (not (re-find #"declare the costume for :sms" msg))
          "no unsatisfiable flip prescription")
      (is (nil? (:suggestion err)) "no did-you-mean tail across types"))))

(deftest undeclared-warn-suggests-declared-near-match
  ;; sl-xjx0 (W13): a typo in the :costumes KEY (:finanace declared,
  ;; :finance worn) downgraded the wrong-interface ERROR to a warn whose
  ;; text prescribed declaring the typo — blessing the bug. With a declared
  ;; near-match the warn now leads with the typo hypothesis.
  (let [costumes {:finanace {:interface :admin}}
        issues (lint/check-plans
                [(mk-plan [(mk-step "shop" :alice :storefront :finance)])]
                costumes interfaces)
        warn (first (by-type issues :costume/undeclared))]
    (is (some? warn))
    (is (= :finanace (:suggestion warn)))
    (is (re-find #"did you mean :finanace" (:message warn)))
    (is (not (re-find #":costumes \{:finance \{:interface :storefront\}\}"
                      (:message warn)))
        "the bug-blessing prescription does not fire beside a near-match")))
