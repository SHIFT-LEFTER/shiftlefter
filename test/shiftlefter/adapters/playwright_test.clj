(ns shiftlefter.adapters.playwright-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.adapters.playwright :as playwright]
            [shiftlefter.adapters.registry :as registry]
            [shiftlefter.browser.protocol :as bp]
            [shiftlefter.webdriver.playwright.browser :as pw-browser]))

;; =============================================================================
;; Shared
;; =============================================================================

(def ^:private live-playwright?
  (= "1" (System/getenv "SHIFTLEFTER_LIVE_PLAYWRIGHT")))

;; =============================================================================
;; Unit Tests (no live browser required)
;; =============================================================================

(deftest test-playwright-adapter-in-registry
  (testing "Playwright adapter is registered"
    (let [adapter (registry/get-adapter :playwright)]
      (is (map? adapter))
      (is (fn? (:factory adapter)))
      (is (fn? (:cleanup adapter))))))

(deftest test-create-browser-error-handling
  (if live-playwright?
    (testing "create-browser returns structured error on failure"
      ;; Test with an invalid browser-type to trigger a runtime error.
      ;; The adapter should never throw — always return {:error ...}.
      (let [result (playwright/create-browser
                    {:headless true :adapter-opts {:browser-type :invalid}})]
        (is (:error result) "Invalid browser-type should return error, not throw")
        (is (= :adapter/create-failed (-> result :error :type)))))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))

(deftest test-invalid-pruning-strategy-is-a-structured-error
  (testing "an unknown :pruning value errors before any browser launches (sl-tnx)"
    (let [result (playwright/create-browser {:headless true :pruning :turbo})]
      (is (= :adapter/create-failed (-> result :error :type)))
      (is (re-find #":pruning" (-> result :error :message))
          "the error names the offending key")
      (is (re-find #":fast \(default\) or :safe" (-> result :error :message))
          "the error teaches the valid values"))))

(deftest test-close-browser-rejects-a-browserless-capability
  (testing "wrong shape (e.g. the wrapped {:ok {...}} map) is an error, not silent green (sl-9vag)"
    (doseq [bad-shape [{:ok {:browser :fake}} {} nil]]
      (let [result (playwright/close-browser bad-shape)]
        (is (= :adapter/cleanup-failed (-> result :error :type))
            (str "shape: " (pr-str bad-shape)))))))

;; =============================================================================
;; Capture (sl-sehc) — unit: the FULL chain (adapter capture fn → dynamic
;; require/resolve → browser impl fn → Page call) with a reified Page, no
;; live browser (the browser_test.clj reify pattern; Playwright's Java API
;; is interfaces, so the surface reifies).
;; =============================================================================

(defn- fake-capture-page
  "A Page answering only the two capture calls. The impl calls the no-arg
   Page.screenshot() default method, which delegates to this 1-arg override."
  [shot-bytes content-str]
  (reify com.microsoft.playwright.Page
    (^bytes screenshot [_ ^com.microsoft.playwright.Page$ScreenshotOptions _opts]
      shot-bytes)
    (^String content [_] content-str)))

(defn- capture-req
  "A capture request as the engine builds it: the handle is the capability
   map, {:browser PlaywrightBrowser}."
  [page]
  {:handle {:browser (pw-browser/make-playwright-browser
                      page {:playwright nil :browser nil :context nil
                            :last-dialog (atom nil) :active-frame (atom nil)})}})

(deftest test-capture-screenshot-through-resolve-chain
  (testing "bytes come back verbatim with the PNG envelope keys"
    (let [data (byte-array [(unchecked-byte 0x89) 0x50 0x4E 0x47 1 2 3])
          result (playwright/capture-screenshot
                  (capture-req (fake-capture-page data "<html/>")))]
      (is (identical? data (:data result)))
      (is (= "image/png" (:media-type result)))
      (is (= "png" (:ext result))))))

(deftest test-capture-page-source-through-resolve-chain
  (testing "the page's HTML comes back with the text/html envelope keys"
    (let [result (playwright/capture-page-source
                  (capture-req (fake-capture-page (byte-array 0)
                                                  "<html><p>sehc</p></html>")))]
      (is (= "<html><p>sehc</p></html>" (:data result)))
      (is (= "text/html" (:media-type result)))
      (is (= "html" (:ext result))))))

(deftest test-capture-empty-screenshot-throws
  (testing "empty bytes are a capture failure (D4 converts), never a silent ref"
    (let [e (try (playwright/capture-screenshot
                  (capture-req (fake-capture-page (byte-array 0) "")))
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :capture/empty-screenshot (:type (ex-data e)))))))

(deftest test-capture-requires-browser-in-handle
  (testing "a handle without :browser is a capture infrastructure failure"
    (doseq [f [playwright/capture-screenshot playwright/capture-page-source]]
      (let [e (try (f {:handle {}})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :capture/no-browser (:type (ex-data e))))))))

;; =============================================================================
;; Integration Tests (require live Playwright browser)
;; =============================================================================

(deftest test-playwright-adapter-create-close
  (if live-playwright?
    (testing "create-browser and close-browser via adapter"
      (let [result (playwright/create-browser {:headless true})]
        (is (:ok result) "create-browser should succeed")
        (let [{:keys [browser]} (:ok result)]
          (is (some? browser))
          (is (satisfies? bp/IBrowser browser))
          ;; Quick smoke test
          (bp/open-to! browser "https://example.com")
          (is (= "Example Domain" (bp/get-title browser)))
          ;; Clean up
          (let [close-result (playwright/close-browser {:browser browser})]
            (is (= {:ok :closed} close-result))))))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))

(deftest test-playwright-adapter-opts-passthrough
  (if live-playwright?
    (testing "adapter-opts are passed through to Playwright"
      ;; Test with :browser-type option
      (let [result (playwright/create-browser
                    {:headless true
                     :adapter-opts {:browser-type :chromium}})]
        (is (:ok result) "create-browser with adapter-opts should succeed")
        (when (:ok result)
          (let [{:keys [browser]} (:ok result)]
            (bp/open-to! browser "https://example.com")
            (is (= "Example Domain" (bp/get-title browser)))
            (playwright/close-browser {:browser browser})))))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))

(deftest test-playwright-via-registry
  (if live-playwright?
    (testing "create-capability :playwright works via registry"
      (let [result (registry/create-capability :playwright {:headless true})]
        (is (:ok result) "create-capability should succeed")
        (when (:ok result)
          (let [{:keys [browser]} (:ok result)]
            (is (satisfies? bp/IBrowser browser))
            (registry/cleanup-capability :playwright {:browser browser})))))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))

(deftest test-playwright-capture-live
  (if live-playwright?
    (testing "live screenshot + page-source via the registry capture specs (sl-sehc)"
      (let [result (playwright/create-browser {:headless true})]
        (is (:ok result) "create-browser should succeed")
        (let [{:keys [browser]} (:ok result)]
          (try
            (bp/open-to! browser (str "data:text/html,<html><head><title>cap"
                                      "</title></head><body><p>sehc-live-marker"
                                      "</p></body></html>"))
            (let [handle {:browser browser}
                  shot ((:fn (registry/capture-spec :playwright :screenshot
                                                    registry/default-registry))
                        {:handle handle})
                  src ((:fn (registry/capture-spec :playwright :page-source
                                                   registry/default-registry))
                       {:handle handle})]
              (is (= "image/png" (:media-type shot)))
              (is (= "png" (:ext shot)))
              (let [^bytes data (:data shot)]
                (is (> (alength data) 100) "a real PNG, not a stub")
                (is (= [(unchecked-byte 0x89) (byte 0x50) (byte 0x4E) (byte 0x47)]
                       (vec (take 4 data)))
                    "PNG magic bytes"))
              (is (= "text/html" (:media-type src)))
              (is (= "html" (:ext src)))
              (is (re-find #"sehc-live-marker" (:data src))))
            (finally
              (playwright/close-browser {:browser browser}))))))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))
