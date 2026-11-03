# 2026-08-05 — Third smoke test

**Third smoke test (2026-08-05, post-`get-ex` restart): clean pass, 24 evals — the previous round's fixes verified
in the field** (`get-ex` returned the CancellationException as a value; the tenant then derefed a catch on the
cancelled task \"accident-on-purpose\" and reported the propagation as *the doc's exact claim confirmed* — the
sharp edge is now a documented behavior a tester validates rather than a trap it falls into). Two observations,
neither a defect, both closed:
1. *The `dotimes` orphan trap:* its first async attempt spawned `__q/sleep` from `dotimes` — semantically correct
   silence (orphans, cascade-cancelled at form settle), but the reach itself was the gap. Closed as the eighth
   pattern namespace, `patterns.iteration`: repeating work has two honest shapes — `qfor` for parallel, plain
   imperative code *inside one task* for sequential, because virtual threads make blocking cheap — and one named
   trap between them: imperative iteration constructs spawning tasks. `__q/sleep` composed properly is fine —
   `(qdo (__q/sleep 1000) (fire!))` is delay-then-do; the trap is discarding what it returns. All three shapes
   verified live before writing (trap returns immediately, sequential ticks 3/3, parallel `[0 1 4]`). Candidate
   for Quiescent's own skill too — the reach-for-`dotimes` prior is a user-code hazard, not just an eval one.
   The deeper diagnosis, underlined in the instructions the same day: the implicit qdo's *synchronous veneer*
   invites the trap — `(__q/sleep 100)` as a top-level form works, because grounding waits for it, so \"tasks
   just run\" generalizes exactly one paren too far. The instructions now state where the veneer ends (\"inside
   a form you are writing ordinary async Clojure\"), quote the trap literally, and point at the `iteration`
   pattern.
2. *`;; evicted 1` read as a count:* now `;; evicted id 1` / `;; evicted ids 4, 5`, at both rendering sites (the
   response footer and the queued cross-session eviction news).
