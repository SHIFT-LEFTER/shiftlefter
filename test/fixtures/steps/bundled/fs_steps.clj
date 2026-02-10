(ns fixtures.steps.bundled.fs-steps
  "Step definitions exercising babashka.fs library."
  (:require [shiftlefter.stepengine.registry :refer [defstep]]
            [babashka.fs :as fs]))

(defstep #"I create a temp file with babashka.fs"
  [ctx]
  (let [tmp (fs/create-temp-file {:prefix "sl-test-" :suffix ".txt"})
        path (str tmp)]
    (spit path "bundled capability test")
    (assoc ctx :temp-file path)))

(defstep #"the temp file should exist"
  [ctx]
  (let [path (:temp-file ctx)]
    (when-not (fs/exists? path)
      (throw (ex-info "Temp file does not exist" {:path path})))
    (assoc ctx :temp-file-exists true)))

(defstep #"I delete the temp file"
  [ctx]
  (let [path (:temp-file ctx)]
    (fs/delete path)
    (assoc ctx :temp-file-deleted (not (fs/exists? path)))))
