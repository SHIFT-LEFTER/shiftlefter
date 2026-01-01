(ns shiftlefter.gherkin.location
  (:require [clojure.spec.alpha :as s]))

(defrecord Location [line column])

(s/def ::line   pos-int?)
(s/def ::column nat-int?)

(s/def ::location
  (s/and (partial instance? Location) (s/keys :req-un [::line ::column])))
