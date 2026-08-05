(ns shiftlefter.attachments-test
  "The attachments contract's leaf ns (sl-l9nj): ref specs, the FULL-STRICT
   After-return claim shape, slug, the path portability contract, and the
   effectful pair (scenario dirs, claim finalization)."
  (:require [babashka.fs :as fs]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.attachments :as att]))

(def ^:private ref-min
  #:attachment{:kind :screenshot :path "attachments/x/shot.png"})

(def ^:private ref-full
  #:attachment{:kind :console
               :path "attachments/checkout-fails/console.log"
               :media-type "text/plain"
               :bytes 48213
               :source [:hook "grab-console"]
               :subject :user/alice
               :interface :web/main
               :duration-ms 84
               :excerpt "…last lines…"
               :error "partial read"})

(deftest ref-spec
  (is (s/valid? ::att/ref ref-min) "kind + path suffice")
  (is (s/valid? ::att/ref ref-full) "every design-doc key admitted")
  (is (not (s/valid? ::att/ref (dissoc ref-min :attachment/path))))
  (is (not (s/valid? ::att/ref (assoc ref-min :attachment/path ""))) "empty path rejected")
  (is (not (s/valid? ::att/ref (assoc ref-min :attachment/bytes -1))))
  (is (s/valid? ::att/ref (assoc ref-min :attachment/source :capture-policy))
      "sl-3q9r's provenance value is spec'd now")
  (is (not (s/valid? ::att/ref (assoc ref-min :attachment/source [:hook :not-a-string])))))

(deftest after-return-spec-is-full-strict
  (testing "the valid claim"
    (is (s/valid? ::att/after-return {:attachments [ref-min]}))
    (is (s/valid? ::att/after-return {:attachments []}) "empty claim is a claim"))
  (testing "malformed claims die loudly (Chair ruling: closed map)"
    (is (not (s/valid? ::att/after-return {:atachments [ref-min]})) "the typo'd key")
    (is (not (s/valid? ::att/after-return {:attachments [ref-min] :extra 1})) "stray sibling key")
    (is (not (s/valid? ::att/after-return {:attachments [(dissoc ref-min :attachment/kind)]}))
        "malformed ref inside the vector")
    (is (not (s/valid? ::att/after-return {:attachments ref-min})) "not a vector")
    (is (not (s/valid? ::att/after-return "screenshot.png")))
    (is (not (s/valid? ::att/after-return [:attachments [ref-min]])))
    (is (not (s/valid? ::att/after-return nil))
        "nil is handled BEFORE the spec (no claim) — the spec itself rejects it")))

(deftest slug-edges
  (is (= "checkout-fails-at-step-3" (att/slug "Checkout FAILS at step 3!")))
  (is (= "scenario" (att/slug "")) "blank falls back")
  (is (= "scenario" (att/slug nil)))
  (is (= "group" (att/slug "" "group")) "caller-chosen fallback")
  (is (= "scenario" (att/slug "———")) "all-symbol input falls back")
  (is (= "n-code" (att/slug "Ünïcode")) "non-ascii collapses, never leaks")
  (is (<= (count (att/slug (apply str (repeat 300 "a-")))) 60) "capped")
  (is (not (re-find #"-$" (att/slug (str (apply str (repeat 59 "a")) " x"))))
      "no trailing dash after the cap cut"))

(deftest path-portability-contract
  (testing "absolute under the group root -> relativized (forward slashes)"
    (is (= "attachments/x/shot.png"
           (:attachment/path (att/normalize-ref "/tmp/run"
                                                (assoc ref-min :attachment/path
                                                       "/tmp/run/attachments/x/shot.png"))))))
  (testing "absolute outside -> verbatim (non-portable, legible)"
    (let [r (att/normalize-ref "/tmp/run" (assoc ref-min :attachment/path "/etc/other.png"))]
      (is (= "/etc/other.png" (:attachment/path r)))
      (is (nil? (:attachment/error r)))))
  (testing "relative with a root -> verbatim (already group-root-relative)"
    (is (= ref-min (att/normalize-ref "/tmp/run" ref-min))))
  (testing "relative with NO root -> unverifiable, marked"
    (is (= "no run directory to resolve against"
           (:attachment/error (att/normalize-ref nil ref-min))))
    (is (= "hook said so"
           (:attachment/error (att/normalize-ref nil (assoc ref-min :attachment/error "hook said so"))))
        "a hook-reported error is never clobbered"))
  (testing "absolute with NO root -> verbatim, no error (checkable path)"
    (let [r (att/normalize-ref nil (assoc ref-min :attachment/path "/etc/other.png"))]
      (is (nil? (:attachment/error r))))))

(deftest resolve-ref-path-contract
  (is (= "/abs/p.png" (att/resolve-ref-path "/tmp/run" "/abs/p.png")))
  (is (= (str (fs/path "/tmp/run" "a/b.png")) (att/resolve-ref-path "/tmp/run" "a/b.png")))
  (is (nil? (att/resolve-ref-path nil "a/b.png"))))

(deftest stamp-source-always-overwrites
  (is (= [:hook "shot"] (:attachment/source (att/stamp-source "shot" ref-min))))
  (is (= [:hook "shot"]
         (:attachment/source (att/stamp-source "shot" (assoc ref-min :attachment/source :capture-policy))))
      "provenance is not self-asserted"))

(deftest excerpt-truncation
  (let [long-ref (assoc ref-min :attachment/excerpt (apply str (repeat 5000 "x")))
        out (att/truncate-excerpt long-ref)]
    (is (= att/excerpt-cap (count (:attachment/excerpt out))))
    (is (re-find #"\[excerpt truncated\]$" (:attachment/excerpt out)))
    (is (= ref-min (att/truncate-excerpt ref-min)) "no excerpt -> untouched")
    (is (= "short" (:attachment/excerpt (att/truncate-excerpt (assoc ref-min :attachment/excerpt "short")))))))

(deftest scenario-dir-creation-and-collision
  (let [root (str (fs/create-temp-dir))]
    (try
      (let [d1 (att/ensure-scenario-dir! root "Checkout fails" #uuid "11111111-2222-3333-4444-555555555555")
            d2 (att/ensure-scenario-dir! root "Checkout fails" #uuid "99999999-8888-7777-6666-000000000000")]
        (is (= (str (fs/path root "attachments" "checkout-fails")) d1))
        (is (= (str (fs/path root "attachments" "checkout-fails-99999999")) d2)
            "outline examples share a name; the pickle-id suffix disambiguates")
        (is (and (fs/directory? d1) (fs/directory? d2)))
        (is (= (str (fs/path root "attachments" "scenario"))
               (att/ensure-scenario-dir! root "Scenario!!!" nil))
            "trailing symbols strip in the slug")
        (is (= (att/ensure-scenario-dir! root "no pickle" nil)
               (att/ensure-scenario-dir! root "no pickle" nil))
            "nil pickle-id (hand-built plans) degrades to an idempotent shared dir"))
      (finally (fs/delete-tree root)))))

(deftest scenario-dir-parallel-smoke
  (let [root (str (fs/create-temp-dir))]
    (try
      (let [ids (repeatedly 8 random-uuid)
            dirs (vec (pmap #(att/ensure-scenario-dir! root "Same name" %) ids))]
        (is (= 8 (count (distinct dirs))) "concurrent same-name creations never share a dir")
        (is (every? fs/directory? dirs)))
      (finally (fs/delete-tree root)))))

(deftest finalize-refs-against-the-world
  (let [root (str (fs/create-temp-dir))]
    (try
      (let [dir (att/ensure-scenario-dir! root "checkout" #uuid "11111111-2222-3333-4444-555555555555")
            _ (spit (str dir "/shot.png") "fake-bytes")
            {:keys [refs missing]}
            (att/finalize-refs! root
                                [(assoc ref-min :attachment/path (str dir "/shot.png"))
                                 (assoc ref-min :attachment/path "attachments/checkout/gone.png")
                                 #:attachment{:kind :console :path "c.log" :error "screenshot timed out"}])]
        (is (= "attachments/checkout/shot.png" (:attachment/path (first refs)))
            "absolute path under root relativized")
        (is (= 10 (:attachment/bytes (first refs))) "bytes filled from the file when absent")
        (is (nil? (:attachment/error (first refs))))
        (is (= "file not found: attachments/checkout/gone.png" (:attachment/error (second refs))))
        (is (= ["attachments/checkout/gone.png"] missing) "exactly the newly-marked misses")
        (is (= "screenshot timed out" (:attachment/error (nth refs 2)))
            "self-reported failure skips the file check, never clobbered")
        (is (nil? (:attachment/bytes (nth refs 2)))))
      (finally (fs/delete-tree root)))))

(deftest finalize-refs-preserves-claimed-bytes
  (let [root (str (fs/create-temp-dir))]
    (try
      (let [_ (fs/create-dirs (fs/path root "attachments"))
            _ (spit (str (fs/path root "attachments" "a.png")) "1234")
            {:keys [refs]} (att/finalize-refs! root
                                               [(assoc ref-min
                                                       :attachment/path "attachments/a.png"
                                                       :attachment/bytes 999)])]
        (is (= 999 (:attachment/bytes (first refs))) "a hook-claimed byte count is not overwritten"))
      (finally (fs/delete-tree root)))))
