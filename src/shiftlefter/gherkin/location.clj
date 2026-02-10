(ns shiftlefter.gherkin.location
  "Location record and specs for source position tracking.

   `(->Location line column)` represents a position in Gherkin source.
   Used throughout the parser and AST to track where each element was
   defined, enabling precise error messages and source-uri linking."
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
