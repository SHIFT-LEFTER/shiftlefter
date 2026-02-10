(ns shiftlefter.webdriver.etaoin.browser
  "Etaoin implementation of IBrowser protocol.

   Wraps an Etaoin driver to provide the standard browser operations
   used by ShiftLefter stepdefs."
  (:require [shiftlefter.browser.protocol :as bp]
            [etaoin.api :as eta]))

;; -----------------------------------------------------------------------------
;; EtaoinBrowser Implementation
;; -----------------------------------------------------------------------------

(defrecord EtaoinBrowser [driver]
  bp/IBrowser

  ;; --- Kernel Operations ---

  (open-to! [this url]
    (eta/go driver url)
    this)

  (click! [this locator]
    (eta/click driver (:q locator))
    this)

  (doubleclick! [this locator]
    (eta/double-click driver (:q locator))
    this)

  (rightclick! [this locator]
    (eta/right-click-on driver (:q locator))
    this)

  (move-to! [this locator]
    ;; Etaoin doesn't have a simple move-to, use click-based hover workaround
    ;; For now, just scroll element into view via query
    (eta/query driver (:q locator))
    this)

  (drag-to! [this from-locator to-locator]
    (eta/drag-and-drop driver (:q from-locator) (:q to-locator))
    this)

  (fill! [this locator text]
    (eta/fill driver (:q locator) text)
    this)

  (element-count [_this locator]
    (count (eta/query-all driver (:q locator))))

  ;; --- Query Operations ---

  (get-text [_this locator]
    (eta/get-element-text driver (:q locator)))

  (get-url [_this]
    (eta/get-url driver))

  (get-title [_this]
    (eta/get-title driver))

  (visible? [_this locator]
    (try
      (eta/displayed? driver (:q locator))
      (catch Exception _
        false)))

  ;; --- Navigation ---

  (go-back! [this]
    (eta/back driver)
    this)

  (go-forward! [this]
    (eta/forward driver)
    this)

  (refresh! [this]
    (eta/refresh driver)
    this)

  ;; --- Scrolling ---

  (scroll-to! [this locator]
    (eta/scroll-query driver (:q locator))
    this)

  (scroll-to-position! [this position]
    (case position
      :top (eta/scroll-top driver)
      :bottom (eta/scroll-bottom driver))
    this)

  ;; --- Form Operations ---

  (clear! [this locator]
    (eta/clear driver (:q locator))
    this)

  (select! [this locator text]
    (eta/select driver (:q locator) text)
    this)

  (press-key! [this key-str]
    (eta/fill-active driver key-str)
    this)

  ;; --- Element Queries ---

  (get-attribute [_this locator attribute]
    (eta/get-element-attr driver (:q locator) (keyword attribute)))

  (get-value [_this locator]
    (eta/get-element-value driver (:q locator)))

  (enabled? [_this locator]
    (try
      (eta/enabled? driver (:q locator))
      (catch Exception _
        false)))

  ;; --- Alerts ---

  (accept-alert! [this]
    (eta/accept-alert driver)
    this)

  (dismiss-alert! [this]
    (eta/dismiss-alert driver)
    this)

  (get-alert-text [_this]
    (eta/get-alert-text driver))

  ;; --- Window Management ---

  (maximize-window! [this]
    (eta/maximize driver)
    this)

  (set-window-size! [this width height]
    (eta/set-window-size driver width height)
    this)

  (switch-to-next-window! [this]
    (eta/switch-window-next driver)
    this)

  ;; --- Frames ---

  (switch-to-frame! [this locator]
    (eta/switch-frame driver (:q locator))
    this)

  (switch-to-main-frame! [this]
    (eta/switch-frame-top driver)
    this))

;; -----------------------------------------------------------------------------
;; Factory
;; -----------------------------------------------------------------------------

(defn make-etaoin-browser
  "Create an EtaoinBrowser from an Etaoin driver.

   The driver should already have a session (created via eta/chrome or similar)."
  [etaoin-driver]
  (->EtaoinBrowser etaoin-driver))

(defn etaoin-driver
  "Extract the underlying Etaoin driver from an EtaoinBrowser."
  [browser]
  (:driver browser))
