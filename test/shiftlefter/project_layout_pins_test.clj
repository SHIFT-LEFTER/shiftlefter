(ns shiftlefter.project-layout-pins-test
  "Pin guard for docs/project-layout.md § Verification appendix (sl-nm9o
   W15). The appendix cites code by `file:line`, and those pins drifted
   silently three times in one release as config.clj grew. The durable fix
   is the same family as the codox version stamp and the exit-contract
   guard: a mechanical check that every cited range still contains what it
   claims to point at.

   The cell grammar the guard reads: every pin is a backticked
   `path:L` or `path:L1-L2` token IMMEDIATELY followed by its anchor in
   parentheses — `src/shiftlefter/config.clj:633` (`known-top-level-keys`)
   — where the anchor is literal text (a symbol for code, a quoted phrase
   for shell and doc lines) that must occur somewhere in lines L1..L2 of
   that file. Prose cells stay prose.

   Three checks per pin: the file exists; the range is inside the file; the
   anchor occurs within the range. A fourth check closes the guard hole: a
   bare `path:digits` pin with no anchor is refused, so no unguarded pin can
   be added later.

   Flap-proof by construction: pure reads of committed files (docs/, src/,
   bin/, release/ are all in the public export's allowlist, so this
   hard-asserts everywhere), no timing, no environment. It reds only when a
   cited range no longer contains its anchor — which is exactly drift — and
   a line shift that keeps the anchor inside the range passes. The failure
   message names the row, the pin, and the nearest line where the anchor
   DOES occur, so the remedy is a re-pin, never a hunt."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def appendix-path "docs/project-layout.md")

(def ^:private anchored-pin-re
  "`path:from(-to)?` (`anchor`) — path is a repo-relative file (no spaces,
   no colons); the anchor is anything up to the closing backtick."
  #"`([^`\s:]+):(\d+)(?:-(\d+))?` \(`([^`]+)`\)")

(def ^:private bare-pin-re
  "Any backticked file:line token — used to find pins the anchored regex did
   NOT claim (the guard-hole check). Also matches the `:41-59` shorthand a
   row might use for 'same file as the previous pin' — refused likewise."
  #"`(?:[^`\s:]+)?:\d+(?:-\d+)?(?:,\d+(?:-\d+)?)*`")

(defn appendix-rows
  "The table rows (lines starting with `|`, header and rule excluded) after
   the `## Verification appendix` heading."
  [doc-text]
  (let [after (second (str/split doc-text #"(?m)^## Verification appendix\s*$" 2))]
    (->> (str/split-lines (or after ""))
         (filter #(str/starts-with? % "|"))
         (remove #(re-matches #"\|\s*Claim\s*\|.*" %))
         (remove #(re-matches #"\|[-| ]+\|" %))
         vec)))

(defn parse-pins
  "Every anchored pin in one row: {:path :from :to :anchor}."
  [row]
  (vec (for [[_ path from to anchor] (re-seq anchored-pin-re row)]
         (let [from (parse-long from)]
           {:path path :from from :to (if to (parse-long to) from) :anchor anchor}))))

(defn unanchored-pins
  "Backticked file:line tokens in `row` that the anchored grammar did not
   claim — each is a guard hole."
  [row]
  (let [claimed (set (map first (re-seq anchored-pin-re row)))
        claimed-heads (set (map #(second (re-find #"^(`[^`]+`)" %)) claimed))]
    (vec (remove claimed-heads (re-seq bare-pin-re row)))))

(defn check-pin
  "nil when the pin holds, else a one-line problem naming the fix.
   `read-lines` is (fn [path] -> vector of lines or nil) — injected so the
   red-side test can feed a literal file."
  [read-lines {:keys [path from to anchor]}]
  (if-let [lines (read-lines path)]
    (let [n (count lines)]
      (cond
        (> to n)
        (str path ":" from "-" to " is past the end of the file (" n " lines)")

        (some #(str/includes? % anchor) (subvec lines (dec from) to))
        nil

        :else
        (let [hits (keep-indexed (fn [i l] (when (str/includes? l anchor) (inc i))) lines)]
          (str path ":" from (when (not= from to) (str "-" to))
               " no longer contains `" anchor "`"
               (if (seq hits)
                 (str " — it is now at line" (when (next hits) "s") " "
                      (str/join ", " (take 5 hits)) "; re-pin")
                 " — the anchor text is gone from the file; re-anchor")))))
    (str path " does not exist")))

(defn appendix-problems
  "All problems across the appendix: drifted pins and unanchored pins,
   each prefixed by the row's claim text."
  [read-lines doc-text]
  (vec (for [row (appendix-rows doc-text)
             :let [claim (str/trim (second (str/split row #"\|" 3)))]
             problem (concat
                      (keep #(check-pin read-lines %) (parse-pins row))
                      (map #(str "unanchored pin " % " — every pin needs a (`anchor`)")
                           (unanchored-pins row)))]
         (str "[" claim "] " problem))))

(defn- repo-lines [path]
  (let [f (io/file path)]
    (when (.exists f) (vec (str/split-lines (slurp f))))))

;; ---------------------------------------------------------------------------
;; Red side first (guard-family law): a real-messy literal — one of the
;; appendix's own rows with its range shifted off the symbol, one bare pin.
;; ---------------------------------------------------------------------------

(def ^:private drifted-fixture
  (str "## Verification appendix\n\n"
       "Every table row above, verified at write time.\n\n"
       "| Claim | Evidence |\n|---|---|\n"
       "| `shiftlefter.edn` is data-only (no code-bearing config keys) | "
       "`src/shiftlefter/config.clj:527` (`known-top-level-keys`) — every species "
       "file cited above is the only code entry |\n"
       "| sieve store location | `src/shiftlefter/sieve/store.clj:9,20-33` |\n"
       "| bus event envelope spec | `src/shiftlefter/events.clj:65` (`::event-envelope`) |\n"))

(deftest red-side-fires
  (let [fake-config (vec (concat (repeat 526 ";; filler")
                                 [";; the symbol moved down"]
                                 (repeat 105 ";; filler")
                                 ["(def known-top-level-keys"]))
        fake-events (vec (concat (repeat 64 "x") ["(s/def ::event-envelope"]))
        read-lines {"src/shiftlefter/config.clj" fake-config
                    "src/shiftlefter/events.clj" fake-events}
        problems (appendix-problems read-lines drifted-fixture)]
    (testing "a range that no longer contains its anchor is reported with the
              nearest line, prefixed by the row's claim"
      (is (some #(str/includes? % "config.clj:527 no longer contains `known-top-level-keys` — it is now at line 633") problems)
          (pr-str problems))
      (is (some #(str/starts-with? % "[`shiftlefter.edn` is data-only") problems)))
    (testing "a bare pin with no anchor is a guard hole and is refused"
      (is (some #(str/includes? % "unanchored pin `src/shiftlefter/sieve/store.clj:9,20-33`") problems)
          (pr-str problems)))
    (testing "a pin that holds is silent"
      (is (not-any? #(str/includes? % "events.clj") problems)))
    (testing "a missing file is named"
      (is (= ["[x] nope.clj does not exist"]
             (appendix-problems {} "## Verification appendix\n| x | `nope.clj:1` (`y`) |\n"))))))

(deftest grammar-parses-the-real-shapes
  (testing "single line, range, and two pins in one cell"
    (is (= [{:path "a/b.clj" :from 3 :to 3 :anchor "foo"}
            {:path "c.sh" :from 10 :to 12 :anchor "TARGET_DIR=\"sl\""}]
           (parse-pins "| c | `a/b.clj:3` (`foo`); `c.sh:10-12` (`TARGET_DIR=\"sl\"`) |"))))
  (testing "prose cells contribute nothing and are not holes"
    (is (empty? (parse-pins "| c | demonstrated: `sl run` in `examples/04-sms-2fa` |")))
    (is (empty? (unanchored-pins "| c | demonstrated: `sl run` in `examples/04-sms-2fa` |")))))

;; ---------------------------------------------------------------------------
;; The live guard
;; ---------------------------------------------------------------------------

(deftest verification-appendix-pins-hold
  (let [doc (slurp appendix-path)
        rows (appendix-rows doc)
        problems (appendix-problems repo-lines doc)]
    (is (seq rows) "the appendix table was found")
    (is (pos? (count (mapcat parse-pins rows))) "the appendix carries anchored pins")
    (is (empty? problems)
        (str "docs/project-layout.md § Verification appendix has drifted — re-pin:\n"
             (str/join "\n" problems)))))
