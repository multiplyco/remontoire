# 9. Quiescent integration

**Wrap every eval in a task** rather than making async opt-in. If the caller has to write `(def t (q/task …))`,
cancellability only exists where a hang was already predicted — but the hangs that actually occur are inside library
code (`@(qfor execute-tool)`, uncapped `Retry-After` sleeps), reached by evaluating something innocent-looking. If the
tool wraps every form, it owns the handle unconditionally, and Quiescent's cancellation propagates to children, which a
thread interrupt does not.

That makes Quiescent a **hard dependency** rather than an optional integration — declared in `deps.edn`, not resolved
dynamically the way malli is. Decided 2026-07-31: the library is built on it rather than merely able to use it, so the
alternative is a fallback execution model that would be a second implementation of the least-tested path, in a host
nobody is using. The cost is that a host carrying its own Quiescent resolves a single version for both.

- fast: await ~2s, return the value inline, id assigned anyway
- slow: return id + `:status :running`; `q/finally` records completion in the registry, and the *next* tool response
  reports it. MCP gives a server no way to wake the model, so this cannot be a push — see §4.
- wedged: `(cancel 14)`

Four things the Quiescent semantics pin down, each of which the obvious implementation gets wrong:

- **The eval task must be rooted, not a child of the request.** A task cannot outlive its parent, and unsettled
  children are cancelled the moment the parent settles. Create the eval task inside anything scoped to the HTTP
  exchange and the slow path above inverts: "return early, keep running" silently becomes "return early, cancelled",
  with no error to show for it. Either construct it outside any enclosing task, or `q/compel` — and if `compel`, at
  the point of *construction*, since compelling a task already held protects only the wrapper.
  - Rooting is the default, not something to arrange (verified 2026-08-01). Parenthood comes from the ambient scope's
    `*this*`, not from thread kind, and a handler thread carries no scope — so a plain `q/task` constructed while
    handling an exchange is top-level, and a task created during one eval survived its exchange and completed. The
    `compel` branch only becomes live if eval handling itself ever moves inside a task.
- **Await, do not deref or time out.** The wait must bound itself, never the work — the task must survive the response.
  `q/timeout` settles the task at the deadline, which is the opposite of the intent, and `deref` throws.
  - **`q/await` was destructive on expiry until Quiescent 0.8.0.** Found 2026-08-01 while verifying `any-of`: an
    expired bound cascade-cancelled the awaited task (a 400ms task awaited with a 100ms bound came back `q/cancelled?`
    true). Not a regression, it turned out — the teardown had been there since ≥0.4.0 and the async return since birth;
    the docstring described a blocking, non-destructive contract the implementation never had. Fixed at the root in
    0.8.0: the awaiting proxy is now a pure observer, so expiry tears down await's own machinery and the cascade dies
    there. The real contract, now also the documented one: `(q/await t ms)` returns a task settling to a boolean,
    never throws, never cancels; deref it to block, which on the virtual HTTP thread is the implementation —
    `@(q/await entry ms)`. Verified against the live connection on 0.8.0: expired → `false` with the work surviving to
    its value, settled → `true`. A defect worth remembering the shape of: the destructive path only fired on
    *expiry* — the path every happy-path test misses — and it held the exact inversion §4's `await`-not-`timeout`
    naming exists to prevent.
- **Cancellation is a control signal, not an error.** A cancelled entry holds a `CancellationException`; rendering
  that through §4's `profile` would report a stack trace for something that did not fail. Check `q/cancelled?` first
  and say "cancelled" — `q/exceptional?` and `q/get-now` read a task without deref's throwing behaviour.
- **The HTTP executor must stay virtual-threaded.** Dereferencing a task from a platform thread throws by default,
  guarding carrier threads. `http.clj` uses Quiescent's own virtual executor (amended 2026-08-01 — see §4, Transport),
  which now carries a second meaning the executor choice did not have when it was made.

Tasks are values, not one-shot channels: settled, they hold their result permanently and can be dereferenced any number
of times without re-running. That is what makes `@e17` safe to repeat, and it is the property the registry is built on.

**Waiting on several entries needs `any-of`, which is a Quiescent-side addition** (added in 0.7.0; verified below).
`q/race`
cannot serve: it tears down the losers, and here the losers are live registry entries the caller is explicitly waiting
on. `compel` does not rescue it — registry entries are pre-existing tasks, the case where compel protects only the
wrapper, and direct cancellation pierces compel regardless when the canceller holds a reference, which `race` does by
construction. Nor can it be composed from outside, because cancellation propagates *upstream* through a chain — the
same mechanism by which `q/timeout` reaches the task it bounds — so any wrapper raced in place of the entry still kills
the entry. `all-of` needs nothing new: `q/handle` turns each failure into a value, which removes the trigger for
sibling cancellation entirely.

Identity of the winner cannot come back as a task — the border invariant grounds it to a value on the way out, so
`any-of` returns *what* settled and not *which*. Racing `[id task]` pairs preserves it, and that wrapping is why
**`await` is a tool rather than an eval idiom**: the control layer already holds the id→entry mapping, so it can apply
the pairing itself. A model that does not know the trick writes the bare form, gets a value back, and cannot tell which
entry produced it — silent misattribution whenever two entries share a result shape. It is also §5's
id-in-every-response rule: as a tool that rule holds by construction, as an idiom it holds only when remembered. The
argument is specific to the multi-entry case and does not reach `cancel`, where the id is already in what was typed and
no association can be lost — `(q/cancel e17)` stays a library call. (The idiom stays; a `cancel` *tool* was added
anyway on 2026-08-04, on lifecycle grounds the mapping argument never considered — see the cancel-tool entry below.)

**Verified against Quiescent 0.7.0, 2026-08-01**, through the live connection:

- Losers keep running: a 300ms entrant lost to a 30ms one, then completed and fired its side effect.
- Cancelling the returned task does not reach the entrants — cancelled the `any-of`, the entrant settled normally.
- Pairs ground: `@(q/any-of [:fast fast] [:slow slow])` → `[:fast :fast-val]`. The identity mechanism works as designed.
- The upstream-cancellation claim above is fact, not inference — with a boundary found the first day the eval wrapping
  ran (2026-08-01): **cancellation propagates upstream through a chain; settlement teardown does not.** Measured three
  ways: `q/cancel` on a bare `q/then` wrapper cancelled its source; cancelling an eval entry that *grounded* a chain
  on another entry cancelled that entry too — the cancel followed the await, exactly as `q/timeout` reaches the task
  it bounds; while the same wrapper shape merely *abandoned* — torn down by its parent settling — left the source
  running. `race` cancels its losers, so the argument for `any-of` stands unchanged — and the settlement half of the
  distinction is what makes chaining onto registry entries safe to abandon at all (see the structured-concurrency
  note below).
- **Settlement rule: first *non-exceptional* result wins.** A fast-failing entrant is skipped, not returned — right for
  `any-of` in general, wrong for the `await` tool uncorrected, since a failed eval is precisely a completion the caller
  wants to hear about. The tool therefore wraps each entry in `q/handle` before racing, turning failure into a value
  (verified: a wrapped 20ms failure beat a 400ms success). The wrappers are chains, but nothing cancels them — `any-of`
  does not own its entrants — so the upstream hazard never fires.
- All entrants failing yields `ex-info "All tasks failed."` carrying `{:errors [& throwables]}` — real throwables, so
  §4's `profile` applies to each.

**The `await` tool, built 2026-08-04.** Shape `{:all-of [ids] :any-of [ids] :await ms}` — named fields over a nested
positional vocabulary, because the schema is the only semantics a model reliably gets, at the cost of one any-group
per call (the full algebra stays available in eval). Returns when every `all-of` id has settled and, if `any-of` is
non-empty, at least one of its ids has — or at the deadline, which is mandatory and clamped (55 s, from the measured
60 s ceiling in §5); eval's `await` field shares the clamp, defaulting to 2 s. Both lists empty returns immediately:
the degenerate case is the cheap "anything while I was away?" check-in. The gate is the algebra itself — **all-of is
just a vector returned from a task** (verified 2026-08-04: `@(q/task [(q/sleep 300) (q/sleep 600) :plain])` →
`[nil nil :plain]` at 605 ms), `any-of` is the verb — with each entry behind a `q/await` shield rather than the
`q/handle`/pair wrapping sketched above: a shield settles when its entry does, never throws, never owns, so failure
counts as completion and no identity needs to survive the race — the tool reports per-id `status` for everything
asked about after the gate opens, `{12 :done, 13 :running}`. Statuses are not values: reporting one is announcement,
not delivery (§5), so settled ids are marked told — post-write — but never touched; a new non-touching
`registry/task` accessor exists because `res`'s touch *is* the delivery mark and machinery that reports *about*
entries must not use it.

**The `cancel` tool, built 2026-08-04.** Shape `{:ids [ids] :await ms}`. Not for the mapping reason — the earlier
judgment that `(q/cancel (res 12))` needs no tool still holds as far as it went — but for two reasons it did not
consider, plus a third the registry lifecycle forced. First, the control surface becomes self-documenting: eval,
await, cancel — the three verbs a control layer owes, visible as tools rather than one of them hiding as an idiom.
Second, the idiom spends a registry entry to say it: every `(q/cancel (res n))` eval mints an id whose only content
is a control act, garbage the moment it settles. Third — found while designing the tool — the cancelled entry itself
was immortal: eviction requires touched, touch requires delivery, and nobody ever `res`es a task they deliberately
killed, so a cancelled eval sat protected forever awaiting a read that cannot come. The tool is where that resolves,
because cancellation has a distinctive place in §5's announcement/delivery split: **cancel delivers the one outcome
it authors.** An id reporting `:cancelled` is touched — post-write, same `::delivered` metadata as everything else —
because the caller directed that ending and the response confirmed it; that is the outcome reaching the caller in
§5's full sense, and it releases the entry. An id that settled first beat the cancel: `:done`/`:failed` is
announcement, told once, the value unread and protected — exactly `await`'s stance. `:running` outlasted the grace
period (`q/cancel` is prompt in a pure task tree, cooperative at a JVM leaf — §9) and the honest report is that the
cancellation has not bitten yet; such an id is neither touched nor told, so its eventual settlement still announces.
The triad divides the split cleanly: eval creates and delivers values, await observes and only announces, cancel
disposes and delivers precisely what it disposed. Mechanically the handler is entirely reused parts — `registry/task`
(the lookup is not a read), `run! q/cancel`, the same shielded all-of gate as `await`'s wait, `deadline` defaulting
to eval's 2 s, `cancellable-await` so TaskStop stops the waiting. Verified through the live connection 2026-08-04:
a 10-min runner cancelled and touched with no re-announcement in the next footer; an entry that settled `:done`
unseen reported `:done`, stayed untouched with its value readable, and was told once; a bad id threw the registry's
evicted/never-assigned explanation as `:isError`. Found and decided
2026-08-01, the first day the wrapping ran. Under a single task wrapper, a task spawned by evaluated code was a child
of the eval's task, cancelled the moment the eval settled — silently, because a task swallows its exception until
something uses it, and a cancelled fire-and-forget never gets used. The first casualty was remontoire restarting its
own endpoint: the eval returned its value and the restart task died unrun, with a running server left looking exactly
like a restarted one.

The answer, same day, was not to loosen the semantics but to name them: **each top-level form is a `then` on the
previous one** — `qdo`, mechanically — so a form's value is *grounded* before the next form is read. A top-level form
that returns a task therefore runs to completion, sequentially, and what the registry captures is its value: the two
REPL expectations — forms run in order, and they *run*, they don't drop — both fall out of the border invariant
instead of being patched around it. Verified: a bare task form grounded to its value; a later form saw the earlier
form's completed side effect at the expected wall-clock; a failure in the third form stopped the chain with the first
two applied; cancellation mid-chain stopped the whole chain.

What remains structured, and the idioms that follow:

- **A task spawned but not returned is a child of its form's link**, cancelled when that form settles. Fire-and-forget
  needs `q/compel`, at construction: `(q/compel (q/task …))`. Often the right shape is no spawn at all — let the eval
  itself be the long-running work; the tool returns `running` with the id and the work *is* the entry.
- **Side-effect chains on entries are reliable to abandon.** Measured: a `q/finally` attached to a running entry
  inside a `do` — never the form's value, so never grounded — fired at the *entry's* settle with the entry's real
  outcome (`cancelled` false, value in hand), despite the attaching eval having settled long before. The subscription
  on the source outlives the wrapper. And by the cascade/direct distinction above, the entry itself is untouchable by
  abandonment; `(q/cancel (res 33))` still reaches it, because explicit cancellation is the deliberate act the chain
  boundary does not stop.
- **A chain written as a form's value is a wait — and the wait is the feature.** `(q/finally (res 33) f)` as the last
  form grounds, so the eval settles when entry 33 does, and settles to 33's value (measured). Nothing is blocked by
  this: the call answers `running` with a fresh id and other evals proceed in parallel, so what the grounding buys is
  that the eventual effect *is* an entry — named, listed in `(evals)`, composable. Wrapping the chain in a `do` to
  "attach and move on" works — the abandoned subscription fires, as above — but is redundant: it trades a first-class
  entry for an anonymous effect and saves nothing.
  - The wait carried **ownership** until the chains were compelled, the same day: cancelling e2, a `q/finally` on
    running entry e1, cancelled e1 as well — cancellation follows a grounding upstream, exactly as `q/timeout`
    reaches the task it bounds. The fix is that every eval chain is constructed inside `q/compel`: the moat stops
    cancellation arriving from another entry's teardown, while direct cancellation of an entry still cascades down
    its own links. Measured both ways — a wedged eval still dies from `(q/cancel (res 61))`, and cancelling e2 now
    leaves e1 running. **Cancellation is id-directed**: tearing down e1 requires naming e1. The `await` tool keeps
    `any-of` regardless — observation should not need a moat to be safe.

The implementation constraints the chain imposes: each link runs on its own virtual thread, so `*ns*`/`*file*` are
re-established per link with the namespace threaded through as data (`binding` does not cross a spawn); reading
happens inside each link, under that link's `*ns*`; the baseline becomes per-link — an atom the running link
refreshes, so a failure in the third form subtracts the third link's arrival path, not the first's; and the namespace
is resolved inside the chain, so a bad namespace fails the task like any other failure, profiled against a
same-thread baseline. The chain is compelled at construction — the id-directed cancellation above — which also
retires the rooting concern outright: a compelled chain survives even if eval handling itself ever moves inside a
task. One tempting simplification is ruled out (measured 2026-08-02): the link body cannot be a `qlet` —
`(qlet [v (eval form)] …)` evaluates its binding forms on threads of their own, outside the link's `binding` frame,
so `eval` there sees `*ns*` = `clojure.core` and the body sees no frame at all. Eval and the namespace capture must
happen inside the frame, with only data crossing to the next link.

This is what removes the transport question rather than answering it. With cancellation living in the registry rather
than the protocol, nothing is left that a REPL protocol was needed for — hence §4's in-process MCP endpoint and no wire
protocol at all.

**Verified**: the virtual-thread classloader hazard is already handled.
`quiescent/src/co/multiply/quiescent/impl/executor.cljc:19-20` captures
`RT/baseLoader` at load time and sets it on all three thread factories. A residual existed through 0.8.0: classes
emitted *after* that load — a `defrecord` defined mid-session — live in their own `DynamicClassLoader`, invisible to
parent-first delegation, so by-name resolution from inside a task (including nippy thaw) could miss them. In a
source-loaded dev JVM the captured loader is itself a `DynamicClassLoader`, whose static JVM-global class cache rescues
the lookup (measured 2026-08-01: a fresh `defrecord`, non-ancestor loaders, `Class/forName` through the task's loader
still resolved). The miss was real only where the captured loader was the plain application loader — an AOT uberjar
host, i.e. the deployed case. **Closed in Quiescent 0.8.1** (f58eda5): the executor wraps the captured loader in a
`DynamicClassLoader`, so cache consultation happens on every thread regardless of what was captured. Structurally
closed rather than empirically — a dev JVM passes either way, so the confirming measurement belongs to the first
deployed host. What remains is inherent to Clojure, not to the executor: re-evaluating a `defrecord` makes a new class
under the same name, and old instances are not instances of it. Direct var invocation was never affected.

