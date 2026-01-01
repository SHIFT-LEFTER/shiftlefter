(ns hooks.arity-mismatch
  (:require [clj-kondo.hooks-api :as api]
            [clojure.spec.alpha :as s]))

(defn arity-mismatch [{:keys [node]}]
  (let [call-sexpr (api/sexpr node)]
    (when (and (seq? call-sexpr) (symbol? (first call-sexpr)))
      (let [fn-sym (first call-sexpr)]
        (when-let [spec (s/get-spec fn-sym)]
          (when-let [ret-spec (:ret spec)]
            (let [arity (cond
                          (and (seq? ret-spec) (= 'clojure.spec.alpha/tuple (first ret-spec)))
                          (count (rest ret-spec))
                          ;; for recur, if it's recur, check the loop args
                          (= fn-sym 'recur)
                          ;; need to find the loop/fn args
                          0 ;; placeholder
                          :else 0)]
              (when (> arity 0)
                ;; find let binding count
                (loop [p (api/parent-node node)]
                  (when p
                    (let [p-sexpr (api/sexpr p)]
                      (if (and (seq? p-sexpr) (= 'let (first p-sexpr)))
                        (let [bindings (nth p-sexpr 1)
                              bind-count (count bindings)]
                          (when (> bind-count arity)
                            (api/reg-finding! (assoc (meta node)
                                                     :message (format "Arity mismatch: %d binds, %d returns—risk nil shift/leak" bind-count arity)
                                                     :type :arity-mismatch))))
                        (recur (api/parent-node p)))))))))))))