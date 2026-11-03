# 2026-08-10 — Third in-situ smoke test: the manual restructured, and `__q` retired

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
