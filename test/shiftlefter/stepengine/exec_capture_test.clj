(ns shiftlefter.stepengine.exec-capture-test
  "The declarative capture engine (sl-3q9r): pure helpers, target
   enumeration, invocation/ref construction (part 1 — the engine in
   isolation), then the wired execution semantics through execute-suite
   (part 2, added with the step_loop/cleanup wiring commits).

   Mock adapters throughout; no browser anywhere."
  (:require [babashka.fs :as fs]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.attachments :as att]
            [shiftlefter.stepengine.exec :as exec]
            [shiftlefter.stepengine.exec.capture :as capture]))

;; -----------------------------------------------------------------------------
;; Mock capture world
;; -----------------------------------------------------------------------------

(defn- shot-fn
  "An instant-kind capture fn stamping the handle's :id."
  [{:keys [handle]}]
  {:data (.getBytes (str "png-" (name (:id handle))) "UTF-8")
   :media-type "image/png"
   :ext "png"})

(defn- console-fn
  "A drain-kind capture fn over the handle's :log atom — read-and-clear,
   like WebDriver."
  [{:keys [handle]}]
  (let [entries @(:log handle)]
    (reset! (:log handle) [])
    (when (seq entries)
      {:data (str/join "\n" entries)
       :media-type "text/plain"
       :ext "log"})))

(defn- mock-registry []
  {:mock {:capture {:screenshot {:fn shot-fn}
                    :console {:fn console-fn :scenario-scoped? true}
                    :boom {:fn (fn [_] (throw (ex-info "capture exploded" {})))}}}})

(defn- ctx-for
  "A scenario-ctx with subject-keyed capabilities; each impl carries its
   own :log atom for drain tests."
  [& ids]
  (into {} (map (fn [id]
                  [(keyword "cap" (str "web." (name id)))
                   {:impl {:id id :log (atom [])} :mode :ephemeral}]))
        ids))

(defn- log-of [ctx id]
  (get-in ctx [(keyword "cap" (str "web." (name id))) :impl :log]))

(defn- opts-for [dir reg policy]
  {:policy policy
   :artifacts-dir dir
   :rel-prefix "attachments/scn"
   :interfaces {:web {:adapter :mock}}
   :registry reg})

(defn- with-adir*
  "Call `f` with a temp artifacts dir, cleaned up after."
  [f]
  (let [root (str (fs/create-temp-dir))
        dir (str root "/attachments/scn")]
    (fs/create-dirs dir)
    (try (f dir) (finally (fs/delete-tree root)))))

(defmacro ^:private with-adir
  "`(with-adir [dir] body...)` — sugar over with-adir*."
  [[sym] & body]
  `(with-adir* (fn [~sym] ~@body)))

;; -----------------------------------------------------------------------------
;; Pure helpers
;; -----------------------------------------------------------------------------

(deftest capture-active-test
  (is (not (capture/capture-active? nil)))
  (is (not (capture/capture-active? {})))
  (is (not (capture/capture-active? {:screenshot :never})))
  (is (capture/capture-active? {:screenshot :never :console :on-failure})))

(deftest unsupported-kinds-test
  (let [reg (mock-registry)]
    (is (= [] (capture/unsupported-kinds {:screenshot :on-failure} reg)))
    (is (= [:screenshoot] (capture/unsupported-kinds {:screenshoot :on-failure} reg))
        "the typo'd kind is caught")
    (is (= [] (capture/unsupported-kinds {:screenshoot :never} reg))
        ":never kinds are not checked — only active ones")
    (is (= [:message-log :page-src]
           (capture/unsupported-kinds {:page-src :every-step :message-log :on-failure} reg))
        "sorted")))

(deftest capture-error-shape-test
  (let [err (capture/capture-error [{:kind :screenshot :cap-key :web.alice
                                     :interface :web :subject :alice
                                     :message "boom"}
                                    {:kind :console :cap-key :web.bob}])]
    (is (= :capture/failed (:type err)))
    (is (str/includes? (:message err) "screenshot on :web.alice")
        "the FIRST failure carries attribution")
    (is (= {:kind :screenshot :capability :web.alice :interface :web :subject :alice}
           (:data err))
        "dual attribution folded into :data — keywords only, scrub-safe")))

;; -----------------------------------------------------------------------------
;; capture-step! — the per-step site
;; -----------------------------------------------------------------------------

(deftest every-step-follows-the-actor
  (with-adir [dir]
    (let [ctx (ctx-for :alice :bob)
          copts (opts-for dir (mock-registry) {:screenshot :every-step})
          {:keys [refs failures]}
          (capture/capture-step! ctx copts
                                 {:step-index 1
                                  :svo {:interface :web :subject :alice}
                                  :failed? false})]
      (is (empty? failures))
      (is (= ["attachments/scn/step-1-screenshot-web-alice.png"]
             (mapv :attachment/path refs))
          "the film strip follows the actor — Bob is NOT captured")
      (is (= :alice (:attachment/subject (first refs))))
      (is (= :web (:attachment/interface (first refs))))
      (is (= :capture-policy (:attachment/source (first refs))))
      (is (number? (:attachment/duration-ms (first refs))))
      (is (fs/exists? (str dir "/step-1-screenshot-web-alice.png")))
      (is (every? #(s/valid? ::att/ref %) refs)
          "engine-built refs satisfy the stage-A contract"))))

(deftest no-svo-step-captures-all-live
  (with-adir [dir]
    (let [ctx (ctx-for :alice :bob)
          copts (opts-for dir (mock-registry) {:screenshot :every-step})
          {:keys [refs]} (capture/capture-step! ctx copts
                                                {:step-index 3 :svo nil :failed? false})]
      (is (= ["attachments/scn/step-3-screenshot-web-alice.png"
              "attachments/scn/step-3-screenshot-web-bob.png"]
             (sort (mapv :attachment/path refs)))
          "vanilla mode: no actor -> every live capability"))))

(deftest on-failure-instant-captures-all-live
  (with-adir [dir]
    (let [ctx (ctx-for :alice :bob)
          copts (opts-for dir (mock-registry) {:screenshot :on-failure})
          green (capture/capture-step! ctx copts
                                       {:step-index 1
                                        :svo {:interface :web :subject :alice}
                                        :failed? false})
          red (capture/capture-step! ctx copts
                                     {:step-index 2
                                      :svo {:interface :web :subject :alice}
                                      :failed? true})]
      (is (empty? (:refs green)) ":on-failure fires nothing on a passing step")
      (is (= ["attachments/scn/failure-screenshot-web-alice.png"
              "attachments/scn/failure-screenshot-web-bob.png"]
             (sort (mapv :attachment/path (:refs red))))
          "Bob's screen may explain Alice's failure — ALL live captured"))))

(deftest on-failure-scenario-scoped-kinds-wait-for-the-drain
  (with-adir [dir]
    (let [ctx (ctx-for :alice)
          _ (reset! (log-of ctx :alice) ["entry"])
          copts (opts-for dir (mock-registry) {:console :on-failure})
          {:keys [refs]} (capture/capture-step! ctx copts
                                                {:step-index 1
                                                 :svo {:interface :web :subject :alice}
                                                 :failed? true})]
      (is (empty? refs)
          "the destructive drain must NOT fire at the instant — it fires
           once post-steps (drain-at-scenario-end!), preserving the whole
           log for the one drain")
      (is (= ["entry"] @(log-of ctx :alice)) "the log is untouched"))))

(deftest per-step-drain-holds-only-entries-since-last
  (with-adir [dir]
    (let [ctx (ctx-for :alice)
          copts (opts-for dir (mock-registry) {:console :every-step})
          svo {:interface :web :subject :alice}
          _ (reset! (log-of ctx :alice) ["s1-a" "s1-b"])
          one (capture/capture-step! ctx copts {:step-index 1 :svo svo :failed? false})
          _ (reset! (log-of ctx :alice) ["s2-a"])
          two (capture/capture-step! ctx copts {:step-index 2 :svo svo :failed? false})
          three (capture/capture-step! ctx copts {:step-index 3 :svo svo :failed? false})]
      (is (= "s1-a\ns1-b" (slurp (str dir "/step-1-console-web-alice.log")))
          "step-grain provenance: each drain holds exactly its step's entries")
      (is (= "s2-a" (slurp (str dir "/step-2-console-web-alice.log"))))
      (is (= "s1-a\ns1-b" (:attachment/excerpt (first (:refs one))))
          "text/plain data derives its excerpt (D3)")
      (is (some? (:refs two)))
      (is (empty? (:refs three)) "an empty drain emits no ref and no file")
      (is (not (fs/exists? (str dir "/step-3-console-web-alice.log")))))))

(deftest capture-throw-is-d4-material
  (with-adir [dir]
    (let [ctx (ctx-for :alice)
          copts (opts-for dir (mock-registry) {:boom :every-step})
          {:keys [refs failures]}
          (capture/capture-step! ctx copts
                                 {:step-index 1
                                  :svo {:interface :web :subject :alice}
                                  :failed? false})]
      (is (= 1 (count refs)))
      (is (str/includes? (:attachment/error (first refs)) "capture exploded")
          "the ref records the miss")
      (is (= "attachments/scn/step-1-boom-web-alice.err"
             (:attachment/path (first refs))))
      (is (= 1 (count failures)))
      (is (= :web.alice (:cap-key (first failures))))
      (is (= ["step-1-boom-web-alice.err"]
             (mapv (comp str fs/file-name) (fs/list-dir dir)))
          "a throw writes the .err evidence file and nothing else (sl-yynw F7)")
      (is (str/includes? (slurp (str dir "/step-1-boom-web-alice.err"))
                         "capture exploded")
          "the ref's path names a real file holding the thrown message"))))

(deftest unsupported-kind-on-capability-is-silent-noop
  (with-adir [dir]
    (let [ctx (ctx-for :alice)
          copts (opts-for dir (mock-registry) {:message-log :every-step})
          {:keys [refs failures]}
          (capture/capture-step! ctx copts {:step-index 1 :svo nil :failed? false})]
      (is (empty? refs))
      (is (empty? failures)))))

;; -----------------------------------------------------------------------------
;; drain-at-scenario-end! — the post-steps site
;; -----------------------------------------------------------------------------

(deftest on-failure-drain-fires-once-when-red
  (with-adir [dir]
    (let [ctx (ctx-for :alice :bob)
          _ (reset! (log-of ctx :alice) ["a-log"])
          _ (reset! (log-of ctx :bob) ["b-log"])
          copts (opts-for dir (mock-registry) {:console :on-failure})
          green (capture/drain-at-scenario-end! ctx copts false)]
      (is (empty? (:refs green)) "green scenario -> no drain, logs kept")
      (is (= ["a-log"] @(log-of ctx :alice)))
      (let [red (capture/drain-at-scenario-end! ctx copts true)]
        (is (= ["attachments/scn/console-web-alice.log"
                "attachments/scn/console-web-bob.log"]
               (sort (mapv :attachment/path (:refs red))))
            "red -> one whole-scenario drain per live capability")))))

(deftest tail-sweep-emits-only-nonempty
  (with-adir [dir]
    (let [ctx (ctx-for :alice :bob)
          copts (opts-for dir (mock-registry) {:console :every-step})
          svo {:interface :web :subject :alice}
          _ (reset! (log-of ctx :alice) ["step stuff"])
          _ (capture/capture-step! ctx copts {:step-index 1 :svo svo :failed? false})
          _ (reset! (log-of ctx :bob) ["bob accumulated"])
          {:keys [refs]} (capture/drain-at-scenario-end! ctx copts false)]
      (is (= ["attachments/scn/console-tail-web-bob.log"]
             (mapv :attachment/path refs))
          "the tail catches the non-actor's accumulation; the actor's
           already-drained (empty) console emits nothing")
      (is (= "bob accumulated" (slurp (str dir "/console-tail-web-bob.log")))))))

(deftest drain-instant-kinds-never-fire-at-scenario-end
  (with-adir [dir]
    (let [ctx (ctx-for :alice)
          copts (opts-for dir (mock-registry) {:screenshot :on-failure})
          {:keys [refs]} (capture/drain-at-scenario-end! ctx copts true)]
      (is (empty? refs) "instant kinds fired at the failing instant, not here"))))

(deftest shared-impl-drains-once
  (with-adir [dir]
    (let [shared {:id :shared :log (atom ["one log"])}
          ctx {:cap/sms.alice {:impl shared :mode :ephemeral}
               :cap/sms.bob {:impl shared :mode :ephemeral}}
          copts {:policy {:console :on-failure}
                 :artifacts-dir dir :rel-prefix "attachments/scn"
                 :interfaces {:sms {:adapter :mock}}
                 :registry (mock-registry)}
          {:keys [refs]} (capture/drain-at-scenario-end! ctx copts true)]
      (is (= 1 (count refs))
          "IdentityHashMap dedup: a shared impl is drained exactly once
           (a second drain would read an already-emptied log)")
      (is (= "one log" (slurp (str dir "/" (fs/file-name (:attachment/path (first refs))))))))))

;; -----------------------------------------------------------------------------
;; Part 2 — WIRED semantics through execute-suite (the step_loop/cleanup
;; firing sites, sl-3q9r C4/C5)
;; -----------------------------------------------------------------------------

(defn- make-binding-w [f]
  {:fn f :arity 1 :captures []
   :stepdef/id "test-step" :pattern-src "test pattern"})

(defn- svo-step
  "A bound SVO step acting as `subject` on :web."
  ([subject] (svo-step subject (fn [ctx] ctx)))
  ([subject f]
   {:status :matched
    :step {:step/id (java.util.UUID/randomUUID)
           :step/text (str "step as " (name subject))
           :step/arguments []}
    :binding (assoc (make-binding-w f)
                    :svo {:subject subject :verb :click :interface :web})}))

(defn- plain-step
  "A bound step with NO SVO (vanilla mode)."
  [f]
  {:status :matched
   :step {:step/id (java.util.UUID/randomUUID)
          :step/text "vanilla step"
          :step/arguments []}
   :binding (make-binding-w f)})

(defn- make-plan [bound-steps & {:keys [hooks name]}]
  (cond-> {:plan/id (java.util.UUID/randomUUID)
           :plan/pickle {:pickle/id (java.util.UUID/randomUUID)
                         :pickle/name (or name "captured pickle")
                         :pickle/source-file "features/c.feature"}
           :plan/steps (vec bound-steps)
           :plan/runnable? true}
    hooks (assoc :plan/hooks hooks)))

(defn- minting-registry
  "A registry whose :web-cap adapter mints a FRESH impl per provision
   (each with its own :log atom) and contributes shot/console/boom kinds.
   `made` (atom of {}) collects the minted impls by :id for later
   inspection."
  [made & {:keys [boom-kind?]}]
  {:web-cap
   (cond-> {:factory (fn [_config]
                       (let [impl {:id (keyword (str "impl-" (count @made)))
                                   :log (atom [])}]
                         (swap! made assoc (:id impl) impl)
                         {:ok impl}))
            :cleanup (constantly {:ok :closed})
            :provides []
            :capture {:screenshot {:fn shot-fn}
                      :console {:fn console-fn :scenario-scoped? true}}}
     boom-kind? (assoc-in [:capture :boom]
                          {:fn (fn [_] (throw (ex-info "capture exploded" {})))}))})

(defn- run-wired
  "execute-suite with capture wired the way cleanup.clj wires it."
  [plans root policy registry]
  (exec/execute-suite plans {:interfaces {:web {:adapter :web-cap}}
                             :adapter-registry registry
                             :artifacts-root root
                             :capture-policy policy}))

(deftest wired-every-step-film-strip
  (let [root (str (fs/create-temp-dir))]
    (try
      (let [made (atom {})
            plan (make-plan [(svo-step :alice) (svo-step :bob) (svo-step :alice)])
            result (-> (run-wired [plan] root {:screenshot :every-step}
                                  (minting-registry made))
                       :scenarios first)]
        (is (= :passed (:status result)))
        (is (= [["step-1-screenshot-web-alice.png"]
                ["step-2-screenshot-web-bob.png"]
                ["step-3-screenshot-web-alice.png"]]
               (mapv (fn [step]
                       (mapv #(str (fs/file-name (:attachment/path %)))
                             (:attachments step)))
                     (:steps result)))
            "one ref per step, on the STEP, following the actor")
        (is (not (contains? result :attachments))
            "no scenario-level refs — placement is the label")
        (is (every? #(s/valid? ::att/ref %)
                    (mapcat :attachments (:steps result)))))
      (finally (fs/delete-tree root)))))

(deftest wired-no-svo-step-captures-all-live
  ;; The vanilla-mode ruling, through the full loop: a step with no SVO
  ;; can't be provisioned for (no interface) — so this test pre-provisions
  ;; via an SVO step, then runs the vanilla step against the live world.
  (let [root (str (fs/create-temp-dir))]
    (try
      (let [made (atom {})
            plan (make-plan [(svo-step :alice) (plain-step (fn [ctx] ctx))])
            result (-> (run-wired [plan] root {:screenshot :every-step}
                                  (minting-registry made))
                       :scenarios first)
            vanilla-step (second (:steps result))]
        (is (= :passed (:status result)))
        (is (= ["step-2-screenshot-web-alice.png"]
               (mapv (comp str fs/file-name :attachment/path)
                     (:attachments vanilla-step)))
            "no actor -> ALL live captured (here: the one browser — the
             single-browser degeneracy that makes vanilla porting cheap)"))
      (finally (fs/delete-tree root)))))

(deftest wired-on-failure-captures-all-live-at-the-instant
  (let [root (str (fs/create-temp-dir))]
    (try
      (let [made (atom {})
            order (atom [])
            plan (make-plan
                  [(svo-step :alice)
                   (svo-step :bob (fn [_] (throw (ex-info "step boom" {}))))]
                  :hooks [{:name "watcher"
                           :after (fn [{:keys [result]}]
                                    (swap! order conj
                                           [:after (count (mapcat :attachments
                                                                  (:steps result)))])
                                    nil)}])
            result (-> (run-wired [plan] root {:screenshot :on-failure}
                                  (minting-registry made))
                       :scenarios first)
            failing-step (second (:steps result))]
        (is (= :failed (:status result)) "capture never changes a red outcome")
        (is (= 2 (count (:attachments failing-step)))
            "BOTH live browsers captured at the failing instant")
        (is (every? #(str/starts-with? (str (fs/file-name (:attachment/path %)))
                                       "failure-screenshot-")
                    (:attachments failing-step)))
        (is (empty? (:attachments (first (:steps result))))
            "the passing step captured nothing under :on-failure")
        (is (= [[:after 2]] @order)
            "the After hook observed the capture refs — capture fired at
             the instant, before any After"))
      (finally (fs/delete-tree root)))))

(deftest wired-step-durations-stay-pure
  (let [root (str (fs/create-temp-dir))]
    (try
      (let [slow-registry
            {:web-cap {:factory (fn [_] {:ok {:id :one :log (atom [])}})
                       :cleanup (constantly {:ok :closed})
                       :provides []
                       :capture {:screenshot
                                 {:fn (fn [req] (Thread/sleep 80) (shot-fn req))}}}}
            plan (make-plan [(svo-step :alice)])
            result (-> (run-wired [plan] root {:screenshot :every-step} slow-registry)
                       :scenarios first)
            step (first (:steps result))]
        (is (< (:duration-ms step) 60)
            "an 80ms capture must not pollute the step's duration")
        (is (>= (:attachment/duration-ms (first (:attachments step))) 80)
            "the capture cost lands on the ref"))
      (finally (fs/delete-tree root)))))

(deftest wired-d4-flips-green-only
  (let [root (str (fs/create-temp-dir))]
    (try
      (let [made (atom {})
            green-plan (make-plan [(svo-step :alice)] :name "green")
            red-plan (make-plan [(svo-step :alice (fn [_] (throw (ex-info "assert" {}))))]
                                :name "red")
            registry (minting-registry made :boom-kind? true)
            suite (run-wired [green-plan red-plan] root {:boom :every-step} registry)
            [green red] (:scenarios suite)]
        (is (= :error (:status green))
            "D4: a green scenario whose promised evidence failed is :error")
        (is (= :capture/failed (-> green :error :type)))
        (is (= :boom (-> green :error :data :kind)) "capture-attributed")
        (is (= :failed (:status red))
            "already-red keeps its status; the ref records the miss")
        (is (some :attachment/error (mapcat :attachments (:steps red)))))
      (finally (fs/delete-tree root)))))

(deftest wired-pending-step-no-failure-capture
  (let [root (str (fs/create-temp-dir))]
    (try
      (let [made (atom {})
            plan (make-plan [(svo-step :alice (fn [_] :pending))])
            result (-> (run-wired [plan] root {:screenshot :on-failure}
                                  (minting-registry made))
                       :scenarios first)]
        (is (= :pending (:status result)))
        (is (empty? (mapcat :attachments (:steps result)))
            "a pending step is a WIP declaration, not misbehavior"))
      (finally (fs/delete-tree root)))))

(deftest wired-artifacts-dir-gate
  (testing "capture-active hookless scenario creates the artifacts dir"
    (let [root (str (fs/create-temp-dir))]
      (try
        (let [made (atom {})]
          (run-wired [(make-plan [(svo-step :alice)] :name "Gate Check")]
                     root {:screenshot :every-step} (minting-registry made))
          (is (fs/directory? (fs/path root "attachments" "gate-check"))))
        (finally (fs/delete-tree root)))))
  (testing "inactive policy creates nothing"
    (let [root (str (fs/create-temp-dir))]
      (try
        (let [made (atom {})]
          (run-wired [(make-plan [(svo-step :alice)] :name "Gate Check")]
                     root {:screenshot :never} (minting-registry made))
          (is (not (fs/exists? (fs/path root "attachments")))))
        (finally (fs/delete-tree root))))))

(deftest wired-drain-and-tail-with-afters-visibility
  (let [root (str (fs/create-temp-dir))]
    (try
      (let [made (atom {})
            seen-by-after (atom nil)
            ;; step 1 logs into alice's console via the step body
            plan (make-plan
                  [(svo-step :alice
                             (fn [ctx]
                               (let [impl (:impl (get ctx :cap/web.alice))]
                                 (swap! (:log impl) conj "from step 1"))
                               ctx))]
                  :hooks [{:name "observer"
                           :after (fn [{:keys [result]}]
                                    (reset! seen-by-after
                                            (mapv :attachment/path
                                                  (:attachments result)))
                                    nil)}])
            result (-> (run-wired [plan] root {:console :every-step}
                                  (minting-registry made))
                       :scenarios first)]
        (is (= :passed (:status result)))
        ;; step-1 drain drained "from step 1"; tail sweep found nothing.
        (is (= 1 (count (mapcat :attachments (:steps result))))
            "the per-step drain ref rides the step")
        (is (not (contains? result :attachments))
            "an empty tail sweep emits no scenario-level ref")
        (is (= [] @seen-by-after)
            "the After ran post-drain and saw the (empty) scenario-level set"))
      (finally (fs/delete-tree root)))))

(deftest wired-on-failure-drain-red-only-pre-afters
  (let [root (str (fs/create-temp-dir))]
    (try
      (let [made (atom {})
            seen-by-after (atom nil)
            plan (make-plan
                  [(svo-step :alice
                             (fn [ctx]
                               (let [impl (:impl (get ctx :cap/web.alice))]
                                 (swap! (:log impl) conj "console noise"))
                               (throw (ex-info "step boom" {}))))]
                  :hooks [{:name "observer"
                           :after (fn [{:keys [result]}]
                                    (reset! seen-by-after
                                            (mapv (comp str fs/file-name :attachment/path)
                                                  (:attachments result)))
                                    nil)}])
            result (-> (run-wired [plan] root {:console :on-failure}
                                  (minting-registry made))
                       :scenarios first)]
        (is (= :failed (:status result)))
        (is (= ["console-web-alice.log"]
               (mapv (comp str fs/file-name :attachment/path) (:attachments result)))
            "red scenario -> one whole-scenario drain, scenario-level")
        (is (= ["console-web-alice.log"] @seen-by-after)
            "the After observed the drain ref: drain fires PRE-Afters"))
      (finally (fs/delete-tree root)))))

(deftest wired-capture-and-hook-claims-coexist
  (let [root (str (fs/create-temp-dir))]
    (try
      (let [made (atom {})
            plan (make-plan
                  [(svo-step :alice
                             (fn [ctx]
                               (let [impl (:impl (get ctx :cap/web.alice))]
                                 (swap! (:log impl) conj "log line"))
                               (throw (ex-info "boom" {}))))]
                  :hooks [{:name "claimer"
                           :after (fn [{:keys [artifacts-dir]}]
                                    (spit (str artifacts-dir "/note.txt") "hook evidence")
                                    {:attachments
                                     [#:attachment{:kind :note
                                                   :path (str artifacts-dir "/note.txt")}]})}])
            result (-> (run-wired [plan] root {:console :on-failure}
                                  (minting-registry made))
                       :scenarios first)
            kinds (mapv :attachment/kind (:attachments result))]
        (is (= [:console :note] kinds)
            "capture refs first (chronological), hook claims appended"))
      (finally (fs/delete-tree root)))))

(deftest wired-parallel-smoke
  (let [root (str (fs/create-temp-dir))]
    (try
      (let [made (atom {})
            plans (mapv #(make-plan [(svo-step :alice)] :name (str "Par " %))
                        (range 3))
            suite (exec/execute-suite plans
                                      {:interfaces {:web {:adapter :web-cap}}
                                       :adapter-registry (minting-registry made)
                                       :artifacts-root root
                                       :capture-policy {:screenshot :every-step}
                                       :max-parallel 3})]
        (is (= 3 (count (:scenarios suite))))
        (is (every? #(= :passed (:status %)) (:scenarios suite)))
        (is (= #{"par-0" "par-1" "par-2"}
               (into #{} (map (comp str fs/file-name))
                     (fs/list-dir (fs/path root "attachments"))))
            "disjoint per-scenario dirs, no cross-talk")
        (is (every? #(= 1 (count (fs/list-dir %)))
                    (fs/list-dir (fs/path root "attachments")))))
      (finally (fs/delete-tree root)))))

(deftest excerpt-derivation-bounds
  (with-adir [dir]
    (let [long-log (str/join "\n" (map #(str "line-" %) (range 2000)))
          ctx (ctx-for :alice)
          _ (reset! (log-of ctx :alice) [long-log])
          copts (opts-for dir (mock-registry) {:console :on-failure})
          {:keys [refs]} (capture/drain-at-scenario-end! ctx copts true)
          excerpt (:attachment/excerpt (first refs))]
      (is (= att/excerpt-cap (count excerpt)) "bounded tail")
      (is (str/starts-with? excerpt "…") "leading ellipsis marks the head cut")
      (is (str/ends-with? excerpt "line-1999") "the tail is where the failure lives"))))
