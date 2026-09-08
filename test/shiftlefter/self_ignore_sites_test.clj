(ns shiftlefter.self-ignore-sites-test
  "sl-6vz8 (Warden W29): the F9 creation-site self-ignore, pinned at every
   Clojure site that creates `.shiftlefter/` (sl-7dq5 F9). Only the
   teardown-marker site was pinned (core_setup_test); the other four were
   verified sound by hand. One parameterized test: each site is driven at
   its REAL default path inside a scratch git repo, then the dir must carry
   the self-ignoring `.gitignore` (`*`) and nothing under `.shiftlefter/`
   may reach `git status --porcelain`. The fifth site — bin/sl's
   spawn_daemon, the shell twin — is pinned in warm_e2e_test (^:daemon).

   Sites:
   - shiftlefter.daemon write-edn! (daemon.edn's parent) — via the in-JVM
     daemon harness, with the throwaway jar OUTSIDE the scratch repo so the
     jar itself cannot dirty porcelain.
   - shiftlefter.costume.wardrobe ensure-dirs! — the wardrobe root's parent,
     resolved through *project-context*.
   - shiftlefter.graph init-db! — the DEFAULT path branch only (an override
     :path is the caller's dir to ignore), reached by redefining the default
     onto the scratch repo.
   - shiftlefter.sieve.store — writes under the default root, reached the
     same way."
  (:require [babashka.fs :as fs]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.costume.wardrobe :as wardrobe]
            [shiftlefter.daemon-test :as daemon-test]
            [shiftlefter.graph :as graph]
            [shiftlefter.sieve.store :as store]))

(defn- git-present? []
  (try (zero? (:exit (sh "git" "--version")))
       (catch java.io.IOException _ false)))

(defn- scratch-repo!
  "A fresh temp dir initialized as a git repo. Returns its path string."
  []
  (let [dir (str (fs/create-temp-dir {:prefix "sl-self-ignore-"}))]
    (when-not (zero? (:exit (sh "git" "init" "-q" :dir dir)))
      (throw (ex-info "git init failed" {:dir dir})))
    dir))

(defn- assert-self-ignored!
  "The two F9 facts for `repo`: the ignore file is exactly `*`, and porcelain
   names nothing under .shiftlefter/."
  [site repo]
  (let [gi (fs/path repo ".shiftlefter" ".gitignore")]
    (is (fs/exists? gi) (str site ": .shiftlefter/.gitignore was created"))
    (when (fs/exists? gi)
      (is (= "*\n" (slurp (str gi))) (str site ": the ignore file is `*`"))))
  (let [porcelain (:out (sh "git" "status" "--porcelain" :dir repo))]
    (is (not (str/includes? porcelain ".shiftlefter"))
        (str site ": nothing under .shiftlefter/ may reach git; porcelain:\n"
             porcelain))))

(def ^:private sites
  "site-name -> (fn [repo]) that drives the creator at its default path."
  {"daemon write-edn!"
   (fn [repo]
     (let [jar (str (fs/path (fs/create-temp-dir {:prefix "sl-jar-"}) "fake.jar"))]
       (spit jar "x")
       ;; The harness asserts daemon.edn exists before calling f — the
       ;; creation has happened by then; assert while the daemon is up.
       (daemon-test/call-with-daemon {:root repo :jar-path jar}
         (fn [_] (assert-self-ignored! "daemon write-edn!" repo)))))

   "wardrobe ensure-dirs!"
   (fn [repo]
     (binding [wardrobe/*project-context* {:project-root repo}]
       (wardrobe/ensure-dirs! :probe))
     (assert-self-ignored! "wardrobe ensure-dirs!" repo))

   "graph init-db! (default path)"
   (fn [repo]
     (let [path (str (fs/path repo ".shiftlefter" "graph"))]
       (with-redefs [graph/default-db-path path]
         (try (graph/init-db!)
              (finally (graph/reset-db! {:path path}))))
       (assert-self-ignored! "graph init-db!" repo)))

   "sieve store write (default root)"
   (fn [repo]
     (with-redefs [store/default-root (str (fs/path repo ".shiftlefter" "sieve"))]
       (#'store/write-edn! (store/artifact-path (store/root-path {}) :probe "x")
                           {:probe true}))
     (assert-self-ignored! "sieve store" repo))})

(deftest every-shiftlefter-creation-site-self-ignores
  (if-not (git-present?)
    (is true "skipped — git unavailable")
    (doseq [[site drive!] sites]
      (testing site
        (let [repo (scratch-repo!)]
          (try
            (drive! repo)
            (finally (fs/delete-tree repo))))))))
