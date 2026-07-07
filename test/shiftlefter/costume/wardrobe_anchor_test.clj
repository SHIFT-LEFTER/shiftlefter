(ns shiftlefter.costume.wardrobe-anchor-test
  "Verifies the real wardrobe-dir anchors to SL_USER_CWD.

   Kept separate from wardrobe-test because that ns's `with-temp-wardrobe`
   fixture stubs `wardrobe-dir` itself — which would shadow the very function
   under test here."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.costume.wardrobe :as wardrobe]
            [shiftlefter.paths :as paths]))

(deftest wardrobe-dir-anchored-to-user-cwd-test
  (testing "wardrobe-dir is anchored at SL_USER_CWD, not the raw process CWD"
    (with-redefs [paths/user-cwd (constantly "/tmp/sl-user-proj")]
      (is (= "/tmp/sl-user-proj/.shiftlefter/wardrobe" (wardrobe/wardrobe-dir)))
      (is (= "/tmp/sl-user-proj/.shiftlefter/wardrobe/finance"
             (wardrobe/costume-dir :finance)))
      (is (= "/tmp/sl-user-proj/.shiftlefter/wardrobe/finance/chrome-profile"
             (wardrobe/chrome-profile-dir :finance))))))
