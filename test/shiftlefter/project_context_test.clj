(ns shiftlefter.project-context-test
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [shiftlefter.project-context :as project-context]))

(defn- temp-dir []
  (str (fs/create-temp-dir)))

(defn- npath [& parts]
  (let [p (apply fs/path parts)]
    (letfn [(nearest-existing [path missing]
              (if (or (nil? path) (fs/exists? path))
                [path missing]
                (recur (fs/parent path) (conj missing (fs/file-name path)))))]
      (try
        (if (fs/exists? p)
          (str (fs/real-path p))
          (let [[existing missing] (nearest-existing p '())
                base (if existing
                       (fs/real-path existing)
                       (.normalize (.toAbsolutePath (fs/path p))))]
            (str (reduce fs/path base missing))))
        (catch Exception _
          (str (.normalize (.toAbsolutePath (fs/path p)))))))))

(defn- touch [path]
  (fs/create-dirs (fs/parent path))
  (spit (str path) "{}")
  (str path))

(defn- with-temp-dir [f]
  (let [dir (temp-dir)]
    (try
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(defn- init-git! [dir]
  (let [result (shell/sh "git" "init" "-q" :dir dir)]
    (when-not (zero? (:exit result))
      (throw (ex-info "git init failed" result)))))

(deftest no-config-returns-defaults-context
  (with-temp-dir
    (fn [dir]
      (let [ctx (project-context/resolve {:invocation-root dir})]
        (is (= :defaults (:config-source ctx)))
        (is (= (npath dir) (:project-root ctx)))
        (is (= (npath dir) (:config-root ctx)))
        (is (nil? (:config-path ctx)))
        (is (= :defaults (:layout ctx)))
        (is (true? (:portable? ctx)))
        (is (= [] (:diagnostics ctx)))))))

(deftest discovers-root-config-from-descendant
  (with-temp-dir
    (fn [dir]
      (touch (fs/path dir "shiftlefter.edn"))
      (let [sub (str (fs/path dir "a" "b"))]
        (fs/create-dirs sub)
        (let [ctx (project-context/resolve {:invocation-root sub})]
          (is (= :discovered (:config-source ctx)))
          (is (= (npath dir) (:project-root ctx)))
          (is (= (npath dir) (:config-root ctx)))
          (is (= (npath dir "shiftlefter.edn") (:config-path ctx)))
          (is (= :root (:layout ctx))))))))

(deftest discovers-sl-directory-config-from-descendant
  (with-temp-dir
    (fn [dir]
      (touch (fs/path dir "sl" "shiftlefter.edn"))
      (let [sub (str (fs/path dir "a" "b"))]
        (fs/create-dirs sub)
        (let [ctx (project-context/resolve {:invocation-root sub})]
          (is (= :discovered (:config-source ctx)))
          (is (= (npath dir) (:project-root ctx)))
          (is (= (npath dir "sl") (:config-root ctx)))
          (is (= (npath dir "sl" "shiftlefter.edn") (:config-path ctx)))
          (is (= :sl-directory (:layout ctx))))))))

(deftest same-root-dual-configs-return-ambiguity-diagnostic
  (with-temp-dir
    (fn [dir]
      (touch (fs/path dir "shiftlefter.edn"))
      (touch (fs/path dir "sl" "shiftlefter.edn"))
      (let [ctx (project-context/resolve {:invocation-root dir})
            diag (first (:diagnostics ctx))]
        (is (= :error (:config-source ctx)))
        (is (= :ambiguous (:layout ctx)))
        (is (= :project-context/ambiguous-config (:type diag)))
        (is (= [(npath dir "shiftlefter.edn")
                (npath dir "sl" "shiftlefter.edn")]
               (:paths diag)))))))

(deftest nested-project-nearest-wins
  (with-temp-dir
    (fn [dir]
      (let [parent (str (fs/path dir "parent"))
            child (str (fs/path parent "apps" "shop"))
            invocation (str (fs/path child "features"))]
        (touch (fs/path parent "shiftlefter.edn"))
        (touch (fs/path child "sl" "shiftlefter.edn"))
        (fs/create-dirs invocation)
        (let [ctx (project-context/resolve {:invocation-root invocation})]
          (is (= (npath child) (:project-root ctx)))
          (is (= (npath child "sl") (:config-root ctx)))
          (is (= :sl-directory (:layout ctx)))
          (is (= [] (:diagnostics ctx))))))))

(deftest automatic-discovery-stops-at-git-root
  (with-temp-dir
    (fn [dir]
      (let [repo (str (fs/path dir "repo"))
            sub (str (fs/path repo "sub"))]
        (fs/create-dirs sub)
        (touch (fs/path dir "shiftlefter.edn"))
        (init-git! repo)
        (let [ctx (project-context/resolve {:invocation-root sub})]
          (is (= :defaults (:config-source ctx)))
          (is (= (npath sub) (:project-root ctx)))
          (is (= (npath repo) (:workspace-root ctx))))))))

(deftest explicit-config-can-live-outside-invocation-tree
  (with-temp-dir
    (fn [dir]
      (let [invocation (str (fs/path dir "work" "here"))
            external (str (fs/path dir "other" "sl" "alt.edn"))]
        (fs/create-dirs invocation)
        (touch external)
        (let [ctx (project-context/resolve {:invocation-root invocation
                                            :config-path "../../other/sl/alt.edn"})]
          (is (= :explicit (:config-source ctx)))
          (is (= (npath external) (:config-path ctx)))
          (is (= (npath dir "other") (:project-root ctx)))
          (is (= (npath dir "other" "sl") (:config-root ctx)))
          (is (= :sl-directory (:layout ctx))))))))

(deftest missing-explicit-config-returns-config-not-found-diagnostic
  (with-temp-dir
    (fn [dir]
      (let [missing (str (fs/path dir "missing.edn"))
            ctx (project-context/resolve {:invocation-root dir
                                          :config-path "missing.edn"})
            diag (first (:diagnostics ctx))]
        (is (= :explicit (:config-source ctx)))
        (is (= (npath missing) (:config-path ctx)))
        (is (= :config/not-found (:type diag)))
        (is (= (npath missing) (:path diag)))
        (is (false? (:portable? ctx)))))))

(deftest path-classes-resolve-against-their-roots
  (with-temp-dir
    (fn [dir]
      (touch (fs/path dir "sl" "shiftlefter.edn"))
      (let [invocation (str (fs/path dir "features"))
            _ (fs/create-dirs invocation)
            ctx (project-context/resolve {:invocation-root invocation})]
        (is (= (npath invocation "login.feature")
               (project-context/resolve-cli-path ctx "login.feature")))
        (is (= (npath dir "sl" "glossary" "subjects.edn")
               (project-context/resolve-config-path ctx "glossary/subjects.edn")))
        (is (= (npath dir ".shiftlefter" "wardrobe")
               (project-context/operational-path ctx ".shiftlefter" "wardrobe")))))))

;; instance-root — the daemon-anchor rule shared with bin/sl find_instance_root
;; (sl-v7l6). Wrapper and daemon must agree on where .shiftlefter/daemon.edn lives.

(deftest instance-root-uses-project-root-when-config-discovered
  (with-temp-dir
    (fn [dir]
      (touch (fs/path dir "shiftlefter.edn"))
      (let [sub (str (fs/path dir "a" "b"))]
        (fs/create-dirs sub)
        (is (= (npath dir)
               (project-context/instance-root
                (project-context/resolve {:invocation-root sub}))))))))

(deftest instance-root-prefers-sl-layout-config-over-git-toplevel
  (with-temp-dir
    (fn [dir]
      (let [repo (str (fs/path dir "repo"))
            sub (str (fs/path repo "proj"))]
        (fs/create-dirs sub)
        (init-git! repo)
        (touch (fs/path sub "sl" "shiftlefter.edn"))
        (is (= (npath sub)
               (project-context/instance-root
                (project-context/resolve {:invocation-root sub}))))))))

(deftest instance-root-anchors-configless-git-repo-at-toplevel
  (with-temp-dir
    (fn [dir]
      (let [repo (str (fs/path dir "repo"))
            sub (str (fs/path repo "sub"))]
        (fs/create-dirs sub)
        (init-git! repo)
        (is (= (npath repo)
               (project-context/instance-root
                (project-context/resolve {:invocation-root sub}))))))))

(deftest instance-root-nil-without-config-or-git
  (with-temp-dir
    (fn [dir]
      (is (nil? (project-context/instance-root
                 (project-context/resolve {:invocation-root dir})))))))

(deftest escaping-config-paths-remain-valid-with-warning
  (with-temp-dir
    (fn [dir]
      (let [project (str (fs/path dir "proj"))]
        (touch (fs/path project "sl" "shiftlefter.edn"))
        (let [ctx (project-context/resolve {:invocation-root project})
            outside (project-context/resolve-config-path ctx "../../shared/subjects.edn")
            ctx' (project-context/note-config-path ctx outside)
            diag (first (:diagnostics ctx'))]
          (is (= (npath dir "shared" "subjects.edn") outside))
          (is (false? (:portable? ctx')))
          (is (= :project-context/path-escapes-project (:type diag)))
          (is (= outside (:path diag))))))))
