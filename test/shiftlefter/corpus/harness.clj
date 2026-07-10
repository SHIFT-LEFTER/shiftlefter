(ns shiftlefter.corpus.harness
  "Generate-and-run harness for the sl-vs9m corpus.

   `run-corpus!` is the stable seam sl-i608 and sl-q9wp acceptance call,
   referencing corpus cases via the manifest (D9). :tag-filter is LIVE
   (sl-i608): it is execute!'s planning-time tag filter, verbatim.
   :max-parallel is LIVE (sl-q9wp): it is execute!'s scenario parallelism
   bound, verbatim."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [shiftlefter.corpus.generator :as gen]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.runner.core :as runner]))

(def corpus-steps-file
  "The corpus stepdef fixture, passed as a single-file :step-paths entry so
   the run loads ONLY the fake corpus vocabulary (plus built-ins)."
  (str (fs/absolutize "test/fixtures/steps/corpus_steps.clj")))

(defn run-corpus!
  "Materialize (profile, seed), run it through the real runner in-process
   (vanilla mode, no browser), and collect every verification surface.

   opts:
   - :seed            required
   - :profile         :default | :parallel-stress (default :default)
   - :include-broken? also run the @broken quarantine dir (exit 2 unless the
                      run excludes @broken via :tag-filter)
   - :tag-filter      execute!'s {:include #{tag} :exclude #{tag}} planning-
                      time filter (sl-i608; threaded when non-nil)
   - :max-parallel    execute!'s scenario parallelism bound (sl-q9wp;
                      threaded when non-nil)
   - :report-mode     :edn (default) or :console. :console runs the console
                      reporter instead of --edn — the sl-q9wp byte-identity
                      proof compares :stderr across :max-parallel values, so
                      the run needs real console output. :edn-summary is nil
                      in :console mode.
   - :html?           also write the HTML run report (sl-muq9); its path is
                      returned as :html-file (nil when off)

   Returns {:corpus <write-corpus! result> :manifest m :junit-file abs-path
            :html-file abs-path-or-nil
            :edn-summary <parsed --edn map, or nil in :console mode>
            :stderr <everything the run printed to *err*>
            :result <execute! return>}."
  [{:keys [seed profile include-broken? tag-filter max-parallel report-mode html?]
    :or {profile :default report-mode :edn}}]
  (let [{:keys [dir features-dir broken-dir manifest] :as corpus}
        (gen/write-corpus! {:profile profile :seed seed})
        pc (project-context/resolve {:invocation-root dir})
        junit-file (str (fs/absolutize (str dir "/results.xml")))
        html-file (when html? (str (fs/absolutize (str dir "/report.html"))))
        edn? (= :edn report-mode)
        paths (cond-> [(str (fs/absolutize features-dir))]
                include-broken? (conj (str (fs/absolutize broken-dir))))
        opts (cond-> {:paths paths
                      :step-paths [corpus-steps-file]
                      :junit-xml junit-file
                      :edn edn?
                      :project-context pc}
               html-file (assoc :html html-file)
               (some? tag-filter) (assoc :tag-filter tag-filter)
               (some? max-parallel) (assoc :max-parallel max-parallel))
        result-box (volatile! nil)
        err-box (java.io.StringWriter.)
        out (with-out-str
              (binding [*err* err-box]
                (vreset! result-box (runner/execute! opts))))
        summary (when edn? (edn/read-string out))]
    {:corpus corpus
     :manifest manifest
     :junit-file junit-file
     :html-file html-file
     :edn-summary summary
     :stderr (str err-box)
     :result @result-box}))
