(ns shiftlefter.stepdefs.sms-test
  "Unit tests for built-in SMS stepdefs.

   Exercises stepdef registration, SVO metadata correctness, end-to-end
   stepdef invocation against the in-memory mock adapter, the smart-default
   :since-ts ladder, and capture storage. Mirrors browser_test.clj's
   pattern: load the stepdefs into a fresh registry, build a ctx with a
   subject-keyed mock impl, invoke (:fn stepdef) directly, assert the
   returned ctx + adapter side-effects."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.sms.mock :as mock]
            [shiftlefter.sms.protocol :as sms-proto]
            [shiftlefter.stepdefs.sms :as sms-step]
            [shiftlefter.stepengine.registry :as registry])
  (:import (java.time Instant)))

;; -----------------------------------------------------------------------------
;; Fixtures
;; -----------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (require 'shiftlefter.stepdefs.sms :reload)
    (f)))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- find-sms-stepdef
  "Find the SMS stepdef matching `verb` and (optional) `frame`."
  ([verb] (find-sms-stepdef verb nil))
  ([verb frame]
   (first (filter (fn [s]
                    (let [m (:metadata s)]
                      (and (= :sms (:interface m))
                           (= verb (-> m :svo :verb))
                           (or (nil? frame)
                               (= frame (-> m :svo :frame))))))
                  (registry/all-stepdefs)))))

(defn- ctx-with-sms
  "Build a ctx with a subject-keyed mock SMS impl + scenario-start-ts."
  ([impl subject] (ctx-with-sms impl subject (Instant/now)))
  ([impl subject ts]
   {(keyword "cap" (str "sms." (name subject)))
    {:impl impl :mode :ephemeral}
    :sms/scenario-start-ts ts}))

(defn- invoke
  "Invoke a stepdef directly with ctx + captures."
  [stepdef ctx & captures]
  (apply (:fn stepdef) ctx captures))

;; -----------------------------------------------------------------------------
;; Registration & metadata
;; -----------------------------------------------------------------------------

(deftest test-stepdefs-registered
  (testing "All four SMS verbs (six stepdefs across frames) are registered"
    (is (some? (find-sms-stepdef :send :to)))
    (is (some? (find-sms-stepdef :send-media :to)))
    (is (some? (find-sms-stepdef :receive :default)))
    (is (some? (find-sms-stepdef :receive :since-iso)))
    (is (some? (find-sms-stepdef :receive :within-last)))
    (is (some? (find-sms-stepdef :see :count)))))

(deftest test-stepdef-metadata-shape
  (testing "Every SMS stepdef declares :requires-protocols [ISMS]"
    (doseq [s (filter #(= :sms (-> % :metadata :interface))
                      (registry/all-stepdefs))]
      (is (= [:shiftlefter.sms.protocol/ISMS]
             (-> s :metadata :requires-protocols))
          (str "Stepdef " (:pattern-src s)
               " should require [ISMS] only — never ISMSInbound (test seam)"))))

  (testing ":receive frames declare correct args alignment"
    (is (= {:to-phone :$2 :match :$3}
           (-> (find-sms-stepdef :receive :default) :metadata :svo :args)))
    (is (= {:to-phone :$2 :since-iso :$3 :match :$4}
           (-> (find-sms-stepdef :receive :since-iso) :metadata :svo :args)))
    (is (= {:to-phone :$2 :duration :$3 :match :$4}
           (-> (find-sms-stepdef :receive :within-last) :metadata :svo :args)))))

;; -----------------------------------------------------------------------------
;; Pattern matching
;; -----------------------------------------------------------------------------

(deftest test-pattern-match-send
  (testing ":send pattern matches expected step text shapes"
    (let [pat (:pattern (find-sms-stepdef :send :to))]
      (is (re-matches pat ":alice sends an SMS to '+15551234567' saying 'hi'"))
      (is (re-matches pat ":user/alice sends an SMS to '+15551234567' saying ''")
          "qualified subject + empty body still match")
      (is (nil? (re-matches pat ":alice sends an SMS to +15551234567 saying 'hi'"))
          "unquoted phone does not match"))))

(deftest test-pattern-match-receive-frames
  (testing ":receive :default matches plain-baseline form"
    (let [pat (:pattern (find-sms-stepdef :receive :default))]
      (is (re-matches pat
                      ":alice receives an SMS to '+15551234567' matching /code: (\\d+)/"))))

  (testing ":receive :since-iso matches with ISO timestamp"
    (let [pat (:pattern (find-sms-stepdef :receive :since-iso))]
      (is (re-matches pat
                      (str ":alice receives an SMS to '+15551234567' "
                           "since '2026-05-06T10:00:00Z' matching /x/")))))

  (testing ":receive :within-last matches duration units"
    (let [pat (:pattern (find-sms-stepdef :receive :within-last))]
      (doseq [unit ["second" "seconds" "minute" "minutes" "hour" "hours"]]
        (is (re-matches pat
                        (str ":alice receives an SMS to '+1' within the last 5 "
                             unit " matching /x/"))
            (str unit " should match"))))))

(deftest test-pattern-match-see-count
  (let [pat (:pattern (find-sms-stepdef :see :count))]
    (is (re-matches pat ":alice should see 3 messages to '+15551234567'"))
    (is (re-matches pat ":alice should see 0 messages to '+15551234567'")
        "zero messages is a valid assertion")))

;; -----------------------------------------------------------------------------
;; :send / :send-media — adapter side-effects
;; -----------------------------------------------------------------------------

(deftest test-send-invokes-adapter
  (testing ":send calls ISMS/send! with :to and :body"
    (let [m   (mock/make-mock-sms)
          ctx (ctx-with-sms m :alice)
          step (find-sms-stepdef :send :to)
          result (invoke step ctx "alice" "+15551234567" "hello")]
      (is (= ctx result) "ctx unchanged on success")
      ;; Inspect mock log directly
      (let [{:keys [ok]} (sms-proto/query-messages
                          m {:since-ts Instant/EPOCH :direction :outbound})]
        (is (= 1 (count ok)))
        (is (= "+15551234567" (-> ok first :to)))
        (is (= "hello" (-> ok first :body)))))))

(deftest test-send-media-invokes-adapter-with-media
  (testing ":send-media stamps a media entry on the outbound message"
    (let [m   (mock/make-mock-sms)
          ctx (ctx-with-sms m :alice)
          step (find-sms-stepdef :send-media :to)
          _   (invoke step ctx "alice" "+15551234567" "look" "https://example/x.jpg")
          {:keys [ok]} (sms-proto/query-messages
                        m {:since-ts Instant/EPOCH :direction :outbound})
          msg (first ok)]
      (is (= 1 (count ok)))
      (is (= [{:url "https://example/x.jpg"
               :content-type "application/octet-stream"}]
             (:media msg))))))

;; -----------------------------------------------------------------------------
;; :receive — happy path, captures, baseline ladder
;; -----------------------------------------------------------------------------

(deftest test-receive-default-finds-and-captures
  (testing ":receive :default binds named groups into :sl/bindings (sl-yh7)
            + advances last-receive-ts"
    (let [m   (mock/make-mock-sms)
          ts  (Instant/now)
          ctx (ctx-with-sms m :alice ts)]
      ;; Pre-stash inbound (after scenario-start)
      (Thread/sleep 5)
      (sms-proto/simulate-inbound! m {:from "+15550000000"
                                      :to   "+15551234567"
                                      :body "Your code: 987654"})
      (let [step   (find-sms-stepdef :receive :default)
            result (invoke step ctx "alice" "+15551234567" "code: (?<code>\\d+)")]
        (is (= "987654" (get-in result [:sl/bindings :code])))
        (is (nil? (:sms/captures result))
            "legacy :sms/captures public storage is gone")
        (is (= "Your code: 987654" (-> result :sms/last-message :body)))
        (is (some? (:sms/last-receive-ts result)))
        (is (= (-> result :sms/last-message :date-sent)
               (:sms/last-receive-ts result))
            "last-receive-ts advances to matched message's :date-sent")))))

(deftest test-receive-multiple-named-groups-all-bind
  (let [m   (mock/make-mock-sms)
        ctx (ctx-with-sms m :alice)]
    (Thread/sleep 5)
    (sms-proto/simulate-inbound! m {:from "+1" :to "+15551234567"
                                    :body "user alice pin 4242"})
    (let [step   (find-sms-stepdef :receive :default)
          result (invoke step ctx "alice" "+15551234567"
                         "user (?<user>[a-z]+) pin (?<pin>\\d+)")]
      (is (= {:user "alice" :pin "4242"} (:sl/bindings result))))))

(deftest test-receive-unnamed-groups-bind-nothing
  (let [m   (mock/make-mock-sms)
        ctx (ctx-with-sms m :alice)]
    (Thread/sleep 5)
    (sms-proto/simulate-inbound! m {:from "+1" :to "+15551234567" :body "code: 42"})
    (let [step   (find-sms-stepdef :receive :default)
          result (invoke step ctx "alice" "+15551234567" "code: (\\d+)")]
      (is (empty? (:sl/bindings result))
          "positional groups never bind — dry-run notices this")
      (is (= "code: 42" (-> result :sms/last-message :body))
          "the match itself still succeeds (capture is also an assert)"))))

(deftest test-receive-matcher-interpolates-embedded-tokens
  (testing "embedded {binding} tokens in the match pattern interpolate as
            regex-quoted literals (sl-yh7)"
    (let [m   (mock/make-mock-sms)
          ctx (assoc (ctx-with-sms m :alice)
                     :sl/bindings {:orderNumber "A-12.3"})]
      (Thread/sleep 5)
      (sms-proto/simulate-inbound! m {:from "+1" :to "+15551234567"
                                      :body "order A-12.3 shipped, track 777"})
      ;; A-12x3 must NOT match — the dot is quoted, not a wildcard
      (sms-proto/simulate-inbound! m {:from "+1" :to "+15551234567"
                                      :body "order A-12x3 shipped, track 888"})
      (let [step   (find-sms-stepdef :receive :default)
            result (invoke step ctx "alice" "+15551234567"
                           "order {orderNumber} shipped, track (?<track>\\d+)")]
        (is (= "777" (get-in result [:sl/bindings :track])))))))

(deftest test-receive-invalid-pattern-is-structured
  (testing "a bad match regex (incl. duplicate group names) throws
            :bindings/invalid-pattern"
    (let [m   (mock/make-mock-sms)
          ctx (ctx-with-sms m :alice)
          step (find-sms-stepdef :receive :default)
          thrown (try (invoke step ctx "alice" "+1" "(?<a>x)|(?<a>y)") nil
                      (catch Exception e (ex-data e)))]
      (is (= :bindings/invalid-pattern (:type thrown))))))

(deftest test-receive-default-uses-last-receive-ts-baseline
  (testing "Sequential :receive uses :sms/last-receive-ts, not scenario-start"
    ;; If smart-default ladder works, the second :receive's :since-ts
    ;; advances past the first message and finds only the second.
    (let [m   (mock/make-mock-sms)
          ts  (Instant/now)
          ctx (ctx-with-sms m :alice ts)
          step (find-sms-stepdef :receive :default)]
      (Thread/sleep 5)
      (sms-proto/simulate-inbound! m {:from "+1" :to "+15551234567" :body "code: 111"})
      (Thread/sleep 5)
      (sms-proto/simulate-inbound! m {:from "+1" :to "+15551234567" :body "code: 222"})
      (let [r1 (invoke step ctx "alice" "+15551234567" "code: (?<code>\\d+)")
            r2 (invoke step r1  "alice" "+15551234567" "code: (?<code>\\d+)")]
        (is (= "111" (get-in r1 [:sl/bindings :code])))
        (is (= "222" (get-in r2 [:sl/bindings :code]))
            "Second :receive advances past the first message via
             last-receive-ts, and rebinding {code} is last-write-wins")))))

(deftest test-receive-times-out-when-no-match
  (testing ":receive throws :sms/receive-timeout when no matching message arrives"
    (let [m   (mock/make-mock-sms)
          ctx (ctx-with-sms m :alice)
          step (find-sms-stepdef :receive :default)]
      (binding [sms-step/*receive-timeout-ms* 200
                sms-step/*poll-interval-ms*    50]
        (let [thrown (try (invoke step ctx "alice" "+15558888888" "anything")
                          nil
                          (catch Exception e (ex-data e)))]
          (is (= :sms/receive-timeout (:type thrown)))
          (is (= 200 (:timeout-ms thrown))))))))

;; -----------------------------------------------------------------------------
;; :receive — explicit-baseline frames
;; -----------------------------------------------------------------------------

(deftest test-receive-since-iso-uses-iso-baseline
  (testing ":receive :since-iso parses ISO timestamp and uses it as :since-ts"
    (let [m   (mock/make-mock-sms)
          ;; Inbound at known time
          msg (sms-proto/simulate-inbound!
               m {:from "+1" :to "+15551234567" :body "after"
                  :date-sent (Instant/parse "2026-05-06T10:00:00Z")})
          ctx (ctx-with-sms m :alice (Instant/parse "2026-05-06T09:00:00Z"))
          step (find-sms-stepdef :receive :since-iso)
          result (invoke step ctx "alice" "+15551234567"
                         "2026-05-06T09:30:00Z" "after")]
      (is (= "after" (-> result :sms/last-message :body)))
      (is (some? msg)))))

(deftest test-receive-since-iso-rejects-malformed
  (testing "Bad ISO timestamp produces :sms/invalid-since-iso"
    (let [m   (mock/make-mock-sms)
          ctx (ctx-with-sms m :alice)
          step (find-sms-stepdef :receive :since-iso)
          thrown (try (invoke step ctx "alice" "+1" "not-an-iso" "x") nil
                      (catch Exception e (ex-data e)))]
      (is (= :sms/invalid-since-iso (:type thrown))))))

(deftest test-receive-within-last-uses-relative-baseline
  (testing ":receive :within-last subtracts the duration from now"
    (let [m   (mock/make-mock-sms)
          ;; Stash a message 30 seconds ago and one 2 minutes ago
          recent (sms-proto/simulate-inbound!
                  m {:from "+1" :to "+15551234567" :body "recent: 999"
                     :date-sent (.minusSeconds (Instant/now) 30)})
          old    (sms-proto/simulate-inbound!
                  m {:from "+1" :to "+15551234567" :body "stale: 111"
                     :date-sent (.minusSeconds (Instant/now) 120)})
          ctx (ctx-with-sms m :alice (.minusSeconds (Instant/now) 600))
          step (find-sms-stepdef :receive :within-last)
          ;; "within the last 1 minute" → only `recent` is in window
          result (invoke step ctx "alice" "+15551234567" "1 minute" "(?<n>\\d+)")]
      (is (some? recent))
      (is (some? old))
      (is (= "999" (get-in result [:sl/bindings :n]))
          "within-last 1 minute should match the 30-seconds-ago message"))))

(deftest test-receive-within-last-rejects-bad-duration
  (testing "Bad duration unit produces :sms/invalid-duration"
    ;; Pattern only matches second(s)/minute(s)/hour(s); other text won't
    ;; reach parse-duration via the framework. Direct call surfaces the
    ;; structured error for code paths that assemble step text manually.
    (let [thrown (try (#'sms-step/parse-duration "5 days") nil
                      (catch Exception e (ex-data e)))]
      (is (= :sms/invalid-duration (:type thrown))))))

;; -----------------------------------------------------------------------------
;; :see :count
;; -----------------------------------------------------------------------------

(deftest test-see-count-exact-match
  (testing ":see :count passes when expected == actual inbound count"
    (let [m   (mock/make-mock-sms)
          ts  (Instant/now)
          ctx (ctx-with-sms m :alice ts)]
      (Thread/sleep 5)
      (sms-proto/simulate-inbound! m {:from "+1" :to "+15551234567" :body "a"})
      (sms-proto/simulate-inbound! m {:from "+2" :to "+15551234567" :body "b"})
      ;; Other-recipient inbound — must NOT be counted
      (sms-proto/simulate-inbound! m {:from "+3" :to "+15559999999" :body "c"})
      ;; And an outbound from alice — must NOT be counted (direction filter)
      (sms-proto/send! m {:to "+15551234567" :body "out"})
      (let [step   (find-sms-stepdef :see :count)
            result (invoke step ctx "alice" "2" "+15551234567")]
        (is (= ctx result) "passing assertion returns ctx unchanged")))))

(deftest test-see-count-mismatch-throws
  (testing ":see :count throws :sms/count-mismatch on count mismatch"
    (let [m   (mock/make-mock-sms)
          ts  (Instant/now)
          ctx (ctx-with-sms m :alice ts)]
      (Thread/sleep 5)
      (sms-proto/simulate-inbound! m {:from "+1" :to "+15551234567" :body "x"})
      (let [step (find-sms-stepdef :see :count)
            thrown (try (invoke step ctx "alice" "5" "+15551234567") nil
                        (catch Exception e (ex-data e)))]
        (is (= :sms/count-mismatch (:type thrown)))
        (is (= 5 (:expected thrown)))
        (is (= 1 (:actual thrown)))))))

(deftest test-see-count-uses-scenario-start-not-last-receive
  (testing ":see :count baselines from :sms/scenario-start-ts only"
    ;; Even after a :receive advances :sms/last-receive-ts, the :see
    ;; assertion looks back to scenario-start so the cardinality is
    ;; stable across receive interleavings.
    (let [m   (mock/make-mock-sms)
          ts  (Instant/now)
          ctx (ctx-with-sms m :alice ts)]
      (Thread/sleep 5)
      (sms-proto/simulate-inbound! m {:from "+1" :to "+15551234567" :body "code: 111"})
      (sms-proto/simulate-inbound! m {:from "+1" :to "+15551234567" :body "code: 222"})
      (let [recv-step (find-sms-stepdef :receive :default)
            see-step  (find-sms-stepdef :see :count)
            ;; First receive consumes one message, advances last-receive-ts
            after-recv (invoke recv-step ctx "alice" "+15551234567" "code: (\\d+)")
            ;; :see :count should still see BOTH (uses scenario-start, not last-receive)
            result (invoke see-step after-recv "alice" "2" "+15551234567")]
        (is (= after-recv result)
            ":see should pass with count=2 even after :receive advanced last-receive-ts")))))
