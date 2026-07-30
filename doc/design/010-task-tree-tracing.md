# 10. Task-tree tracing

`(q/traced collector expr)` — a live tree of in-flight tasks with parent links, timings, and handles.

**Verified, and it makes the feature nearly free**:
`quiescent/src/co/multiply/quiescent/impl.cljc:443` already does

```clojure
(call/setScope t (assoc-scope (current-scope) *this* t))
```

Quiescent threads identity through its own spawn path: at task creation it captures the ambient scope and extends it
with `*this*` bound to the new task; every closure then runs under `(with-scope scope …)`. **A child's captured scope
already contains its parent's `*this*`.** The parent link is present and merely unrecorded. Because `co.multiply.scoped`
captures and re-establishes a scope map (JVM 25 `ScopedValue`) rather than relying on `StructuredTaskScope` inheritance,
this survives the plain `Thread/ofVirtual` factory — and unlike `binding`, it does propagate across the spawn.

So the incision is **one hook at that line**: if a collector is in scope, record
`{:id t :parent <previous *this*> :label … :started …}`. Roots come out with
`:parent nil` — `quiescent.cljc:1506` and `:1534` already establish that for detached contexts. Forest of roots, for
free.

- **The collector belongs in the scope, not a global.** Traces then cannot leak between unrelated computations, and
  nested `q/traced` naturally shadows to capture a subtree. No teardown to get wrong.
- **Overhead when untraced is negligible** — line 443 already allocates a scope map, so one extra lookup is noise. Safe
  on the task-creation path in production code.
- **Node identity can be the Task itself**, so subtree cancellation is a walk over the collector plus `q/cancel`. No
  separate handle registry.
- **Trace nodes are a third element type**, streamed:
  `{:id :parent :label :state :started :finished}`. Tree-building is
  `group-by :parent`, "what's still running" is `filter`, hotspots are `sort-by`. No trace query API.
- Worth checking whether the node shape can match the forest-of-roots shape a host's production telemetry pipeline
  already uses — one renderer would then serve local dev and production both.

What this buys: the investigations that have cost the most — which node is parked and whether it sits above or below the
permit layer; distinguishing "parked" from
"no progress", which last-progress timestamps could not — become data rather than thread dumps and internal reflection.

## Known limitation: handover

Task-tree inspection does not follow work **handed over** to another execution context — e.g. code that runs inside a
separate compute runtime with no app-layer resource cluster. Crossing that boundary requires instrumenting the far
side with the same collector and making its results readable by some other route. The in-process case is valuable
enough to build regardless.

