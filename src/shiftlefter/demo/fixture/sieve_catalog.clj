(ns shiftlefter.demo.fixture.sieve-catalog
  "Deterministic A/B web fixture for the SIEVE two-observation proof (sl-043).

   Serves two related states of one catalog so the reconcile bead
   (sl-sieve-two-observation-reconcile-bun) has raw material for every diff
   claim — retained / new / disappeared / changed — against ONE shared
   tentative vocabulary. THIS page only stands up the states and their stable
   anchors; the diff itself is out of scope here.

   ## Why small-but-noisy

   Enough non-semantic wrapper noise (shared chrome/nav, decorative `div`
   wrappers around each card, a nested rating widget) to exercise the
   deterministic provider honestly, but small enough to author and debug by
   hand. The committed Evidence Snapshots (test/fixtures/sieve/web-catalog-
   {a,b}-snapshot.edn) are hand-authored to match what `resources/sieve.js`
   would capture from these pages.

   ## Capture-shape constraint (drives the markup)

   `sieve.js` only emits CLASSIFIED elements (chrome / typable / clickable /
   readable). A bare `<div data-testid=…>` wrapper is filtered out and never
   reaches the inventory, so every stable cross-observation anchor sits on a
   captured element:

   - collection + card containers -> `<section>` / `<article>` (landmarks ->
     chrome) carrying `data-testid`;
   - title / price / rating text -> `<span>` (readable), anchored where it must
     be matched;
   - nav -> `<header id>` + `<a href>` links (chrome + clickable);
   - the modal overlay -> `<aside id>` (a landmark; `<dialog>`/`<div>` are NOT
     captured).

   Retained structures carry IDENTICAL anchors in both states so cross-
   observation correspondence is deterministic (general in-the-wild matching is
   post-0.5).

   ## The two states

   - State A (GET /sieve/catalog): shared chrome + a repeated collection of
     three product cards (card-1001/1002/1003); card-1001 carries the nested,
     reusable rating widget.
   - State B (GET /sieve/catalog/quickview): the same chrome + collection with a
     quick-view modal open, authored to exercise all four reconcile cases:
       - retained:    site-nav, nav-catalog, results, card-1001, card-1002
       - changed:     card-1002's price (same anchor, different text)
       - disappeared: card-1003 (display:none -> filtered by sieve.js)
       - new:         aside#quickview + reused card + add-to-cart form

   No auth — static GET content. Register with `:sieve-catalog`."
  (:require [shiftlefter.demo.fixture.pages :as pages]
            [shiftlefter.demo.fixture.handler :as handler]))

;; -----------------------------------------------------------------------------
;; HTML partials
;; -----------------------------------------------------------------------------

(defn- chrome
  "Shared site chrome (header/nav) reused by both states. The nav links carry
   stable anchors so the chrome is a retained structure across observations."
  []
  (str "<header id=\"site-nav\">"
       "<a href=\"/sieve/catalog\" data-testid=\"nav-catalog\">Catalog</a>"
       "<a href=\"/sieve/about\" data-testid=\"nav-about\">About</a>"
       "</header>"))

(defn- rating-widget
  "Nested, reusable rating widget. The `div.rating` wrapper is non-semantic
   noise; the `span.stars` carries the anchor and the readable text."
  [testid stars]
  (str "<div class=\"rating\">"
       "<span class=\"stars\" data-testid=\"" testid "\">" stars " stars</span>"
       "</div>"))

(defn- card
  "A reusable ProductCard. `<article>` is captured as chrome and carries the
   stable `data-testid`; inner spans are readable. `rating` is optional HTML
   (the nested widget) appended inside the card."
  [testid title price rating]
  (str "<article class=\"card\" data-testid=\"" testid "\">"
       "<div class=\"card-body\">"
       "<span class=\"title\">" title "</span>"
       "<span class=\"price\">" price "</span>"
       rating
       "</div>"
       "</article>"))

;; -----------------------------------------------------------------------------
;; State A — catalog listing
;; -----------------------------------------------------------------------------

(defn- catalog-a-html
  []
  (str "<!DOCTYPE html><html><head><title>Catalog</title></head><body>"
       (chrome)
       "<main>"
       "<h1>Product Catalog</h1>"
       "<section class=\"results\" data-testid=\"results\">"
       (card "card-1001" "Aurora Lamp" "$42" (rating-widget "rating-1001" "4.5"))
       (card "card-1002" "Beacon Clock" "$28" "")
       (card "card-1003" "Cinder Mug" "$14" "")
       "</section>"
       "</main></body></html>"))

;; -----------------------------------------------------------------------------
;; State B — same catalog with quick-view modal open
;; -----------------------------------------------------------------------------

(defn- quickview-modal-html
  "The NEW structure in state B: a modal overlay reusing the ProductCard widget
   in a new container, plus an add-to-cart form."
  []
  (str "<aside id=\"quickview\" class=\"modal\">"
       (card "quickview-card-1001" "Aurora Lamp" "$42"
             (rating-widget "quickview-rating-1001" "4.5"))
       "<form id=\"add-to-cart\">"
       "<input id=\"qty-input\" name=\"qty\" type=\"number\">"
       "<button id=\"add-to-cart-btn\" type=\"submit\">Add to cart</button>"
       "</form>"
       "</aside>"))

(defn- catalog-b-html
  []
  (str "<!DOCTYPE html><html><head><title>Catalog</title></head><body>"
       (chrome)
       "<main>"
       "<h1>Product Catalog</h1>"
       "<section class=\"results\" data-testid=\"results\">"
       (card "card-1001" "Aurora Lamp" "$42" (rating-widget "rating-1001" "4.5"))
       ;; card-1002 retained anchor, CHANGED price.
       (card "card-1002" "Beacon Clock" "$22 sale" "")
       ;; card-1003 DISAPPEARED: hidden, so sieve.js filters it out.
       "<article class=\"card\" data-testid=\"card-1003\" style=\"display:none\">"
       "<div class=\"card-body\"><span class=\"title\">Cinder Mug</span>"
       "<span class=\"price\">$14</span></div></article>"
       "</section>"
       (quickview-modal-html)
       "</main></body></html>"))

;; -----------------------------------------------------------------------------
;; Handlers + registration
;; -----------------------------------------------------------------------------

(defn- get-catalog-a
  [_request _session-atom _users _behaviors & _ctx]
  (handler/html-response 200 (catalog-a-html)))

(defn- get-catalog-b
  [_request _session-atom _users _behaviors & _ctx]
  (handler/html-response 200 (catalog-b-html)))

(pages/defpage :sieve-catalog
  {:routes [["GET" "/sieve/catalog" :get-catalog-a]
            ["GET" "/sieve/catalog/quickview" :get-catalog-b]]
   :handlers {:get-catalog-a get-catalog-a
              :get-catalog-b get-catalog-b}})
