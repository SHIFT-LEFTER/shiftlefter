(ns shiftlefter.svo.grammar-table-test
  "Table-driven tests for the ratified grammar-markers table (sl-2sj6).

   The canonical fixture is test/resources/grammar_markers_table.edn —
   in-repo so every checkout (CI included) runs the full behavioral
   suite deterministically. The wiki file
   _docs/wiki/decisions/grammar-markers.md is the LAW; the drift test at
   the bottom parses it and compares example/status cells against the
   fixture when _docs resolves, and self-skips green when it doesn't
   (the ChromeDriver-gated e2e pattern).

   Every :case in the fixture maps to one behavioral assertion here:
   pattern admission (match/no-match), plan validation (always-on :key
   errors, strict-mode :text silence), and exec normalization — the
   exec/lint agreement the ka80 lesson demands."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [etaoin.keys]
            [shiftlefter.stepengine.bind :as bind]
            [shiftlefter.stepengine.bindings :as bindings]
            [shiftlefter.browser.url-match :as url-match]
            [shiftlefter.intent.loader :as loader]
            [shiftlefter.stepengine.hints :as hints]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.svo.bindings-lint :as bindings-lint]
            [shiftlefter.svo.glossary :as glossary]
            [shiftlefter.svo.keys :as svo-keys]
            [shiftlefter.svo.validate :as validate]
            [shiftlefter.stepdefs.browser]))

(def ^:private fixture-path "test/resources/grammar_markers_table.edn")
(def ^:private wiki-path "_docs/wiki/decisions/grammar-markers.md")

(def ^:private table (edn/read-string (slurp fixture-path)))

(defn- rows [section] (get-in table [:sections section :rows]))

(use-fixtures :once
  (fn [f]
    (registry/clear-registry!)
    (require 'shiftlefter.stepdefs.browser :reload)
    (require 'shiftlefter.stepdefs.sms :reload)
    (f)))

;; -----------------------------------------------------------------------------
;; Harness
;; -----------------------------------------------------------------------------

(defn- find-stepdef [text]
  (first (filter #(re-matches (:pattern %) text) (registry/all-stepdefs))))

(defn- captures-of [stepdef text]
  (vec (rest (re-matches (:pattern stepdef) text))))

(defn- validate-object-issues
  "Run SVO validation for a matched step's extracted object under the
   given enforcement, returning only O-slot issue types (subject noise
   from the bare test glossary is irrelevant here)."
  [stepdef text enforcement]
  (let [g (glossary/load-default-glossaries)
        captures (captures-of stepdef text)
        svo-meta (:svo (:metadata stepdef))
        object-ref (:object svo-meta)
        ;; :$N placeholder -> capture (1-based)
        object (when object-ref
                 (nth captures (dec (parse-long (subs (name object-ref) 1)))))
        svo {:subject :user :interface :web
             :verb (:verb svo-meta) :frame (:frame svo-meta)
             :object object}
        result (validate/validate-svo g {:web {:type :web}} svo
                                      {:unknown-object enforcement})]
    (filterv #(#{:svo/unknown-object :svo/raw-locator-disallowed
                 :svo/unknown-key :svo/invalid-key-literal}
               (:type %))
             (:issues result))))

(defn- normalize
  "Replicate the engine's exec-seam normalization for a matched step."
  [stepdef text ctx]
  (let [captures (captures-of stepdef text)
        kinds (bind/default-slot-kinds (:metadata stepdef) (count captures))]
    (bindings/normalize-captures captures kinds ctx)))

(defn- lint-plan
  "Run the bindings lint over a single-step plan built from live
   bind-time machinery (slot kinds stamped exactly as the engine
   stamps them) — the lint half of the exec/lint agreement tests."
  ([stepdef text] (lint-plan stepdef text nil))
  ([stepdef text intents]
  (let [captures (captures-of stepdef text)
        kinds (bind/default-slot-kinds (:metadata stepdef) (count captures))
        plan {:plan/pickle {:pickle/source-file "grammar-table-test"}
              :plan/hooks []
              :plan/steps [{:status :matched
                            :step {:step/text text
                                   :step/id "row"
                                   :step/location {:line 1 :column 1}}
                            :binding {:captures captures
                                      :slot-kinds kinds
                                      :metadata (:metadata stepdef)}}]}]
    (bindings-lint/check-plans [plan] intents))))

;; -----------------------------------------------------------------------------
;; Element slot rows (:object-kind :intent) — sl-s04n
;; -----------------------------------------------------------------------------

(defn- bind-text
  "Bind a bare pickle step built from `text` against the live registry."
  [text]
  (bind/bind-step {:step/id (java.util.UUID/randomUUID) :step/text text}
                  (registry/all-stepdefs)))

(deftest element-slot-ref-rows
  (doseq [{:keys [step-text] :as row} (rows :element-slot)
          :when (#{:ref-resolves :typo-ref-matches} (:case row))]
    (testing (str (:case row) ": " step-text)
      (is (some? (find-stepdef step-text))
          "bare refs (valid or typo'd) are admitted; resolution is validation's job"))))

(deftest element-slot-quoted-ref-hint-row
  (let [{:keys [step-text repaired]}
        (first (filter #(= :quoted-ref-hint (:case %)) (rows :element-slot)))]
    (testing "quoted ref in a structure-only slot: no match, taught by hint"
      (is (nil? (find-stepdef step-text)))
      (let [bound (bind-text step-text)]
        (is (= :undefined (:status bound)))
        (is (= :grammar/quoted-ref (-> bound :hint :type)))
        (is (= repaired (-> bound :hint :repaired-text))
            "the unquote repair proves the slot admits bare refs")))))

;; -----------------------------------------------------------------------------
;; Value slot rows ('…' | {token}) — sl-s04n hint rows + reserved forms
;; -----------------------------------------------------------------------------

(defn- value-row [case-kw]
  (first (filter #(= case-kw (:case %)) (rows :value-slot))))

(deftest value-slot-token-rows
  (doseq [case-kw [:value-token :uppercase-token]]
    (let [{:keys [step-text]} (value-row case-kw)]
      (is (some? (find-stepdef step-text)) (str case-kw " must match")))))

(deftest value-slot-token-no-producer-row
  (let [{:keys [step-text]} (value-row :token-no-producer)
        sd (find-stepdef step-text)]
    (is (= [[:bindings/consumed-without-producer :error]]
           (mapv (juxt :type :severity) (lint-plan sd step-text))))))

(deftest value-slot-quoted-token-literal-row
  (let [{:keys [step-text normalized]} (value-row :quoted-token-literal)
        sd (find-stepdef step-text)]
    (is (some? sd))
    (testing "'{code}' is the six literal characters, not a token"
      (is (= normalized (-> (normalize sd step-text {}) :ok (nth 2)))))))

(deftest value-slot-double-quote-inside-literal-row
  (let [{:keys [step-text normalized]} (value-row :double-quote-inside-literal)
        sd (find-stepdef step-text)]
    (is (some? sd) "a \" inside a single-quoted literal is an ordinary character")
    (is (= normalized (-> (normalize sd step-text {}) :ok (nth 2))))
    (testing "scanner false-positive pin: the inner quotes raise no hint cue"
      (is (nil? (hints/undefined-hint step-text (registry/all-stepdefs) nil))))))

(deftest value-slot-double-quotes-hint-row
  (let [{:keys [step-text repaired]} (value-row :double-quotes-hint)]
    (is (nil? (find-stepdef step-text)))
    (let [bound (bind-text step-text)]
      (is (= :grammar/double-quotes (-> bound :hint :type)))
      (is (= repaired (-> bound :hint :repaired-text))))))

(deftest value-slot-unbalanced-quote-hint-row
  (let [{:keys [step-text repaired]} (value-row :unbalanced-quote-hint)]
    (is (nil? (find-stepdef step-text)))
    (let [bound (bind-text step-text)]
      (is (= :grammar/unbalanced-quote (-> bound :hint :type)))
      (is (= repaired (-> bound :hint :repaired-text))))))

(deftest value-slot-reserved-rows
  ;; The reserved LAW (no match) is unchanged for both rows. Since sl-38fy
  ;; the bare-value row also gets the rematch-gated teaching hint — quoting
  ;; the tail proves the fills frame — superseding the option-A no-hint pin.
  ;; The hyphen-token row stays hint-free: {order-number} is a brace span,
  ;; so the tail zone holds no bare words to quote.
  (let [{:keys [step-text]} (value-row :hyphen-token)]
    (testing ":hyphen-token: reserved — no match, cue-free, no hint"
      (is (nil? (find-stepdef step-text)))
      (is (not (contains? (bind-text step-text) :hint)))))
  (let [{:keys [step-text]} (value-row :bare-value-reserved)]
    (testing ":bare-value-reserved: reserved — no match; bare-value hint
              fires with the quote-the-tail repair (sl-38fy)"
      (is (nil? (find-stepdef step-text)))
      (let [bound (bind-text step-text)]
        (is (= :grammar/bare-value (-> bound :hint :type)))
        (is (= ":alice fills Login.code with 'Welcome'"
               (-> bound :hint :repaired-text)))))))

(deftest value-slot-doubled-literal-rows
  (testing "sl-jlqz: '' inside a nonempty literal is one literal quote"
    (doseq [case-kw [:doubled-quote-literal :quadruple-quote-literal]]
      (let [{:keys [step-text normalized]} (value-row case-kw)
            sd (find-stepdef step-text)]
        (is (some? sd) (str case-kw " must match"))
        (is (= normalized (-> (normalize sd step-text {}) :ok (nth 2)))
            (str case-kw ": interior undoubles at the exec seam"))))))

(deftest value-slot-apostrophe-hint-row
  (let [{:keys [step-text repaired]} (value-row :apostrophe-in-literal-hint)]
    (testing "an undoubled apostrophe ends the literal: no match, taught by
              the sl-jlqz hint family with the doubled did-you-mean"
      (is (nil? (find-stepdef step-text)))
      (let [bound (bind-text step-text)]
        (is (= :grammar/apostrophe-in-literal (-> bound :hint :type)))
        (is (= repaired (-> bound :hint :repaired-text)))))))

(deftest value-slot-empty-literal-row
  (let [{:keys [step-text]} (value-row :empty-literal)]
    (testing "'' is reserved — no match, and the hint points at clears
              with the step's own element ref (fit-pass condition)"
      (is (nil? (find-stepdef step-text)))
      (let [bound (bind-text step-text)]
        (is (= :grammar/empty-literal (-> bound :hint :type)))
        (is (str/includes? (-> bound :hint :suggestion) "clears Login.code"))))))

;; -----------------------------------------------------------------------------
;; Location slot rows — both layers (sl-34ec completes the section)
;; -----------------------------------------------------------------------------

(defn- location-row [case-kw]
  (first (filter #(= case-kw (:case %)) (rows :location-slot))))

(deftest location-slot-admission-rows
  (testing "refs, tokens, and quoted forms all bind (resolution is exec's job)"
    (doseq [case-kw [:named-ref-navigate :no-location-ref :nav-root-relative
                     :nav-bare-word-literal :nav-doubled-literal :nav-token
                     :exactly-quoted-token :exactly-bare-word]]
      (let [{:keys [step-text]} (location-row case-kw)]
        (is (some? (find-stepdef step-text)) (str case-kw " must match"))))))

(deftest location-slot-literal-resolution-rows
  (testing "sl-34ec: schemeless literals RFC-resolve against :base-url"
    (doseq [case-kw [:nav-root-relative :nav-bare-word-literal :exactly-bare-word]]
      (let [{:keys [literal resolved]} (location-row case-kw)]
        (is (= resolved (:ok (url-match/resolve-literal literal "http://h:9092")))
            (str case-kw ": " literal " -> " resolved)))))
  (testing "without :base-url the exactly expectation keeps :not-a-full-url,
            message naming both exits (18b's conditional truth)"
    (let [{:keys [literal]} (location-row :exactly-bare-word)
          m (url-match/exact-match literal "http://h:9092/Login")]
      (is (= :not-a-full-url (:reason m)))
      (is (str/includes? (:message m) "add :base-url"))
      (is (str/includes? (:message m) "absolute URL")))))

(deftest location-slot-exactly-refs-reserved
  (let [{:keys [step-text reserved-text]} (location-row :region-ref-exactly-reserved)]
    (is (some? (find-stepdef step-text)) "region assertion admits bare refs")
    (is (nil? (find-stepdef reserved-text))
        "at-exactly: bare ref is plan-time no-match (reserved)")))

;; -----------------------------------------------------------------------------
;; Text slot rows (:object-kind :text)
;; -----------------------------------------------------------------------------

(deftest text-slot-quoted-literal-rows
  (doseq [{:keys [step-text normalized] :as row} (rows :text-slot)
          :when (#{:quoted-literal :ref-shaped-literal} (:case row))]
    (testing (str (:case row) ": " step-text)
      (let [sd (find-stepdef step-text)]
        (is (some? sd) "quoted text matches the :text-slot stepdef (title / on-page)")
        ;; The bead repro: strict AND warn stay silent on quoted text.
        (is (= [] (validate-object-issues sd step-text :strict)))
        (is (= [] (validate-object-issues sd step-text :warn)))
        ;; Exec: literal, even when ref-shaped.
        (is (= normalized (-> (normalize sd step-text {}) :ok (nth 1))))
        ;; Lint agreement: zero binding consumption for a quoted literal.
        (is (= [] (lint-plan sd step-text)))))))

(deftest text-slot-token-row
  (let [{:keys [step-text]} (first (filter #(= :token (:case %)) (rows :text-slot)))
        sd (find-stepdef step-text)]
    (is (some? sd) "token matches the title stepdef")
    (is (= [] (validate-object-issues sd step-text :strict)))
    (testing "exec: token resolves against a producer"
      (is (= "X17" (-> (normalize sd step-text {:sl/bindings {:orderNumber "X17"}})
                       :ok (nth 1)))))
    (testing "exec: unproduced token is a structured error"
      (is (= :bindings/unresolved
             (-> (normalize sd step-text {}) :error :type))))
    (testing "lint agreement: token consumption without producer is an error"
      (is (= [:bindings/consumed-without-producer]
             (mapv :type (lint-plan sd step-text)))))))

(deftest text-slot-bare-reserved-rows
  (doseq [{:keys [step-text]} (filter #(= :bare-reserved (:case %)) (rows :text-slot))]
    (testing (str "unquoted ref-shape in the text slot is RESERVED — no match: " step-text)
      (is (nil? (find-stepdef step-text))))))

;; sl-90x5: a {token} in the `should see O` position is NOT a text-slot
;; token — it is the :visible LOCATOR slot (locator-re admits any {...}).
;; The row pins that exactly one stepdef fires and it is the locator one by
;; frame identity, so the on-page :text frame never admits a token there
;; (which would make every such step an ambiguous bind, exit 2).
(deftest text-slot-token-reserved-locator-shape-rows
  (doseq [{:keys [step-text locator-frame]}
          (filter #(= :token-reserved-locator-shape (:case %)) (rows :text-slot))]
    (testing (str "token in the on-page position binds to the locator stepdef alone: " step-text)
      (let [matches (filter #(re-matches (:pattern %) step-text) (registry/all-stepdefs))]
        (is (= 1 (count matches)) "exactly one stepdef — never ambiguous")
        (is (= {:verb :see :frame locator-frame}
               (select-keys (get-in (first matches) [:metadata :svo]) [:verb :frame]))
            "and it is the :see locator frame, not :on-page")))))

;; -----------------------------------------------------------------------------
;; Key slot rows (:object-kind :key)
;; -----------------------------------------------------------------------------

(deftest key-slot-w3c-name-row
  (let [{:keys [step-text]} (first (filter #(= :w3c-name (:case %)) (rows :key-slot)))
        sd (find-stepdef step-text)]
    (is (some? sd) "W3C key name matches the press stepdef")
    (doseq [enforcement [:off :warn :strict]]
      (is (= [] (validate-object-issues sd step-text enforcement))))))

(deftest key-slot-typo-row
  (let [{:keys [step-text suggestion]} (first (filter #(= :typo (:case %)) (rows :key-slot)))
        sd (find-stepdef step-text)]
    (is (some? sd) "typo'd name still matches the pattern (validation rejects it)")
    (testing "always-on: plan error with did-you-mean even at :off"
      (doseq [enforcement [:off :warn :strict]]
        (let [[issue :as issues] (validate-object-issues sd step-text enforcement)]
          (is (= 1 (count issues)))
          (is (= :svo/unknown-key (:type issue)))
          (is (= :error (:severity issue)))
          (is (= suggestion (:suggestion issue))))))))

(deftest key-slot-quoted-single-row
  (let [{:keys [step-text key-sent]} (first (filter #(= :quoted-single (:case %)) (rows :key-slot)))
        sd (find-stepdef step-text)]
    (is (some? sd))
    (is (= [] (validate-object-issues sd step-text :off)))
    (testing ":key captures reach the stepdef raw (not engine-normalized)"
      (is (= (str "'" key-sent "'")
             (-> (normalize sd step-text {}) :ok (nth 1)))))))

(deftest key-slot-quoted-apostrophe-row
  (let [{:keys [step-text]} (first (filter #(= :quoted-apostrophe (:case %)) (rows :key-slot)))
        sd (find-stepdef step-text)]
    (is (some? sd) "'''' matches the press stepdef (sl-jlqz doubling)")
    (testing "always-on validation: the undoubled interior is one character"
      (doseq [enforcement [:off :warn :strict]]
        (is (= [] (validate-object-issues sd step-text enforcement)))))
    (testing ":key captures reach the stepdef raw (undoubling is the
              stepdef's own resolve step)"
      (is (= "''''" (-> (normalize sd step-text {}) :ok (nth 1)))))))

(deftest key-slot-quoted-multi-row
  (let [{:keys [step-text]} (first (filter #(= :quoted-multi (:case %)) (rows :key-slot)))
        sd (find-stepdef step-text)]
    (is (some? sd) "multi-char quoted matches the pattern so validation can teach")
    (testing "always-on plan error pointing at fills"
      (doseq [enforcement [:off :strict]]
        (let [[issue :as issues] (validate-object-issues sd step-text enforcement)]
          (is (= 1 (count issues)))
          (is (= :svo/invalid-key-literal (:type issue)))
          (is (= :error (:severity issue)))
          (is (str/includes? (:message issue) "fills")))))))

(deftest key-slot-chords-parked-row
  (let [{:keys [step-text]} (first (filter #(= :chords-parked (:case %)) (rows :key-slot)))]
    (testing "chords no longer match — the + space is unclaimed (sl-eecq)"
      (is (nil? (find-stepdef step-text))))))

(deftest key-names-are-w3c-values
  (testing "every unquoted surface name is a W3C KeyboardEvent.key value,
            except the single documented deviation (Space)"
    (let [w3c-values #{"Enter" "Tab" "Escape" "Backspace" "Delete" "Insert"
                       "Home" "End" "PageUp" "PageDown"
                       "ArrowUp" "ArrowDown" "ArrowLeft" "ArrowRight"
                       "F1" "F2" "F3" "F4" "F5" "F6" "F7" "F8" "F9" "F10" "F11" "F12"
                       "Shift" "Control" "Alt" "Meta"
                       "Pause" "Clear" "Help" "Cancel"}
          names (set svo-keys/key-names)
          deviations (remove w3c-values names)]
      (is (= ["Space"] (vec deviations))
          "the exception list stays at exactly one entry"))))

;; -----------------------------------------------------------------------------
;; Matcher slot rows (sl-gwru) — the frame-aware role matrix
;; -----------------------------------------------------------------------------

(defn- matcher-row [case-kw]
  (first (filter #(= case-kw (:case %)) (rows :matcher-slot))))

(defn- lint-types
  "Lint issue [type severity] pairs for one fixture row's step text."
  [step-text]
  (let [sd (find-stepdef step-text)]
    (is (some? sd) (str "stepdef match for: " step-text))
    (mapv (juxt :type :severity) (lint-plan sd step-text))))

(deftest matcher-capture-named-binds
  (let [{:keys [step-text]} (matcher-row :capture-named-binds)]
    ;; Named capture is the canonical produce path — its only issue is the
    ;; produced-never-consumed nudge (this plan has no consumer), never an
    ;; unnamed/assert-only error.
    (is (= [[:bindings/produced-never-consumed :warn]] (lint-types step-text)))))

(deftest matcher-capture-all-unnamed-error
  (let [{:keys [step-text]} (matcher-row :capture-all-unnamed-error)
        sd (find-stepdef step-text)
        issues (lint-plan sd step-text)]
    (is (= [[:bindings/unnamed-only-groups :error]]
           (mapv (juxt :type :severity) issues)))
    (is (str/includes? (:message (first issues)) "should see"))))

(deftest matcher-assert-only-silent
  (let [{:keys [step-text]} (matcher-row :assert-only-silent)]
    (is (= [] (lint-types step-text)))))

(deftest matcher-assert-only-named-error
  (let [{:keys [step-text]} (matcher-row :assert-only-named-error)
        sd (find-stepdef step-text)
        issues (lint-plan sd step-text)]
    (is (= [[:bindings/named-groups-in-assert-only :error]]
           (mapv (juxt :type :severity) issues)))
    (is (str/includes? (:message (first issues)) "captures"))))

(deftest matcher-receives-unnamed-notice
  (let [{:keys [step-text]} (matcher-row :receives-unnamed-notice)]
    (is (= [[:bindings/unnamed-only-groups :warn]] (lint-types step-text)))))

(deftest matcher-embedded-token-consumes
  (let [{:keys [step-text]} (matcher-row :embedded-token-consumes)]
    ;; {prev} has no producer in a single-step plan — the token is a
    ;; consumer (its named group also produces :c, unconsumed here).
    (is (= #{[:bindings/consumed-without-producer :error]
             [:bindings/produced-never-consumed :warn]}
           (set (lint-types step-text))))))

(deftest matcher-malformed-pattern-error
  (let [{:keys [step-text]} (matcher-row :malformed-pattern-error)]
    (is (= [[:bindings/invalid-pattern :error]] (lint-types step-text)))))

;; --- Declared shapes (sl-ectw) ---

(def ^:private shaped-intents
  "Checkout.total declares a :shape (web); Checkout.note none; Reset.smsCode
   declares one on :sms only."
  (delay
    (let [dir (fs/create-temp-dir)]
      (spit (str (fs/path dir "checkout.edn"))
            (str "{:intent \"Checkout\"\n"
                 " :elements {:total {:bindings {:web {:id \"total\"}}\n"
                 "                    :shape \"\\\\$(?<total>[0-9.]+)\"}\n"
                 "            :note {:bindings {:web {:id \"note\"}}}}}"))
      (spit (str (fs/path dir "reset.edn"))
            (str "{:intent \"Reset\"\n"
                 " :elements {:smsCode {:bindings {:sms {}}\n"
                 "                      :shape \"code is: (\\\\d{6})\"}}}"))
      (:ok (loader/load-all-intents (str dir))))))

(defn- shaped-lint-types [step-text]
  (let [sd (find-stepdef step-text)]
    (is (some? sd) (str "stepdef match for: " step-text))
    (mapv (juxt :type :severity) (lint-plan sd step-text @shaped-intents))))

(deftest matcher-capture-declared-shape
  (let [{:keys [step-text]} (matcher-row :capture-declared-shape)
        sd (find-stepdef step-text)
        issues (lint-plan sd step-text @shaped-intents)]
    ;; The shape's one group binds {total}; unconsumed here → the nudge,
    ;; pointing at the glossary line that declared it.
    (is (= [[:bindings/produced-never-consumed :warn]]
           (mapv (juxt :type :severity) issues)))
    (is (= :total (:name (first issues))))
    (is (= 2 (-> issues first :def-site :line)))))

(deftest matcher-capture-without-shape-error
  (let [{:keys [step-text]} (matcher-row :capture-without-shape-error)
        sd (find-stepdef step-text)
        issues (lint-plan sd step-text @shaped-intents)]
    (is (= [[:bindings/captures-without-shape :error]]
           (mapv (juxt :type :severity) issues)))
    (is (str/includes? (:message (first issues)) "matching /…/") "both exits named")))

(deftest matcher-shape-and-inline-error
  (let [{:keys [step-text]} (matcher-row :shape-and-inline-error)
        sd (find-stepdef step-text)
        issues (lint-plan sd step-text @shaped-intents)
        err (first (filter #(= :bindings/shape-and-inline (:type %)) issues))]
    (is (some? err))
    (is (= :error (:severity err)))
    (is (some? (:def-site err)) "both sites: the step location and the glossary line")))

(deftest matcher-receive-shape-ref-binds
  (let [{:keys [step-text]} (matcher-row :receive-shape-ref-binds)
        sd (find-stepdef step-text)
        issues (lint-plan sd step-text @shaped-intents)]
    (is (= [[:bindings/produced-never-consumed :warn]]
           (mapv (juxt :type :severity) issues)))
    (is (= :smsCode (:name (first issues))) "a single group binds to the element's own name")))

(deftest matcher-assert-only-shape-ref-silent
  (let [{:keys [step-text]} (matcher-row :assert-only-shape-ref-silent)]
    (is (= [] (shaped-lint-types step-text)))))

(deftest matcher-shape-ref-wrong-interface-error
  (let [{:keys [step-text]} (matcher-row :shape-ref-wrong-interface-error)]
    (is (= [[:bindings/shape-no-binding-for-interface :error]]
           (shaped-lint-types step-text)))))

;; -----------------------------------------------------------------------------
;; Grammar topic drift guard (sl-fcw0): the public doc's key list must
;; track the closed set in svo.keys — a shipped doc naming a key that
;; doesn't validate (or omitting one that does) is the doc lying.
;; -----------------------------------------------------------------------------

(deftest grammar-topic-key-list-in-sync
  (let [md (slurp (io/resource "shiftlefter/agent_docs/grammar.md"))
        f-key? #(re-matches #"F\d+" %)]
    (testing "every non-F key name appears backticked in the grammar topic"
      (doseq [k (remove f-key? svo-keys/key-names)]
        (is (str/includes? md (str "`" k "`"))
            (str "grammar.md is missing key name: " k))))
    (testing "the function keys are documented as the F1–F12 range"
      (is (str/includes? md "`F1`–`F12`"))
      (is (= 12 (count (filter f-key? svo-keys/key-names)))
          "the F-range shorthand assumes exactly F1..F12 in the set"))))

;; -----------------------------------------------------------------------------
;; Wiki-sync drift test — runs only where _docs resolves (dev machines);
;; self-skips green on a bare CI checkout. The in-repo EDN stays canonical
;; for behavior; this guards EDN <-> wiki agreement on example/status.
;; -----------------------------------------------------------------------------

(defn- parse-wiki-section-rows
  "Example/status cell pairs from the first markdown table after the
   section heading containing `heading-substr`. Backticks stripped."
  [md heading-substr]
  (let [lines (str/split-lines md)
        start (first (keep-indexed
                      (fn [i l] (when (and (str/starts-with? l "###")
                                           (str/includes? l heading-substr)) i))
                      lines))]
    (when start
      (->> (drop (inc start) lines)
           (take-while #(not (str/starts-with? % "###")))
           (filter #(str/starts-with? (str/trim %) "|"))
           (drop 2)                                     ; header + separator
           (keep (fn [l]
                   (let [cells (->> (str/split l #"\|")
                                    (map str/trim)
                                    (remove str/blank?)
                                    vec)]
                     (when (>= (count cells) 3)
                       [(str/replace (first cells) "`" "")
                        (last cells)]))))
           set))))

(deftest wiki-table-sync
  (let [wiki (io/file wiki-path)]
    (if-not (.exists wiki)
      (is true "_docs not present (bare checkout) — drift check self-skips")
      (let [md (slurp wiki)]
        (doseq [[section-key {:keys [wiki-heading] :as section}] (:sections table)]
          (testing (str "fixture matches wiki table: " section-key)
            (let [wiki-rows (parse-wiki-section-rows md wiki-heading)
                  fixture-rows (set (map (juxt :example :status) (:rows section)))]
              (is (some? wiki-rows) (str "wiki section found: " wiki-heading))
              (is (= wiki-rows fixture-rows)))))))))
