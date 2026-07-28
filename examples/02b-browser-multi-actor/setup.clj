(ns setup
  "Run-level orchestration for the multi-actor browser example.

   Starts the bundled fixture server (classes already in the jar) with the
   login + dashboard page blocks and two seeded users — alice and bob each
   log in with their own credentials, and the dashboard greets each session
   by name, so session isolation is visible as page text. Fully offline.

   This is the framework's setup.clj convention (ARCHITECTURE.md § Test
   Orchestration): run-level orchestration, zero custom stepdefs.

   Binds 127.0.0.1 specifically and the feature dials 127.0.0.1 (sl-7hb1
   hygiene); fixed port 9095 because the feature hardcodes its URL in step
   text (9090/9092 belong to examples 04-06, 9094 to example 02)."
  (:require [org.httpkit.server :as http]
            [shiftlefter.demo.fixture.handler :as fh]
            ;; Side-effect: register the :login and :dashboard page blocks.
            [shiftlefter.demo.fixture.login]
            [shiftlefter.demo.fixture.dashboard]))

(def ^:private port 9095)

(defn- start-login-fixture
  [_config]
  (let [handler (fh/build-handler {:users {"alice" "secret1"
                                           "bob"   "secret2"}
                                   :pages [:login :dashboard]})
        stop!   (http/run-server handler {:ip "127.0.0.1" :port port})]
    {:stop (fn [] (stop! :timeout 100))}))

(def setups
  [{:label    "two-users"
    :start    start-login-fixture
    :features ["features/two-users.feature"]}])
