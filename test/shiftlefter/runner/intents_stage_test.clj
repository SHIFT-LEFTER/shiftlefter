(ns shiftlefter.runner.intents-stage-test
  "The runner's Stage 1b — loading the intents glossary into intent-state from
   `:glossaries {:intents …}` in config, resolved relative to the config dir.

   Before this stage existed, `get-glossary-config` had no production caller and
   `intent-state` defaulted to a hardcoded CWD-relative `glossary/intents`; a
   project's own intents never reached the browser step path. (sl-1ps)"
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.intent.loader :as loader]
            [shiftlefter.intent.state :as intent-state]
            [shiftlefter.runner.core :as core]))

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
          [status result] (load-intents-stage "rid" (assoc (opts-for dir) :edn true)
                                              {:glossaries {:intents "intents"}})]
      (is (= :exit status))
      (is (= :planning-failed (:status result)))
      (is (= 2 (:exit-code result))))))
