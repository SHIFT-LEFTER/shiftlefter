(ns shiftlefter.webdriver.keys-parity-test
  "The key-split parity contract (sl-52ly, sitting-ruled 08-19).

   The language layer owns the closed W3C key-name set
   (shiftlefter.svo.keys); each adapter owns name → vendor-code directly.
   This test IS the extension mechanism: a future key name added to the
   closed set without a row in a vendor table must FAIL the build here,
   never surface as a runtime miss."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.svo.keys :as svo-keys]
            [shiftlefter.webdriver.etaoin.keys :as et-keys]
            [shiftlefter.webdriver.playwright.keys :as pw-keys]))

(deftest etaoin-table-covers-the-closed-set
  (testing "name-set == keys(etaoin table), both directions"
    (is (= (set svo-keys/key-names)
           (set (keys et-keys/w3c-key->etaoin)))
        "every closed-set name has an etaoin code, and the table carries no name outside the set"))
  (testing "every name resolves to a non-empty wire string distinct from the name"
    (doseq [n svo-keys/key-names]
      (let [resolved (et-keys/->key-str n)]
        (is (and (string? resolved) (seq resolved)) n)
        (is (not= n resolved)
            (str n " must translate to a vendor code, not pass through"))))))

(deftest playwright-is-identity-except-space
  (testing "the sole exception, asserted explicitly: Space → the single space character"
    (is (= " " (pw-keys/translate-key-string "Space"))))
  (testing "every other closed-set name passes through as itself"
    (doseq [n (remove #{"Space"} svo-keys/key-names)]
      (is (= n (pw-keys/translate-key-string n)) n))))

(deftest single-characters-pass-through-on-both-adapters
  (testing "quoted-single-char tokens reach the vendor untranslated"
    (doseq [c ["g" "1" "'" " "]]
      (is (= c (et-keys/->key-str c)) (pr-str c))
      (is (= c (pw-keys/translate-key-string c)) (pr-str c)))))
