# Changelog

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
