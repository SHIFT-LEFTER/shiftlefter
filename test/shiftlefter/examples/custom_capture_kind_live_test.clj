(ns shiftlefter.examples.custom-capture-kind-live-test
  "LIVE in-process validator for examples/07-custom-capture-kind (sl-9od5).

   07 is the worked example for the CONTRIBUTE side of the capture
   contract: a setup.clj adapter registry contributes a custom
   :message-log kind, and the config's :every-step policy captures it.
   Because the example is browser-free (mock SMS only — no ports, no
   ChromeDriver), the FULL arc runs here in the bare unit suite, not
   behind an env gate: config names the kind → the registry contributes
   it (first consumer of the sl-wntk entry contract) → refs land in the
   result → files and report.html land in the stamped run dir.

   Like the planning validators (sl-ybox), this reads the SHIPPED example
   dir directly — rot detection requires the real thing. The run writes a
   stamped dir under the example's (self-gitignored) sl/results/; the
   test deletes that run dir afterwards so repeated suite runs don't
   accumulate litter."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.project-context :as pc]
            [shiftlefter.runner.core :as rc]))

(def ^:private ex-07 "examples/07-custom-capture-kind/sl/")

(defn- run-live
  "Execute the example in-process (output captured so green suite runs
   don't print the runner's console report)."
  []
  (let [result (atom nil)]
    (with-out-str
      (reset! result
              (rc/execute! {:paths [(str ex-07 "features")]
                            :project-context (pc/resolve {:config-path (str ex-07 "shiftlefter.edn")})})))
    @result))

(deftest custom-capture-kind-full-arc
  (let [{:keys [exit-code groups results-dir]} (run-live)]
    (try
      (testing "the example runs green"
        (is (= 0 exit-code)))
      (let [group      (first groups)
            group-root (:group-root group)
            steps      (->> (get-in group [:result :scenarios])
                            (mapcat :steps))
            refs       (mapcat :attachments steps)
            ml-refs    (filter #(= :message-log (:attachment/kind %)) refs)]
        (testing "refs land on the capturing steps (station 3 of the arc)"
          ;; 4 of the scenario's 5 steps carry a ref; the opening
          ;; empty-inbox step exercises the nil branch — no file, no ref.
          (is (= 4 (count ml-refs)))
          (is (empty? (:attachments (first steps))))
          (is (every? #(= :capture-policy (:attachment/source %)) ml-refs))
          (is (every? #(= "text/plain" (:attachment/media-type %)) ml-refs))
          (is (every? (comp pos? :attachment/bytes) ml-refs))
          (is (str/includes? (:attachment/excerpt (first ml-refs)) "ping")))
        (testing "the framework wrote the files the refs point to"
          (doseq [r ml-refs]
            (is (fs/exists? (fs/path group-root (:attachment/path r)))
                (str "missing artifact " (:attachment/path r)))))
        (testing "report.html renders the contributed kind (station 4)"
          (let [report (fs/path group-root "report.html")]
            (is (fs/exists? report))
            (is (str/includes? (slurp (fs/file report)) "message-log")))))
      (finally
        ;; The end-of-run report copy (configured [:runner :report :html])
        ;; and the stamped run dir are both run outputs — remove them so
        ;; suite runs leave the shipped example dir as committed.
        (when results-dir
          (fs/delete-tree results-dir))
        (let [copy (fs/path ex-07 "results" "report.html")]
          (when (fs/exists? copy)
            (fs/delete copy)))))))
