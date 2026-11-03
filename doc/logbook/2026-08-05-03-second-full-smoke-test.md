# 2026-08-05 — Second full smoke test

**Second full smoke test (2026-08-05, fresh tenant, post-restart JVM with the pattern book, drilldown, `__q` rail
and `*err*` tee aboard): 26 evals, every core surface, verdict \"holds up very well\" — and the first field report
to drive a change *upstream*.** Its battery covered the whole day's work sight-unseen: pattern index followed to a
real file, `(ex-data (__q/catch …))` recovered programmatically, compel-vs-unreturned-child verified with counter
evidence, eviction and bogus-id errors quoted approvingly. Three findings, all reproduced live before fixing:
1. *The sharp edge (real):* the taught catch recipe, applied to a **cancelled** entry, silently cancelled the
   probing eval itself — Quiescent semantics working correctly (`catch` never sees cancellation; grounding
   propagates it) against a recipe that only said \"the failure as a value.\" The safe probes (`failure`, which
   status-checks first; `cancelled?`; `await`) all existed and none were taught at the point of need. Closed from
   both ends: **Quiescent 0.9.0 gained `get-ex`** — the symmetric partner of `get-now`: the exception as a value,
   failure and cancellation alike, nil otherwise, non-blocking, never throws, the read `catch` cannot express —
   prototyped and validated through the live connection against the impl's own `isExceptional`/`getResult` shims
   before landing upstream. The taught recipe is now `(__q/get-ex (__r/res 12))` everywhere (instructions, `res`
   and `failure` docstrings, registry's own doc), with the catch-propagation caveat stated beside it; and the
   `:cancelled` response note — which fires almost only for propagation, since a directly-cancelled `running` eval
   gets no later response — now renders the exception's own message: `cancelled — Task cancelled directly.`, the
   provenance the exception already carried.
2. *Stale ns on running responses (real, one line):* the footer default read the session's sticky ns, which only
   eof updates — an explicit-ns eval outliving its await reported the previous namespace. Fixed: the default is
   now where the eval *started* (explicit request, else sticky); `:done` keeps its authoritative override.
3. *Eviction \"at ~7 read entries\" (working as designed, message fibbed):* capacity counts the whole registry and
   protection is absolute, so read entries bear all the pressure — but the error said \"keeps the 20 most recently
   used settled evals,\" implying 20 delivered slots. Reworded to name the mechanism: holds N entries, running and
   unread protected, least recently touched of the read entries go first.
The loop's full length this round: field report → live reproduction → library gap identified → `get-ex` upstream
in Quiescent 0.9.0 → recipe swap downstream → response teaches provenance. The tenant also tripped exactly where
the reviewer did minutes later (`cancelled?` returns a plain boolean, not a task) — converging evidence that the
corner needed the docs it now has. The codebase eats the same dish: both internal exception extractions —
`failure`'s and the failed-response render's `@(q/catch task identity)` — became `(q/get-ex task)`, retiring the
chain-and-deref dance everywhere the taught recipe retired it.
