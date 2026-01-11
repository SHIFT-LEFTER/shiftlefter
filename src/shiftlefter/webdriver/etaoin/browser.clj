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

  (element-count [this locator]
    (count (eta/query-all driver (:q locator)))))

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
