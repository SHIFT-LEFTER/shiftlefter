(ns shiftlefter.adapters.registry-test
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.adapters.registry :as registry]
            [shiftlefter.adapters.etaoin :as etaoin]
            [shiftlefter.adapters.playwright :as playwright]
            [shiftlefter.adapters.sms-mock :as sms-mock-adapter]
            [shiftlefter.browser.protocol :as bp]
            [shiftlefter.test-helpers.adapter-registry :as mock]))

;; -----------------------------------------------------------------------------
;; get-adapter Tests
;; -----------------------------------------------------------------------------

(deftest test-get-adapter-known
  (testing "get-adapter returns adapter for known name"
    (let [adapter (registry/get-adapter :etaoin)]
      (is (map? adapter))
      (is (fn? (:factory adapter)))
      (is (fn? (:cleanup adapter)))
      (is (= etaoin/create-browser (:factory adapter)))
      (is (= etaoin/close-browser (:cleanup adapter))))))

(deftest test-get-adapter-playwright
  (testing "get-adapter returns adapter for :playwright"
    (let [adapter (registry/get-adapter :playwright)]
      (is (map? adapter))
      (is (fn? (:factory adapter)))
      (is (fn? (:cleanup adapter)))
      (is (= playwright/create-browser (:factory adapter)))
      (is (= playwright/close-browser (:cleanup adapter))))))

(deftest test-get-adapter-unknown
  (testing "get-adapter returns error for unknown adapter"
    (let [result (registry/get-adapter :unknown)]
      (is (map? (:error result)))
      (is (= :adapter/unknown (-> result :error :type)))
      (is (= :unknown (-> result :error :adapter)))
      (is (contains? (set (-> result :error :known)) :etaoin))
      (is (contains? (set (-> result :error :known)) :playwright)))))

(deftest test-get-adapter-custom-registry
  (testing "get-adapter can use custom registry"
    (let [custom-registry {:custom {:factory identity :cleanup identity}}
          adapter (registry/get-adapter :custom custom-registry)]
      (is (fn? (:factory adapter)))
      (is (= identity (:factory adapter))))
    ;; Default adapter not in custom registry
    (let [result (registry/get-adapter :etaoin {:custom {}})]
      (is (:error result)))))

;; -----------------------------------------------------------------------------
;; known-adapters Tests
;; -----------------------------------------------------------------------------

(deftest test-known-adapters
  (testing "known-adapters returns list of adapter names"
    (let [adapters (registry/known-adapters)]
      (is (vector? adapters))
      (is (contains? (set adapters) :etaoin)))))

(deftest test-known-adapters-custom-registry
  (testing "known-adapters works with custom registry"
    (let [adapters (registry/known-adapters {:a {} :b {} :c {}})]
      (is (= 3 (count adapters)))
      (is (= #{:a :b :c} (set adapters))))))

;; -----------------------------------------------------------------------------
;; default-registry Tests
;; -----------------------------------------------------------------------------

(deftest test-default-registry-structure
  (testing "default-registry has expected structure"
    (is (map? registry/default-registry))
    (is (contains? registry/default-registry :etaoin))
    (is (contains? registry/default-registry :playwright))
    (let [etaoin-adapter (:etaoin registry/default-registry)]
      (is (contains? etaoin-adapter :factory))
      (is (contains? etaoin-adapter :cleanup)))
    (let [pw-adapter (:playwright registry/default-registry)]
      (is (contains? pw-adapter :factory))
      (is (contains? pw-adapter :cleanup)))))

;; -----------------------------------------------------------------------------
;; create-capability Tests (with mock)
;; -----------------------------------------------------------------------------

(deftest test-create-capability-unknown-adapter
  (testing "create-capability returns error for unknown adapter"
    (let [result (registry/create-capability :unknown {})]
      (is (:error result))
      (is (= :adapter/unknown (-> result :error :type))))))

(deftest test-create-capability-with-mock
  (testing "create-capability calls factory with config"
    (let [captured-config (atom nil)
          mock-factory (fn [config]
                         (reset! captured-config config)
                         {:ok {:mock true}})
          mock-registry {:mock {:factory mock-factory :cleanup identity :provides []}}
          result (registry/create-capability :mock {:headless true} mock-registry)]
      (is (= {:ok {:mock true}} result))
      (is (= {:headless true} @captured-config)))))

(deftest test-create-capability-factory-error
  (testing "create-capability returns factory's error on failure"
    (let [mock-factory (fn [_config]
                         {:error {:type :adapter/create-failed :message "boom"}})
          mock-registry {:mock {:factory mock-factory :cleanup identity :provides []}}
          result (registry/create-capability :mock {} mock-registry)]
      (is (:error result))
      (is (= :adapter/create-failed (-> result :error :type))))))

;; -----------------------------------------------------------------------------
;; cleanup-capability Tests (with mock)
;; -----------------------------------------------------------------------------

(deftest test-cleanup-capability-unknown-adapter
  (testing "cleanup-capability returns error for unknown adapter"
    (let [result (registry/cleanup-capability :unknown {})]
      (is (:error result))
      (is (= :adapter/unknown (-> result :error :type))))))

(deftest test-cleanup-capability-with-mock
  (testing "cleanup-capability calls cleanup with capability"
    (let [captured-capability (atom nil)
          mock-cleanup (fn [capability]
                         (reset! captured-capability capability)
                         {:ok :closed})
          mock-registry {:mock {:factory identity :cleanup mock-cleanup :provides []}}
          result (registry/cleanup-capability :mock {:browser :fake} mock-registry)]
      (is (= {:ok :closed} result))
      (is (= {:browser :fake} @captured-capability)))))

;; -----------------------------------------------------------------------------
;; Adapter availability (sl-gcdu)
;; -----------------------------------------------------------------------------

(def ^:private pinned-playwright-not-bundled-message
  "The exact user-facing remedy text, byte-pinned (Tower condition on
   sl-gcdu) so message-template refactors can't drift the two remedies or
   the sizing clause."
  (str "Playwright is supported but not bundled (the driver bundle alone is ~200MB)"
       " — use :etaoin (bundled), or add com.microsoft.playwright/playwright"
       " {:mvn/version \"1.50.0\"} to your deps.edn, or drop the resolved jars"
       " in sl/lib/ (docs/INSTALL-TIERS.md)"))

(deftest test-playwright-declares-not-bundled-data
  (testing ":playwright carries the not-bundled registry keys"
    (let [pw (:playwright registry/default-registry)]
      (is (= "com.microsoft.playwright.Playwright" (:requires-class pw)))
      (is (str/includes? (:requires-dep pw) "com.microsoft.playwright/playwright"))))
  (testing "bundled adapters declare no :requires-class"
    (is (nil? (:requires-class (:etaoin registry/default-registry))))
    (is (nil? (:requires-class (:sms-mock registry/default-registry))))
    (is (nil? (:requires-class (:sms-twilio registry/default-registry))))))

(deftest test-adapter-unavailable-error-when-dep-absent
  ;; The unit-test classpath always has Playwright, so absence is simulated
  ;; by stubbing the probe — the only seam that can exercise this path.
  (with-redefs [registry/class-available? (constantly false)]
    (let [err (registry/adapter-unavailable-error :web :playwright)]
      (is (= :config/adapter-not-bundled (:type err)))
      (is (= pinned-playwright-not-bundled-message (:message err)))
      (is (= {:interface :web
              :adapter :playwright
              :requires-class "com.microsoft.playwright.Playwright"}
             (:data err))))))

(deftest test-adapter-unavailable-error-nil-cases
  (testing "bundled adapters: nil even when the probe reports absent"
    (with-redefs [registry/class-available? (constantly false)]
      (is (nil? (registry/adapter-unavailable-error :web :etaoin)))
      (is (nil? (registry/adapter-unavailable-error :sms :sms-mock)))))
  (testing "unknown adapter: nil — availability is not an existence check"
    (is (nil? (registry/adapter-unavailable-error :web :no-such-adapter))))
  (testing "no false positive: playwright IS on the test classpath (AC2)"
    (is (true? (registry/class-available? "com.microsoft.playwright.Playwright")))
    (is (nil? (registry/adapter-unavailable-error :web :playwright)))))

;; -----------------------------------------------------------------------------
;; Task 3.0.8 Acceptance Criteria
;; -----------------------------------------------------------------------------

;; -----------------------------------------------------------------------------
;; impl-key + check-extracted-impl Tests (sl-091)
;; -----------------------------------------------------------------------------

(def ^:private ibrowser-kw :shiftlefter.browser.protocol/IBrowser)

(defn- fake-ibrowser
  "An object that satisfies IBrowser (no method bodies needed for satisfies?)."
  []
  (reify bp/IBrowser))

(deftest test-impl-key-accessor
  (testing "browser adapters declare :impl-key :browser"
    (is (= :browser (registry/impl-key :etaoin)))
    (is (= :browser (registry/impl-key :playwright))))
  (testing "SMS adapters declare :impl-key :sms (sl-uv3r — the bundle
            {:sms impl :from-number ...} rides :cleanup-handle; steps get
            the protocol object)"
    (is (= :sms (registry/impl-key :sms-mock)))
    (is (= :sms (registry/impl-key :sms-twilio))))
  (testing "custom registry is honored"
    (is (= :foo (registry/impl-key :a {:a {:impl-key :foo}})))
    (is (nil? (registry/impl-key :a {:a {}})))))

(deftest test-check-extracted-impl-satisfied
  (testing "returns nil when the impl satisfies every declared protocol"
    (let [reg {:webwrap {:impl-key :browser
                         :provides [ibrowser-kw]}}]
      (is (nil? (registry/check-extracted-impl :webwrap (fake-ibrowser) reg))))))

(deftest test-check-extracted-impl-unsatisfied
  (testing "returns the unsatisfied protocols when the impl is the wrong shape"
    (let [reg {:webwrap {:impl-key :browser
                         :provides [ibrowser-kw]}}
          ;; A plain map is what the sl-091 bug produced — the wrapper, not
          ;; the IBrowser inside it.
          result (registry/check-extracted-impl :webwrap {:browser :nested} reg)]
      (is (= [ibrowser-kw] result)))))

(deftest test-check-extracted-impl-exempts-no-impl-key
  (testing "adapters without :impl-key are exempt (bare-impl factory path,
            e.g. example 04's :sms-mock override)"
    ;; The bundle does not satisfy ISMS — only the nested :sms does — but
    ;; without :impl-key the check must not run.
    (let [reg {:sms {:provides [:shiftlefter.sms.protocol/ISMS]}}]
      (is (nil? (registry/check-extracted-impl :sms {:sms :nested :from-number "x"} reg))))))

(deftest test-default-sms-mock-extraction-satisfies-provides
  (testing "sl-uv3r: the DEFAULT :sms-mock entry's :impl-key extracts an
            impl that satisfies the entry's declared :provides — the exact
            promise that went unverified while the entry omitted :impl-key"
    (let [{:keys [ok]} (sms-mock-adapter/create-sms {})
          impl (get ok (registry/impl-key :sms-mock))]
      (is (some? impl))
      (is (nil? (registry/check-extracted-impl :sms-mock impl
                                               registry/default-registry))))))

(deftest test-check-extracted-impl-unresolvable-protocol
  (testing "an unresolvable :provides keyword is reported as unsatisfied"
    (let [reg {:webwrap {:impl-key :browser
                         :provides [:no.such.ns/NopeProtocol]}}]
      (is (= [:no.such.ns/NopeProtocol]
             (registry/check-extracted-impl :webwrap (fake-ibrowser) reg))))))

;; -----------------------------------------------------------------------------
;; Capture kinds (sl-3q9r)
;; -----------------------------------------------------------------------------

(deftest test-capture-accessors-nil-tolerance
  (testing "unknown adapter -> {} / nil, never a throw"
    (is (= {} (registry/capture-kinds :no-such-adapter)))
    (is (nil? (registry/capture-spec :no-such-adapter :screenshot))))
  (testing "adapter without capture kinds -> {} / nil"
    (is (= {} (registry/capture-kinds :sms-mock)))
    (is (nil? (registry/capture-spec :sms-mock :screenshot))))
  (testing "custom registry is honored"
    (let [reg {:my-adapter {:capture {:message-log {:fn (constantly nil)}}}}]
      (is (some? (registry/capture-spec :my-adapter :message-log reg)))
      (is (nil? (registry/capture-spec :my-adapter :screenshot reg))))))

(deftest test-etaoin-capture-contract
  (testing "the browser adapter contributes exactly the three v1 kinds"
    (is (= #{:screenshot :console :page-source}
           (set (keys (registry/capture-kinds :etaoin))))))
  (testing ":console is the destructive drain — scenario-scoped; the others instant"
    (is (true? (:scenario-scoped? (registry/capture-spec :etaoin :console))))
    (is (not (:scenario-scoped? (registry/capture-spec :etaoin :screenshot))))
    (is (not (:scenario-scoped? (registry/capture-spec :etaoin :page-source)))))
  (testing "every kind's :fn is callable"
    (is (every? #(fn? (:fn %)) (vals (registry/capture-kinds :etaoin))))))

(deftest test-playwright-capture-contract
  (testing "playwright contributes exactly the two pull-at-capture kinds (sl-sehc)
            — no :console: impossible in the drain shape (no browser-side
            buffer; Playwright console is listener events; design is sl-lam0)"
    (is (= #{:screenshot :page-source}
           (set (keys (registry/capture-kinds :playwright))))))
  (testing "neither kind is a drain — no scenario-scoped flags"
    (is (not-any? :scenario-scoped? (vals (registry/capture-kinds :playwright)))))
  (testing "every kind's :fn is callable"
    (is (every? #(fn? (:fn %)) (vals (registry/capture-kinds :playwright))))))

(deftest test-console-line-formatting
  (testing "one plain line per entry: LEVEL source message"
    (is (= "SEVERE console-api boom at line 3\nINFO network fetch failed"
           (etaoin/format-console-lines
            [{:level :severe :source :console-api :message "boom at line 3"}
             {:level :info :source :network :message "fetch failed"}]))))
  (testing "missing level defaults to INFO; missing source tolerated"
    (is (= "INFO  hello"
           (etaoin/format-console-lines [{:message "hello"}])))))

(deftest acceptance-adapter-registry-test
  (testing "Task 3.0.8 AC: get-adapter for known adapter"
    (let [adapter (registry/get-adapter :etaoin)]
      (is (fn? (:factory adapter)))
      (is (fn? (:cleanup adapter)))))

  (testing "Task 3.0.8 AC: get-adapter for unknown adapter"
    (let [result (registry/get-adapter :unknown)]
      (is (:error result))
      (is (= :adapter/unknown (-> result :error :type)))
      (is (= :unknown (-> result :error :adapter)))
      (is (seq (-> result :error :known))))))

;; -----------------------------------------------------------------------------
;; Registry-entry contract (sl-wntk) — the spec's first consumer is our own
;; registry; then the legible-problems validator, one negative per failure
;; class.
;; -----------------------------------------------------------------------------

(deftest default-registry-conforms-to-entry-spec
  (testing "AC2: default-registry is the spec's first conformance test"
    (is (s/valid? ::registry/registry registry/default-registry)
        (s/explain-str ::registry/registry registry/default-registry))
    (is (nil? (registry/registry-problems registry/default-registry))))
  (testing "a mock-helper-built registry conforms too (helper stays in step)"
    (let [built (mock/registry {:web {:impl {:browser :fake} :impl-key :browser}
                                :sms {:provides [:shiftlefter.sms.protocol/ISMS]
                                      :capture {:screenshot {:fn (constantly nil)}}}})]
      (is (s/valid? ::registry/registry built)
          (s/explain-str ::registry/registry built))
      (is (nil? (registry/registry-problems built))))))

(defn- problem-messages [registry]
  (mapv :message (registry/registry-problems registry)))

(def ^:private valid-entry
  {:factory (fn [_config] {:ok {}})
   :cleanup (fn [_cap] {:ok :closed})
   :provides []})

(deftest registry-problems-negatives
  (testing "registry itself not a map"
    (let [[problem] (registry/registry-problems 42)]
      (is (= :not-a-map (:problem problem)))
      (is (str/includes? (:message problem) "not a map"))))

  (testing "non-keyword adapter name"
    (let [[problem] (registry/registry-problems {"web" valid-entry})]
      (is (= :non-keyword-adapter-name (:problem problem)))
      (is (str/includes? (:message problem) "\"web\""))))

  (testing "missing :factory — spec violation with s/explain output"
    (let [[msg] (problem-messages {:bad (dissoc valid-entry :factory)})]
      (is (str/includes? msg "adapter :bad"))
      (is (str/includes? msg ":factory"))))

  (testing ":factory not invokable"
    (let [[msg] (problem-messages {:bad (assoc valid-entry :factory "not-a-fn")})]
      (is (str/includes? msg "ifn?"))))

  (testing ":provides with unqualified keywords"
    (let [[msg] (problem-messages {:bad (assoc valid-entry :provides [:unqualified])})]
      (is (str/includes? msg "qualified-keyword?"))))

  (testing ":provides not a collection"
    (let [[msg] (problem-messages {:bad (assoc valid-entry :provides :oops)})]
      (is (str/includes? msg "adapter :bad"))))

  (testing "typo'd key names the known vocabulary"
    (let [[problem] (registry/registry-problems {:bad (assoc valid-entry :implkey :browser)})]
      (is (= [{:problem :unknown-key
               :key :implkey
               :known (vec (sort registry/known-entry-keys))}]
             (:problems problem)))
      (is (str/includes? (:message problem) "unknown entry key :implkey"))
      (is (str/includes? (:message problem) ":impl-key"))))

  (testing "capture kind missing :fn"
    (let [[msg] (problem-messages {:bad (assoc valid-entry
                                               :capture {:screenshot {:scenario-scoped? true}})})]
      (is (str/includes? msg "adapter :bad"))
      (is (str/includes? msg ":fn"))))

  (testing "one problem report per broken adapter, sorted, valid entries silent"
    (let [problems (registry/registry-problems
                    {:zeta (dissoc valid-entry :cleanup)
                     :alpha (assoc valid-entry :captur {})
                     :fine valid-entry})]
      (is (= [:alpha :zeta] (mapv :adapter problems))))))
