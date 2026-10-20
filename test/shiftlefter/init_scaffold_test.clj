(ns shiftlefter.init-scaffold-test
  "Guards for the `sl init` scaffold (sl-sl-init-scaffold-cn7).

   The headline test is the sitting-ruled proof AC: a fresh scaffold must
   prove itself — `sl run --dry-run` on the scaffold output exits 0 (the
   starter feature + builtin web verbs + the seeded persona subjects bind
   clean through the real planning path). Same in-process dry-run vehicle as
   the example planning validators (sl-ybox pattern): no browser, no env
   gate, runs in the bare `./bin/kaocha unit`.

   The rest pin the ruled semantics: coarse bail on sl/shiftlefter.edn,
   AGENTS.md create/append/skip marker behavior, the self-ignoring
   .shiftlefter/.gitignore (folded sl-245y), and stanza reuse from k0s's
   single jar-resource source (bead AC2 — drift-couples init to it)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.init :as init]
            [shiftlefter.project-context :as pc]
            [shiftlefter.runner.core :as rc]))

(defn- with-temp-root
  "Run f with a fresh temp directory (string path); always cleans up."
  [f]
  (let [dir (str (fs/create-temp-dir {:prefix "sl-init-test"}))]
    (try
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(defn- dry-run-scaffold
  "The proof-AC vehicle: in-process planning dry-run against a scaffolded
   root, output captured (example-planning-validators pattern)."
  [root]
  (let [result (atom nil)]
    (with-out-str
      (reset! result
              (rc/execute! {:paths []
                            :project-context (pc/resolve
                                              {:config-path (str root "/sl/shiftlefter.edn")})
                            :dry-run true})))
    {:exit (:exit-code @result)
     :plan-count (count (:plans @result))}))

(defn- dry-run-scaffold-full-diagnostics
  "The same dry-run through the --edn envelope: returns the whole
   :diagnostics map. Lints and warns ride exit 0 (the ruled sl-femd /
   sl-hlkz behavior), so exit code alone cannot tell a clean scaffold from
   one whose config drifted — this can."
  [root]
  (let [out (with-out-str
              (rc/execute! {:paths []
                            :project-context (pc/resolve
                                              {:config-path (str root "/sl/shiftlefter.edn")})
                            :dry-run true
                            :edn true}))]
    (:diagnostics (edn/read-string out))))

(defn- dry-run-scaffold-diagnostics
  "The :svo-issues vector from the --edn dry-run (empty when planning
   bound everything clean)."
  [root]
  (vec (:svo-issues (dry-run-scaffold-full-diagnostics root))))

(deftest fresh-scaffold-proves-itself-via-dry-run
  (with-temp-root
    (fn [root]
      (let [{:keys [status written agents-file]} (init/scaffold! root)
            {:keys [exit plan-count]} (dry-run-scaffold root)]
        (testing "scaffold reports every ruled artifact — and by default
                  AGENTS.md is NOT one of them (sl-7dq5 F1 ownership
                  boundary: a user-owned agent file is written only on an
                  explicit ask)"
          (is (= :scaffolded status))
          (is (= :not-written agents-file))
          (is (not (fs/exists? (fs/path root "AGENTS.md"))))
          (is (= ["sl/shiftlefter.edn"
                  "sl/glossary/subjects.edn"
                  "sl/glossary/intents/login.edn.example"
                  "sl/features/login.feature"
                  ".shiftlefter/.gitignore"]
                 written)))
        (testing "the proof AC: sl run --dry-run exits 0 on the scaffold output"
          (is (= 0 exit))
          (is (pos? plan-count)))
        (testing "the scaffold's config draws ZERO config lints (sl-d3cz,
                  Warden W25 — lints ride exit 0, so the exit assertion
                  above cannot catch scaffold/lint drift; the :config-lints
                  key is absent from :diagnostics when clean)"
          (let [diagnostics (dry-run-scaffold-full-diagnostics root)]
            (is (nil? (:config-lints diagnostics))
                (pr-str (:config-lints diagnostics)))
            (is (empty? (:svo-issues diagnostics)))))))))

(deftest scaffold-seeds-a-bagless-adversary
  ;; sl-l53: :adversary ships in the default subject glossary as a standing
  ;; spur — identity + :desc ONLY. No capability bag, no personas: the
  ;; framework must not imply it knows what adversaries can do. Proof that
  ;; the spur is usable, not decorative: a scenario authored as :adversary
  ;; binds clean through the real planning path in Shifted mode.
  (with-temp-root
    (fn [root]
      (init/scaffold! root)
      (let [subjects (-> (slurp (fs/file root "sl" "glossary" "subjects.edn"))
                         edn/read-string
                         :subjects)
            adversary (:adversary subjects)]
        (testing "present, documented, and nothing but identity + :desc"
          (is (= #{:user :adversary} (set (keys subjects))))
          (is (= [:desc] (keys adversary)))
          (is (str/includes? (:desc adversary) "misuse cases"))
          (is (str/includes? (:desc adversary) "No capability bag")
              "the :desc says the bag is the project's to declare")
          (is (str/includes? (:desc adversary) "object-level")
              "the P7 guardrail: names what it does NOT cover"))
        (testing "the flavors ride the file's comment block, as subject types"
          (let [text (slurp (fs/file root "sl" "glossary" "subjects.edn"))]
            (is (str/includes? text "inside job"))
            (is (str/includes? text "outside job"))
            (is (str/includes? text "AD authenticated them; it didn't authorize them."))))
        (testing "a misuse case authored as :adversary binds clean"
          (let [feature (fs/file root "sl" "features" "login.feature")]
            (spit feature (str (slurp feature)
                               "\n  Scenario: An outsider probes the login page\n"
                               "    When :adversary opens the browser to 'https://example.invalid/admin'\n"
                               "    Then :adversary should see the title 'Login'\n"))
            (is (= [] (dry-run-scaffold-diagnostics root)))
            (let [{:keys [exit plan-count]} (dry-run-scaffold root)]
              (is (= 0 exit))
              (is (= 2 plan-count)))))))))

(deftest example-intent-is-loader-invisible
  ;; The .example suffix keeps the worked intent out of the *.edn glob
  ;; (loader.clj) — the scaffold must never ship an ACTIVE intent whose
  ;; locators point at a fictional page.
  (with-temp-root
    (fn [root]
      (init/scaffold! root)
      (is (= ["example"]
             (map fs/extension
                  (fs/list-dir (fs/path root "sl" "glossary" "intents"))))))))

(deftest example-intent-activates-and-teaches-a-binding-step
  ;; sl-st01 (Warden W11, sl-p1op): the worked exemplar's step must be a
  ;; shape that actually binds — the previous `{username}` form parsed as a
  ;; raw EDN locator (runtime :browser/locator-parse-error) AND collided
  ;; with the {name} data-plane syntax. Proof: do exactly what the file's
  ;; header tells a reader to do (rename to login.edn, use the worked
  ;; step) and dry-run the scaffold — planning must bind it clean.
  (with-temp-root
    (fn [root]
      (init/scaffold! root)
      (let [intents (fs/path root "sl" "glossary" "intents")
            example (slurp (fs/file (str intents) "login.edn.example"))
            worked-step (->> (str/split-lines example)
                             (keep #(re-find #";;\s+(And .+)$" %))
                             (map second)
                             first)
            feature (fs/file root "sl" "features" "login.feature")]
        (is (= "And :user/alice fills Login.username with 'alice'" worked-step)
            "the header's worked line is the intent-reference shape")
        (spit feature (str (slurp feature) "    " worked-step "\n"))
        (testing "control: before activation the name is an unresolvable
                  intent ref — a planning ERROR (exit 2) since sl-u2r0, the
                  issue under the exit-2 :planning block, naming where
                  intents are declared"
          (let [summary (edn/read-string
                         (with-out-str
                           (rc/execute! {:paths []
                                         :project-context (pc/resolve
                                                           {:config-path (str root "/sl/shiftlefter.edn")})
                                         :dry-run true
                                         :edn true})))
                issue (-> summary :planning :svo-issues first)]
            (is (= 2 (:run/exit-code summary)))
            (is (= [:svo/unknown-object] (mapv :type (-> summary :planning :svo-issues))))
            (is (= :error (:severity issue)))
            (is (str/ends-with? (str (-> issue :def-site :file)) "sl/glossary/intents")
                "no suggestion possible (nothing loaded): the def-site is the intents dir")))
        (fs/move (fs/path intents "login.edn.example") (fs/path intents "login.edn"))
        (testing "activated: the worked step resolves through the intent, zero diagnostics"
          (is (= [] (dry-run-scaffold-diagnostics root)))
          (let [{:keys [exit plan-count]} (dry-run-scaffold root)]
            (is (= 0 exit))
            (is (pos? plan-count))))))))

(deftest coarse-bail-on-existing-config
  (with-temp-root
    (fn [root]
      (fs/create-dirs (fs/path root "sl"))
      (spit (fs/file root "sl" "shiftlefter.edn") "{}")
      (let [report (init/scaffold! root)]
        (testing "bails whole-scaffold: no artifact is written, config untouched"
          (is (= :bailed (:status report)))
          (is (= "{}" (slurp (fs/file root "sl" "shiftlefter.edn"))))
          (is (not (fs/exists? (fs/path root "AGENTS.md"))))
          (is (not (fs/exists? (fs/path root ".shiftlefter"))))
          (is (not (fs/exists? (fs/path root "sl" "features")))))))))

(deftest second-run-bails-via-its-own-config
  (with-temp-root
    (fn [root]
      (is (= :scaffolded (:status (init/scaffold! root))))
      (is (= :bailed (:status (init/scaffold! root)))))))

(deftest agents-file-created-with-markers-and-reused-stanza
  (with-temp-root
    (fn [root]
      (let [{:keys [written agents-file]}
            (init/scaffold! root {:write-agents? true})]
        (is (= :created agents-file))
        (is (= "AGENTS.md" (last written))
            "the explicit ask reports the write as an artifact"))
      (let [content (slurp (fs/file root "AGENTS.md"))
            stanza (slurp (io/resource "shiftlefter/onramp/agents-breadcrumb.md"))]
        (is (str/includes? content init/onramp-begin-marker))
        (is (str/includes? content init/onramp-end-marker))
        (testing "stanza text is k0s's resource verbatim, never re-authored (AC2)"
          (is (str/includes? content (str/trim-newline stanza))))))))

(deftest agents-file-appended-when-present-without-marker
  (with-temp-root
    (fn [root]
      (let [prior "# My project\n\nExisting agent notes.\n"]
        (spit (fs/file root "AGENTS.md") prior)
        (let [{:keys [agents-file]} (init/scaffold! root {:write-agents? true})
              content (slurp (fs/file root "AGENTS.md"))]
          (is (= :appended agents-file))
          (testing "prior content intact, stanza appended after it"
            (is (str/starts-with? content prior))
            (is (str/includes? content init/onramp-begin-marker))))))))

(deftest agents-file-skipped-when-marker-present
  (with-temp-root
    (fn [root]
      (let [existing (str "# Mine\n" init/onramp-begin-marker
                          "\nhand-edited stanza\n" init/onramp-end-marker "\n")]
        (spit (fs/file root "AGENTS.md") existing)
        (let [{:keys [agents-file written]} (init/scaffold! root {:write-agents? true})]
          (is (= :skipped agents-file))
          (is (not-any? #{"AGENTS.md"} written))
          (testing "never edits an existing stanza — byte-identical"
            (is (= existing (slurp (fs/file root "AGENTS.md"))))))))))

(deftest bail-arm-write-agents-performs-only-the-trio-write
  ;; The one narrow, named exception to the coarse bail (Chair-approved
  ;; 08-28, sl-7dq5 F1): an EXPLICIT --write-agents on a bailed scaffold
  ;; performs only the marker-guarded AGENTS.md write — the offer's named
  ;; command must work after first init, or the surface lies about its one
  ;; control (the --no-breadcrumb sin).
  (with-temp-root
    (fn [root]
      (fs/create-dirs (fs/path root "sl"))
      (spit (fs/file root "sl" "shiftlefter.edn") "{}")
      (testing "bail WITHOUT the flag stays a pure no-op"
        (let [report (init/scaffold! root)]
          (is (= :bailed (:status report)))
          (is (not (contains? report :agents-file)))
          (is (not (fs/exists? (fs/path root "AGENTS.md"))))))
      (testing "bail WITH the flag writes the stanza, nothing else"
        (let [report (init/scaffold! root {:write-agents? true})]
          (is (= :bailed (:status report)))
          (is (= :created (:agents-file report)))
          (is (str/includes? (slurp (fs/file root "AGENTS.md"))
                             init/onramp-begin-marker))
          (is (= "{}" (slurp (fs/file root "sl" "shiftlefter.edn")))
              "config untouched")
          (is (not (fs/exists? (fs/path root "sl" "features")))
              "no scaffold artifact appears")))
      (testing "a second flagged run skips on the marker (trio semantics)"
        (is (= :skipped (:agents-file (init/scaffold! root {:write-agents? true}))))))))

(deftest shiftlefter-dir-self-ignores
  (with-temp-root
    (fn [root]
      (init/scaffold! root)
      (is (= "*\n" (slurp (fs/file root ".shiftlefter" ".gitignore")))))))
