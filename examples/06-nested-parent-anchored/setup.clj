(ns setup
  "Test orchestration for the parent-anchored nested-addressing demo.

   Stands up the fixture server with the :catalog page on a fixed port so the
   feature's URL is stable. No custom adapter registry — the default :web
   (Etaoin) adapter drives a real browser."
  (:require [org.httpkit.server :as http]
            [shiftlefter.demo.fixture.handler :as fh]
            ;; Side-effect: registers the :catalog page in the page registry.
            [shiftlefter.demo.fixture.catalog]))

(def ^:private port 9091)

(defn- start-catalog
  [_config]
  (let [handler (fh/build-handler {:pages [:catalog]})
        stop!   (http/run-server handler {:port port})]
    {:stop (fn [] (stop! :timeout 100))}))

(def setups
  [{:label    "parent-anchored"
    :start    start-catalog
    :features ["features/catalog.feature"]}])
