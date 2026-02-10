(ns fixtures.steps.bundled.java-interop-steps
  "Step definitions exercising Java stdlib interop."
  (:require [shiftlefter.stepengine.registry :refer [defstep]])
  (:import [java.time LocalDate]
           [java.net URI]
           [java.security MessageDigest]))

(defstep #"I use java.time to get today's date"
  [ctx]
  (let [today (LocalDate/now)
        year (.getYear today)]
    (assoc ctx :java-date (str today) :java-year year)))

(defstep #"I parse a URI with java.net"
  [ctx]
  (let [uri (URI. "https://shiftlefter.dev/docs?page=1")
        host (.getHost uri)
        scheme (.getScheme uri)]
    (assoc ctx :uri-host host :uri-scheme scheme)))

(defstep #"I compute a SHA-256 hash"
  [ctx]
  (let [md (MessageDigest/getInstance "SHA-256")
        input (.getBytes "shiftlefter" "UTF-8")
        hash-bytes (.digest md input)
        hex-str (apply str (map #(format "%02x" %) hash-bytes))]
    (assoc ctx :sha256-hash hex-str)))

(defstep #"the Java interop results should be correct"
  [ctx]
  (when-not (pos? (:java-year ctx))
    (throw (ex-info "Invalid year" {:year (:java-year ctx)})))
  (when (not= "shiftlefter.dev" (:uri-host ctx))
    (throw (ex-info "URI parse failed" {:host (:uri-host ctx)})))
  (when (not= "https" (:uri-scheme ctx))
    (throw (ex-info "URI scheme wrong" {:scheme (:uri-scheme ctx)})))
  (when-not (= 64 (count (:sha256-hash ctx)))
    (throw (ex-info "SHA-256 hash wrong length" {:hash (:sha256-hash ctx)})))
  ctx)
