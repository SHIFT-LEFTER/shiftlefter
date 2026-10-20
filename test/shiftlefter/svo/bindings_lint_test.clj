(ns shiftlefter.svo.bindings-lint-test
  "Static producer/consumer check for the scenario data plane (sl-yh7).
   Matcher-slot captures arrive WITH their markers (sl-ectw): `/…/` for an
   inline pattern, `Intent.element` for a declared :shape."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.intent.loader :as loader]
            [shiftlefter.svo.bindings-lint :as lint]))

(defn- mk-step [text captures kinds]
  {:status :matched
   :step {:step/id (java.util.UUID/randomUUID)
          :step/text text
          :step/location {:line 7 :column 3}}
   :binding {:captures captures :slot-kinds kinds}})

(defn- mk-plan
  ([steps] (mk-plan steps nil))
  ([steps hooks]
   (cond-> {:plan/pickle {:pickle/source-file "features/f.feature"}
            :plan/steps (vec steps)
            :plan/runnable? true}
     hooks (assoc :plan/hooks hooks))))

(deftest clean-produce-then-consume
  (is (empty? (lint/check-plans
               [(mk-plan [(mk-step "receive" ["'p'" "/(?<code>\\d+)/"] [:value :matcher])
                          (mk-step "fill" ["Login.code" "{code}"] [nil :value])])]))))

(deftest consumed-without-producer-errors-with-did-you-mean
  (let [issues (lint/check-plans
                [(mk-plan [(mk-step "receive" ["'p'" "/(?<code>\\d+)/"] [:value :matcher])
                           (mk-step "fill" ["Login.code" "{coed}"] [nil :value])])])
        err (first (filter #(= :bindings/consumed-without-producer (:type %)) issues))]
    (is (some? err))
    (is (= :error (:severity err)))
    (is (= :code (:suggestion err)))
    (is (= [:code] (:known err)))
    (is (= "features/f.feature" (-> err :location :uri)))
    (is (lint/blocking? issues))))

(deftest consumption-is-forward-only
  (testing "a later producer does not satisfy an earlier consumer"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step "fill" ["Login.code" "{code}"] [nil :value])
                             (mk-step "receive" ["'p'" "/(?<code>\\d+)/"] [:value :matcher])])])]
      (is (some #(= :bindings/consumed-without-producer (:type %)) issues)))))

(deftest hook-provides-count-as-producers
  (is (empty? (lint/check-plans
               [(mk-plan [(mk-step "fill" ["Login.code" "{sessionToken}"] [nil :value])]
                         [{:name "seed" :provides [:sessionToken]}])]))))

(deftest hook-provides-are-exempt-from-never-consumed
  (is (empty? (lint/check-plans
               [(mk-plan [(mk-step "noop" [] [])]
                         [{:name "seed" :provides [:sessionToken]}])]))))

(deftest embedded-matcher-tokens-are-consumers
  (let [issues (lint/check-plans
                [(mk-plan [(mk-step "receive" ["'p'" "/(?<n>\\d+) {order}/"] [:value :matcher])])])]
    (is (= [:order] (map :name (filter #(= :bindings/consumed-without-producer (:type %))
                                       issues))))))

(deftest location-slot-tokens-are-consumers
  (let [issues (lint/check-plans
                [(mk-plan [(mk-step "navigate" ["{resetLink}"] [:location])])])]
    (is (some #(= :bindings/consumed-without-producer (:type %)) issues))))

(deftest quoted-location-capture-is-not-a-consumer
  (testing "a '{x}' literal (quotes intact, sl-ka80) never registers as consumption"
    (is (empty? (lint/check-plans
                 [(mk-plan [(mk-step "be-on-exactly" ["'{x}'"] [:location])])])))))

(deftest duplicate-group-names-are-a-planning-error
  (let [issues (lint/check-plans
                [(mk-plan [(mk-step "r" ["'p'" "/(?<a>x)|(?<a>y)/"] [:value :matcher])])])]
    (is (= [:bindings/invalid-pattern] (map :type issues)))
    (is (lint/blocking? issues))))

(deftest unnamed-only-groups-notice
  (let [issues (lint/check-plans
                [(mk-plan [(mk-step "r" ["'p'" "/code: (\\d+)/"] [:value :matcher])])])]
    (is (= [[:bindings/unnamed-only-groups :warn]]
           (map (juxt :type :severity) issues)))
    (is (not (lint/blocking? issues)))))

;; --- Frame-aware matcher roles (sl-gwru) ---

(defn- mk-step-svo
  "mk-step with :svo metadata so matcher-role can classify the frame."
  [text captures kinds svo]
  (assoc-in (mk-step text captures kinds) [:binding :metadata :svo] svo))

(deftest capture-all-unnamed-escalates-to-error
  (testing "captures with only unnamed groups binds nothing — plan ERROR
            with the pointer at should see ... matching (sl-gwru)"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step-svo "cap" ["El" "/Order (\\d+)/"] [nil :matcher]
                                          {:verb :capture :frame :default})])])]
      (is (= [[:bindings/unnamed-only-groups :error]]
             (map (juxt :type :severity) issues)))
      (is (re-find #"should see .* matching" (:message (first issues))))
      (is (lint/blocking? issues)))))

(deftest assert-only-all-unnamed-is-silent
  (testing "should see ... matching with all-unnamed groups is the
            expected shape — no issue"
    (is (empty? (lint/check-plans
                 [(mk-plan [(mk-step-svo "see" ["El" "/Order (\\d+)/"] [nil :matcher]
                                         {:verb :see :frame :matching})])])))))

(deftest assert-only-named-groups-error-with-pointer
  (testing "a named group in the assert-only frame is a producer marker
            the frame cannot honor — plan ERROR pointing at captures"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step-svo "see" ["El" "/(?<code>\\d+)/"] [nil :matcher]
                                          {:verb :see :frame :matching})])])]
      (is (= [[:bindings/named-groups-in-assert-only [:code] :error]]
             (map (juxt :type :names :severity) issues)))
      (is (re-find #"captures" (:message (first issues))))
      (is (lint/blocking? issues)))))

(deftest assert-only-names-never-enter-produced-set
  (testing "defense-in-depth: even alongside the error, an assert-only
            frame's names never satisfy a downstream consumer"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step-svo "see" ["El" "/(?<code>\\d+)/"] [nil :matcher]
                                          {:verb :see :frame :matching})
                             (mk-step "fill" ["Login.code" "{code}"] [nil :value])])])]
      (is (some #(= :bindings/consumed-without-producer (:type %)) issues)))))

(deftest sms-receive-unnamed-keeps-notice
  (testing "the shared matcher slot outside captures keeps the :warn
            notice — binding-free assertion is legitimate there"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step-svo "recv" ["'p'" "/code (\\d+)/"] [:value :matcher]
                                          {:verb :receive :frame :default})])])]
      (is (= [[:bindings/unnamed-only-groups :warn]]
             (map (juxt :type :severity) issues))))))

(deftest produced-never-consumed-notice
  (let [issues (lint/check-plans
                [(mk-plan [(mk-step "r" ["'p'" "/(?<code>\\d+)/"] [:value :matcher])])])]
    (is (= [[:bindings/produced-never-consumed :code :warn]]
           (map (juxt :type :name :severity) issues)))))

(deftest steps-without-slot-kinds-are-invisible
  (testing "undeclared stepdefs don't trip the check (declaring the frame
            is what buys the static guarantees)"
    (is (empty? (lint/check-plans
                 [(mk-plan [(mk-step "custom" ["{code}"] nil)])])))))

(deftest scenarios-are-independent
  (testing "bindings are scenario-scoped — a producer in plan A does not
            satisfy plan B"
    (let [producer (mk-plan [(mk-step "r" ["'p'" "/(?<code>\\d+)/"] [:value :matcher])
                             (mk-step "f" ["L" "{code}"] [nil :value])])
          orphan (mk-plan [(mk-step "f" ["L" "{code}"] [nil :value])])]
      (is (some #(= :bindings/consumed-without-producer (:type %))
                (lint/check-plans [producer orphan]))))))

;; --- Declared shapes in the matcher slot (sl-ectw) ---

(defn- load-fixture-intents
  "Write the given intent files into a temp dir and load them."
  [files]
  (let [dir (fs/create-temp-dir)]
    (doseq [[fname content] files]
      (spit (str (fs/path dir fname)) content))
    (:ok (loader/load-all-intents (str dir)))))

(def ^:private shaped-intents
  (delay
    (load-fixture-intents
     {"reset.edn" (str "{:intent \"Reset\"\n"
                       " :elements {:smsCode {:bindings {:sms {}}\n"
                       "                      :shape \"code is: (\\\\d{6})\"}}}")
      "checkout.edn" (str "{:intent \"Checkout\"\n"
                          " :elements {:total {:bindings {:web {:id \"total\"}}\n"
                          "                    :shape \"\\\\$(?<total>[0-9.]+)\"}\n"
                          "            :note {:bindings {:web {:id \"note\"}}}}}")})))

(defn- mk-step-iface
  "mk-step-svo with the stepdef's interface TYPE and an O placeholder."
  [text captures kinds svo iface]
  (-> (mk-step-svo text captures kinds svo)
      (assoc-in [:binding :metadata :interface] iface)))

(deftest shape-ref-produces-the-element-name
  (testing "matching Reset.smsCode produces {smsCode}, and the producer's
            site is the glossary line, not the step"
    (let [plans [(mk-plan [(mk-step-iface "recv" ["'p'" "Reset.smsCode"] [:value :matcher]
                                          {:verb :receive :frame :default} :sms)
                           (mk-step "fill" ["Login.code" "{smsCode}"] [nil :value])])]]
      (is (empty? (lint/check-plans plans @shaped-intents))))
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step-iface "recv" ["'p'" "Reset.smsCode"] [:value :matcher]
                                            {:verb :receive :frame :default} :sms)
                             (mk-step "fill" ["Login.code" "{smscode}"] [nil :value])])]
                  @shaped-intents)
          err (first (filter #(= :bindings/consumed-without-producer (:type %)) issues))]
      (is (= :smsCode (:suggestion err)))
      (is (re-find #"reset\.edn$" (-> err :def-site :file)))
      (is (= 2 (-> err :def-site :line))))))

(deftest shape-ref-never-consumed-notice-points-at-def-site
  (let [issues (lint/check-plans
                [(mk-plan [(mk-step-iface "recv" ["'p'" "Reset.smsCode"] [:value :matcher]
                                          {:verb :receive :frame :default} :sms)])]
                @shaped-intents)]
    (is (= [[:bindings/produced-never-consumed :smsCode :warn]]
           (map (juxt :type :name :severity) issues)))
    (is (re-find #"reset\.edn$" (-> issues first :def-site :file)))))

(deftest shape-ref-errors
  (testing "unknown element"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step-iface "recv" ["'p'" "Reset.nope"] [:value :matcher]
                                            {:verb :receive :frame :default} :sms)])]
                  @shaped-intents)]
      (is (= [[:bindings/unknown-shape-ref :error]] (map (juxt :type :severity) issues)))))
  (testing "element without a :shape — def-site and both exits"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step-iface "cap" ["Checkout.note" "Checkout.note"] [nil :matcher]
                                            {:verb :capture :frame :matching :object :$1} :web)])]
                  @shaped-intents)
          err (first issues)]
      (is (= :bindings/element-has-no-shape (:type err)))
      (is (re-find #"checkout\.edn$" (-> err :def-site :file)))
      (is (re-find #"matching /…/" (:message err)))))
  (testing "a :shape read through an interface the element has no binding on"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step-iface "cap" ["Checkout.total" "Reset.smsCode"] [nil :matcher]
                                            {:verb :capture :frame :matching :object :$1} :web)])]
                  @shaped-intents)]
      (is (= [:bindings/shape-no-binding-for-interface] (map :type issues)))))
  (testing "no intents loaded at all — a ref is unknown"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step-iface "recv" ["'p'" "Reset.smsCode"] [:value :matcher]
                                            {:verb :receive :frame :default} :sms)])])]
      (is (= [:bindings/unknown-shape-ref] (map :type issues))))))

(deftest captures-without-clause-reads-the-shape
  (testing "captures Checkout.total produces {total} from the declared shape"
    (is (empty? (lint/check-plans
                 [(mk-plan [(mk-step-iface "cap" ["Checkout.total"] [nil]
                                           {:verb :capture :frame :default :object :$1} :web)
                            (mk-step "fill" ["L" "{total}"] [nil :value])])]
                 @shaped-intents))))
  (testing "on an element without a :shape — plan ERROR with def-site + both exits"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step-iface "cap" ["Checkout.note"] [nil]
                                            {:verb :capture :frame :default :object :$1} :web)])]
                  @shaped-intents)
          err (first issues)]
      (is (= [[:bindings/captures-without-shape :error]] (map (juxt :type :severity) issues)))
      (is (re-find #"checkout\.edn:\d+" (:message err)))
      (is (re-find #"matching /…/" (:message err)))
      (is (lint/blocking? issues))))
  (testing "on a raw locator — plan ERROR"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step-iface "cap" ["{:id \"x\"}"] [nil]
                                            {:verb :capture :frame :default :object :$1} :web)])]
                  @shaped-intents)]
      (is (= [:bindings/captures-without-shape] (map :type issues))))))

(deftest inline-on-a-shaped-element-is-two-declarations
  (testing "captures Checkout.total matching /…/ where total declares a
            :shape — plan ERROR naming both sites"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step-iface "cap" ["Checkout.total" "/(?<total>\\d+)/"] [nil :matcher]
                                            {:verb :capture :frame :matching :object :$1} :web)
                             (mk-step "fill" ["L" "{total}"] [nil :value])])]
                  @shaped-intents)
          err (first (filter #(= :bindings/shape-and-inline (:type %)) issues))]
      (is (some? err))
      (is (= :error (:severity err)))
      (is (re-find #"checkout\.edn$" (-> err :def-site :file)))
      (is (= 7 (-> err :location :line)))
      (is (lint/blocking? issues))))
  (testing "inline on an UNshaped element stays the escape hatch"
    (is (empty? (lint/check-plans
                 [(mk-plan [(mk-step-iface "cap" ["Checkout.note" "/(?<note>.+)/"] [nil :matcher]
                                           {:verb :capture :frame :matching :object :$1} :web)
                            (mk-step "fill" ["L" "{note}"] [nil :value])])]
                 @shaped-intents))))
  (testing "should see O matching /…/ on a shaped element competes with nothing"
    (is (empty? (lint/check-plans
                 [(mk-plan [(mk-step-iface "see" ["Checkout.total" "/\\$\\d+/"] [nil :matcher]
                                           {:verb :see :frame :matching :object :$1} :web)])]
                 @shaped-intents)))))

(deftest assert-only-admits-a-shape-ref-and-binds-nothing
  (testing "should see O matching Intent.element: legal, no producer marker
            in the step, and the shape's name never enters the produced set"
    (is (empty? (lint/check-plans
                 [(mk-plan [(mk-step-iface "see" ["Checkout.note" "Checkout.total"] [nil :matcher]
                                           {:verb :see :frame :matching :object :$1} :web)])]
                 @shaped-intents)))
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step-iface "see" ["Checkout.note" "Checkout.total"] [nil :matcher]
                                            {:verb :see :frame :matching :object :$1} :web)
                             (mk-step "fill" ["L" "{total}"] [nil :value])])]
                  @shaped-intents)]
      (is (= [:bindings/consumed-without-producer] (map :type issues)))))
  (testing "an unknown ref in the assert-only frame is still an error"
    (is (= [:bindings/unknown-shape-ref]
           (map :type (lint/check-plans
                       [(mk-plan [(mk-step-iface "see" ["Checkout.note" "Checkout.nope"] [nil :matcher]
                                                 {:verb :see :frame :matching :object :$1} :web)])]
                       @shaped-intents))))))

(deftest bare-pattern-without-markers-is-rejected
  (testing "a matcher slot that is neither /…/ nor a ref (clause 4: the
            markers ARE the classification)"
    (let [issues (lint/check-plans
                  [(mk-plan [(mk-step "r" ["'p'" "code (?<c>\\d+)"] [:value :matcher])])])]
      (is (= [[:bindings/invalid-matcher-slot :error]] (map (juxt :type :severity) issues))))))
