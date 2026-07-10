(ns build
  "Build tasks for ShiftLefter.

   Usage:
     clj -T:build uberjar                        ; Build standalone JAR
     clj -T:build jar                            ; Alias for uberjar
     clj -T:build docs                           ; Generate API docs
     clj -T:build release-zip :version '\"X.Y.Z\"' ; Build release distribution zip
     clj -T:build clean                          ; Remove build artifacts"
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]))

(def lib 'com.shiftlefter/shiftlefter)
(def version "0.5.1")
(def class-dir "target/classes")
(def uber-file "target/shiftlefter.jar")
(def logback-version "1.5.16")

;; Calculate basis from deps.edn
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def uberjar-basis
  (delay
    (b/create-basis
     {:project "deps.edn"
      :extra {:deps {'ch.qos.logback/logback-classic
                     {:mvn/version logback-version}}}})))

(def builtins-doc-resource
  "Checked-in, classpath-visible home of the generated built-in vocabulary
   topic. Committed so `sl agent-doc builtins` behaves identically in a dev run
   (only resources/ on the classpath) and from the distributed jar."
  "resources/shiftlefter/agent_docs/builtins.md")

(defn- write-builtins-doc!
  "Generate the built-in vocabulary topic to `out-path`.

   Shells out to `clojure -X` so generation runs on the project classpath: the
   `-T:build` tool classpath doesn't see the framework (same reason the `docs`
   task subprocesses codox)."
  [out-path]
  (let [result (shell/sh "clojure" "-X"
                         "shiftlefter.agent-doc.builtins-gen/write-doc!"
                         ":out" (pr-str out-path))]
    (print (:out result))
    (binding [*out* *err*]
      (print (:err result)))
    (when-not (zero? (:exit result))
      (throw (ex-info "Built-in vocabulary generation failed"
                      {:exit (:exit result) :err (:err result)})))))

(defn gen-builtins
  "Regenerate the checked-in built-in vocabulary agent-doc topic (sl-6po).

   Usage: clojure -T:build gen-builtins

   Writes resources/shiftlefter/agent_docs/builtins.md from the framework's
   default glossaries, step registry, and adapter registry. Run after changing
   a built-in verb/frame/step/adapter; the drift test fails until you do."
  [_]
  (write-builtins-doc! builtins-doc-resource))

(def agent-md-doc-path
  "Repo-root derived agent guide (sl-b06). Not packaged — single-sourced from the
   `sl agent-doc` topics for repo/GitHub readers."
  "docs/AGENT.md")

(defn- write-agent-md-doc!
  "Generate docs/AGENT.md from the packaged agent-doc topics. Shells out to
   `clojure -X` for the same classpath reason as `write-builtins-doc!`."
  [out-path]
  (let [result (shell/sh "clojure" "-X"
                         "shiftlefter.agent-doc.agent-md-gen/write-doc!"
                         ":out" (pr-str out-path))]
    (print (:out result))
    (binding [*out* *err*]
      (print (:err result)))
    (when-not (zero? (:exit result))
      (throw (ex-info "AGENT.md generation failed"
                      {:exit (:exit result) :err (:err result)})))))

(defn gen-agent-md
  "Regenerate the derived docs/AGENT.md from the packaged agent-doc topics (sl-b06).

   Usage: clojure -T:build gen-agent-md

   Run after editing any topic in resources/shiftlefter/agent_docs/. Because the
   `builtins` topic is itself generated, run `gen-builtins` first when built-in
   vocabulary changed; the drift test fails until docs/AGENT.md is regenerated."
  [_]
  (write-agent-md-doc! agent-md-doc-path))

(def dev-jar-link
  "Flat-layout dev jar (sl-72e): bin/sl discovers `shiftlefter*.jar` NEXT TO
   itself, the same rule the release zip relies on. So the dev build links the
   built jar into bin/ — one wrapper, one discovery rule, dev == release shape.
   A relative symlink (not a 42MB copy) so it always tracks the real jar and
   stays in sync on every rebuild. Git-ignored."
  "bin/shiftlefter.jar")

(defn- link-dev-jar!
  "Point bin/shiftlefter.jar at the freshly built uberjar via a relative symlink.
   Best-effort: a platform without `ln -s` (e.g. a future Windows build host) just
   skips the link — the canonical jar in target/ is unaffected, and bin/sl will
   report 'no jar found' until a flat jar exists, which is honest."
  []
  (let [link (io/file dev-jar-link)]
    (.delete link)                       ; replace any stale link/file in place
    (let [{:keys [exit err]}
          (shell/sh "ln" "-s" "../target/shiftlefter.jar" dev-jar-link)]
      (if (zero? exit)
        (println (str "Linked " dev-jar-link " -> " uber-file))
        (binding [*out* *err*]
          (println (str "Warning: could not link " dev-jar-link ": " err)))))))

(defn clean
  "Remove build artifacts."
  [_]
  (b/delete {:path "target"})
  (.delete (io/file dev-jar-link))       ; drop the now-dangling dev jar link
  (println "Cleaned target/"))

(defn- write-version-resource []
  (let [version-file (io/file class-dir "shiftlefter" "version.edn")]
    (.mkdirs (.getParentFile version-file))
    (spit version-file (pr-str {:version version}))))

(defn uberjar
  "Build standalone uberjar with AOT compilation."
  [_]
  (clean nil)
  (println "Compiling sources...")
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir
               ;; src/java holds the warm-path dispatch client (sl-x6r); it is
               ;; compiled by b/javac below, not shipped as source.
               :ignores [".*\\.java$"]})
  (write-version-resource)
  ;; Regenerate the built-in vocabulary topic straight into the jar so it can
  ;; never drift from the code being packaged, independent of the checked-in
  ;; copy (sl-6po).
  (let [builtins-class-file (io/file class-dir "shiftlefter" "agent_docs" "builtins.md")]
    (.mkdirs (.getParentFile builtins-class-file))
    (write-builtins-doc! (str builtins-class-file)))
  (b/compile-clj {:basis @basis
                  :ns-compile '[shiftlefter.core]
                  :class-dir class-dir})
  ;; Warm-path dispatch client (shiftlefter.client.NreplClient) — a Clojure-free
  ;; Java class compiled INTO the uberjar so it is always version-matched with the
  ;; daemon and boots in ~30ms (no Clojure init). bin/sl runs it via -cp.
  (b/javac {:src-dirs ["src/java"]
            :class-dir class-dir
            :basis @basis})
  (println "Building uberjar...")
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @uberjar-basis
           :main 'shiftlefter.core})
  (println (str "Built " uber-file))
  (link-dev-jar!))

(defn jar
  "Build standalone uberjar. Alias retained for public build docs."
  [opts]
  (uberjar opts))

(defn docs
  "Generate API documentation with the canonical build version."
  [_]
  (let [result (shell/sh "clj" "-X:codox" ":version" (pr-str version))]
    (print (:out result))
    (binding [*out* *err*]
      (print (:err result)))
    (when-not (zero? (:exit result))
      (throw (ex-info "Codox generation failed"
                      {:exit (:exit result)
                       :err (:err result)}))))
  (println "Generated docs/api/index.html"))

(defn release-zip
  "Build release distribution zip containing sl script and versioned JAR.

   Usage: clj -T:build release-zip :version '\"X.Y.Z\"'

   Creates: target/shiftlefter-vX.Y.Z.zip containing:
     shiftlefter-vX.Y.Z/
       sl
       shiftlefter-vX.Y.Z.jar
       agents-breadcrumb.md"
  [{:keys [version]}]
  (when-not version
    (throw (ex-info "Version required. Usage: clj -T:build release-zip :version '\"X.Y.Z\"'" {})))

  ;; Build the uberjar first
  (uberjar nil)

  (let [version-str    (str "v" version)
        dir-name       (str "shiftlefter-" version-str)
        jar-name       (str "shiftlefter-" version-str ".jar")
        zip-name       (str dir-name ".zip")
        staging-dir    (io/file "target" dir-name)
        ;; Package the ONE canonical wrapper (sl-72e). bin/sl is always-jar +
        ;; warm-daemon-capable and discovers its jar flat (next to itself) — the
        ;; exact shape the zip stages below — so the shipped artifact is the same
        ;; script the warm-path E2E exercises. The stale pre-warm release/sl is
        ;; gone; there is no second wrapper to drift.
        release-sl     (io/file "bin/sl")
        ;; The agent on-ramp breadcrumb (sl-k0s), staged flat so the installer
        ;; can print it without reaching into the jar. Single source — the same
        ;; file is a jar classpath resource that sl init (cn7) / sl-tje reuse.
        breadcrumb     (io/file "resources/shiftlefter/onramp/agents-breadcrumb.md")
        source-jar     (io/file uber-file)]

    (when-not (.exists release-sl)
      (throw (ex-info "bin/sl wrapper not found" {:path "bin/sl"})))
    (when-not (.exists breadcrumb)
      (throw (ex-info "agent breadcrumb stanza not found"
                      {:path (str breadcrumb)})))

    ;; Create staging directory with release contents
    (println (str "Staging " dir-name "..."))
    (.mkdirs staging-dir)
    (let [dest-sl (io/file staging-dir "sl")]
      (io/copy release-sl dest-sl)
      ;; Set executable permission (rwxr-xr-x)
      (.setExecutable dest-sl true false))
    (io/copy source-jar (io/file staging-dir jar-name))
    (io/copy breadcrumb (io/file staging-dir "agents-breadcrumb.md"))

    ;; Use system zip command to preserve permissions
    (println (str "Creating target/" zip-name "..."))
    (let [result (shell/sh "zip" "-r" zip-name dir-name :dir "target")]
      (when (not= 0 (:exit result))
        (throw (ex-info "zip command failed" {:exit (:exit result)
                                              :err (:err result)}))))

    ;; Clean up staging directory
    (b/delete {:path (str staging-dir)})

    (println (str "Built target/" zip-name))
    (println "")
    (println "To test:")
    (println (str "  cd /tmp && unzip " (System/getProperty "user.dir") "/target/" zip-name))
    (println (str "  export PATH=\"$PATH:/tmp/" dir-name "\""))
    (println "  sl --help")))
