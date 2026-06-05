(ns handoff
  "The entire glue between web and SMS for this demo: 14 lines of body.

   The framework's SMS receive step stashes captured groups from its
   regex match into ctx as `{:sms/captures {:full ... :groups [g1 ...]}}`.
   This stepdef pulls the first capture out and types it into a browser
   field. That's the cross-interface handoff.

   A future bead (γ — generic ctx-interpolation) may make this expressible
   in step text directly (e.g. `{ctx.sms.captures.groups.0}`); until then,
   custom stepdefs are how you compose interfaces. They're easy to write."
  (:require [clojure.edn :as edn]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.browser.locators :as loc]
            [shiftlefter.browser.protocol :as bp]
            [shiftlefter.stepengine.registry :refer [defstep]]))

(defn- subject-str->key
  "Normalize subject string from step text to keyword, stripping namespace.
   \"user/alice\" → :alice; \"alice\" → :alice."
  [s]
  (let [kw (keyword s)]
    (if (namespace kw) (keyword (name kw)) kw)))

(defstep #":([\w./-]+) fills (\{[^}]+\}) with the SMS code"
  {:interface :web
   :requires-protocols [:shiftlefter.browser.protocol/IBrowser]}
  [ctx subject locator-text]
  (let [code     (first (get-in ctx [:sms/captures :groups]))
        browser  (cap/get-capability ctx :web (subject-str->key subject))
        locator  (loc/resolve-locator (edn/read-string locator-text))]
    (bp/fill! browser locator code)
    ctx))
