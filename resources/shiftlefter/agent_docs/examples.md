# ShiftLefter Worked Examples

Complete, runnable file shapes — config, glossaries, intents, features,
and run-level orchestration — copied VERBATIM from the framework's shipped
`examples/` projects. A consumer project has only the jar, so these worked
shapes are supplied here as packaged reference; each snippet names its
source file.

This file is GENERATED from the example files themselves (which the
example validation tests keep true). Do not edit it by hand — edit the
source example and regenerate with `clojure -T:build gen-examples`.

## Minimal config

The smallest useful `shiftlefter.edn`: one interface, one adapter. Everything else (glossaries, `:svo` enforcement, step paths) is opt-in.

_Verbatim from `examples/02-browser-zero-code/shiftlefter.edn`._

```edn
;; The :etaoin adapter suppresses Chrome's native password popups (breach
;; warnings, save prompts) by default — automation hygiene, so page-driving
;; tests aren't narrated over by browser UI. Override via :adapter-opts
;; {:prefs {...}} if a test ever needs them back (docs/INSTALL-TIERS.md
;; § adapter-opts).

{:interfaces {:web {:type :web
                    :adapter :etaoin}}}
```

## Config with glossaries wired

The canonical glossary layout: `glossary/` at the project root, subjects in `glossary/subjects.edn`, per-interface verbs in `glossary/verbs/<iface>.edn`, plus `:svo` enforcement levels.

_Verbatim from `examples/svo-demo/shiftlefter.edn`._

```edn
;; ShiftLefter SVO Demo Configuration
;;
;; This example demonstrates the Subject-Verb-Object (SVO) validation system.
;; Key concepts:
;;
;; - INTERFACE NAME: The key you use in config and stepdefs (e.g., :web, :customer-portal)
;; - INTERFACE TYPE: The vocabulary category (e.g., :web, :api) - determines valid verbs
;;
;; Simple case: name = type (:web interface uses :web verbs)
;; Advanced case: multiple names share a type (:legacy-app and :new-app both use :web verbs)

{:parser {:dialect "en"}

 :runner {:step-paths ["src/stepdefs/"]
          :allow-pending? true}

 ;; Glossary configuration (canonical layout: glossary/ at the project root —
 ;; same convention as examples 04-06 and docs)
 ;; - subjects: path to subject glossary (glossary/subjects.edn)
 ;; - verbs: map of interface-type -> verb glossary path (glossary/verbs/<iface>.edn)
 ;; Project glossaries extend (don't replace) framework defaults
 :glossaries {:subjects "glossary/subjects.edn"
              :verbs {:web "glossary/verbs/web.edn"}}

 ;; Interface definitions
 ;; Each interface NAME maps to:
 ;; - :type — which verb vocabulary to use (determines valid verbs)
 ;; - :adapter — which adapter to use for provisioning
 ;; - :config — adapter-specific configuration
 :interfaces {:web {:type :web              ; Uses :web verb glossary
                    :adapter :etaoin        ; Browser automation
                    :config {:headless true}}}

 ;; SVO enforcement levels
 ;; - :warn — log warning but continue execution
 ;; - :error — fail at bind time (before execution)
 :svo {:unknown-subject :warn    ; Typos in subject names
       :unknown-verb :warn       ; Unknown verbs for interface type
       :unknown-interface :error}} ; Misconfigured interfaces
```

## Subjects glossary

Subject types with optional `:instances` (session handles — `:user/alice`) and singletons (`:admin`, `:guest`).

_Verbatim from `examples/svo-demo/glossary/subjects.edn`._

```edn
;; Subject Glossary for SVO Demo
;;
;; Subjects represent the actors in your test scenarios.
;; Types are top-level keys; instances are session handles under :instances.
;; Types without :instances are singletons (used directly in steps).
;;
;; In Gherkin, use :type/instance form:
;;   :user/alice, :user/bob, :admin, :guest
;;
;; Legacy flat format (no :instances) still works — each entry is a singleton.

{:subjects
 {;; Human actors with instances
  :user {:desc "Standard application user"
         :instances [:alice :bob]}

  ;; Singleton roles (no instances needed)
  :admin {:desc "Administrative user with elevated privileges"}
  :guest {:desc "Unauthenticated visitor"}

  ;; System actors (for setup/teardown steps)
  :system/test-setup {:desc "Test harness for fixture creation"}
  :system/database {:desc "Database operations actor"}}}
```

## Project verbs glossary

A project verbs glossary EXTENDS the framework defaults for its interface type. Each verb declares a closed set of frames; each frame declares its arg slots and surface pattern. Frames bind to step definitions via shifted stepdef metadata — see `sl agent-doc authoring`.

_Verbatim from `examples/svo-demo/glossary/verbs/web.edn`._

```edn
;; Project-Specific Web Verbs
;;
;; This glossary EXTENDS the framework's default web verbs.
;; Default verbs include: :click, :fill, :see, :navigate, :scroll, :press, etc.
;;
;; Each verb declares its valence as a closed set of frames; each frame
;; declares its args (slot names beyond S-V-O) and a human-readable
;; surface pattern. See the framework's verbs-web.edn for documentation.

{:type :web
 :verbs
 {;; Domain-specific verbs for this application
  :login  {:desc "Authenticate with username/password"
           :frames {:default {:args [:username :password]
                              :pattern "S logs in as USERNAME with PASSWORD"}}}
  :logout {:desc "End authenticated session"
           :frames {:default {:args []
                              :pattern "S logs out"}}}
  :search {:desc "Execute a search query"
           :frames {:default {:args [:query]
                              :pattern "S searches for QUERY"}}}
  :filter {:desc "Apply filter criteria to results"
           :frames {:default {:args [:criteria]
                              :pattern "S filters by CRITERIA"}}}}}
```

## Intent with elements

The simplest intent shape: named elements with per-interface locator bindings. Steps then address `Login.email` instead of a raw locator.

_Verbatim from `examples/04-sms-2fa/glossary/intents/login.edn`._

```edn
;; Login — the reset-password flow's form surface. Both stages of the
;; fixture page reuse one submit button markup, so a single :submit
;; element serves stage 1 (email) and stage 2 (code).
{:intent "Login"
 :elements {:email  {:bindings {:web {:id "email"}}}
            :code   {:bindings {:web {:id "code"}}}
            :submit {:bindings {:web {:css "button[type=\"submit\"]"}}}}}
```

## Config wiring an intents directory

Intents load from a directory of EDN files via `:glossaries {:intents "glossary/intents"}`.

_Verbatim from `examples/06-nested-parent-anchored/shiftlefter.edn`._

```edn
;; ShiftLefter config — parent-anchored nested addressing demo (sl-1ps example 06).
;;
;; One interface (:web via Etaoin). The :intents glossary is loaded by the
;; runner into the intent state at startup (runner Stage 1b) so the browser
;; step path resolves nested addresses like Dashboard.featured[1].rating.stars
;; against this example's own intents. setup.clj stands up the fixture server.
{:interfaces {:web {:type :web :adapter :etaoin :config {}}}
 :runner     {:step-paths []}
 :glossaries {:subjects "glossary/subjects.edn"
              :intents  "glossary/intents"}
 :svo        {:unknown-subject :error
              :unknown-verb    :warn
              :unknown-interface :error}}
```

## Parent-anchored nested intents

One reused component (`ProductCard`) appearing under three different wrappers: the parent's `:collections` supply the container `:selector`, the child keeps only its inner selectors, and features address nested regions semantically (`Dashboard.featured[1].rating.stars`) — prefer this over flat union selectors.

_Verbatim from `examples/06-nested-parent-anchored/glossary/intents/dashboard.edn`._

```edn
;; Dashboard — document-scoped (no :root). The SAME ProductCard appears under
;; three different wrappers; each collection supplies its own :selector. This
;; is what parent-anchoring expresses and self-rooting cannot (§7.4): one
;; component, three container markups.
{:intent "Dashboard"
 :elements {}
 :collections
 {:featured {:intent "ProductCard" :selector {:web {:css ".featured .fbt-item"}}    :cardinality :many}
  :sidebar  {:intent "ProductCard" :selector {:web {:css ".sidebar .recs-card"}}    :cardinality :many}
  :results  {:intent "ProductCard" :selector {:web {:css ".results .s-result-item"}} :cardinality :many}}}
```

_Verbatim from `examples/06-nested-parent-anchored/glossary/intents/product-card.edn`._

```edn
;; ProductCard — the reused component (design §7.3, parent-anchored).
;;
;; No :root: the card's CONTAINER markup differs by section (.fbt-item /
;; .recs-card / .s-result-item), so the card cannot self-locate. Each parent
;; collection supplies the :selector (see dashboard.edn). The card's INNER
;; selectors (.title, .price) are identical everywhere — only the wrapper
;; varies, and only the parent knows it.
{:intent "ProductCard"
 :elements {:title {:bindings {:web {:css ".title"}}}
            :price {:bindings {:web {:css ".price"}}}}
 :collections {:rating {:intent "Rating"
                        :selector {:web {:css ".rating"}}
                        :count {:max 1}}}}
```

_Verbatim from `examples/06-nested-parent-anchored/glossary/intents/rating.edn`._

```edn
;; Rating — a nested region inside a ProductCard. No :root; the card's
;; :rating collection supplies the :selector. Demonstrates nested descent:
;; Dashboard.featured[1].rating.stars.
{:intent "Rating"
 :elements {:stars {:bindings {:web {:css ".stars"}}}}}
```

## Feature: intent refs and nested addresses

Built-in steps driving the parent-anchored intents above. Note the single-quoted literals (double quotes are reserved) and that no selector ever appears in step text.

_Verbatim from `examples/06-nested-parent-anchored/features/catalog.feature`._

```gherkin
Feature: Parent-anchored nested addressing
  One reused ProductCard component appears under three different section
  wrappers (.featured / .sidebar / .results). Each parent collection supplies
  its own :selector; the card's inner selectors are identical everywhere. The
  feature text never mentions a selector — only semantic addresses.

  Scenario: The same card resolves correctly under each wrapper
    When :user/shopper opens the browser to 'http://localhost:9091/catalog'
    Then :user/shopper should see Dashboard.featured[1].title with text 'Widget A'
    And :user/shopper should see Dashboard.featured[2].price with text '$20'
    And :user/shopper should see Dashboard.sidebar[1].title with text 'Gadget X'
    And :user/shopper should see Dashboard.results[-1].title with text 'Thing 3'

  Scenario: Nested descent into a card's rating region
    When :user/shopper opens the browser to 'http://localhost:9091/catalog'
    Then :user/shopper should see Dashboard.featured[1].rating.stars with text '4.5'
```

## Feature: raw-locator fallback

Built-in steps can also take raw locator maps inline (`{:id "username"}`) when no intent exists yet — a legitimate fallback; see `sl agent-doc locators` for the brittleness policy.

_Verbatim from `examples/02-browser-zero-code/features/login.feature`._

```gherkin
Feature: Login page

  Scenario: Page loads with expected elements
    When :user opens the browser to 'http://127.0.0.1:9094/login'
    Then :user should see the title 'Login'
    And :user should see {:id "username"}
    And :user should see {:id "password"}
    And :user should see {:css "button[type='submit']"}

  Scenario: Successful login
    When :user opens the browser to 'http://127.0.0.1:9094/login'
    And :user fills {:id "username"} with 'alice'
    And :user fills {:id "password"} with 'secret1'
    And :user clicks {:css "button[type='submit']"}
    Then :user should see 'Welcome, alice!'
    And :user should be on '/dashboard'
```

## Run-level orchestration: setup.clj

The setup.clj convention: run-level fixture orchestration (start/stop a server around chosen features), not a step definition — the feature files stay zero custom stepdefs.

_Verbatim from `examples/02-browser-zero-code/setup.clj`._

```clojure
(ns setup
  "Run-level orchestration for the zero-custom-code browser example.

   Starts the bundled fixture server (classes already in the jar — no
   Clojure toolchain needed) with the login + dashboard page blocks and one
   seeded user, so the example runs fully offline. This file is the
   framework's setup.clj convention (see ARCHITECTURE.md § Test
   Orchestration): run-level orchestration, not a step definition — the
   feature file remains zero custom stepdefs.

   Binds 127.0.0.1 specifically and the feature dials 127.0.0.1 (sl-7hb1
   hygiene); fixed port 9094 because the feature hardcodes its URL in step
   text (9090/9092 belong to examples 04-06)."
  (:require [org.httpkit.server :as http]
            [shiftlefter.demo.fixture.handler :as fh]
            ;; Side-effect: register the :login and :dashboard page blocks.
            [shiftlefter.demo.fixture.login]
            [shiftlefter.demo.fixture.dashboard]))

(def ^:private port 9094)

(defn- start-login-fixture
  [_config]
  (let [handler (fh/build-handler {:users {"alice" "secret1"}
                                   :pages [:login :dashboard]})
        stop!   (http/run-server handler {:ip "127.0.0.1" :port port})]
    {:stop (fn [] (stop! :timeout 100))}))

(def setups
  [{:label    "login"
    :start    start-login-fixture
    :features ["features/login.feature"]}])
```
