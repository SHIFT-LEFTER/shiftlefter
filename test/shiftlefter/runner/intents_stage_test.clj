(ns shiftlefter.runner.intents-stage-test
  "The runner's Stage 1b — loading the intents glossary into intent-state from
   `:glossaries {:intents …}` in config, resolved relative to the config dir.

   Before this stage existed, `get-glossary-config` had no production caller and
   `intent-state` defaulted to a hardcoded CWD-relative `glossary/intents`; a
   project's own intents never reached the browser step path. (sl-1ps)"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.intent.loader :as loader]
            [shiftlefter.intent.state :as intent-state]
            [shiftlefter.runner.core :as core]))

;; These tests deliberately drive the GLOBAL intent-state cache through
;; loaded/error/cleared states via load-intents-stage. Without cleanup, the
;; seed-ordered namespace that runs next inherits whichever state the last
;; test here left behind (a temp-dir glossary, or the loaded-with-:errors
;; state where get-intents returns nil) — the sl-tkrx suspect-(a) order
;; dependence (sl-s04n's bind_test blip was the victim side of this class).
;; Clear after every test so later namespaces start from the pristine
;; not-loaded baseline.
(use-fixtures :each
  (fn [f]
    (intent-state/clear-intents!)
    (try (f)
         (finally (intent-state/clear-intents!)))))

;; load-intents-stage is private; exercise it through its var.
(def ^:private load-intents-stage #'core/load-intents-stage)

(defn- temp-project
  "Create a temp project dir with an intents/ subdir containing `files`
   (map of filename → EDN string). Returns the project dir path string.
   The config would live at <dir>/shiftlefter.edn, so intents resolve to
   <dir>/intents relative to it."
  [files]
  (let [dir (str (fs/create-temp-dir))
        intents-dir (str (fs/path dir "intents"))]
    (fs/create-dirs intents-dir)
    (doseq [[fname content] files]
      (spit (str (fs/path intents-dir fname)) content))
    dir))

(defn- opts-for [dir]
  {:config-path (str (fs/path dir "shiftlefter.edn"))})

;; -----------------------------------------------------------------------------

(deftest absent-intents-key-clears-and-continues
  (testing "no :glossaries :intents → state cleared, stage continues"
    (intent-state/reload-intents! "test/does-not-exist") ;; seed some state
    (let [[status data] (load-intents-stage "rid" {} {})]
      (is (= :continue status))
      (is (nil? data))
      (is (false? (intent-state/intents-loaded?))
          "absent intents config clears state rather than leaving stale intents"))))

(deftest valid-intents-load-relative-to-config-dir
  (testing "a valid glossary loads and is resolved relative to the config dir"
    (let [dir (temp-project
               {"login.edn"
                "{:intent \"Login\" :elements {:submit {:bindings {:web {:css \"#submit\"}}}}}"})
          [status data] (load-intents-stage "rid" (opts-for dir)
                                            {:glossaries {:intents "intents"}})]
      (is (= :continue status))
      (is (nil? data))
      (is (true? (intent-state/intents-loaded?)))
      (is (= "Login" (loader/known-intent? (intent-state/get-intents) "Login"))
          "the project's own intent is now resolvable"))))

(deftest invalid-glossary-is-a-planning-failure
  (testing "§7.5 anchor violation (no :selector + no :root) → exit 2, planning-failed"
    (let [dir (temp-project
               {"dash.edn"
                "{:intent \"Dashboard\" :collections {:item {:intent \"Card\" :cardinality :many}}}"
                "card.edn"
                "{:intent \"Card\" :elements {:title {:bindings {:web {:css \".title\"}}}}}"})
          out (java.io.StringWriter.)
          [status result] (binding [*out* out]
                            (load-intents-stage "rid" (assoc (opts-for dir) :edn true)
                                                {:glossaries {:intents "intents"}}))]
      (is (= :exit status))
      (is (= :planning-failed (:status result)))
      (is (= 2 (:exit-code result)))
      (testing "the EDN summary carries the error with nested detail (sl-0n6t)"
        (let [summary (edn/read-string (str out))
              [err :as errs] (get-in summary [:planning :errors])]
          (is (= 1 (count errs)))
          (is (= :glossary/intents-load-failed (:type err)))
          (is (seq (:errors err))
              "per-entry sub-errors survive serialization")
          (is (every? #(string? (:message %)) (:errors err))))))))
