# 4. The connection (v0)

## Transport: MCP, in-process, over HTTP

The library is loaded into the host JVM, so it needs no REPL to reach — it *is* in the process it inspects. The MCP
server therefore runs inside the host and speaks Streamable HTTP directly. No nREPL, no socket REPL, no intermediate
translator process.

The REPL-protocol question is settled by deletion rather than by choice. For the record, neither candidate survived on
merit:

- **nREPL's interrupt is the weaker of the two cancel mechanisms available.** It is `Thread/interrupt` on the eval
  thread — `Thread/stop` throws `UnsupportedOperationException` on JDK 20+, so on JVM 25 the interrupt is all there is.
  It does nothing to a thread parked on a non-interruptible operation, and nothing at all to threads the expression
  spawned, which is precisely the hang §9 describes. Quiescent's cancellation propagates to children. The general
  limitation remains cooperative, though (noted 2026-08-04): Quiescent guarantees the cascade *within* the framework;
  the leaves depend on good citizenship — interruptible operations die properly, tight loops and interrupt-swallowers
  linger, and stateful things with external control channels want a `q/finally` rigged at the leaf
  (`(q/finally t (fn [_] (.close socket)))`) so teardown reaches what interruption cannot. The JVM is cooperative;
  this is its shape, not ours.
- **Multiplexing is unnecessary, not merely available elsewhere.** A wedged eval poisons a request/response channel only
  if its response never arrives; the ~2s await then `:status :running` rule frees the channel on schedule, so
  `(cancel 14)` goes out as the next ordinary request. Control is in-band data rather than a protocol operation — the
  same move §3 makes against options.
- **Out-of-band output demuxing is the registry's job, and it does it better.** `(out 12)` stays re-readable after the
  eval returns, which a wire protocol cannot be. nREPL would have actively hurt here: it rebinds `*out*` per session
  thread, so main-thread printlns would land in `:out` messages while virtual-thread printlns land in the root tee,
  splitting the log exactly where §5 needs it whole.

Every distinguishing feature being subsumed, what remained was a hop between two processes — and the hop is what got
cut.

**The HTTP server is `com.sun.net.httpserver.HttpServer`** — in the JDK since Java 6, module `jdk.httpserver`, a
supported API despite the `com.sun` prefix. Zero jars on the host classpath. HTTP/1.1 only and no WebSocket, neither of
which MCP needs; `sendResponseHeaders(200, 0)` selects chunked encoding, which is what Streamable HTTP's server→client
SSE channel requires. **Set an executor** — the default handles exchanges serially on the dispatch thread — and make it
**Quiescent's own virtual executor** (amended 2026-08-01, from a bare `newVirtualThreadPerTaskExecutor`): the same
substrate *and* the same captured classloader as every task thread, so by-name resolution behaves identically whether
code runs in a handler or in a task. It also puts §9's rooting on firm ground: a handler thread is not a task, so a
task constructed in one has no ambient parent — verified through the live connection, a task created during one
exchange survived that exchange settling and completed normally.

**A `tools/call` is answered with an SSE stream** (implemented and measured 2026-08-04); the quick protocol methods
stay on plain JSON, so an SSE quirk can never break the handshake. The stream opens immediately — headers and a
comment — then heartbeats every 15 s while the eval runs, then the JSON-RPC response as the stream's one message, then
closes. A heartbeat is a `notifications/progress` message when the client attached a `progressToken` (Claude Code
does, on every call) and a bare SSE comment otherwise — a load-bearing distinction, because the client's idle timer
counts MCP messages, not bytes. Measured against Claude Code, one probe per claim: a 65 s call died at the 60 s
first-byte timer under plain JSON; with SSE open, a 115 s eval returned inline; at 120 s the client moves the call to
a background task and delivers the result asynchronously as a task notification — the call is never abandoned; with
comment-only heartbeats both long probes died at exactly 300 s of "no response or progress"; with progress-message
heartbeats, 10- and 15-minute evals completed end-to-end. Remaining bounds: the client's ~28 h wall-clock default and
the 300 s idle timer the beats feed. A failed heartbeat write is also the only way the JDK server ever notices a
hangup — detection by symptom, within a beat or two — and the final write failing is what withholds the delivery
marks (§5).

**The progress `message` field renders, verbatim** (measured 2026-08-04: Claude Code shows it under "Calling
remontoire…", replacing the bare "Processing… n" it falls back to when only `progress` is sent, and updates it each
beat). That makes the heartbeat a live one-line display the server controls while an eval runs: the display is the
work narrating itself rather than a synthetic status, and the model gains a mid-flight side channel — `println`s it
writes for its own benefit double as the user's progress report. Built the same day, then tuned against the screen:
the stream polls the bank every second and beats the moment there is fresh output — every line since the last beat,
the cursor advancing only on an emitted beat so nothing drops — with a plain elapsed-time beat each 15 s when the
eval is silent, feeding the idle timer. First narration reaches the display within ~1–2 s of the print (measured
2026-08-04). Messages go untrimmed with semantic newlines: the client renders verbatim but formats its own display —
flattening newlines to spaces and truncating long lines itself — and a server that pre-shortens for a UI it does not
own destroys information twice. Heartbeats stay ephemeral announcements — no delivery marks; the authoritative
output arrives with the response, under `;; out`.

**The tee, built 2026-08-04.** Output capture is a root-`*out*` swap, installed by `init!` and restored by `stop!` —
global installation, per-write discrimination: every write passes through to the original writer unchanged, and a
write whose thread carries a bank is additionally copied into it, over §11's two rails (`scoping` around chain
construction for the whole task tree, a per-link `binding` for Clojure conveyance, one `sc/ask` covering both).
Banks are per-entry, capped at 32 KiB keeping the tail with the drop announced. Restructured 2026-08-07 (wishlist
item 6): a bank stores completed lines plus the pending unterminated partial, not one string — the line is the unit
of retention, so trimming drops whole entries oldest-first (string surgery survives only at the one edge where a
single entry outgrows the whole cap — cut from its own front rather than dropped whole, tail-keeping outranking entry
purity), and `deposit!` stopped re-copying the retained 32 KiB per write. `__r/out` returns the vector of lines —
pending unterminated line last, chars the cap dropped announced as the first element, positionally honest at the head
end where tail-reaching composition never meets it. No windowing arity, deliberately: a `(out id n skip)` surface was
built and retired the same day for the invariant's form — `take-last` peeks, `drop-last` pages, `filter` searches,
and the one composition a string forced (`str/split-lines` before everything) is gone. The `since` cursor (the
heartbeat's read) kept its character-count contract unchanged through the restructure. Every response branch carries
`;; out` — value first (§4), then output, then footer — because a running or failed eval's output is often the most
informative thing about it; `(__r/out id)` reads a bank mid-run without delivering, via the non-touching
`registry/entry` accessor (§5). Verified through the live connection: top-level, nested-task and `future` prints
landing in one bank in order; concurrent evals fully separated; a raw thread attributed only through `scoped-fn`,
its bare twin falling through to the console; the failed branch carrying its output; the cap announcing
`…[7232 chars dropped]`. Host threads and third-party internals stay untouched by construction.

**The API layer, built 2026-08-04.** `co.multiply.remontoire.api` — `res`, `evals`, `out`, `failure`, `scoped-fn` —
aliased as `__r` in every namespace an eval runs in, ensured per link and *reasserted* when `__r` points anywhere
else: the name is obscure precisely so a foreign `__r` is a stale manual experiment, not a claim the invariant
yields to (observed same day: a hand-aliased `__r` in the REPL's own namespace shadowed the API with a baffling
`No such var`). The eval tool description documents the alias; it is for eval code only, never committed source.

**JSON is the one real dependency.** `org.clojure/data.json`: pure Clojure, no transitive deps. Not `jsonista`, which
drags Jackson into a host that already has an opinion about Jackson's version. Not a hand-rolled codec either — §1's
premise is that hand-authored escaping eats `\uXXXX`, and owning that bug class at the bottom of the stack would be an
unusually literal way to reproduce the problem this project exists to solve.

## What MCP does and does not give us

- **The server never talks to the model.** It answers a client inside the host application, which decides what reaches
  the conversation. No notification wakes an agent, so **§9's "ping on completion" cannot be a push.** It becomes
  registry state: `q/finally` records completion and the next tool response carries a line saying task 12 finished.
  Transport-independent, and more reliable than a push the host may ignore.
- **One tool means one JSON Schema** — §2's tool-budget argument realized. That schema and its description are the
  entire out-of-band interface the model has; everything else must arrive as tool *results*, which is what makes in-band
  discoverability (`(doc nav/usages)`) load-bearing rather than a nicety.
- **Clients impose per-request timeouts.** A tool that always returns within ~2s never has to negotiate one.
- **MCP has its own `notifications/cancelled`** — advisory, with no guarantee the server stops. Unneeded for the same
  reason nREPL's interrupt was: cancellation lives in the registry, keyed by a stable id.
- Tool annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`) are advisory hints hosts use for permission
  prompts. An eval tool is honestly none of the safe ones.

## The eval tool

```
eval(code, ns, await) → {:id :value :out :error :status}
```

- **`ns` is optional and sticky, and every result reports the namespace it used.** Amended 2026-07-31; this originally
  required `ns` explicitly, on the grounds that a sticky `*ns*` means evaluating in the wrong namespace and
  misdiagnosing the result. That objection is really to *hidden* state — a human REPL shows `user=>` in the prompt,
  but an agent sees only tool results, so a stickiness nobody echoes back is invisible. Reporting the namespace on
  every result, errors included, removes the hazard and keeps the convenience.
  - Evaluation starts in a scratch namespace, so that a stray `def` lands somewhere disposable rather than in
    `clojure.core` — which is where an unbound `*ns*` resolves to at runtime, and therefore where an eval tool that
    does nothing about it will quietly define things.
    - **Per session since 2026-08-05** (`session/scratch-ns-sym`): `co.multiply.remontoire.scratch.s<prefix>`, the
      same 8-char sid prefix `:by` attribution wears, filtered to symbol-legal characters because an adopted sid is
      client-chosen text (hashed whole when nothing survives). Two motivations, one fix: concurrent tenants in a
      shared scratch clobber each other's defs and rewire each other's refers; and the Quiescent skill's style
      teaching — `:refer` the `q`-prefixed macros bare — was in tension with visiting-namespace hygiene until each
      session owned a namespace where that style is simply correct. So scratch is minted pre-wired:
      `[co.multiply.quiescent :as q :refer [q qdo qjoin qfor qlet qmerge if-qlet when-qlet]]`, plus `res`/`evals` and
      the `__r` alias as before. Verified in vivo: a bare skill-style `qlet` evaluates in a fresh session's scratch
      with no setup. The id-less nil session keeps the shared `co.multiply.remontoire.scratch` — no session to die
      with, nothing to reap. *(The `q` alias here was superseded by `__q` the same day — see the tooling-rail record
      below; the bare refers stayed. And the id-less session lost its wire identity 2026-08-06 — bare requests are
      400'd now; nil remains the sessionless in-process caller, who keeps the shared scratch. See §11.)*
    - **The tooling rail became the pair `__r`/`__q`, 2026-08-05 — `q` is injected nowhere.** Prompted by a fresh
      smoke tenant observing that `q` \"also resolved from `user`\" — the fill-when-free `q` was *conditional*, and a
      conditional affordance cannot be calibrated: the model could never know what `q` meant in a namespace it just
      walked into, and a host's own partial `:refer [qlet]` teaches it that bare `qdo` works too. Interrogated
      whether Quiescent should ride along at all; kept, because `__r`'s handles compose in Quiescent verbs *by
      design* — `res` returns a task precisely so `catch`/`cancel`/`then` are the access idioms — so the rail is
      incomplete without them. The reasoning, now stated in the instructions: **`q` is reserved for user code** —
      host namespaces own their meaning of it, and committed source requires Quiescent as `q` itself — **`__q` is
      self-owned tooling**, obscure by design, reasserted always, colliding with nothing. This also dissolves the
      skill-vs-remontoire tension: the Quiescent skill speaks `q` because it teaches user code; remontoire speaks
      `__q` because it is model tooling — two perspectives, not two conventions in conflict. Scratch uses `__q` as
      its alias too (one spelling everywhere; the refers' absence when visiting is already the unavoidable
      divergence, so the alias at least stays constant), and the teachings swept: instructions compositions, the
      eval tool description, api docstrings, and the pattern namespaces — whose code and walkthroughs now speak the
      tooling spelling throughout, with the umbrella docstring naming the perspective explicitly. Refer mechanics
      verified before deciding (probe ns, removed after): partial-then-fuller refers of the same namespace *union*;
      re-refer of the same var is a no-op; a refer colliding with the namespace's own interned def loses — the def
      wins, with the rejection noted only on `*err*` — invisible at the time; the `*err*` tee built later the same
      day (§5) now carries exactly that rejection into the eval response.
      So a model re-establishing `q` for itself in a host namespace is safe by construction. Verified in vivo:
      fresh scratch has `__q` + bare refers and no `q`; a probe host ns aliasing `q`→clojure.string kept it while
      `__q` arrived and the taught idiom `(__q/catch (__q/task :fine) identity)` ran there; the served instructions
      carry the reasoning; patterns re-verified running under the new spelling. Transitional ghosts accepted:
      namespaces that received the old fill-when-free `q` keep it until restart.
    - **The scratch dies with its session** (`session/drop!`, so both the expiry reap and the DELETE goodbye reach
      it): conversational state, same rule as the sticky namespace. An eval somehow still running there when its
      session expires fails loudly at its next form (`No namespace`) rather than working on in a ghost — accepted
      edge, visible not silent. The shared scratch is never removed.
    - Hygiene is *encouraged, not enforced* (instructions, 2026-08-05): work from scratch, reach into the system
      qualified — `#'their.ns/private-var` reads and calls privates without moving there — but `in-ns`/`require`/`def`
      into a host namespace stays available; a user request may legitimately need it, and forbidding it would put the
      model in tension with the user. "There may be genuine reasons to divert; there's no reason not to start here."
  - A named namespace must already exist. Creating one on a typo answers every later expression with `Unable to
    resolve symbol` and leaves the blame on the expression.
  - The namespace tracked is where the code *ended*, so `(in-ns …)` and `(ns …)` stick the way they do in a REPL.
- **Forms are read and evaluated one at a time**, and the count is reported when more than one ran. Not `(do …)`
  wrapped around the whole string: that wrapper is swallowed by a trailing line comment, and reading everything up
  front resolves `::keywords` and reader aliases in later forms against the namespace the caller started in, even when
  an earlier form moved elsewhere. The count makes "did it run all of that?" observable rather than assumed. Each
  form's value is grounded before the next is read — an eval is an implicit `qdo`, see §9.
- **Structured error**: exception class, message, `ex-data`, frames filtered to the library and the host project. This
  becomes load-bearing rather than cosmetic once composition means malformed calls throw Clojure exceptions instead of
  failing schema validation.

  Observed while building the tool, and shaping what the filter has to do. A mistyped namespace — a pure user error —
  rendered as ~1,400 characters, of which two lines were the message and `ex-data` and the rest was `Filter$Chain`,
  `AuthFilter`, `ServerImpl`, `MultiFn` and `handle-payload`: the tool's own transport, which can never be relevant to
  the caller. `pr-str` on a throwable also prints `:via` and `:trace` restating each other. And compile errors report
  their position as whatever `*file*` happens to be bound to, so `Syntax error compiling at (probe.clj:19:1)` blames a
  file the caller has never heard of. Four consequences:

  - **Cut what the throwable's trace shares with the stack at the catch point.** Those frames are how the call
    arrived, identical on both sides, and never part of what went wrong. Two enumerative versions were tried first and
    both failed on real traces: a list of transport class prefixes is rescued in its entirety by one unlisted class
    mid-stack (`clojure.lang.MultiFn`), and cutting at a named eval-boundary frame does nothing when the throw happens
    *before* evaluation begins, which is exactly the case for a mistyped namespace. Comparing against the catch-site
    stack needs no list and is exact.

    **The baseline must be captured where control passes to user code, not where the failure is caught.** Those are
    the same stack today and stop being so the moment eval runs inside a task (§9): the throw happens on a task
    thread and the catch on the HTTP thread, and §4's own measurement of that shape found 34 frames against 9 sharing
    *nothing*. Subtracting one from the other would remove zero and silently restore the full noisy trace — a
    regression with no error to announce it. It would also make the cross-thread `:from` break fire on every single
    failure, and a marker that always fires carries no information.
  - **Keep the cause chain.** Clojure wraps aggressively — a `CompilerException` around the real failure is the common
    case, and a format that keeps only the outermost message reports the wrapper instead of the problem.
  - **Bind `*file*` to something honest** during evaluation, so a compile error names the eval rather than a bystander.
  - **Demunge Clojure frames to `ns/name` symbols.** `co.multiply.module.impl$run_crawl_one/invokeStatic` becomes
    `co.multiply.module.impl/run-crawl-one`, which is not merely more readable: by §3's invariant it is a valid input
    to `nav/form`, so "show me the source of the frame that threw" is composition rather than a feature.

  **Say how many frames were dropped.** A filtered trace and a short trace look identical otherwise, and the
  difference matters when the answer is "the interesting frame was in the part you hid".

  **Two functions, not one** (decided 2026-07-31). `trace` is the navigable half: a sequential, complete run of sites,
  which reduces, filters and maps like every other result and whose elements feed `nav/form`. `profile` is the ocular
  half: what went wrong, pruned, with a trace attached. `render` turns a profile into a string, and is decoupled from
  it — a profile stays data so the registry can store it and later answer "every `NullPointerException` this session",
  which a string could not. Pruning belongs to `profile` alone, because pruning needs to know where the call came from
  and a trace does not; that also disposes of the question of where a `:dropped` count lives on a bare sequence.

  **`trace` subtracts across the cause chain rather than concatenating it.** Measured: catching and rethrowing on one
  thread produces a wrapper whose trace is an exact suffix of its cause's, so `mapcat` over the chain reports
  everything twice. Across threads the two are disjoint — 34 frames and 9, sharing nothing — and both matter: the
  failure inside the task, then the thread that was awaiting it. Sites contributed by anything but the root carry
  `:from`, naming what rethrew them, and the renderer breaks the trace there because running the two sides together
  reads as one call chain when it is two. This is not an exotic case here: under Quiescent, a task failing on a
  spawned thread and being collected by its parent is the ordinary one.

  **`ex-data` is the one part of the result that is not ours.** Everything remontoire builds is symbols, strings,
  numbers and vectors, and round-trips through `pr-str`/`read-string` unchanged. `ex-data` is passed through verbatim,
  so it is whatever the thrower put there: live objects, atoms, and — the case that bites — a lazy infinite seq, whose
  unbounded `pr-str` never returns. Rendering binds `*print-length*` and `*print-level*`; Clojure marks each cut, so
  the truncation announces itself. Bounding the *print* does not bound the *reference*, which is a retention question
  for the registry (§5) rather than a rendering one.
- **Session init**: `(q/throw-on-platform-park! false)`, sane `*print-length*` /
  `*print-level*` — and it *says* when it clamped.
- **A per-call timeout, and `cancel` as an ordinary call.** See §9 — on timeout, return the id with `:status :running`
  rather than killing the work; `(cancel 14)` is then just the next eval, not a protocol operation.
  - **The bound is a caller-supplied argument with a default**, not a constant. It is the nearest thing to "tell me
    when it is done" that the protocol permits: MCP has server→client notifications, but every one of them targets the
    *client*, and none re-invokes the model — `progress` updates a request still in flight, `message` lands in a log.
    A server cannot wake a turn that has ended. Letting the caller say "wait up to a minute for this one" converts the
    missing push into a decision the caller is better placed to make than the server, and costs nothing when unused.
  - **The field is `await`, not `timeout`.** Decided 2026-07-31. The schema is the only semantics a model gets, and
    `timeout` means teardown — in Quiescent specifically (`q/timeout` settles the task at the deadline) and in general
    usage. A model deciding what to pass would be reasoning about whether it is arming a deadline. `await` names what
    happens: how long the caller waits, with the work unaffected either way. The implementation must match the name —
    `q/await`, never `q/timeout`. (Quiescent ≤0.7.0's `q/await` itself failed this test — destructive on expiry,
    contrary to its own docstring. Fixed in 0.8.0; see §9.)
  - **Clamp it, and say so.** A bound longer than the *client's* own request timeout does not buy a longer wait, it
    buys a transport error: the model loses the id, the "still running" notice, and the thread, while the work
    continues invisibly. The ceiling is the client's, so it is measured against the live connection rather than
    guessed. Report the clamp when it applies — §5's objection to a silently-stopped id applies equally to a silently
    shortened wait.
  - **A long bound is the whole "wake me when it is done" mechanism**, not a poor substitute for one. The harness is
    already blocked on an outstanding tool call, so a call that returns when the task settles resumes the turn exactly
    as a push would; the nap is spent inside the request rather than in the client's scheduler. This costs nothing
    architecturally because each exchange already gets its own virtual thread (§4, Transport) — one call parked for ten
    minutes impedes no other, so the connection is not wedged, only that call. The ceiling is HTTP-level: client
    request timeouts and intervening proxies, not anything here.
    - Fidelity limit: parallel tool calls all resolve before a turn resumes, so this gives "wake me when *all* of these
      are done", never three staggered wakes. `q/race` recovers most of it — waiting on a race of several entries
      returns on the first, and the next turn waits on the rest. Staggered wakes, reconstructed a turn at a time, with
      no protocol support at all.
  - The better answer is often not to wait. An entry is a task before it is a value, so follow-up work can be chained
    onto `e17` while it is still running and will run when it settles. Waiting is for when a *human or a model* needs
    the answer in hand; composition is for when only the next computation does.
- **Print budget** starts as a dumb character cap. The right behaviour is empirical; by the time it is known, `shape`
  exists to replace it.

Nothing else. No `shape`, no `nav`, no budgeting cleverness — those are library functions, built through the tool once
it exists.

## Lifecycle: inert until `init!`

Nothing binds a port until asked. `(remontoire/init! {:port … :bind …})` starts the endpoint, `stop!` releases it.

- **Idempotent** — a second `init!` returns the running server rather than binding another one.
- **`init!` owns the HTTP server and nothing else.** The registry stays behind `defonce` in its own namespace (§5), so
  the endpoint can be restarted — and every library namespace above it reloaded — without ids evaporating mid-session.
- **On a deployed host, `init!` is called from the host's own REPL** over the tailnet. That keeps remontoire out of the
  deployment configuration entirely and makes turning it on a deliberate, logged act. An env var for always-on belongs
  in dev, not in production.
- **Source availability is settled at init**, not per call — see below.

## Exposure

A REPL port is a remote-execution endpoint; so is this one. What makes it safe is the network, not the protocol.

The presumed deployed shape: machines join a private overlay network — e.g. a Tailscale tailnet — and are addressed
by its internal hostnames or CGNAT-range addresses. Only the application's public port is reachable from the public
internet, via a load balancer; everything else is reachable from inside the overlay only.

- **Bind the node's overlay address, not `0.0.0.0`.** Public reachability is then structurally impossible rather than
  firewall-dependent. Default to the first interface address in `100.64.0.0/10` on a tailnet, allow an explicit
  `:bind`, and never bind the application's public port.
- **The residual risk is the browser, and the perimeter does not cover it.** An operator's laptop is on the tailnet, so
  a page they visit can reach `100.x` addresses: the attacker is inside the perimeter rather than in front of it.
  Validate `Origin` and never emit permissive CORS headers. The realistic failure mode is not the design as drawn but an
  `Access-Control-Allow-Origin: *` added later to make some browser test pass.
- **Record the peer address on every registry entry.** Tailnet addresses are stable per device and Tailscale's own logs
  map address to machine, so attribution costs one field and no Tailscale coupling. `tailscale whois` and the
  `tailscaled` local API can resolve device and user if that ever proves insufficient. Retrofitting this once ids mean
  something is unpleasant.

## Source-bearing and runtime-only halves

Running against a deployed server splits the library along an axis nothing else in this document does. §5, §6, §9 and
§10 — registry, `shape`, Quiescent integration, tracing — are pure runtime and work anywhere. §7 and §8 — `outline`,
`form`, `usages`, rename — need `.clj` files on disk and a clj-kondo cache, which an uberjar deployment will not have.

Detect this at init and report it once, rather than letting each `outline` call fail its own way. It also argues for the
runtime half being loadable without rewrite-clj or clj-kondo on the classpath at all; worth settling when the namespaces
exist rather than in advance.

## Why build the tool first

The transport's properties are **empirical**; the library's are **analytic**. Whether root-`*out*` capture actually
catches printlns from virtual threads, how many tokens of a report is useful before it becomes noise, which stack frames
matter — none of that is specifiable in advance. The element-type table above, by contrast, can be reasoned about on
paper. So build the thing that has to teach you first, and write the thing you can design inside it.

**First thing to run through it**: an expression that fans out over `q/task`s and printlns from several virtual threads.
That validates the least certain part of the design on day one rather than after a library is built on top of it.

