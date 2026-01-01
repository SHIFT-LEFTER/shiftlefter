(ns build
  "Build tasks for ShiftLefter.

   Usage:
     clj -T:build uberjar    ; Build standalone JAR
     clj -T:build clean      ; Remove build artifacts"
  (:require [clojure.tools.build.api :as b]))

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
