(ns co.multiply.remontoire.session
  "Session state: who is connected, when they last spoke, and the
   conversational state that belongs to one dialogue rather than to the JVM —
   the sticky namespace, the told-once ledger's audience, queued eviction
   news (§11).

   A session is a handle the `create-session` tool mints and every other
   tool call names in its arguments, whichever MCP revision the client
   speaks. It lives in the model's context, not in the connection, so it
   outlasts a reconnect; it expires by silence, and a handle this server
   does not hold is refused, never adopted.

   The earlier revisions' `Mcp-Session-Id` names no session. It is a
   connection: what a client said at `initialize`, and which tool list it
   last fetched — the last section here.

   State lives behind `defonce` for the same reason the registry's does:
   library reloads must not end anyone's session.

   The scratch namespaces themselves — naming, furnishing, reaping — also
   live here: a session's scratch family is conversational state exactly
   like its sticky namespace, created on demand and gone with the session.
   So is its upload directory, where files a client hands the JVM are
   staged: minted on the first upload, deleted with the session."
  (:require [co.multiply.remontoire.rewire :as rewire])
  (:import
    [java.nio.file FileVisitResult Files Path SimpleFileVisitor]
    [java.nio.file.attribute FileAttribute]
    [java.util UUID]))


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
   functions directly; the wire never admits one."
  nil)


;; sid → {:last-seen ms, :client m, :current-ns sym, :evictions [ids],
;;        :manual-read bool, :nudged bool, :upload-dir Path, :address str}.
;; Keys are minted handles, never nil: a tool call naming no live session
;; is refused (§11).
(defonce ^:private state (atom {}))


(defn- now
  []
  (System/currentTimeMillis))


(def ^:private handle-letters
  "Consonants only, so a handle never spells a word: 20⁵ = 3.2 million
   handles at five letters."
  "bcdfghjklmnpqrstvwxz")


;; Every handle minted since the JVM started, live or not.
(defonce ^:private minted (atom #{}))


;; Held while a session's scratch is furnished and while a session's
;; family is swept, so that the two never interleave.
(defonce ^:private scratch-lock (Object.))


(defn- handle-length
  "How many letters the next handle gets, `used` having been minted: five,
   and one more each time the length in use is half spent — which at five
   is 1.6 million sessions in one JVM, so the rule is there for a draw to
   always find a free handle, not because anyone will see it act."
  [used]
  (loop [n 5 room (long (Math/pow (count handle-letters) 5))]
    (if (< (* 2 used) room) n (recur (inc n) (* room (count handle-letters))))))


(defn mint!
  "A fresh session (§11), born now, named by five random consonants that
   the model passes as an ordinary tool argument. Redrawn against every
   handle minted since the JVM started, not only the live ones: a handle
   names one session, and what an ended session left running — an eval, a
   task id in a client's hands — still speaks under it, so a second
   session by the same name would be written to by the first's leftovers.
   A collision is therefore only ever with a handle from a previous boot —
   and the space is sparse, so a typo or a stale handle almost always
   names nothing and is refused loudly, where a counter's dense space
   would land it silently in a neighbour."
  []
  (loop []
    (let [h        (apply str (repeatedly (handle-length (count @minted)) #(rand-nth handle-letters)))
          [before] (swap-vals! minted conj h)]
      (if (contains? before h)
        (recur)
        (do (swap! state assoc-in [h :last-seen] (now))
          h)))))


(defn live?
  "Whether `sid` names a session this server holds. A handle is a tool
   argument, and an unknown one is a stale or mistyped one (§11)."
  [sid]
  (contains? @state sid))


(defn refresh!
  "Refresh `sid`'s last-seen stamp if it is live, and say whether it was.
   Never mints: neither a call naming a handle nor liveness evidence that
   arrives beside one — a heartbeat write, a task poll, an upload — may
   create the session it names."
  [sid]
  (let [[before] (swap-vals! state
                   (fn [m] (cond-> m (contains? m sid) (assoc-in [sid :last-seen] (now)))))]
    (contains? before sid)))


(defn scratch-ns-sym
  "The scratch namespace `sid`'s evals start in. Per session, so two
   tenants' defs and requires cannot collide — named by the handle, behind
   an `s`. nil — the sessionless in-process caller, never the wire — keeps
   the shared scratch: no session to die with, nothing to reap."
  [sid]
  (if (nil? sid)
    'co.multiply.remontoire.scratch
    (symbol (str "co.multiply.remontoire.scratch.s" sid))))


(defn scratch-sub-ns-sym
  "The namespace symbol for `sid`'s scratch compartment named `label` —
   nested under the session's scratch, so uniqueness across sessions comes
   from the handle and the label stays whatever the caller chose. Within
   a session the same label always names the same namespace: the label is
   the compartment's identity. `label` is caller-chosen text, filtered to
   what a symbol segment can carry — `-` and `_` kept,
   `s`-prefixed when the survivors don't open with a letter, hashed whole
   when nothing survives."
  [sid label]
  (let [tag (into [] (filter #(or (Character/isLetterOrDigit (char %))
                                (#{\- \_} (char %))))
              (str label))]
    (symbol (str (scratch-ns-sym sid) "."
              (cond
                (empty? tag) (str "s" (Integer/toHexString (hash (str label))))
                (not (Character/isLetter (char (first tag)))) (apply str "s" tag)
                :else (apply str tag))))))


(defn furnish-scratch!
  "Create-or-refresh the scratch namespace `ns-sym`, returning it. Bare
   `create-ns` gives a namespace in which `+` does not resolve, so the
   full wiring is applied: clojure.core referred, and the composition
   surface — clojure.core *and* Quiescent (§5) — spelled exactly as
   committed source spells it: the `q` alias plus the `q`-prefixed macros
   referred bare, correct precisely because a scratch is a namespace its
   session owns. Idioms thereby copy verbatim between evals, the Quiescent
   skill, and source files; outside the scratch family, aliasing Quiescent
   is the model's own act (§4). `res` is how an id in one response becomes
   a value in the next expression, and `__r` the API's one alias — so a
   scratch resolves them all from the start.

   Idempotent, and re-applied in full on every call: `create-ns` returns
   the existing namespace, and re-referring keeps a scratch created by an
   older remontoire current with the refers a newer one expects. A name
   the caller has interned themselves is never replaced — their def wins,
   and Clojure notes the rejection on *err*.

   Rewired first: after a reload of a namespace the wiring names, `:as q`
   would otherwise find `q` still naming the replaced object, and `alias`
   refuses to rebind — every eval in the scratch would throw."
  [ns-sym]
  (binding [*ns* (create-ns ns-sym)]
    (rewire/rewire! *ns*)
    (refer-clojure)
    (require '[co.multiply.quiescent :as q
               :refer [q qdo qjoin qfor qlet qmerge if-qlet when-qlet]]
      '[co.multiply.remontoire.registry :refer [res evals]]
      '[co.multiply.remontoire.api :as __r])
    *ns*))


(defn furnish-for!
  "`furnish-scratch!` for a namespace of `sid`'s scratch family — the
   namespace, or nil, nothing created, when `sid` is not live. An eval
   outlives a session that ends under it, and a scratch furnished for it
   then would belong to nobody and never be reaped; furnishing and
   `drop!`'s sweep exclude each other, so a scratch is either made before
   the sweep and taken by it, or not made. The sessionless caller (nil)
   has no session to end.

   The namespace is returned for a reason: enter it as the object, never
   again by name — `in-ns` creates what it does not find, and would put
   back, bare, a scratch the sweep took in the meantime."
  [sid ns-sym]
  (locking scratch-lock
    (when (or (nil? sid) (live? sid))
      (furnish-scratch! ns-sym))))


(defn scratch?
  "Whether `ns-sym` belongs to a scratch family — any session's, the shared
   one included. Remontoire's own namespaces: no reloader ever touches them,
   which is why their mappings are rewired (§4) and a host's are not."
  [ns-sym]
  (let [base (str (scratch-ns-sym nil))
        n    (str ns-sym)]
    (or (= n base) (String/.startsWith n (str base ".")))))


(defn scratch-nses
  "`sid`'s scratch family as it exists right now: the session's scratch
   first when present, compartments after, alphabetical. Only namespaces
   that exist — nothing here mints.

   Output shape:
   [Symbol …]"
  [sid]
  (let [base   (str (scratch-ns-sym sid))
        prefix (str base ".")]
    (into []
      (comp (map ns-name)
        (filter #(let [n (str %)]
                   (or (= n base) (String/.startsWith n prefix)))))
      (sort-by str (all-ns)))))


(defn- delete-tree!
  "Delete `dir` and everything beneath it, best effort: what cannot be
   listed or deleted is left where it is, and nothing is thrown — a session
   ends whatever became of its files. Links are removed, never followed:
   what a tenant linked to from its upload directory is not the session's
   to delete."
  [^Path dir]
  (let [delete! (fn [^Path p]
                  (try (Files/deleteIfExists p) (catch Exception _ nil))
                  FileVisitResult/CONTINUE)]
    (try
      (Files/walkFileTree dir
        ;; A failed visit is a path that is unreadable or already gone, and
        ;; the walk goes on past it: an empty directory may still go.
        (proxy [SimpleFileVisitor] []
          (visitFile [p _] (delete! p))

          (visitFileFailed [p _] (delete! p))

          (postVisitDirectory [p _] (delete! p))))
      (catch Exception _ nil))
    nil))


;; One directory per JVM under the platform temp directory, the sessions'
;; own beneath it. Per JVM rather than a fixed name, so two JVMs on one
;; machine never share — or sweep — each other's uploads; removed at exit,
;; and a crashed JVM's is left to the platform's own temp cleaning.
(defonce ^:private upload-root
  (delay
    (let [root (Files/createTempDirectory "remontoire-" (make-array FileAttribute 0))]
      (Runtime/.addShutdownHook (Runtime/getRuntime)
        (Thread. ^Runnable (fn [] (delete-tree! root))))
      root)))


(defn upload-dir
  "Where `sid`'s uploaded files are staged, or nil when it has none yet.

   Output shape:
   Path"
  ^Path [sid]
  (get-in @state [sid :upload-dir]))


(defn upload-dir!
  "`sid`'s upload directory, minted on first use: a fresh directory named
   by the handle and a random suffix, so the path is never something a
   client constructs — it is asked for. nil for a sid that is not live: an
   upload must never mint the session it names."
  ^Path [sid]
  (locking upload-root
    (or (upload-dir sid)
      (when (live? sid)
        (let [dir (Files/createTempDirectory @upload-root (str sid "-")
                    (make-array FileAttribute 0))]
          (swap! state
            (fn [m] (cond-> m (contains? m sid) (assoc-in [sid :upload-dir] dir))))
          ;; A drop racing the mint leaves the directory unrecorded — take
          ;; it back rather than leak it.
          (or (upload-dir sid)
            (do (delete-tree! dir) nil)))))))


(defn upload-dirs
  "Every live session's upload directory, for those that have one.

   Output shape:
   {sid Path}"
  []
  (into {}
    (keep (fn [[sid {:keys [upload-dir]}]] (when upload-dir [sid upload-dir])))
    @state))


(defn- stale?
  "Whether a session's row says its silence has outlasted `expiry-ms`. No
   row, or one without a stamp, counts as ancient."
  [row]
  (< (or (:last-seen row) 0) (- (now) expiry-ms)))


(defn- end!
  "Take `sid`'s row if `go?` says so of it — asked in the step that takes
   it — and with the row what dies with a session: its scratch family and
   its upload directory. Whether it went."
  [sid go?]
  (let [[before after] (locking scratch-lock
                         (let [[_ after :as swapped]
                               (swap-vals! state (fn [m] (if (go? (get m sid)) (dissoc m sid) m)))]
                           (when (and (some? sid) (not (contains? after sid)))
                             (run! remove-ns (scratch-nses sid)))
                           swapped))
        gone?          (not (contains? after sid))]
    (when gone?
      (some-> (get-in before [sid :upload-dir]) delete-tree!))
    gone?))


(defn drop!
  "Forget `sid`. The registry releases a session's entry claims before
   dropping it — see `registry/release-session!`, which owns that order.
   Its upload directory is deleted, staged files and all. The session's
   scratch family goes with it — compartments included:
   conversational state dies with the session — so an eval somehow still
   running there fails loudly at its next form (`No namespace`) rather
   than working on in a ghost. The sessionless in-process caller's shared
   scratch family is never removed — no session to die with."
  [sid]
  (end! sid (constantly true))
  nil)


(defn expire!
  "`drop!`, if `sid`'s silence still outlasts `expiry-ms`, and whether it
   went. `expired` lists and this decides: a session that was refreshed in
   between — by a call that has since been let in — must stay, or that
   call's work is left with an owner the server refuses."
  [sid]
  (end! sid stale?))


(defn expired
  "Session ids whose silence has outlasted `expiry-ms`. Open streams keep
   their session fresh — every successful heartbeat write refreshes the
   stamp — so a session mid-await cannot expire under it (§11). A stampless
   entry counts as ancient: it can only arise from a write racing a drop,
   and releasing it again is harmless."
  []
  (into [] (keep (fn [[sid row]] (when (stale? row) sid))) @state))


(defn live-count
  "How many sessions are current — the `n` of the capacity formula (§11).
   Counted after the reap that precedes every admission, so the dead never
   inflate the pool."
  []
  (count @state))


(defn client!
  "What the caller said about itself — protocol version, client name and
   version, capabilities — recorded as the session is created. Kept per
   session because clients keep teaching us their actual shape (§4), and
   knowing which client and version a measurement was made against keeps
   the measurements honest."
  [sid m]
  (swap! state
    (fn [state] (cond-> state (contains? state sid) (assoc-in [sid :client] m))))
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


(defn address!
  "How `sid`'s client addressed its latest request — scheme and authority,
   `http://host:port` — kept as the best available guess at where the same
   client can reach this server's other doors. Guarded like `note!`: a
   request must not mint a session by being addressed somewhere."
  [sid address]
  (swap! state
    (fn [m] (cond-> m (contains? m sid) (assoc-in [sid :address] address))))
  nil)


(defn address
  [sid]
  (get-in @state [sid :address]))


(defn manual-read!
  "Mark `sid` as having read the manual — set when `api/manual` runs on its
   rails. Until it is set, the session's first eval response carries a
   one-line note (§4): the keys stay in the model's hands, but a fresh
   session doesn't start silent. Doubles as the compliance measurement the
   lean-instructions design is smoke-tested by. Guarded like `note!` — a
   dead sid is not recreated — which also makes the sessionless in-process
   caller a no-op."
  [sid]
  (swap! state
    (fn [m] (cond-> m (contains? m sid) (assoc-in [sid :manual-read] true))))
  nil)


(defn manual-read?
  [sid]
  (boolean (get-in @state [sid :manual-read])))


(defn nudged!
  "Mark `sid` as having been shown the manual note — set only once the
   response carrying it has actually gone out, the same discipline as every
   delivery mark (§5), so a response the client never received burns
   nothing. Once per session, deliberately: the server knows only session
   truth — the conversation may have read the manual through another server
   of the same build, or before a restart — and a note that repeats what
   the model already judged spends the footer's credibility, the channel
   evictions and truncations must be believed on. Guarded like `note!`."
  [sid]
  (swap! state
    (fn [m] (cond-> m (contains? m sid) (assoc-in [sid :nudged] true))))
  nil)


(defn nudged?
  [sid]
  (boolean (get-in @state [sid :nudged])))


(defn current-ns!
  "Where `sid`'s last eval ended — the sticky namespace is conversational
   state, so it lives and dies with the session (§11). Guarded like
   `note!`, and it matters most here: an eval outlives a session that is
   released under it, and its ending must not write the session back — a
   row is all it takes for a handle to be live."
  [sid ns-sym]
  (swap! state
    (fn [m] (cond-> m (contains? m sid) (assoc-in [sid :current-ns] ns-sym))))
  nil)


(defn current-ns
  [sid]
  (get-in @state [sid :current-ns]))


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


;; # Connections
;; ################################################################################
;;
;; The revisions before 2026-07-28 open with `initialize`, and the server
;; answers with an `Mcp-Session-Id` the client echoes on every request
;; (measured 2026-08-04). That id names a connection, not a session: a
;; reconnect arrives bare and is given a new one, while the handle in the
;; model's context carries on. A connection holds only what the wire itself
;; needs remembered between requests.

;; cid → {:last-seen ms, :client m, :fetched-tools decls}
(defonce ^:private connections (atom {}))


(defn connect!
  "A fresh connection id, for the `initialize` response's `Mcp-Session-Id`
   header. Connections silent for longer than `expiry-ms` are forgotten
   here: a client that reconnects abandons its old id without a goodbye
   (measured 2026-08-04), and every reconnect passes this way."
  []
  (let [cid    (str (UUID/randomUUID))
        t      (now)
        cutoff (- t expiry-ms)]
    (swap! connections
      (fn [m]
        (-> (into {} (remove (fn [[_ c]] (< (:last-seen c 0) cutoff))) m)
          (assoc cid {:last-seen t}))))
    cid))


(defn connected!
  "Refresh `cid`'s last-seen stamp, adopting an id never seen — a client
   that outlived a server restart keeps presenting its old one, and all it
   has lost is a record that refills. Returns `cid`."
  [cid]
  (swap! connections assoc-in [cid :last-seen] (now))
  cid)


(defn disconnect!
  "Forget `cid` — the spec's DELETE goodbye. Sessions are untouched: they
   belong to conversations, and expire by silence."
  [cid]
  (swap! connections dissoc cid)
  nil)


(defn connection-client!
  "What `initialize` said about the client on `cid` — protocol version,
   client name and version, capabilities. Copied onto each session created
   over the connection."
  [cid m]
  (swap! connections assoc-in [cid :client] m)
  nil)


(defn connection-client
  [cid]
  (get-in @connections [cid :client]))


(defn fetched-tools!
  "The tool declarations as `cid` last actually fetched them. Per
   connection, because `tools/list` names no session and each client's
   cache is its own (§4): a lost announcement re-announces on the next
   stream."
  [cid decls]
  (swap! connections assoc-in [cid :fetched-tools] decls)
  nil)


(defn fetched-tools
  [cid]
  (get-in @connections [cid :fetched-tools]))
