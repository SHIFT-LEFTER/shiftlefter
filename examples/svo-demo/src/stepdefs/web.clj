(ns stepdefs.web
  "Web step definitions demonstrating SVO validation.

   This file shows both legacy and shifted step definition styles:

   LEGACY STYLE (no metadata):
   - Works unchanged from pre-SVO versions
   - No subject/verb validation
   - Good for setup steps that don't fit SVO pattern

   SHIFTED STYLE (with metadata):
   - Adds :interface and :svo metadata
   - Subject/verb validated against glossaries
   - Capability auto-provisioned based on interface

   Key concepts:
   - :interface — which configured interface this step uses (e.g., :web)
   - :svo — subject/verb/object extraction with :$1, :$2 placeholders
   - Capture groups map to :$1, :$2, etc. in order

   Convention: ctx-first
   - ctx is the first argument to every step function
   - ctx is a flat map (scenario state) — NOT nested under (:scenario ctx)
   - Return the updated ctx map (or ctx unchanged for verification steps)"
  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

;; =============================================================================
;; LEGACY STEPS (No SVO Metadata)
;; =============================================================================

;; Setup steps often don't follow SVO pattern - that's fine!
;; Legacy style works exactly as before.

(defstep #"^the login page is loaded$"
  [ctx]
  ;; In a real test, this would navigate to the login page
  (println "[Setup] Navigating to login page")
  (assoc ctx :page :login))

;; =============================================================================
;; SHIFTED STEPS (With SVO Metadata)
;; =============================================================================

;; Shifted steps include :interface and :svo metadata.
;; The framework will:
;; 1. Extract subject/verb/object at bind time
;; 2. Validate against glossaries
;; 3. Auto-provision capability (browser) for the interface

;; --- Fill Steps ---
;; Pattern: "{Subject} fills the {field} field with {value}"
;; Subject comes from first capture group (:$1)
;; Object is the field being filled (:$2)

(defstep #"^(\w+) fills the (\w+) field with \"([^\"]+)\"$"
  {:interface :web
   :svo {:subject :$1      ; First capture = subject (Alice, Bob, etc.)
         :verb :fill        ; Action being performed
         :object :$2}}      ; Second capture = what's being filled
  [ctx subject field value]
  ;; In a real test, this would use a browser driver
  (println (str "[" subject "] Filling " field " with: " value))
  (assoc ctx :last-action {:fill field :value value}))

;; --- Click Steps ---
;; Pattern: "{Subject} clicks the {element}"

(defstep #"^(\w+) clicks the (.+)$"
  {:interface :web
   :svo {:subject :$1
         :verb :click
         :object :$2}}
  [ctx subject element]
  (println (str "[" subject "] Clicking: " element))
  (assoc ctx :last-action {:click element}))

;; --- See Steps ---
;; Pattern: "{Subject} sees {something}"

(defstep #"^(\w+) sees the (.+)$"
  {:interface :web
   :svo {:subject :$1
         :verb :see
         :object :$2}}
  [ctx subject element]
  (println (str "[" subject "] Verifying visible: " element))
  ctx)

(defstep #"^(\w+) sees (?:a |an )?(?:error )?message \"([^\"]+)\"$"
  {:interface :web
   :svo {:subject :$1
         :verb :see
         :object "message"}}
  [ctx subject message]
  (println (str "[" subject "] Verifying message: " message))
  ctx)

(defstep #"^(\w+) sees her (.+)$"
  {:interface :web
   :svo {:subject :$1
         :verb :see
         :object :$2}}
  [ctx subject element]
  (println (str "[" subject "] Verifying visible: " element))
  ctx)

;; --- Navigate Steps ---
;; Pattern: "{Subject} navigates to {page}"

(defstep #"^(\w+) navigates to the (.+)$"
  {:interface :web
   :svo {:subject :$1
         :verb :navigate
         :object :$2}}
  [ctx subject page]
  (println (str "[" subject "] Navigating to: " page))
  (assoc ctx :page page))

;; =============================================================================
;; Notes for Users
;; =============================================================================
;;
;; 1. SUBJECT VALIDATION
;;    Subjects like "Alice", "Bob", "Admin", "Guest" are validated against
;;    config/glossaries/subjects.edn. Typos like "Alcie" will be caught.
;;
;; 2. VERB VALIDATION
;;    Verbs like :fill, :click, :see are validated against the verb glossary
;;    for the interface type. Using :smash would fail validation.
;;
;; 3. INTERFACE vs TYPE
;;    The :interface in metadata refers to the NAME in config (:web here).
;;    The :type in config determines which verb vocabulary applies.
;;    Simple case: :web interface uses :web type verbs.
;;
;; 4. AUTO-PROVISIONING
;;    When a step has :interface :web, the framework will automatically
;;    create a browser session if one doesn't exist. This uses the
;;    :adapter and :config from the interface definition.
;;
;; 5. CAPTURE GROUPS
;;    :$1, :$2, :$3 refer to regex capture groups in order.
;;    The subject is typically :$1 in "Subject does something" patterns.
;;
;; 6. CTX-FIRST CONVENTION
;;    ctx is always the first argument. It's a flat map — just assoc/get
;;    directly on it. Return the updated ctx (or ctx unchanged for
;;    verification-only steps).
