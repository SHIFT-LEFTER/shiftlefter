(ns hooks)

(def hooks
  [{:name "audit"
    :global? true
    :before (fn [_payload] nil)}
   {:name "reset-db"
    :before (fn [_payload] {:seed/reset true})
    :after (fn [_payload] nil)}])
