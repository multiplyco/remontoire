# Changelog

## 0.7.3 - 2026-09-11

- Update Quiescent to 0.11.3.

## 0.7.2 - 2026-09-10

- Update Quiescent to 0.11.2, bringing its gate fixes and retaining Scoped 0.1.17's lazy `ask` defaults.
  Rely on Quiescent's transitive Scoped dependency instead of maintaining a separate version pin.

## 0.7.1 - 2026-09-10

- Update Scoped to 0.1.17: `(ask *var* default)` evaluates the default expression only when needed.

## 0.7.0 - 2026-08-14

- Quiescent 0.10.0: gates leave the cancellation tree. A gate is now a pure coordinator, like
  `semaphore` — off the tree, immortal, `(q/cancel gate)` a type error — so a top-level
  `(def gate (q/gate n))` re-created through an eval-driven reload no longer dies with the eval, and
  the silent-dead-gate mode (`gate-task` on a cancelled gate returning an already-cancelled task whose
  `then`/`catch`/`handle` never ran) is no longer representable. Gated tasks are unchanged: structured
  under their creator, so cancelling an eval still tears down exactly the work it enqueued — running or
  queued — while the gate, and other work sharing it, run on.
- The manual teaches the def boundary: orphanhood is decided by the dynamic extent at creation — `def`
  lifts nothing out of the tree, and any load path an eval drives (`require :reload`, a sweeping
  reloader) re-evaluates top-level forms inside the eval, so the tasks those defs re-create die with
  the call. Compel at creation or capture the settled value for tasks; coordinators are exempt by
  design. The `gated` recipe's docstring now attributes its cancel-the-eval guarantee to the tasks'
  own parenthood, where it always lived.

## 0.6.0 - 2026-08-13

- Scratch compartments: `(__r/scratch! "kbtest")` switches to a named scratch of the session's own,
  nested under its main scratch — `…scratch.s<tag>.kbtest` — so uniqueness across sessions comes from
  the session tag and the label stays what the caller chose. Minted and wired like scratch itself on
  first use (`q` and the `q*` macros, `res`/`evals`, `__r`); within a session the same label is the
  same compartment thereafter — the label is the compartment's identity, so re-entry after a lost
  context is the same call as creation. The switch is a real `in-ns` — it sticks across calls — and
  announces itself on the eval's output. `(__r/scratch!)` returns to the main scratch,
  `(__r/scratches)` lists the family (the namespace tree is the tracking; no ledger), and
  `(__r/retire-scratch! "kbtest")` removes a compartment — retiring the ground you stand on lands you
  back in the main scratch first, and neither the main scratch nor another session's compartment is
  retirable. Compartments die with the session, like all conversational state.

## 0.5.1 - 2026-08-11

- The manual note appears once per session — on the first eval response, then never again — and its
  wording turned from imperative to fact: `;; new session — (__r/manual) unless this conversation has`.
  The server knows only session truth: a conversation may have read the manual through another server of
  the same build, or before a restart, so a repeating note is usually wrong exactly where fleets and
  restarts are the deployment shape — and a footer that cries wolf spends the credibility evictions and
  truncations are announced on. The note is burned only when its response actually goes out, the same
  discipline as every delivery mark: a client that timed out was never nudged.

## 0.5.0 - 2026-08-11

- `vars` exactness reads through conventional decoration: `claim?`, `->Claim` and `*claim*` are exact
  matches for "claim". The affix set is closed idiom — one leading `map->`/`->`/`-`, one trailing
  `?`/`!`/`*`, earmuffs as a pair — and exactness as spelled still leads the tier: `claim` itself, or a
  query spelling the sigil, ranks above the decorated family.
- Within each tier, publics rank before privates — the API is usually the quarry, and the private sibling
  follows one row down, still flagged. A bare scope listing (`"helpers/"`) keeps whole-namespace source
  order: a listing, not a ranking.

## 0.4.0 - 2026-08-11

- **Breaking: `vars` speaks qualified matches; `:ns-match` is gone.** A `/` in the match qualifies it,
  spelling the search the way Clojure spells the target: `(vars "helpers/await")` — string or symbol —
  scopes to namespaces whose *name* matches the ns side (docstrings not consulted, as `:ns-match` scoped)
  and matches the name side within them, docstrings included as ever. Either side may be empty:
  `"helpers/"` lists a scope whole, `"/await"` degenerates to the bare search. `"/"` and strings with more
  than one `/` throw — the var named `/` is reached as `'clojure.core//`, split by the reader. A regex
  containing `/` matches whole qualified names — the one spelling that mixes forms — where a slashless
  regex matches bare names, unchanged. The qualified spelling was previously a silent `[]`: the natural
  first query, answered as "no such var".
- `vars` takes `{:search-docstrings false}` — `nses`' word — restricting the name side to names alone.
- `*` in a string or symbol match is a wildcard matching any run, in `nses`, `vars` and `outline`'s
  `:match` alike — `"q*/await"`, `"co.*.helpers/"`. A starless match stays a plain substring; a literal
  `*` (earmuffed names) is a regex away: `#"\*out\*"`.

## 0.3.0 - 2026-08-11

- **Breaking: `file` returns the vector of lines**, like `out` before it — composition is the window. The
  1-indexed `:line` an `outline` or `vars` entry carries indexes it directly: `(drop (dec line) (file ns))`
  starts reading at the var, which is precisely the drilldown models were observed reconstructing by hand
  from the string. String consumers `str/join` with a newline.
- The api docstrings move to a terse contract template: an `Accepts:` section with exact match and option
  semantics (absent on nullary functions — absence is the clearer signal), and an `Output shape:` section
  always present, even when trivial, so a caller can compose on a result — or slice it into the first
  call — without a probe eval to learn its keys. Special capabilities stay with the docstring; general
  composition advice lives in the manual; adjacent functions cross-reference instead of repeating.
- `vars` takes `{:ns-match m}` — the forms `match` takes — scoping the search to namespaces whose *name*
  matches: a docstring mention is no reason to widen a scope. An unmatched scope is an empty answer, not
  the whole system.
- `nses` takes `{:search-docstrings false}`, restricting the search to names — the docstring tier
  vanishes; it is the name-only form `:ns-match` scopes through.
- `outline`'s `:match` takes a symbol, matched as its name — the capacity `nses` and `vars` already had,
  now defined once in the shared matcher, so all three agree.

## 0.2.0 - 2026-08-10

- **Breaking: the `__q` alias is retired.** Scratch namespaces are pre-wired with Quiescent exactly as committed
  source spells it — the `q` alias plus the bare-referred macros (`q`, `qdo`, `qlet`, `qfor`, …) — and nothing but
  `__r` follows an eval into other namespaces, so idioms copy verbatim between evals, the Quiescent skill and source
  files. Eval code written against `__q/…` becomes `q/…` in scratch; in any other namespace, aliasing Quiescent is
  the model's own act — and `__q` is the collision-proof spelling for a namespace you don't own, since requiring
  into it rewires its alias map. The upside of the loud failure: `q/task` in a foreign namespace now either means
  what the host's own alias says, or fails self-diagnosingly, instead of silently meaning something injected.
- `nses` ranks the way `vars` ranks, adapted to dotted names: names with a whole dot-segment matching exactly
  first — "outline" puts `x.outline` above `x.outline-test` — then remaining name hits, then docstring-only hits,
  alphabetical within each tier. Namespace docstrings are searched for the first time; the result stays a flat
  vector of symbols, so a docstring hit carries no quoted evidence — `outline` the namespace to see it.
- `outline` takes `:include-private`: the glance keeps privates by default, `{:include-private false}` keeps the
  public surface alone, for when the API is exactly the question.
- The manual restructured principles-first — the conceptual model and frame of mind before tools and specifics —
  and trimmed: mechanics the API docstrings already carry are no longer restated, so the prose half holds the
  contracts, doctrine and silent-failure modes alone.

## 0.1.3 - 2026-08-07

- `__r/vars` — global var search, the top-ranked wish from the first in-situ smoke test: every loaded var whose name
  or docstring matches, as outline entries with `:ns` attached — scored, so vars named exactly what you asked for
  lead, then name hits, then docstring-only mentions, in namespace and source order within each tier; docstring hits
  quote their matching lines as `:matched`. Takes what `nses` takes — a string (case-insensitive substring), a regex,
  or a symbol matched as its name — so "where does that function live?" no longer takes guessing namespaces.
- Truncation announces its size: when the value renderer's caps cut a result, the response footer says how much was
  elided — exactly for counted collections (`;; truncated — 9872 elided`), a floor when a lazy tail keeps the total
  unknowable — and points at `(res id)` for the whole value. Same doctrine as eviction: announced, never silent.
- The manual and the eval description name the infinite-lazy-return trap: grounding walks each form's whole value, so
  a returned infinite seq never settles. Infinite seqs are fine to work with — `(def nat-ints (range))` returns the
  var, `(take 5 nat-ints)` a bounded value; it is only the returned value that cannot be unbounded.
- `__r/out` returns the printed output as a vector of lines — the pending unterminated line last (it is the progress
  line), chars the cap dropped announced as the first element. Reading back is composing, per the core invariant:
  `(take-last 5 (out id))` peeks at a chatty eval, `drop-last` then `take-last` pages further back, `filter`
  searches, and `(run! println (out id))` is the verbatim reading form. Underneath, output banks retain completed
  lines rather than one string, so the cap trims whole entries oldest-first and a chatty eval no longer pays a
  full-bank copy per write.

## 0.1.2 - 2026-08-06

- `GET /probe` — a sessionless identity endpoint beside `/mcp`, for discovery tooling, health checks and post-deploy
  sanity: plain JSON (`{"server": "remontoire", "version": …, "describe": …}`), no session minted, no registry or
  session state touched. `server` is the fixed discriminator to key on; `describe` is the `init!`-time
  self-description, `null` when unset; more fields may accrete — consumers should key on field presence. Same
  exposure and origin policy as `/mcp`.

## 0.1.1 - 2026-08-06

Documentation amendments from the first in-situ smoke test (a real 1900-namespace JVM, the jar from Clojars).

- The `eval` tool description states last-value-only up front — batch independent probes in a vector or labeled
  map — and its orphan-cancellation sentence now matches the manual: cancelled by the time the eval settles, not at
  the form border.
- The manual's async section names deep grounding as the idiomatic single-eval fan-out: a returned vector of tasks
  runs in parallel and the eval waits for all.

## 0.1.0 - 2026-08-06

Initial release.

- MCP over HTTP served from inside the host JVM (`com.sun.net.httpserver`, Streamable HTTP) — no separate server
  process, no REPL wire protocol. Inert until `init!`; loopback binding, Origin validation, and no CORS by default.
- Three tools: `eval` — namespace-aware, every eval a Quiescent task under a registry id, with per-eval output
  capture attributed across the whole task tree and the latest printed line as live progress; `await` — deadline-bound
  waiting on ids; `cancel` — cooperative cancellation with a settled report.
- The `__r` API, aliased into every namespace evals touch: `nses`, `outline`, `doc`, `source`, `file` for navigation;
  `res`, `evals`, `out`, `failure` for the registry; `sessions`, `introduce!`, `release!` for sharing the JVM;
  `manual` — the self-teaching surface, prose printed and the API reference returned as live data.
- Retention with guarantees: running work and unread results are never evicted; settled, delivered entries live in a
  bounded LRU pool, and eviction is always announced in a response footer.
- Sessions with scratch namespaces pre-wired with Quiescent (`__q`), a shared registry across sessions with `:by`
  attribution, and a connect-time intro the server composes about itself (`:describe`).
