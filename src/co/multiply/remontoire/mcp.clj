(ns co.multiply.remontoire.mcp
  "JSON-RPC dispatch for MCP.

   Takes a parsed request map, returns a response map. No I/O and no transport
   concerns — which is what makes it testable without a socket."
  (:require [clojure.string :as str]
            [co.multiply.quiescent :as q]
            [co.multiply.remontoire.evaluate :as evaluate]
            [co.multiply.remontoire.exception :as exception]
    ;; Not called from anywhere: loaded so the pattern namespaces exist
    ;; for the drilldown — `(__r/nses "patterns")` is their index.
            [co.multiply.remontoire.patterns]
            [co.multiply.remontoire.registry :as registry]
            [co.multiply.remontoire.session :as session]
            [co.multiply.remontoire.tee :as tee]))


(def ^:private server-info
  {:name "Remontoire" :version "0.1.2"})


(def ^:private await-ms
  "How long a call waits before returning `running` when the caller does not
   say (§9)."
  2000)


(def ^:private await-clamp
  "The most any call is allowed to wait: 24 h, mirroring the transport's own
   ~28 h wall-clock rather than a judgment of ours — with SSE and progress
   heartbeats (§4) the caller's deadline is the deadline, and the model
   chooses it. Measured end-to-end to 61⅔ min (the long probe, 2026-08-04,
   delivered through the client's background-task path); no known timer
   remains between there and the wall-clock. There is always a deadline;
   nothing waits unboundedly. One value with session expiry — derefed from
   `session/expiry-ms` so the two cannot drift (§11): any wait the protocol
   will grant, the session survives to collect. `manual.md` states the
   24 h figure literally — a change here must update it by hand."
  session/expiry-ms)


(defonce ^{:private true
           :doc     "requestId → a thunk that cancels the work that request
   started. An entry lives only while its handler runs;
   `notifications/cancelled` looks its requestId up here."}
  in-flight
  (atom {}))


(defonce ^{:private true
           :doc     "requestIds the client explicitly cancelled while they ran.
   Consulted once when the response is built: a cancelled request has no
   reader by definition — the client keeps the socket open after aborting
   (measured 2026-08-04), so no write can detect it — and its delivery
   marks must be withheld so the outcome stays owed and announceable (§5)."}
  client-cancelled
  (atom #{}))


(defn- clamp-await
  "Tolerates a stringly number: a client holding a stale schema for the tool
   passes unknown fields through untyped (observed 2026-08-04)."
  [requested]
  (-> (if (string? requested) (or (parse-long requested) 0) requested)
    (max 0)
    (min await-clamp)))


(defn- deadline
  "The caller's deadline, clamped — or `default` when the caller said
   nothing. There is always a deadline; nothing waits unboundedly (§9)."
  [requested default]
  (if (some? requested) (clamp-await requested) default))


(defn- cancellable-await
  "Wait for `task` to settle, up to `ms`, with the wait on the books: while
   it runs, `in-flight` maps `request-id` to a thunk cancelling `task`, so
   an explicit client cancellation — TaskStop arrives as
   `notifications/cancelled` naming the request — reaches the work directly
   (§9). Only the explicit notice does that; a dead socket is an accident
   and cancels nothing. The deref never throws: `q/await` shields."
  [task ms request-id]
  (when request-id
    (swap! in-flight assoc request-id #(q/cancel task)))
  (try
    @(q/await task ms)
    (finally
      (when request-id (swap! in-flight dissoc request-id)))))


(defn- cancel-request!
  "Act on an explicit cancellation: cancel what `request-id` started and
   record that its response will have no reader. Unknown ids do nothing, so
   the record cannot grow on strays."
  [request-id]
  (when-let [cancel! (get @in-flight request-id)]
    (swap! client-cancelled conj request-id)
    (cancel!)))


(defn- aborted?
  "Whether the client cancelled `request-id` while it ran. Consuming: the
   record is read once, as the response is built, and dropped."
  [request-id]
  (let [[before] (swap-vals! client-cancelled disj request-id)]
    (contains? before request-id)))


(defn- render-value
  "Value first, because that is what was asked for. Bounded, because `pr-str`
   of an infinite seq never returns; Clojure prints `...` at each cut, so the
   truncation announces itself. This is §4's dumb cap — `shape` replaces it."
  [value]
  (binding [*print-length* 128
            *print-level*  12]
    (pr-str value)))


(defn- news
  "What settled unseen for `sid` since its last response (§5, §11), minus
   `reported` — the ids the current response already accounts for in its
   own right. Owner-scoped: another session's endings are not this
   caller's news, and telling the wrong caller consumes the telling."
  [reported sid]
  (into [] (remove (comp (set reported) :id)) (registry/owed sid)))


(defn- news-line
  [settled]
  (str ";; settled — "
    (str/join ", " (map (fn [{:keys [id status]}] (str id " " (name status)))
                     settled))))


(defn- footer
  "Every response names its entry (§5) and the namespace it used (§4) —
   namespace after the value, because a sticky default is only safe while it
   is visible. The form count appears only when more than one ran; evals
   that settled unseen since the last response are reported here — id and
   status, the value is a `res` away — and eviction is announced, never
   silent. A session that has not read the manual is told so, once per
   response (§4): a fact, not an error — a model that read it on another
   server of the same build may judge for itself."
  [{:keys [id note ns forms settled evicted nudge]}]
  (str ";; id " id
    (when note (str " · " note))
    " · ns " ns
    (when (and forms (> forms 1)) (str ", " forms " forms"))
    (when (seq settled) (str "\n" (news-line settled)))
    (when (seq evicted)
      ;; "id 1", not bare "1" — a lone number reads as a count (a fresh
      ;; tenant read it exactly that way, 2026-08-05).
      (str "\n;; evicted " (if (next evicted) "ids " "id ")
        (str/join ", " evicted)))
    (when nudge "\n;; manual unread — (__r/manual)")))


(defn- eval-handler
  "Run, register, wait up to the bound, report. Everything after the wait
   keys off `registry/status` — the same triage `(evals)` shows a caller."
  [{:keys [code ns await]} {:keys [peer display session] ::keys [request-id]}]
  (let [{:keys [task details baseline bank]} (evaluate/evaluate code ns session)
        {:keys [id evicted]}
        (registry/capture! {:task     task
                            :baseline baseline
                            :bank     bank
                            :peer     peer
                            :session  session
                            :at       (java.util.Date.)})]
    ;; Point the transport's progress display at the bank before waiting, so
    ;; heartbeats can show the eval's latest output line while it runs (§4).
    (when display (reset! display bank))
    (cancellable-await task (deadline await await-ms) request-id)
    (let [status  (registry/status task)
          ;; Minus this eval's own entry, which the response reports anyway —
          ;; listed here would mean `running` earlier and settled since.
          settled (news [id] session)
          ;; Runs only after the transport writes the response (§5): a
          ;; settled response delivers the outcome, admitting the entry to
          ;; the evictable pool, and the news is marked told — but a client
          ;; that timed out read neither, so the entry must stay owed,
          ;; protected, and announceable.
          mark!   (fn []
                    (when (not= :running status)
                      (registry/touch! id))
                    (registry/told! (mapv :id settled)))
          line    (fn [opts]
                    ;; The default ns is where this eval *started* — the
                    ;; explicit request, else the session's sticky. Where it
                    ;; has moved since is unknowable until eof records it,
                    ;; so `:done` overrides with the truth from `details`;
                    ;; the sticky alone misreported explicit-ns evals that
                    ;; outlived their await (measured 2026-08-05).
                    ;; The unread check runs at response build, after the
                    ;; eval — so a `manual` call's own response arrives
                    ;; already unmarked.
                    (footer (merge {:id      id
                                    ;; An explicit ns that does not exist was
                                    ;; never entered — `resolve-ns` refuses
                                    ;; inside the task — and naming it here
                                    ;; read as the session having moved
                                    ;; (smoke test, 2026-08-06); the session's
                                    ;; actual place is the truthful default.
                                    :ns      (if (and (seq ns)
                                                  (find-ns (symbol ns)))
                                               ns
                                               (evaluate/current-ns session))
                                    :settled settled
                                    :evicted evicted
                                    :nudge   (and session
                                               (not (session/manual-read? session)))}
                              opts)))
          ;; What the eval printed, when it printed — present in every
          ;; branch, because a `running` or `failed` eval's output is often
          ;; the most informative thing about it. Value still first (§4).
          out     (let [s (tee/text bank)]
                    (when (seq s)
                      (str ";; out\n" s
                        (when-not (str/ends-with? s "\n") "\n"))))]
      (-> (case status
            :running
            {:text (str out (line {:note (str "running — (res " id ") is the task")}))}

            :cancelled
            ;; This branch fires only when cancellation lands inside the
            ;; await window — dominated by propagation: an eval that grounds
            ;; a cancelled task is torn down with it, and a bare "cancelled"
            ;; left a fresh tenant with no why (measured 2026-08-05). The
            ;; exception's own message carries what provenance exists.
            {:text (str out
                     (line {:note (if-let [m (some-> (q/get-ex task) ex-message)]
                                    (str "cancelled — " m)
                                    "cancelled")}))}

            :failed
            {:text   (str (exception/render
                            (exception/profile (q/get-ex task) @baseline))
                       "\n" out (line {}))
             :error? true}

            :done
            ;; Settled non-exceptionally ⇒ evaluate returned ⇒ details was
            ;; delivered before the task settled — and its :ns is where this
            ;; eval itself landed, where `current-ns` is whoever moved last.
            (let [{:keys [ns forms]} @details]
              {:text (str (render-value @task)
                       "\n" out (line {:ns ns :forms forms}))}))
        (assoc ::delivered mark!)))))


(defn- await-handler
  "Block until every `all-of` entry has settled and — when `any-of` is
   non-empty — at least one `any-of` entry has, or until the deadline.
   The gate is the design's own algebra: all-of is just a vector returned
   from a task (verified 2026-08-04), any-of is the verb. Each entry sits
   behind a `q/await` shield, so a failure counts as a completion and
   nothing here owns, cancels or reads anything — reporting a status is
   announcement, not delivery (§5), so settled ids are marked told, not
   touched, and only after the response goes out."
  [{:keys [all-of any-of await]} {:keys [session] ::keys [request-id]}]
  (let [ms    (deadline await 0)
        alls  (mapv registry/task all-of)                   ; a bad id throws → :isError
        anys  (mapv registry/task any-of)
        parts (cond-> (mapv #(q/await % ms) alls)
                (seq anys) (conj (apply q/any-of (mapv #(q/await % ms) anys))))]
    (when (seq parts)
      ;; Cancelling an await means stop waiting, nothing more: the gate and
      ;; its shields die, every entry's compel moat stops the cascade from
      ;; reaching the work, and `cancellable-await`'s own shield turns the
      ;; gate's death into an ordinary early return — statuses still get
      ;; reported.
      (cancellable-await (q/task parts) ms request-id))
    (let [statuses (into (sorted-map)
                     (map (fn [id task] [id (registry/status task)])
                       (concat all-of any-of)
                       (concat alls anys)))
          fresh    (news (keys statuses) session)
          reported (-> (filterv #(not= :running (statuses %)) (keys statuses))
                     (into (map :id) fresh))]
      {:text       (str (pr-str statuses)
                     (when (seq fresh) (str "\n" (news-line fresh))))
       ::delivered #(registry/told! reported)})))


(defn- cancel-handler
  "Cancel entries by id, wait briefly for the cancellations to bite, then
   report where every id stands. Cancellation is the one outcome this tool
   authors, so it is the one it delivers: an id reporting `:cancelled` is
   touched — the caller asked for that ending and the response confirms it,
   and nobody reads a task they deliberately killed, so the entry must not
   sit immortal waiting for a `res` that never comes (§5). An id that
   settled first beat the cancel: `:done` or `:failed` is announcement,
   told once, the value still unread and protected — exactly `await`'s
   stance. `:running` outlasted the grace period — `q/cancel` is prompt in
   a pure task tree but cooperative at a JVM leaf (§9), so the honest
   report is that it has not bitten yet."
  [{:keys [ids await]} {:keys [session] ::keys [request-id]}]
  (let [ms    (deadline await await-ms)
        tasks (mapv registry/task ids)]                     ; a bad id throws → :isError
    (run! q/cancel tasks)
    (when (seq tasks)
      ;; The same shielded gate as `await`'s: each entry's death is a
      ;; completion, and cancelling this request just stops the waiting.
      (cancellable-await (q/task (mapv #(q/await % ms) tasks)) ms request-id))
    (let [statuses  (into (sorted-map) (map vector ids (map registry/status tasks)))
          fresh     (news ids session)
          cancelled (filterv #(= :cancelled (statuses %)) (keys statuses))
          reported  (-> (filterv #(not= :running (statuses %)) (keys statuses))
                      (into (map :id) fresh))]
      {:text       (str (pr-str statuses)
                     (when (seq fresh) (str "\n" (news-line fresh))))
       ::delivered (fn []
                     (run! registry/touch! cancelled)
                     (registry/told! reported))})))


(def tools
  "The tool table. Each entry is its own MCP declaration plus a :handler,
   which is stripped before the entry is sent to the client."
  [{:name        "eval"
    :description (str "Evaluate Clojure code and return the value.\n\n"
                   "Evaluation continues in the namespace the previous call ended in, "
                   "starting in a scratch namespace of this session's own, pre-wired "
                   "with Quiescent. Pass `ns` to evaluate somewhere else; that "
                   "namespace must already exist. The namespace used is reported with "
                   "every result.\n\n"
                   "Every evaluation is captured in a rolling registry, and every "
                   "response carries its id — a handle to the work, usable before it "
                   "settles. A call still unfinished when its `await` deadline passes "
                   "(default 2 s, clamped to " (/ await-clamp 3600000) " h) returns "
                   "`running` while the work continues; follow-up work can be chained "
                   "onto the task meanwhile. When an eval that returned `running` "
                   "later settles, the next response notes it once "
                   "(`;; settled — 12 done`); the result waits, unread and protected, "
                   "until `(__r/res 12)` reads it.\n\n"
                   "An eval is an implicit qdo: top-level forms run in order, and each "
                   "form's value is grounded before the next runs — a form returning a "
                   "task runs to completion and its value is what is captured. Only the "
                   "last form's value is returned; batch independent probes in a vector "
                   "or labeled map. Tasks "
                   "spawned but not returned are children and are cancelled by the time "
                   "the eval settles — silently. Work that must outlive the call needs "
                   "`(__q/compel (__q/task …))`, or better: let the eval itself be the "
                   "long-running work and use its id.\n\n"
                   "Printed output — `*err*` included, interleaved — is captured per "
                   "eval, attributed across its whole "
                   "task tree and returned under `;; out`; while an eval runs, its "
                   "latest output line is the progress message, so a `println` "
                   "mid-work is a live status report. The remontoire API is aliased "
                   "as `__r` in every namespace an eval runs in — "
                   "`(__r/manual)` is the manual; read it before your first real "
                   "eval. `__r` is for eval code only — never write it into source "
                   "files.")
    :inputSchema {:type       "object"
                  :properties {:code  {:type        "string"
                                       :description "Clojure code to evaluate."}
                               :ns    {:type        "string"
                                       :description "Namespace to evaluate in. Omit to continue where the last call ended."}
                               :await {:type        "integer"
                                       :description (str "How long to wait for completion before returning `running`, in milliseconds. Default 2000; clamped to " await-clamp ".")}}
                  :required   ["code"]}
    :handler     eval-handler}

   {:name        "await"
    :description (str "Wait for evals to settle, then report where they stand.\n\n"
                   "`all-of` lists entry ids that must every one settle; `any-of` "
                   "lists ids of which one settling is enough. The call returns when "
                   "both conditions hold, or at the `await` deadline — deadlines are "
                   "mandatory, in milliseconds, clamped to " await-clamp ".\n\n"
                   "Returns a map of id to status: :running, :done, :failed or "
                   ":cancelled. Statuses, not values — failure and cancellation are "
                   "completions here, an id still :running simply outlasted the "
                   "deadline, and a value is read with `(res id)` in an eval, which "
                   "is also what releases the entry for eviction. Waiting neither "
                   "reads, refreshes nor cancels anything.\n\n"
                   "With both lists empty or omitted the call returns at once, which "
                   "makes it the cheap check-in: evals that settled unseen are "
                   "reported in every response either way.")
    :inputSchema {:type       "object"
                  :properties {:all-of {:type        "array"
                                        :items       {:type "integer"}
                                        :description "Entry ids that must all settle."}
                               :any-of {:type        "array"
                                        :items       {:type "integer"}
                                        :description "Entry ids of which one settling suffices."}
                               :await  {:type        "integer"
                                        :description (str "Deadline in milliseconds. Required; clamped to " await-clamp ".")}}
                  :required   ["await"]}
    :handler     await-handler}

   {:name        "cancel"
    :description (str "Cancel evals by id, then report where each stands.\n\n"
                   "Sends `q/cancel` to every entry in `ids` — the wire-level "
                   "counterpart of `(__q/cancel (res 12))`, without spending a registry "
                   "entry to say it. Cancellation is cooperative: a pure task tree "
                   "stops promptly, but a JVM call already in flight stops at its next "
                   "check, so the call waits up to `await` (default 2000 ms, clamped "
                   "to " await-clamp ") for the cancellations to settle, then returns "
                   "a map of id to status. `:cancelled` — it worked, and the entry is "
                   "released for eviction, since a deliberately killed task has no "
                   "result worth reading. `:done` or `:failed` — the work settled "
                   "first; its result is still unread and stays protected until "
                   "`(res id)`. `:running` — the cancellation is requested but has "
                   "not yet taken effect. Cancelling a settled entry is harmless and "
                   "reports its actual status.")
    :inputSchema {:type       "object"
                  :properties {:ids   {:type        "array"
                                       :items       {:type "integer"}
                                       :description "Entry ids to cancel."}
                               :await {:type        "integer"
                                       :description (str "How long to wait for the cancellations to settle, in milliseconds. Default 2000; clamped to " await-clamp ".")}}
                  :required   ["ids"]}
    :handler     cancel-handler}])


(def ^:private by-name
  (into {} (map (juxt :name identity)) tools))


(defn- declarations
  "The tool table as the client sees it — handlers stripped."
  []
  (mapv #(dissoc % :handler) tools))


(defn tools-changed?
  "Whether the tool set differs from what `sid` last fetched. Per session
   (§11), and deliberately \"last fetched\", not \"last announced\": a lost
   announcement re-announces on the next stream, and a client holding a
   list from a previous server run is told to refresh on its first call —
   the marker only advances when a `tools/list` actually serves the
   current set (§4). The transport asks before closing each response
   stream and announces `tools/list_changed` while the answer is yes —
   the client re-fetches promptly (measured 2026-08-04, claude-code
   2.1.220), which is what makes a reloaded tool table reach a live
   session without a reconnect."
  [sid]
  (not= (declarations) (session/fetched-tools sid)))


(defn- result
  [id v]
  {:jsonrpc "2.0" :id id :result v})


(defn- error
  [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})


(defn- text
  [s]
  [{:type "text" :text s}])


(defmulti ^:private handle
  "Dispatch one JSON-RPC request (a message carrying an :id)."
  :method)


(defonce ^{:private true
           :doc     "`init!`-time options this namespace serves — currently
   `:describe`, the self-description woven into `instructions`. Behind
   `defonce` for the usual reason state is: set once at init, surviving
   the reloads this namespace churns through."}
  config
  (atom {}))


(defn configure!
  "Adopt `init!`'s teaching-surface options. Called before the port opens,
   so no initialize ever composes without them."
  [opts]
  (swap! config merge opts)
  nil)


(defn probe
  "The sessionless identity `GET /probe` serves: who answers here, minting
   nothing. `:server` is the fixed discriminator discovery tooling keys on;
   `:version` reads `server-info` — a site the release checklist already
   syncs, so no fifth version location; `:describe` is the `init!`-time
   self-description, nil when unset. Consumers key on field presence, not
   shape completeness — fields may accrete here without coordination."
  []
  {:server   "remontoire"
   :version  (:version server-info)
   :describe (:describe @config)})


(defn- instructions
  "What every connecting client learns at initialize — deliberately lean,
   the third form of this surface (§4; the instructions arc in
   `doc/logbook.md` carries the evidence): the eager channel is paid by every conversation in a
   project whether the REPL is used or not, goes stale against a
   hot-reloading server, and is first under the knife at context
   compaction — so it carries only what must be eager. *When*: the fit,
   phrased affirmatively so the read-only calls where an outline beats a
   file scan actually happen. *Whether*: effects are real, weighed
   together with the environment — `:describe` letting the server name
   its own (\"here: the production system behind …\"). And the pointer:
   `(__r/manual)` delivers the bulk fresh from the running build, its
   dedup clause living in the only place dedup can — the puller, who
   alone knows what it already holds."
  []
  (str "Remontoire is a live connection into a running Clojure system: eval "
    "runs inside the JVM itself, among the loaded namespaces and running "
    "state of the actual system"
    (when-let [d (:describe @config)] (str " (here: " d ")"))
    " — not a sandbox. Prefer it for navigating and reading Clojure — "
    "namespaces, signatures, docstrings, source, live data — where it is "
    "faster and more precise than file search, as well as for evaluating "
    "code. Its async semantics will silently surprise you: before your "
    "first eval, run `(__r/manual)`, unless you've already read it in this "
    "conversation."))


(defmethod handle "initialize" [{:keys [id params] ::keys [ctx]}]
  ;; What the caller said about itself lives with its session (§11) — the
  ;; transport minted one just before dispatching this.
  (session/client! (:session ctx)
    (select-keys params [:protocolVersion :clientInfo :capabilities]))
  ;; v0 accepts whatever version the client proposes and echoes it back. Pin a
  ;; supported set once we have seen what clients actually send — guessing at
  ;; it now would only produce a mismatch we cannot observe.
  (result id {:protocolVersion (:protocolVersion params)
              ;; listChanged: the server may announce that the tool set
              ;; changed — riding an open response stream, since this
              ;; transport initiates nothing (§4).
              :capabilities    {:tools {:listChanged true}}
              :serverInfo      server-info
              :instructions    (instructions)}))


(defmethod handle "ping" [{:keys [id]}]
  (result id {}))


(defmethod handle "tools/list" [{:keys [id] ::keys [ctx]}]
  (let [decls (declarations)]
    (session/fetched-tools! (:session ctx) decls)
    (result id {:tools decls})))


(defn- content
  "A handler returns either a string, or `{:text … :error? …}` when it wants to
   report failure in its own words rather than let the exception escape."
  [returned]
  (let [{:keys [error?] :as m} (if (map? returned) returned {:text (str returned)})]
    {:content (text (:text m)) :isError (boolean error?)}))


(defn- with-eviction-news
  "Append the caller's queued eviction news (§5, §11) — its entries went
   under another session's admission, and this is its next response of any
   kind. The queue clears only once the response actually goes out, riding
   the same `::delivered` hook as every other mark; a re-announcement after
   a timed-out response costs nothing, a lost one costs an id that stopped
   resolving in silence."
  [returned sid]
  (let [ids (seq (session/pending-evictions sid))]
    (if (and ids (map? returned))
      (-> returned
        (update :text str "\n;; evicted " (if (next ids) "ids " "id ")
          (str/join ", " ids))
        (update ::delivered
          (fn [mark!]
            (fn []
              (when mark! (mark!))
              (session/clear-evictions! sid ids)))))
      returned)))


(defmethod handle "tools/call" [{:keys [id params] ::keys [ctx]}]
  (let [{tool-name :name :keys [arguments]} params]
    (if-let [{:keys [handler]} (by-name tool-name)]
      ;; A tool that throws is not a protocol error: it is a result the model
      ;; is meant to see and react to, so it comes back as :isError rather
      ;; than as a JSON-RPC error.
      (try
        (let [returned (-> (handler arguments (assoc (or ctx {}) ::request-id id))
                         (with-eviction-news (:session ctx)))
              response (result id (content returned))]
          ;; As metadata, so it can never leak into the JSON: the transport
          ;; calls it once the response is actually written (§5) — unless
          ;; the client cancelled this request, in which case nobody reads
          ;; what gets written and nothing may be marked.
          (if-let [mark! (and (not (aborted? id)) (map? returned) (::delivered returned))]
            (with-meta response {::delivered mark!})
            response))
        (catch Throwable t
          (result id {:content (text (str (.getName (class t)) ": " (ex-message t)))
                      :isError true})))
      (error id -32602 (str "Unknown tool: " tool-name)))))


(defmethod handle :default [{:keys [id method]}]
  (error id -32601 (str "Method not found: " method)))


(defn- handle-message
  "One message. Notifications carry no :id and by JSON-RPC get no reply, so
   they return nil. `ctx` is transport fact — the peer address — carried
   under a namespaced key so it can never collide with protocol fields.

   One notification is acted on: an explicit client cancellation is
   id-directed cancel arriving over the wire — TaskStop sends it, verified
   2026-08-04 — unlike a dead socket, which is an accident and cancels
   nothing (§9)."
  [{:keys [id method params] :as message} ctx]
  (if (some? id)
    (handle (assoc message ::ctx ctx))
    (do (when (= "notifications/cancelled" method)
          (cancel-request! (:requestId params)))
      nil)))


(defn handle-payload
  "A parsed request body — one message or a batch of them. Returns the response
   payload, or nil when nothing in the batch warrants a reply."
  ([payload] (handle-payload payload {}))
  ([payload ctx]
   (if (sequential? payload)
     (seq (into [] (keep #(handle-message % ctx)) payload))
     (handle-message payload ctx))))
