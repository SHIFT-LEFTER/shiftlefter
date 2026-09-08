(ns shiftlefter.project-context
  "Canonical project context discovery and path resolution.

   A project context is computed at command/request entry, then explicitly
   threaded through project-scoped subsystems. The resolver owns only location
   facts: config discovery, root classification, path normalization, and
   portability diagnostics. Config parsing and domain validation stay with the
   owning subsystems."
  (:refer-clojure :exclude [resolve])
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [shiftlefter.paths :as paths]))

(def config-file-name "shiftlefter.edn")

(defn- path-name [path]
  (some-> path fs/file-name str))

(defn- normalize-path
  "Return an absolute normalized string. Does not require the path to exist."
  ([path]
   (normalize-path nil path))
  ([base path & more]
   (let [p (if (and base (not (fs/absolute? path)))
             (apply fs/path base path more)
             (apply fs/path path more))]
     (try
       (if (fs/exists? p)
         (str (fs/real-path p))
         (str (.normalize (.toAbsolutePath (fs/path p)))))
       (catch Exception _
         (str (.normalize (.toAbsolutePath (fs/path p)))))))))

(defn- filesystem-root [path]
  (loop [dir (fs/path path)]
    (if-let [parent (fs/parent dir)]
      (recur parent)
      (normalize-path dir))))

(defn- git-worktree-root [start]
  (let [result (shell/sh "git" "rev-parse" "--show-toplevel" :dir start)]
    (when (zero? (:exit result))
      (normalize-path (str/trim (:out result))))))

(defn- search-boundary [invocation-root]
  (or (git-worktree-root invocation-root)
      (filesystem-root invocation-root)))

(defn- ancestors-through-boundary [start boundary]
  (let [boundary (normalize-path boundary)]
    (loop [dir (fs/path (normalize-path start))
           acc []]
      (let [dir-str (normalize-path dir)
            acc' (conj acc dir-str)]
        (cond
          (= dir-str boundary) acc'
          (nil? (fs/parent dir)) acc'
          :else (recur (fs/parent dir) acc'))))))

(defn- sl-config-path [candidate-root]
  (normalize-path candidate-root "sl" config-file-name))

(defn- candidate-at
  "The single discovered layout (sl-fcjm, layout C): <candidate>/sl/shiftlefter.edn.
   Root-level shiftlefter.edn is no longer discovered; -c FILE remains the
   escape hatch for exotic layouts."
  [candidate-root]
  (let [sl-path (sl-config-path candidate-root)]
    (when (fs/exists? sl-path)
      {:status :found
       :project-root candidate-root
       :config-root (normalize-path candidate-root "sl")
       :config-path sl-path
       :layout :sl-directory})))

(defn- not-found-diagnostic [path]
  {:type :config/not-found
   :message (str "Config file not found: " path)
   :path path})

(defn- standard-explicit-layout [config-path]
  (let [config-dir (normalize-path (fs/parent config-path))]
    (if (= "sl" (path-name config-dir))
      {:project-root (normalize-path (fs/parent config-dir))
       :config-root config-dir
       :layout :sl-directory}
      {:project-root config-dir
       :config-root config-dir
       :layout :root})))

(defn- context-base [invocation-root workspace-root]
  {:invocation-root invocation-root
   :workspace-root workspace-root})

(defn- portable-diagnostic [path-class base path]
  {:type :project-context/path-escapes-project
   :message (str "Configured " (name path-class) " path escapes the project root: " path)
   :path-class path-class
   :base base
   :path path})

(defn- inside-root? [root path]
  (let [root (normalize-path root)
        path (normalize-path path)]
    (or (= root path)
        (str/starts-with? path (str root java.io.File/separator)))))

(defn- with-portability-diagnostic [context path-class base path]
  (if (inside-root? (:project-root context) path)
    context
    (-> context
        (assoc :portable? false)
        (update :diagnostics conj (portable-diagnostic path-class base path)))))

(defn resolve-cli-path
  "Resolve a command-line path against :invocation-root."
  [context path]
  (normalize-path (:invocation-root context) path))

(defn resolve-cli-paths
  "Resolve command-line paths against :invocation-root. Nil-safe."
  [context paths]
  (mapv #(resolve-cli-path context %) (or paths [])))

(defn resolve-config-path
  "Resolve a config-declared path against :config-root."
  [context path]
  (normalize-path (:config-root context) path))

(defn resolve-config-paths
  "Resolve config-declared paths against :config-root. Nil-safe."
  [context paths]
  (mapv #(resolve-config-path context %) (or paths [])))

(defn operational-path
  "Resolve framework-owned operational state below :project-root."
  [context & segments]
  (normalize-path (apply fs/path (:project-root context) segments)))

(defn ensure-self-ignore!
  "Create a `.shiftlefter` state dir and its self-ignoring `.gitignore`
   (`*`), write-if-absent — creation-site self-ignore (sl-7dq5 F9): the
   runtime litter dir must be git-silent however the jar arrived, not only
   when the installer ran `sl init`. Mirrors
   shiftlefter.runner.results/ensure-results-root!; an existing (possibly
   user-customized) ignore file is never clobbered. Callers pass the
   `.shiftlefter` dir itself — never a user-chosen override root, which is
   the user's to ignore or not. Returns the dir."
  [shiftlefter-dir]
  (fs/create-dirs shiftlefter-dir)
  (let [gi (fs/file (str shiftlefter-dir) ".gitignore")]
    (when-not (fs/exists? gi)
      (spit gi "*\n")))
  shiftlefter-dir)

(defn note-config-path
  "Return context with a portability warning if a config-declared source path
   escapes :project-root. Escapes remain valid."
  [context path]
  (with-portability-diagnostic context :config (:config-root context) path))

(defn note-cli-path
  "Return context with a portability warning if a CLI source path escapes
   :project-root. Escapes remain valid."
  [context path]
  (with-portability-diagnostic context :cli (:invocation-root context) path))

(defn resolve
  "Resolve the current ShiftLefter project context.

   Options:
   - :invocation-root absolute/relative directory, defaults to paths/user-cwd
   - :config-path explicit config file path, resolved against invocation root

   The resolver prints nothing. Diagnostics are returned as data."
  ([] (resolve {}))
  ([opts]
   (let [invocation-root (normalize-path (or (:invocation-root opts)
                                             (paths/user-cwd)))
         workspace-root (search-boundary invocation-root)
         base (context-base invocation-root workspace-root)]
     (if-let [explicit (:config-path opts)]
       (let [config-path (normalize-path invocation-root explicit)]
         (if-not (fs/exists? config-path)
           (merge base
                  (standard-explicit-layout config-path)
                  {:config-path config-path
                   :config-source :explicit
                   :portable? false
                   :diagnostics [(not-found-diagnostic config-path)]})
           (merge base
                  (standard-explicit-layout config-path)
                  {:config-path config-path
                   :config-source :explicit
                   :portable? true
                   :diagnostics []})))
       (let [ancestors (ancestors-through-boundary invocation-root workspace-root)]
         (loop [[candidate-root & more] ancestors]
           (if-not candidate-root
             (merge base
                    {:project-root invocation-root
                     :config-root invocation-root
                     :config-path nil
                     :config-source :defaults
                     :layout :defaults
                     :portable? true
                     :diagnostics []})
             (if-let [candidate (candidate-at candidate-root)]
               (merge base
                      (select-keys candidate [:project-root :config-root :config-path :layout])
                      {:config-source :discovered
                       :portable? true
                       :diagnostics []})
               (recur more)))))))))

(defn instance-root
  "Daemon-instance anchor for a resolved context, or nil when there is none.

   MUST match bin/sl `find_instance_root` exactly — the wrapper and the daemon
   have to agree on where .shiftlefter/daemon.edn lives (sl-v7l6). The shared
   rule: a discovered/explicit config anchors at its project root; with no
   config, a git worktree anchors at its toplevel; otherwise there is no anchor
   (the wrapper runs cold rather than spawn a daemon for an unconfigured tree)."
  [context]
  (if (= :defaults (:config-source context))
    (let [workspace-root (:workspace-root context)]
      ;; :workspace-root is the git toplevel when one exists, else the
      ;; filesystem root (search-boundary). A `.git` entry — dir or linked-
      ;; worktree file, same probe as the wrapper's `-e` — tells them apart.
      (when (fs/exists? (fs/path workspace-root ".git"))
        workspace-root))
    (:project-root context)))

(defn require-real-config
  "Return nil when context has a real config; otherwise a structured diagnostic."
  [context command]
  (when (= :defaults (:config-source context))
    {:type :project-context/config-required
     :message (str command " requires a shiftlefter.edn. Create sl/shiftlefter.edn at the project root.")
     :command command
     :invocation-root (:invocation-root context)}))
