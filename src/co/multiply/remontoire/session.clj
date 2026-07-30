(ns co.multiply.remontoire.session
  "Session state: who is connected, when they last spoke, and the
   conversational state that belongs to one dialogue rather than to the JVM —
   the sticky namespace, the told-once ledger's audience, the tool-list
   fetch marker, queued eviction news (§11).

   Streamable HTTP sessions are server-minted: `initialize` gets an id in
   the `Mcp-Session-Id` response header and the client echoes it on every
   subsequent request (measured 2026-08-04, claude-code 2.1.220). Sessions
   are cheap and ephemeral — a reconnect abandons the old one without the
   spec's DELETE goodbye (measured the same day) — so everything here
   expires by silence, and an id never seen before is adopted rather than
   404'd: after a server restart the client keeps presenting its old id,
   and forcing a re-initialize would buy spec strictness at the price of
   churn. A deliberate deviation, recorded in §11.

   State lives behind `defonce` for the same reason the registry's does:
   library reloads must not end anyone's session."
  (:import [java.util UUID]))


(def expiry-ms
  "How long a session may stay silent before it is presumed gone and its
   claims released. Deliberately the await clamp's own value — `mcp` derefs
   this var for it, so the two cannot drift: any wait the protocol will
   grant, the session survives to collect. Generous over stingy until
   proven insufficient (2026-08-04); the cost is bounded — a dead session's
   unread entries ride above the capacity for at most a day."
  86400000)


(def ^:dynamic *session*
  "The session an eval runs for — bound by `evaluate` on both attribution
   rails exactly as `tee/*bank*` is (§11), so code like `(evals)` can tell
   the caller's entries from another session's. nil at the root means a
   sessionless in-process caller — a human at the actual REPL calling api
   functions directly; the transport never admits one (bare requests are
   400'd, 2026-08-06)."
  nil)


;; sid → {:last-seen ms, :client m, :current-ns sym, :fetched-tools decls,
;;        :evictions [ids], :manual-read bool}. Keys are transport-minted
;; ids, never nil: a
;; request without one is refused at the door (http, 2026-08-06).
(defonce ^:private state (atom {}))


(defn- now
  []
  (System/currentTimeMillis))


(defn seen!
  "Refresh `sid`'s last-seen stamp, adopting an unknown id — a restarted
   server's returning client presents an id minted before the restart.
   Returns `sid` so establishing a session reads as one expression."
  [sid]
  (swap! state assoc-in [sid :last-seen] (now))
  sid)


(defn mint!
  "A fresh session, born now. The transport puts the id in the `initialize`
   response's `Mcp-Session-Id` header; the client echoes it thereafter."
  []
  (seen! (str (UUID/randomUUID))))


(defn scratch-ns-sym
  "The scratch namespace `sid`'s evals start in. Per session, so two
   tenants' defs and requires cannot collide — named by the same 8-char id
   prefix `:by` attribution wears, `s`-prefixed into a legal symbol
   segment and filtered to what a symbol can carry, since an adopted sid
   is client-chosen text (hashed whole when nothing survives the filter).
   nil — the sessionless in-process caller, never the wire — keeps the
   shared scratch: no session to die with, nothing to reap."
  [sid]
  (if (nil? sid)
    'co.multiply.remontoire.scratch
    (let [tag (into [] (comp (filter #(Character/isLetterOrDigit (char %)))
                         (take 8))
                sid)]
      (symbol (str "co.multiply.remontoire.scratch.s"
                (if (seq tag)
                  (apply str tag)
                  (Integer/toHexString (hash sid))))))))


(defn drop!
  "Forget `sid`. The registry releases a session's entry claims before
   dropping it — see `registry/release-session!`, which owns that order.
   The session's scratch namespace goes with it — conversational state
   dies with the session — so an eval somehow still running there fails
   loudly at its next form (`No namespace`) rather than working on in a
   ghost. The sessionless in-process caller's shared scratch is never
   removed — no session to die with."
  [sid]
  (swap! state dissoc sid)
  (when (some? sid)
    (remove-ns (scratch-ns-sym sid)))
  nil)


(defn expired
  "Session ids whose silence has outlasted `expiry-ms`. Open streams keep
   their session fresh — every successful heartbeat write refreshes the
   stamp — so a session mid-await cannot expire under it (§11). A stampless
   entry counts as ancient: it can only arise from a write racing a drop,
   and releasing it again is harmless."
  []
  (let [cutoff (- (now) expiry-ms)]
    (into []
      (keep (fn [[sid {:keys [last-seen]}]]
              (when (< (or last-seen 0) cutoff) sid)))
      @state)))


(defn live-count
  "How many sessions are current — the `n` of the capacity formula (§11).
   Counted after the reap that precedes every admission, so the dead never
   inflate the pool."
  []
  (count @state))


(defn client!
  "What `initialize` said about the caller — protocol version, client name
   and version, capabilities. Kept per session because clients keep
   teaching us their actual shape (§4), and knowing which client and
   version a measurement was made against keeps the measurements honest."
  [sid m]
  (swap! state assoc-in [sid :client] m)
  nil)


(defn client
  [sid]
  (get-in @state [sid :client]))


(defn note!
  "A self-description for the roster — what this session is for, in its own
   words (§11). Conversational state like everything here: it dies with the
   session, which is right, because so does the purpose."
  [sid s]
  (swap! state
    (fn [m] (cond-> m (contains? m sid) (assoc-in [sid :note] (str s)))))
  nil)


(defn roster
  "Every live session as data — id, last-seen, client info, note — for the
   API layer's `sessions` listing, which adds what only other namespaces
   know (entry counts, who is asking)."
  []
  (mapv (fn [[sid m]]
          (assoc (select-keys m [:last-seen :client :note]) :sid sid))
    @state))


(defn manual-read!
  "Mark `sid` as having read the manual — set when `api/manual` runs on its
   rails. Until it is set, eval responses carry a one-line unread note (§4):
   the keys stay in the model's hands, but ignorance doesn't stay silent.
   Doubles as the compliance measurement the lean-instructions design is
   smoke-tested by. Guarded like `note!` — a dead sid is not recreated —
   which also makes the sessionless in-process caller a no-op."
  [sid]
  (swap! state
    (fn [m] (cond-> m (contains? m sid) (assoc-in [sid :manual-read] true))))
  nil)


(defn manual-read?
  [sid]
  (boolean (get-in @state [sid :manual-read])))


(defn current-ns!
  "Where `sid`'s last eval ended — the sticky namespace is conversational
   state, so it lives and dies with the session; a reconnect starts back
   at scratch (§11)."
  [sid ns-sym]
  (swap! state assoc-in [sid :current-ns] ns-sym)
  nil)


(defn current-ns
  [sid]
  (get-in @state [sid :current-ns]))


(defn fetched-tools!
  "The tool declarations as `sid` last actually fetched them. Per session,
   for the same reason the marker exists at all (§4): a lost announcement
   re-announces on the next stream, and each client's cache is its own."
  [sid decls]
  (swap! state assoc-in [sid :fetched-tools] decls)
  nil)


(defn fetched-tools
  [sid]
  (get-in @state [sid :fetched-tools]))


(defn note-evictions!
  "Queue eviction news for `sid`: its entries went under another session's
   admission, and an id that silently stops resolving is §5's nasty failure
   mode regardless of whose admission did it. Drained into the owner's next
   response. A sid no longer live is not recreated — its reader is gone."
  [sid ids]
  (swap! state
    (fn [m]
      (cond-> m
        (contains? m sid) (update-in [sid :evictions] (fnil into []) ids))))
  nil)


(defn pending-evictions
  [sid]
  (get-in @state [sid :evictions]))


(defn clear-evictions!
  "Called only once the response carrying the news has actually gone out —
   the same discipline as every delivery mark (§5)."
  [sid ids]
  (swap! state
    (fn [m]
      (cond-> m
        (contains? m sid)
        (update-in [sid :evictions] #(into [] (remove (set ids)) %)))))
  nil)
