(ns shiftlefter.integration.sms-2fa-stress-test
  "Stress test for the sl-dbu mock-backed 2FA password-reset demo
   (`examples/04-sms-2fa`).

   Acceptance criterion #1 of sl-bnk: 10 consecutive runs, 0 flakes.

   Each iteration is a fresh JVM (`clojure -M:demo`) — exercises cold
   start, fixture-server bind on port 9090, MirrorMockSMS construction,
   adapter-registry plumbing, scenario-start-ts setter, browser launch
   via Etaoin, SMS code roundtrip, browser teardown, fixture-server
   stop. Cold-start coverage is the primary intent; warm-state stress
   (multiple iterations within one JVM) is a follow-up.

   ## Gating

   This test is SKIPPED unless `SHIFTLEFTER_LIVE_SMS_STRESS=1` is set.
   It also requires Chrome on the system path (Etaoin / chromedriver).

       SHIFTLEFTER_LIVE_SMS_STRESS=1 ./bin/kaocha --focus :integration

   ## Why subprocess instead of in-process

   `runner/execute!` resolves `:step-paths` and `:glossaries` paths
   relative to the JVM cwd, and the example's setup.clj uses
   `localhost:9090` baked into the feature file. Running each iteration
   as a subprocess with `:dir example-dir` avoids JVM-chdir surgery and
   matches how a user would actually invoke the example. Tradeoff:
   ~10s/iteration JVM startup; ~150s total. Acceptable for a gated
   integration test."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; -----------------------------------------------------------------------------
;; Test gating
;; -----------------------------------------------------------------------------

(def ^:private live-stress?
  (some? (System/getenv "SHIFTLEFTER_LIVE_SMS_STRESS")))

(def ^:private iterations
  (Integer/parseInt (or (System/getenv "SHIFTLEFTER_SMS_STRESS_ITERATIONS")
                        "10")))

(def ^:private example-dir "examples/04-sms-2fa")

;; -----------------------------------------------------------------------------
;; One iteration
;; -----------------------------------------------------------------------------

(defn- run-once
  "Invoke the example via `clojure -M:demo`. Returns
   `{:exit int :out string :err string}`."
  []
  (shell/sh "clojure" "-M:demo" :dir example-dir))

(defn- summarize [{:keys [exit out err]} i]
  (format "iter %d: exit=%d\n--- STDOUT ---\n%s\n--- STDERR ---\n%s"
          i exit (or out "") (or err "")))

;; -----------------------------------------------------------------------------
;; Stress run
;; -----------------------------------------------------------------------------

(deftest ^:integration sms-2fa-stress
  (if-not live-stress?
    (testing (str "Skipping sms-2fa-stress "
                  "(set SHIFTLEFTER_LIVE_SMS_STRESS=1 to enable)")
      (is true))
    (testing (str iterations " consecutive sl-dbu runs, 0 flakes")
      (let [results (vec
                     (for [i (range iterations)]
                       (let [r (run-once)]
                         (assoc r :iter i))))
            failures (filter #(not (zero? (:exit %))) results)]
        (when (seq failures)
          (println)
          (println (str "==== sms-2fa-stress: "
                        (count failures) "/" iterations
                        " iterations FAILED ===="))
          (doseq [{:keys [iter] :as r} failures]
            (println (summarize r iter))))
        (is (empty? failures)
            (str (count failures) "/" iterations " iterations failed; "
                 "failed iters: "
                 (str/join "," (map :iter failures))))))))
