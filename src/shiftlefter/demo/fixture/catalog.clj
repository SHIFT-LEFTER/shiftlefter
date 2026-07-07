(ns shiftlefter.demo.fixture.catalog
  "Parent-anchored product dashboard for the nested-addressing examples
   (sl-1ps example 06).

   Serves GET /catalog: ONE reused `ProductCard` component rendered under three
   sections with DIFFERENT container markup but IDENTICAL inner markup — the
   Amazon-ProductCard pattern (design §7.3). The card declares no `:root`; each
   parent collection supplies its own `:selector` to locate instances:

   - `.featured .fbt-item`
   - `.sidebar  .recs-card`
   - `.results  .s-result-item`

   The card's inner selectors (`.title`, `.price`) are the same everywhere; only
   the container differs, and only the parent knows it — which is exactly what
   self-rooting cannot express and parent-anchoring can (§7.4). Each card also
   carries a nested `.rating` region (`.stars`) to exercise nested descent, and
   the sidebar interleaves a `.promo` banner to exercise heterogeneous-cell
   exclusion by precise `:selector` (§7.7).

   No auth — static GET content. Register with `:catalog`."
  (:require [shiftlefter.demo.fixture.pages :as pages]
            [shiftlefter.demo.fixture.handler :as handler]))

;; -----------------------------------------------------------------------------
;; HTML
;; -----------------------------------------------------------------------------

(defn- card
  "A ProductCard with a given container class. Inner markup is identical
   regardless of container — only the wrapper class varies by section."
  [wrapper-class title price stars]
  (str "<div class=\"" wrapper-class "\">"
       "<a class=\"title\">" title "</a>"
       "<span class=\"price\">" price "</span>"
       "<div class=\"rating\"><span class=\"stars\">" stars "</span></div>"
       "</div>"))

(defn- catalog-html
  []
  (str "<!DOCTYPE html>
<html>
<head><title>Catalog</title></head>
<body>
  <h1>Catalog</h1>

  <section class=\"featured\">
    " (card "fbt-item" "Widget A" "$10" "4.5") "
    " (card "fbt-item" "Widget B" "$20" "4.0") "
  </section>

  <section class=\"sidebar\">
    " (card "recs-card" "Gadget X" "$30" "3.5") "
    <div class=\"promo\">Promo: free shipping</div>
    " (card "recs-card" "Gadget Y" "$40" "5.0") "
  </section>

  <section class=\"results\">
    " (card "s-result-item" "Thing 1" "$50" "2.0") "
    " (card "s-result-item" "Thing 2" "$60" "4.2") "
    " (card "s-result-item" "Thing 3" "$70" "3.8") "
  </section>
</body>
</html>"))

;; -----------------------------------------------------------------------------
;; Handler + registration
;; -----------------------------------------------------------------------------

(defn- get-catalog
  [_request _session-atom _users _behaviors & _ctx]
  (handler/html-response 200 (catalog-html)))

(pages/defpage :catalog
  {:routes [["GET" "/catalog" :get-catalog]]
   :handlers {:get-catalog get-catalog}})
