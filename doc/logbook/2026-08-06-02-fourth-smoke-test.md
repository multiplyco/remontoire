# 2026-08-06 — Fourth smoke test

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
