(ns shiftlefter.runner-test
  (:require [clojure.test :refer [deftest is]]
            [shiftlefter.runner :as runner]
            [shiftlefter.gherkin.lexer :as lexer]
            [shiftlefter.gherkin.parser :as parser]
            [shiftlefter.gherkin.pickler :as pickler]
            [clojure.core.async :refer [chan go <! close!]]))

(deftest test-defstep
  (reset! runner/step-registry {})
  (runner/defstep #"test (.*)" [arg] {:result arg})
  (is (= 1 (count @runner/step-registry))))

(deftest test-exec-pass
  (reset! runner/step-registry {})
  (runner/defstep #"I pass" [] :ok)
  (let [event-chan (chan 100)]
    (go (loop [] (when (<! event-chan) (recur))))
    (let [pickles [{:pickle/steps [{:step/id (java.util.UUID/randomUUID) :step/text "I pass"}]}]
          results (runner/exec pickles event-chan)]
      (is (= 1 (count results)))
      (is (= :pass (:status (first results)))))))

(deftest test-exec-fail
  (reset! runner/step-registry {})
  (runner/defstep #"I fail" [] (throw (ex-info "fail" {})))
  (let [event-chan (chan 100)]
    (go (loop [] (when (<! event-chan) (recur))))
    (let [pickles [{:pickle/steps [{:step/id (java.util.UUID/randomUUID) :step/text "I fail"}]}]
          results (runner/exec pickles event-chan)]
      (is (= 1 (count results)))
      (is (= :fail (:status (first results)))))))

(deftest test-no-match
  (reset! runner/step-registry {})
  (let [event-chan (chan 100)]
    (go (loop [] (when (<! event-chan) (recur))))
    (let [pickles [{:pickle/steps [{:step/id (java.util.UUID/randomUUID) :step/text "no match"}]}]
          results (runner/exec pickles event-chan)]
      (is (= 1 (count results)))
      (is (= :fail (:status (first results)))))))

(deftest test-toy-exec
  (reset! runner/step-registry {})
  (runner/defstep #"I am on the login page" [] (runner/see "login page" :pass))
  (runner/defstep #"I type \"([^\"]+)\" into \"([^\"]+)\"" [value field] (runner/mock-type field value :pass))
  (runner/defstep #"I click \"([^\"]+)\"" [element] (runner/click element :pass))
  (runner/defstep #"I see \"([^\"]+)\"" [text] (runner/see text :pass))
  (let [event-chan (chan 100)
        collected-events (atom [])
        _ (go (loop [] (when-let [e (<! event-chan)] (swap! collected-events conj e) (recur))))
        content (slurp "resources/features/toy-login.feature")
        tokens (shiftlefter.gherkin.lexer/lex content)
        ast (shiftlefter.gherkin.parser/parse tokens)
        pickles (shiftlefter.gherkin.pickler/pre-pickles (:ast ast) {} "toy-login.feature")
        results (runner/exec pickles event-chan)]
    (close! event-chan)
    (is (= 5 (count results)))
    (is (every? #(= :pass (:status %)) results))
    (is (= 10 (count @collected-events)))))

(deftest test-events-emitted
  (reset! runner/step-registry {})
  (runner/defstep #"test step" [] :ok)
  (let [event-chan (chan 1)
        collected-events (atom [])
        _ (go (loop [] (when-let [e (<! event-chan)] (swap! collected-events conj e) (recur))))
        pickles [{:pickle/steps [{:step/id (java.util.UUID/randomUUID) :step/text "test step"}]}]
        _results (runner/exec pickles event-chan)]
    (close! event-chan)
    (is (= 2 (count @collected-events)))  ; start and end
    (is (= :step-start (:event (first @collected-events))))
    (is (= :step-end (:event (second @collected-events))))))