(ns shiftlefter.gherkin.location
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

(defrecord Location [line column])

(s/def ::line   pos-int?)
(s/def ::column nat-int?)

(s/def ::location
  (s/with-gen
    (s/and (partial instance? Location) (s/keys :req-un [::line ::column]))
    #(gen/fmap (fn [[line col]] (->Location line col))
               (gen/tuple (s/gen ::line) (s/gen ::column)))))
