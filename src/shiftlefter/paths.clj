(ns shiftlefter.paths
  "Single source of truth for \"where the user is\".

   When `sl` runs from PATH, `bin/sl` cd's into the install dir before the JVM
   starts, so the raw process CWD is the install dir — NOT the project the user
   ran `sl` from. `bin/sl` captures the user's real working directory in the
   `SL_USER_CWD` env var before any cd. Every project-scoped path (wardrobe,
   default config, user-supplied feature/config paths) must resolve against that,
   never against the raw process CWD.

   This ns is dependency-light on purpose (only `babashka.fs`) so any namespace —
   including low-level ones like `costume.wardrobe` that cannot depend on `core` —
   can resolve user paths from one definition. No duplicated env reads."
  (:require [babashka.fs :as fs]))

(def ^:dynamic *user-cwd*
  "Per-request working-directory override. The warm daemon (sl-rju) binds this
   for each dispatch — a single JVM cannot chdir per request, so this dynamic
   var is the seam that lets one daemon serve every subdirectory correctly.
   nil (the default, cold path) ⇒ fall back to SL_USER_CWD, then user.dir,
   exactly as the CLI always has."
  nil)

(defn user-cwd
  "The user's working directory: the bound `*user-cwd*` if set, else
   `SL_USER_CWD`, else the JVM's `user.dir`.

   `*user-cwd*` is the daemon's per-request seam (nil on the cold path).
   `SL_USER_CWD` is captured by `bin/sl` before any cd; the `user.dir` fallback
   covers running via `clj`/REPL directly (where the process CWD IS the project)."
  []
  (or *user-cwd*
      (System/getenv "SL_USER_CWD")
      (System/getProperty "user.dir")))

(defn resolve-user-path
  "Resolve a path relative to the user's working directory.

   Absolute paths are returned unchanged. Relative paths are resolved against
   `user-cwd`."
  [path]
  (if (fs/absolute? path)
    (str path)
    (str (fs/path (user-cwd) path))))

(defn resolve-user-paths
  "Resolve multiple paths relative to the user's working directory. Nil-safe."
  [paths]
  (mapv resolve-user-path paths))
