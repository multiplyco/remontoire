(ns co.multiply.remontoire.registry
  "The rolling registry: every eval, captured (§5).

   An entry is a `q/task`, not a value — what is retained is something that
   will eventually complete, which is what makes an id useful before the work
   is finished and a valid input to `q/catch`, `q/finally` and `q/cancel`
   after it.

   State lives here behind `defonce` and nothing else lives here at all: the
   library namespaces above churn constantly during development, and ids that
   evaporate on reload would break the property the registry exists for —
   that an id keeps resolving."
  (:require [co.multiply.quiescent :as q]
    [co.multiply.remontoire.session :as session]
    [co.multiply.scoped :as sc]))


(def default-capacity
  "How many settled, delivered entries are retained unless `init!` is told
   otherwise (`:capacity`)."
  20)


;; Strong references, released wholesale by eviction — decided over weak refs
;; because result readback is the property worth keeping (§5).
(defonce ^:private state
  (atom {:next-id 1 :entries (sorted-map)}))


(defn capacity
  "How many settled, delivered entries are retained (§5). In `state` so a
   tuned cap survives reloads the way the entries do; absent until tuned,
   because `defonce` state predating this key must keep working."
  ([] (capacity @state))
  ([state] (or (:capacity state) default-capacity)))


(defn capacity!
  "Retain `n` settled, delivered entries from here on (§5). Runners and
   unread results are uncounted either way. Takes effect at the next
   admission — shrinking below the current pool evicts the excess then, with
   the usual announcement."
  [n]
  {:pre [(pos-int? n)]}
  (swap! state assoc :capacity n)
  n)


(defn- settled?
  "Instantaneous and idempotent: `q/await` with a zero bound settles
   immediately with whether the task has."
  [task]
  @(q/await task 0))


(defn- evictable?
  "Settled *and* touched: a running eval's id must keep resolving, and a
   result nobody has seen must survive until somebody sees it, however much
   work piles on meanwhile (§5). `settled?` reads final state, so the test
   is safe under a swap retry."
  [{:keys [task touched]}]
  (and touched (settled? task)))


(defn- touch
  "Stamp `id` with the next tick — pure, so safe inside a swap. A missing id
   passes through unchanged; `res` reports those itself."
  [{:keys [entries] :as state} id]
  (if (get entries id)
    (let [tick (inc (or (:tick state) 0))]
      (-> state
        (assoc :tick tick)
        (assoc-in [:entries id :touched] tick)))
    state))


(defn touch!
  "Mark `id` delivered — its outcome has reached a caller — and refresh it.
   The transport calls this when a response reports the outcome; `res` calls
   it on every access. An entry never touched is never evicted (§5)."
  [id]
  (swap! state touch id)
  nil)


(defn- release-claim
  "Mark an entry delivered and told if it still exists."
  [state id]
  (if (get-in state [:entries id])
    (-> state (touch id) (assoc-in [:entries id :announced] true))
    state))


(defn- lapse-claim
  "Lift the shield of an entry whose reader is gone, if it still exists:
   delivered, when it had not been, and told. One already delivered keeps
   its place among the read — a lapse is not a reading, and an entry
   lapsed twice, by the sweep of its session's end and by an admission
   that found the session gone, is where one lapse left it."
  [state id]
  (if-let [entry (get-in state [:entries id])]
    (-> (cond-> state (nil? (:touched entry)) (touch id))
      (assoc-in [:entries id :announced] true))
    state))


(defn- lapse-claims!
  "The delivery claims of a session that has ended lapse: every entry it
   owns is marked delivered and told, so the eviction shield lifts and the
   entries join the LRU pool — still `res`-able by anyone until capacity
   pressure claims them. Death degrades; it does not delete (§11). Running
   entries are marked too: not evictable until they settle, but the moment
   they do, their reader being gone, they owe nobody anything."
  [sid]
  (let [ids (into []
              (keep (fn [[id entry]] (when (= sid (:session entry)) id)))
              (:entries @state))]
    (swap! state #(reduce lapse-claim % ids))
    nil))


(defn release-session!
  "End session `sid` now: it is dropped, and its claims lapse
   (`lapse-claims!`). The row goes first, as when a session expires, and
   the claims lapse for the one call that took it: a session ended twice
   — a fleet can ask for the same retirement twice (§15) — is ended once,
   and what it left behind is not refreshed over what the living read
   since."
  [sid]
  (when (session/drop! sid)
    (lapse-claims! sid))
  nil)


(defn- expire-session!
  "End session `sid` for its silence, if it is still silent. The row goes
   first here, and only then do the claims lapse: `session/expire!` decides
   and removes in one step, so a call that refreshed the session since it
   was listed keeps it, and one that comes after is refused before it
   starts anything."
  [sid]
  (when (session/expire! sid)
    (lapse-claims! sid))
  nil)


(defn owners
  "Session id → how many entries it owns. For the roster (§11)."
  []
  (frequencies (map :session (vals (:entries @state)))))


(defn release!
  "Punctuation (§11): release `sid`'s claims on the leg of work behind it.
   Every settled, undelivered entry it owns is marked delivered and told —
   the caller declares it has absorbed them, and the eviction shield
   lifts. With `period?` the punctuation is a period rather than a comma:
   running entries are cancelled too, and marked — cancellation is the
   outcome the caller authors (§9) — though each turns evictable only when
   its cancellation actually bites, so a wedged leaf stays visible as a
   protected runner rather than vanishing mid-cancel. A comma leaves
   running entries alone entirely: what is still moving stays a live
   concern, settled news included.

   The entry evaluating the release is itself a running entry the caller
   owns, so a period would cancel the mouth that spoke it (observed
   2026-08-05: the report died with its own eval). It is exempt —
   identified by `self-bank`, the eval's identity on the rails — because
   punctuation ends the leg *behind* the caller.

   Returns {:released [ids] :cancelled [ids]}, disjoint — what was
   absorbed versus what was stopped. The report is the audit the caller
   would otherwise perform by sifting its conversation for what it has
   read and what might still be running."
  [sid period? self-bank]
  (let [owned     (filterv #(= sid (:session %)) (vals (:entries @state)))
        released  (into []
                    (comp (filter #(and (nil? (:touched %)) (settled? (:task %))))
                      (map :id))
                    owned)
        stopped   (if period?
                    (filterv #(and (not (settled? (:task %)))
                                (not (identical? self-bank (:bank %))))
                      owned)
                    [])
        cancelled (mapv :id stopped)]
    (run! (comp q/cancel :task) stopped)
    (swap! state #(reduce release-claim % (concat released cancelled)))
    {:released released :cancelled cancelled}))


(defn- session-bonus
  "The capacity extension for multi-session crowding (§11): +10 per live
   session beyond the first, so concurrent callers widen the pool rather
   than evict each other's entries out from under themselves. One session
   sees the configured capacity exactly."
  []
  (* 10 (max 0 (dec (session/live-count)))))


(defn capture!
  "Assign the next id to `entry` and admit it, evicting past the bound.
   Within the evictable pool, eviction is least-recently-touched first — the
   id gives mint order, not eviction order, so the entries being composed on
   stay alive. The registry grows past the bound while work runs or waits to
   be read, and one admission can evict several as it converges back.

   Admission is also when the dead are reaped (§11): liveness is computed
   lazily, right where dead sessions' claims would otherwise shield entries
   forever and where the capacity formula needs the live count. No reaper
   thread — the machinery stays inert until asked, like everything else.

   An entry admitted for a session that has ended is owed to nobody, and
   is admitted as one whose claim has lapsed. Looked at after the entry is
   in: a session ends by losing its row and then lapsing its claims, so an
   entry admitted before the lapse is lapsed by it, and one admitted after
   finds the row gone.

   Returns {:id n :evicted [ids]} — `evicted` holding the admitting
   session's own losses only, which the caller announces in its response;
   another session's evictions are queued for their owner instead (§5), so
   nobody's ids stop resolving without them hearing it."
  [entry]
  (run! expire-session! (session/expired))
  (let [bonus    (session-bonus)
        [before after]
        (swap-vals! state
          (fn [{:keys [next-id] :as state}]
            (let [entries (assoc (:entries state) next-id (assoc entry :id next-id))
                  doomed  (->> (vals entries)
                            (filter evictable?)
                            (sort-by :touched)
                            (drop-last (+ (capacity state) bonus))
                            (map :id))]
              (-> state
                (assoc :next-id (inc next-id))
                (assoc :entries (reduce dissoc entries doomed))))))
        evicted  (into [] (remove (:entries after)) (keys (:entries before)))
        id       (:next-id before)
        owner    (:session entry)
        by-owner (group-by #(get-in before [:entries % :session]) evicted)]
    (when (and (some? owner) (not (session/live? owner)))
      (swap! state lapse-claim id))
    (doseq [[sid ids] by-owner :when (not= sid owner)]
      (session/note-evictions! sid ids))
    {:id      id
     :evicted (vec (get by-owner owner))}))


(defn- resolve-entry
  "The entry for `id` in `state`, or a thrown explanation of which way the
   id failed to resolve — evicted and never-assigned are different mistakes."
  [{:keys [next-id entries] :as state} id]
  (or (get entries id)
    (throw (ex-info (if (< 0 id next-id)
                      (str "Entry " id " was evicted; the registry holds "
                        (capacity state) " entries, and running or unread "
                        "ones are protected — so the least recently touched "
                        "of the read entries go first")
                      (str "No entry " id "; ids assigned so far reach " (dec next-id)))
             {:id id}))))


(defn res
  "The entry for `id`: the eval's task. Deref for the value — safe to repeat,
   a settled task holds its result permanently. Compose rather than wait:
   `(q/get-ex (res 12))` is the exception as a value — cancellation
   included, where `catch` propagates it — and `(q/cancel (res 12))`
   cancels. Access refreshes the entry: what is in use is the last to be
   evicted. Throws on an id that does not resolve."
  [id]
  (:task (resolve-entry (swap! state touch id) id)))


(defn entry
  "The full entry map for `id` without refreshing or delivering — the
   accessor for machinery that reports *about* an entry rather than reads
   its result: `await` says how entries stand, `out` reads what one printed,
   `failure` needs the baseline. Announcement-side reads (§5) must not admit
   entries to the evictable pool; only `res` delivers."
  [id]
  (resolve-entry @state id))


(defn task
  "The task for `id`, without refreshing or delivering — see `entry`."
  [id]
  (:task (entry id)))


(defn status
  "Where `task` stands: `:running`, `:cancelled`, `:failed` or `:done`.
   Never blocks and never throws — `q/await` with a zero bound is the
   settled check, and settlement is final, so the checks after it cannot be
   outraced by the task failing mid-read. Cancelled before exceptional: a
   cancelled task holds a CancellationException, and reporting one as
   failed would report the wrong thing — cancellation is a control signal,
   not an error (§9)."
  [task]
  (cond
    (not (settled? task)) :running
    (q/cancelled? task) :cancelled
    (q/exceptional? task) :failed
    :else :done))


(defn owed
  "What has settled unseen and untold *for `sid`* — settled, untouched,
   unannounced, owned by that session — as [{:id n :status s} …], oldest
   first. Owner-scoped (§11): the news that entry 12 finished belongs to
   whoever started it, and announcing it to a stranger both leaks and
   consumes the telling. A pure read; `told!` marks. Split, because the
   marks may only land once the response carrying the news has actually
   gone out (§5): a client that times out reads nothing."
  [sid]
  (into []
    (comp (filter (fn [{:keys [task touched announced] owner :session}]
                    (and (= sid owner)
                      (nil? touched) (not announced) (settled? task))))
      (map (fn [{:keys [id task]}] {:id id :status (status task)})))
    (vals (:entries @state))))


(defn told!
  "Mark `ids` announced — told once. Announcing is not delivery: the footer
   says *that* an eval finished, not what it produced, so the entries stay
   protected from eviction until a `res` actually reads them (§5)."
  [ids]
  (swap! state update :entries
    (fn [entries]
      (reduce (fn [m id]
                ;; Guarded, though an untouched entry cannot be evicted:
                ;; `assoc-in` on a vanished id would recreate it.
                (cond-> m (get m id) (assoc-in [id :announced] true)))
        entries ids)))
  nil)


(defn evals
  "One map per live entry, oldest first. The registry is shared — one JVM,
   one system, global ids — so everything is listed; entries another
   session minted carry `:by`, the owner's session id (nil for a
   sessionless in-process caller — the wire always has a session).
   Attribution, not isolation (§11): what the caller didn't make
   is still labeled, never hidden. The caller's own session rides the same
   rails as `tee/*bank*`, so this works wherever an eval navigates."
  []
  (let [caller (sc/ask session/*session* nil)]
    (mapv (fn [{:keys [id task peer at] owner :session}]
            (cond-> {:id id :status (status task) :peer peer :at at}
              (not= owner caller)
              (assoc :by owner)))
      (vals (:entries @state)))))
