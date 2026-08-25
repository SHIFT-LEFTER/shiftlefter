(ns shiftlefter.runner.reporter-test
  "sl-21z: the Reporter contract's four invariants, as executable checks."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [shiftlefter.runner.core :as rc]
            [shiftlefter.events :as events]
            [shiftlefter.runner.reporter :as reporter]))

(defrecord ExampleRecord [a b])

(def ^:private step-paths ["test/fixtures/steps/"])

(defn- run-quiet
  "Run the pipeline with all user-facing output swallowed."
  [opts]
  (binding [*out* (java.io.StringWriter.)
            *err* (java.io.StringWriter.)]
    (rc/execute! (merge {:step-paths step-paths :no-color true} opts))))

;; -----------------------------------------------------------------------------
;; Invariant 3 — pure, EDN-native data
;; -----------------------------------------------------------------------------

(deftest edn-safe?-admits-edn-native-scalars
  (testing "R4: UUIDs and symbols are EDN-native and qualify"
    (is (reporter/edn-safe? (java.util.UUID/randomUUID)))
    (is (reporter/edn-safe? 'some.ns/foo))
    (is (reporter/edn-safe? :kw))
    (is (reporter/edn-safe? [1 "a" #{:b} {:c nil}])))

  (testing "the actual danger is still banned"
    (is (not (reporter/edn-safe? identity)))
    (is (not (reporter/edn-safe? (atom 1))))
    (is (not (reporter/edn-safe? (Object.))))
    (is (not (reporter/edn-safe? (events/make-memory-bus)))))

  (testing "defrecords do not qualify: pr-str emits a tag read-string can't read"
    (let [r (->ExampleRecord 1 2)]
      (is (map? r) "precondition: a record IS a map, which is the trap")
      (is (not (reporter/edn-safe? r)))
      (is (thrown? RuntimeException (edn/read-string (pr-str r)))))))

;; --- Generative round-trip property (sl-48n6) --------------------------------
;; edn-safe?'s docstring asserts a PROPERTY — accepted values round-trip
;; pr-str/read-string with default readers — that its cond does not check.
;; The defrecord gap (sl-21z) proved the two can drift. These properties pin
;; the whole class: anything edn-safe? admits must actually round-trip, and
;; non-data must be rejected (false, not thrown) wherever it hides.
;;
;; CORRECTION (sl-27uh): sl-48n6's original conclusion — "no gaps found in
;; edn-safe?" — was false: its generators only produced well-formed
;; keyword/symbol names, so the type-only check passed the property while
;; admitting non-round-tripping tokens like (keyword "a b"). The hostile
;; generators below close that blind spot ("property tests are only as
;; strong as their generators", ARCHITECTURE.md).

(def ^:private gen-edn-scalar
  ;; The scalar vocabulary invariant 3 admits. NaN is deliberately absent:
  ;; it round-trips textually (##NaN) but NaN ≠ NaN, an equality wrinkle of
  ;; IEEE 754 — not a serialization gap. ±Inf round-trips and stays in.
  (gen/one-of [(gen/return nil)
               gen/string
               gen/keyword
               gen/keyword-ns
               gen/symbol
               gen/symbol-ns
               gen/large-integer
               (gen/double* {:NaN? false})
               gen/boolean
               gen/uuid]))

(def ^:private gen-hostile-name
  ;; Names that break (or stress) EDN's token grammar: whitespace,
  ;; reader-significant characters, control chars, leading digits, and the
  ;; island-hostile shape from sl-27uh. Some combinations DO round-trip
  ;; (`:<script` is readable) — the properties below are conditional, so
  ;; that's exactly the point: admit iff round-trip, per token, definitionally.
  (gen/one-of [(gen/return "</script><script>alert(1)</script>")
               (gen/return "a b")
               (gen/return "x]y")
               (gen/return "")
               (gen/fmap (fn [[a c b]] (str a c b))
                         (gen/tuple gen/string-alphanumeric
                                    (gen/elements [" " "\t" "\n" "]" ")" "}"
                                                   "\"" ";" "\\" "<" "/" "@"
                                                   "^" "`" "~" (str (char 0))])
                                    gen/string-alphanumeric))
               (gen/fmap #(str % "a") (gen/choose 0 9))]))

(def ^:private gen-hostile-token
  ;; sl-27uh: keywords/symbols whose NAMES may not survive pr-str/read-string.
  ;; sl-48n6's original generators never produced these — the exact blind spot
  ;; behind its "no gaps found in edn-safe?" claim, which this bead corrects.
  ;; sl-7kct: namespaced variants added — readable-token? checks the NAMESPACE
  ;; part too ((keyword "a b" "c") prints as :a b/c), previously unfuzzed.
  (gen/one-of [(gen/fmap keyword gen-hostile-name)
               (gen/fmap symbol gen-hostile-name)
               (gen/fmap (fn [[ns n]] (keyword ns n))
                         (gen/tuple gen-hostile-name gen/string-alphanumeric))
               (gen/fmap (fn [[ns n]] (keyword ns n))
                         (gen/tuple gen/string-alphanumeric gen-hostile-name))
               (gen/fmap (fn [[ns n]] (keyword ns n))
                         (gen/tuple gen-hostile-name gen-hostile-name))
               (gen/fmap (fn [[ns n]] (symbol ns n))
                         (gen/tuple gen-hostile-name gen/string-alphanumeric))
               (gen/fmap (fn [[ns n]] (symbol ns n))
                         (gen/tuple gen/string-alphanumeric gen-hostile-name))
               (gen/fmap (fn [[ns n]] (symbol ns n))
                         (gen/tuple gen-hostile-name gen-hostile-name))]))

(def ^:private gen-nasty-leaf
  ;; The actual danger, per invariant 3 — plus the two shapes that LOOK like
  ;; data: records (satisfy map?, the sl-21z trap) and tagged literals (print
  ;; as readable EDN a remote coordinator has no reader for).
  (gen/one-of [(gen/fmap (fn [[a b]] (->ExampleRecord a b))
                         (gen/tuple gen/small-integer gen/small-integer))
               (gen/return (Object.))
               (gen/return identity)
               (gen/fmap atom gen/small-integer)
               (gen/return (tagged-literal 'my.ns/Rec {:a 1}))]))

(defn- gen-containers
  "Container layer for recursive-gen: maps (mixed keys), vectors, sets, lists."
  [inner]
  (gen/one-of [(gen/map inner inner {:max-elements 4})
               (gen/vector inner 0 4)
               (gen/set inner {:max-elements 4})
               (gen/fmap (partial apply list) (gen/vector inner 0 4))]))

(def ^:private gen-mixed-value
  ;; Deeply nested mixed collections where any leaf may be safe or nasty.
  (gen/recursive-gen gen-containers
                     (gen/frequency [[4 gen-edn-scalar]
                                     [1 gen-nasty-leaf]
                                     [1 gen-hostile-token]])))

(def ^:private gen-buried-nasty
  ;; A nasty leaf under 0-4 layers of otherwise-safe containers.
  (gen/bind gen-nasty-leaf
            (fn [nasty]
              (gen/fmap (fn [wrappers] (reduce (fn [acc wrap] (wrap acc)) nasty wrappers))
                        (gen/vector (gen/elements [(fn [x] [x 1 "pad"])
                                                   (fn [x] {:k x :safe true})
                                                   (fn [x] #{x})
                                                   (fn [x] (list x :pad))])
                                    0 4)))))

(defspec edn-safe?-accepted-values-actually-round-trip 200
  ;; The docstring's claim, verbatim: admit ⇒ round-trip. Also exercises
  ;; "reject, don't throw" — a throw from edn-safe? fails the property.
  ;; Serialization goes through pr-edn-str, the writer seam invariant 3
  ;; actually guarantees (sl-3ub4): bare pr-str under clojure.main's ambient
  ;; *print-namespace-maps*=true emits #:0a{...} literals EDN can't re-read,
  ;; which made both round-trip properties flake at ~7.5%/suite-run.
  (prop/for-all [x gen-mixed-value]
    (if (reporter/edn-safe? x)
      (= x (edn/read-string (reporter/pr-edn-str x)))
      true)))

(defspec edn-safe?-rejects-a-nasty-leaf-at-any-depth 200
  (prop/for-all [x gen-buried-nasty]
    (false? (reporter/edn-safe? x))))

(defspec scrub-output-is-always-edn-safe-and-round-trips 200
  ;; scrub is the seam that enforces invariant 3; whatever it emits must be
  ;; admissible and genuinely round-trip — through pr-edn-str (see the
  ;; sibling property above for why bare pr-str is wrong here).
  (prop/for-all [x gen-mixed-value]
    (let [scrubbed (reporter/scrub x)]
      (and (reporter/edn-safe? scrubbed)
           (= scrubbed (edn/read-string (reporter/pr-edn-str scrubbed)))))))

(deftest pinned-writer-survives-hostile-ambient-bindings
  ;; sl-3ub4 regression, replaying the overnight hunt's evidence: under
  ;; clojure.main's ambient *print-namespace-maps*=true, bare pr-str of a
  ;; map whose keys share the digit-leading namespace "0a" emits #:0a{...},
  ;; which edn/read-string rejects (NumberFormatException "Invalid number:
  ;; 0a") — even though every token round-trips and edn-safe? admits it.
  ;; Pre-fix this exact shape fell out of (tc/quick-check 2000 prop :seed 1)
  ;; as the shrunk counterexample; the wild strikes are the fail-laneC-*
  ;; logs in the sl-tkrx evidence. pr-edn-str must be immune to the ambient
  ;; bindings; the bare-pr-str breakage is asserted too so this test fails
  ;; loudly if a Clojure upgrade ever changes the underlying behavior.
  (let [shrunk [{nil {{{(keyword "0a" "A") nil} nil} nil}}]
        scrubbed (reporter/scrub shrunk)]
    (binding [*print-namespace-maps* true
              *print-length* 2
              *print-level* 2]
      (is (= scrubbed (edn/read-string (reporter/pr-edn-str scrubbed)))
          "pinned writer round-trips under hostile ambient bindings")
      (is (thrown? NumberFormatException
                   (edn/read-string (pr-str {(keyword "0a" "A") nil})))
          "bare pr-str under pnm=true still emits the unreadable literal"))))

(deftest scrub-coerces-to-edn-native-data
  (testing "identity fast path: already-safe data is returned untouched"
    (let [d {:reason :test :id (java.util.UUID/randomUUID) :ns 'foo.bar}]
      (is (identical? d (reporter/scrub d)))))

  (testing "records flatten to plain maps"
    (let [scrubbed (reporter/scrub {:loc (->ExampleRecord 1 2)})]
      (is (= {:loc {:a 1 :b 2}} scrubbed))
      (is (reporter/edn-safe? scrubbed))
      (is (= scrubbed (edn/read-string (pr-str scrubbed))))))

  (testing "arbitrary objects are rendered with pr-str"
    (is (string? (:drv (reporter/scrub {:drv (Object.)}))))
    (is (reporter/edn-safe? (reporter/scrub {:f identity})))))

(deftest edn-safe?-rejects-non-round-tripping-tokens
  ;; sl-27uh: the type check alone admitted any keyword/symbol; invariant 3
  ;; stands unweakened (Tower ruling 2026-07-10), so admission requires the
  ;; token's printed form to re-read to an equal value.
  (testing "the Warden evidence shapes are rejected"
    (is (not (reporter/edn-safe? (keyword "</script><script>alert(1)</script>"))))
    (is (not (reporter/edn-safe? (keyword "a b"))))
    (is (not (reporter/edn-safe? (symbol "a b"))))
    (is (not (reporter/edn-safe? (symbol "x]y"))))
    (is (not (reporter/edn-safe? (keyword ""))))
    (is (not (reporter/edn-safe? (symbol "-1")))
        "prints as the NUMBER -1: type lost on re-read"))

  (testing "a hostile NAMESPACE is rejected even when the name is clean (sl-7kct)"
    (is (not (reporter/edn-safe? (keyword "a b" "c"))))
    (is (not (reporter/edn-safe? (symbol "a b" "c")))))

  (testing "hostile tokens are caught at any depth, key or value"
    (is (not (reporter/edn-safe? {:k [(symbol "a b")]})))
    (is (not (reporter/edn-safe? {(keyword "a b") 1}))))

  (testing "scrub stringifies what edn-safe? now rejects (pr-str form)"
    (is (= ":a b" (reporter/scrub (keyword "a b"))))
    (is (= {:k "a b"} (reporter/scrub {:k (symbol "a b")})))
    (is (= {:x ":</script><script>alert(1)</script>"}
           (reporter/scrub {:x (keyword "</script><script>alert(1)</script>")}))
        "the muq9 coupling: a hostile keyword can now only reach the island
         as a STRING, where escape-island handles it"))

  (testing "well-formed tokens still qualify — fast path and slow path alike"
    (is (reporter/edn-safe? :step/invalid-return))
    (is (reporter/edn-safe? 'shiftlefter.runner.core))
    (is (reporter/edn-safe? '->))
    ;; suspicious-LOOKING but genuinely readable names take the definitional
    ;; slow path and are correctly admitted; the island emitter's integrity
    ;; guard (html.clj, sl-27uh) is the defense layer for the `</` corner.
    (is (reporter/edn-safe? (keyword "<script")))
    (is (reporter/edn-safe? (keyword "1a")))
    (is (reporter/edn-safe? (keyword "<script" "ok"))
        "suspicious-looking but readable NAMESPACE takes the slow path too")))

(deftest error-envelope-pr-strs-value-exactly-once
  (testing "R2: :value is an arbitrary runtime value, stringified here and only here"
    (let [env (reporter/error-envelope {:type :step/invalid-return
                                        :message "bad" :value {:a 1}})]
      (is (= "{:a 1}" (:value env)))
      (is (string? (:value env)))))

  (testing "falsy :value / :data are omitted, preserving the historical shape"
    (is (= {:type :t :message "m"}
           (reporter/error-envelope {:type :t :message "m" :value nil :data nil}))))

  (testing ":data is scrubbed, :exception-class carried"
    (let [env (reporter/error-envelope {:type :t :message "m"
                                        :exception-class "java.lang.Exception"
                                        :data {:loc (->ExampleRecord 1 2)}})]
      (is (= "java.lang.Exception" (:exception-class env)))
      (is (= {:loc {:a 1 :b 2}} (:data env)))
      (is (reporter/edn-safe? env)))))

(deftest scenario-envelopes-are-edn-native-and-round-trip
  (doseq [feature ["basic" "failing" "pending"]]
    (testing feature
      (let [result (run-quiet {:paths [(str "test/fixtures/features/" feature ".feature")]})
            raw (first (:scenarios (:result result)))
            env (reporter/scenario-envelope raw)]
        (is (not (reporter/edn-safe? raw))
            "precondition: the raw result carries a live :scenario-ctx")
        (is (reporter/edn-safe? env) "AC 4: envelopes handed to reporters are EDN-safe")
        (is (= env (edn/read-string (pr-str env)))
            "invariant 3: envelopes round-trip pr-str/read-string with default readers")

        (testing "allowlist projection drops the unsafe and the unread"
          (is (nil? (:scenario-ctx env)))
          (is (nil? (:capability-cleanup env)))
          (is (nil? (-> env :plan :plan/steps)))
          (is (some? (-> env :plan :plan/pickle)) "reporters read the pickle"))))))

(deftest envelope-carries-attachments-at-both-levels
  ;; sl-l9nj: refs survive the allowlist at step AND scenario level (the
  ;; step-level producer arrives with sl-3q9r; the contract is live now).
  (let [ref #:attachment{:kind :screenshot
                         :path "attachments/checkout/shot.png"
                         :media-type "image/png"
                         :bytes 42
                         :source [:hook "shot"]}
        raw {:status :passed
             :plan {:plan/pickle {:pickle/name "checkout"}}
             :steps [{:step {:step/text "s"} :binding {} :status :passed
                      :attachments [ref]}]
             :attachments [ref]
             :scenario-ctx {:live-driver (Object.)}}
        env (reporter/scenario-envelope raw)]
    (is (= [ref] (:attachments env)) "scenario-level refs survive")
    (is (= [ref] (-> env :steps first :attachments)) "step-level refs survive")
    (is (reporter/edn-safe? env) "refs are pure data — invariant 3 holds")
    (is (= env (edn/read-string (pr-str env))) "round-trips")
    (testing "zero-attachment envelopes are keyless (byte-identity guard)"
      (let [bare (reporter/scenario-envelope
                  (-> raw
                      (dissoc :attachments)
                      (update-in [:steps 0] dissoc :attachments)))]
        (is (not (contains? bare :attachments)))
        (is (not (contains? (-> bare :steps first) :attachments)))))))

(deftest execute!-keeps-the-raw-result-for-programmatic-callers
  (testing "envelopes are a projection for the report/observe planes only"
    (let [raw (first (:scenarios (:result (run-quiet {:paths ["test/fixtures/features/basic.feature"]}))))]
      (is (contains? raw :scenario-ctx))
      (is (contains? raw :capability-cleanup))
      (is (some? (-> raw :plan :plan/steps))))))

;; -----------------------------------------------------------------------------
;; Invariant 4 — exit-code independence, loud failure
;; -----------------------------------------------------------------------------

(defrecord ThrowingReporter [moment]
  reporter/Reporter
  (on-run-start [_ _] (when (= :run-start moment) (throw (ex-info "boom" {}))) nil)
  (on-scenario-complete [_ _] (when (= :scenario-complete moment) (throw (ex-info "boom" {}))) nil)
  (on-diagnostics [_ _] (when (= :diagnostics moment) (throw (ex-info "boom" {}))) nil)
  (on-run-end [_ _] (when (= :run-end moment) (throw (ex-info "boom" {}))) nil))

(deftest a-throwing-reporter-fails-the-run-loudly
  (testing "AC 5: the user asked for that artifact; a partial one must not pass silently"
    (doseq [moment [:run-start :scenario-complete :diagnostics :run-end]]
      (testing (str "throw from " moment)
        (with-redefs [rc/make-reporters (fn [& _] [(->ThrowingReporter moment)])]
          (let [result (run-quiet {:paths ["test/fixtures/features/basic.feature"]})]
            (is (= 3 (:exit-code result)) "a reporter throw surfaces as a loud runner error")
            (is (= :crashed (:status result)))))))))

(deftest exit-codes-are-independent-of-reporting
  (testing "AC 5: exit codes computed from the execution result, unchanged by reporters"
    (is (= 0 (:exit-code (run-quiet {:paths ["test/fixtures/features/basic.feature"]}))))
    (is (= 1 (:exit-code (run-quiet {:paths ["test/fixtures/features/failing.feature"]}))))
    (is (= 2 (:exit-code (run-quiet {:paths ["test/fixtures/features/undefined.feature"]}))))
    (testing "and are identical under --edn, where a different reporter runs"
      (is (= 0 (:exit-code (run-quiet {:paths ["test/fixtures/features/basic.feature"] :edn true}))))
      (is (= 1 (:exit-code (run-quiet {:paths ["test/fixtures/features/failing.feature"] :edn true})))))))

;; -----------------------------------------------------------------------------
;; The two planes
;; -----------------------------------------------------------------------------

(defrecord RecordingReporter [log]
  reporter/Reporter
  (on-run-start [_ ctx] (swap! log conj [:run-start ctx]) nil)
  (on-scenario-complete [_ sr] (swap! log conj [:scenario-complete sr]) nil)
  (on-diagnostics [_ d] (swap! log conj [:diagnostics d]) nil)
  (on-run-end [_ s] (swap! log conj [:run-end s]) nil))

(deftest reporters-see-each-moment-once-in-plan-order
  (let [log (atom [])]
    (with-redefs [rc/make-reporters (fn [& _] [(->RecordingReporter log)])]
      (run-quiet {:paths ["test/fixtures/features/basic.feature"
                          "test/fixtures/features/failing.feature"]}))
    (let [moments (mapv first @log)
          summary (second (last @log))]
      (testing "AC 3: one call per lifecycle moment"
        (is (= 1 (count (filter #{:run-start} moments))))
        (is (= 1 (count (filter #{:diagnostics} moments))))
        (is (= 1 (count (filter #{:run-end} moments))))
        (is (= 2 (count (filter #{:scenario-complete} moments)))))

      (testing "run-start first, run-end last"
        (is (= :run-start (first moments)))
        (is (= :run-end (last moments))))

      (testing "invariant 2: reporters see scenarios in PLAN order"
        (is (= ["Basic passing scenario" "Step throws exception"]
               (->> @log
                    (filter #(= :scenario-complete (first %)))
                    (mapv #(-> % second :plan :plan/pickle :pickle/name))))))

      (testing "run-summary carries what the contract promises"
        (is (every? #(contains? summary %) [:run-id :exit-code :counts :status]))
        (is (= 1 (:exit-code summary)))
        (is (= :failed (:status summary))))

      (testing "AC 4: every payload handed to a reporter is EDN-safe"
        (is (every? #(reporter/edn-safe? (second %)) @log))))))

(deftest bus-carries-scenario-finished-with-edn-safe-payloads
  ;; No sleep, no race: the runner's bus-close! DRAINS accepted events to
  ;; every subscriber before returning (sl-q9wp fit-pass), so by the time
  ;; run-quiet returns, @seen is complete and this test is deterministic.
  ;; (Its historic ~1-in-3 flake was the sl-4tsi drop-at-close race.)
  (let [seen (atom [])
        make-bus events/make-memory-bus]
    ;; Subscribing here is the test's doing — a real run has zero subscribers.
    (with-redefs [events/make-memory-bus
                  (fn [] (doto (make-bus) (events/subscribe! #(swap! seen conj %))))]
      (run-quiet {:paths ["test/fixtures/features/basic.feature"
                          "test/fixtures/features/failing.feature"]}))
    (testing "AC 3: :scenario/finished is published, one per scenario"
      (is (= 2 (count (filter #(= :scenario/finished (:type %)) @seen)))))
    (testing "the run-boundary events fire — including the FINAL event, which
              the pre-drain bus-close! systematically raced (sl-4tsi's
              drop-at-close half, fixed on this branch)"
      (is (= 1 (count (filter #(= :test-run/started (:type %)) @seen))))
      (is (= 1 (count (filter #(= :test-run/finished (:type %)) @seen)))))
    (testing "AC 4: bus payloads are EDN-safe too"
      (is (every? #(reporter/edn-safe? (:payload %)) @seen)))
    (testing "the observe plane carries the same scenario envelope shape"
      (let [payload (:payload (first (filter #(= :scenario/finished (:type %)) @seen)))]
        (is (some? (-> payload :scenario :plan :plan/pickle)))
        (is (nil? (-> payload :scenario :scenario-ctx)))))))

(deftest bus-has-no-reporter-subscribers
  (testing "AC 6: the bus is retained but write-only; reporters are NOT subscribers"
    (let [bus (events/make-memory-bus)]
      (is (zero? (count @(:subscriptions-atom bus)))))))

;; -----------------------------------------------------------------------------
;; Scenario-level :error projection (sl-esq)
;; -----------------------------------------------------------------------------

(deftest scenario-envelope-projects-scenario-level-error
  (testing "an :error scenario's error survives the envelope seam with hook attribution"
    (let [envelope (reporter/scenario-envelope
                    {:status :error
                     :error {:type :hook/before-failed
                             :message "boom"
                             :exception-class "java.lang.IllegalStateException"
                             :hook "reset-db"
                             :registration {:path "/proj/hooks.clj"}
                             :tag-source {:file "features/x.feature" :line 3}}
                     :plan {:plan/pickle {:pickle/id (java.util.UUID/randomUUID)}}
                     :steps []})]
      (is (= :error (:status envelope)))
      (is (= "reset-db" (-> envelope :error :hook)))
      (is (= "/proj/hooks.clj" (-> envelope :error :registration :path)))
      (is (= 3 (-> envelope :error :tag-source :line)))
      (is (reporter/edn-safe? envelope))
      (is (= envelope (edn/read-string (pr-str envelope))))))
  (testing "no :error on the raw result — no :error key on the envelope (byte-identity guard)"
    (let [envelope (reporter/scenario-envelope
                    {:status :passed
                     :plan {:plan/pickle {:pickle/id (java.util.UUID/randomUUID)}}
                     :steps []})]
      (is (not (contains? envelope :error))))))

;; -----------------------------------------------------------------------------
;; Derived-scheduling surfacing (sl-esq AC8, round-5 unification)
;; -----------------------------------------------------------------------------

(deftest scenario-envelope-records-effective-scheduling
  (testing "non-default scheduling with reason survives the seam — costume,
            shared-impl (the retrofit) and [:hook name] alike"
    (doseq [reason [:costume :shared-impl [:hook "reset-db"]]]
      (let [envelope (reporter/scenario-envelope
                      {:status :passed
                       :plan {:plan/pickle {:pickle/id (java.util.UUID/randomUUID)}
                              :plan/schedule {:serial? true :reason reason}}
                       :steps []})]
        (is (= {:serial? true :reason reason} (:schedule envelope)))
        (is (reporter/edn-safe? envelope)))))
  (testing "default-scheduled scenarios: no :schedule key (byte-identity guard)"
    (let [envelope (reporter/scenario-envelope
                    {:status :passed
                     :plan {:plan/pickle {:pickle/id (java.util.UUID/randomUUID)}}
                     :steps []})]
      (is (not (contains? envelope :schedule))))))
