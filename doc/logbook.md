# Remontoire logbook

The dated, append-only record — builds, trials, smoke tests, measurements — oldest first. The doctrine these
entries settled lives distilled in `doc/design/`; this file is the evidence trail. (Moved out of §11 when the
design doc was split, 2026-08-07; §-references resolve per the convention in CLAUDE.md.)

## 2026-08-04 — The model API layer

Built 2026-08-04 — see §4. Contents to be tuned in use; candidates from §12 join it
as they land. 2026-08-05: its docstrings made self-contained — no § pointers, no internal namespace names, no
design-doc episodes — because `(outline '__r {:include-docstrings true})` is the planned `instructions` bootstrap:
the docstrings are the manual a fresh model reads, assuming only the conceptual foundation `instructions` itself
will carry. The § apparatus stays in the internal namespaces, whose readers have this document. The full-docstring
outline measures ~1.4 k tokens — one roundtrip replacing piecemeal `doc` discovery, cheap for a once-per-session
orientation.

## 2026-08-05 — The `instructions` field

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

## 2026-08-05 — The manual rides the instructions block

**Superseded the same day by the final form: the manual rides the instructions block itself.** The trials taught
a hierarchy — options get stripped, instructions get followed, ambient context gets absorbed — so the manual
moved to the top tier: `render-manual` in `mcp` renders the API layer's public vars (signature plus live
docstring, markdown, not `pr-str` — it is an instruction manual now, not a return value) and the `instructions`
def appends it to the prose half at load. ~1.55 k tokens all told, zero roundtrips, zero drift by construction,
and — the sleeper argument — a system prompt survives the conversation compaction that would eventually swallow a
fetched manual. Publics only; privates stay readable through a real outline. With the delivery job gone, `outline`
reverted to the signatures-only glance and the bootstrap imperative left the instructions — replaced by \"its
manual follows at the end of these instructions\"; the eval description keeps the full-incantation pointer as the
fallback for clients that ignore `instructions` entirely. Wire-verified: initialize serves prose + ten-entry
manual, 6.2 k chars. Trial four (fresh tenant, manual-bearing block): it operated `introduce!` and `sessions`
correctly — arities, composition, timing — *before any introspection*, and since the description slimming the
block is the only place those functions are taught: knowledge at birth, where trial one had to outline to learn
the same surface. Its later `(outline '__r)` was the *bare* form with \"checking the toolkit\" narration —
feature-under-test in a smoke test, not re-orientation; a model already holding the docstrings has no reason to
re-order the manual, and the signatures-only ping is what \"does outline work?\" costs. Its full battery surfaced
the first field-driven manual tuning: it concluded \"the API doesn't offer programmatic access to exception data\"
— false (`@(q/catch (res id) identity)` then `ex-data`, verified live) but instructively so, since `failure`'s own
docstring never pointed across to the composition; and it flagged `doc` as possibly not printing, when its bare
symbol simply didn't resolve in the `user` namespace it had navigated to — `clojure.repl/doc` prints nothing for
an unresolvable name. Both closed as docstring amendments, and the manual re-rendered itself on reload: the
single-source loop working as designed.

## 2026-08-05 — Second full smoke test

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

## 2026-08-05 — Third smoke test

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

## 2026-08-06 — The manual leaves the block

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

## 2026-08-06 — Fourth smoke test

**Fourth smoke test (2026-08-06, fresh tenant, first of the lean-instructions era): the criterion held.** Asked
only to smoke test from a no-prior-knowledge stance, the tenant ran `(__r/manual)` before its first real eval —
the pull design's make-or-break behavior, n=1 — then learned the entire system through the drilldown without one
filesystem read ("discoverability is genuinely good"), ~35 calls and ~6 k tokens all told, against the 2.7 k the
eager design charged every conversation up front. Verdict "passes"; the full battery (navigation, ns continuity,
error rendering, running/settled lifecycle, cancellation-as-value, cross-eval composition, compel, `:by`
attribution, `release!`) confirmed the manual's claims empirically, including a deliberate reproduction of the
`dotimes` orphan trap. Three findings, all reproduced live: *(1) `qfor` silently drops extra binding entries* —
`(qfor [i (range 5) :max-parallelism 1] …)` macroexpands to the first pair with the rest discarded (verified by
`macroexpand-1`), so a guessed modifier becomes a silent behavioral surprise; a shape assert in the macro is the
fix, upstream in Quiescent — queued as a candidate alongside the skill's `dotimes` item. The want itself
(bounding parallelism) is `q/gate`, which a clued-in model reaches two ways — the Quiescent skill where
available, or outlining `co.multiply.quiescent` — so the manual now says the latter out loud: Quiescent is a
namespace like any other, worth outlining before guessing at an option it might not have. *(2) Concurrent
`println`s interleave mid-line in the tee* — reproduced verbatim (`ticktickticktick 1` from six parallel tasks).
Settled as teaching, not machinery: the interleaving is any console's truth (`println` writes its pieces
separately), the tee's never-withholds doctrine is what makes the progress line and `since` cursor trustworthy,
and the tenant *read the garbled output correctly* — the evidence bar for read-side line assembly is a model
drawing wrong conclusions from it, not cosmetics. The manual now carries the working idiom where printing is
taught as the cheapest instrumentation: from concurrent work, print a whole line in one write —
`(print (str "tick " i "\n"))`, one string one write, where `(println (str …))` still writes its newline
separately. Both manual additions reached the served surface with no reload — the per-call slurp working as
designed. (The file itself moved to `api/manual.md` the same day, beside the surface it documents.)
*(3) The footer named a namespace the session never entered* — `;; id 36 · ns no.such.namespace` on a
failed switch, the requested-not-effective ns. Fixed the same day: an explicit ns that does not exist was never
entered (`resolve-ns` refuses inside the task), so the footer default now trusts the request only when `find-ns`
confirms it, falling back to the session's actual place — verified both ways (bogus ns reports scratch, a
deliberate throw inside `clojure.string` still reports `clojure.string`).

## 2026-08-06 — The test suite

**Test suite (2026-08-06, frozen into the initial commit).** Invariants over wording — exact messages and
instructions drift by design; error-free behavior is the contract. Six namespaces plus rigging (`bb test`, kaocha):
bank mechanics, session state, the eval model, the retention guarantees, MCP dispatch without a socket (the mcp
namespace's own premise, with the transport's delivery discipline emulated by invoking `::delivered` after the
response), and real HTTP roundtrips — `init!` on an ephemeral loopback port, the JDK's own `HttpClient` speaking
actual MCP at it, Quiescent grounding three concurrent requests in parallel. Writing it refined two claims, both
confirmed against Quiescent's actual doctrine: *(a) orphan cancellation lands at eval settle, not the form
border.* A first draft asserted the manual's phrasing — orphans die "when its form settles" — and failed
honestly: auto-flattening means each link's task settles only when the downstream chain does, so an orphan from
form 2 is still alive while form 3 runs. The observable contract — the eval neither waits for orphans nor lets
them outlive it, `compel` the moat that does survive — is what the suite pins and what the manual now says
("by the time the eval settles"). Quiescent moves on as quickly as it can; waiting for quiescence is itself
composition — deref `q/cancel` or chain off it — which the `cancel` tool already does by awaiting each task.
*(b) A returned infinite lazy seq never settles*: grounding realizes lazy forms — deliberate upstream, since
letting laziness through could produce tasks outside the structured-concurrency tree, a no-win trade — so
`(range)` as a final form is an eval that runs forever: cancellable, never rendered, and out of reach of the
print cap and of `shape` alike, both of which run after settlement. The guard is teaching, not machinery.
One rig-level finding: kaocha's output-capture plugin swaps the root `*out*` per test, displacing the tee — the
suite runs with capture off, which is also the honest configuration: the tees installed at the root exactly as in
a live host.

## 2026-08-06 — In-situ smoke test

**In-situ smoke test (2026-08-06, fluent-platform, the 0.1.0 jar from Clojars — first run in a real app).**
A clean pass across the whole surface in a 1909-namespace JVM: the running lifecycle end-to-end (live progress
line at the await deadline, `await` blocking to `:done`, `res`/`out` delivering after), cancellation, failure
rendering with machinery-subtracted frames surfacing a genuine app-side NPE cleanly, multi-session roster,
namespace stickiness, eviction announcing itself under load, and app log lines interleaving into `;; out` —
it is the real JVM. The detach story composed with the harness: a 10-minute `await` held the call open, Claude
Code backgrounded it at its own 120 s threshold and delivered the settled result as a task notification, while
the registry id stayed readable from parallel evals meanwhile — two independent handles to the same work,
neither stepping on the other. The vehicle was real: two Rama module updates fanned out in parallel via
`[(q/task (update-module! …)) (q/task (update-module! …))]` — deep grounding making a returned vector of tasks
the idiomatic single-eval fan-out, now a line in the manual's async section. The truncation/`res` split
validated exactly as designed: a 10k-element vector rendered cut at ~128 elements while
`(count @(__r/res 26))` → 10000 — truncated rendering plus a composable handle to the untruncated value.
Two doc amendments came out of it: the eval description now states last-value-only up front ("batch independent
probes in a vector or labeled map" — the tenant guessed wrong first; last-value-only itself stays, because a
per-form subindex would leak into the whole registry model — an id is a handle to one task, one status — to buy
what a caller-labeled map already expresses better), and the description's orphan sentence was corrected to
"by the time the eval settles", matching the manual. The tenant's `__r` wishlist, ranked by felt friction, noted
for the next phase: (1) global var search — outline-shaped hits with `:ns` attached, docstring search with
`:matched` evidence; the one it actually missed ("where does normalize-str live?" required guessing namespaces);
(2) usages — who calls this, across loaded namespaces (already on the roadmap via clj-kondo); (3) truncation
annotated with what was elided (`... 9872 more]`) — folds into `shape`; (4) classpath-aware namespace discovery
(`{:all true}` marking loadable-but-unloaded); (5) Java class outline — `clojure.reflect` reshaped into sorted
members-and-signatures data; (6) an `out` tail option for chatty long evals. Declined by the tenant itself, in
agreement: value pretty-printing helpers (truncation + `res` covers it) and reload/system-lifecycle helpers —
project conventions, not the lens's business.

## 2026-08-07 — `vars`: global var search

**`vars` built (2026-08-07) — item (1) from the in-situ wishlist.** `outline`'s matcher and entry machinery mapped
across `all-ns`; surfaced as `__r/vars`. Measured on build day against this project's own JVM (101 namespaces, via
the live MCP connection): the broadest possible search — the single letter "a", 2031 hits — completes in ~18 ms,
linear meta reads with no index, so a 1900-namespace JVM extrapolates to a few hundred ms worst case and far less
for any real term. Verified live: a private var found and marked (`designated`), docstring hits quoting `:matched`
evidence (a search for "designated" surfacing `defrecord`'s docstring line), symbol/string/regex all matching, the
nil-match throw reading plainly, and the composability invariant round-tripped — a hit's `:ns` fed `outline`, the
qualified symbol fed `doc`. One shape observed and kept: within a namespace hits arrive in source order, so a
docstring mention can precede the exact-name definition (`(vars 'gate-task)` leads with `gate`, whose docstring
mentions it) — grep semantics, judged not worth a ranking pass. Suite green after the build: 71 tests,
251 assertions.

Revised the same day: the flat ordering was falsified by first real use — `(vars 'status)` put the literal
`(defn status …)` in `co.multiply.remontoire` third, behind docstring mentions, exactly the "where does it live"
question answering itself badly. Scoring added: three tiers — exact name match, name hit, docstring-only hit —
namespace-then-source-line within each; the shape stays one flat vector, the ordering carrying the ranking.
Verified live: `(vars 'status)` now leads with the two exact-named defns before any mention. Suite green:
71 tests, 252 assertions.

## 2026-08-07 — Truncation announces its size

**Built (2026-08-07) — item (3) from the in-situ wishlist, the interim form.** The value renderer's caps stay as they
are; what changed is the silence: a cut rendering now carries a footer line — `;; truncated — 9872 elided ·
(res 8) is the whole value` — produced by a mirror of the printer's own walk (counted collections counted exactly for
free, an uncounted seq probed one already-realized cell past the cap, below-`*print-level*` subtrees reported hidden,
not sized). The inline `... n more` rendering the wishlist sketched was considered and declined: it needs a custom
bounded printer, which is precisely the machinery §6's `shape` will retire the cap with — the footer line joins the
eviction announcement instead, same doctrine, and nothing gets thrown away when `shape` lands. Verified live through
the MCP connection: `(vec (range 10000))` → "9872 elided" (the wishlist's own example number), `(take 200 (range))`
→ "uncounted elision" with `(count @(__r/res 9))` → 200 behind it. One trap re-confirmed while testing: an eval
returning an *infinite* seq never settles — deep grounding walks the return value — so the infinite case cannot
reach the renderer at all; the honest uncounted case is a finite lazy seq. Suite green: 72 tests, 258 assertions.
