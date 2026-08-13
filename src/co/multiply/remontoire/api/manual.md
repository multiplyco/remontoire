## About

Remontoire is a REPL with async semantics, connected into a live, running system: your evals run inside the JVM itself,
among its loaded namespaces and running state. Every eval executes as a task on a virtual thread, under a global id
carried on the response — and because no eval ever holds the connection, you cannot block it, intentionally or
otherwise. That one property sets the frame of mind:

- A long-running eval does not stall you. Control returns at your deadline, the work runs on, and you read the result
  when it settles. Keep evaluating in the meantime — later responses announce when earlier work settles — or, if you can
  run tool calls in the background, issue a long `await` and be notified.
- Runaway work is never a crisis: cancel it by id. Concurrency is structured — tasks form a tree under their eval, and
  cancellation cascades through everything the work spawned — so patterns an ordinary REPL forbids are routine here: a
  polling loop with `Thread/sleep` in the body is fine, because you can always cancel it later.
- Synchronous code evaluates exactly as you expect: imperatively, in order. Nothing is made async behind your back —
  async is opted into with Quiescent — and an async value you return (a Quiescent task, a `CompletableFuture`, a Clojure
  future; all auto-converted) extends the eval's lifetime: the eval settles when it does, and its result is what the
  task contained, not the task itself.

Remontoire is built on [Quiescent](https://github.com/multiplyco/quiescent), a structured concurrency library. Load the
Quiescent skill now[^1].

## Your toolkit

Your evals start in a scratch namespace of your session's own, and its wiring is your toolkit:

- `__r`: the remontoire API — the outline this call returns, every function with signatures and docstrings as data, is
  its reference. `__r` is the one alias that follows your evals into every namespace they touch.
- `q`: the Quiescent public API, plus its macros referred bare (`qlet`, `qdo`, `qfor`, …) — exactly the spelling
  committed user code uses. This wiring is scratch's own: in any other namespace, requiring Quiescent is yours to do.

Quiescent is a namespace like any other: `(__r/outline 'co.multiply.quiescent {:include-private false})` shows the API
that Quiescent exposes, or you can find information about specific concepts with eg.
`(__r/outline 'co.multiply.quiescent {:match "gate"})`.

An eval is an implicit `qdo` (see `(__r/doc co.multiply.quiescent/qdo)`): forms run in order, sync or async alike, each
top-level form's value grounded before the next form runs, and only the last form's value is the eval's result. So
`(q/sleep 5000)` as a form of its own is an async pause, and a returned task simply runs.

Grounding is deep — a returned data structure of tasks, `[(q/task (f)) (q/task (g))]`, runs them in parallel and the
eval waits for all: the idiomatic fan-out within one eval. Grounding walks the whole of each form's value, so what a
form returns must be finite: `(range)` as a form's own value never settles, nor does a structure with an infinite seq
anywhere inside it. Infinite seqs themselves are fine to work with — `(def nat-ints (range))` returns the var,
`(take 5 nat-ints)` a bounded value; it is only the returned value that cannot be unbounded.

That synchronous veneer ends at the form boundary — inside a form you are writing ordinary async Clojure, where a task
nobody returns or composes is an orphan: never waited on, and cancelled silently by the time the eval settles —
`(dotimes [_ 3] (q/sleep 100))` sleeps not at all. See the Quiescent documentation and the `iteration` pattern namespace
for alternatives.

## Handles and retention

Every response carries its eval's id. An id is a handle to work, not just to a result: compose on it before it settles —
`(q/then (__r/res 12) report!)`, `(q/cancel (__r/res 12))` — and deref `(__r/res 12)` for the value after, safely and
repeatedly. The mechanics — exceptions as values, failure renderings, reading output back — are in the docstrings of
`res`, `failure` and `out` in the reference this call returned.

Retention you can rely on: running work and unread results are never evicted, however much work piles on. Reading a
result is delivery — it admits the entry to a bounded least-recently-used pool, where what you keep touching is what
stays. If eviction does claim entries of yours, your next response says so; ids never stop resolving silently.

## Output

Printing is the cheapest instrumentation there is: output is captured per eval, arrives under `;; out`, and while an
eval runs its latest line is the live progress message. One caveat, true on any console: `println` writes its pieces
separately, so parallel tasks interleave mid-line — from concurrent work, print a whole line in one write,
`(print (str "tick " i "\n"))`.

## Navigation

The system is read by drilling down: `(__r/nses)` lists the loaded namespaces, `(__r/outline 'some.ns)` reads one —
signatures at a glance, docstrings and search via its options — `doc` and `source` read single vars, and `file` reads a
whole source file as lines, sliceable by the `:line` an outline entry carries. When the namespace is the unknown — where
does that function live? — `(__r/vars "await")` searches every loaded var by name and docstring, and the query is
spelled the way Clojure spells the target: `(__r/vars "helpers/await")` scopes to matching namespaces, `"helpers/"`
lists a scope whole, `*` is a wildcard, and a regex containing `/` matches fully qualified names. All of these return
plain data — `vars` scored, best hits first — so a large result is a collection like any other: `take`, `drop`,
`filter`, whatever idiom builds the narrowing or pagination the search calls for. Recipes for the common async shapes —
cleanup, deadlines, partial results, integrations, work that outlives its eval, bounded fan-out, repeating work — are
loaded pattern namespaces: `(__r/nses "patterns")` is the index, `outline` and `file` read them.

## Sharing the JVM

One JVM, one system: the registry is shared across every connected session — `(__r/evals)` lists everything, `:by`
tags what other sessions minted, and `(__r/res id)` reaches it all, which is how sessions hand work to each other.
Etiquette: read each other's settled work; leave a stranger's running entries to their owner. Your own session holds
your current namespace and your delivery claims, and expires after 24 h of silence; `sessions` (the roster),
`introduce!` (your line on it) and `release!` (the punctuation that marks a leg of work absorbed) are each a docstring
away.

Your scratch namespace is yours to `def` and `require` in freely — a namespace of your own, so nothing you mint collides
with another tenant's. Its Quiescent wiring means one dialect everywhere: an idiom copies verbatim between the Quiescent
skill, the host's own source and your evals, in both directions. Outside scratch, requiring and aliasing Quiescent is
yours to do — and since you did it, you know what you called it. One recommendation: in a namespace you don't own, alias
it `__q` — requiring into a host namespace rewires its alias map, and the dunder is the collision-proof spelling of that
act.

When one scratch isn't enough — two explorations whose defs would collide — `(__r/scratch! "kbtest")` switches to a
named compartment of your own under it, minted and wired like scratch on first use, and within your session the same
label is the same compartment thereafter. `(__r/scratch!)` returns to the main scratch, `(__r/scratches)` lists the
family, and `retire-scratch!` removes a compartment.

Hygiene worth starting from: work from scratch and reach into the system by qualified name — `#'their.ns/private-var`
reads and calls even privates — leaving host namespaces as your visit found them. Moving in with `in-ns`, or requiring
and `def`-ing into a namespace others own, is sometimes exactly what the job needs; it just rarely needs to be the
opening move.

[^1]: `__r/outline` on the `co.multiply.quiescent` will serve if the Quiescent skill is unavailable.