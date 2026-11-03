# 2026-08-07 — `vars`: global var search

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
