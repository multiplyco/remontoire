# 6. `shape`

A **report**, not a manipulation. It never realizes, never forces, never recurses into anything that could block, throw
or have effects.

The rule is not a compute budget — this is one-off REPL work, where 5s of local compute beats a context-filling round
trip every time. The rule is: **touch nothing that could block, throw, or have effects; otherwise scan everything.**

- Vectors and maps are never lazy → scan them completely. No sampling, no
  `:full` flag, and therefore no confidence hedging: `keys {:sid :uri :mime}` is a *fact*.
- Lazy seqs, `eduction`, bare `Iterable`, channels, Quiescent `Task`s: **name them, don't touch them.** `LazySeq` can
  report `realized?` on its head for free. To look inside, the caller composes: `(shape (vec (take 3 …)))` — the
  decision to realize is explicit and belongs to the caller.
- Opaque objects — nippy byte arrays, `RocksDBWrapper`, `MachineLatch` — get their class and size named, never their
  contents walked. (Rama subindexed reads hand back unserializable wrappers; walking one can block or throw.)

**Report in the vocabulary of the predicates used to narrow.** The test for every candidate field is: *does knowing this
change what I type next?* Leaf class names (`java.lang.Long`) fail it. Nil presence passes it, prominently.

```
(shape (res 12))
;; vector 4  ⚠ mixed types — not sortable
;;   number?   2
;;   string?   1
;;   keyword?  1

(shape (res 7))
;; vector 17323
;;   map? — keys {:sid :uri :mime :parsed :tokens}
;;     :mime    string?  #{"application/pdf" "text/html" "text/markdown"}
;;     :tokens  number?  nil in 412
;;     :parsed  byte[]   ~180 KB each
```

- **Predicate names verbatim**, so the output *is* the code. Always the most specific predicate covering the whole
  collection — `number?` not `int?` if there are doubles, or the caller writes a filter that silently drops half.
- **Counts alongside**, so the caller knows whether a `filter` returns 2 things or 20,000 — i.e. whether to print it or
  `shape` it again.
- **For maps, keysets replace predicates**, and for low-cardinality values, the **value set itself** — that is what lets
  a filter be written without a second round trip. Stop enumerating distinct values at ~12.
- **Nil presence called out separately**, never folded into the type list. It is what breaks the follow-up expression.
- **Mixed-type collections flag "not sortable"** — `sort` on `[1 2 "hello" :world]`
  throws, and that is exactly a follow-up written blind.
- **Homogeneous collections collapse to one line**: `vector 100000 — number?`. If generality taxes the common case it
  loses on net.
- **Memoize the digest on the registry entry.** The value is pinned and immutable, so `shape` → `schema` → a filter
  costs one scan.

**Do not emit suggested expressions.** "Try `(filter number? (res 12))`" guesses intent, bloats output, and is usually
wrong.

**Do not aim for a schema by default.** A schema must account for every element, so its complexity tracks the data's
irregularity. What is wanted is a *navigational* description. Where an actual schema is the goal (writing a spec),
`malli.provider` already does it and already samples — **verified** present in malli 0.20.1, already on
fluent-platform's classpath. Resolve it dynamically; don't take the dependency.

|              |                                 | use                                |
|--------------|---------------------------------|------------------------------------|
| `(shape x)`  | full scan of realized structure | default rendering for large values |
| `(schema x)` | `malli.provider`                | opt-in, when writing a spec        |

**When a value exceeds the print budget, emit its shape plus the id — never
`…`-truncated data.** Truncated data shows neither structure nor content; shape plus a handle shows the structure *and*
tells the caller what to filter on.

Interim, built 2026-08-07 (wishlist item 3 from the first in-situ smoke test): until `shape` replaces the dumb cap, the
cap announces itself — the response footer counts what the caps hid (`;; truncated — 9872 elided · (res 8) is the
whole value`) via a mirror of the printer's own walk: counted collections answer exactly and for free, an uncounted seq is
probed one already-realized cell past the cap, a subtree below `*print-level*` is reported hidden rather than sized —
so the mirror can neither block nor force anything the printer didn't. Deliberately a footer annotation, not an inline
`... n more` rendering: inline requires a custom printer, which is exactly the machinery `shape` retires this cap with.

