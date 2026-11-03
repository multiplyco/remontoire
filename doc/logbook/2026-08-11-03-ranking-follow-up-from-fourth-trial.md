# 2026-08-11 — Ranking follow-up from the fourth trial's tenant: sigil-aware exactness, publics first

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
