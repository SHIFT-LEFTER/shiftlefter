(ns build
  "Build tasks for ShiftLefter.

   Usage:
     clj -T:build uberjar                        ; Build standalone JAR
     clj -T:build release-zip :version '\"0.3.5\"' ; Build release distribution zip
     clj -T:build clean                          ; Remove build artifacts"
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]))

(def lib 'com.shiftlefter/shiftlefter)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file "target/shiftlefter.jar")

;; Calculate basis from deps.edn
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean
  "Remove build artifacts."
  [_]
  (b/delete {:path "target"})
  (println "Cleaned target/"))

(defn uberjar
  "Build standalone uberjar with AOT compilation."
  [_]
  (clean nil)
  (println "Compiling sources...")
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :ns-compile '[shiftlefter.core]
                  :class-dir class-dir})
  (println "Building uberjar...")
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'shiftlefter.core})
  (println (str "Built " uber-file)))

(defn release-zip
  "Build release distribution zip containing sl script and versioned JAR.

   Usage: clj -T:build release-zip :version '\"0.3.5\"'

   Creates: target/shiftlefter-v0.3.5.zip containing:
     shiftlefter-v0.3.5/
       sl
       shiftlefter-v0.3.5.jar"
  [{:keys [version]}]
  (when-not version
    (throw (ex-info "Version required. Usage: clj -T:build release-zip :version '\"0.3.5\"'" {})))

  ;; Build the uberjar first
  (uberjar nil)

  (let [version-str    (str "v" version)
        dir-name       (str "shiftlefter-" version-str)
        jar-name       (str "shiftlefter-" version-str ".jar")
        zip-name       (str dir-name ".zip")
        staging-dir    (io/file "target" dir-name)
        release-sl     (io/file "release/sl")
        source-jar     (io/file uber-file)]

    (when-not (.exists release-sl)
      (throw (ex-info "release/sl script not found" {:path "release/sl"})))

    ;; Create staging directory with release contents
    (println (str "Staging " dir-name "..."))
    (.mkdirs staging-dir)
    (let [dest-sl (io/file staging-dir "sl")]
      (io/copy release-sl dest-sl)
      ;; Set executable permission (rwxr-xr-x)
      (.setExecutable dest-sl true false))
    (io/copy source-jar (io/file staging-dir jar-name))

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
