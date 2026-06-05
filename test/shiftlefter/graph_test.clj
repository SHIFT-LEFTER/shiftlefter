(ns shiftlefter.graph-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.graph :as graph]
            [babashka.fs :as fs]))

;; ---------------------------------------------------------------------------
;; Temp directory fixture — each test gets a fresh DB path
;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db-path* nil)

(defn with-temp-db [f]
  (let [tmp (str (fs/create-temp-dir {:prefix "sl-graph-test-"}))]
    (binding [*test-db-path* tmp]
      (try
        (f)
        (finally
          (graph/reset-db! {:path tmp}))))))

(use-fixtures :each with-temp-db)

(defn test-opts []
  {:path *test-db-path*})

;; ---------------------------------------------------------------------------
;; Lifecycle tests
;; ---------------------------------------------------------------------------

(deftest init-db-creates-connection
  (testing "init-db! returns a connection and creates the directory"
    (let [conn (graph/init-db! (test-opts))]
      (is (some? conn))
      (is (fs/exists? *test-db-path*)))))

(deftest reset-db-clears-data
  (testing "reset-db! wipes the database and returns a fresh connection"
    (let [conn (graph/init-db! (test-opts))]
      (graph/transact! conn [{:sl/kind :subject :sl/name :alice}])
      (let [fresh-conn (graph/reset-db! (test-opts))
            results    (graph/query fresh-conn
                         '[:find ?name
                           :where [?e :sl/kind :subject]
                                  [?e :sl/name ?name]])]
        (is (some? fresh-conn))
        (is (empty? results))))))

;; ---------------------------------------------------------------------------
;; Transact + query round-trip
;; ---------------------------------------------------------------------------

(deftest transact-and-query-subjects
  (testing "transact entity maps and query them back"
    (let [conn (graph/init-db! (test-opts))]
      (graph/transact! conn
        [{:sl/kind :subject :sl/name :alice :subject/type :user}
         {:sl/kind :subject :sl/name :bob   :subject/type :user}
         {:sl/kind :subject :sl/name :pat   :subject/type :admin}])
      (let [results (graph/query conn
                      '[:find ?name ?type
                        :where [?e :sl/kind :subject]
                               [?e :sl/name ?name]
                               [?e :subject/type ?type]])]
        (is (= 3 (count results)))
        (is (= #{[:alice :user] [:bob :user] [:pat :admin]}
               (set results)))))))

(deftest transact-and-query-verbs
  (testing "transact verbs and query by interface"
    (let [conn (graph/init-db! (test-opts))]
      (graph/transact! conn
        [{:sl/kind :verb :sl/name :click    :verb/interface :web}
         {:sl/kind :verb :sl/name :fill     :verb/interface :web}
         {:sl/kind :verb :sl/name :send-api :verb/interface :api}])
      (let [web-verbs (graph/query conn
                        '[:find ?name
                          :where [?e :sl/kind :verb]
                                 [?e :verb/interface :web]
                                 [?e :sl/name ?name]])]
        (is (= 2 (count web-verbs)))
        (is (= #{[:click] [:fill]} (set web-verbs)))))))

(deftest transact-mixed-entity-kinds
  (testing "mixed entity kinds coexist and are independently queryable"
    (let [conn (graph/init-db! (test-opts))]
      (graph/transact! conn
        [{:sl/kind :subject :sl/name :alice :subject/type :user}
         {:sl/kind :verb    :sl/name :click :verb/interface :web}
         {:sl/kind :region  :sl/name :login-form}])
      (let [all-kinds (graph/query conn
                        '[:find ?kind ?name
                          :where [?e :sl/kind ?kind]
                                 [?e :sl/name ?name]])]
        (is (= 3 (count all-kinds)))
        (is (= #{[:subject :alice] [:verb :click] [:region :login-form]}
               (set all-kinds)))))))
