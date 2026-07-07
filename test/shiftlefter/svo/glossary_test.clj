(ns shiftlefter.svo.glossary-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.svo.glossary :as glossary]
            [shiftlefter.test-helpers.log-capture :as log-capture]))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(def fixtures-path "test/fixtures/glossaries")

(defn fixture-path [filename]
  (str fixtures-path "/" filename))

;; -----------------------------------------------------------------------------
;; load-glossary Tests
;; -----------------------------------------------------------------------------

(deftest load-glossary-from-file
  (testing "loads valid subject glossary"
    (let [g (glossary/load-glossary (fixture-path "subjects.edn"))]
      (is (map? g))
      (is (contains? g :subjects))
      (is (= 4 (count (:subjects g))))
      (is (contains? (:subjects g) :user))
      (is (contains? (:subjects g) :admin))
      (is (contains? (:subjects g) :guest))
      (is (contains? (:subjects g) :system/test-setup))
      (is (= [:alice :bob] (get-in g [:subjects :user :instances])))
      (is (= [:pat :admin-banned] (get-in g [:subjects :admin :instances])))))

  (testing "loads valid verb glossary"
    (let [g (glossary/load-glossary (fixture-path "verbs-web-project.edn"))]
      (is (map? g))
      (is (= :web (:type g)))
      (is (contains? (:verbs g) :swipe))
      (is (contains? (:verbs g) :pinch))))

  (testing "returns error for missing file"
    (let [result (glossary/load-glossary "nonexistent.edn")]
      (is (= :glossary/file-not-found (:type result)))
      (is (string? (:message result)))
      (is (= "nonexistent.edn" (:path result))))))

;; -----------------------------------------------------------------------------
;; load-default-glossaries Tests
;; -----------------------------------------------------------------------------

(deftest load-default-glossaries-test
  (testing "loads framework defaults"
    (let [g (glossary/load-default-glossaries)]
      (is (map? g))
      (is (= {} (:subjects g)) "no default subjects")
      (is (contains? (:verbs g) :web) "has web verbs")
      (is (contains? (get-in g [:verbs :web]) :click))
      (is (contains? (get-in g [:verbs :web]) :fill))
      (is (contains? (get-in g [:verbs :web]) :navigate))))

  (testing "loads sms framework defaults alongside web (sl-ups)"
    (let [g (glossary/load-default-glossaries)]
      (is (contains? (:verbs g) :sms) "has sms verbs")
      (is (= #{:send :send-media :receive :see}
             (set (keys (get-in g [:verbs :sms]))))
          "ships exactly the four canonical SMS verbs"))))

(deftest load-default-verbs-test
  (testing "loads web verbs from classpath"
    (let [g (glossary/load-default-verbs :web)]
      (is (some? g))
      (is (= :web (:type g)))
      (is (contains? (:verbs g) :click))
      (is (contains? (:verbs g) :fill))))

  (testing "loads sms verbs from classpath (sl-ups)"
    (let [g (glossary/load-default-verbs :sms)]
      (is (some? g))
      (is (= :sms (:type g)))
      (is (contains? (:verbs g) :send))
      (is (contains? (:verbs g) :send-media))
      (is (contains? (:verbs g) :receive))
      (is (contains? (:verbs g) :see))
      ;; :receive carries three frames (smart-default, since-iso, within-last)
      (is (= #{:default :since-iso :within-last}
             (set (keys (get-in g [:verbs :receive :frames])))))
      ;; :see has only the :count frame in v1
      (is (= #{:count} (set (keys (get-in g [:verbs :see :frames])))))
      ;; :see :count uses :implicit-object since "messages" is not a locator
      (is (= :sms-messages (get-in g [:verbs :see :frames :count :implicit-object])))
      ;; Args order is fixed (matches stepdef regex group order)
      (is (= [:to-phone :body]
             (get-in g [:verbs :send :frames :to :args])))
      (is (= [:to-phone :match]
             (get-in g [:verbs :receive :frames :default :args])))
      (is (= [:count :to-phone]
             (get-in g [:verbs :see :frames :count :args])))))

  (testing "returns nil for unknown type"
    (is (nil? (glossary/load-default-verbs :unknown)))))

;; -----------------------------------------------------------------------------
;; merge-glossaries Tests
;; -----------------------------------------------------------------------------

(deftest merge-glossaries-test
  (testing "project extends defaults"
    (let [defaults {:subjects {:alice {:desc "default alice"}}
                    :verbs {:web {:click {:desc "click"}}}}
          project {:subjects {:bob {:desc "project bob"}}
                   :verbs {:web {:swipe {:desc "swipe"}}}}
          merged (glossary/merge-glossaries defaults project)]
      (is (= #{:alice :bob} (set (keys (:subjects merged)))))
      (is (= #{:click :swipe} (set (keys (get-in merged [:verbs :web])))))))

  (testing "project overrides with flag"
    (let [defaults {:subjects {:alice {:desc "default alice"}}
                    :verbs {:web {:click {:desc "click"}}}}
          project {:subjects {:bob {:desc "only bob"}}
                   :verbs {:web {:swipe {:desc "only swipe"}}}
                   :override-defaults true}
          merged (glossary/merge-glossaries defaults project)]
      (is (= #{:bob} (set (keys (:subjects merged)))))
      (is (= #{:swipe} (set (keys (get-in merged [:verbs :web])))))))

  (testing "project values override same keys"
    (let [defaults {:subjects {:alice {:desc "old desc"}}
                    :verbs {:web {:click {:desc "old click"}}}}
          project {:subjects {:alice {:desc "new desc"}}
                   :verbs {:web {:click {:desc "new click"}}}}
          merged (glossary/merge-glossaries defaults project)]
      (is (= "new desc" (get-in merged [:subjects :alice :desc])))
      (is (= "new click" (get-in merged [:verbs :web :click :desc]))))))

;; -----------------------------------------------------------------------------
;; load-all-glossaries Tests
;; -----------------------------------------------------------------------------

(deftest load-all-glossaries-test
  (testing "loads from config paths and merges with defaults"
    (let [config {:subjects (fixture-path "subjects.edn")
                  :verbs {:web (fixture-path "verbs-web-project.edn")}}
          g (glossary/load-all-glossaries config)]
      ;; Project subjects loaded (type keys)
      (is (contains? (:subjects g) :user))
      (is (contains? (:subjects g) :admin))
      ;; Instance index built
      (is (map? (:instance-index g)))
      (is (= :user (get (:instance-index g) :alice)))
      ;; Default web verbs present
      (is (contains? (get-in g [:verbs :web]) :click))
      (is (contains? (get-in g [:verbs :web]) :fill))
      ;; Project web verbs merged in
      (is (contains? (get-in g [:verbs :web]) :swipe))
      (is (contains? (get-in g [:verbs :web]) :pinch))))

  (testing "handles nil paths gracefully"
    (let [config {:subjects nil
                  :verbs {:web nil}}
          g (glossary/load-all-glossaries config)]
      ;; Still has defaults
      (is (= {} (:subjects g)))
      (is (contains? (get-in g [:verbs :web]) :click))))

  (testing "handles empty config"
    (let [g (glossary/load-all-glossaries {})]
      ;; Just defaults
      (is (= {} (:subjects g)))
      (is (contains? (get-in g [:verbs :web]) :click)))))

;; -----------------------------------------------------------------------------
;; Query Function Tests
;; -----------------------------------------------------------------------------

(deftest known-subject?-test
  (let [g {:subjects {:alice {:desc "alice"}
                      :admin {:desc "admin"}}}]
    (testing "returns true for known subjects"
      (is (true? (glossary/known-subject? g :alice)))
      (is (true? (glossary/known-subject? g :admin))))

    (testing "returns false for unknown subjects"
      (is (false? (glossary/known-subject? g :bob)))
      (is (false? (glossary/known-subject? g :alcie))))))

(deftest known-verb?-test
  (let [g {:verbs {:web {:click {:desc "click"}
                         :fill {:desc "fill"}}
                   :api {:get {:desc "get"}
                         :post {:desc "post"}}}}]
    (testing "returns true for known verbs"
      (is (true? (glossary/known-verb? g :web :click)))
      (is (true? (glossary/known-verb? g :web :fill)))
      (is (true? (glossary/known-verb? g :api :get))))

    (testing "returns false for unknown verbs"
      (is (false? (glossary/known-verb? g :web :smash)))
      (is (false? (glossary/known-verb? g :api :click))))

    (testing "returns false for unknown interface type"
      (is (false? (glossary/known-verb? g :sms :send))))))

(deftest known-subjects-test
  (let [g {:subjects {:alice {} :admin {} :guest {}}}]
    (testing "returns all subject keywords"
      (let [subjects (glossary/known-subjects g)]
        (is (= 3 (count subjects)))
        (is (= #{:alice :admin :guest} (set subjects))))))

  (testing "returns empty vector for no subjects"
    (is (= [] (glossary/known-subjects {:subjects {}})))))

(deftest known-verbs-test
  (let [g {:verbs {:web {:click {} :fill {} :see {}}
                   :api {:get {} :post {}}}}]
    (testing "returns verbs for interface type"
      (let [web-verbs (glossary/known-verbs g :web)]
        (is (= 3 (count web-verbs)))
        (is (= #{:click :fill :see} (set web-verbs))))
      (let [api-verbs (glossary/known-verbs g :api)]
        (is (= 2 (count api-verbs)))
        (is (= #{:get :post} (set api-verbs)))))

    (testing "returns empty vector for unknown type"
      (is (= [] (glossary/known-verbs g :sms))))))

(deftest subject-info-test
  (let [g {:subjects {:user {:desc "Standard customer" :role :customer
                             :instances [:alice :bob]}
                      :guest {:desc "Unauthenticated"}}
           :instance-index {:alice :user :bob :user}}]
    (testing "returns info map for type key"
      (let [info (glossary/subject-info g :user)]
        (is (= "Standard customer" (:desc info)))
        (is (= :customer (:role info)))))

    (testing "returns info map for qualified subject"
      (let [info (glossary/subject-info g :user/alice)]
        (is (= "Standard customer" (:desc info)))))

    (testing "returns info map for singleton"
      (let [info (glossary/subject-info g :guest)]
        (is (= "Unauthenticated" (:desc info)))))

    (testing "returns nil for unknown subject"
      (is (nil? (glossary/subject-info g :unknown))))))

(deftest verb-info-test
  (let [g {:verbs {:web {:click {:desc "Click element" :needs-locator true}}}}]
    (testing "returns info map for known verb"
      (let [info (glossary/verb-info g :web :click)]
        (is (= "Click element" (:desc info)))
        (is (true? (:needs-locator info)))))

    (testing "returns nil for unknown verb"
      (is (nil? (glossary/verb-info g :web :smash))))

    (testing "returns nil for unknown interface type"
      (is (nil? (glossary/verb-info g :api :click))))))

;; -----------------------------------------------------------------------------
;; Instance Index Tests
;; -----------------------------------------------------------------------------

(deftest build-instance-index-test
  (testing "builds reverse index from instances"
    (let [subjects {:user  {:desc "User" :instances [:alice :bob]}
                    :admin {:desc "Admin" :instances [:pat]}
                    :guest {:desc "Guest"}}
          result (glossary/build-instance-index subjects)]
      (is (= {:ok {:alice :user :bob :user :pat :admin}} result))))

  (testing "empty instances vector produces no entries"
    (let [subjects {:user {:desc "User" :instances []}
                    :guest {:desc "Guest"}}
          result (glossary/build-instance-index subjects)]
      (is (= {:ok {}} result))))

  (testing "no instances at all produces empty index"
    (let [subjects {:guest {:desc "Guest"}
                    :admin {:desc "Admin"}}
          result (glossary/build-instance-index subjects)]
      (is (= {:ok {}} result))))

  (testing "detects duplicate instance across types"
    (let [subjects {:user  {:desc "User" :instances [:alice :bob]}
                    :admin {:desc "Admin" :instances [:alice]}}
          result (glossary/build-instance-index subjects)]
      (is (:error result))
      (is (= :glossary/duplicate-instance (-> result :error :type)))
      (is (= :alice (-> result :error :instance)))))

  (testing "detects instance/type collision"
    (let [subjects {:user  {:desc "User" :instances [:admin]}
                    :admin {:desc "Admin"}}
          result (glossary/build-instance-index subjects)]
      (is (:error result))
      (is (= :glossary/instance-type-collision (-> result :error :type)))
      (is (= :admin (-> result :error :instance)))))

  (testing "detects non-keyword instances"
    (let [subjects {:user {:desc "User" :instances ["alice" "bob"]}}
          result (glossary/build-instance-index subjects)]
      (is (:error result))
      (is (= :glossary/invalid-instances (-> result :error :type))))))

;; -----------------------------------------------------------------------------
;; Subject Resolution Tests
;; -----------------------------------------------------------------------------

(def ^:private test-glossary
  "Standard test glossary with types, instances, and singletons."
  {:subjects {:user  {:desc "Standard application user"
                      :instances [:alice :bob :carol]}
              :admin {:desc "Administrative user"
                      :instances [:pat :admin-banned :admin-no-disk]}
              :guest {:desc "Unauthenticated visitor"}
              :test-harness/fixture-insertion {:desc "Creates test data fixtures"}}
   :instance-index {:alice :user :bob :user :carol :user
                    :pat :admin :admin-banned :admin :admin-no-disk :admin}
   :verbs {}})

(deftest resolve-subject-test
  (testing "resolves qualified subject"
    (let [result (glossary/resolve-subject test-glossary :user/alice)]
      (is (= :user (:type result)))
      (is (= :alice (:instance result)))
      (is (= :user/alice (:qualified result)))
      (is (= "Standard application user" (:desc result)))
      (is (nil? (:singleton? result)))))

  (testing "resolves qualified admin instance"
    (let [result (glossary/resolve-subject test-glossary :admin/admin-banned)]
      (is (= :admin (:type result)))
      (is (= :admin-banned (:instance result)))
      (is (= :admin/admin-banned (:qualified result)))))

  (testing "resolves singleton"
    (let [result (glossary/resolve-subject test-glossary :guest)]
      (is (= :guest (:type result)))
      (is (= :guest (:instance result)))
      (is (= :guest (:qualified result)))
      (is (= "Unauthenticated visitor" (:desc result)))
      (is (true? (:singleton? result)))))

  (testing "resolves namespaced singleton"
    (let [result (glossary/resolve-subject test-glossary :test-harness/fixture-insertion)]
      (is (some? result))
      (is (= :test-harness/fixture-insertion (:type result)))
      (is (= :fixture-insertion (:instance result)))
      (is (= "Creates test data fixtures" (:desc result)))))

  (testing "returns nil for unknown subject"
    (is (nil? (glossary/resolve-subject test-glossary :unknown))))

  (testing "returns nil for unknown instance under known type"
    (is (nil? (glossary/resolve-subject test-glossary :user/unknown))))

  (testing "returns nil for unknown type with instance syntax"
    (is (nil? (glossary/resolve-subject test-glossary :mystery/alice))))

  (testing "bare type with instances is not a valid standalone subject"
    (is (nil? (glossary/resolve-subject test-glossary :user))
        ":user has instances, so bare :user is not a subject"))

  (testing "bare instance resolves via index"
    (let [result (glossary/resolve-subject test-glossary :alice)]
      (is (= :user (:type result)))
      (is (= :alice (:instance result)))
      (is (= :user/alice (:qualified result))))))

;; -----------------------------------------------------------------------------
;; Query Function Tests (new)
;; -----------------------------------------------------------------------------

(deftest instances-of-type-test
  (testing "returns instances for a type"
    (is (= [:alice :bob :carol]
           (glossary/instances-of-type test-glossary :user))))

  (testing "returns nil for singleton type"
    (is (nil? (glossary/instances-of-type test-glossary :guest))))

  (testing "returns nil for unknown type"
    (is (nil? (glossary/instances-of-type test-glossary :unknown)))))

(deftest subject-type-test
  (testing "reverse lookup from instance to type"
    (is (= :user (glossary/subject-type test-glossary :alice)))
    (is (= :user (glossary/subject-type test-glossary :bob)))
    (is (= :admin (glossary/subject-type test-glossary :pat))))

  (testing "returns nil for unknown instance"
    (is (nil? (glossary/subject-type test-glossary :unknown)))))

(deftest all-types-test
  (testing "returns all type keywords"
    (let [types (glossary/all-types test-glossary)]
      (is (= #{:user :admin :guest :test-harness/fixture-insertion}
             (set types))))))

(deftest singleton?-test
  (testing "true for singleton types"
    (is (true? (glossary/singleton? test-glossary :guest)))
    (is (true? (glossary/singleton? test-glossary :test-harness/fixture-insertion))))

  (testing "false for types with instances"
    (is (false? (glossary/singleton? test-glossary :user)))
    (is (false? (glossary/singleton? test-glossary :admin))))

  (testing "false for unknown types"
    (is (false? (glossary/singleton? test-glossary :unknown)))))

(deftest all-subject-forms-test
  (testing "returns singleton type keys plus qualified instance forms"
    (let [forms (glossary/all-subject-forms test-glossary)]
      ;; Singleton type keys (resolvable standalone)
      (is (some #{:guest} forms))
      (is (some #{:test-harness/fixture-insertion} forms))
      ;; Qualified instances
      (is (some #{:user/alice} forms))
      (is (some #{:user/bob} forms))
      (is (some #{:admin/pat} forms))
      (is (some #{:admin/admin-banned} forms))))

  (testing "excludes bare types-with-instances — unresolvable, so listing or
            suggesting them misleads (sl-6e7p)"
    (let [forms (glossary/all-subject-forms test-glossary)]
      (is (not-any? #{:user} forms))
      (is (not-any? #{:admin} forms))
      ;; every listed form must actually resolve
      (is (every? #(glossary/known-subject? test-glossary %) forms)))))

;; -----------------------------------------------------------------------------
;; known-subject? with types/instances
;; -----------------------------------------------------------------------------

(deftest known-subject?-with-instances-test
  (testing "qualified subject is known"
    (is (true? (glossary/known-subject? test-glossary :user/alice)))
    (is (true? (glossary/known-subject? test-glossary :admin/admin-banned))))

  (testing "singleton is known"
    (is (true? (glossary/known-subject? test-glossary :guest))))

  (testing "bare instance is known via index"
    (is (true? (glossary/known-subject? test-glossary :alice)))
    (is (true? (glossary/known-subject? test-glossary :pat))))

  (testing "bare type with instances is NOT known as a subject"
    (is (false? (glossary/known-subject? test-glossary :user))
        ":user has instances, so bare :user is not a valid subject"))

  (testing "unknown subject is not known"
    (is (false? (glossary/known-subject? test-glossary :unknown)))))

;; -----------------------------------------------------------------------------
;; Backward Compatibility
;; -----------------------------------------------------------------------------

(deftest backward-compat-flat-glossary-test
  (testing "old flat format works as singletons"
    (let [g {:subjects {:alice {:desc "A user"}
                        :admin {:desc "An admin"}}
             :instance-index {}
             :verbs {}}]
      (is (true? (glossary/known-subject? g :alice)))
      (is (true? (glossary/known-subject? g :admin)))
      (let [resolved (glossary/resolve-subject g :alice)]
        (is (= :alice (:type resolved)))
        (is (= :alice (:instance resolved)))
        (is (true? (:singleton? resolved)))))))

;; -----------------------------------------------------------------------------
;; Verb Valence Spec Tests (Tier 0) — sl-hse
;; -----------------------------------------------------------------------------
;;
;; Verb entries in the glossary must declare :desc and :frames. Each
;; frame must declare :args (vector of keyword slot names) and :pattern
;; (human-readable surface form), with optional :implicit-object.
;; These tests exercise the spec definitions directly via s/valid? —
;; load-time enforcement is wired in a later checkpoint.

(deftest frame-spec-test
  (testing "frame requires :args and :pattern"
    (is (true? (s/valid? :shiftlefter.svo.glossary/frame
                         {:args [] :pattern "S clicks O"})))
    (is (true? (s/valid? :shiftlefter.svo.glossary/frame
                         {:args [:value] :pattern "S fills O with VALUE"})))
    (is (false? (s/valid? :shiftlefter.svo.glossary/frame
                          {:args []}))
        "missing :pattern is rejected")
    (is (false? (s/valid? :shiftlefter.svo.glossary/frame
                          {:pattern "S clicks O"}))
        "missing :args is rejected"))

  (testing "frame :args must be a vector of keywords"
    (is (true? (s/valid? :shiftlefter.svo.glossary/frame
                         {:args [:width :height] :pattern "S resizes to W by H"})))
    (is (false? (s/valid? :shiftlefter.svo.glossary/frame
                          {:args ["value"] :pattern "S fills O"}))
        "string args are rejected — must be keywords")
    (is (false? (s/valid? :shiftlefter.svo.glossary/frame
                          {:args #{:value} :pattern "S fills O"}))
        "set args are rejected — must be a vector"))

  (testing "frame allows optional :implicit-object"
    (is (true? (s/valid? :shiftlefter.svo.glossary/frame
                         {:args [:width :height]
                          :pattern "S resizes to WIDTH by HEIGHT"
                          :implicit-object :window})))
    (is (false? (s/valid? :shiftlefter.svo.glossary/frame
                          {:args [] :pattern "x" :implicit-object "window"}))
        ":implicit-object must be a keyword")))

(deftest verb-entry-spec-test
  (testing "verb entry requires :desc and :frames"
    (is (true? (s/valid? :shiftlefter.svo.glossary/verb-entry
                         {:desc "Click an element"
                          :frames {:default {:args [] :pattern "S clicks O"}}})))
    (is (false? (s/valid? :shiftlefter.svo.glossary/verb-entry
                          {:desc "Click an element"}))
        "bare {:desc} entries are rejected — frames now required")
    (is (false? (s/valid? :shiftlefter.svo.glossary/verb-entry
                          {:frames {:default {:args [] :pattern "x"}}}))
        "missing :desc is rejected"))

  (testing "verb entry rejects empty frames map"
    (is (false? (s/valid? :shiftlefter.svo.glossary/verb-entry
                          {:desc "x" :frames {}}))
        "a verb with no frames is unimplemented; should not load"))

  (testing "verb entry accepts multiple frames (polysemy)"
    (let [see-entry {:desc "Observe an element"
                     :frames {:visible {:args []        :pattern "S sees O"}
                              :text    {:args [:text]   :pattern "S sees O with text TEXT"}
                              :enabled {:args []        :pattern "S sees O enabled"}}}]
      (is (true? (s/valid? :shiftlefter.svo.glossary/verb-entry see-entry))))))

(deftest verb-entry-explain-test
  (testing "explain-data surfaces the missing key for diagnostic messages"
    (let [explain (s/explain-data :shiftlefter.svo.glossary/verb-entry
                                  {:desc "Click"})
          problems (:clojure.spec.alpha/problems explain)]
      (is (some? explain) "non-conforming entries produce explain-data")
      (is (seq problems) "problems collection is non-empty")
      (is (some #(re-find #":frames" (pr-str (:pred %))) problems)
          "the predicate identifies :frames as the missing key"))))

;; -----------------------------------------------------------------------------
;; Default Web Glossary Tests (Tier 0 applied to ship data) — sl-hse
;; -----------------------------------------------------------------------------
;;
;; The framework's default web glossary lives at
;; resources/shiftlefter/glossaries/verbs-web.edn. Every verb entry must
;; conform to ::verb-entry, and the content must match the per-verb plan
;; locked in the sl-hse design discussion.

(deftest default-web-glossary-conforms-test
  (testing "every verb in the default web glossary conforms to ::verb-entry"
    (let [verbs (:verbs (glossary/load-default-verbs :web))
          failing (remove #(s/valid? :shiftlefter.svo.glossary/verb-entry (val %))
                          verbs)]
      (is (empty? failing)
          (str "non-conforming verbs: " (mapv key failing))))))

(deftest default-web-glossary-content-test
  (let [verbs (:verbs (glossary/load-default-verbs :web))]
    (testing "core verbs are present"
      (is (contains? verbs :click))
      (is (contains? verbs :fill))
      (is (contains? verbs :see))
      (is (contains? verbs :not-see))
      (is (contains? verbs :navigate))
      (is (contains? verbs :resize)))

    (testing ":see has the 11 declared frames (sl-rch extracted :url to :be/:at)"
      (let [see-frames (set (keys (get-in verbs [:see :frames])))]
        (is (= 11 (count see-frames)))
        (is (= #{:visible :on-page :text :value :attribute :enabled
                 :disabled :count :title :alert :alert-with-text}
               see-frames))))

    (testing ":be verb has the :at frame (sl-rch)"
      (is (contains? verbs :be))
      (is (= #{:at} (set (keys (get-in verbs [:be :frames])))))
      (is (= "S is on O" (get-in verbs [:be :frames :at :pattern]))))

    (testing "multi-frame verbs declare the right frames"
      (is (= #{:to-element :to-position}
             (set (keys (get-in verbs [:scroll :frames])))))
      (is (= #{:into :main}
             (set (keys (get-in verbs [:switch-frame :frames]))))))

    (testing "single-frame verbs use :default for canonical or descriptive names elsewhere"
      (is (contains? (get-in verbs [:click :frames]) :default))
      (is (contains? (get-in verbs [:fill :frames]) :with))
      (is (contains? (get-in verbs [:select :frames]) :from))
      (is (contains? (get-in verbs [:resize :frames]) :dimensions)))

    (testing "implicit-object verbs declare it on the frame"
      (is (= :window
             (get-in verbs [:resize :frames :dimensions :implicit-object])))
      (is (= :alert
             (get-in verbs [:accept-alert :frames :default :implicit-object]))))))

(deftest default-web-glossary-dropped-verbs-test
  (testing "verbs permanently dropped per sl-psc remain absent"
    (let [verbs (:verbs (glossary/load-default-verbs :web))]
      ;; :count was a standalone verb; now expressed as :see/:count frame.
      (is (not (contains? verbs :count))
          ":count standalone verb absorbed into :see/:count frame")
      ;; sl-psc Bucket A — permanent removals (decisions.md § "Web verb defaults").
      ;; (sl-jsn re-introduced :hover and :wait in 2026-05-13; they are present.)
      (doseq [v [:upload :check :uncheck :submit]]
        (is (not (contains? verbs v))
            (str v " should remain absent from default glossary (sl-psc Bucket A)"))))))

(deftest default-web-glossary-reintroduced-verbs-test
  (testing ":hover and :wait reintroduced per sl-jsn (2026-05-13)"
    (let [verbs (:verbs (glossary/load-default-verbs :web))]
      (is (contains? verbs :hover) ":hover present")
      (is (= #{:default} (set (keys (get-in verbs [:hover :frames]))))
          ":hover has the single :default frame")
      (is (contains? verbs :wait) ":wait present")
      (is (= #{:duration :for-element :for-text :for-count}
             (set (keys (get-in verbs [:wait :frames]))))
          ":wait has the four :duration/:for-* frames")
      (is (= :time
             (get-in verbs [:wait :frames :duration :implicit-object]))
          ":wait :duration uses implicit-object :time"))))

;; -----------------------------------------------------------------------------
;; Loader Enforcement Tests (Tier 0 wired in) — sl-hse
;; -----------------------------------------------------------------------------
;;
;; load-all-glossaries-strict fails on bad verb entries; load-all-glossaries
;; (lenient) emits warnings but still loads. The bad-verbs-web.edn fixture
;; exercises both verb-level and frame-level validation failures.

(deftest strict-mode-rejects-bad-verb-glossary
  (testing "load-all-glossaries-strict returns :error for non-conforming verb entries"
    (let [config {:subjects (fixture-path "subjects.edn")
                  :verbs {:web (fixture-path "bad-verbs-web.edn")}}
          result (glossary/load-all-glossaries-strict config)]
      (is (contains? result :error) "strict mode produces an error result")
      (let [err (:error result)]
        (is (= :glossary/invalid-verb-entry (:type err)))
        (is (= :web (:interface-type err)))
        (is (string? (:message err)))
        (is (re-find #"verb :web/swipe" (:message err))
            "error message names the offending verb"))
      (testing ":all-errors collects every bad entry"
        (let [errs (-> result :error :all-errors)]
          (is (= 2 (count errs)) "two bad verbs in the fixture")
          (is (some #(= :swipe (:verb %)) errs))
          (is (some #(= :pinch (:verb %)) errs)))))))

(deftest strict-mode-accepts-good-verb-glossary
  (testing "strict mode passes for conforming project glossary"
    (let [config {:subjects (fixture-path "subjects.edn")
                  :verbs {:web (fixture-path "verbs-web-project.edn")}}
          result (glossary/load-all-glossaries-strict config)]
      (is (contains? result :ok))
      (is (contains? (get-in result [:ok :verbs :web]) :swipe))
      (is (contains? (get-in result [:ok :verbs :web]) :pinch)))))

(deftest lenient-mode-warns-on-bad-verb-glossary
  (testing "lenient load-all-glossaries does NOT throw on bad entries; loads anyway"
    (let [config {:subjects (fixture-path "subjects.edn")
                  :verbs {:web (fixture-path "bad-verbs-web.edn")}}
          result (atom nil)
          ;; sl-5wj: warnings go through clojure.tools.logging now,
          ;; capture them via the test-helpers log factory instead of
          ;; rebinding *err*.
          logs (log-capture/with-captured-logs
                 #(reset! result (glossary/load-all-glossaries config)))]
      (is (map? @result) "load returns a glossary, not an error")
      (is (contains? (get-in @result [:verbs :web]) :swipe)
          "bad verb entries are still merged into the result")
      (is (some (log-capture/level-msg? :warn "verb :web/swipe") logs)
          "warning is emitted for the bad verb")
      (is (some (log-capture/level-msg? :warn "missing required key :frames") logs)
          "warning identifies the missing key"))))

(deftest verb-entry-error-message-shape
  (testing "error messages identify the verb, missing key, and path"
    (let [config {:subjects (fixture-path "subjects.edn")
                  :verbs {:web (fixture-path "bad-verbs-web.edn")}}
          err (-> (glossary/load-all-glossaries-strict config) :error :all-errors)
          swipe-err (first (filter #(= :swipe (:verb %)) err))
          pinch-err (first (filter #(= :pinch (:verb %)) err))]

      (testing "verb-level missing key"
        (is (re-find #"verb :web/swipe" (:message swipe-err)))
        (is (re-find #"missing required key :frames" (:message swipe-err)))
        (is (re-find #"bad-verbs-web\.edn" (:message swipe-err))
            "path is included in the message"))

      (testing "frame-level missing key includes the frame name"
        (is (re-find #"verb :web/pinch" (:message pinch-err)))
        (is (re-find #"frame :default" (:message pinch-err))
            "frame name is surfaced for nested failures")
        (is (re-find #"missing required key :pattern" (:message pinch-err)))))))

;; -----------------------------------------------------------------------------
;; Costume :wears — schema + resolution (sl-rnm)
;; -----------------------------------------------------------------------------

(deftest subject-entry-wears-schema-test
  (testing "::subject-entry accepts an optional :wears keyword"
    (is (s/valid? ::glossary/subject-entry {:wears :finance}))
    (is (s/valid? ::glossary/subject-entry {:instances [:alice :bob] :wears :finance}))
    (is (s/valid? ::glossary/subject-entry {:instances [:alice :bob]})
        "entries without :wears still conform")
    (is (s/valid? ::glossary/subject-entry {})
        "empty entry (singleton) still conforms"))
  (testing "::subject-entry rejects a non-keyword :wears"
    (is (not (s/valid? ::glossary/subject-entry {:wears "finance"})))))

(deftest costume-for-subject-test
  (let [glossary {:subjects {:operator {:instances [:alice] :wears :finance}
                             :admin {:wears :ops}
                             :guest {}}
                  :instance-index {:alice :operator}}]
    (testing "resolves an instance to its type's costume"
      (is (= :finance (glossary/costume-for-subject glossary :alice)))
      (is (= :finance (glossary/costume-for-subject glossary :operator/alice))))
    (testing "resolves a singleton type's costume directly"
      (is (= :ops (glossary/costume-for-subject glossary :admin))))
    (testing "nil when the subject wears nothing"
      (is (nil? (glossary/costume-for-subject glossary :guest))))
    (testing "nil for an unknown subject"
      (is (nil? (glossary/costume-for-subject glossary :nobody))))))
