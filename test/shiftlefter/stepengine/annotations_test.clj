(ns shiftlefter.stepengine.annotations-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.stepengine.annotations :as ann]))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- make-step
  ([text]
   {:step/id (java.util.UUID/randomUUID)
    :step/text text
    :step/arguments []})
  ([text location]
   (assoc (make-step text) :step/location location)))

(defn- make-pickle
  [steps]
  {:pickle/id (java.util.UUID/randomUUID)
   :pickle/name "test"
   :pickle/steps (mapv #(if (string? %) (make-step %) %) steps)})

(def ^:private web-sms-ifaces
  {:web {:type :web :adapter :etaoin}
   :sms {:type :sms :adapter :twilio}})

;; -----------------------------------------------------------------------------
;; strip-annotation
;; -----------------------------------------------------------------------------

(deftest test-strip-annotation-present
  (testing "Strips [:iface] prefix including trailing whitespace"
    (is (= "foo bar"
           (ann/strip-annotation "[:sms] foo bar")))
    (is (= ":user/alice receives a message"
           (ann/strip-annotation "[:sms] :user/alice receives a message")))))

(deftest test-strip-annotation-absent
  (testing "Returns text unchanged when no prefix"
    (is (= "foo bar" (ann/strip-annotation "foo bar")))
    (is (= ":user/alice clicks button"
           (ann/strip-annotation ":user/alice clicks button")))))

(deftest test-strip-annotation-various-keywords
  (testing "Handles dots, slashes, hyphens, underscores"
    (is (= "x" (ann/strip-annotation "[:web] x")))
    (is (= "x" (ann/strip-annotation "[:my.ns/iface] x")))
    (is (= "x" (ann/strip-annotation "[:multi-word] x")))
    (is (= "x" (ann/strip-annotation "[:snake_case] x")))))

(deftest test-strip-annotation-only-prefix-matched
  (testing "Only leading prefix is stripped; later [:...] patterns are left alone"
    (is (= "stuff [:inline] more"
           (ann/strip-annotation "[:sms] stuff [:inline] more")))
    ;; When [:...] is mid-text (not at start), it's untouched
    (is (= "stuff [:inline] more"
           (ann/strip-annotation "stuff [:inline] more")))))

;; -----------------------------------------------------------------------------
;; annotate-step — happy path
;; -----------------------------------------------------------------------------

(deftest test-annotate-no-prefix
  (testing "Steps without prefix pass through unchanged"
    (let [{:keys [pickle errors]}
          (ann/annotate-pickle (make-pickle ["I click button"])
                               web-sms-ifaces false)]
      (is (empty? errors))
      (let [step (-> pickle :pickle/steps first)]
        (is (= "I click button" (:step/text step)))
        (is (nil? (:step/declared-interface step)))))))

(deftest test-annotate-prefix-attaches-keyword
  (testing "Steps with prefix get :step/declared-interface attached; text unchanged"
    (let [{:keys [pickle errors]}
          (ann/annotate-pickle (make-pickle ["[:sms] alice receives message"])
                               web-sms-ifaces false)]
      (is (empty? errors))
      (let [step (-> pickle :pickle/steps first)]
        ;; Text preserved verbatim
        (is (= "[:sms] alice receives message" (:step/text step)))
        ;; Declared interface attached
        (is (= :sms (:step/declared-interface step)))))))

(deftest test-annotate-multiple-interfaces
  (testing "Different prefixes attach different keywords"
    (let [{:keys [pickle errors]}
          (ann/annotate-pickle
           (make-pickle ["[:web] click thing"
                         "[:sms] send message"
                         "no annotation here"])
           web-sms-ifaces false)]
      (is (empty? errors))
      (let [steps (:pickle/steps pickle)]
        (is (= :web (:step/declared-interface (nth steps 0))))
        (is (= :sms (:step/declared-interface (nth steps 1))))
        (is (nil? (:step/declared-interface (nth steps 2))))))))

;; -----------------------------------------------------------------------------
;; annotate-step — error cases
;; -----------------------------------------------------------------------------

(deftest test-annotate-unknown-interface
  (testing "Interface not in :interfaces config produces error"
    (let [{:keys [pickle errors]}
          (ann/annotate-pickle (make-pickle ["[:whatsapp] send message"])
                               web-sms-ifaces false)]
      (is (= 1 (count errors)))
      (let [err (first errors)]
        (is (= :annotation/unknown-interface (:type err)))
        (is (= :whatsapp (:declared-interface err)))
        (is (= [:sms :web] (:known-interfaces err)))
        (is (re-find #":whatsapp" (:message err))))
      ;; Step should not have declared-interface attached on error
      (let [step (-> pickle :pickle/steps first)]
        (is (nil? (:step/declared-interface step)))))))

;; -----------------------------------------------------------------------------
;; Did-you-mean (sl-563)
;; -----------------------------------------------------------------------------

(deftest test-did-you-mean-typo-suggestion
  (testing "Single-edit typo suggests the closest known interface"
    (let [{:keys [errors]}
          (ann/annotate-pickle (make-pickle ["[:wbe] click thing"])
                               web-sms-ifaces false)
          err (first errors)]
      (is (= :web (:did-you-mean err)))
      (is (re-find #"Did you mean \[:web\]\?" (:message err))))))

(deftest test-did-you-mean-prefers-closest
  (testing "Closest-by-edit-distance wins when multiple ifaces are configured"
    (let [{:keys [errors]}
          (ann/annotate-pickle (make-pickle ["[:slak] send"])
                               {:slack {} :sms {} :web {}} false)]
      (is (= :slack (-> errors first :did-you-mean))))))

(deftest test-did-you-mean-no-suggestion-when-too-distant
  (testing "Wildly different keyword → no :did-you-mean and no \"Did you mean\" text"
    (let [{:keys [errors]}
          (ann/annotate-pickle
           (make-pickle ["[:totally-different] x"])
           web-sms-ifaces false)
          err (first errors)]
      (is (nil? (:did-you-mean err)))
      (is (not (re-find #"Did you mean" (:message err)))))))

(deftest test-did-you-mean-no-suggestion-when-empty-config
  (testing "Empty :interfaces → no suggestion possible"
    (let [{:keys [errors]}
          (ann/annotate-pickle (make-pickle ["[:sms] x"]) {} false)]
      (is (nil? (-> errors first :did-you-mean))))))

(deftest test-annotate-unknown-interface-empty-config
  (testing "Empty interfaces config — any annotation is unknown"
    (let [{:keys [errors]}
          (ann/annotate-pickle (make-pickle ["[:sms] send"])
                               {} false)]
      (is (= 1 (count errors)))
      (is (= :annotation/unknown-interface (-> errors first :type)))
      (is (re-find #"no interfaces configured" (-> errors first :message))))))

(deftest test-annotate-macro-call-collision
  (testing "Annotation + macro suffix with macros enabled → error"
    (let [{:keys [errors]}
          (ann/annotate-pickle (make-pickle ["[:sms] login as alice +"])
                               web-sms-ifaces true)]
      (is (= 1 (count errors)))
      (let [err (first errors)]
        (is (= :annotation/on-macro-call-unsupported (:type err)))
        (is (= :sms (:declared-interface err)))
        (is (re-find #"macro" (:message err)))))))

(deftest test-annotate-macro-call-ok-when-macros-disabled
  (testing "Annotation + \" +\" text with macros disabled → no error (plain text)"
    (let [{:keys [pickle errors]}
          (ann/annotate-pickle (make-pickle ["[:sms] weird text +"])
                               web-sms-ifaces false)]
      (is (empty? errors))
      (is (= :sms (-> pickle :pickle/steps first :step/declared-interface))))))

;; -----------------------------------------------------------------------------
;; Synthetic steps
;; -----------------------------------------------------------------------------

(deftest test-synthetic-steps-passthrough
  (testing "Synthetic steps are passed through untouched even with weird text"
    (let [synthetic {:step/id (java.util.UUID/randomUUID)
                     :step/text "[:sms] synthetic wrapper"
                     :step/synthetic? true}
          {:keys [pickle errors]}
          (ann/annotate-pickle
           {:pickle/id (java.util.UUID/randomUUID)
            :pickle/name "test"
            :pickle/steps [synthetic]}
           web-sms-ifaces true)]
      (is (empty? errors))
      (is (nil? (-> pickle :pickle/steps first :step/declared-interface))))))

;; -----------------------------------------------------------------------------
;; annotate-pickles (suite-level)
;; -----------------------------------------------------------------------------

(deftest test-annotate-pickles-aggregates-errors
  (testing "Errors from multiple pickles are collected"
    (let [p1 (make-pickle ["[:whatsapp] a"])
          p2 (make-pickle ["[:telegram] b"])
          p3 (make-pickle ["clean step"])
          {:keys [pickles errors]} (ann/annotate-pickles [p1 p2 p3]
                                                         web-sms-ifaces false)]
      (is (= 3 (count pickles)))
      (is (= 2 (count errors)))
      (is (every? #(= :annotation/unknown-interface (:type %)) errors)))))

(deftest test-annotate-pickles-empty
  (testing "Empty pickle list returns empty results"
    (let [{:keys [pickles errors]} (ann/annotate-pickles [] web-sms-ifaces false)]
      (is (empty? pickles))
      (is (empty? errors)))))
