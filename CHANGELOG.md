# Changelog

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
