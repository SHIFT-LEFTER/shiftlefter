(ns shiftlefter.test-helpers.log-capture
  "Capture `clojure.tools.logging` calls for assertions in tests.

   Bypasses the SLF4J backend entirely by binding a custom
   `*logger-factory*`, so tests don't depend on logback / log4j config.
   Migrated out of `test/shiftlefter/sms/twilio_test.clj` (sl-5wj) so
   multiple test namespaces can share it."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as log-impl]))

(defn capturing-factory
  "Return [factory log-atom] where `factory` is a tools.logging
   LoggerFactory that reports `enabled?` true for all levels and writes
   each call into the returned atom as `[level msg]`. Useful when you
   need finer control than `with-captured-logs` (e.g. capturing across
   a longer-lived scope)."
  []
  (let [logged (atom [])
        logger (reify log-impl/Logger
                 (enabled? [_ _level] true)
                 (write! [_ level _throwable msg]
                   (swap! logged conj [level (str msg)])))
        factory (reify log-impl/LoggerFactory
                  (name [_] "test-capture")
                  (get-logger [_ _logger-ns] logger))]
    [factory logged]))

(defn with-captured-logs
  "Run `f` while capturing every `clojure.tools.logging` call.

   Returns a vector of `[level msg]` entries (level is a keyword such
   as `:warn` / `:error`, msg is the stringified message). Bypasses
   the underlying logging backend, so the same assertions work
   whether or not an SLF4J binding is on the classpath."
  [f]
  (let [[factory logged] (capturing-factory)]
    (binding [log/*logger-factory* factory]
      (f))
    @logged))

(defn level-msg?
  "Predicate: `entry` (a `[level msg]` pair) has `level` and its
   message contains `substr`. Convenience for `(some ...)` assertions
   over the vector returned by `with-captured-logs`."
  [level substr]
  (fn [[lvl msg]]
    (and (= level lvl) (str/includes? msg substr))))
