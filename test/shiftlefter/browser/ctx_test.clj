(ns shiftlefter.browser.ctx-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.browser.ctx :as ctx]))

(deftest browser-present?-test
  (testing "returns false for empty ctx"
    (is (false? (ctx/browser-present? {}))))

  (testing "returns false for ctx without :cap/browser"
    (is (false? (ctx/browser-present? {:foo :bar}))))

  (testing "returns false for :cap/browser without active session"
    (is (false? (ctx/browser-present? {:cap/browser {:sessions {} :active nil}}))))

  (testing "returns false for :cap/browser with active pointing to missing session"
    (is (false? (ctx/browser-present? {:cap/browser {:sessions {} :active :default}}))))

  (testing "returns true for ctx with active browser session"
    (is (true? (ctx/browser-present?
                {:cap/browser {:sessions {:default {:impl :fake}}
                               :active :default}})))))

(deftest get-active-browser-test
  (testing "returns nil for empty ctx"
    (is (nil? (ctx/get-active-browser {}))))

  (testing "returns nil for ctx without browser"
    (is (nil? (ctx/get-active-browser {:foo :bar}))))

  (testing "returns the active browser capability"
    (let [browser {:impl :fake :session-id "abc"}
          ctx {:cap/browser {:sessions {:default browser}
                             :active :default}}]
      (is (= browser (ctx/get-active-browser ctx)))))

  (testing "returns correct browser when multiple sessions exist"
    (let [alice {:impl :fake :session-id "alice"}
          bob {:impl :fake :session-id "bob"}
          ctx {:cap/browser {:sessions {:alice alice :bob bob}
                             :active :bob}}]
      (is (= bob (ctx/get-active-browser ctx))))))

(deftest assoc-active-browser-test
  (testing "adds browser to empty ctx with default session name"
    (let [browser {:impl :fake}
          ctx' (ctx/assoc-active-browser {} browser)]
      (is (= browser (get-in ctx' [:cap/browser :sessions :default])))
      (is (= :default (get-in ctx' [:cap/browser :active])))))

  (testing "adds browser with explicit session name"
    (let [browser {:impl :fake}
          ctx' (ctx/assoc-active-browser {} :alice browser)]
      (is (= browser (get-in ctx' [:cap/browser :sessions :alice])))
      (is (= :alice (get-in ctx' [:cap/browser :active])))))

  (testing "updates existing browser"
    (let [old {:impl :fake :old true}
          new {:impl :fake :new true}
          ctx {:cap/browser {:sessions {:default old} :active :default}}
          ctx' (ctx/assoc-active-browser ctx new)]
      (is (= new (ctx/get-active-browser ctx')))))

  (testing "preserves other ctx keys"
    (let [ctx {:foo :bar :baz 123}
          ctx' (ctx/assoc-active-browser ctx {:impl :fake})]
      (is (= :bar (:foo ctx')))
      (is (= 123 (:baz ctx'))))))

(deftest get-session-test
  (testing "returns nil for missing session"
    (is (nil? (ctx/get-session {} :default))))

  (testing "returns specific session"
    (let [alice {:impl :fake :name :alice}
          bob {:impl :fake :name :bob}
          ctx {:cap/browser {:sessions {:alice alice :bob bob}
                             :active :alice}}]
      (is (= alice (ctx/get-session ctx :alice)))
      (is (= bob (ctx/get-session ctx :bob))))))

(deftest set-active-session-test
  (testing "sets active to existing session"
    (let [ctx {:cap/browser {:sessions {:alice {} :bob {}}
                             :active :alice}}
          ctx' (ctx/set-active-session ctx :bob)]
      (is (= :bob (get-in ctx' [:cap/browser :active])))))

  (testing "does nothing if session doesn't exist"
    (let [ctx {:cap/browser {:sessions {:alice {}}
                             :active :alice}}
          ctx' (ctx/set-active-session ctx :charlie)]
      (is (= :alice (get-in ctx' [:cap/browser :active]))))))

(deftest dissoc-session-test
  (testing "removes specific session"
    (let [ctx {:cap/browser {:sessions {:alice {} :bob {}}
                             :active :alice}}
          ctx' (ctx/dissoc-session ctx :bob)]
      (is (nil? (get-in ctx' [:cap/browser :sessions :bob])))
      (is (some? (get-in ctx' [:cap/browser :sessions :alice])))))

  (testing "clears active if removed session was active"
    (let [ctx {:cap/browser {:sessions {:alice {} :bob {}}
                             :active :alice}}
          ctx' (ctx/dissoc-session ctx :alice)]
      (is (nil? (get-in ctx' [:cap/browser :active])))
      (is (some? (get-in ctx' [:cap/browser :sessions :bob]))))))

(deftest dissoc-browser-test
  (testing "removes entire :cap/browser from ctx"
    (let [ctx {:cap/browser {:sessions {:default {:impl :fake}}
                             :active :default}
               :foo :bar}
          ctx' (ctx/dissoc-browser ctx)]
      (is (nil? (:cap/browser ctx')))
      (is (= :bar (:foo ctx')))))

  (testing "works on empty ctx"
    (is (= {} (ctx/dissoc-browser {})))))

(deftest roundtrip-test
  (testing "acceptance criteria from spec"
    (let [ctx {}
          b {:impl :fake}
          ctx' (ctx/assoc-active-browser ctx b)]
      (is (true? (ctx/browser-present? ctx')))
      (is (= b (ctx/get-active-browser ctx'))))))
