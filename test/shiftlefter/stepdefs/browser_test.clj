(ns shiftlefter.stepdefs.browser-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.browser.ctx :as browser.ctx]
            [shiftlefter.browser.protocol :as bp]
            [shiftlefter.stepdefs.browser]))

;; -----------------------------------------------------------------------------
;; Fake Browser for Testing
;; -----------------------------------------------------------------------------

(defrecord FakeBrowser [calls config]
  bp/IBrowser
  ;; --- Kernel Operations ---
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
  ;; --- Query Operations ---
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
    (get @config :visible true))
  ;; --- Navigation ---
  (go-back! [this]
    (swap! calls conj {:op :go-back})
    this)
  (go-forward! [this]
    (swap! calls conj {:op :go-forward})
    this)
  (refresh! [this]
    (swap! calls conj {:op :refresh})
    this)
  ;; --- Scrolling ---
  (scroll-to! [this locator]
    (swap! calls conj {:op :scroll-to :locator locator})
    this)
  (scroll-to-position! [this position]
    (swap! calls conj {:op :scroll-to-position :position position})
    this)
  ;; --- Form Operations ---
  (clear! [this locator]
    (swap! calls conj {:op :clear :locator locator})
    this)
  (select! [this locator text]
    (swap! calls conj {:op :select :locator locator :text text})
    this)
  (press-key! [this key-str]
    (swap! calls conj {:op :press-key :key-str key-str})
    this)
  ;; --- Element Queries ---
  (get-attribute [_this locator attribute]
    (swap! calls conj {:op :get-attribute :locator locator :attribute attribute})
    (get @config :attribute-value "some-value"))
  (get-value [_this locator]
    (swap! calls conj {:op :get-value :locator locator})
    (get @config :input-value "input-text"))
  (enabled? [_this locator]
    (swap! calls conj {:op :enabled? :locator locator})
    (get @config :enabled true))
  ;; --- Alerts ---
  (accept-alert! [this]
    (swap! calls conj {:op :accept-alert})
    this)
  (dismiss-alert! [this]
    (swap! calls conj {:op :dismiss-alert})
    this)
  (get-alert-text [_this]
    (swap! calls conj {:op :get-alert-text})
    (if-let [text (get @config :alert-text)]
      text
      (throw (ex-info "no such alert" {:type :browser/no-alert}))))
  ;; --- Window Management ---
  (maximize-window! [this]
    (swap! calls conj {:op :maximize-window})
    this)
  (set-window-size! [this width height]
    (swap! calls conj {:op :set-window-size :width width :height height})
    this)
  (switch-to-next-window! [this]
    (swap! calls conj {:op :switch-to-next-window})
    this)
  ;; --- Frames ---
  (switch-to-frame! [this locator]
    (swap! calls conj {:op :switch-to-frame :locator locator})
    this)
  (switch-to-main-frame! [this]
    (swap! calls conj {:op :switch-to-main-frame})
    this))

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

;; =============================================================================
;; Kernel Action Tests (0.2.x)
;; =============================================================================

(deftest test-open-browser-to
  (testing ":user opens the browser to '<url>' navigates"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :user browser)
          result (invoke-step ":user opens the browser to 'https://example.com'" ctx)]
      (is (map? result))
      (is (= [{:op :open-to :url "https://example.com"}] (get-calls browser))))))

(deftest test-click
  (testing ":user clicks {locator} clicks element"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :user browser)
          result (invoke-step ":user clicks {:css \"#submit\"}" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= 1 (count calls)))
        (is (= :click (:op (first calls))))
        (is (= {:css "#submit"} (-> calls first :locator :q)))))))

(deftest test-double-click
  (testing ":user double-clicks {locator} double-clicks element"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :user browser)
          result (invoke-step ":user double-clicks {:id \"item\"}" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :doubleclick (:op (first calls))))))))

(deftest test-right-click
  (testing ":user right-clicks {locator} right-clicks element"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :user browser)
          result (invoke-step ":user right-clicks {:css \".menu\"}" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :rightclick (:op (first calls))))))))

(deftest test-move-to
  (testing ":user moves to {locator} moves mouse"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :user browser)
          result (invoke-step ":user moves to {:css \".hover-target\"}" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :move-to (:op (first calls))))))))

(deftest test-drag-to
  (testing ":user drags {from} to {to} drags element"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :user browser)
          result (invoke-step ":user drags {:id \"source\"} to {:id \"target\"}" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :drag-to (:op (first calls))))
        (is (= {:id "source"} (-> calls first :from :q)))
        (is (= {:id "target"} (-> calls first :to :q)))))))

(deftest test-fill
  (testing ":user fills {locator} with '<text>' fills input"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :user browser)
          result (invoke-step ":user fills {:name \"email\"} with 'test@example.com'" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :fill (:op (first calls))))
        (is (= {:name "email"} (-> calls first :locator :q)))
        (is (= "test@example.com" (:text (first calls))))))))

;; =============================================================================
;; Navigation Action Tests (0.3.6)
;; =============================================================================

(deftest test-goes-back
  (testing ":alice goes back calls go-back!"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice goes back" ctx)]
      (is (map? result))
      (is (= [{:op :go-back}] (get-calls browser))))))

(deftest test-goes-forward
  (testing ":alice goes forward calls go-forward!"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice goes forward" ctx)]
      (is (map? result))
      (is (= [{:op :go-forward}] (get-calls browser))))))

(deftest test-refreshes-the-page
  (testing ":alice refreshes the page calls refresh!"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice refreshes the page" ctx)]
      (is (map? result))
      (is (= [{:op :refresh}] (get-calls browser))))))

;; =============================================================================
;; Scrolling Action Tests (0.3.6)
;; =============================================================================

(deftest test-scrolls-to-element
  (testing ":alice scrolls to {locator}"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice scrolls to {:css \"#footer\"}" ctx)]
      (is (map? result))
      (let [call (first (get-calls browser))]
        (is (= :scroll-to (:op call)))
        (is (= {:css "#footer"} (-> call :locator :q)))))))

(deftest test-scrolls-to-top
  (testing ":alice scrolls to the top"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice scrolls to the top" ctx)]
      (is (map? result))
      (is (= [{:op :scroll-to-position :position :top}] (get-calls browser))))))

(deftest test-scrolls-to-bottom
  (testing ":alice scrolls to the bottom"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice scrolls to the bottom" ctx)]
      (is (map? result))
      (is (= [{:op :scroll-to-position :position :bottom}] (get-calls browser))))))

;; =============================================================================
;; Form Action Tests (0.3.6)
;; =============================================================================

(deftest test-clears-field
  (testing ":alice clears {locator}"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice clears {:id \"email\"}" ctx)]
      (is (map? result))
      (let [call (first (get-calls browser))]
        (is (= :clear (:op call)))
        (is (= {:id "email"} (-> call :locator :q)))))))

(deftest test-selects-from-dropdown
  (testing ":alice selects 'Option A' from {locator}"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice selects 'Option A' from {:id \"dropdown\"}" ctx)]
      (is (map? result))
      (let [call (first (get-calls browser))]
        (is (= :select (:op call)))
        (is (= {:id "dropdown"} (-> call :locator :q)))
        (is (= "Option A" (:text call)))))))

(deftest test-presses-single-key
  (testing ":alice presses enter sends key"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice presses enter" ctx)]
      (is (map? result))
      (let [call (first (get-calls browser))]
        (is (= :press-key (:op call)))
        (is (string? (:key-str call)))))))

(deftest test-presses-chord
  (testing ":alice presses shift+control+t sends chord"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice presses shift+control+t" ctx)]
      (is (map? result))
      (let [call (first (get-calls browser))]
        (is (= :press-key (:op call)))
        (is (string? (:key-str call)))
        ;; Chord string should be longer than a single character
        (is (> (count (:key-str call)) 1))))))

(deftest test-presses-single-char
  (testing ":alice presses a sends character 'a'"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice presses a" ctx)]
      (is (map? result))
      (let [call (first (get-calls browser))]
        (is (= :press-key (:op call)))
        (is (= "a" (:key-str call)))))))

(deftest test-presses-unknown-key-throws
  (testing ":alice presses unknownkey throws"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown key name"
                            (invoke-step ":alice presses unknownkey" ctx))))))

;; =============================================================================
;; Alert Action Tests (0.3.6)
;; =============================================================================

(deftest test-accepts-alert
  (testing ":alice accepts the alert"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice accepts the alert" ctx)]
      (is (map? result))
      (is (= [{:op :accept-alert}] (get-calls browser))))))

(deftest test-dismisses-alert
  (testing ":alice dismisses the alert"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice dismisses the alert" ctx)]
      (is (map? result))
      (is (= [{:op :dismiss-alert}] (get-calls browser))))))

;; =============================================================================
;; Window Management Action Tests (0.3.6)
;; =============================================================================

(deftest test-maximizes-window
  (testing ":alice maximizes the window"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice maximizes the window" ctx)]
      (is (map? result))
      (is (= [{:op :maximize-window}] (get-calls browser))))))

(deftest test-resizes-window
  (testing ":alice resizes the window to 1024x768"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice resizes the window to 1024x768" ctx)]
      (is (map? result))
      (is (= [{:op :set-window-size :width 1024 :height 768}] (get-calls browser))))))

(deftest test-switches-to-next-window
  (testing ":alice switches to the next window"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice switches to the next window" ctx)]
      (is (map? result))
      (is (= [{:op :switch-to-next-window}] (get-calls browser))))))

;; =============================================================================
;; Frame Action Tests (0.3.6)
;; =============================================================================

(deftest test-switches-to-frame
  (testing ":alice switches to frame {locator}"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice switches to frame {:id \"my-iframe\"}" ctx)]
      (is (map? result))
      (let [call (first (get-calls browser))]
        (is (= :switch-to-frame (:op call)))
        (is (= {:id "my-iframe"} (-> call :locator :q)))))))

(deftest test-switches-to-main-frame
  (testing ":alice switches to the main frame"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice switches to the main frame" ctx)]
      (is (map? result))
      (is (= [{:op :switch-to-main-frame}] (get-calls browser))))))

;; =============================================================================
;; Vector Locator Syntax Tests
;; =============================================================================

(deftest test-vector-locator-syntax
  (testing "Vector locator syntax [:css \"...\"] works with subject steps"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :user browser)
          result (invoke-step ":user clicks [:css \"#login\"]" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :click (:op (first calls))))
        ;; Vector normalized to map
        (is (= {:css "#login"} (-> calls first :locator :q)))))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest test-no-browser-configured
  (testing "throws when no browser in ctx"
    (let [ctx {}]  ;; flat ctx with no browser
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No browser session for subject"
                            (invoke-step ":user opens the browser to 'https://example.com'" ctx))))))

(deftest test-invalid-locator
  (testing "throws on invalid locator"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :user browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid locator"
                            (invoke-step ":user clicks {:invalid \"x\"}" ctx))))))

;; =============================================================================
;; Acceptance Criteria
;; =============================================================================

(deftest test-acceptance-criteria
  (testing "stepdefs call resolve-locator and invoke protocol"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-subject :user browser)]
      ;; Navigation
      (invoke-step ":user opens the browser to 'https://example.com'" ctx)
      ;; Click with resolved locator
      (invoke-step ":user clicks {:css \"a.more\"}" ctx)

      (let [calls (get-calls browser)]
        (is (= 2 (count calls)))
        (is (= :open-to (:op (first calls))))
        (is (= :click (:op (second calls))))))))

;; =============================================================================
;; Multi-Subject Routing Tests
;; =============================================================================

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

;; =============================================================================
;; Verification Step Tests (0.3.5 — existing)
;; =============================================================================

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

(deftest test-should-see-count-retry
  (testing ":alice should see N elements retries until count matches"
    (let [browser (make-fake-browser {:element-count 0})
          ctx (make-ctx-with-subject :alice browser)
          ;; After 150ms, "render" the 3 elements
          _ (future (Thread/sleep 150)
                    (reset! (:config browser) {:element-count 3}))]
      ;; Use short timeout to keep test fast, but long enough for the future
      (binding [shiftlefter.stepdefs.browser/*retry-timeout-ms* 2000]
        (let [result (invoke-step ":alice should see 3 {:css \".item\"} elements" ctx)]
          (is (map? result))
          ;; Should have been called multiple times (retried)
          (is (> (count (filter #(= :element-count (:op %)) (get-calls browser))) 1)
              "element-count should have been called more than once (retried)"))))))

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

;; =============================================================================
;; Verification Step Tests (0.3.6 — new)
;; =============================================================================

(deftest test-should-see-element-text-pass
  (testing ":alice should see {locator} with text 'hello' passes"
    (let [browser (make-fake-browser {:page-text "hello world"})
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice should see {:id \"msg\"} with text 'hello'" ctx)]
      (is (map? result))
      (let [call (first (get-calls browser))]
        (is (= :get-text (:op call)))
        (is (= {:id "msg"} (-> call :locator :q)))))))

(deftest test-should-see-element-text-fail
  (testing ":alice should see {locator} with text 'missing' throws"
    (let [browser (make-fake-browser {:page-text "something else"})
          ctx (make-ctx-with-subject :alice browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Expected element text to contain"
                            (invoke-step ":alice should see {:id \"msg\"} with text 'missing'" ctx))))))

(deftest test-should-see-element-value-pass
  (testing ":alice should see {locator} with value 'hello' passes"
    (let [browser (make-fake-browser {:input-value "hello"})
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice should see {:id \"email\"} with value 'hello'" ctx)]
      (is (map? result))
      (is (= :get-value (:op (first (get-calls browser))))))))

(deftest test-should-see-element-value-fail
  (testing ":alice should see {locator} with value 'wrong' throws"
    (let [browser (make-fake-browser {:input-value "correct"})
          ctx (make-ctx-with-subject :alice browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Expected element value"
                            (invoke-step ":alice should see {:id \"email\"} with value 'wrong'" ctx))))))

(deftest test-should-see-attribute-pass
  (testing ":alice should see {locator} with attribute 'href' equal to '/home' passes"
    (let [browser (make-fake-browser {:attribute-value "/home"})
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice should see {:id \"link\"} with attribute 'href' equal to '/home'" ctx)]
      (is (map? result))
      (let [call (first (get-calls browser))]
        (is (= :get-attribute (:op call)))
        (is (= "href" (:attribute call)))))))

(deftest test-should-see-attribute-fail
  (testing ":alice should see {locator} with attribute 'href' equal to '/wrong' throws"
    (let [browser (make-fake-browser {:attribute-value "/home"})
          ctx (make-ctx-with-subject :alice browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Expected attribute 'href'"
                            (invoke-step ":alice should see {:id \"link\"} with attribute 'href' equal to '/wrong'" ctx))))))

(deftest test-should-see-enabled-pass
  (testing ":alice should see {locator} enabled passes"
    (let [browser (make-fake-browser {:enabled true})
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice should see {:id \"submit\"} enabled" ctx)]
      (is (map? result)))))

(deftest test-should-see-enabled-fail
  (testing ":alice should see {locator} enabled throws when disabled"
    (let [browser (make-fake-browser {:enabled false})
          ctx (make-ctx-with-subject :alice browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Expected element to be enabled"
                            (invoke-step ":alice should see {:id \"submit\"} enabled" ctx))))))

(deftest test-should-see-disabled-pass
  (testing ":alice should see {locator} disabled passes"
    (let [browser (make-fake-browser {:enabled false})
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice should see {:id \"submit\"} disabled" ctx)]
      (is (map? result)))))

(deftest test-should-see-disabled-fail
  (testing ":alice should see {locator} disabled throws when enabled"
    (let [browser (make-fake-browser {:enabled true})
          ctx (make-ctx-with-subject :alice browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Expected element to be disabled"
                            (invoke-step ":alice should see {:id \"submit\"} disabled" ctx))))))

(deftest test-should-see-alert-pass
  (testing ":alice should see an alert passes when alert present"
    (let [browser (make-fake-browser {:alert-text "Are you sure?"})
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice should see an alert" ctx)]
      (is (map? result)))))

(deftest test-should-see-alert-fail
  (testing ":alice should see an alert throws when no alert"
    (let [browser (make-fake-browser)  ;; no :alert-text → get-alert-text throws
          ctx (make-ctx-with-subject :alice browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Expected an alert to be present"
                            (invoke-step ":alice should see an alert" ctx))))))

(deftest test-should-see-alert-with-text-pass
  (testing ":alice should see an alert with 'Are you sure?' passes"
    (let [browser (make-fake-browser {:alert-text "Are you sure?"})
          ctx (make-ctx-with-subject :alice browser)
          result (invoke-step ":alice should see an alert with 'Are you sure?'" ctx)]
      (is (map? result)))))

(deftest test-should-see-alert-with-text-fail
  (testing ":alice should see an alert with 'wrong' throws"
    (let [browser (make-fake-browser {:alert-text "Are you sure?"})
          ctx (make-ctx-with-subject :alice browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Expected alert text to contain"
                            (invoke-step ":alice should see an alert with 'wrong text'" ctx))))))

;; =============================================================================
;; SVO Metadata Tests
;; =============================================================================

(deftest test-subject-steps-have-svo-metadata
  (testing "all subject-extracting steps have SVO metadata"
    (let [subject-patterns [;; Kernel actions
                            ":alice opens the browser to 'https://example.com'"
                            ":alice clicks {:css \"#x\"}"
                            ":alice double-clicks {:css \"#x\"}"
                            ":alice right-clicks {:css \"#x\"}"
                            ":alice moves to {:css \"#x\"}"
                            ":alice drags {:id \"a\"} to {:id \"b\"}"
                            ":alice fills {:css \"#x\"} with 'text'"
                            ;; Navigation actions
                            ":alice goes back"
                            ":alice goes forward"
                            ":alice refreshes the page"
                            ;; Scrolling actions
                            ":alice scrolls to {:css \"#footer\"}"
                            ":alice scrolls to the top"
                            ;; Form actions
                            ":alice clears {:id \"email\"}"
                            ":alice selects 'Option A' from {:id \"dropdown\"}"
                            ":alice presses enter"
                            ;; Alert actions
                            ":alice accepts the alert"
                            ":alice dismisses the alert"
                            ;; Window management
                            ":alice maximizes the window"
                            ":alice resizes the window to 1024x768"
                            ":alice switches to the next window"
                            ;; Frame actions
                            ":alice switches to frame {:id \"iframe\"}"
                            ":alice switches to the main frame"
                            ;; Existing verification steps
                            ":alice should see 'text'"
                            ":alice should see 3 {:css \".x\"} elements"
                            ":alice should see {:css \"#x\"}"
                            ":alice should not see {:css \"#x\"}"
                            ":alice should be on 'https://x.com'"
                            ":alice should see the title 'X'"
                            ;; New verification steps
                            ":alice should see {:id \"x\"} with text 'hello'"
                            ":alice should see {:id \"x\"} with value 'hello'"
                            ":alice should see {:id \"x\"} with attribute 'href' equal to '/home'"
                            ":alice should see {:id \"x\"} enabled"
                            ":alice should see {:id \"x\"} disabled"
                            ":alice should see an alert"
                            ":alice should see an alert with 'text'"]]
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

;; =============================================================================
;; Regex Disambiguation Tests
;; =============================================================================

(deftest test-no-ambiguous-step-matches
  (testing "new verification steps don't ambiguously match existing patterns"
    (let [;; Each text should match exactly ONE stepdef
          texts [":alice should see {:id \"x\"} with text 'hello'"
                 ":alice should see {:id \"x\"} with value 'hello'"
                 ":alice should see {:id \"x\"} with attribute 'href' equal to '/home'"
                 ":alice should see {:id \"x\"} enabled"
                 ":alice should see {:id \"x\"} disabled"
                 ":alice should see {:id \"x\"}"
                 ":alice should see an alert"
                 ":alice should see an alert with 'text'"]]
      (doseq [text texts]
        (let [matches (filter #(re-matches (:pattern %) text) (registry/all-stepdefs))]
          (is (= 1 (count matches))
              (str "Expected exactly 1 match for '" text "' but got "
                   (count matches) ": "
                   (mapv #(str (:pattern %)) matches))))))))
