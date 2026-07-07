(ns shiftlefter.costume.wardrobe-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.shell :as shell]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [shiftlefter.costume.wardrobe :as wardrobe]
            [babashka.fs :as fs]))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(def ^:dynamic *test-wardrobe* nil)

(defn with-temp-wardrobe
  "Fixture that redirects the wardrobe root to a temp directory outside the repo.

   The temp dir is untracked and outside the repo, so the git-tracked guard
   never trips and nothing is written into the repo's real `.shiftlefter/`."
  [f]
  (let [temp-dir (str (fs/create-temp-dir {:prefix "sl-wardrobe-test-"}))]
    (try
      (with-redefs [wardrobe/wardrobe-dir (constantly temp-dir)]
        (binding [*test-wardrobe* temp-dir]
          (f)))
      (finally
        (fs/delete-tree temp-dir)))))

(use-fixtures :each with-temp-wardrobe)

;; -----------------------------------------------------------------------------
;; Path Tests
;; -----------------------------------------------------------------------------

(deftest wardrobe-dir-default-test
  (testing "wardrobe-dir is the project-scoped, CWD-relative store"
    (with-redefs [wardrobe/wardrobe-dir (constantly ".shiftlefter/wardrobe")]
      (is (= ".shiftlefter/wardrobe" (wardrobe/wardrobe-dir)))
      (is (= ".shiftlefter/wardrobe/finance" (wardrobe/costume-dir :finance)))
      (is (= ".shiftlefter/wardrobe/finance/chrome-profile"
             (wardrobe/chrome-profile-dir :finance))))))

(deftest costume-dir-test
  (testing "costume-dir returns a path under the wardrobe root"
    (let [dir (wardrobe/costume-dir :finance)]
      (is (string? dir))
      (is (.endsWith dir "/finance"))))

  (testing "costume-dir accepts string names"
    (let [dir (wardrobe/costume-dir "work")]
      (is (.endsWith dir "/work")))))

(deftest chrome-profile-dir-test
  (testing "chrome-profile-dir returns the Chrome user-data-dir inside the costume"
    (let [dir (wardrobe/chrome-profile-dir :finance)]
      (is (string? dir))
      (is (.endsWith dir "/finance/chrome-profile")))))

;; -----------------------------------------------------------------------------
;; Directory Management Tests
;; -----------------------------------------------------------------------------

(deftest ensure-dirs-test
  (testing "ensure-dirs! creates directories"
    (let [dir (wardrobe/ensure-dirs! :test-costume)]
      (is (fs/exists? dir))
      (is (fs/exists? (wardrobe/chrome-profile-dir :test-costume)))
      (is (fs/directory? dir))))

  (testing "ensure-dirs! is idempotent"
    (wardrobe/ensure-dirs! :test-costume)
    (wardrobe/ensure-dirs! :test-costume)
    (is (fs/exists? (wardrobe/costume-dir :test-costume)))))

(deftest delete-costume-test
  (testing "delete-costume! removes directory"
    (wardrobe/ensure-dirs! :deleteme)
    (is (wardrobe/costume-exists? :deleteme))
    (is (true? (wardrobe/delete-costume! :deleteme)))
    (is (not (wardrobe/costume-exists? :deleteme))))

  (testing "delete-costume! returns false if not exists"
    (is (false? (wardrobe/delete-costume! :never-existed)))))

(deftest costume-exists-test
  (testing "costume-exists? returns false for missing"
    (is (not (wardrobe/costume-exists? :nonexistent))))

  (testing "costume-exists? returns true after ensure-dirs!"
    (wardrobe/ensure-dirs! :exists-test)
    (is (wardrobe/costume-exists? :exists-test))))

;; -----------------------------------------------------------------------------
;; Metadata Tests
;; -----------------------------------------------------------------------------

(def full-meta
  {:debug-port 9222
   :chrome-pid 12345
   :user-data-dir "/some/path"})

(deftest save-and-load-costume-meta-test
  (testing "save and load roundtrip"
    (wardrobe/save-costume-meta! :meta-test full-meta)
    (let [loaded (wardrobe/load-costume-meta :meta-test)]
      (is (= 9222 (:debug-port loaded)))
      (is (= 12345 (:chrome-pid loaded)))
      (is (string? (:created-at loaded)))
      (is (string? (:last-connected-at loaded)))))

  (testing "save adds timestamps"
    (wardrobe/save-costume-meta! :timestamp-test (assoc full-meta :debug-port 9999))
    (let [loaded (wardrobe/load-costume-meta :timestamp-test)]
      (is (some? (:created-at loaded)))
      (is (some? (:last-connected-at loaded)))))

  (testing "save preserves existing created-at"
    (wardrobe/save-costume-meta! :preserve-test (assoc full-meta :debug-port 1111))
    (let [first-load (wardrobe/load-costume-meta :preserve-test)
          created-at (:created-at first-load)]
      ;; Save again
      (Thread/sleep 10) ;; Ensure time passes
      (wardrobe/save-costume-meta! :preserve-test (assoc full-meta :debug-port 2222))
      (let [second-load (wardrobe/load-costume-meta :preserve-test)]
        (is (= created-at (:created-at second-load)))
        (is (= 2222 (:debug-port second-load)))))))

(deftest load-costume-meta-missing-test
  (testing "load returns nil for missing file"
    (is (nil? (wardrobe/load-costume-meta :never-saved)))))

(deftest clear-costume-meta-test
  (testing "clear-costume-meta! removes file"
    (wardrobe/save-costume-meta! :clear-test full-meta)
    (is (some? (wardrobe/load-costume-meta :clear-test)))
    (is (true? (wardrobe/clear-costume-meta! :clear-test)))
    (is (nil? (wardrobe/load-costume-meta :clear-test))))

  (testing "clear-costume-meta! returns false if not exists"
    (is (false? (wardrobe/clear-costume-meta! :never-existed)))))

;; -----------------------------------------------------------------------------
;; costume-meta Spec Tests (boundary)
;; -----------------------------------------------------------------------------

(deftest costume-meta-spec-test
  (testing "a valid costume-meta conforms"
    (is (s/valid? ::wardrobe/costume-meta full-meta)))

  (testing "a legacy meta carrying a stray :stealth key still conforms (extras ignored)"
    (is (s/valid? ::wardrobe/costume-meta (assoc full-meta :stealth true))))

  (testing "a meta missing required keys does not conform"
    (is (not (s/valid? ::wardrobe/costume-meta {:debug-port 9222})))))

(deftest save-costume-meta-validates-test
  (testing "save-costume-meta! throws a structured error on invalid meta"
    (let [ex (try
               (wardrobe/save-costume-meta! :bad {:debug-port 9222})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :costume/invalid-meta (:type (ex-data ex)))))))

(deftest legacy-stealth-meta-loads-test
  (testing "a previously-saved meta with a stray :stealth key still loads"
    ;; Simulate a legacy on-disk meta by saving one that carries :stealth.
    (wardrobe/save-costume-meta! :legacy (assoc full-meta :stealth true))
    (let [loaded (wardrobe/load-costume-meta :legacy)]
      (is (some? loaded))
      (is (= 9222 (:debug-port loaded)))
      (is (true? (:stealth loaded)) "extra key is preserved"))))

;; -----------------------------------------------------------------------------
;; Git Safety Rail Tests
;; -----------------------------------------------------------------------------

(deftest git-tracked-test
  (testing "git-tracked? is true for a tracked file"
    (is (true? (wardrobe/git-tracked? "src/shiftlefter/costume.clj"))))

  (testing "git-tracked? is false for an untracked / nonexistent path"
    (is (false? (wardrobe/git-tracked? (str *test-wardrobe* "/nope"))))))

(deftest ensure-gitignored-noop-when-covered-test
  (testing "ensure-gitignored! is a no-op when the wardrobe is already ignored"
    ;; This repo gitignores `.shiftlefter/`, which covers `.shiftlefter/wardrobe/`.
    ;; ensure-gitignored! must detect that and not touch .gitignore.
    (with-redefs [wardrobe/wardrobe-dir (constantly ".shiftlefter/wardrobe")]
      (is (nil? (wardrobe/ensure-gitignored!))))))

(deftest ensure-gitignored-append-and-idempotent-test
  (testing "ensure-gitignored! appends the pattern in a fresh git repo, idempotently"
    ;; Use the real relative wardrobe path and an isolated temp git repo as root,
    ;; so the append branch is exercised without depending on / touching the CWD.
    (with-redefs [wardrobe/wardrobe-dir (constantly ".shiftlefter/wardrobe")]
      (let [repo (str (fs/create-temp-dir {:prefix "sl-gitignore-test-"}))
            gitignore (str repo "/.gitignore")]
        (try
          (shell/sh "git" "init" :dir repo)
          ;; Fresh repo, no .gitignore yet → appends and returns the pattern.
          (is (= ".shiftlefter/wardrobe/" (wardrobe/ensure-gitignored! repo)))
          (is (fs/exists? gitignore))
          (is (re-find #"(?m)^\.shiftlefter/wardrobe/$" (slurp gitignore)))
          ;; Idempotent: pattern already listed → no-op, no duplicate line.
          (is (nil? (wardrobe/ensure-gitignored! repo)))
          (let [hits (->> (slurp gitignore)
                          str/split-lines
                          (filter #(= ".shiftlefter/wardrobe/" (str/trim %))))]
            (is (= 1 (count hits)) "pattern appears exactly once"))
          (finally
            (fs/delete-tree repo)))))))

;; -----------------------------------------------------------------------------
;; Listing Tests
;; -----------------------------------------------------------------------------

(deftest list-costumes-test
  (testing "list-costumes returns empty when none exist"
    (is (= [] (wardrobe/list-costumes))))

  (testing "list-costumes returns created costumes"
    (wardrobe/ensure-dirs! :alpha)
    (wardrobe/ensure-dirs! :beta)
    (wardrobe/ensure-dirs! :gamma)
    (let [costumes (wardrobe/list-costumes)]
      (is (= ["alpha" "beta" "gamma"] costumes)))))
