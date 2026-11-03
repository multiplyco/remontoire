# 2026-08-06 — The manual leaves the block

**Superseded again (2026-08-06): the manual leaves the block — the lean pointer returns, this time with the
evidence the first attempt lacked.** The eager channel was indicted from inside a live claude-code session, on
three counts. *Staleness:* the instructions block in the running conversation's system prompt was an older build
than the server's current var — pre-`__q` recipes (`(q/catch (__r/res 12) identity)`) where the var, verified
identical to disk, teaches `(__q/then …)` — and it stayed stale through a CLI restart and two `/mcp` reconnects,
so the 2026-08-05 refresh-on-reconnect measurement does not generalize to long-lived continued conversations.
Eager instructions are a cache with no invalidation, serving a server that hot-reloads daily. *Truncation:* the
sleeper argument ran backwards — the block was observed cut mid-manual (`… [truncated]`) after the session
compacted; a long block is first in line for the knife, and a two-sentence one is effectively untruncatable.
(Whether claude-code also caps at delivery is unattributed from inside a compacted session; a fresh-session check
settles it.) This also explains the standing smoke-test observation that tenants pull `outline` despite
"holding" the manual: they need to. *Cost:* eager is paid by every conversation in a project carrying the
`.mcp.json` (~2.7 k tokens: ~1 k prose + ~1.7 k rendered manual, measured live), REPL used or not; and push
cannot dedupe — a server composes instructions blind to the client's other connections, so a local+prod pair
ships the surface twice, where pull dedupes for free because the puller knows what it already holds.

The successor design, settled 2026-08-06. The intro message goes lean (~80 tokens) and carries only what must be
eager — *when* and *whether*, never *how*: the fit ("prefer it for navigating and reading Clojure — namespaces,
signatures, docstrings, source, live data — where it is faster and more precise than file search"), phrased
affirmatively on purpose, because a use-only-when-you-must framing would suppress exactly the read-only calls
where `(__r/nses)` beats a file scan; the safety fact ("not a sandbox"), which governs effectful evals before any
manual is read and whose seriousness the model infers together with the environment; and the pointer — before
your first eval, run `(__r/manual)`, unless already read in this conversation — the dedup clause living in the
only place dedup can (the puller). `(__r/manual)` is a small genuine build (the prose currently has no eval-side
path at all): the `__r` outline with docstrings as the *return value* — live, evergreen by construction — and the
authored prose as a `println` riding `;; out`; value for the live half, print for the authored half is the honest
rendering of each side's provenance. Transport pre-verified: the outline renders to 8.2 k chars under the dumb
cap *untruncated* (`*print-length*` bounds collections, not strings, and the bulk is docstrings), and the out
channel is uncapped — nothing waits on `shape`. Around it: `init!` may take a self-description woven into the
intro ("here, the production system behind …") — the server naming its own environment in its own voice, which
redeems the earlier wish to set the client-visible name from `init!` (settled the same day: that name is
client-owned, the `.mcp.json`/`claude mcp add` config key, with `server-info`'s name ignored for display); a
`:manual-read` session mark set when `manual` runs, with an optional footer nudge on responses until it is —
keys stay in the model's hands, ignorance just can't stay silent — doubling as the compliance measurement.
README queue: the server-entry naming scheme (`mpf-local-repl` / `mpf-prod-repl` — the config name is the
model's only handle on *which system* it addresses, a safety affordance for prod), the responsibility note
(local is the presumed case; wiring prod is a deliberate act whose consequences belong to the wirer), and
project-scoped `.mcp.json` as the distribution vehicle. The smoke-test criterion is the usual one — fresh tenant,
`manual` called before the first real eval, first-eval competence held.

Built and wire-verified the same day. `instructions.md` renamed `manual.md` (first paragraph ceded to the intro,
manual-reference sentence reworded for the new delivery); `api/manual` marks, prints the prose, returns the
publics-only outline — and slurps the markdown *per call*, so prose edits now reach callers with no reload at all,
an upgrade on the old load-time composition; `session/manual-read!` guarded like `note!`, making the sessionless
in-process caller a no-op; the footer nudge computed at response build, so a `manual` call's own response arrives
already unmarked; `mcp/config` + `configure!` behind `defonce`, filled by `init!` before the port opens (`:describe`
in the defaults map, nil silent); `render-manual` deleted and the `io`/`outline`/bare-`api` requires with it; the
eval description's fallback pointer now names `(__r/manual)`. Verified live: the lean block serves at 512 chars
(down from 10.8 k); the describe variant weaves `(here: …)` correctly and probe sessions were released after
measuring; the unread note appeared on this session's responses immediately after reload — first response still
rendered by the pre-reload handler captured at dispatch, the next one nudging — and `(__r/manual)` returned the
full outline (privates absent), printed the prose whole under `;; out`, and retired the note from the very
response that carried it.
