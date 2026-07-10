(ns shiftlefter.runner.report.html-test
  "Unit + corpus tests for the native HTML run report (sl-muq9).

   The document is a viewer: the EDN data island is the sole source of
   truth, so correctness is netted by SLICING the island and re-reading it
   with clojure.edn — there is deliberately NO byte-golden on the HTML
   itself (styling churn would make it meaningless; the island compare is
   the real lock). Renderer behavior that only exists in the browser
   (default-open, toggles) is locked structurally against the JS source and
   covered by the fit-pass eyeball."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.corpus.harness :as harness]
            [shiftlefter.gherkin.api :as api]
            [shiftlefter.gherkin.pickler :as pk]
            [shiftlefter.runner.report.html :as html]
            [shiftlefter.runner.reporter :as reporter]
            [shiftlefter.stepengine.macros :as macros]
            [shiftlefter.stepengine.registry :as registry]))

(defn- clean-registry-fixture [f]
  (registry/clear-registry!)
  (f)
  (registry/clear-registry!))

(use-fixtures :each clean-registry-fixture)

;; -----------------------------------------------------------------------------
;; Fixtures / helpers
;; -----------------------------------------------------------------------------

(def ^:private esc (str (char 0x1b)))   ; ANSI escape introducer

(def ^:private run-ctx
  {:run-id "r-1" :project-name "demo" :version "0.5.1" :mode :shifted
   :started-at "2026-07-09T03:00:00" :project-root "/proj"
   :allow-pending? false})

(def ^:private run-summary
  {:run-id "r-1" :exit-code 1 :status :failed
   :counts {:passed 1 :failed 1 :pending 0 :skipped 0}})

(defn- step
  ([kw text status] (step kw text status nil))
  ([kw text status error]
   (cond-> {:step {:step/keyword kw :step/text text} :status status :duration-ms 1.0}
     error (assoc :error error))))

(defn- scenario
  "Build a scrubbed scenario envelope (through the REAL scenario-envelope seam)."
  [{:keys [name feature status steps line tags]}]
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

(defn- exc [msg cls] {:type :step/exception :message msg :exception-class cls})

(defn- island-of
  "Slice the EDN data island out of a document string, raw."
  [doc]
  (let [marker "id=\"run-data\">"
        start (long (+ (str/index-of doc marker) (count marker)))
        end (long (str/index-of doc "</script>" start))]
    (subs doc start end)))

(defn- doc-and-island
  "Build a document from island parts; return [doc parsed-island]."
  [scenarios & {:keys [ctx summary diagnostics]}]
  (let [doc (html/build-document {:run-ctx (or ctx run-ctx)
                                  :scenarios (vec scenarios)
                                  :diagnostics diagnostics
                                  :summary (or summary run-summary)})]
    [doc (edn/read-string (island-of doc))]))

;; -----------------------------------------------------------------------------
;; AC2: the island is the sole source of truth and round-trips as EDN
;; -----------------------------------------------------------------------------

(deftest island-round-trips
  (let [envs [(scenario {:name "p" :feature "F" :status :passed
                         :tags [{:name "@smoke"}]
                         :steps [(step "Given" "a" :passed)]})
              (scenario {:name "f" :feature "F" :status :failed
                         :steps [(step "Then" "b" :failed (exc "boom" "E"))]})]
        [_doc island] (doc-and-island envs)]
    (testing "island carries run-ctx + envelopes + summary, byte-faithfully"
      (is (= run-ctx (:run-ctx island)))
      (is (= (vec envs) (:scenarios island)))
      (is (= run-summary (:summary island))))
    (testing "island is edn-safe (invariant 3 survives emission)"
      (is (reporter/edn-safe? island)))
    (testing "tags visible in the data"
      (is (= "@smoke" (-> island :scenarios first :plan :plan/pickle
                          :pickle/tags first :name))))))

(deftest selection-story-in-run-ctx
  (testing "AC3/AC4: a filter-active run-ctx carries :selection through the island"
    (let [ctx (assoc run-ctx :selection {:selected 1 :filtered-out 3
                                         :filter {:include #{"@smoke"}}})
          [_doc island] (doc-and-island [(scenario {:name "p" :feature "F" :status :passed
                                                    :steps [(step "Given" "a" :passed)]})]
                                        :ctx ctx)]
      (is (= {:selected 1 :filtered-out 3 :filter {:include #{"@smoke"}}}
             (-> island :run-ctx :selection))))))

;; -----------------------------------------------------------------------------
;; AC6: hostile data stays inert
;; -----------------------------------------------------------------------------

(deftest hostile-scenario-name-is-inert
  (let [nasty "<script>alert(1)</script>"
        [doc island] (doc-and-island
                      [(scenario {:name nasty :feature "F" :status :failed
                                  :steps [(step "Then" (str "x " nasty) :failed
                                                (exc (str "err " nasty) "E"))]})])]
    (testing "no raw script-open/close from data anywhere in the document"
      (is (not (str/includes? doc "<script>alert")))
      ;; the ONLY </script> occurrences are the island's and the renderer's
      ;; own closing tags — data cannot terminate the island early
      (is (= 2 (count (re-seq #"</script" doc)))))
    (testing "the name survives as pure data through the island"
      (is (= nasty (-> island :scenarios first :plan :plan/pickle :pickle/name))))
    (testing "the renderer never uses innerHTML (textContent-only contract)"
      (is (not (str/includes? (slurp (io/resource "report/report.js"))
                              "innerHTML"))))))

(deftest ansi-stripped-from-island-strings
  (let [[_doc island] (doc-and-island
                       [(scenario {:name "colors" :feature "F" :status :passed
                                   :steps [(step "Given" (str esc "[31m" "red" esc "[0m text")
                                                 :passed)]})])]
    (is (= "red text" (-> island :scenarios first :steps first :step :step/text)))))

(deftest island-immune-to-ambient-printer-bindings
  ;; clojure.main (every CLI run) binds *print-namespace-maps* true, and a
  ;; REPL can bind *print-length*. Either would corrupt the island — the JS
  ;; parser doesn't expand #:pickle{...} syntax, and truncation is silent
  ;; data loss. Emission must pin the printer dynvars.
  (binding [*print-namespace-maps* true
            *print-length* 3]
    (let [doc (html/build-document {:run-ctx run-ctx
                                    :scenarios [(scenario {:name "p" :feature "F" :status :passed
                                                           :steps [(step "Given" "a" :passed)
                                                                   (step "And" "b" :passed)
                                                                   (step "And" "c" :passed)
                                                                   (step "And" "d" :passed)
                                                                   (step "And" "e" :passed)]})]
                                    :diagnostics nil
                                    :summary run-summary})
          raw (island-of doc)
          island (edn/read-string raw)]
      (is (str/includes? raw ":pickle/source-file")
          "no #:pickle{...} namespace-map syntax in the island")
      (is (not (str/includes? raw "...")) "no *print-length* truncation")
      (is (= 5 (-> island :scenarios first :steps count))))))

(deftest multibyte-text-round-trips
  (let [name "em—dash ✓ → done"
        [_doc island] (doc-and-island
                       [(scenario {:name name :feature "F" :status :passed
                                   :steps [(step "Given" "a" :passed)]})])]
    (is (= name (-> island :scenarios first :plan :plan/pickle :pickle/name)))))

;; -----------------------------------------------------------------------------
;; AC1/AC7/AC9: self-contained shell — offline, one style block, noscript
;; -----------------------------------------------------------------------------

(deftest document-is-self-contained
  (let [[doc _] (doc-and-island [(scenario {:name "p" :feature "F" :status :passed
                                            :steps [(step "Given" "a" :passed)]})])]
    (testing "zero external URLs / fetch surfaces anywhere in the document"
      (is (zero? (count (re-seq #"(?i)https?://" doc))))
      (is (not (re-find #"(?i)@import" doc)))
      (is (not (re-find #"(?i)<link" doc)))
      (is (not (re-find #"(?i)\bfetch\s*\(" doc))))
    (testing "shell shape: doctype, one style block, island + renderer scripts"
      (is (str/starts-with? doc "<!DOCTYPE html>"))
      (is (= 1 (count (re-seq #"<style>" doc))))
      (is (= 1 (count (re-seq #"<script type=\"application/edn\"" doc))))
      (is (= 2 (count (re-seq #"<script" doc)))))
    (testing "noscript note present"
      (is (str/includes? doc "<noscript>")))
    (testing "both color schemes styled from one block"
      (is (str/includes? doc "prefers-color-scheme")))))

;; -----------------------------------------------------------------------------
;; AC4/AC7 structural: renderer behavior locked against the JS source
;; (browser-only behavior; the fit-pass eyeball is the live check)
;; -----------------------------------------------------------------------------

(deftest renderer-structural-locks
  (let [js (slurp (io/resource "report/report.js"))]
    (testing "failures + errors default-open"
      (is (str/includes? js "details.open = red")))
    (testing "expand-all / collapse-all + failures-only toggle exist"
      (is (str/includes? js "setAllOpen"))
      (is (str/includes? js "failures-only")))
    (testing "D6 macro rules: child marker + dual attribution"
      (is (str/includes? js "\"+ [\""))
      (is (str/includes? js "via macro '")))))

;; -----------------------------------------------------------------------------
;; AC5: macro golden parity — the same real-expansion scenario the JUnit
;; golden locks, verified structurally through the island (provenance keys
;; the renderer's D6 rules consume must survive emission intact)
;; -----------------------------------------------------------------------------

(defn- macro-scenario
  "Real macro expansion of macro_happy.feature via expand-pickle against
   auth.ini — same construction as the JUnit macro golden (sl-40to AC6)."
  []
  (let [src (slurp "test/fixtures/features/macro_happy.feature")
        {:keys [ast]} (api/parse-string src)
        {:keys [pickles]} (pk/pickles ast {} "test/fixtures/features/macro_happy.feature")
        {:keys [registry]} (macros/load-registries ["test/fixtures/macros/auth.ini"])
        {:keys [pickle]} (macros/expand-pickle {:enabled? true} (first pickles) registry)
        steps (:pickle/steps pickle)
        statuses [:failed :passed :passed :failed :skipped :skipped :skipped]
        errs {3 (exc "button missing" "clojure.lang.ExceptionInfo")}
        srs (map-indexed (fn [i s]
                           (cond-> {:step s :status (nth statuses i) :duration-ms (double i)}
                             (errs i) (assoc :error (errs i))))
                         steps)]
    (reporter/scenario-envelope
     {:status :failed :duration-ms 12.0
      :plan {:plan/pickle pickle} :steps (vec srs)})))

(deftest macro-provenance-survives-the-island
  (let [[_doc island] (doc-and-island [(macro-scenario)])
        steps (-> island :scenarios first :steps)
        wrapper (first steps)
        children (filter #(= :expanded (get-in % [:step :step/macro :role])) steps)
        failing (first (filter #(= :failed (:status %)) children))]
    (testing "authored wrapper: synthetic, :role :call, carries :step-count"
      (is (true? (get-in wrapper [:step :step/synthetic?])))
      (is (= :call (get-in wrapper [:step :step/macro :role])))
      (is (= 5 (get-in wrapper [:step :step/macro :step-count]))))
    (testing "expanded children carry key/index/call-site for the N/M marker"
      (is (= 5 (count children)))
      (is (= (range 5) (map #(get-in % [:step :step/macro :index]) children)))
      (is (every? #(= "login as alice" (get-in % [:step :step/macro :key])) children))
      (is (every? #(= 3 (get-in % [:step :step/macro :call-site :line])) children)))
    (testing "dual attribution inputs: the failing child has both the error
              and its macro provenance"
      (is (= "clojure.lang.ExceptionInfo" (get-in failing [:error :exception-class])))
      (is (= 2 (get-in failing [:step :step/macro :index]))))))

;; -----------------------------------------------------------------------------
;; AC8: loud failure on write error (reporter invariant 4)
;; -----------------------------------------------------------------------------

(deftest write-failure-throws
  ;; A path whose parent is an existing FILE cannot be mkdir'd or written —
  ;; spit throws, and on-run-end must let it propagate.
  (let [tmp (java.io.File/createTempFile "sl-html" ".notdir")
        bad-path (str (.getPath tmp) "/report.html")
        r (html/make-reporter {:path bad-path})]
    (reporter/on-run-start r run-ctx)
    (reporter/on-scenario-complete
     r (scenario {:name "p" :feature "F" :status :passed
                  :steps [(step "Given" "a" :passed)]}))
    (is (thrown? java.io.IOException (reporter/on-run-end r run-summary)))
    (.delete tmp)))

;; -----------------------------------------------------------------------------
;; AC2/AC10: corpus run — island vs manifest (names, statuses, counts,
;; durations at-or-above floors), goldens untouched by construction (the
;; console/EDN/JUnit golden suites run elsewhere in this suite)
;; -----------------------------------------------------------------------------

(defn- island-status-mismatch
  "Nil when the island scenario envelope matches the manifest expectation.
   The four corpus statuses project onto envelope shapes: passed/pending map
   1:1; corpus failed = :failed whose first failed step has no
   :exception-class; corpus error = :failed with one."
  [{:keys [expected-status] :as _entry} env]
  (let [status (:status env)
        failing (first (filter #(and (= :failed (:status %))
                                     (not (get-in % [:step :step/synthetic?])))
                               (:steps env)))
        ex-class (get-in failing [:error :exception-class])]
    (case expected-status
      :passed (when (not= :passed status) {:actual status})
      :pending (when (not= :pending status) {:actual status})
      :failed (when-not (and (= :failed status) (nil? ex-class))
                {:actual status :exception-class ex-class})
      :error (when-not (and (= :failed status)
                            (= "clojure.lang.ExceptionInfo" ex-class))
               {:actual status :exception-class ex-class}))))

(deftest corpus-island-vs-manifest
  (let [{:keys [manifest html-file result]}
        (harness/run-corpus! {:seed 4242 :html? true})
        doc (slurp html-file)
        island (edn/read-string (island-of doc))
        envs (:scenarios island)
        by-name (into {} (map (fn [e] [(-> e :plan :plan/pickle :pickle/name) e]))
                      envs)
        entries (:scenarios manifest)]
    (testing "run executed as designed"
      (is (= 1 (:exit-code result)))
      (is (fs/exists? html-file)))
    (testing "every manifest scenario present in the island, none extra"
      (is (= (set (map :name entries)) (set (keys by-name)))))
    (testing "per-scenario status matches the manifest expectation"
      (doseq [entry entries
              :let [env (get by-name (:name entry))
                    mm (when env (island-status-mismatch entry env))]]
        (is (nil? mm) (pr-str (:name entry) mm))))
    (testing "per-scenario durations at-or-above their floors (floors only)"
      (doseq [entry entries
              :let [env (get by-name (:name entry))]]
        (is (>= (:duration-ms env) (:duration-floor-ms entry))
            (pr-str (:name entry)))))
    (testing "island summary counts consistent with the manifest"
      (let [by-status (frequencies (map :expected-status entries))
            expected {:passed (get by-status :passed 0)
                      :failed (+ (get by-status :failed 0)
                                 (get by-status :error 0))
                      :pending (get by-status :pending 0)
                      :skipped 0}]
        (is (= expected (select-keys (-> island :summary :counts)
                                     [:passed :failed :pending :skipped])))))
    (testing "island run-ctx is the enriched ctx (DP1)"
      (let [ctx (:run-ctx island)]
        (is (string? (:version ctx)))
        (is (string? (:started-at ctx)))
        (is (contains? #{:shifted :vanilla} (:mode ctx)))
        (is (string? (:project-name ctx)))
        (is (nil? (:selection ctx)) "no filter active => no selection key")))
    (testing "the document at corpus scale is still self-contained"
      (is (zero? (count (re-seq #"(?i)https?://" doc)))))))

(deftest corpus-island-selection-story
  (let [{:keys [manifest html-file]}
        (harness/run-corpus! {:seed 4242 :html? true
                              :tag-filter {:include #{"@corpus-suite"}}})
        island (edn/read-string (island-of (slurp html-file)))
        selection (-> island :run-ctx :selection)
        expected-selected (->> (:scenarios manifest)
                               (filter #(some #{"@corpus-suite"} (:tags %)))
                               count)]
    (testing "AC4: the selection story rides run-ctx when a filter is active"
      (is (= expected-selected (:selected selection)))
      (is (= (- (count (:scenarios manifest)) expected-selected)
             (:filtered-out selection)))
      (is (= {:include #{"@corpus-suite"}} (:filter selection))))
    (testing "island scenarios are exactly the selected subset"
      (is (= expected-selected (count (:scenarios island)))))))
