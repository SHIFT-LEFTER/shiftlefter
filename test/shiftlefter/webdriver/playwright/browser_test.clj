(ns shiftlefter.webdriver.playwright.browser-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [shiftlefter.webdriver.playwright.browser :as pw-browser]
            [shiftlefter.webdriver.playwright.keys :as pw-keys]
            [shiftlefter.browser.protocol :as bp]))

;; =============================================================================
;; Unit Tests (no live browser required)
;; =============================================================================

;; -----------------------------------------------------------------------------
;; Protocol Satisfaction
;; -----------------------------------------------------------------------------

(deftest protocol-satisfaction-test
  (testing "PlaywrightBrowser satisfies IBrowser protocol"
    (let [browser (pw-browser/make-playwright-browser
                   nil {:playwright nil :browser nil :context nil
                        :last-dialog (atom nil) :active-frame (atom nil)})]
      (is (satisfies? bp/IBrowser browser)))))

;; -----------------------------------------------------------------------------
;; Locator Translation
;; -----------------------------------------------------------------------------

(deftest locator-translation-test
  (testing "CSS selector"
    (is (= ".login-form"
           (#'pw-browser/resolve-playwright-selector {:q {:css ".login-form"}}))))

  (testing "XPath selector gets xpath= prefix"
    (is (= "xpath=//div[@id='main']"
           (#'pw-browser/resolve-playwright-selector {:q {:xpath "//div[@id='main']"}}))))

  (testing "ID selector becomes CSS #id"
    (is (= "#username"
           (#'pw-browser/resolve-playwright-selector {:q {:id "username"}}))))

  (testing "Tag selector passes through"
    (is (= "button"
           (#'pw-browser/resolve-playwright-selector {:q {:tag "button"}}))))

  (testing "Class selector becomes CSS .class"
    (is (= ".btn-primary"
           (#'pw-browser/resolve-playwright-selector {:q {:class "btn-primary"}}))))

  (testing "Name selector becomes attribute selector"
    (is (= "[name='email']"
           (#'pw-browser/resolve-playwright-selector {:q {:name "email"}}))))

  (testing "String passthrough"
    (is (= "#direct-selector"
           (#'pw-browser/resolve-playwright-selector {:q "#direct-selector"}))))

  (testing "Keyword passthrough"
    (is (= "my-element"
           (#'pw-browser/resolve-playwright-selector {:q :my-element}))))

  (testing "Unknown map selector throws"
    (is (thrown? clojure.lang.ExceptionInfo
                (#'pw-browser/resolve-playwright-selector {:q {:bogus "x"}})))))

;; -----------------------------------------------------------------------------
;; Key Translation
;; -----------------------------------------------------------------------------

(deftest key-translation-test
  (testing "Single character key"
    (is (= "a" (pw-keys/webdriver-char->playwright \a)))
    (is (= "1" (pw-keys/webdriver-char->playwright \1))))

  (testing "WebDriver special keys → Playwright names"
    (is (= "Enter" (pw-keys/webdriver-char->playwright (char 0xE006))))
    (is (= "Enter" (pw-keys/webdriver-char->playwright (char 0xE007))))
    (is (= "Tab" (pw-keys/webdriver-char->playwright (char 0xE004))))
    (is (= "Backspace" (pw-keys/webdriver-char->playwright (char 0xE003))))
    (is (= "Escape" (pw-keys/webdriver-char->playwright (char 0xE00C))))
    (is (= "ArrowUp" (pw-keys/webdriver-char->playwright (char 0xE013))))
    (is (= "ArrowDown" (pw-keys/webdriver-char->playwright (char 0xE015))))
    (is (= "ArrowLeft" (pw-keys/webdriver-char->playwright (char 0xE012))))
    (is (= "ArrowRight" (pw-keys/webdriver-char->playwright (char 0xE014))))
    (is (= "Delete" (pw-keys/webdriver-char->playwright (char 0xE017))))
    (is (= "Home" (pw-keys/webdriver-char->playwright (char 0xE011))))
    (is (= "End" (pw-keys/webdriver-char->playwright (char 0xE010))))
    (is (= "F1" (pw-keys/webdriver-char->playwright (char 0xE031)))))

  (testing "Modifier keys"
    (is (= "Shift" (pw-keys/webdriver-char->playwright (char 0xE008))))
    (is (= "Control" (pw-keys/webdriver-char->playwright (char 0xE009))))
    (is (= "Alt" (pw-keys/webdriver-char->playwright (char 0xE00A))))
    (is (= "Meta" (pw-keys/webdriver-char->playwright (char 0xE03D)))))

  (testing "Single key string translation"
    (is (= "Enter" (pw-keys/translate-key-string (str (char 0xE007)))))
    (is (= "a" (pw-keys/translate-key-string "a"))))

  (testing "Chord string translation"
    ;; Etaoin chord format: modifier-down, key, modifier-up
    ;; e.g., Shift+a = [shift-down, a, shift-up]
    (let [shift-down (char 0xE008)
          shift-up (char 0xE008)
          chord-str (str shift-down "a" shift-up)]
      (is (= "Shift+a" (pw-keys/translate-key-string chord-str))))))

;; =============================================================================
;; Integration Tests (require live Playwright browser)
;; =============================================================================

(def ^:private live-playwright?
  (= "1" (System/getenv "SHIFTLEFTER_LIVE_PLAYWRIGHT")))

;; Shared browser instance for integration tests
(def ^:private test-browser (atom nil))

(defn- integration-fixture [f]
  (if live-playwright?
    (let [browser (pw-browser/launch-playwright-browser {:headless true})]
      (try
        (reset! test-browser browser)
        (f)
        (finally
          (pw-browser/close-playwright-browser browser)
          (reset! test-browser nil))))
    (f)))

(use-fixtures :once integration-fixture)

(deftest playwright-launch-test
  (if live-playwright?
    (testing "PlaywrightBrowser launches and has valid page"
      (let [browser @test-browser]
        (is (some? browser))
        (is (some? (:page browser)))
        (is (satisfies? bp/IBrowser browser))))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))

(deftest playwright-navigation-test
  (if live-playwright?
    (testing "Navigate, get URL and title"
      (let [browser @test-browser]
        (bp/open-to! browser "https://example.com")
        (is (= "https://example.com/" (bp/get-url browser)))
        (is (= "Example Domain" (bp/get-title browser)))))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))

(deftest playwright-element-query-test
  (if live-playwright?
    (testing "Get text, visibility, element count"
      (let [browser @test-browser]
        (bp/open-to! browser "https://example.com")
        ;; Page has an h1 with "Example Domain"
        (is (= "Example Domain" (bp/get-text browser {:q {:tag "h1"}})))
        (is (true? (bp/visible? browser {:q {:tag "h1"}})))
        (is (= 1 (bp/element-count browser {:q {:tag "h1"}})))))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))

(deftest playwright-navigation-history-test
  (if live-playwright?
    (testing "Back and forward navigation"
      (let [browser @test-browser]
        (bp/open-to! browser "https://example.com")
        (bp/open-to! browser "https://www.iana.org/domains/reserved")
        (bp/go-back! browser)
        (is (= "https://example.com/" (bp/get-url browser)))
        (bp/go-forward! browser)
        (is (str/includes? (bp/get-url browser) "iana.org"))))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))

(deftest playwright-window-size-test
  (if live-playwright?
    (testing "Set window size via viewport"
      (let [browser @test-browser]
        ;; Should not throw
        (bp/set-window-size! browser 800 600)
        (bp/maximize-window! browser)
        (is true "Window size operations completed without error")))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))

(deftest playwright-scroll-test
  (if live-playwright?
    (testing "Scroll to element and positions"
      (let [browser @test-browser]
        (bp/open-to! browser "https://example.com")
        (bp/scroll-to! browser {:q {:tag "h1"}})
        (bp/scroll-to-position! browser :top)
        (bp/scroll-to-position! browser :bottom)
        (is true "Scroll operations completed without error")))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))

(deftest playwright-refresh-test
  (if live-playwright?
    (testing "Page refresh preserves URL"
      (let [browser @test-browser]
        (bp/open-to! browser "https://example.com")
        (bp/refresh! browser)
        (is (= "https://example.com/" (bp/get-url browser)))))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))
