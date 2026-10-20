(ns shiftlefter.stepengine.exec.abort
  "First-class run abort (sl-617t): the process-level abort request and the
   current-run registry it is checked against.

   ## The mechanism (Chair ruling 09-15)

   An abort is a cancellation flag READ at the halt gate's existing check
   sites — `run-or-skip` (no not-yet-started scenario starts) and the
   runner's setup-group loop (no later group's :start runs) — plus ONE new
   check inside the step loop, so a scenario already running stops at its
   next step boundary. A step is never interrupted (an interrupted step
   cannot clean up honestly), no nREPL interrupt or session close is ever
   involved, and no executor is `shutdownNow`-ed: the abort returns
   normally through the halt path, so Afters, capability cleanup, every
   `:stop` and the report writers all run in the ordinary epilogue.

   Why a process-level flag rather than a CAS into each gate atom: under
   `:halt-on-observation-failure` `:group`/`false` every setup group owns
   its own gate atom (created only when that group runs), and the step
   loop has no atom at all. One request read beside the atom at the same
   sites is one mechanism with two arming sources; the abort record wins
   over an observation record when both exist. The 8orx crash `finally`
   (gate armed `{:reason :crash}` + `shutdownNow`) is untouched and never
   reached by an abort.

   One run at a time by construction (the daemon's dispatch lock; cold has
   one run per JVM), so this is a single JVM-wide registry: `begin-run!`
   resets it at every `execute!`, `end-run!` clears it in the finally. An
   abort arriving with no run registered is `:idle` — nothing to abort.

   ## Two strikes (cold path only)

   `cold-sigint!` is the -main SIGINT handler's brain: strike one requests
   the abort (the caller prints the immediate line); strike two is the
   crash row — the caller runs the registered crash epilogue (8orx markers,
   the EDN crash form, the 'Runner crash:' line) and exits 3 — UNLESS the
   run is already `finalizing?` (the FINAL report writes have begun: the
   single-scope suite returned, or every setup group returned and the
   invocation surfaces are being written; Tower fold-in C1 / fit-pass
   P1): then strike two is a no-op with a line and the run exits 4 when
   its writers finish, so a strike never races them. Strikes past two are
   ignored."
  (:import (java.util Date)))

(def ^:private initial
  {:run nil :request nil :strikes 0 :finalizing? false :crash-epilogue nil})

(defonce ^{:doc "The JVM-wide abort registry — see the ns docstring."
           :private true}
  state
  (atom initial))

;; -----------------------------------------------------------------------------
;; Run registry (the runner's side)
;; -----------------------------------------------------------------------------

(defn begin-run!
  "Register a run in flight and forget any previous run's request. Called
   at the top of every `execute!`."
  []
  (reset! state (assoc initial :run {:in-flight 0 :results-dir nil}))
  nil)

(defn end-run!
  "Forget the run — no abort can land after this (an abort arriving now is
   `:idle`). Called in `execute!`'s finally."
  []
  (reset! state initial)
  nil)

(defn run-registered?
  "True between `begin-run!` and `end-run!`."
  []
  (some? (:run @state)))

(defn register-results-dir!
  "Record the run's results directory the moment it is created, so the
   immediate line and the detach hint can name where reports will land."
  [dir]
  (swap! state update :run #(some-> % (assoc :results-dir (some-> dir str))))
  nil)

(defn results-dir
  "The registered results dir, or nil."
  []
  (get-in @state [:run :results-dir]))

(defn register-crash-epilogue!
  "Register the thunk a cold second strike runs before exiting 3: `(f
   throwable)` writes the 8orx crash markers (when the invocation has
   surfaces) and the crash report form/line. The runner layers these: the
   invocation wrapper wraps `execute!`'s base reporter with the marker
   writes."
  [f]
  (swap! state assoc :crash-epilogue f)
  nil)

(defn crash-epilogue
  "The registered crash epilogue thunk, or nil."
  []
  (:crash-epilogue @state))

(defn finalizing!
  "Mark the run's FINAL report writes as begun: the single-scope suite has
   returned, or every setup group has and the invocation surfaces are
   being written (fold-in C1 / fit-pass P1). A cold second strike from
   here on is a no-op."
  []
  (swap! state assoc :finalizing? true)
  nil)

(defn scenario-started!
  "A scenario's real run began (run-or-skip's run branch)."
  []
  (swap! state update :run #(some-> % (update :in-flight inc)))
  nil)

(defn scenario-finished!
  "A scenario's real run ended (its Afters/cleanup included)."
  []
  (swap! state update :run #(some-> % (update :in-flight (fn [n] (max 0 (dec n))))))
  nil)

;; -----------------------------------------------------------------------------
;; The request (the aborter's side: SIGINT handler, daemon op)
;; -----------------------------------------------------------------------------

(defn requested?
  "True once an abort has been requested for the run in flight."
  []
  (some? (:request @state)))

(defn request!
  "Request the abort. Returns `{:status :aborting|:already-aborting
   :in-flight n :results-dir d}` when a run is registered, or
   `{:status :idle}` when nothing is running — an idle abort changes
   nothing. Idempotent: a second request re-reports `:already-aborting`."
  []
  (let [[old {:keys [run]}]
        (swap-vals! state (fn [{:keys [run request] :as st}]
                            (if (and run (nil? request))
                              (assoc st :request {:reason :aborted
                                                  :requested-at (Date.)})
                              st)))]
    (if (nil? run)
      {:status :idle}
      {:status (if (nil? (:request old)) :aborting :already-aborting)
       :in-flight (:in-flight run)
       :results-dir (:results-dir run)})))

(defn halt-record
  "The suite-level `:halted` record for an aborted run — the same slot the
   observation halt fills, with the reason on it. `after-scenario` = how
   many scenarios reached a verdict before the gate closed (nat-int; 0 when
   the abort landed before the first)."
  [after-scenario]
  {:reason :aborted :after-scenario after-scenario})

(defn announce-line
  "The line printed the instant the trap fires (Chair's condition: before
   any waiting). `in-flight` = scenarios finishing their current step."
  [{:keys [in-flight results-dir]}]
  (str "aborting: " (or in-flight 0)
       " scenario(s) finishing their current step, then Afters, cleanup,"
       " :stop; reports will land at "
       (or results-dir "the configured report paths")))

(defn cold-sigint!
  "The cold SIGINT handler's decision. Returns a map with `:outcome`:
   `:no-run` (nothing registered — the caller keeps the JVM's 130),
   `:aborting` (strike one; `:in-flight`/`:results-dir` for the line),
   `:crash` (strike two; `:finalizing?` and `:crash-epilogue` for the
   caller), or `:ignored` (strike three and later)."
  []
  (let [{:keys [run strikes finalizing? crash-epilogue]}
        (swap! state (fn [st] (if (:run st) (update st :strikes inc) st)))]
    (cond
      (nil? run) {:outcome :no-run}
      (= 1 strikes) (let [r (request!)]
                      (assoc r :outcome :aborting))
      (= 2 strikes) {:outcome :crash
                     :finalizing? finalizing?
                     :crash-epilogue crash-epilogue}
      :else {:outcome :ignored})))
