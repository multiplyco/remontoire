# 11. Open questions

- ~~**Trace retention.**~~ Resolved 2026-07-31: strong references, released wholesale. See §5.
- ~~**Output attribution: scope or thread-local.**~~ Resolved 2026-07-31: scope. See §5.
- **Registry scope across MCP sessions.** Streamable HTTP carries a session id, so two agents — or an agent and a human
  in Cursive — can be connected at once. Global is the leaning: ids then mean the same thing to everyone, and you can
  inspect from the editor what the agent just evaluated, which is most of why a shared JVM is worth having. The cost is
  that concurrent callers interleave ids and evict each other's entries out from under themselves. Per-session
  registries fix that and lose the shared view.

  **Measured 2026-08-04**: the spine is real — a server that answers `initialize` with an `Mcp-Session-Id` header gets
  the id echoed back on *every* subsequent request from claude-code 2.1.220 (`notifications/initialized`, `tools/list`,
  `tools/call` all carried it). The client doesn't bring an identity; the server assigns one, exactly as the spec says.
  A connection initialized before the header existed keeps sending nothing — the id-less legacy shape is observable,
  not an error. A *reconnect* (measured same day) is a clean rebirth: the fresh `initialize` arrives bare rather than
  dragging the old id, and the client echoes whatever new id it is minted. So sessions are cheap and ephemeral —
  one conversation can span several — which sets two requirements: owner tags on registry entries must outlive their
  session gracefully (the entries stay reachable by id; only the dead session's *delivery* claims lapse), and anything
  owed to a session that will never call again — told-once news, the unread-results eviction shield — needs a
  liveness notion so it doesn't accumulate as permanent debt.

  On that liveness notion, two more measurements (2026-08-04): the client sends **no DELETE** on reconnect — the old
  session is abandoned without the spec's terminating request — so expiry-by-silence (a last-seen stamp per session,
  refreshed by every request) is the *only* release mechanism, not a backstop. Release means marking the dead
  session's entries seen: the eviction shield lifts, the entries join the LRU pool still reachable by `res` until
  capacity pressure claims them — death degrades gracefully rather than deleting anything. And the client *does*
  send a GET right after `notifications/initialized` — offering to open the spec's server-initiated SSE channel,
  session id attached — which this server 405s and the client tolerates. News keeps riding responses (§5), but if a
  standing channel is ever wanted, the client is already knocking.

  Direction settled by discussion the same day: split the concern rather than choose isolation or sharing wholesale.
  *Session-scoped* — the conversational state whose single-caller assumptions actually break with two clients: sticky
  namespace, news/told-once delivery, the `fetched-tools` marker, the client-info capture. *Shared* — the registry
  substrate: one JVM, global ids, every entry tagged with the session that minted it; `res` reaches anything, so two
  models on the same JVM can hand results to each other by id. Delivery to the owning session is what counts as
  delivered. Crowding is met with capacity extension rather than partitioning.

  **Built 2026-08-04**, all mechanisms verified live against claude-code and curl side by side. The shape as shipped:
  the `session` namespace holds `defonce` state keyed by minted id (nil is a real key — the shared identity of
  id-less callers; *revoked 2026-08-06: bare requests are 400'd, nil is the in-process caller only — see the MCP
  revision record below*); the transport mints at `initialize` and refreshes on every request, and successful heartbeat
  writes refresh too, so open streams are liveness and a session mid-await cannot expire under its own wait. Unknown
  ids are *adopted*, not 404'd — a deliberate spec deviation: after a server restart the client keeps presenting its
  old id, and forcing re-initialization buys strictness at the price of churn. Expiry is by silence at the shared
  24 h constant (`session/expiry-ms`, derefed by the await clamp so the two cannot drift), swept lazily at admission
  — no reaper thread. Release lifts the eviction shield (entries marked seen and told, still `res`-able until LRU
  pressure claims them): death degrades, it does not delete. The spec's DELETE is honored as immediate release
  (measured: claude-code never sends it). `evals` lists everything, entries from other sessions tagged `:by` —
  attribution, not isolation; the caller's identity rides both attribution rails as `session/*session*`, beside the
  bank. Evictions inflicted on another session's entries are queued and drain into that owner's next response of any
  kind, cleared only after the write, like every delivery mark. Capacity extends by +10 per live session beyond the
  first — one session sees the configured cap exactly; the extension was observed expanding (no evictions while a
  second session lived) and contracting (a six-entry convergence the admission after its DELETE). Accepted cost: a
  reconnect resets the sticky namespace, since a new session is genuinely a new conversation as far as the server
  can tell.

  **Two-Claude exercise, 2026-08-05**: two Claude Code instances worked the same JVM concurrently, one running a
  scripted battery and writing notes, the other clobbering alongside. Everything held: owner tags read as
  attribution on both sides (interleaved ids registered as \"normal id-sequence gaps\", not confusion); values were
  handed both directions by id (`res 203` / `res 208`); sticky namespaces moved independently; output attribution
  never leaked a line across sessions; settled-news announced each side's ids to that side only, in both the
  positive and negative cases. Findings with edges: an ordinary client restart (which also picked up a client
  update, 2.1.220 → 2.1.222) re-initialized one side between days — one human conversation sprayed three session
  ids, vindicating adoption and orphan-grace, but meaning **a session must never bake its current id into data;
  it asks the rails**
  (`sc/ask session/*session*`) — an `__r` helper is a candidate. And the shared substrate's sharp edge is real:
  `res`-ing another session's *running* entry would mark it delivered and eat the owner's settled-news
  announcement — any delivery counts, by design; collaborators read each other's settled work, not their in-flight
  ids.

  **Cross-session affordances, built 2026-08-05** — the exercise's frictions, turned into three `__r` functions.
  `sessions`: the roster — sid, idleness, client, note, owned-entry count, `:you` on the caller's row — answering by
  one read what the second tenant reconstructed by inference. `introduce!`: a self-description on that roster, a
  commit message for a session; it dies with the session, which is right, because so does the purpose. `release!`:
  punctuation — `(release!)` is a comma, marking the caller's settled unread entries absorbed and evictable while
  running work stays a live concern; `(release! true)` is a period, cancelling the caller's running entries too
  (cancel-authors-the-outcome doctrine; each turns evictable only when the cancellation bites). Both return
  `{:released [...] :cancelled [...]}` — the report *is* the audit the caller would otherwise do by sifting its
  conversation. Also the polite goodbye, in a system whose clients never send DELETE. One bite mid-build: the first
  period cancelled the eval that spoke it — the release-eval is itself a running entry the caller owns. Exempted by
  its bank, the eval's identity on the rails: punctuation ends the leg behind you, not the sentence being spoken.
- **Is `impl.cljc:443` the only site a task receives a scope?**
  `race.cljc:89`'s `(with-scope (call/getScope t) …)` may imply other creation paths needing the same hook. A ten-minute
  read of `impl.cljc` decides whether the incision is one line or four.
- **babashka compatibility.** `rewrite-clj` is bundled in bb, `clj-kondo` is not. Either split the parse layer
  (bb-capable) from the analysis layer, or shell out to the `clj-kondo` binary. Affects whether `outline` runs in a
  100ms process.
- **Print-budget defaults** — purely empirical, to be learned from the first week of use.
- ~~**The model API layer.**~~ Built 2026-08-04 — see §4; contents tuned in use since. The teaching surface's
  history — the `instructions` arc, the manual's delivery, and the smoke tests that shaped both — lives in the
  logbook (`doc/logbook.md`), as do the test-suite freeze and the first in-situ smoke test.
- **The sessionless identity endpoint, `GET /probe` (2026-08-06, built for deployment rollouts).** Rollout
  context first, worth keeping: where each deployed app container joins the tailnet as its own ephemeral node
  via `tailscaled --tun=userspace-networking`, inbound tailnet connections are proxied to loopback — so the
  default `:bind "127.0.0.1"` is already the tailnet-only exposure there, no binding change needed. The consumer
  to design for is an operator-side discovery helper — a Babashka script, say — that enumerates tailnet nodes
  and regenerates `.mcp.json` from the Remontoire instances it finds, which means it must verify "a live
  Remontoire answers here" — port-open is not evidence, and mid-rollout many environments would still run
  pre-Remontoire builds. The natural verification, an MCP `initialize`, mints a session that lives to
  `expiry-ms` of silence: discovery probes from several operators, several times a day, against every
  environment would fill the roster with ghosts — polluting precisely the surface built for tenant mutual
  awareness. The sessionless 400 was the other
  candidate fingerprint: identifying the server by an error message, and learning nothing about it. So the
  transport grew a second context: `GET /probe`, plain JSON, no session minted, no registry or session state
  touched — `{"server": "remontoire", "version": …, "describe": …}`. `server` is the fixed discriminator the
  helper keys on. `describe` is the `init!`-time self-description — §4's "the environment names itself," here
  behind a sessionless door, so discovery reports each environment in its own voice. `version` reads `mcp.clj`'s
  `server-info`, a site the release checklist already syncs — no fifth version location. Consumers key on field
  presence, not shape completeness, so fields (session count, uptime) can accrete without coordination. GET-only
  and curl-friendly, doubling as a post-deploy sanity check and a monitoring target; exposure and origin policy
  are exactly `/mcp`'s, and no auth — the tailnet is the lock, as everywhere. Consumer contract for the record:
  connection-refused or timeout means no Remontoire; a 404 on `/probe` means a pre-0.1.2 Remontoire (acceptable
  mid-rollout, when no deployed environment has any version yet); a 200 with `"server": "remontoire"` means
  verified alive.
- **Output attribution rides two rails** (measured 2026-08-04; built the same day — see §4). Quiescent captures the scoped-library scope at task
  construction (`impl.cljc:444`) and re-establishes it around every body, so a scoped var bound around eval-chain
  construction reaches every link and every descendant task, through compel moats (measured: nested and compelled
  tasks both see it). Clojure conveyance (`future`/`agent`/`bound-fn`) carries binding frames but not scope
  (measured: `:conveyed` via frames, nil via scope) — so the bank is also thread-bound per link, and `sc/ask`'s
  var-deref fallback makes one tee-side lookup cover both rails: `(future (println …))` from eval'd code attributes
  with no helper. Irreducibly manual: raw Java threads, executor callbacks, and futures launched from *nested* tasks
  (no frames there, and futures don't carry scope) — those need spawn-site capture, a `bound-fn`-for-scope helper
  that likely belongs in `scoped` itself and is exposed through the API layer. Third-party internal threads pass
  through the tee unattributed — visible on the console, never mis-banked. Attribution follows the task tree, which
  is the same line structured concurrency draws.
- **Extraction timing.** Keep the entry/site/node shapes moving in one place until they stop changing; version churn
  while the element types are unsettled buys nothing.
- **The 2026-07-28 MCP revision** (assessed 2026-08-05 from the changelog and spec pages). The spec went stateless:
  `initialize`/`initialized` and `Mcp-Session-Id` are removed, every request carries protocol version, capabilities
  and `clientInfo` in `_meta`, and a mandatory `server/discover` RPC advertises versions and carries the
  `instructions` field — so the teaching surface keeps its slot. `clientInfo` is `{name, version}`, not
  instance-unique: there is no covert way to correlate requests into a session, and the changelog's migration line
  is aimed straight at this design — "servers that need cross-call state use explicit, server-minted handles passed
  as ordinary tool arguments."

  *Impact now: none.* Dual-era servers are explicitly blessed (legacy `initialize` and stateless `_meta` served
  concurrently on one endpoint, era selected by how the client opens), claude-code speaks the legacy handshake, and
  deprecations carry a twelve-month minimum window. The standing version-pinning item acquires its content: pin the
  legacy revision we answer today, go dual-era when claude-code actually sends modern requests, not before. The
  mechanical adoption items are small — `Mcp-Method`/`Mcp-Name` header validation (`HeaderMismatchError` −32020),
  `ttlMs`/`cacheScope` on `tools/list`, deterministic tool order (three tools; already deterministic). Non-impacts:
  Roots/Sampling/Logging deprecated (unused here), HTTP+SSE deprecated (this is Streamable HTTP), SSE resumability
  removed (never built), the GET channel replaced by `subscriptions/listen` (this server 405s the GET already), MRTR
  (no server-initiated requests made), OAuth changes (the tailnet is the auth layer). One flattering convergence:
  experimental tasks became the `io.modelcontextprotocol/tasks` extension — poll-based `tasks/get`, servers may
  return task handles unsolicited — the registry's shape arriving at the protocol level. Not needed (in-band ids are
  richer: composition, cross-session handoff), but a possible someday-surface for generic clients, and decent
  evidence for the eval-as-task-under-an-id grain.

  *The one real design question is the session,* and it stays open. Everything keyed off the transport sid — scratch
  namespace, sticky ns, delivery claims, `:by`, expiry, `release!` — would key off a server-minted handle threaded
  as a tool argument; the blast radius is `mcp.clj` alone, since `session` already takes an opaque sid and does not
  care where it came from. Two positions recorded from the day's discussion. For: a handle in tool-space survives
  reconnects and client restarts — the "one conversation sprayed three session ids" finding gets its real fix, since
  any instance could deliberately assume any recorded session; sessions were never a security boundary anyway (the
  registry is shared and `res` reaches everything), so assumability inside the tailnet trust is a feature. And the
  concern was arguably never transport-shaped: scratch/claims/attribution are application state that borrowed
  transport keying because it was free — the model already threads server-minted handles competently, because eval
  ids *are* that, and "ids are how sessions hand work to each other" extends one level up to the sessions
  themselves. Against: it puts what feels like plumbing on the model, a per-request affordance of exactly the kind
  the smoke tests showed gets stripped unless the laziest call is the compliant call. Mitigation if built: the
  response footer carries the handle back on every response, so it is ambient rather than remembered. A related
  idea considered and shelved: collapse the surface to a single `introduction` tool that returns instructions and
  unlocks the rest interactively. It runs against the revision's grain — the same changelog makes list endpoints
  per-connection-invariant and cacheable (`ttlMs`, `cacheScope: public`), so per-caller tool gating is precisely
  what statelessness forbids, and the carrying job it would do is already homed in `server/discover`'s
  `instructions`. Decision: no build work now; session-as-handle gets its own design pass when a modern client
  exists to measure against.

  **The handle-less call, settled for the legacy wire and narrowed for the modern one (2026-08-06).** The first
  draft of this record had a handle-less request degrading to a shared anonymous session — the nil-sid identity the
  transport already carried. Interrogated a day later: was that path ever actually walked? Measured live — no. The
  session table held exactly two sids, both real tenants; the shared scratch namespace had never been minted; every
  registry entry had a real owner. Structurally so: every MCP client must `initialize` before anything else and
  claude-code echoes its id on every request, so the id-less population is hand-written curl and broken clients —
  and for a broken client, silent state-pooling is the worst answer: two such tenants would share defs, sticky ns
  and delivery claims with no signal anything was shared. So the wire went strict the same day: a non-initialize
  POST without `Mcp-Session-Id` is 400'd (`http/session!` returns nil, `serve!` refuses — the spec's own SHOULD,
  and the DELETE branch was already doing it), and nil narrowed to the sessionless *in-process* caller — a human at
  the actual REPL — who keeps the shared scratch and reads as `:by nil`. Cost accepted: curl follow-ups must carry
  the header, which makes probes exercise the real path. For the modern era the anonymous-degradation option is
  gone from the menu, leaving two live candidates: *mint-on-missing* — a handle-less tool call implicitly births a
  session and the footer announces it, so every bare caller is a fresh tenant and collision is impossible, at the
  price of a model that drops its handle silently forking into a new identity mid-conversation; or *explicit
  mint* — a dedicated session-minting tool, every other call erroring without a handle, the error text itself
  teaching the incantation — `initialize` reborn as an in-band tool, loud where mint-on-missing is forgiving. The
  strictness decision leans the same way the legacy one did: the loud error is the one that never pools strangers
  and never forks anyone silently. To be settled by measurement when a modern client exists. Verified against the
  revision's authorization spec (2026-08-06): auth is untouched by statelessness and has not moved to the model
  layer — the OAuth 2.1 bearer token rides the `Authorization` header on *every* request, was never coupled to the
  session id, and never appears in tool arguments or `_meta` — so token and handle are cleanly orthogonal: the
  token says who may talk to the server at all, the handle says which conversation this is, and session
  assumability is bounded by transport credentials (here: the tailnet), not by the handle's secrecy.

  That orthogonality suggested an inversion, same day: don't hand the model a handle — *infer the session from the
  credentials the caller holds*. It buys everything the handle-burden objection feared losing: no model-layer
  plumbing, identity stable across reconnects and restarts (the sprayed-session-ids problem dissolves), and it is
  spec-aligned — `cacheScope: "private"` exists precisely because responses may vary per-credential; the revision
  evicted per-connection state, not per-principal state. But the grain is wrong as the *whole* key, and our own
  measurements say so: a credential names a principal, a session names a conversation, and the two-Claude exercise
  ran two concurrent conversations from one principal — same machine, same tailnet identity, and under any
  realistic OAuth the same user token, since harnesses mint per user, not per conversation. Key sessions by
  credential alone and your own tenants collapse into one scratch — the pooling the 400 refuses, rebuilt inside
  one account. (The web analogy: login-keyed state is right for the shopping cart, wrong for the wizard's current
  step; sticky ns, told-once news and scratch defs are all wizard.) Where it lands: as the *outer* key. The
  credential gives the principal — attribution, the security boundary, the roster's "who" — and a handle
  distinguishes conversations within it. The composition improves both open candidates: the handle-less fallback
  becomes per-principal rather than global, so forgetting the handle degrades to "your own tenants share" — the
  two-Claude exercise's measured, survivable degradation — never "strangers pool"; and assumability is scoped for
  free to conversations your credential owns, with handle secrecy never load-bearing. The principal rail already
  exists in the current transport unused: the tailnet's credential *is* the peer address, and `ctx` carries
  `:peer` today.

  Pushed one level further (2026-08-06): the credential's grain is mint-time policy, not a fact of nature — rig the
  minting and reauthentication ≈ new session. The precise rig is session ≡ *grant*, not token string: naive
  TTL-forced reauth would conflate "new conversation" with "expired token mid-conversation" and fork sessions on
  refresh; the clean invariant is a session claim the AS carries through token refresh (same grant → same session)
  and renews on fresh authorization (new dance → new conversation). Running the AS ourselves — RFC 9728 discovery
  lets the MCP server name any AS, including one in the same JVM — makes that claim ours to mint. Two dependencies
  and one new cost, though. Dependencies: client storage behavior (claude-code stores MCP credentials per *server*
  in shared config, so concurrent instances would present one grant and collapse again — controllable when we own
  both ends, a bet otherwise), and OAuth-mode only (the tailnet's peer-address credential has no reauth to rig).
  Cost: conversation lifecycle moves into the auth layer — birth becomes a human-gated dance (friction for
  unattended agents), and resumption/assumption becomes credential sharing, i.e. exfiltrating a bearer token,
  precisely what token hygiene forbids. Registry-id work handoff survives regardless; it is session *assumption*,
  the feature that motivated explicit handles, that turns into the thing auth exists to prevent. Net: the design
  space collapsed to one question — *where does the conversation handle live?* Tool-space: model-visible,
  forgettable, cheap to hand off. Credential-space: invisible, unforgeable, handoff-hostile,
  client-behavior-dependent. Same handle, different substrate, opposite failure modes.

  The credential-space option, taken to its logical end (2026-08-06): co-opt the auth mechanics so the credential
  has an infinite (or very long) lifespan — every new client dances once and gets its own, nothing ever refreshes —
  or, if tokens must expire, the co-located AS re-keys the session at refresh (it sees the grant lineage; renaming
  the key is state migration, the stateful twin of an in-token session claim). Both implement one invariant: new
  dance = new session, one instance = one continuous identity. What this *is*, named plainly: a session cookie.
  The spec removed `Mcp-Session-Id`, but `Authorization` is the one header clients must still store and replay on
  every request — the last cookie jar in MCP — and the rig moves the remembering into the client's credential
  storage, which is the durable kind (survives restarts; the actual win). The infinite lifespan is safe precisely
  because the token's security function is vestigial — the tailnet is the lock, the token a name tag — and
  becomes wrong the moment the endpoint would face the public web. Symmetry with the shipped design: session
  state still expires by silence; an eternal credential outliving its session state is reborn under the same key,
  exactly today's adopted-unknown-sid semantics. The whole rig is the legacy design with mint moved from
  `initialize` into the auth dance. Unchanged from above: the client-behavior dependency (shared credential
  storage collapses concurrent instances into one cookie) and handoff-hostility (assuming a session = copying the
  credential). Established as feasible, not endorsed — the cost of a session id in tool calls may simply be worth
  paying.

  Where Remontoire sits in the space the revision was drawn for, stated plainly to close the record (2026-08-06):
  at the opposite pole from the deployment statelessness enables, and legitimately so. The stateless core exists
  for servers that are interchangeable windows onto state living elsewhere; here *the state is the server* — the
  registry holds live Task objects, scratch namespaces are interned in this runtime, `res 12` is an object
  identity in this heap, and the conversation is *about* this JVM. Round-robin is not merely unsupported, it is
  meaningless: a second instance would answer about a different world, and serialization cannot rescue what is
  unserializable by premise — access to the running, the loaded, the live *is* the product. This is conformant:
  protocol statelessness means the protocol does not carry the session, not that the server has none. Restart is
  the already-lived semantics, sharpened: expiry-by-silence made sessions ephemeral from birth, and a restart is
  the same cliff, total — clients reborn empty under adopted ids. One nuance, raised and then rightly narrowed: a
  *signed* credential could survive the cliff (verifiable by key alone, no table) — but only if the key material is
  durably stored, and the coherent choice here is that it is not. Opaque token in a defonce atom: authentication is
  membership, not decoding, and "not in the atom" means "not authenticated." That makes credential mortality match
  content mortality, which is the honest semantics — the signed variant's continuity-of-identity would sail a
  tenant through the restart recognized, to discover piecemeal that every id dangles and every def is gone; the
  atom variant makes the discontinuity loud at the door (401, the world you knew is gone, dance again), the same
  principle as the bare-request 400 and `drop!`'s loud ghost-eval failure. Identity that outlives everything it
  names is not continuity but a haunting. Noted with some satisfaction: opaque-token-in-an-atom is *literally the
  shipped sid machinery* — UUID minted, defonce atom, client replays per request, unknown-at-the-door handled
  there — relocated from `Mcp-Session-Id` to `Authorization`. The credential-space option at its simplest
  implementation reconverges on what is already built, wearing a different header; the genuine deltas are who does
  the dance (auth flow vs `initialize`) and which client storage remembers (credential store — durable — vs MCP
  session state — connection-lived).

