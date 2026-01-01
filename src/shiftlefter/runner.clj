(ns shiftlefter.runner
  "Runner for ShiftLefter — executes pickles via regex-bound step functions."
  (:require [clojure.core.async :refer [>!!]]))

;; Registry for step bindings: atom {regex fn}
(def step-registry (atom {}))

;; Mock functions for toy execution
(defn click [element status]
  (if (= status :fail)
    (throw (ex-info "Click failed" {:element element}))
    {:action :click :element element}))

(defn mock-type [field value status]
  (if (= status :fail)
    (throw (ex-info "Type failed" {:field field :value value}))
    {:action :type :field field :value value}))

(defn see [text status]
  (if (= status :fail)
    (throw (ex-info "See failed" {:text text}))
    {:action :see :text text}))

;; defstep macro: binds regex to fn
(defmacro defstep [pattern args & body]
  `(swap! step-registry assoc ~pattern (fn ~args ~@body)))

;; Execute pickles, emit events to chan, return results
(defn exec [pickles event-chan]
  (let [results (atom [])]
    (doseq [pickle pickles
            step (:pickle/steps pickle)]
      (let [text (:step/text step)
            match (first (for [[regex fn] @step-registry
                               :let [m (re-find regex text)]
                               :when m] [regex fn m]))]
        (if match
          (let [[_ fn groups] match
                args (if (string? groups) [] (rest groups))
                step-id (:step/id step)]
            (>!! event-chan {:event :step-start :step-id step-id :trace {:text text}})
            (try
              (let [result (apply fn args)]
                (swap! results conj {:status :pass :step step :result result})
                (>!! event-chan {:event :step-end :step-id step-id :trace result}))
              (catch Exception e
                (let [msg (ex-message e)]
                  (swap! results conj {:status :fail :step step :error msg})
                  (>!! event-chan {:event :step-end :step-id step-id :trace {:error msg}})))))
          (let [msg "No matching step"]
            (>!! event-chan {:event :step-error :step-id (:step/id step) :trace {:error msg}})
            (swap! results conj {:status :fail :step step :error msg})))))
    @results))

;; Console report from results
(defn report [results]
  (let [passed (count (filter #(= (:status %) :pass) results))
        failed (count (filter #(= (:status %) :fail) results))]
    (println (str "Passed: " passed ", Failed: " failed))
    (doseq [r results]
      (let [prefix (if (= (:status r) :pass) "PASS" "FAIL")
            text (:step/text (:step r))
            error (:error r)]
        (println (str prefix " " text (when error (str " (" error ")"))))))))