# 5. The registry

One rolling registry, holding **both** the outcome and the output log of every eval. Live objects, not printed strings.

An entry is a **`q/task`**, not a value — every eval is wrapped in one (§9), so what is retained is something that will
eventually complete rather than something that already has. That is what makes an id useful before the work is
finished, and it means an entry composes with Quiescent directly, not only with remontoire's own functions:

```clojure
(res 12)                  ; the entry
@e17                      ; the value — awaits, and is itself inside a task, so it does not block
(q/catch e17 identity)    ; the failure as a value; a task swallows its exception until something uses it
(q/finally e17 (fn [_v e _c] …))
```

§3's invariant reaches further than intended here. Entries are valid inputs to `q/finally`, `q/catch` and `q/cancel`
without remontoire defining any of them — the composition surface is `clojure.core` *and* Quiescent, and neither is
something this library has to re-expose.

```clojure
(res 12)   ; the entry
(out 12)   ; vector of [ts thread line]
```

- **Auto-capture is the point.** Without it, forgetting to `def` means re-running — and re-running a Rama select or an
  LLM call is slow and sometimes side-effecting. `*1`/`*2` exist but shift between calls; a stable id does not.
- **Output capture must be at the root, not per-thread.** `with-out-str` rebinds
  `*out*` per thread, so printlns from virtual threads spawned by the evaluated expression — which is where the
  interesting output is in a Quiescent codebase — go to the root binding and are silently missed. Use `alter-var-root`
  on `*out*`
  or a `System/setOut` tee.
- **The out log keeps accumulating after the eval returns**, and stays re-readable. A snapshot string would race with
  async output and lose the tail.
- **Tag lines with thread and timestamp.** Under parallel `qfor` fan-out, output from concurrent tasks interleaves into
  mush; per-thread filtering is the fix and is nearly impossible to reconstruct afterwards.
- **`*err*` through the tee — built 2026-08-05.** The same tee, installed at both roots, both streams filling the
  *same* bank: interleaved in arrival order and unmarked, because chronology beats channel separation — a terminal
  merges them for the same reason, and the message content self-identifies. The motivating measurement came the same
  day: a `require :refer` colliding with an interned def loses to the def and says so *only* on `*err*` — a probe
  observed the rejection as pure silence. Re-run after the tee: the response now carries `REJECTED: attempt to
  replace interned var #'probe.errcap/qfor with #'co.multiply.quiescent/qfor … you must ns-unmap first`, verbatim,
  interleaved between the `*out*` lines around it — the whisper class (refer warnings, reflection notes, stray-thread
  stack traces, host muttering) made readable. `installed` now banks both original writers (`{:out w :err w}`);
  live migration required uninstalling with the *old* code before reloading, since the new `uninstall!` destructures
  the map shape. JVM-level `System/setErr`-`System/setOut` (Java libs writing to the streams directly) remains the
  separate optional layer, deliberately unbuilt.
- **Attribute output by scope, not by thread.** Resolved 2026-07-31. Quiescent propagates `co.multiply.scoped` values
  across spawn where `binding` does not, so an eval id put in scope is visible from every descendant task. The tee's
  `write` runs on whichever thread did the printing — inside that task's scope — so it can `ask` for the id at the
  moment the line arrives. A thread-local cannot do this — not because thread identity is ambiguous (virtual threads
  are one per task and never recycled, so a thread *is* a stable identity) but because a thread-local does not
  propagate into a spawned task at all. A `qfor` fan-out would need the tag re-established on every spawn, which is
  exactly what scope does for free. Tag lines with thread *as well*, for reading interleaved output; the id is what
  says which eval they belong to.
- **State lives behind `defonce` in its own namespace, never reloaded.** The library namespaces above it will churn
  constantly during development; if the registry shares a namespace with them, ids evaporate mid-session on the first
  `clj-reload`.
- **Bounded** (~20 entries, out logs capped by total bytes), evicting oldest, **announcing eviction**. An id that
  silently stopped resolving is a nasty failure mode.
  - **Evictable = settled ∧ delivered, least-recently-touched first** (decided 2026-08-03; the same day's settled-only
    rule, extended). A running eval's id must keep resolving — it is the only handle on the work — and a result nobody
    has seen must survive until somebody sees it: a mistimed burst of evals must not be able to discard the
    long-running result being waited on. Delivery is the first touch (a done/failed/cancelled response, or the first
    `res`); every `res` refreshes; `evals` deliberately does not — a survey is not a use. The id therefore gives mint
    order, not eviction order: the pool holds the `capacity` most recently used delivered results, runners and unread
    results ride above the bound, and one admission can evict several as the backlog converges. The cap is tunable
    upward (~40) if this feels cramped — entries are small, since settlement releases the link chain and an entry
    retains only the bottom value and its task wrapper. Configurable at `init!` (`:capacity`, default 20; decided
    2026-08-03): an eviction-race loss is hard to diagnose from inside a session, so the remedy must be reachable
    without editing the library. Also adjustable mid-run (`remontoire/capacity!`). Lowering evicts *lazily*, at the
    next admission, never at the call itself: deferral costs nothing, keeps a hasty reduction reversible until work
    actually needs the room, and keeps eviction announced on its one path — a response footer.
- **Settlement is announced on the next response** (decided 2026-08-03). An eval that returned `running` and later
  settled unseen is reported — id and status, not the value — in the footer of whatever response comes next: "here's
  what happened while you were away." Told once (an `:announced` mark), because unread news repeating every response is
  nagging; but announcing is *not* delivery — the entry stays eviction-protected until a `res` reads it. Piggybacking
  is the only channel that reaches the model: the transport could push over SSE at any moment (Streamable HTTP allows a
  GET-opened stream), but a server-initiated notification lands in the client's plumbing, not the conversation — the
  model perceives tool results and nothing else, so news must ride one.
  - **Delivery and told-once marks land only after the transport write succeeds** (found and fixed 2026-08-04, when a
    65 s probe outran the client's 60 s first-byte timer: the handler had already marked the entry delivered while the
    response died on a closed socket — the exact lost result the gate exists to prevent, back through a side door).
    The handler attaches the marking as response *metadata* (never serialized); http runs it after `respond!` returns.
    A timed-out client therefore leaves its eval owed, protected and announceable — the next call reports it.
  - **Explicit client cancellation propagates; accidents never do** (decided and measured 2026-08-04). TaskStop on a
    backgrounded call sends `notifications/cancelled` naming the requestId (measured; reason "AbortError"), and that
    explicit notice is id-directed cancellation arriving over the wire: an in-flight table maps requestId → cancel
    thunk — for `eval` it cancels the entry, direct cancellation piercing its own moat; for `await` only the gate,
    whose dying shields are stopped at every entry's moat (verified in-process — the awaited runner survived, the
    handler returned early with honest statuses). A dead socket, by contrast, cancels nothing. The same notice
    suppresses the response's delivery marks: the client keeps the socket *open* after aborting (measured — a
    response written six minutes after TaskStop still "succeeded"), so no write can detect this abandonment; a
    cancelled request has no reader by definition, its marks are withheld, and the next contact announces the
    outcome (`;; settled — 125 cancelled`). An `: end` probe frame after the final SSE message covers the
    disconnects that *do* close the socket, where the reset fails the second write.
- **Claude Code's request ceiling, measured 2026-08-04**: for HTTP MCP servers the binding limit is a **60 s
  per-request timer to the first response byte** (probe: a 65 s eval, client gave up at ~60 s; docs confirm). Raisable
  per server via `"timeout"` in `.mcp.json` or `MCP_TOOL_TIMEOUT` ≥ 60 s; progress notifications do *not* extend it;
  separate 5 min idle timeout for HTTP. Bash-tool timeouts (10 min+) are unrelated plumbing. Consequence: the default
  `await` clamp should be ~55 s, configurable alongside it for users who raise their client ceiling. **Superseded the
  same day**: with SSE and progress heartbeats (§4) neither the first-byte nor the idle timer binds anymore — the
  clamp is 15 min, the measured end-to-end point rather than a transport limit, and the model chooses its deadline
  within it. Raised again to 24 h later that day — mirroring the transport's own ~28 h wall-clock, so the ceiling
  carries no judgment of ours and the model's deadline is the deadline. **Long-probe run 2026-08-04**: a 61⅔-minute
  eval with a matching `await` — backgrounded by the client at 120 s, keepalive beats every 15 s throughout —
  delivered its value as a task notification on schedule. No cliff between the 15-minute mark and the hour; the
  remaining untested stretch to the ~28 h wall-clock has no known timer left in it.
- **Strong references, released wholesale.** Decided 2026-07-31, for the registry and the trace (§10) together, since
  retrofitting weak refs through a collector afterwards is unpleasant. Result readback is the property worth keeping —
  it is what makes every node of a trace inspectable — so the bound on retention is the eviction policy above and, for a
  trace, dropping the collector, which releases the whole forest at once. Weak references were the alternative and lose
  readback; revisit only if a dev JVM actually OOMs.
- **Each entry records the caller's peer address** (§4). Cheap now, awkward later.
- **A failed eval retains the task, which holds the throwable.** Nothing stores a `profile`: it is derivable from a
  throwable and the reverse is not, so storing it would put `causes`, a different baseline and re-tracing permanently
  out of reach — one filterable `:trace` and nothing else. The task already retains the failure and `q/catch` extracts
  it, so this costs nothing beyond keeping the entry.
- **The id appears in every response**, results and errors alike. It is the handle the condensation is safe *because*
  of: a pruned trace that hides nineteen frames is only reasonable while the entry that holds all of them can still be
  named. The response is also the sole channel to the model (§4), so anything the caller might ask for next has to be
  named in it — the same constraint that turns §9's completion ping into registry state.
- **No `*e`, no `*1`/`*2`/`*3`.** Decided 2026-07-31. Clojure's REPL bindings are an unnamed, single-slot, silently
  overwritten version of what the registry does properly, and having both means two answers to "give me back an earlier
  result". Remontoire's eval deliberately leaves them unbound; note that this makes the registry a prerequisite for
  reaching §4's `trace` from the connection at all, rather than a convenience layered on top.

