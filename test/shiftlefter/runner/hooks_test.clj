(ns shiftlefter.runner.hooks-test
  "Unit tests for the hooks.clj convention loader (sl-esq)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.gherkin.api :as api]
            [shiftlefter.gherkin.pickler :as pk]
            [shiftlefter.runner.hooks :as hooks]
            [shiftlefter.runner.tag-disposition :as tagd]))

;; -----------------------------------------------------------------------------
;; Temp-dir helpers (same idiom as setup_test.clj)
;; -----------------------------------------------------------------------------

(defn- with-temp-dir [f]
  (let [dir (str (fs/create-temp-dir {:prefix "sl-hooks-test-"}))]
    (try
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(defn- spit-file
  "Write `content` to `dir/relative`. Returns the realpath-normalized
   absolute path."
  [dir relative content]
  (let [target (fs/path dir relative)]
    (fs/create-dirs (fs/parent target))
    (spit (str target) content)
    (str (fs/real-path target))))

;; -----------------------------------------------------------------------------
;; find-hooks-file
;; -----------------------------------------------------------------------------

(deftest find-hooks-file-resolves-sibling
  (testing "Returns sibling hooks.clj when present"
    (with-temp-dir
      (fn [dir]
        (let [config (spit-file dir "shiftlefter.edn" "{}")
              hooks  (spit-file dir "hooks.clj" "(ns hooks)\n(def hooks [])")]
          (is (= (str (fs/absolutize hooks))
                 (hooks/find-hooks-file config))))))))

(deftest find-hooks-file-returns-nil-when-absent
  (testing "MISSING file = no hooks, silently fine"
    (with-temp-dir
      (fn [dir]
        (let [config (spit-file dir "shiftlefter.edn" "{}")]
          (is (nil? (hooks/find-hooks-file config))))))))

(deftest find-hooks-file-nil-when-no-config-path
  (testing "Returns nil when config-path is nil (built-in defaults)"
    (is (nil? (hooks/find-hooks-file nil)))))

(deftest find-hooks-file-accepts-context-map
  (testing "Accepts a project-context-shaped map carrying :config-path"
    (with-temp-dir
      (fn [dir]
        (let [config (spit-file dir "shiftlefter.edn" "{}")
              hooks  (spit-file dir "hooks.clj" "(ns hooks)\n(def hooks [])")]
          (is (= (str (fs/absolutize hooks))
                 (hooks/find-hooks-file {:config-path config}))))))))

;; -----------------------------------------------------------------------------
;; load-hooks
;; -----------------------------------------------------------------------------

(deftest load-hooks-happy-path-var
  (testing "Loads a hooks.clj where `hooks` is a vector var; order preserved"
    (with-temp-dir
      (fn [dir]
        (let [path (spit-file dir "hooks.clj"
                              "(ns hooks)
                               (def hooks
                                 [{:name \"reset-db\"
                                   :before (fn [_] {:seed/ok true})
                                   :global? true
                                   :requires-serial true}
                                  {:name \"screenshot\"
                                   :after (fn [_] nil)}])")
              {:keys [ok error]} (hooks/load-hooks path {})]
          (is (nil? error))
          (is (vector? ok))
          (is (= ["reset-db" "screenshot"] (mapv :name ok)))
          (is (true? (-> ok first :global?)))
          (is (true? (-> ok first :requires-serial)))
          (is (ifn? (-> ok first :before)))
          (is (ifn? (-> ok second :after))))))))

(deftest load-hooks-happy-path-fn
  (testing "Loads a hooks.clj where `hooks` is a (fn [config])"
    (with-temp-dir
      (fn [dir]
        (let [path (spit-file dir "hooks.clj"
                              "(ns hooks)
                               (defn hooks [config]
                                 [{:name (str \"prefix-\" (:suffix config))
                                   :before (fn [_] nil)}])")
              {:keys [ok error]} (hooks/load-hooks path {:suffix "x"})]
          (is (nil? error))
          (is (= "prefix-x" (-> ok first :name))))))))

(deftest load-hooks-empty-vector-is-valid
  (testing "An empty registry is valid — no hooks, no error"
    (with-temp-dir
      (fn [dir]
        (let [path (spit-file dir "hooks.clj" "(ns hooks)\n(def hooks [])")
              {:keys [ok error]} (hooks/load-hooks path {})]
          (is (nil? error))
          (is (= [] ok)))))))

(deftest load-hooks-rejects-missing-hooks-var
  (testing "Hooks file without `hooks` produces a structured error"
    (with-temp-dir
      (fn [dir]
        (let [path (spit-file dir "hooks.clj" "(ns hooks)\n(def something-else 1)")
              {:keys [error]} (hooks/load-hooks path {})]
          (is (some? error))
          (is (= :hooks/load-failed (:type error)))
          (is (= path (:path error))))))))

(deftest load-hooks-rejects-malformed-entries
  (testing "Spec failure on bad shape returns :hooks/invalid-shape"
    (with-temp-dir
      (fn [dir]
        (let [path (spit-file dir "hooks.clj"
                              "(ns hooks)\n(def hooks [{:before (fn [_] nil)}])")
              {:keys [error]} (hooks/load-hooks path {})]
          (is (= :hooks/invalid-shape (:type error)))
          (is (some? (:explain error)))
          (is (= path (:path error)))))))
  (testing "A non-vector registry is malformed (order is the contract)"
    (with-temp-dir
      (fn [dir]
        (let [path (spit-file dir "hooks.clj"
                              "(ns hooks)\n(def hooks {:name \"a\"})")
              {:keys [error]} (hooks/load-hooks path {})]
          (is (= :hooks/invalid-shape (:type error))))))))

(deftest load-hooks-rejects-duplicate-names
  (testing "Duplicate :name = planning error naming the duplicates"
    (with-temp-dir
      (fn [dir]
        (let [path (spit-file dir "hooks.clj"
                              "(ns hooks)
                               (def hooks
                                 [{:name \"reset-db\" :before (fn [_] nil)}
                                  {:name \"other\" :after (fn [_] nil)}
                                  {:name \"reset-db\" :after (fn [_] nil)}])")
              {:keys [error]} (hooks/load-hooks path {})]
          (is (= :hooks/duplicate-name (:type error)))
          (is (= ["reset-db"] (:names error)))
          (is (str/includes? (:message error) "reset-db")))))))

(deftest load-hooks-syntax-error-surfaces
  (testing "Syntax error in hooks.clj surfaces as :hooks/load-failed"
    (with-temp-dir
      (fn [dir]
        (let [path (spit-file dir "hooks.clj" "(ns hooks)\n(def hooks [{:name")
              {:keys [error]} (hooks/load-hooks path {})]
          (is (= :hooks/load-failed (:type error))))))))

;; -----------------------------------------------------------------------------
;; attach-hooks — resolution, ordering, dedupe, planning errors
;; -----------------------------------------------------------------------------

(def ^:private noop (fn [_] nil))

(defn- plan-with-tags [& tag-names]
  {:plan/pickle {:pickle/source-file "features/x.feature"
                 :pickle/tags (mapv (fn [n] {:name n :location {:line 2 :column 3}})
                                    tag-names)}})

(deftest attach-hooks-tagged-order-and-attribution
  (let [registry [{:name "a" :before noop} {:name "b" :after noop}]
        plan (plan-with-tags "@hook=a" "@smoke" "@hook=b")
        {:keys [ok error]} (hooks/attach-hooks [plan] registry "/proj/hooks.clj")]
    (is (nil? error))
    (let [attached (:plan/hooks (first ok))]
      (is (= ["a" "b"] (mapv :name attached)) "pickle-tag order")
      (is (every? #(= {:path "/proj/hooks.clj"} (:registration %)) attached))
      (is (= {:file "features/x.feature" :line 2} (:tag-source (first attached)))
          "the @hook= tag's file:line rides along for attribution")
      (is (ifn? (:before (first attached)))))))

(deftest attach-hooks-globals-first-in-registry-order
  (let [registry [{:name "g2" :global? true :before noop}
                  {:name "named" :before noop}
                  {:name "g1" :global? true :after noop}]
        tagged (plan-with-tags "@hook=named")
        bare (plan-with-tags "@smoke")
        {:keys [ok]} (hooks/attach-hooks [tagged bare] registry "/proj/hooks.clj")]
    (is (= ["g2" "g1" "named"] (mapv :name (:plan/hooks (first ok))))
        "globals outermost in REGISTRY VECTOR order, then tagged")
    (is (= ["g2" "g1"] (mapv :name (:plan/hooks (second ok))))
        "globals apply to every scenario, visibly")
    (is (nil? (:tag-source (first (:plan/hooks (first ok)))))
        "no tag names a global — no tag-source")))

(deftest attach-hooks-dedupes-global-also-tagged
  (testing "a hook both :global? and named via @hook= runs ONCE, at the
            outermost (global) position"
    (let [registry [{:name "g" :global? true :before noop}]
          plan (plan-with-tags "@hook=g")
          {:keys [ok]} (hooks/attach-hooks [plan] registry "/proj/hooks.clj")]
      (is (= ["g"] (mapv :name (:plan/hooks (first ok)))))
      (is (nil? (:tag-source (first (:plan/hooks (first ok)))))
          "global occurrence wins — first-occurrence-wins dedupe"))))

(deftest attach-hooks-leaves-hookless-plans-untouched
  (let [plan (plan-with-tags "@smoke")]
    (testing "no registry, no tags — plan IDENTICAL (byte-identity guard)"
      (is (= [plan] (:ok (hooks/attach-hooks [plan] nil nil)))))
    (testing "registry with no globals, plan with no @hook= — untouched"
      (is (= [plan]
             (:ok (hooks/attach-hooks [plan] [{:name "a" :before noop}]
                                      "/proj/hooks.clj")))))))

(deftest attach-hooks-unknown-name-is-planning-error
  (testing "unknown @hook= name errors, naming the tag and its file:line"
    (let [registry [{:name "real" :before noop}]
          plan (plan-with-tags "@hook=typo")
          {:keys [error]} (hooks/attach-hooks [plan] registry "/proj/hooks.clj")]
      (is (= :hooks/unknown-name (:type error)))
      (is (str/includes? (:message error) "@hook=typo at features/x.feature:2"))
      (is (str/includes? (:message error) "known hooks"))
      (is (str/includes? (:message error) "real"))))
  (testing "@hook= with NO hooks.clj at all fails loudly, not silently"
    (let [{:keys [error]} (hooks/attach-hooks [(plan-with-tags "@hook=x")] nil nil)]
      (is (= :hooks/unknown-name (:type error)))
      (is (str/includes? (:message error) "no hooks.clj found"))))
  (testing "identical unknowns dedupe (outline rows share one tag)"
    (let [p1 (plan-with-tags "@hook=typo")
          p2 (plan-with-tags "@hook=typo")
          {:keys [error]} (hooks/attach-hooks [p1 p2] [] "/proj/hooks.clj")]
      (is (= 1 (count (:unknown error)))))))

;; -----------------------------------------------------------------------------
;; AC3 ordering — through the REAL pickler (feature -> rule -> scenario ->
;; examples nesting IS pickle-tag order by pickler construction)
;; -----------------------------------------------------------------------------

(defn- pickles-of [src]
  (let [{:keys [ast]} (api/parse-string src)]
    (:pickles (pk/pickles ast {} "features/t.feature"))))

(defn- facet-names [pickle]
  (mapv :name (:hooks (tagd/disposition nil pickle))))

(deftest pickler-order-feature-rule-scenario-stacking
  (let [src (str "@hook=f\n"
                 "Feature: F\n"
                 "  @hook=r\n"
                 "  Rule: R\n"
                 "    @hook=s @hook=s2\n"
                 "    Scenario: S\n"
                 "      Given a step\n")
        [pickle] (pickles-of src)]
    (is (= ["f" "r" "s" "s2"] (facet-names pickle))
        "outermost-first: feature -> rule -> scenario; within one tag line, left-to-right")))

(deftest pickler-order-outline-examples-blocks-differ
  (let [src (str "Feature: F\n"
                 "  Scenario Outline: O\n"
                 "    Given step <x>\n"
                 "    @hook=block-one\n"
                 "    Examples: one\n"
                 "      | x |\n"
                 "      | 1 |\n"
                 "    @hook=block-two\n"
                 "    Examples: two\n"
                 "      | x |\n"
                 "      | 2 |\n")
        [p1 p2] (pickles-of src)]
    (is (= ["block-one"] (facet-names p1)))
    (is (= ["block-two"] (facet-names p2))
        "two differently-tagged Examples blocks yield pickles with different hooks")))

(deftest pickler-dedupe-same-hook-two-levels
  (let [src (str "@hook=a\n"
                 "Feature: F\n"
                 "  @hook=a @hook=b\n"
                 "  Scenario: S\n"
                 "    Given a step\n")
        [pickle] (pickles-of src)]
    (is (= ["a" "b"] (facet-names pickle))
        "same @hook= at two levels runs ONCE at the outermost position
         (pickler dedupe keys on the full token name)"))
  (testing "plain same-name tag at two levels also collapses (dedupe-tags on :name)"
    (let [src "@smoke\nFeature: F\n  @smoke\n  Scenario: S\n    Given a step\n"
          [pickle] (pickles-of src)]
      (is (= 1 (count (filter #(= "@smoke" (:name %)) (:pickle/tags pickle))))))))
