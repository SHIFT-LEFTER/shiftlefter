(ns setup
  "Test orchestration for the self-rooted nested-addressing demo.

   Stands up the fixture server with the :feed page on a fixed port so the
   feature's URL is stable. No custom adapter registry — the default :web
   (Etaoin) adapter drives a real browser."
  (:require [org.httpkit.server :as http]
            [shiftlefter.demo.fixture.handler :as fh]
            ;; Side-effect: registers the :feed page in the page registry.
            [shiftlefter.demo.fixture.feed]))

(def ^:private port 9092)

(defn- start-feed
  [_config]
  (let [handler (fh/build-handler {:pages [:feed]})
        stop!   (http/run-server handler {:port port})]
    {:stop (fn [] (stop! :timeout 100))}))

(def setups
  [{:label    "self-rooted"
    :start    start-feed
    :features ["features/feed.feature"]}])
