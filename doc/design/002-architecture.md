# 2. Architecture: one tool, many functions

**One MCP tool** — a REPL connection. Everything else is a **library of ordinary Clojure functions** called through it.
(Amended 2026-08-01: one tool plus `await` — §9. The test for tool-hood is whether correctness depends on a mapping only
the control layer holds; `await` over several entries passes it. Amended 2026-08-04: plus `cancel`, which passes a
different test — the act belongs to the control layer's own lifecycle, not the mapping; §9.)

Why this shape rather than N MCP tools:

- **Reduction happens before data enters the context.** `(->> (nav/usages 'x) (map :ns) distinct sort)`
  is one round trip; the equivalent as discrete tools is `1 + N` calls, each paying tokens for intermediates that get
  discarded.
- **The tool-schema budget collapses to one.** Tool selection becomes code authoring, which is a more reliable operation
  than picking among similarly named tools.
- **Discoverability is in-band and better than schemas.** `(doc nav/usages)`
  plus the source beats a JSON schema, and cannot go stale relative to the code.
- **It gets a test suite.** An MCP server's behaviour is observable only through the agent using it, so its blind spots
  stay folklore. A library turns each blind spot into a fixture with expected output.
- **Two feedback loops, one of them instant.** The tool is the only thing whose change requires a restart. The library
  is reloadable Clojure written at the REPL it serves.

The library must not require the host project's runtime — pure static analysis and file reading, so it still works when
the system under inspection is broken, which is when it is most needed.

