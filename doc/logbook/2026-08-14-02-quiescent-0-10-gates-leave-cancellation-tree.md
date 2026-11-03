# 2026-08-14 — Quiescent 0.10.0: gates leave the cancellation tree; the manual entry lands against it

Upstream resolution of the 0.9.0 findings above, same day: Quiescent 0.10.0 makes the gate a pure
coordinator — off the tree, no control-task, no `ICancellable`, `(cancel gate)` a type error — deleting
both the reload trap and the silent-dead-gate mode outright rather than documenting them. Gated tasks are
unchanged: structured under their creator, so cancelling a spawning eval still tears down exactly its own
cohort while the gate, and other cohorts sharing it, run on. The taxonomy the release names — tasks and
promises are work, mortal tree members; gates and semaphores are coordinators, immortal and safe to `def`
globally — is the rule the manual now teaches in place of a gotcha list.

Landed here: quiescent bumped to 0.10.0; the manual's implicit-qdo section gains the def-boundary
paragraph (dynamic extent decides orphanhood, `def` lifts nothing out, reload paths driven by an eval mint
eval-children; compel-at-creation or capture the settled value for tasks, coordinators exempt by design);
`patterns/parallelism`'s `gated` reworded — its cancel-the-eval guarantee always flowed through the tasks'
own parenthood and survives verbatim, only the justification changed. The eval tool description's
overlapping claim ("spawned but not returned … cancelled by the time the eval settles") checked against
the new manual text: refined, not contradicted; left as is.
