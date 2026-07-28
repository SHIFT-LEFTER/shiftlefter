(ns shiftlefter.runner.results-test
  "The run-results directory convention (sl-y3wd, N5 D7).

   Unit half: the pure stamp and the create/collision/gitignore/skip rules
   of ensure-run-dir!. Integration half: rc/execute! against a temp project
   creates exactly one stamped dir per invocation; dry-run and the internal
   :results-dir? seam create nothing; reporter output carries no trace (the
   goldens lock that suite-wide)."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.core :as rc]
            [shiftlefter.runner.results :as results]))

(def ^:private repo-root (System/getProperty "user.dir"))

(deftest stamp-test
  (testing "the ratified example: compact, colon-free, trailing Z"
    (is (= "20260712T153042Z" (results/stamp "2026-07-12T15:30:42"))))
  (testing "filesystem/Windows-safe charset only"
    (is (re-matches #"[0-9TZ]+" (results/stamp "2026-01-01T00:00:00")))))

(defn- ctx-at [root]
  {:config-root (str root) :config-source :discovered})

(deftest ensure-run-dir-test
  (let [tmp (fs/real-path (fs/create-temp-dir))]
    (try
      (testing "first call creates results/<stamp>/ and the self-ignoring .gitignore"
        (let [state (atom nil)
              dir (results/ensure-run-dir! state (ctx-at tmp)
                                           "2026-07-12T15:30:42" "aaaabbbb-uuid")]
          (is (= (str (fs/path tmp "results" "20260712T153042Z")) dir))
          (is (fs/directory? dir))
          (is (= "*\n" (slurp (str (fs/path tmp "results" ".gitignore")))))
          (testing "later calls in the same invocation return the cached dir"
            (is (= dir (results/ensure-run-dir! state (ctx-at tmp)
                                                "2026-07-12T15:30:59" "other-uuid"))))))
      (testing "same-second collision from a fresh invocation gets the run-id suffix"
        (let [dir (results/ensure-run-dir! (atom nil) (ctx-at tmp)
                                           "2026-07-12T15:30:42" "ffffffff-uuid")]
          (is (= (str (fs/path tmp "results" "20260712T153042Z-ffffffff")) dir))
          (is (fs/directory? dir))))
      (testing "a :defaults context (no real config) creates nothing"
        (let [tmp2 (fs/real-path (fs/create-temp-dir))]
          (try
            (is (nil? (results/ensure-run-dir!
                       (atom nil) {:config-root (str tmp2) :config-source :defaults}
                       "2026-07-12T15:30:42" "any-uuid")))
            (is (not (fs/exists? (fs/path tmp2 "results"))))
            (finally (fs/delete-tree tmp2)))))
      (finally (fs/delete-tree tmp)))))

(defn- run-dirs [project-root]
  (when (fs/exists? (fs/path project-root "results"))
    (->> (fs/list-dir (fs/path project-root "results"))
         (filter fs/directory?)
         (mapv (comp str fs/file-name)))))

(defn- execute-quietly! [opts]
  ;; :edn keeps the report on stdout; swallow it — these tests assert on the
  ;; filesystem and the return map only.
  (let [result (atom nil)]
    (with-out-str (reset! result (rc/execute! opts)))
    @result))

(deftest execute-creates-run-dir-test
  (let [tmp (fs/real-path (fs/create-temp-dir))
        opts {:paths [(str (fs/path repo-root "test/fixtures/features/basic.feature"))]
              :step-paths [(str (fs/path repo-root "test/fixtures/steps/"))]
              :config-path (str (fs/path tmp "shiftlefter.edn"))
              :edn true}]
    (try
      (spit (str (fs/path tmp "shiftlefter.edn")) "{:runner {:step-paths []}}")
      (testing "an executing run creates exactly one stamped dir at <config-root>/results/"
        (let [result (execute-quietly! opts)
              dirs (run-dirs tmp)]
          (is (= 0 (:exit-code result)))
          (is (= 1 (count dirs)))
          (is (re-matches #"[0-9]{8}T[0-9]{6}Z" (first dirs)))
          (is (= (str (fs/path tmp "results" (first dirs)))
                 (:results-dir result))
              ":results-dir rides the execute! return map (not run-ctx)")))
      (testing "dry-run creates nothing further"
        (execute-quietly! (assoc opts :dry-run true))
        (is (= 1 (count (run-dirs tmp)))))
      (testing "the internal :results-dir? seam suppresses creation"
        (let [result (execute-quietly! (assoc opts :results-dir? false))]
          (is (= 0 (:exit-code result)))
          (is (nil? (:results-dir result)))
          (is (= 1 (count (run-dirs tmp))))))
      (finally (fs/delete-tree tmp)))))
