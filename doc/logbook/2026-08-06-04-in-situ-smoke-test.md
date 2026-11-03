# 2026-08-06 — In-situ smoke test

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
