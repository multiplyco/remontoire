# 2026-08-14 — Gates die with their eval, exactly like tasks; the probe that said otherwise could not fail

A field report from a tenant embedding Remontoire in a host application: a namespace-level
`(def gate (q/gate n))`, re-created by an eval that drove the host's sweeping reload path (clj-reload),
came back dead — the gate was born a child of the eval and cancelled with it. Verified here against
quiescent 0.9.0, and extended to the narrower question the report left open: plain `(require ns :reload)`
behaves identically. A probe namespace loaded via require inside an eval had its top-level task cancelled
and its top-level gate's control-task cancelled at eval settle — within 205 ms by a compelled watcher.
Parenthood is dynamic extent at creation; `def` lifts nothing out; every load path that re-evaluates def
forms inside an eval mints eval-children. Boot and plain nREPL loads are safe for the same reason the
eval chain is rooted (§9): no ambient task, so top-level resources are roots.

Gates have no task-space escape, mechanically confirmed: `(q/compel (q/gate n))` throws
IllegalArgumentException — compel accepts only Tasks — and the smuggle `@(q/compel (q/task (q/gate n)))`
fails too, since the gate parents to the inner task and dies the moment *it* settles. The tenant
conversation's mechanism-story ("compel insulates with a wrapper task") is falsified in the detail —
there is no wrapper, there is a type error — while its conclusion stands stronger: tasks have two
reload-safe shapes (compel at creation, capture the settled value), gates have exactly one — never
re-create (`defonce`), or mint off-tree.

Mid-trial this suite briefly "falsified" the report — several def'd gates read alive minutes after
settle — and the falsification was itself falsified: the probe was
`(try (q/gate-task g …) :alive (catch CancellationException …))`, and enqueue on a cancelled gate does
not throw. It returns an already-cancelled task ("Gate cancelled.") whose exception surfaces only at
deref, `get-now` or grounding — with a stack pointing at `Gate/enqueue`, the construction site, not the
use site. Direct `isCancelled` on the control-task told the truth throughout: every gate dies promptly.
A liveness probe that cannot fail reads as survival.

That same property is the manual-worthy sharp edge, beyond the def-boundary gotcha itself: a dead gate
is silent at the call border. `gate-task` succeeds, the task it returns is cancelled, and cancellation
is a control signal — `then`/`catch`/`handle` never run, only `finally` sees it; grounding it cancels
the surrounding expression instead of failing it. After a reload that re-created a def'd gate, every
request through it evaporates as cancellation, invisible to error-shaped handling. Manual entry pending.
