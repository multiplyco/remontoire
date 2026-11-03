# 2026-08-11 — Fifth in-situ trial: the qualified match in the wild, and a conceptual-search wish

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
