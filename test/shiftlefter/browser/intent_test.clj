(ns shiftlefter.browser.intent-test
  "Browser-aware intent target resolution — the flat `:nth-child` bugfix.

   These run WITHOUT a live browser: a stub IBrowser returns a known sequence of
   matches, and we assert resolution indexes by the Nth *match* (acceptance #3).
   The old mechanism rewrote the selector to `:nth-child(n)` — wrong on a
   heterogeneous sibling set. The fix is structural: resolution now hands
   query-all the *base* selector and takes the Nth of what it returns."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.browser.intent :as bi]
            [shiftlefter.browser.protocol :as bp]))

;; -----------------------------------------------------------------------------
;; Fixtures
;; -----------------------------------------------------------------------------

;; Loader lookup shape: {:lookup {[intent element] {:bindings {iface binding}}}}
(def ^:private intents
  {:lookup {["Region" "tweet"] {:bindings {:web {:css ".tweet"}}}}})

(defn- stub-browser
  "Minimal IBrowser stub: `query-all`/`query-all-pruned` return `matches` and
   record each call's args into `calls`. resolve-target calls no other method.
   With hand-built fixtures (no `:boundaries`) the resolver passes a blank
   boundary, so pruning is a no-op here — the Nth-match logic is what's tested."
  [matches calls]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify bp/IBrowser
    (query-all [_ scope locator]
      (swap! calls conj {:scope scope :locator locator})
      matches)
    (query-all-pruned [_ scope locator boundary-css]
      (swap! calls conj {:scope scope :locator locator :boundary boundary-css})
      matches)))

;; A heterogeneous feed: query-all (in the real adapter) returns ONLY the
;; elements matching `.tweet` — i.e. these three, with ads/separators already
;; excluded by the selector. The Nth of THIS sequence is the Nth tweet.
(def ^:private matches [{:el :tweet-0} {:el :tweet-1} {:el :tweet-2}])

;; -----------------------------------------------------------------------------
;; Non-indexed: stays pure, never touches the browser
;; -----------------------------------------------------------------------------

(deftest non-indexed-is-pure-query-target
  (testing "a bare reference resolves to {:q <base-binding>} without query-all"
    (let [calls (atom [])
          b (stub-browser matches calls)
          result (bi/resolve-target b intents "Region.tweet" :web)]
      (is (= {:q {:css ".tweet"}} result))
      (is (empty? @calls) "query-all must not be called for a non-indexed ref"))))

;; -----------------------------------------------------------------------------
;; Indexed: the Nth MATCH (the bugfix)
;; -----------------------------------------------------------------------------

(deftest positive-index-takes-nth-match
  (testing "[2] selects the 2nd MATCH, not the parent's 2nd child"
    (let [calls (atom [])
          b (stub-browser matches calls)
          result (bi/resolve-target b intents "Region.tweet[2]" :web)]
      (is (= {:el :tweet-1} result) "1-based: [2] -> index 1")
      (testing "query-all received the BASE selector — no :nth-child rewrite"
        (is (= 1 (count @calls)))
        (is (= :document (:scope (first @calls))))
        (is (= {:q {:css ".tweet"}} (:locator (first @calls)))
            "the latent :nth-child bug is gone: we index matches, not selectors")))))

(deftest first-index
  (let [b (stub-browser matches (atom []))]
    (is (= {:el :tweet-0} (bi/resolve-target b intents "Region.tweet[1]" :web)))))

(deftest negative-index-counts-from-end
  (let [b (stub-browser matches (atom []))]
    (is (= {:el :tweet-2} (bi/resolve-target b intents "Region.tweet[-1]" :web)))
    (is (= {:el :tweet-1} (bi/resolve-target b intents "Region.tweet[-2]" :web)))))

(deftest wildcard-returns-whole-collection
  (testing "[*] resolves to the full vector of element targets"
    (let [b (stub-browser matches (atom []))]
      (is (= matches (bi/resolve-target b intents "Region.tweet[*]" :web))))))

;; -----------------------------------------------------------------------------
;; Hard errors — never a silent nil (design §5)
;; -----------------------------------------------------------------------------

(deftest zero-index-is-a-hard-error
  (let [b (stub-browser matches (atom []))
        result (bi/resolve-target b intents "Region.tweet[0]" :web)]
    (is (some? (:errors result)))
    (is (= :intent/zero-index (-> result :errors first :type)))))

(deftest out-of-range-is-a-hard-error
  (testing "[5] with only 3 matches fails loud"
    (let [b (stub-browser matches (atom []))
          result (bi/resolve-target b intents "Region.tweet[5]" :web)]
      (is (some? (:errors result)))
      (is (= :intent/index-out-of-range (-> result :errors first :type)))
      (is (= 3 (-> result :errors first :data :count))))))

(deftest negative-out-of-range-is-a-hard-error
  (let [b (stub-browser matches (atom []))
        result (bi/resolve-target b intents "Region.tweet[-9]" :web)]
    (is (= :intent/index-out-of-range (-> result :errors first :type)))))

;; -----------------------------------------------------------------------------
;; Resolution failures surface as {:errors}
;; -----------------------------------------------------------------------------

(deftest unknown-element-errors
  (let [b (stub-browser matches (atom []))
        result (bi/resolve-target b intents "Region.nope[1]" :web)]
    (is (some? (:errors result)))))

(deftest parse-failure-errors
  (let [b (stub-browser matches (atom []))
        result (bi/resolve-target b intents "not a ref" :web)]
    (is (some? (:errors result)))))

;; -----------------------------------------------------------------------------
;; Nested resolution — the recursive scoped walk (sl-tl9, design §8)
;; -----------------------------------------------------------------------------
;;
;; A programmable stub: `query-all` answers from a routing fn of [scope locator],
;; so a test can model a real DOM tree (root -> instances -> nested instances ->
;; element) without a live browser. Each match is an opaque {:el <kw>} handle, as
;; the real adapter returns.

(defn- routed-browser
  "IBrowser stub whose query-all/query-all-pruned call `route` with the matched
   CSS selector and the scope; `route` returns the vector of {:el} matches.
   `route` models the DOM the live browser would return AFTER pruning, so the
   boundary arg is not applied here (the real filter is in-browser; the boundary
   threading itself is asserted in the §8.1 tests below)."
  [route]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify bp/IBrowser
    (query-all [_ scope locator]
      (route scope (:css (:q locator))))
    (query-all-pruned [_ scope locator _boundary-css]
      (route scope (:css (:q locator))))))

;; Bookmarks (rooted) -> tweet[] (self-rooted Tweet) -> quoted (nested Tweet)
;;                    -> author element. ProductCard is parent-anchored (no root).
(def ^:private nested-intents
  {:lookup {["Tweet" "author"] {:bindings {:web {:css "AUTHOR"}}}
            ["ProductCard" "price"] {:bindings {:web {:css "PRICE"}}}}
   :regions
   {"Bookmarks" {:root {:web {:css "BM"}}
                 :collections {:tweet {:intent "Tweet" :cardinality :many}}
                 :elements #{}}
    "Tweet" {:root {:web {:css "TWEET"}}
             :collections {:quoted {:intent "Tweet" :optional true :count {:max 1}}}
             :elements #{"author"}}
    "FBT" {:collections {:item {:intent "ProductCard"
                                :selector {:web {:css "FBTSEL"}}
                                :cardinality :many}}
           :elements #{}}
    "ProductCard" {:collections {} :elements #{"price"}}}})

(deftest self-rooted-recursion-to-element
  (testing "Bookmarks.tweet[2].quoted.author walks root -> Nth tweet -> quoted -> author"
    (let [route (fn [scope css]
                  (cond
                    (and (= scope :document) (= css "BM")) [{:el :bm}]
                    (and (= scope {:el :bm}) (= css "TWEET")) [{:el :t0} {:el :t1} {:el :t2}]
                    (and (= scope {:el :t1}) (= css "TWEET")) [{:el :quoted}]
                    (and (= scope {:el :quoted}) (= css "AUTHOR")) [{:el :author}]
                    :else []))
          result (bi/resolve-target (routed-browser route) nested-intents
                                    "Bookmarks.tweet[2].quoted.author" :web)]
      (is (= {:el :author} result)))))

(deftest terminal-collection-nth-instance
  (testing "Bookmarks.tweet[2] returns the 2nd tweet instance handle"
    (let [route (fn [scope css]
                  (cond
                    (and (= scope :document) (= css "BM")) [{:el :bm}]
                    (and (= scope {:el :bm}) (= css "TWEET")) [{:el :t0} {:el :t1} {:el :t2}]
                    :else []))]
      (is (= {:el :t1}
             (bi/resolve-target (routed-browser route) nested-intents
                                "Bookmarks.tweet[2]" :web))))))

(deftest parent-anchored-selector-overrides-root
  (testing "FBT.item[1].price uses the parent's :selector (ProductCard has no :root)"
    (let [route (fn [scope css]
                  (cond
                    ;; FBT has no root -> document scope; :selector locates items.
                    (and (= scope :document) (= css "FBTSEL")) [{:el :c0} {:el :c1}]
                    (and (= scope {:el :c0}) (= css "PRICE")) [{:el :p0}]
                    :else []))]
      (is (= {:el :p0}
             (bi/resolve-target (routed-browser route) nested-intents
                                "FBT.item[1].price" :web))))))

(deftest wildcard-fans-out-over-collection
  (testing "Bookmarks.tweet[*].author yields one author per tweet (fan-out)"
    (let [route (fn [scope css]
                  (cond
                    (and (= scope :document) (= css "BM")) [{:el :bm}]
                    (and (= scope {:el :bm}) (= css "TWEET")) [{:el :t0} {:el :t1}]
                    (and (= scope {:el :t0}) (= css "AUTHOR")) [{:el :a0}]
                    (and (= scope {:el :t1}) (= css "AUTHOR")) [{:el :a1}]
                    :else []))]
      (is (= [{:el :a0} {:el :a1}]
             (bi/resolve-target (routed-browser route) nested-intents
                                "Bookmarks.tweet[*].author" :web)))))

  (testing "Bookmarks.tweet[*] (terminal) returns the whole instance sequence"
    (let [route (fn [scope css]
                  (cond
                    (and (= scope :document) (= css "BM")) [{:el :bm}]
                    (and (= scope {:el :bm}) (= css "TWEET")) [{:el :t0} {:el :t1}]
                    :else []))]
      (is (= [{:el :t0} {:el :t1}]
             (bi/resolve-target (routed-browser route) nested-intents
                                "Bookmarks.tweet[*]" :web))))))

(deftest heterogeneous-feed-excludes-non-tweets-by-type
  (testing "A precise component selector matches ONLY tweets — ads/separators are
            never returned, so [n] indexes real tweets with no positional drift (§7.7)"
    ;; The real feed interleaves cellInnerDiv wrappers (ad, tweet, who-to-follow,
    ;; tweet, separator, tweet). query-all by the precise `article[...]` selector
    ;; (modeled as "TWEET") returns ONLY the three tweets — the resolver never sees
    ;; the non-tweet cells. The 2nd MATCH is the 2nd real tweet, not the 2nd cell.
    (let [route (fn [scope css]
                  (cond
                    (and (= scope :document) (= css "BM")) [{:el :bm}]
                    ;; ONLY tweets come back — the heterogeneous cells are excluded.
                    (and (= scope {:el :bm}) (= css "TWEET"))
                    [{:el :tweet-a} {:el :tweet-b} {:el :tweet-c}]
                    (and (= scope {:el :tweet-b}) (= css "AUTHOR")) [{:el :author-b}]
                    :else []))
          result (bi/resolve-target (routed-browser route) nested-intents
                                    "Bookmarks.tweet[2].author" :web)]
      (is (= {:el :author-b} result)
          "2nd tweet (not 2nd cell) -> its author; no zip misalignment"))))

(deftest ambiguous-collection-without-index-is-loud
  (testing "A :many collection with >1 instance and no index errors, never guesses"
    (let [route (fn [scope css]
                  (cond
                    (and (= scope :document) (= css "BM")) [{:el :bm}]
                    (and (= scope {:el :bm}) (= css "TWEET")) [{:el :t0} {:el :t1}]
                    :else []))
          result (bi/resolve-target (routed-browser route) nested-intents
                                    "Bookmarks.tweet.author" :web)]
      (is (= :intent/ambiguous-collection (-> result :errors first :type))))))

(deftest empty-collection-without-index-is-loud
  (testing "A no-index collection that matches nothing errors loudly"
    (let [route (fn [scope css]
                  (cond
                    (and (= scope :document) (= css "BM")) [{:el :bm}]
                    :else []))
          result (bi/resolve-target (routed-browser route) nested-intents
                                    "Bookmarks.tweet.author" :web)]
      (is (= :intent/empty-collection (-> result :errors first :type))))))

(deftest root-not-found-is-loud
  (testing "A rooted intent whose root matches nothing fails loud"
    (let [route (fn [_ _] [])
          result (bi/resolve-target (routed-browser route) nested-intents
                                    "Bookmarks.tweet[1].author" :web)]
      (is (= :intent/root-not-found (-> result :errors first :type))))))

(deftest unknown-segment-is-loud
  (testing "A segment that is neither a known collection nor element errors"
    (let [route (fn [scope css]
                  (if (and (= scope :document) (= css "BM")) [{:el :bm}] []))
          result (bi/resolve-target (routed-browser route) nested-intents
                                    "Bookmarks.nonsense" :web)]
      (is (= :intent/unknown-segment (-> result :errors first :type))))))

;; -----------------------------------------------------------------------------
;; §8.1 Nearest-enclosing-instance pruning — resolver threads the boundary (sl-h7h)
;; -----------------------------------------------------------------------------
;;
;; The closest()-filter itself runs in the live browser (verified by the
;; example-05 e2e). Here we verify the RESOLVER's contribution: it derives the
;; boundary union of the component being resolved WITHIN, and passes it to
;; query-all-pruned at both match sites — at the same one-call-per-hop cost.

;; nested-intents + the :boundaries the loader precomputes for it. Bookmarks'
;; only collection is `tweet` → Tweet's root "TWEET"; Tweet's only collection is
;; `quoted` → Tweet's root "TWEET"; FBT's `item` carries :selector "FBTSEL".
(def ^:private boundaried-intents
  (assoc nested-intents
         :boundaries {"Bookmarks"   {:web "TWEET"}
                      "Tweet"       {:web "TWEET"}
                      "FBT"         {:web "FBTSEL"}
                      "ProductCard" {:web ""}}))

(defn- recording-browser
  "IBrowser stub that records every call as {:op :scope :css :boundary} into
   `calls` and answers from `route`. Lets a test assert which boundary the
   resolver passed at each hop and how many browser calls a walk costs."
  [route calls]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify bp/IBrowser
    (query-all [_ scope locator]
      (swap! calls conj {:op :query-all :scope scope :css (:css (:q locator))})
      (route scope (:css (:q locator))))
    (query-all-pruned [_ scope locator boundary-css]
      (swap! calls conj {:op :query-all-pruned :scope scope
                         :css (:css (:q locator)) :boundary boundary-css})
      (route scope (:css (:q locator))))))

(def ^:private deep-route
  "Bookmarks(BM) -> tweet[t0,t1,t2] -> t1.quoted -> quoted.author."
  (fn [scope css]
    (cond
      (and (= scope :document) (= css "BM")) [{:el :bm}]
      (and (= scope {:el :bm}) (= css "TWEET")) [{:el :t0} {:el :t1} {:el :t2}]
      (and (= scope {:el :t1}) (= css "TWEET")) [{:el :quoted}]
      (and (= scope {:el :quoted}) (= css "AUTHOR")) [{:el :author}]
      :else [])))

(deftest pruning-threads-boundary-at-every-match-site
  (testing "each collection/element hop receives the boundary union of the
            component it resolves within"
    (let [calls (atom [])
          result (bi/resolve-target (recording-browser deep-route calls)
                                    boundaried-intents
                                    "Bookmarks.tweet[2].quoted.author" :web)
          pruned (filterv #(= :query-all-pruned (:op %)) @calls)]
      (is (= {:el :author} result))
      ;; tweet (within Bookmarks), quoted (within Tweet), author (within Tweet):
      (is (= ["TWEET" "TWEET" "TWEET"] (mapv :boundary pruned))
          "boundary is the resolved-within component's collection union")
      (is (= [:quoted] (->> @calls (filter #(= "AUTHOR" (:css %))) (map :scope)
                            (map :el)))
          "author is sought inside the quoted instance (tweet[2].quoted)"))))

(deftest pruning-costs-one-browser-call-per-hop
  (testing "a 3-hop nested walk makes exactly one query per hop (+root) — the
            fused op adds no round trips (acceptance #5)"
    (let [calls (atom [])]
      (bi/resolve-target (recording-browser deep-route calls) boundaried-intents
                         "Bookmarks.tweet[2].quoted.author" :web)
      ;; root-scope (BM) + tweet + quoted + author = 4 calls, no extras.
      (is (= 4 (count @calls)))
      (is (= [:query-all :query-all-pruned :query-all-pruned :query-all-pruned]
             (mapv :op @calls))
          "root locates via query-all; the three nested hops prune"))))

(deftest flat-component-passes-blank-boundary
  (testing "a component with no collections prunes nothing (boundary blank) —
            ProductCard.price within FBT.item"
    (let [calls (atom [])
          route (fn [scope css]
                  (cond
                    (and (= scope :document) (= css "FBTSEL")) [{:el :c0} {:el :c1}]
                    (and (= scope {:el :c0}) (= css "PRICE")) [{:el :p0}]
                    :else []))
          result (bi/resolve-target (recording-browser route calls)
                                    boundaried-intents "FBT.item[1].price" :web)
          price-call (first (filter #(= "PRICE" (:css %)) @calls))]
      (is (= {:el :p0} result))
      ;; FBT.item uses FBT's boundary ("FBTSEL"); price is an element of
      ;; ProductCard, which declares no collections → blank boundary.
      (is (= "FBTSEL" (:boundary (first (filter #(= "FBTSEL" (:css %)) @calls)))))
      (is (= "" (:boundary price-call))
          "ProductCard has no collections → nothing to prune"))))

(deftest element-binding-must-be-css-under-active-boundary
  (testing "when the component declares collections, a non-CSS (XPath) element
            binding is a loud error, not a silent mis-prune"
    (let [xpath-intents
          {:lookup {["Tweet" "author"] {:bindings {:web {:xpath "//author"}}}}
           :regions {"Tweet" {:root {:web {:css "TWEET"}}
                              :collections {:quoted {:intent "Tweet"}}
                              :elements #{"author"}}}
           :boundaries {"Tweet" {:web "TWEET"}}}
          route (fn [scope css]
                  (cond
                    (and (= scope :document) (= css "TWEET")) [{:el :t0}]
                    :else []))
          result (bi/resolve-target (routed-browser route) xpath-intents
                                    "Tweet.author" :web)]
      (is (= :intent/element-binding-not-css (-> result :errors first :type))))))
