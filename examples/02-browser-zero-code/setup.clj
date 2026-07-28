(ns setup
  "Run-level orchestration for the zero-custom-code browser example.

   Starts the bundled fixture server (classes already in the jar — no
   Clojure toolchain needed) with the login + dashboard page blocks and one
   seeded user, so the example runs fully offline. This file is the
   framework's setup.clj convention (see ARCHITECTURE.md § Test
   Orchestration): run-level orchestration, not a step definition — the
   feature file remains zero custom stepdefs.

   Binds 127.0.0.1 specifically and the feature dials 127.0.0.1 (sl-7hb1
   hygiene); fixed port 9094 because the feature hardcodes its URL in step
   text (9090/9092 belong to examples 04-06)."
  (:require [org.httpkit.server :as http]
            [shiftlefter.demo.fixture.handler :as fh]
            ;; Side-effect: register the :login and :dashboard page blocks.
            [shiftlefter.demo.fixture.login]
            [shiftlefter.demo.fixture.dashboard]))

(def ^:private port 9094)

(defn- start-login-fixture
  [_config]
  (let [handler (fh/build-handler {:users {"alice" "secret1"}
                                   :pages [:login :dashboard]})
        stop!   (http/run-server handler {:ip "127.0.0.1" :port port})]
    {:stop (fn [] (stop! :timeout 100))}))

(def setups
  [{:label    "login"
    :start    start-login-fixture
    :features ["features/login.feature"]}])
