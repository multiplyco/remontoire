# 2026-08-05 — The `instructions` field

**The `instructions` field, built 2026-08-05.** The spec's `InitializeResult.instructions` is the established
channel for server-to-model orientation, and claude-code injects it into the system prompt verbatim (observed from
inside a session carrying three other servers' blocks). The division of labor: `instructions` carries the
conceptual model — what an id is, the retention invariants, one-JVM-one-system sharing, session expiry and the
punctuation etiquette — plus the bootstrap line, `(__r/outline '__r {:include-docstrings true})`; tool
descriptions retreat to tool mechanics (the eval description's registry-doctrine and `__r`-enumeration paragraphs
migrated out); the API docstrings, fetched through the bootstrap, are the manual. The text lives in
`instructions.md` beside `mcp.clj` — prose edited as prose — slurped at namespace load, so a prose edit takes the
usual reload; it sits under `src` rather than a `resources` root because the running JVM's classpath is fixed at
start and `src` is already on it. Fetched once per initialize with no change notification,
so it holds doctrine, nothing volatile; the 24 h expiry appears as a literal, with the sync reminder on
`await-clamp`. Wire-verified by curl initialize (field present, text exact, probe session released by DELETE).
Building it surfaced a broken promise: every teaching writes `(q/catch …)`, but `q` resolved only in scratch —
`ensure-api-alias!` became `ensure-aliases!`, which asserts `__r` (obscure by design, stale bindings clobbered)
and fills `q` only when free, since `q` is a name a host namespace may legitimately own (verified in vivo: a
namespace aliasing `q` to `clojure.string` kept its claim through an eval while `__r` was asserted beside it).
Client-side injection measured 2026-08-05: a mid-session `/mcp` reconnect re-initialized, and claude-code injected
the block into the model's system prompt verbatim, under `# MCP Server Instructions` — so instructions refresh on
reconnect, not only at session birth. Behavioral uptake, first fresh tenant (2026-08-05, asked only for "a basic
smoke test"): one sanity arithmetic call, then `(__r/outline '__r)` unprompted — the front-door habit took. It
chose the *bare* form over the instructions' full-docstring incantation — and everything it then did correctly
(await/res lifecycle, cancellation, a closing comma `release!` interpreted rightly against the retention model) it
learned from the instructions block and tool descriptions, not from the outline it read. So the miss was masked by
redundancy the architecture intends to thin: as an instruction, the bootstrap line failed. Reworded the same day —
"Before your first real eval, read the manual", the cost of skipping named (docstrings carry semantics signatures
do not), the bare form repositioned as the tool for *other* namespaces rather than a lighter version of the same
step. The retrial (second tenant, same day, freshly restarted JVM) failed identically: the option was stripped
again. n=2 — an option spelled out in an instruction is optimization surface, and wording does not defend it.
Resolved by inverting the affordance instead: `outline` defaults to docstrings, the bootstrap line is the
optionless `(__r/outline '__r)`, and the laziest possible call *is* the compliant call — the model does not know
it ordered the manual, but it is about to find out. Trial three (same day, third fresh tenant): held — the plain
call returned the manual whole. That tenant also `introduce!`d itself as its *first form*, before its sanity
arithmetic — the etiquette both earlier tenants skipped; the roster showed \"smoke test of Remontoire\" against
its row from the other side. Not attributable with confidence (the block also got leaner between trials), but the
drift is in the right direction: instructions written closer to how models actually economize get followed more,
not less.
