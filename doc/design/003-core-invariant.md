# 3. The core invariant

**The elements of every result must be valid inputs to every other function.**
Fully-qualified symbols are the universal currency; more precisely, every thing is identified by the value a Clojure
reader would produce for it — symbols for namespaces and vars, keywords for keywords, strings for files. Never a wrapper
map, never an opaque handle.

Consequences:

- **Results stream self-describing elements, flat.** Not `{ns → [entries]}` — that breaks `mapcat` at the first hop.
  Each entry carries its own `:sym`,
  `:file`, `:row`.
- **`:sym` is a symbol, not `:name`/`:ns` strings.** String pairs force reassembly at every hop; this is the most common
  way a library like this fails to compose.
- **The container type is incidental.** Eduction, lazy seq, vector — irrelevant. What matters is the element type.
  Pipelines land on `into []`, which is also what makes the result something `shape` will look at.
- **Uniformity holds *across* functions, not just within one.** Minimizing the number of distinct element types matters
  more than making each maximally informative. Two cover nearly everything:

```clojure
;; entry — a top-level form
{:sym  'co.multiply.module.driver.impl/run-crawl-one
 :kind :defn :arglists '([ctx tool])
 :file "…/impl.clj" :row 412 :end-row 449 :doc "…"}

;; site — a reference to something
{:sym  'co.multiply.module.driver.impl/run-one   ; nil at top level
 :file "…/impl.clj" :row 517 :col 23 :top-level false}
```

| function                   | streams                |
|----------------------------|------------------------|
| `namespaces`               | ns symbols             |
| `outline`, `changed-forms` | entry maps             |
| `form`                     | entry maps + `:source` |
| `usages`, `kw-usages`      | site maps              |
| `rename`                   | edit maps              |
| `trace`                    | node maps              |

**No options where data will do.** Every option is a sign the return value was not data-shaped enough:

- `:context 1` (neighbouring forms) → `outline` returns an *ordered* vector; neighbour selection is `clojure.core`.
- `:tail 50`, regex, grep-context on stdout → `out` returns a vector of
  `[ts thread line]`; tail is `take-last`, grep is `filter`, context is `partition`.
- `:full true` on `shape` → see §6; the types already make the distinction.

**No query DSL.** Tools in this space reliably grow one — glob patterns, selector syntax, `--filter` flags. Transducers
over plain data make it unnecessary. That is a large avoided cost.

**Nothing in the library returns prose.** `form` streams entries with `:source`
attached; a separate small printer renders. Rendering lives at exactly one boundary per pipeline.

The one knob that survives is **how much of a result crosses into the agent's context** — not expressible as
composition, because it is a property of the transport. It belongs to the eval tool, not to any function.

