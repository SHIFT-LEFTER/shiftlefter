(ns shiftlefter.text
  "Neutral text-similarity substrate: the ONE Levenshtein + did-you-mean
   home (sl-raf4). Cross-layer infrastructure at a root address — the
   `shiftlefter.events` precedent — consumed by config, svo, stepengine,
   and projection alike. History: `svo.validate` (matrix impl, sl-yh7
   public `suggest-similar`) and `stepengine.annotations` (two-row impl,
   sl-563) each carried a private copy; this ns keeps the two-row DP and
   both families consume it.")

;; -----------------------------------------------------------------------------
;; Levenshtein Distance
;; -----------------------------------------------------------------------------

(defn levenshtein
  "Edit distance between two strings (insertions/deletions/substitutions
   each cost 1). Iterative two-row DP."
  [a b]
  (let [m (count a) n (count b)]
    (cond
      (zero? m) n
      (zero? n) m
      :else
      (loop [i 0 prev (vec (range (inc n)))]
        (if (= i m)
          (peek prev)
          (let [ai (.charAt ^String a i)
                curr (loop [j 0 row (transient [(inc i)])]
                       (if (= j n)
                         (persistent! row)
                         (let [bj (.charAt ^String b j)
                               cost (if (= ai bj) 0 1)
                               above (get prev (inc j))
                               left (nth row j)
                               diag (get prev j)]
                           (recur (inc j)
                                  (conj! row (min (inc above)
                                                  (inc left)
                                                  (+ diag cost)))))))]
            (recur (inc i) curr)))))))

;; -----------------------------------------------------------------------------
;; Did-You-Mean
;; -----------------------------------------------------------------------------

(defn keyword->str
  "Convert a keyword (or symbol) to its string form without leading colon.
   Handles namespaced keywords: `:user/alice` → `\"user/alice\"`. Any other
   value (a string key a user typed, a number) is `str`'d as-is (sl-q4cb,
   Warden W35): the suggester is a neutral substrate and must never throw
   on the very input it exists to diagnose."
  [kw]
  (cond
    (and (instance? clojure.lang.Named kw) (namespace kw))
    (str (namespace kw) "/" (name kw))

    (instance? clojure.lang.Named kw) (name kw)
    :else (str kw)))

(defn suggest-similar
  "Find the most similar candidate to `target`. Compares the full keyword
   string (including namespace); non-keyword targets and candidates are
   compared by their `str` form (sl-q4cb). Returns the best-matching
   candidate AS GIVEN if distance <= max-distance, else nil."
  [target candidates max-distance]
  (when (and target (seq candidates))
    (let [target-str (keyword->str target)
          scored (map (fn [kw]
                        {:keyword kw
                         :distance (levenshtein target-str (keyword->str kw))})
                      candidates)
          best (apply min-key :distance scored)]
      (when (<= (:distance best) max-distance)
        (:keyword best)))))
