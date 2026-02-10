(ns shiftlefter.spec-health-test
  "Tests that all shiftlefter specs can generate sample data.

   This catches:
   - Dangling spec references (specs that reference undefined specs)
   - Specs that need custom generators but don't have them
   - Circular dependencies that prevent generation

   If this test fails, the error message will identify exactly which
   specs are broken, making it easy to fix."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            ;; Require all namespaces that define specs to ensure they're loaded
            [shiftlefter.gherkin.api]
            [shiftlefter.gherkin.compliance]
            [shiftlefter.gherkin.dialect]
            [shiftlefter.gherkin.io]
            [shiftlefter.gherkin.lexer]
            [shiftlefter.gherkin.location]
            [shiftlefter.gherkin.parser]
            [shiftlefter.gherkin.pickler]
            [shiftlefter.gherkin.printer]
            [shiftlefter.gherkin.tokens]))

(defn shiftlefter-specs
  "Returns all spec keywords in :shiftlefter.* namespaces, sorted."
  []
  (->> (s/registry)
       keys
       (filter #(str/starts-with? (str %) ":shiftlefter"))
       sort
       vec))

(defn try-generate
  "Attempts to generate one sample from the given spec.
   Returns {:spec spec :status :ok} on success,
   or {:spec spec :status :error :message msg} on failure."
  [spec]
  (try
    (gen/generate (s/gen spec))
    {:spec spec :status :ok}
    (catch Exception e
      {:spec spec :status :error :message (.getMessage e)})))

(defn check-all-specs
  "Checks that all shiftlefter specs can generate.
   Returns {:ok count} if all pass,
   or {:failed count :specs [...]} listing broken specs."
  []
  (let [specs (shiftlefter-specs)
        results (map try-generate specs)
        failures (filter #(= :error (:status %)) results)]
    (if (seq failures)
      {:failed (count failures)
       :total (count specs)
       :specs (mapv (fn [{:keys [spec message]}]
                      {:spec spec :message message})
                    failures)}
      {:ok (count specs)})))

(deftest all-specs-can-generate
  (testing "Every :shiftlefter.* spec should be able to generate sample data"
    (let [result (check-all-specs)]
      (when (:failed result)
        (println "\n=== BROKEN SPECS ===")
        (doseq [{:keys [spec message]} (:specs result)]
          (println (str "  " spec))
          (println (str "    -> " message)))
        (println "====================\n"))
      (is (nil? (:failed result))
          (str (:failed result) " of " (:total result) " specs cannot generate. "
               "Broken specs: " (mapv :spec (:specs result)))))))

(deftest spec-count-sanity-check
  (testing "Should have a reasonable number of shiftlefter specs"
    (let [specs (shiftlefter-specs)
          count (count specs)]
      ;; We expect at least 80 specs based on current codebase
      ;; This catches accidental spec deletions or loading issues
      (is (>= count 80)
          (str "Expected at least 80 specs, but found only " count
               ". This may indicate specs failed to load.")))))
