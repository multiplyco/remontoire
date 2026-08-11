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

The trap got its teaching line the same day: the manual's grounding paragraph and the eval description now both say
it — grounding walks the whole of each form's value, so a returned infinite seq never settles; work with them freely
(`(def nat-ints (range))` returns the var, `(take 5 nat-ints)` a bounded value), return them bounded. Verified live
before writing: the def-and-take shape settles cleanly through the connection.

## 2026-08-07 — The output tail, and the bank goes line-structured

**Built (2026-08-07) — item (6) from the in-situ wishlist, taken structurally.** The question that shaped it: values
truncate safely because `(res id)` holds the whole, but the output bank keeps only a 32 KiB tail — would a tail
option lose the rest? No: `out` is a non-consuming read, so a window takes nothing from a later full read; the only
irretrievable drop is the cap's own, which predates the feature and stays announced. Henrik's structural call: bank
*entries*, not one string — retain or drop whole log lines, string manipulation only at the edges. So the bank became
a queue of completed lines plus the pending unterminated partial: trimming pops whole lines oldest-first (the one
surviving string cut is a single line outgrowing the entire cap), `tail` is a `subvec` window with what lies behind
announced (`…[6 more lines retained]`), and `deposit!` shed an accident the restructure exposed — the old
string-concat bank re-copied the whole retained 32 KiB on every write. The `since` cursor kept its character-count
contract through the restructure, tests unchanged and passing. Surfaced as `(__r/out id n)`, the pending line
counting as the newest entry — it is the progress line. Verified live: an 8-line chatty eval tailed to 3 showed the
last two lines plus the in-progress print with the window announced, the full read intact behind it. The reload
itself demonstrated the transition hazard on cue: the eval carrying the `:reload` deposited into a bank minted by
the old build and NPE'd — old-shape state meeting new code, machinery-subtracted frames pointing straight at it;
the next eval minted fresh and ran clean. Suite green: 74 tests, 266 assertions.

Revised the same day: the windowing arity lasted hours. Henrik's ergonomics question — wouldn't `out` returning the
vector compose better than `(out id n)`? — answered itself the moment it met the doctrine: results are plain data,
composition is the option surface, and `(out id n skip)` is exactly the per-function knob §2 rejects. So `out`
returns the vector of lines (terminators stripped, pending line last), `take-last` peeks, `drop-last` pages,
`filter` searches — verified live, all four idioms through the connection — and the drop announcement moved to the
vector's first element, positionally honest at the head end where tail-reaching composition never meets it. One
trim rule tightened in passing: a completed entry outgrowing the whole cap is now cut from its own front instead of
dropped whole — tail-keeping outranks entry purity when the two collide. Suite green: 75 tests, 268 assertions.

## 2026-08-10 — Third in-situ smoke test: the manual restructured, and `__q` retired

A fresh tenant on the same live host system ran the full surface — manual onboarding, `vars` scoring, a 12 s eval
outliving its await with live progress, failure rendering, teardown, cancel-by-id, `release!` — all passing, and
its impressions confirmed the bet's shape: settlement footers, trimmed exceptions and cancel-by-id are what saved
effort, and the manual's irreplaceable job is the silent-failure modes (orphans, infinite returns, retention) that
no response teaches by example. Asked what was tangential, it named the `__q`-vs-`q` rationale paragraph as design
document rather than manual, and — pressed with Henrik's alternative — made the case that ended the split: aliasing
Quiescent as `q` in scratch only makes eval idioms verbatim-portable against the skill and committed source, and
the foreign-namespace failure mode flips from prevented-by-guarantee to loud-and-self-diagnosing (`No such
namespace: q`, the model's own omission, one roundtrip). The `__q` injection was a permanent per-eval tax spent
preventing exactly that error.

So built the same day: `ensure-aliases!` asserts `__r` alone; scratch requires Quiescent `:as q` (bare refers
unchanged); the manual, eval description, api docstrings, pattern namespaces and tests all speak `q`. The manual
keeps one line of residue as advice, not machinery: alias Quiescent `__q` in a namespace you don't own, the
collision-proof spelling of rewiring someone else's alias map. New invariants pinned: scratch resolves `q/task`,
and a visited namespace carries neither `q` nor `__q` unless the model put it there. Doctrine revised in §4's
tooling-rail record. The manual also went principles-first this week (About → toolkit → async → handles →
navigation → sharing), per the same tenant's read that the frame of mind, not the tool list, is what a fresh
session needs first.

The tenant's remaining observations, unactioned and noted: the manual payload is the biggest single token cost in
the flow (full docstrings for all publics; a first-sentence-only tier for the rarely-touched ones would cut a
third); mid-run `out` peeking rarely beats turn latency for sub-10 s work, suggesting the description nudge toward
sizing `await` to the expected duration instead; `(__r/evals)` listing the eval executing it as `:running` costs a
double-take.

## 2026-08-11 — Fourth in-situ trial: losing the decision moment, and the qualified match

The fourth in-situ session surfaced an electivity failure with unusually clean introspection attached. Asked where
a function lived, a tenant that had read the manual — `vars` docstring included — reached for grep anyway: two
guessed file paths, one nonexistent, and an eyeballed sed window. Run afterward, the `vars` call answered strictly
better: namespace, both arities with option maps, line, a private sibling flagged. The tenant's own post-mortem:
tool choice runs on recall cost at the decision moment, not on manuals read earlier — grep is zero-recall muscle
memory, an options map is not; the instructions block is the only surface reliably in context when the question
arises; and what wins there is a concrete incantation, not an abstract preference ("prefer it for var lookups" was
in context and lost anyway).

Built the same day, both halves. The query language: a `/` in the match qualifies it — `(vars "helpers/await")`,
string or symbol, scopes to namespaces matching the ns side and matches the name side within them, docstrings
included; `"helpers/"` lists a scope whole; `"/x"` degenerates to the bare search; `*` is a wildcard in every
match site; a regex containing `/` matches whole qualified names, slashless regexes unchanged; `:ns-match` is
retired. The natural spelling had been a silent `[]` — a trap worse than absence, reading as "no such var" — and
now it either works or throws (`"/"`, multi-slash; the var named `/` is reached as `'clojure.core//`, split by the
reader). The `vars` docstring grew an `Examples:` section — one call per operator, paste-ready — after the
observation that a query mini-language is better shown than described, and its first line became a capability
contrast: grep guesses paths over the repo's text; this reads the JVM.

The instructions carry the same contrast plus four intent→incantation pairs (`vars` / `outline` / `doc`+`source` /
`file`), each phrased as the question a model asks itself, with "effectively instant" retiring the observed
misprice of navigation calls as async round-trips. The bet on named helpers is structural, not stylistic: the
server exposes three tools — eval, await, cancel — so at the tool-list level "var search" does not exist as an
affordance; the instructions are the only eager surface that can give navigation a name, and a named call turns
"faster than grep" from a claim into a one-eval experiment. Cost accepted with eyes open: the block roughly
doubles (~90 → ~170 words), paid per conversation per connected server — a client reaching several Remontoire
hosts pays it once each — against the doctrine that the eager channel carries only what must be eager. The
evidence said the lean version loses the moment it exists for; whether the card flips the default is the next
in-situ trial's question.

## 2026-08-11 — Fifth in-situ trial: the qualified match in the wild, and a conceptual-search wish

Same day, after the 0.4.0 cut, a directed comparison rather than an electivity test: a tenant on an application
JVM was asked to evaluate three lookup paths — plain text search, Remontoire, and a code-graph indexer — for
"find the function behind a mentioned qualified name", running the same lookup through each. Because the choice
was assigned, the standing question — whether the recipe card flips the grep default at an unprompted decision
moment — stays open.

What the trial does establish: the query language lands first try. Working from the shipped surfaces alone, the
tenant spelled `(__r/vars "helpers/await")`, got exactly the intended two hits — the public var with arglists
and line, the private sibling flagged — and cited the design's own rationale as the reason it worked ("the query
is spelled the way Clojure spells the target: ns-partial/name-partial"). Its verdict called it the most precise
query language of the three: no rank scores, no hundreds-deep haystack, `doc` + `source` one eval behind. The
costs it priced: the one-time manual read (~4k tokens that session) as the entry fee, and loaded-namespaces-only
as the one real blind spot — a namespace nothing has required yet is invisible. Plain text search won the cold
start. All consistent with the positioning: Remontoire earns its keep when a REPL is already in the loop, or
when the system is remote and the question is about what actually runs.

Wish surfaced, not built: vocabulary-bridging search — "where do we throttle" finding the var whose docstring
says rate-limiting. The sketch from the discussion: the var is the natural chunk (ns/name + arglists + docstring
+ source, kebab-case names word-split), enumerable by the machinery `vars` already uses, dependency jars
included — the chunking problem that sinks file-splitting indexers doesn't exist here. Ranked BM25 over that
corpus first: small, dependency-free, builds inline in well under a second, and where docstring discipline is
high it covers most of the vocabulary gap. An embedding tier only as strict opt-in behind a pluggable embedder
slot — no embedder is a legal state, and no remote fallback ever: for a tool pointed at live systems, "source
and docstrings never leave the process" should stay absolute. At loaded-system scale (tens of thousands of vars)
a brute-force cosine scan over a flat array beats any index structure. The lazy shape falls out of the async
model: the first call builds BM25 and answers immediately, background embedding warms with progress reported the
way progress is always reported, later calls merge tiers and say which answered; content-hash dirty tracking per
var keeps hot reloads honest — the live-system promise file-based indexers structurally cannot make.

## 2026-08-11 — Ranking follow-up from the fourth trial's tenant: sigil-aware exactness, publics first

Shown the shipped qualified match, the fourth trial's tenant confirmed the zero-recall spelling — both partial
and fully qualified forms landed without an option map — and stress-tested the ranking into one refinement with
two halves. For a stem query, recall was perfect but the head of the list was the decorated family in source
order; to a Clojure eye `claim?`, `->Claim` and `-Claim` *are* the exact matches — the decoration is
convention, not name. And a private helper outranked its public sibling on line order alone, against the
intuition that the API is usually the quarry. Both built the same day: a closed conventional affix set — one
leading `map->`/`->`/`-`, one trailing `?`/`!`/`*`, earmuffs as a pair — is stripped candidate-side before the
exactness comparison, admitting decorated names to the exact tier one rung below exactness as spelled (a query
that spells the sigil raw-matches without stripping); and within every tier publics now rank before privates,
namespace first, source line last, the private sibling one row down and still flagged. One exception settled
during the build: a bare scope listing (`"helpers/"`) keeps whole-namespace source order — a listing, not a
ranking — a distinction the suite's empty-name-side pin surfaced before it could ship wrong. Verified live on
this JVM: `"out"` now answers `out`, then `*out*`, then the name hits.

The stress test also showed `vars` doubling as a surface linter: a macro-interned, gensym-named public factory
var surfaced in a record namespace's results — a wart the owning macro can fix with `^:private`, caught
precisely because the tool refuses to hide what is interned. Electivity remains untested — the tenant was again
pointed at the tool — but the spelling has now survived two independent first contacts.

## 2026-08-11 — The ranking confirmed in the wild; docstring promiscuity noted, unactioned

Post-restart, the fourth trial's tenant re-ran its stress cases: the decorated family tops its stem query in
sigil-exact order (`map->` stripping explicitly endorsed — "as much constructor convention as `->`"), the
public sibling leads the private, and a macro fix on the host's side landing simultaneously, the gensym'd
factory now sinks to the tail flagged `:private`. "Nothing left on my list for these two cases," plus a stated
intent to reach for `vars` over grep for any "where does X live" question. Stated intent is not observed
electivity: instructions ride the initialize response, so an existing conversation never sees a new block — a
mid-session swap would also invalidate the client's prompt cache — and this tenant predates the recipe card it
asked to have added. The clean test is now defined: a fresh conversation against the restarted host is the
first time the card and the finished ranking share a decision moment.

One tuning observation, noted and unactioned: short queries make docstring-substring matching promiscuous —
three characters were enough to hit "away" and "aware" across a namespace's docstrings. The counter-analysis,
recorded for whenever this resurfaces: the tiering contains the damage (a docstring hit can never displace a
name hit — the whole cost is tail length, the tenant's own concession); whole-word boundaries would break
inflection recall ("cancel" no longer finds "cancellation"); a left boundary alone would not have trimmed the
observed tail, since "away" and "aware" match at word starts; and a length gate is an arbitrary cliff in an
otherwise rule-clean language whose spelling for the intent already exists — more letters, or
`{:search-docstrings false}`.

## 2026-08-11 — The manual nag muted to once per session

Settled after the fleet observation: with several same-build servers reachable from one conversation, or
across an app restart, the per-response `;; manual unread` line was almost always inaccurate — the manual is
per-conversation, the read-mark per-session, and the server structurally cannot see conversation state
(sessions don't span reconnects; a mid-conversation instructions swap would bust the client's prompt cache,
so nothing else delivers late corrections either). Three findings drove the shape chosen. The repeating nag
cannot do its apparent job: it rides eval responses, so the first eval it could inform is the second — the
pre-first-eval channels are the instructions and the eval description, which already carry the directive with
the conversation-scoped conditional. Its real cost is footer credibility: the footer is where truncation,
eviction and settled news are announced, and a line that is usually wrong trains tenants to skim the channel
that must be believed. And dropping it entirely was rejected for one asymmetry: instructions delivery is
client-dependent, while the footer is in-band and always arrives — so a single first-response line is the one
server-controlled channel guaranteed to reach a genuinely fresh tenant. Hence: once per session, worded as
fact with the judgment left where the knowledge is (`new session — (__r/manual) unless this conversation
has`), burned only when the response carrying it actually goes out — a client that timed out was never
nudged, the same delivery discipline as every mark.
