(ns shiftlefter.sieve.server-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.sieve.contract :as contract]
            [shiftlefter.sieve.server :as server]
            [shiftlefter.sieve.store :as store]
            [shiftlefter.sieve.web :as web])
  (:import [java.io ByteArrayInputStream]))

(defn- json-request [method uri body]
  (cond-> {:request-method method :uri uri}
    body (assoc :body (ByteArrayInputStream. (.getBytes (json/generate-string body) "UTF-8")))))

(deftest run-sieve-wraps-current-browser-path-behind-contract
  (let [snapshot (contract/make-evidence-snapshot
                   {:interface {:name :web :type :web}
                    :source {:kind :live-browser :url "https://example.test"}
                    :capture {:captured-at "2026-06-16T12:00:00Z"
                              :mechanism :etaoin-sieve-js
                              :best-effort? true
                              :deterministic? false
                              :warnings []}
                    :environment {}
                    :project {:fingerprint "projection-fp"}
                    :payload-schema web/web-evidence-schema
                    :payload {:html "<html></html>"
                              :inventory {:elements [{:label "Sign in"}]
                                          :forms []
                                          :iframes []}}
                    :warnings []})
        analysis (web/analyze-web-evidence snapshot
                                       {:projection {:fingerprint "projection-fp"}})]
    (with-redefs [web/capture-and-analyze-web (fn [request]
                                                (is (= :driver (:driver request)))
                                                {:evidence snapshot
                                                 :analysis analysis})]
      (let [result (server/run-sieve :driver
                                     {:projection {:fingerprint "projection-fp"}})]
        (is (= [{:label "Sign in"}] (:elements result)))
        (is (= (contract/evidence-ref snapshot)
               (get-in result [:sieve :evidence-snapshot])))
        (is (= (contract/analysis-ref analysis)
               (get-in result [:sieve :analysis-result])))
        (is (= 1 (get-in result [:sieve :candidate-count])))))))

(deftest proposal-endpoint-writes-a-proposal-result-headlessly
  ;; The full data round-trip without a live browser: analyze a saved snapshot,
  ;; store it, then POST a classify/rename/decide interpretation to /proposal and
  ;; assert a Proposal Result is persisted. Driver is unused on this path (nil).
  (let [tmp (str (fs/create-temp-dir))
        snapshot (store/load-fixture "test/fixtures/sieve/web-login-snapshot.edn")
        analysis (store/save-analysis-result!
                   tmp (web/analyze-web-evidence snapshot {:projection {:fingerprint "fp"}}))
        handler (#'server/make-handler nil {:store-root tmp})
        response (handler (json-request :post "/proposal"
                                        {:analysis-id (:analysis/id analysis)
                                         :page-url "https://example.test/login"
                                         :claims [{:element-index 0 :category "clickable"
                                                   :name "sign-in" :decision "accept"}
                                                  {:element-index 1 :category "typable"
                                                   :decision "ambiguous"}]}))
        body (json/parse-string (:body response) true)]
    (is (= 200 (:status response)))
    (is (= 1 (:selected body)))
    (is (= 1 (:unresolved body)))
    (is (= "draft" (:status body)))
    (testing "the proposal is loadable from the store under the returned id"
      (let [proposal (store/load-proposal-result tmp (:proposal-id body))]
        (is (= [] (:intended-writes proposal)))))))

(deftest proposal-endpoint-rejects-missing-analysis-id
  (let [handler (#'server/make-handler nil {:store-root "/tmp/unused"})
        response (handler (json-request :post "/proposal" {:claims []}))]
    (is (= 400 (:status response)))))

(deftest ui-is-served-from-the-bridge
  (let [handler (#'server/make-handler nil {})]
    (testing "GET / serves the authoring shell index"
      (let [response (handler {:request-method :get :uri "/"})]
        (is (= 200 (:status response)))
        (is (re-find #"text/html" (get-in response [:headers "Content-Type"])))
        (is (re-find #"SIEVE Toddler" (:body response)))))
    (testing "path traversal is rejected"
      (is (= 404 (:status (handler {:request-method :get :uri "/../secret"})))))))
