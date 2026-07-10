(ns shiftlefter.runner.report.junit-test
  "Unit tests for the JUnit-XML reporter (sl-40to).

   Correctness is netted three ways per the grooming: a community XSD
   (test/fixtures/junit/junit.xsd), a parse-back property (clojure.xml,
   zero deps — proves attribute- and text-context escaping), and — outside
   this file — a real GitLab ingest. The macro golden is the standing
   provenance regression the 0.6 macro rewrite must keep green (AC6)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.xml :as xml]
            [shiftlefter.gherkin.api :as api]
            [shiftlefter.gherkin.pickler :as pk]
            [shiftlefter.stepengine.macros :as macros]
            [shiftlefter.runner.reporter :as reporter]
            [shiftlefter.runner.report.junit :as ju]))

;; -----------------------------------------------------------------------------
;; Fixtures / helpers
;; -----------------------------------------------------------------------------

(def ^:private esc (str (char 0x1b)))   ; ANSI escape introducer
(def ^:private bel (str (char 0x07)))   ; illegal C0 control
(def ^:private nul (str (char 0x00)))   ; illegal C0 control

(def ^:private run-ctx
  {:project-name "demo" :version "0.5.1" :mode :shifted
   :started-at "2026-07-09T03:00:00" :hostname "testhost"
   :project-root "/proj" :allow-pending? false})

(defn- step
  ([kw text status] (step kw text status nil))
  ([kw text status error]
   (cond-> {:step {:step/keyword kw :step/text text} :status status :duration-ms 1.0}
     error (assoc :error error))))

(defn- scenario
  "Build a scrubbed scenario envelope (through the REAL scenario-envelope seam)."
  [{:keys [name feature status steps line]}]
  (reporter/scenario-envelope
   {:status status
    :duration-ms 2.0
    :plan {:plan/pickle {:pickle/id (java.util.UUID/randomUUID)
                         :pickle/name name
                         :pickle/feature-name feature
                         :pickle/source-file "/proj/features/x.feature"
                         :pickle/location {:line (or line 1)}}}
    :steps steps}))

(defn- doc [scenarios] (ju/build-document scenarios run-ctx))

(defn- xsd-valid?
  "Validate an XML string against the bundled community JUnit XSD. Throws
   (SAXException) with a descriptive message on invalid input."
  [xml-str]
  (let [factory (javax.xml.validation.SchemaFactory/newInstance
                 javax.xml.XMLConstants/W3C_XML_SCHEMA_NS_URI)
        schema (.newSchema factory (io/file "test/fixtures/junit/junit.xsd"))
        validator (.newValidator schema)]
    (.validate validator
               (javax.xml.transform.stream.StreamSource.
                (java.io.StringReader. xml-str)))
    true))

(defn- parse [xml-str]
  (xml/parse (java.io.ByteArrayInputStream. (.getBytes ^String xml-str "UTF-8"))))

(defn- exc [msg cls] {:type :step/exception :message msg :exception-class cls})

(defn- red-count
  "Number of <failure>+<error> elements in the document."
  [xml]
  (count (re-seq #"<(failure|error)\b" xml)))

;; -----------------------------------------------------------------------------
;; XSD validation + parse-back
;; -----------------------------------------------------------------------------

(deftest validates-against-community-xsd
  (testing "AC2: a mixed pass/fail/error/skipped document validates"
    (let [xml (doc [(scenario {:name "p" :feature "F" :status :passed
                               :steps [(step "Given" "a" :passed)]})
                    (scenario {:name "f" :feature "F" :status :failed
                               :steps [(step "Then" "b" :failed {:type :step/invalid-return :message "bad"})]})
                    (scenario {:name "e" :feature "F" :status :failed
                               :steps [(step "Then" "c" :failed (exc "boom" "java.lang.RuntimeException"))]})
                    (scenario {:name "s" :feature "F" :status :skipped
                               :steps [(step "Given" "d" :skipped)]})])]
      (is (true? (xsd-valid? xml))))))

(deftest parse-back-round-trips
  (testing "AC7: output re-parses (attribute- and text-context escaping hold)"
    (let [xml (doc [(scenario {:name "has <angle> & \"quote\"" :feature "F & <G>"
                               :status :failed
                               :steps [(step "Then" "x > y & z" :failed (exc "a < b & \"c\"" "E"))]})])
          tree (parse xml)]
      (is (= :testsuites (:tag tree)))
      ;; the raw special chars survive as decoded text after parse-back
      (let [tc (->> (tree-seq map? :content tree)
                    (filter #(= :testcase (:tag %))) first)]
        (is (= "has <angle> & \"quote\"" (get-in tc [:attrs :name])))))))

;; -----------------------------------------------------------------------------
;; RED <=> NONZERO (AC3) across all statuses, both allow-pending? modes
;; -----------------------------------------------------------------------------

(deftest red-iff-nonzero
  (testing "pass => 0 red"
    (is (zero? (red-count (doc [(scenario {:name "p" :feature "F" :status :passed
                                           :steps [(step "Given" "a" :passed)]})])))))
  (testing "fail (invalid-return, no exception-class) => >=1 red (<failure>)"
    (let [xml (doc [(scenario {:name "f" :feature "F" :status :failed
                               :steps [(step "Then" "b" :failed {:type :step/invalid-return :message "bad"})]})])]
      (is (pos? (red-count xml)))
      (is (str/includes? xml "<failure"))))
  (testing "error (thrown, has exception-class) => >=1 red (<error>)"
    (let [xml (doc [(scenario {:name "e" :feature "F" :status :failed
                               :steps [(step "Then" "c" :failed (exc "boom" "java.lang.RuntimeException"))]})])]
      (is (pos? (red-count xml)))
      (is (str/includes? xml "<error"))))
  (testing "D2: pending is RED under strict (exit 1) — <failure type='pending'>"
    (let [xml (ju/build-document
               [(scenario {:name "pn" :feature "F" :status :pending
                           :steps [(step "When" "wait" :pending)]})]
               (assoc run-ctx :allow-pending? false))]
      (is (pos? (red-count xml)))
      (is (str/includes? xml "type=\"pending\""))))
  (testing "D2: pending is GREEN under allow-pending? (exit 0) — <skipped>"
    (let [xml (ju/build-document
               [(scenario {:name "pn" :feature "F" :status :pending
                           :steps [(step "When" "wait" :pending)]})]
               (assoc run-ctx :allow-pending? true))]
      (is (zero? (red-count xml)))
      (is (str/includes? xml "<skipped"))
      (is (true? (xsd-valid? xml))))))

;; -----------------------------------------------------------------------------
;; testcase identity (AC4)
;; -----------------------------------------------------------------------------

(deftest testcase-identity-stable
  (let [xml (doc [(scenario {:name "Login works" :feature "Auth" :status :passed :line 7
                             :steps [(step "Given" "a" :passed)]})])]
    (testing "name / classname / file / line present"
      (is (str/includes? xml "name=\"Login works\""))
      (is (str/includes? xml "classname=\"Auth\""))
      (is (str/includes? xml "file=\"features/x.feature\""))
      (is (str/includes? xml "line=\"7\"")))
    (testing "no :pickle/id UUID leaks into the document"
      (is (not (re-find #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" xml))))))

(deftest identical-outline-row-names-disambiguated
  (testing "AC4: identical names within a feature get stable ' (example N)'"
    (let [xml (doc [(scenario {:name "Row" :feature "F" :status :passed :steps [(step "Given" "a" :passed)]})
                    (scenario {:name "Row" :feature "F" :status :passed :steps [(step "Given" "a" :passed)]})
                    (scenario {:name "Unique" :feature "F" :status :passed :steps [(step "Given" "a" :passed)]})])]
      (is (str/includes? xml "name=\"Row (example 1)\""))
      (is (str/includes? xml "name=\"Row (example 2)\""))
      ;; the unique name is left alone
      (is (str/includes? xml "name=\"Unique\""))
      (is (not (str/includes? xml "name=\"Unique (example"))))))

;; -----------------------------------------------------------------------------
;; Sanitization (AC7) — control chars built via `char` to keep the source clean
;; -----------------------------------------------------------------------------

(deftest sanitization
  (testing "ANSI escapes stripped from messages and transcripts"
    (is (= "red" (ju/clean (str esc "[31m" "red" esc "[0m")))))
  (testing "XML-1.0-illegal control chars (BEL, NUL) removed; tab/LF/CR kept"
    (is (= "ab" (ju/clean (str "a" bel nul "b"))))
    (is (= "a\tb\nc\r" (ju/clean "a\tb\nc\r"))))
  (testing "]]> inside system-out is split so it cannot close CDATA early"
    (let [xml (doc [(scenario {:name "cd" :feature "F" :status :passed
                               :steps [(step "Given" "danger ]]> here" :passed)]})])]
      (is (not (str/includes? (subs xml (str/index-of xml "<system-out>")) "danger ]]> here")))
      (is (str/includes? xml "]]]]><![CDATA[>"))
      (is (true? (xsd-valid? xml)))))
  (testing "control chars in step text don't break the document"
    (let [xml (doc [(scenario {:name "ctl" :feature "F" :status :passed
                               :steps [(step "Given" (str "bell" bel "null" nul " ok") :passed)]})])]
      (is (true? (xsd-valid? xml)))
      (is (= :testsuites (:tag (parse xml))))))
  (testing "UTF-8 declared"
    (is (str/includes? (doc [(scenario {:name "u" :feature "F" :status :passed
                                        :steps [(step "Given" "a" :passed)]})])
                       "encoding=\"UTF-8\""))))

;; -----------------------------------------------------------------------------
;; edn-safe accumulator (AC10)
;; -----------------------------------------------------------------------------

(deftest envelopes-stay-edn-safe
  (testing "the scenario envelopes the reporter accumulates round-trip as EDN"
    (let [envs [(scenario {:name "p" :feature "F" :status :passed
                           :steps [(step "Given" "a" :passed)]})
                (scenario {:name "e" :feature "F" :status :failed
                           :steps [(step "Then" "c" :failed (exc "boom" "E"))]})]]
      (is (every? reporter/edn-safe? envs)))))

;; -----------------------------------------------------------------------------
;; Loud failure on write error (AC8, reporter invariant 4)
;; -----------------------------------------------------------------------------

(deftest write-failure-throws
  (testing "AC8: a file-write failure at run-end throws, not swallows"
    ;; A path whose parent is an existing FILE cannot be mkdir'd or written —
    ;; spit throws, and on-run-end must let it propagate.
    (let [tmp (java.io.File/createTempFile "sl-junit" ".notdir")
          bad-path (str (.getPath tmp) "/report.xml")
          r (ju/make-reporter {:path bad-path})]
      (reporter/on-run-start r run-ctx)
      (reporter/on-scenario-complete
       r (scenario {:name "p" :feature "F" :status :passed
                    :steps [(step "Given" "a" :passed)]}))
      (is (thrown? java.io.IOException (reporter/on-run-end r nil)))
      (.delete tmp))))

;; -----------------------------------------------------------------------------
;; MACRO GOLDEN (AC6) — provenance guard for the 0.6 macro rewrite
;; -----------------------------------------------------------------------------

(defn- macro-scenario
  "Build a scrubbed scenario envelope from REAL macro expansion of
   macro_happy.feature (via expand-pickle against auth.ini), assigning
   deterministic statuses with a failing expanded child at index 3. The
   provenance keys (:step/synthetic?, :step/macro role/key/index/call-site/
   step-count) come from the real expander — that is what this golden locks."
  []
  (let [src (slurp "test/fixtures/features/macro_happy.feature")
        {:keys [ast]} (api/parse-string src)
        ;; registry arg is unused by pickles but its fdef requires a map
        {:keys [pickles]} (pk/pickles ast {} "test/fixtures/features/macro_happy.feature")
        {:keys [registry]} (macros/load-registries ["test/fixtures/macros/auth.ini"])
        {:keys [pickle]} (macros/expand-pickle {:enabled? true} (first pickles) registry)
        steps (:pickle/steps pickle)
        ;; wrapper(rolled :failed), 5 children [pass pass FAIL skip skip], trailing skip
        statuses [:failed :passed :passed :failed :skipped :skipped :skipped]
        errs {3 (exc "button missing" "clojure.lang.ExceptionInfo")}
        srs (map-indexed (fn [i s]
                           (cond-> {:step s :status (nth statuses i) :duration-ms (double i)}
                             (errs i) (assoc :error (errs i))))
                         steps)]
    (reporter/scenario-envelope
     {:status :failed :duration-ms 12.0
      :plan {:plan/pickle pickle} :steps (vec srs)})))

(def ^:private macro-golden-file "test/fixtures/junit/macro-golden.xml")

(deftest macro-golden
  (let [xml (ju/build-document [(macro-scenario)]
                               (assoc run-ctx :project-root
                                      (System/getProperty "user.dir")))]
    (testing "authored-primary wrapper is a transcript line, NOT its own testcase"
      (is (= 1 (count (re-seq #"<testcase\b" xml))) "one testcase per scenario, macro-proof")
      (is (str/includes? xml "Given login as alice + [failed]")))
    (testing "each expanded child carries a '+ [key N/5]' marker"
      (doseq [n (range 1 6)]
        (is (str/includes? xml (str "+ [login as alice " n "/5]")))))
    (testing "child failure attributes BOTH call-site (key + feature line) AND child (index, text)"
      ;; apostrophes are literal inside double-quoted attributes (no &apos;)
      (is (str/includes? xml "via macro 'login as alice' called at feature line 3, step 3/5"))
      (is (str/includes? xml "I enter password")))
    (testing "wrapper-status quirk: the synthetic wrapper rolls up to its children's
              worst status and is EXCLUDED from being a testcase; the real failing
              leaf (not the wrapper) is what <error> names."
      (is (str/includes? xml "<error"))
      (is (not (str/includes? xml "message=\"login as alice +\""))))
    (testing "byte-locked golden (regenerate deliberately if the mapping changes)"
      (is (.exists (io/file macro-golden-file))
          "missing macro golden — regenerate with the ns comment recipe")
      (is (= (slurp macro-golden-file) xml)
          "macro XML drifted — if intentional, regenerate the golden and say so"))
    (testing "the golden validates against the XSD"
      (is (true? (xsd-valid? xml))))))

;; Regenerate the macro golden (run in a REPL after a deliberate mapping change):
;;   (spit "test/fixtures/junit/macro-golden.xml"
;;         (shiftlefter.runner.report.junit/build-document
;;           [(#'shiftlefter.runner.report.junit-test/macro-scenario)]
;;           (assoc @#'shiftlefter.runner.report.junit-test/run-ctx
;;                  :project-root (System/getProperty "user.dir"))))
