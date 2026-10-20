(ns shiftlefter.report-smoke-test
  "Runtime smoke for the shipped HTML report renderer (sl-t0r9).

   The t86d escape class this gates: resources/report/report.js renders
   client-side on DOMContentLoaded, so a runtime error (the t86d
   ReferenceError) blanks every `--html` report while exit codes, counts,
   and the EDN island stay perfect — bin/sieve-js is syntax-only and the
   JVM golden tests slurp-assert strings without executing JS. This test
   EXECUTES the renderer: it materializes a page for every verdict status
   through the REAL reporter seams (generated at test time, never frozen
   fixtures — the islands ride the reporter's own emitters so they cannot
   drift), then node+jsdom loads each page and fails on an empty #app or
   any console/window error (test/report_smoke/smoke.js, adapted from the
   Warden pass-2 Q3 harness).

   Self-skips (the daemon-suite pattern): node absent, or jsdom's one-time
   `npm ci` fetch failing (first run needs the network; the committed
   lockfile makes it repeatable, and node_modules rides the global
   gitignore). A machine without node never runs this gate — the loud
   skip line below keeps that visible (the sl-2b2k zero-gates lesson)."
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [shiftlefter.runner.report.html :as html]
   [shiftlefter.runner.reporter :as reporter]))

(def ^:private smoke-dir "test/report_smoke")

(defn- sh-safe [& args]
  (try (apply shell/sh args)
       (catch java.io.IOException _ nil)))

;; ---------------------------------------------------------------------------
;; page generation — every verdict status through the real reporter seams
;; (scenario shapes from the Warden Q3 gen.clj)
;; ---------------------------------------------------------------------------

(def ^:private run-ctx
  {:run-id "r-1" :project-name "demo" :version "0.5.5"
   :started-at "2026-08-23T23:00:00" :project-root "/proj"
   :allow-pending? false})

(defn- step
  ([kw text status] (step kw text status nil))
  ([kw text status error]
   (cond-> {:step {:step/keyword kw :step/text text} :status status :duration-ms 1.0}
     error (assoc :error error))))

(defn- scenario [{:keys [name feature status steps line tags]}]
  (reporter/scenario-envelope
   {:status status
    :duration-ms 2.0
    :plan {:plan/pickle (cond-> {:pickle/id (java.util.UUID/randomUUID)
                                 :pickle/name name
                                 :pickle/feature-name feature
                                 :pickle/source-file "/proj/features/x.feature"
                                 :pickle/location {:line (or line 1)}}
                          tags (assoc :pickle/tags tags))}
    :steps steps}))

(def ^:private exc
  ;; markup-hostile on purpose: the message must survive HTML escaping
  {:type :step/exception :message "boom <b>x</b> </script"
   :exception-class "java.lang.RuntimeException"})

(defn- scen-pass []
  (scenario {:name "p" :feature "F" :status :passed
             :steps [(step :given "a" :passed)
                     ;; sl-tcsq: a polled oracle — exercises the renderer's
                     ;; `(N polls)` arm on every generated page.
                     (assoc (step :then "b" :passed)
                            :poll-counts {:attempts 3 :answered 1 :threw 0
                                          :observation-throws 0})]}))

(defn- scen-fail []
  (scenario {:name "f" :feature "F" :status :failed
             :steps [(step :given "a" :passed)
                     (step :when "b" :failed exc)
                     (step :then "c" :skipped)]}))

(defn- scen-pend []
  (scenario {:name "pend" :feature "G" :status :pending
             :steps [(step :given "a" :pending)]}))

(defn- doc! [dir name payload]
  (spit (str (fs/path dir name)) (html/build-document payload)))

(defn- generate-pages!
  "One page per verdict status plus the renderer's edge arms:
   unknown-status fallback, empty run, planning failure (real seam,
   grammar hint included), and the invocation TOC carrying all five
   statuses — the exact surface the t86d rewrite touched."
  [dir]
  (doc! dir "passed.html"
        {:run-ctx run-ctx :scenarios [(scen-pass)] :diagnostics nil
         :summary {:run-id "r-1" :exit-code 0 :status :passed
                   :counts {:passed 1 :failed 0 :pending 0 :skipped 0}}})
  (doc! dir "failed.html"
        {:run-ctx run-ctx :scenarios [(scen-pass) (scen-fail)] :diagnostics nil
         :summary {:run-id "r-1" :exit-code 1 :status :failed
                   :counts {:passed 1 :failed 1 :pending 0 :skipped 0}}})
  (doc! dir "degraded.html"
        {:run-ctx run-ctx :scenarios [(scen-pass) (scen-pend)] :diagnostics nil
         :summary {:run-id "r-1" :exit-code 4 :status :degraded
                   :counts {:passed 1 :failed 0 :pending 1 :skipped 0}}})
  (doc! dir "crashed.html"
        {:run-ctx run-ctx :scenarios [(scen-pass)] :diagnostics nil
         :summary {:run-id "r-1" :exit-code 3 :status :crashed
                   :counts {:passed 1 :failed 0 :pending 0 :skipped 0}}})
  (doc! dir "unknown-status.html"
        {:run-ctx run-ctx :scenarios [(scen-pass)] :diagnostics nil
         :summary {:run-id "r-1" :exit-code 7 :status :weird}})
  ;; sl-78u4 (Warden W10): the :halted record renders as one header line.
  (doc! dir "halted.html"
        {:run-ctx run-ctx :scenarios [(scen-pass) (scen-fail)] :diagnostics nil
         :summary {:run-id "r-1" :exit-code 4 :status :degraded
                   :halted {:after-scenario 1 :scenario "p"}
                   :counts {:passed 1 :failed 0 :pending 0 :skipped 1 :unobserved 1}}})
  ;; sl-617t: the abort record in the same slot, with a held scenario.
  (doc! dir "aborted.html"
        {:run-ctx run-ctx
         :scenarios [(scen-pass)
                     (assoc (scen-pass) :status :skipped
                            :held {:reason :aborted :after-step 1})]
         :diagnostics nil
         :summary {:run-id "r-1" :exit-code 4 :status :degraded
                   :halted {:reason :aborted :after-scenario 1}
                   :counts {:passed 1 :failed 0 :pending 0 :skipped 0 :held 1}}})
  (doc! dir "empty.html"
        {:run-ctx run-ctx :scenarios [] :diagnostics nil
         :summary {:run-id "r-1" :exit-code 0 :status :passed
                   :counts {:passed 0 :failed 0 :pending 0 :skipped 0}}})
  (html/write-planning-failure!
   (str (fs/path dir "planning-failed.html")) run-ctx
   {:undefined [{:status :undefined
                 :step {:step/text "the gateway degrades, the client retries."
                        :step/location {:line 4 :column 5}}
                 :source-file "/proj/features/broken.feature"
                 :hint {:type :grammar/prose-as-step
                        :suggestion "This line reads like description prose."}}]
    :errors [{:type :hooks/unknown-name :message "Unknown hook name(s): [nope]"}]
    :counts {:undefined-count 1 :total-issues 2}}
   {:run-id "r-2" :exit-code 2 :status :planning-failed
    :counts {:undefined-count 1 :total-issues 2}})
  (html/write-invocation-toc!
   nil (str (fs/path dir "toc.html"))
   {:run-ctx {:run-id "r-1" :project-name "proj" :version "0.5.5"
              :started-at "2026-08-23T23:00:00"}
    :toc {:groups [{:label "alpha" :path "alpha/report.html" :exit-code 0 :status :passed
                    :counts {:passed 2 :failed 0 :pending 0 :skipped 0}}
                   {:label "beta" :path "beta/report.html" :exit-code 1 :status :failed
                    :counts {:passed 1 :failed 1 :pending 0 :skipped 0}}
                   {:label "gamma" :exit-code 2 :status :planning-failed}
                   {:label "delta" :path "delta/report.html" :exit-code 4 :status :degraded
                    :counts {:passed 1 :failed 0 :pending 1 :skipped 0}}
                   {:label "eps" :exit-code 3 :status :crashed}
                   ;; sl-78u4: a :run-scope-halt-skipped group — no exit code.
                   {:label "zeta" :status :skipped}]}
    :summary {:run-id "r-1" :exit-code 4 :status :degraded
              :halted {:after-scenario 2 :scenario "d2" :group/label "delta"}}}))

;; ---------------------------------------------------------------------------
;; the smoke
;; ---------------------------------------------------------------------------

(defn- node-available? []
  (let [r (sh-safe "node" "--version")]
    (and r (zero? (:exit r)))))

(defn- deps-ready?
  "node_modules present, or one `npm ci` away (lockfile-pinned fetch)."
  []
  (or (fs/exists? (fs/path smoke-dir "node_modules" "jsdom"))
      (let [r (sh-safe "npm" "ci" "--no-audit" "--no-fund" :dir smoke-dir)]
        (and r (zero? (:exit r))))))

(deftest report-renderer-executes-clean-for-every-status
  (cond
    (not (node-available?))
    (do (println "report smoke SKIPPED: node not on PATH — the renderer gate did not run")
        (is true "skipped: node absent"))

    (not (deps-ready?))
    (do (println "report smoke SKIPPED LOUDLY: npm ci failed (first run needs the"
                 "network to fetch the lockfile-pinned jsdom) — the renderer gate"
                 "did not run")
        (is true "skipped: jsdom fetch failed"))

    :else
    (testing "every verdict status renders a non-empty #app with zero errors"
      (let [dir (str (fs/create-temp-dir {:prefix "report-smoke"}))]
        (try
          (generate-pages! dir)
          (let [{:keys [exit out err]}
                (shell/sh "node" (str (fs/absolutize (fs/path smoke-dir "smoke.js"))) dir)
                header-of (fn [page]
                            (->> (str/split-lines out)
                                 (drop-while #(not (str/includes? % page)))
                                 second
                                 str))]
            (is (zero? exit)
                (str "report renderer smoke failed — a shipped report page "
                     "renders empty or errors at runtime (the t86d class):\n"
                     out "\n" err))
            (testing "sl-78u4 (Warden W10): the :halted record is a header
                      line on the run page AND the invocation TOC (the smoke
                      harness prints each page's rendered header text)"
              (is (str/includes?
                   (header-of "halted.html")
                   "halted after scenario 1 (p): observation failure"))
              (is (str/includes?
                   (header-of "toc.html")
                   "halted after scenario 2 (d2): observation failure — in group delta")))
            (testing "sl-617t: the abort record renders in the same header
                      slot with the held count"
              (is (str/includes?
                   (header-of "aborted.html")
                   "aborted after scenario 1: 1 scenario(s) held"))))
          (finally
            (fs/delete-tree dir)))))))
