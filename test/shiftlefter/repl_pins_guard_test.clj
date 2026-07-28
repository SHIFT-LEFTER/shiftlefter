(ns shiftlefter.repl-pins-guard-test
  "Drift guard (sl-vkhk): the nrepl + cider-nrepl pins live in TWO places
   that cannot share code — deps.edn :deps and the hand-built bash -Sdeps
   string in bin/sl (clj mode pulls them as deps so clj's classloader sees
   them, sl-ddi6). A bump touching only one ships a version-skewed
   `sl repl --clj` silently; this test makes that a red suite instead.
   Same family as the sl-8bxt twin-regex guard.

   The parse+compare is a pure function of the two file texts, so the
   guard's own sensitivity is tested (Tower rider on sl-vkhk): a doctored
   skew must be reported, and a mutilated -Sdeps line must surface as a
   loud extraction failure — never a silent vacuous pass."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(def ^:private pinned-libs ['nrepl/nrepl 'cider/cider-nrepl])

(defn- sdeps-pin
  "Extract `lib`'s :mvn/version from bin/sl's -Sdeps line. The file text
   carries bash-escaped quotes (literal backslash-quote around the version),
   so the pattern matches {:mvn/version \\\"X\\\"}. Nil when absent."
  [sl-text lib]
  (let [pattern (re-pattern (str (java.util.regex.Pattern/quote (str lib))
                                 " \\{:mvn/version \\\\\"([^\"\\\\]+)\\\\\""))]
    (second (re-find pattern sl-text))))

(defn- pin-report
  "Pure: per-lib {:lib :deps-edn :bin-sl} version entries from the two file
   texts. Extraction failure = nil value, asserted loudly by the tests."
  [deps-edn-text sl-text]
  (let [deps (edn/read-string deps-edn-text)]
    (mapv (fn [lib]
            {:lib lib
             :deps-edn (get-in deps [:deps lib :mvn/version])
             :bin-sl (sdeps-pin sl-text lib)})
          pinned-libs)))

(deftest live-pins-agree
  (let [report (pin-report (slurp "deps.edn") (slurp "bin/sl"))]
    (doseq [{:keys [lib deps-edn bin-sl]} report]
      (testing (str lib " — extraction succeeded (anti-vacuous: a guard that
                stops matching must fail, not pass)")
        (is (some? deps-edn) (str lib " pin not found in deps.edn :deps"))
        (is (some? bin-sl) (str lib " pin not found in bin/sl's -Sdeps"
                                " string — if the line moved or its quoting"
                                " changed, update sdeps-pin (sl-vkhk)")))
      (testing (str lib " — the two pins match")
        (is (= deps-edn bin-sl)
            (str lib " drifted: deps.edn pins " deps-edn
                 " but bin/sl's -Sdeps string pins " bin-sl
                 " — bump both together (sl-vkhk)"))))))

;; -----------------------------------------------------------------------------
;; The guard's own sensitivity (pure, synthetic inputs — Tower rider)
;; -----------------------------------------------------------------------------

(def ^:private synthetic-deps
  "{:deps {nrepl/nrepl {:mvn/version \"1.7.0\"}
           cider/cider-nrepl {:mvn/version \"0.62.2\"}}}")

(def ^:private synthetic-sl-skewed
  ;; bin/sl-shaped line with a skewed nrepl pin (file text carries \" pairs)
  "sl_dep=\"$sl_dep nrepl/nrepl {:mvn/version \\\"1.6.0\\\"} cider/cider-nrepl {:mvn/version \\\"0.62.2\\\"}\"")

(deftest guard-detects-skew
  (testing "a version skewed in only one place is reported as a mismatch"
    (let [report (pin-report synthetic-deps synthetic-sl-skewed)
          by-lib (into {} (map (juxt :lib identity)) report)]
      (is (= "1.7.0" (get-in by-lib ['nrepl/nrepl :deps-edn])))
      (is (= "1.6.0" (get-in by-lib ['nrepl/nrepl :bin-sl])))
      (is (not= (get-in by-lib ['nrepl/nrepl :deps-edn])
                (get-in by-lib ['nrepl/nrepl :bin-sl])))
      (is (= "0.62.2"
             (get-in by-lib ['cider/cider-nrepl :deps-edn])
             (get-in by-lib ['cider/cider-nrepl :bin-sl]))
          "the un-skewed twin still matches"))))

(deftest guard-fails-loudly-on-mutilation
  (testing "an -Sdeps line the extractor can't parse yields nil, not garbage
            (the live test's some? assertions then fail loudly)"
    (let [report (pin-report synthetic-deps
                             "sl_dep=\"$sl_dep something-else-entirely\"")]
      (is (every? #(nil? (:bin-sl %)) report))
      (is (every? #(some? (:deps-edn %)) report)
          "deps.edn side still extracts — the failure is attributed to bin/sl"))))
