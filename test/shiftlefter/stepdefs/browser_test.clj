(ns shiftlefter.stepdefs.browser-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.browser.ctx :as browser.ctx]
            [shiftlefter.browser.protocol :as bp]))

;; -----------------------------------------------------------------------------
;; Fake Browser for Testing
;; -----------------------------------------------------------------------------

(defrecord FakeBrowser [calls config]
  bp/IBrowser
  (open-to! [this url]
    (swap! calls conj {:op :open-to :url url})
    this)
  (click! [this locator]
    (swap! calls conj {:op :click :locator locator})
    this)
  (doubleclick! [this locator]
    (swap! calls conj {:op :doubleclick :locator locator})
    this)
  (rightclick! [this locator]
    (swap! calls conj {:op :rightclick :locator locator})
    this)
  (move-to! [this locator]
    (swap! calls conj {:op :move-to :locator locator})
    this)
  (drag-to! [this from to]
    (swap! calls conj {:op :drag-to :from from :to to})
    this)
  (fill! [this locator text]
    (swap! calls conj {:op :fill :locator locator :text text})
    this)
  (element-count [_this locator]
    (swap! calls conj {:op :element-count :locator locator})
    (get @config :element-count 42))
  (get-text [_this locator]
    (swap! calls conj {:op :get-text :locator locator})
    (get @config :page-text "Hello World"))
  (get-url [_this]
    (swap! calls conj {:op :get-url})
    (get @config :url "https://example.com"))
  (get-title [_this]
    (swap! calls conj {:op :get-title})
    (get @config :title "Example Title"))
  (visible? [_this locator]
    (swap! calls conj {:op :visible? :locator locator})
    (get @config :visible true)))

(defn make-fake-browser
  ([] (->FakeBrowser (atom []) (atom {})))
  ([config-map] (->FakeBrowser (atom []) (atom config-map))))

(defn get-calls [browser]
  @(:calls browser))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    ;; Load browser stepdefs
    (require 'shiftlefter.stepdefs.browser :reload)
    (f)))

;; -----------------------------------------------------------------------------
;; Helper
;; -----------------------------------------------------------------------------

(defn- make-ctx-with-browser
  "Create a flat ctx with a fake browser attached.
   Note: ctx-first convention - ctx is flat scenario state."
  [browser]
  (browser.ctx/assoc-active-browser {} browser))

(defn- find-stepdef
  "Find a stepdef by pattern match."
  [text]
  (first (filter #(re-matches (:pattern %) text) (registry/all-stepdefs))))

(defn- invoke-step
  "Invoke a stepdef with text and ctx.
   Note: ctx-first convention - ctx goes first, then captures."
  [text ctx]
  (let [stepdef (find-stepdef text)
        matcher (re-matcher (:pattern stepdef) text)
        _ (.matches matcher)
        captures (mapv #(.group matcher %) (range 1 (inc (.groupCount matcher))))
        arity (:arity stepdef)
        ;; ctx-first: if arity > captures, prepend ctx
        args (if (= arity (inc (count captures)))
               (into [ctx] captures)
               captures)]
    (apply (:fn stepdef) args)))

;; -----------------------------------------------------------------------------
;; Navigation Tests
;; -----------------------------------------------------------------------------

(deftest test-open-browser-to
  (testing "I open the browser to '<url>' navigates"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I open the browser to 'https://example.com'" ctx)]
      (is (map? result))
      (is (= [{:op :open-to :url "https://example.com"}] (get-calls browser))))))

;; -----------------------------------------------------------------------------
;; Click Tests
;; -----------------------------------------------------------------------------

(deftest test-click
  (testing "I click {locator} clicks element"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I click {:css \"#submit\"}" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= 1 (count calls)))
        (is (= :click (:op (first calls))))
        (is (= {:css "#submit"} (-> calls first :locator :q)))))))

(deftest test-double-click
  (testing "I double-click {locator} double-clicks element"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I double-click {:id \"item\"}" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :doubleclick (:op (first calls))))))))

(deftest test-right-click
  (testing "I right-click {locator} right-clicks element"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I right-click {:css \".menu\"}" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :rightclick (:op (first calls))))))))

;; -----------------------------------------------------------------------------
;; Mouse Tests
;; -----------------------------------------------------------------------------

(deftest test-move-to
  (testing "I move to {locator} moves mouse"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I move to {:css \".hover-target\"}" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :move-to (:op (first calls))))))))

(deftest test-drag-to
  (testing "I drag {from} to {to} drags element"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I drag {:id \"source\"} to {:id \"target\"}" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :drag-to (:op (first calls))))
        (is (= {:id "source"} (-> calls first :from :q)))
        (is (= {:id "target"} (-> calls first :to :q)))))))

;; -----------------------------------------------------------------------------
;; Input Tests
;; -----------------------------------------------------------------------------

(deftest test-fill
  (testing "I fill {locator} with '<text>' fills input"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I fill {:name \"email\"} with 'test@example.com'" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :fill (:op (first calls))))
        (is (= {:name "email"} (-> calls first :locator :q)))
        (is (= "test@example.com" (:text (first calls))))))))

;; -----------------------------------------------------------------------------
;; Query Tests
;; -----------------------------------------------------------------------------

(deftest test-count-elements
  (testing "I count {locator} elements stores count"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I count {:css \".item\"} elements" ctx)]
      (is (= 42 (:element-count result)))
      (let [calls (get-calls browser)]
        (is (= :element-count (:op (first calls))))))))

;; -----------------------------------------------------------------------------
;; Vector Locator Syntax Tests
;; -----------------------------------------------------------------------------

(deftest test-vector-locator-syntax
  (testing "Vector locator syntax [:css \"...\"] works"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I click [:css \"#login\"]" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :click (:op (first calls))))
        ;; Vector normalized to map
        (is (= {:css "#login"} (-> calls first :locator :q)))))))

;; -----------------------------------------------------------------------------
;; Error Handling Tests
;; -----------------------------------------------------------------------------

(deftest test-no-browser-configured
  (testing "throws when no browser in ctx"
    (let [ctx {}]  ;; flat ctx with no browser
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No browser configured"
                            (invoke-step "I open the browser to 'https://example.com'" ctx))))))

(deftest test-invalid-locator
  (testing "throws on invalid locator"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid locator"
                            (invoke-step "I click {:invalid \"x\"}" ctx))))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria
;; -----------------------------------------------------------------------------

(deftest test-acceptance-criteria
  (testing "Task 2.5.11 AC: stepdefs call resolve-locator and invoke protocol"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)]
      ;; Navigation
      (invoke-step "I open the browser to 'https://example.com'" ctx)
      ;; Click with resolved locator
      (invoke-step "I click {:css \"a.more\"}" ctx)

      (let [calls (get-calls browser)]
        (is (= 2 (count calls)))
        (is (= :open-to (:op (first calls))))
        (is (= :click (:op (second calls))))))))

;; =============================================================================
;; Subject-Extracting Step Tests
;; =============================================================================

(defn- make-ctx-with-subject
  "Create ctx with a named subject browser session."
  [subject-kw browser]
  (-> {}
      (browser.ctx/assoc-active-browser subject-kw browser)))

(defn- make-ctx-with-two-subjects
  "Create ctx with two named subject browser sessions."
  [subj1-kw browser1 subj2-kw browser2]
  (-> {}
      (browser.ctx/assoc-active-browser subj1-kw browser1)
      (browser.ctx/assoc-active-browser subj2-kw browser2)))

;; -----------------------------------------------------------------------------
;; Subject-Extracting Action Steps
;; -----------------------------------------------------------------------------

(deftest test-subject-opens-browser
  (testing ":alice opens the browser to '<url>' navigates"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice opens the browser to 'https://example.com'" ctx)]
      (is (map? result))
      (is (= [{:op :open-to :url "https://example.com"}] (get-calls browser))))))

(deftest test-subject-clicks
  (testing ":alice clicks {locator}"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice clicks {:css \"#submit\"}" ctx)]
      (is (map? result))
      (is (= :click (:op (first (get-calls browser))))))))

(deftest test-subject-double-clicks
  (testing ":alice double-clicks {locator}"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice double-clicks {:id \"item\"}" ctx)]
      (is (map? result))
      (is (= :doubleclick (:op (first (get-calls browser))))))))

(deftest test-subject-right-clicks
  (testing ":alice right-clicks {locator}"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice right-clicks {:css \".menu\"}" ctx)]
      (is (map? result))
      (is (= :rightclick (:op (first (get-calls browser))))))))

(deftest test-subject-moves-to
  (testing ":alice moves to {locator}"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice moves to {:css \".hover\"}" ctx)]
      (is (map? result))
      (is (= :move-to (:op (first (get-calls browser))))))))

(deftest test-subject-drags
  (testing ":alice drags {from} to {to}"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice drags {:id \"src\"} to {:id \"dst\"}" ctx)]
      (is (map? result))
      (let [call (first (get-calls browser))]
        (is (= :drag-to (:op call)))
        (is (= {:id "src"} (-> call :from :q)))
        (is (= {:id "dst"} (-> call :to :q)))))))

(deftest test-subject-fills
  (testing ":alice fills {locator} with 'text'"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice fills {:name \"email\"} with 'test@example.com'" ctx)]
      (is (map? result))
      (let [call (first (get-calls browser))]
        (is (= :fill (:op call)))
        (is (= "test@example.com" (:text call)))))))

;; -----------------------------------------------------------------------------
;; Subject Routing
;; -----------------------------------------------------------------------------

(deftest test-subject-routing-selects-correct-session
  (testing "subject routes to correct browser session"
    (let [alice-browser (make-fake-browser)
          bob-browser (make-fake-browser)
          ctx (make-ctx-with-two-subjects :alice alice-browser :bob bob-browser)]
      (invoke-step ":alice clicks {:css \"#a\"}" ctx)
      (invoke-step ":bob clicks {:css \"#b\"}" ctx)
      (is (= 1 (count (get-calls alice-browser))))
      (is (= 1 (count (get-calls bob-browser))))
      (is (= {:css "#a"} (-> (get-calls alice-browser) first :locator :q)))
      (is (= {:css "#b"} (-> (get-calls bob-browser) first :locator :q))))))

(deftest test-subject-missing-session-error
  (testing "throws clear error when subject has no session"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          ex (try
               (invoke-step ":bob clicks {:css \"#x\"}" ctx)
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (.contains (ex-message ex) "No browser session for subject: bob"))
      (let [data (ex-data ex)]
        (is (= :browser/no-session (:type data)))
        (is (= "bob" (:subject data)))
        (is (contains? (set (:available-sessions data)) :alice))))))

;; -----------------------------------------------------------------------------
;; Subject-Extracting Verification Steps
;; -----------------------------------------------------------------------------

(deftest test-should-see-text-pass
  (testing ":alice should see 'text' passes when text is on page"
    (let [browser (make-fake-browser {:page-text "Hello World welcome"})
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice should see 'Hello World'" ctx)]
      (is (map? result))
      (is (= :get-text (:op (first (get-calls browser))))))))

(deftest test-should-see-text-fail
  (testing ":alice should see 'text' throws when text is not on page"
    (let [browser (make-fake-browser {:page-text "No match here"})
          ctx (make-ctx-with-subject :alice browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Expected to see text"
                            (invoke-step ":alice should see 'Goodbye'" ctx))))))

(deftest test-should-see-count-pass
  (testing ":alice should see N {locator} elements passes on match"
    (let [browser (make-fake-browser {:element-count 3})
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice should see 3 {:css \".item\"} elements" ctx)]
      (is (map? result)))))

(deftest test-should-see-count-fail
  (testing ":alice should see N {locator} elements throws on mismatch"
    (let [browser (make-fake-browser {:element-count 5})
          ctx (make-ctx-with-subject :alice browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Expected 3 elements but found 5"
                            (invoke-step ":alice should see 3 {:css \".item\"} elements" ctx))))))

(deftest test-should-see-count-zero
  (testing ":alice should see 0 {locator} elements passes when none found"
    (let [browser (make-fake-browser {:element-count 0})
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice should see 0 {:css \".error\"} elements" ctx)]
      (is (map? result)))))

(deftest test-should-see-locator-visible-pass
  (testing ":alice should see {locator} passes when element visible"
    (let [browser (make-fake-browser {:visible true})
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice should see {:css \"#header\"}" ctx)]
      (is (map? result)))))

(deftest test-should-see-locator-visible-fail
  (testing ":alice should see {locator} throws when element not visible"
    (let [browser (make-fake-browser {:visible false})
          ctx (make-ctx-with-subject :alice browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Expected element to be visible"
                            (invoke-step ":alice should see {:css \"#hidden\"}" ctx))))))

(deftest test-should-not-see-locator-pass
  (testing ":alice should not see {locator} passes when element not visible"
    (let [browser (make-fake-browser {:visible false})
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice should not see {:css \".gone\"}" ctx)]
      (is (map? result)))))

(deftest test-should-not-see-locator-fail
  (testing ":alice should not see {locator} throws when element IS visible"
    (let [browser (make-fake-browser {:visible true})
          ctx (make-ctx-with-subject :alice browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Expected element to NOT be visible"
                            (invoke-step ":alice should not see {:css \"#visible\"}" ctx))))))

(deftest test-should-be-on-url-pass
  (testing ":alice should be on 'url' passes on substring match"
    (let [browser (make-fake-browser {:url "https://example.com/dashboard?tab=home"})
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice should be on 'example.com/dashboard'" ctx)]
      (is (map? result)))))

(deftest test-should-be-on-url-fail
  (testing ":alice should be on 'url' throws on mismatch"
    (let [browser (make-fake-browser {:url "https://other.com"})
          ctx (make-ctx-with-subject :alice browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Expected URL to contain"
                            (invoke-step ":alice should be on 'example.com'" ctx))))))

(deftest test-should-see-title-pass
  (testing ":alice should see the title 'text' passes on match"
    (let [browser (make-fake-browser {:title "My App"})
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice should see the title 'My App'" ctx)]
      (is (map? result)))))

(deftest test-should-see-title-fail
  (testing ":alice should see the title 'text' throws on mismatch"
    (let [browser (make-fake-browser {:title "Wrong Title"})
          ctx (make-ctx-with-subject :alice browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Expected page title"
                            (invoke-step ":alice should see the title 'My App'" ctx))))))

;; -----------------------------------------------------------------------------
;; SVO Metadata Tests
;; -----------------------------------------------------------------------------

(deftest test-subject-steps-have-svo-metadata
  (testing "all subject-extracting steps have SVO metadata"
    (let [subject-patterns [":alice opens the browser to 'https://example.com'"
                            ":alice clicks {:css \"#x\"}"
                            ":alice double-clicks {:css \"#x\"}"
                            ":alice right-clicks {:css \"#x\"}"
                            ":alice moves to {:css \"#x\"}"
                            ":alice drags {:id \"a\"} to {:id \"b\"}"
                            ":alice fills {:css \"#x\"} with 'text'"
                            ":alice should see 'text'"
                            ":alice should see 3 {:css \".x\"} elements"
                            ":alice should see {:css \"#x\"}"
                            ":alice should not see {:css \"#x\"}"
                            ":alice should be on 'https://x.com'"
                            ":alice should see the title 'X'"]]
      (doseq [text subject-patterns]
        (let [stepdef (find-stepdef text)]
          (is (some? stepdef) (str "No stepdef found for: " text))
          (when stepdef
            (is (some? (:metadata stepdef))
                (str "No metadata on stepdef for: " text))
            (when-let [meta (:metadata stepdef)]
              (is (= :web (:interface meta))
                  (str "Expected :interface :web for: " text))
              (is (some? (:svo meta))
                  (str "No :svo in metadata for: " text)))))))))
