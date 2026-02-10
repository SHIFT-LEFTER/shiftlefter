(ns shiftlefter.webdriver.session-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [babashka.fs :as fs]
            [shiftlefter.webdriver.session-store :as store]))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(def ^:private test-dir "./.shiftlefter-test")

(defn clean-test-dir [f]
  (when (fs/exists? test-dir)
    (fs/delete-tree test-dir))
  (f)
  (when (fs/exists? test-dir)
    (fs/delete-tree test-dir)))

(use-fixtures :each clean-test-dir)

;; -----------------------------------------------------------------------------
;; Factory Tests
;; -----------------------------------------------------------------------------

(deftest make-edn-store-test
  (testing "creates store with default dir"
    (let [s (store/make-edn-store)]
      (is (= "./.shiftlefter" (:dir s)))))

  (testing "creates store with custom dir"
    (let [s (store/make-edn-store {:dir test-dir})]
      (is (= test-dir (:dir s))))))

;; -----------------------------------------------------------------------------
;; Save/Load/Delete Tests
;; -----------------------------------------------------------------------------

(deftest save-and-load-test
  (testing "saves and loads session handle"
    (let [s (store/make-edn-store {:dir test-dir})
          handle {:webdriver-url "http://127.0.0.1:9515"
                  :session-id "abc123"
                  :meta {:foo :bar}}
          result (store/save-session-handle! s :alice handle)]
      (is (:ok result))
      (is (string? (get-in result [:ok :saved-at])))
      (is (= :alice (get-in result [:ok :surface])))

      (let [loaded (store/load-session-handle s :alice)]
        (is (= "http://127.0.0.1:9515" (:webdriver-url loaded)))
        (is (= "abc123" (:session-id loaded)))
        (is (= {:foo :bar} (:meta loaded)))
        (is (string? (:saved-at loaded)))))))

(deftest load-nonexistent-test
  (testing "load returns nil for nonexistent surface"
    (let [s (store/make-edn-store {:dir test-dir})]
      (is (nil? (store/load-session-handle s :nobody))))))

(deftest delete-test
  (testing "deletes existing handle"
    (let [s (store/make-edn-store {:dir test-dir})
          handle {:webdriver-url "http://x:1" :session-id "S"}]
      (store/save-session-handle! s :alice handle)
      (is (some? (store/load-session-handle s :alice)))

      (let [result (store/delete-session-handle! s :alice)]
        (is (:ok result)))

      (is (nil? (store/load-session-handle s :alice)))))

  (testing "delete succeeds for nonexistent surface"
    (let [s (store/make-edn-store {:dir test-dir})
          result (store/delete-session-handle! s :nobody)]
      (is (:ok result)))))

(deftest multiple-surfaces-test
  (testing "stores multiple surfaces independently"
    (let [s (store/make-edn-store {:dir test-dir})]
      (store/save-session-handle! s :alice {:webdriver-url "http://a:1" :session-id "A"})
      (store/save-session-handle! s :bob {:webdriver-url "http://b:2" :session-id "B"})

      (is (= "A" (:session-id (store/load-session-handle s :alice))))
      (is (= "B" (:session-id (store/load-session-handle s :bob))))

      (store/delete-session-handle! s :alice)
      (is (nil? (store/load-session-handle s :alice)))
      (is (= "B" (:session-id (store/load-session-handle s :bob)))))))

(deftest overwrite-test
  (testing "saving overwrites existing handle"
    (let [s (store/make-edn-store {:dir test-dir})]
      (store/save-session-handle! s :alice {:webdriver-url "http://old:1" :session-id "OLD"})
      (store/save-session-handle! s :alice {:webdriver-url "http://new:2" :session-id "NEW"})

      (let [loaded (store/load-session-handle s :alice)]
        (is (= "http://new:2" (:webdriver-url loaded)))
        (is (= "NEW" (:session-id loaded)))))))

;; -----------------------------------------------------------------------------
;; List Surfaces Test
;; -----------------------------------------------------------------------------

(deftest list-surfaces-test
  (testing "lists all stored surfaces"
    (let [s (store/make-edn-store {:dir test-dir})]
      (store/save-session-handle! s :alice {:session-id "A"})
      (store/save-session-handle! s :bob {:session-id "B"})
      (store/save-session-handle! s :charlie {:session-id "C"})

      (let [surfaces (set (store/list-surfaces s))]
        (is (= #{:alice :bob :charlie} surfaces)))))

  (testing "returns empty for nonexistent dir"
    (let [s (store/make-edn-store {:dir "./.nonexistent-test-dir"})]
      (is (nil? (store/list-surfaces s))))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria (from spec)
;; -----------------------------------------------------------------------------

(deftest acceptance-criteria-test
  (testing "Task 2.5.7 AC"
    (let [s (store/make-edn-store {:dir test-dir})
          h {:webdriver-url "http://x:1" :session-id "S" :saved-at "t"}]
      (store/save-session-handle! s :alice h)
      (is (= "S" (:session-id (store/load-session-handle s :alice))))
      (store/delete-session-handle! s :alice)
      (is (nil? (store/load-session-handle s :alice))))))
