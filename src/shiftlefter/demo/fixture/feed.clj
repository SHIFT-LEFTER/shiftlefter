(ns shiftlefter.demo.fixture.feed
  "Self-rooted feed page for the nested-addressing examples (sl-1ps example 05).

   Serves GET /feed: a generic discussion site with two regions, both built
   from one consistent-markup `Post` component (`article[data-testid='post']`)
   that self-locates via its `:root` — the self-rooted anchoring model
   (design §7.2).

   - `section[data-testid='feed']` — a FLAT timeline of top-level posts with
     interleaved ads (`div[data-testid='ad']`). Exercises [n]/[-n]/[*] indexing
     and heterogeneous-cell exclusion (§7.7): the precise `article[data-testid=
     'post']` selector skips the ad cells by type.
   - `section[data-testid='thread']` — one expanded post that QUOTES another
     post (recursion: a Post inside a Post) and carries COMMENTS (a cross-type
     child that shares the `[data-testid='author']` binding). Exercises the
     nearest-enclosing-instance rule (§8.1): the quoted post is not one of the
     thread's top-level posts, and the post's own author is not the quote's or
     a comment's author.

   No auth — both regions are static GET content. Register with `:feed`."
  (:require [shiftlefter.demo.fixture.pages :as pages]
            [shiftlefter.demo.fixture.handler :as handler]))

;; -----------------------------------------------------------------------------
;; HTML
;; -----------------------------------------------------------------------------

(defn- post
  "A top-level post article: author + content, no nesting."
  [author content]
  (str "<article data-testid=\"post\">"
       "<span data-testid=\"author\">" author "</span>"
       "<div data-testid=\"content\">" content "</div>"
       "</article>"))

(defn- ad
  "A heterogeneous non-post cell that the post selector must exclude (§7.7)."
  [text]
  (str "<div data-testid=\"ad\">" text "</div>"))

(defn- comment-el
  "A comment: a cross-type child that reuses the author binding (§8.1)."
  [author body]
  (str "<div data-testid=\"comment\">"
       "<span data-testid=\"author\">" author "</span>"
       "<div data-testid=\"body\">" body "</div>"
       "</div>"))

(defn- feed-html
  []
  (str "<!DOCTYPE html>
<html>
<head><title>Feed</title></head>
<body>
  <h1>Discussion</h1>

  <!-- FLAT timeline — self-rooted Post, ads interleaved, no nesting. -->
  <section data-testid=\"feed\">
    " (post "Alice" "Post by Alice") "
    " (ad "Sponsored: buy now") "
    " (post "Bob" "Post by Bob") "
    " (post "Carol" "Post by Carol") "
    " (ad "Sponsored: sign up") "
    " (post "Dave" "Post by Dave") "
  </section>

  <!-- EXPANDED thread — one post quoting another (recursion) plus comments
       (cross-type). Exercises §8.1 nearest-enclosing-instance pruning. -->
  <section data-testid=\"thread\">
    <article data-testid=\"post\">
      <span data-testid=\"author\">Alice</span>
      <div data-testid=\"content\">Check this out
        " (post "Zoe" "Original by Zoe") "
      </div>
      " (comment-el "Bob" "Agreed") "
      " (comment-el "Carol" "Nice") "
    </article>
  </section>
</body>
</html>"))

;; -----------------------------------------------------------------------------
;; Handler + registration
;; -----------------------------------------------------------------------------

(defn- get-feed
  [_request _session-atom _users _behaviors & _ctx]
  (handler/html-response 200 (feed-html)))

(pages/defpage :feed
  {:routes [["GET" "/feed" :get-feed]]
   :handlers {:get-feed get-feed}})
