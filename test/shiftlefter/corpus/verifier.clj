(ns shiftlefter.corpus.verifier
  "Verifies a corpus run against its manifest (sl-vs9m D3).

   VERIFICATION IS VIA THE JUNIT FILE: the JUnit reporter (sl-40to) carries
   the per-testcase name/status/time the --edn summary deliberately does not
   (aggregate counts + failure identities only), and parses with in-core
   clojure.xml. The corpus thereby doubles as a standing at-scale exerciser
   of the JUnit reporter.

   Timing checks are FLOORS ONLY -- never upper bounds (upper bounds are how
   CI suites learn to flake).

   Every verify fn returns {:ok? bool :mismatches [...]}; tests assert
   (is (:ok? v) (pr-str (:mismatches v))) so failures name themselves."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.xml :as xml]))

;; -----------------------------------------------------------------------------
;; JUnit parsing (clojure.xml, in core)
;; -----------------------------------------------------------------------------

(defn parse-junit [file]
  (with-open [in (io/input-stream (str file))]
    (xml/parse in)))

(defn- suites [doc]
  (filterv #(= :testsuite (:tag %)) (:content doc)))

(defn testcases
  "Flatten a parsed JUnit document to one map per <testcase>."
  [doc]
  (vec (for [suite (suites doc)
             tc (:content suite)
             :when (= :testcase (:tag tc))]
         (let [attrs (:attrs tc)
               outcome (first (filter #(#{:failure :error :skipped} (:tag %))
                                      (:content tc)))]
           {:suite (get-in suite [:attrs :name])
            :name (:name attrs)
            :classname (:classname attrs)
            :file (:file attrs)
            :line (some-> (:line attrs) parse-long)
            :time-ms (some-> (:time attrs) Double/parseDouble (* 1000.0))
            :outcome (:tag outcome)
            :outcome-type (get-in outcome [:attrs :type])
            :outcome-message (get-in outcome [:attrs :message])}))))

;; -----------------------------------------------------------------------------
;; Manifest subsetting for tag-filtered runs (sl-i608)
;; -----------------------------------------------------------------------------

(defn- entry-selected?
  "Tag-filter semantics over a manifest entry's plain-string :tags:
   include is OR, exclude wins over include, empty/absent = unconstrained."
  [{:keys [include exclude]} entry]
  (let [tags (set (:tags entry))]
    (and (not (some tags exclude))
         (or (empty? include) (boolean (some tags include))))))

(defn filter-manifest
  "Subset the manifest's :scenarios by tag-filter rules. Deliberately an
   independent reimplementation over the manifest's inherited plain-string
   tags -- NOT the runner's disposition seam -- so a filtered corpus run
   cross-checks the seam against this oracle."
  [manifest rules]
  (update manifest :scenarios
          (fn [entries] (vec (filter #(entry-selected? rules %) entries)))))

;; -----------------------------------------------------------------------------
;; Manifest vs JUnit
;; -----------------------------------------------------------------------------

(defn- expected-outcome-mismatch
  "Nil when the testcase's outcome element matches the manifest expectation,
   else a mismatch map. The four corpus statuses map 1:1 onto the four
   distinct testcase shapes (allow-pending? true):
   passed -> no child; failed -> <failure type=step/invalid-return>;
   error -> <error type=clojure.lang.ExceptionInfo>; pending -> <skipped>."
  [{:keys [expected-status pending-step] :as _entry}
   {:keys [outcome outcome-type outcome-message] :as _tc}]
  (case expected-status
    :passed (when (some? outcome)
              {:expected :passed :actual outcome :type outcome-type})
    ;; the reporter renders (name :step/invalid-return) -- namespace stripped
    :failed (when-not (and (= :failure outcome)
                           (= "invalid-return" outcome-type))
              {:expected :failed :actual outcome :type outcome-type})
    :error (when-not (and (= :error outcome)
                          (= "clojure.lang.ExceptionInfo" outcome-type))
             {:expected :error :actual outcome :type outcome-type})
    :pending (when-not (and (= :skipped outcome)
                            (= (str "pending: " pending-step) outcome-message))
               {:expected :pending :actual outcome :message outcome-message})))

(defn- suite-count-mismatches
  "The testsuite-level tallies must be consistent with its own testcases."
  [doc]
  (vec (for [suite (suites doc)
             :let [attrs (:attrs suite)
                   tcs (filterv #(= :testcase (:tag %)) (:content suite))
                   tally (fn [tag]
                           (count (filter (fn [tc]
                                            (some #(= tag (:tag %)) (:content tc)))
                                          tcs)))
                   expected {:tests (count tcs)
                             :failures (tally :failure)
                             :errors (tally :error)
                             :skipped (tally :skipped)}
                   actual {:tests (some-> (:tests attrs) parse-long)
                           :failures (some-> (:failures attrs) parse-long)
                           :errors (some-> (:errors attrs) parse-long)
                           :skipped (some-> (:skipped attrs) parse-long)}]
             :when (not= expected actual)]
         {:mismatch :suite-counts :suite (:name attrs)
          :expected expected :actual actual})))

(defn verify-run
  "Compare a parsed JUnit document against the manifest: exact testcase set,
   per-testcase outcome, per-testcase duration at-or-above its floor, and
   suite-count consistency."
  [manifest doc]
  (let [tcs (testcases doc)
        by-name (into {} (map (juxt :name identity)) tcs)
        entries (:scenarios manifest)
        expected-names (set (map :name entries))
        actual-names (set (map :name tcs))
        mismatches
        (-> []
            (into (for [n (sort (remove actual-names expected-names))]
                    {:mismatch :missing-testcase :name n}))
            (into (for [n (sort (remove expected-names actual-names))]
                    {:mismatch :unexpected-testcase :name n}))
            (into (mapcat
                   (fn [{:keys [name feature-name expected-status
                                duration-floor-ms] :as entry}]
                     (when-let [tc (get by-name name)]
                       (cond-> []
                         (not= feature-name (:classname tc))
                         (conj {:mismatch :classname :name name
                                :expected feature-name :actual (:classname tc)})

                         (expected-outcome-mismatch entry tc)
                         (conj (assoc (expected-outcome-mismatch entry tc)
                                      :mismatch :outcome :name name
                                      :expected-status expected-status))

                         (< (:time-ms tc) duration-floor-ms)
                         (conj {:mismatch :duration-below-floor :name name
                                :floor-ms duration-floor-ms
                                :time-ms (:time-ms tc)}))))
                   entries))
            (into (suite-count-mismatches doc)))]
    {:ok? (empty? mismatches) :mismatches mismatches}))

;; -----------------------------------------------------------------------------
;; Manifest vs --edn summary (aggregate counts + failure identities)
;; -----------------------------------------------------------------------------

(defn verify-edn
  "Cross-check the --edn summary: scenario counts derived from the manifest,
   and failure identities (scenario name + failing step text) exactly."
  [manifest summary]
  (let [entries (:scenarios manifest)
        by-status (frequencies (map :expected-status entries))
        expected-counts {:passed (get by-status :passed 0)
                         ;; scenario status is :failed for both corpus
                         ;; fail (invalid return) and error (throw)
                         :failed (+ (get by-status :failed 0)
                                    (get by-status :error 0))
                         :pending (get by-status :pending 0)
                         :skipped 0
                         :scenarios (count entries)}
        actual-counts (select-keys (:counts summary)
                                   [:passed :failed :pending :skipped :scenarios])
        expected-failures (set (map (juxt :name :expected-fail-step)
                                    (filter #(#{:failed :error} (:expected-status %))
                                            entries)))
        actual-failures (set (map (juxt :scenario/name :step/text)
                                  (:failures summary)))
        mismatches
        (cond-> []
          (not= expected-counts actual-counts)
          (conj {:mismatch :edn-counts
                 :expected expected-counts :actual actual-counts})

          (not= expected-failures actual-failures)
          (conj {:mismatch :edn-failure-identities
                 :missing (sort (remove actual-failures expected-failures))
                 :unexpected (sort (remove expected-failures actual-failures))}))]
    {:ok? (empty? mismatches) :mismatches mismatches}))

;; -----------------------------------------------------------------------------
;; Serial hazard + positive control (D6)
;; -----------------------------------------------------------------------------

(defn- parse-marker-lines [file]
  (if-not (.exists (io/file file))
    ::missing
    (mapv (fn [line]
            (let [[which group scenario] (str/split line #" " 3)]
              {:which which :group group :scenario scenario}))
          (str/split-lines (slurp file)))))

(defn- non-interleaved-mismatches
  "Within a serial group's scratch file, every begin must be immediately
   followed by its matching end. Trivially green under today's serial
   execution; becomes the real guard under sl-q9wp parallelism."
  [group lines]
  (loop [ls lines mismatches []]
    (cond
      (empty? ls) mismatches
      (< (count ls) 2) (conj mismatches {:mismatch :dangling-marker :group group
                                         :line (first ls)})
      :else
      (let [[a b & more] ls]
        (if (and (= "begin" (:which a)) (= "end" (:which b))
                 (= (:scenario a) (:scenario b)))
          (recur more mismatches)
          (recur (rest ls)
                 (conj mismatches {:mismatch :interleaved-markers :group group
                                   :saw [a b]})))))))

(defn- pair-completeness-mismatches
  "Every scenario must have exactly one begin and one end marker.
   Interleaving is NOT checked -- for the ungrouped control set it is
   ALLOWED, and observing actual interleaving here is sl-q9wp's positive
   proof that parallelism happened (without it, a silently-serial q9wp
   passes everything)."
  [group lines expected-scenarios]
  (let [begins (frequencies (map :scenario (filter #(= "begin" (:which %)) lines)))
        ends (frequencies (map :scenario (filter #(= "end" (:which %)) lines)))
        expected (into {} (map (fn [s] [s 1])) expected-scenarios)]
    (cond-> []
      (not= expected begins)
      (conj {:mismatch :begin-markers :group group
             :expected expected :actual begins})
      (not= expected ends)
      (conj {:mismatch :end-markers :group group
             :expected expected :actual ends}))))

(defn verify-control-interleaved
  "sl-q9wp's POSITIVE control: the ungrouped control scenarios' scratch file
   must show at least one observed interleaving (some begin lands between
   another scenario's begin and end). A silently-serial implementation runs
   the control set one at a time, leaves the file perfectly paired, and
   FAILS this check. Only meaningful for a run at :max-parallel > 1."
  [manifest]
  (let [file (get-in manifest [:scratch-files "control"])
        lines (parse-marker-lines file)]
    (cond
      (= ::missing lines)
      {:ok? false :mismatches [{:mismatch :scratch-file-missing
                                :group "control" :file file}]}

      ;; Perfectly begin/end-paired = zero observed concurrency.
      (empty? (non-interleaved-mismatches "control" lines))
      {:ok? false :mismatches [{:mismatch :no-interleaving-observed
                                :group "control" :file file}]}

      :else {:ok? true :mismatches []})))

(defn verify-serial-hazard
  "Check every scratch file recorded in the manifest: serial groups must be
   non-interleaved AND pair-complete; the control file pair-complete only."
  [manifest]
  (let [entries (:scenarios manifest)
        scenarios-of (fn [group]
                       (map :name (filter #(= group (:serial-group %)) entries)))
        control-scenarios (map :name
                               (filter #(and (nil? (:serial-group %))
                                             (str/includes? (:name %) " control "))
                                       entries))
        mismatches
        (vec (mapcat
              (fn [[group file]]
                (let [lines (parse-marker-lines file)
                      control? (= "control" group)]
                  (if (= ::missing lines)
                    [{:mismatch :scratch-file-missing :group group :file file}]
                    (concat
                     (when-not control?
                       (non-interleaved-mismatches group lines))
                     (pair-completeness-mismatches
                      group lines
                      (if control? control-scenarios (scenarios-of group)))))))
              (:scratch-files manifest)))]
    {:ok? (empty? mismatches) :mismatches mismatches}))
