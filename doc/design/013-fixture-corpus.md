# 13. Fixture corpus

The library's value is not `outline` — anyone can write that. It is the accumulated knowledge of where Clojure static
analysis lies to you. That has to be captured as **checked-in minimal fixtures**, one per pattern found in real use, not
as a dependency on any private repository. Starting set, all from this conversation:

- `($ Component)` unqualified and alias-qualified — both must resolve
- a component fetched from a route table into a local, then `$`-called — must be reported as invisible, not as absent
- a var referenced only through a keyword-keyed dispatch registry
- a `defn` whose only usages are top-level (`:sym` nil, `:top-level true`)
- a cljc file whose cljs branch references a deleted var
- namespaced vs. bare keyword usages, including destructured and schema-registered
- a lazy seq, an `eduction`, a Task and a byte array, for `shape`'s don't-touch list
