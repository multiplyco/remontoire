# 8. Refactoring

The valuable edit primitive is **rename with propagation**, not structural editing. Ordinary text editing plus
paren-balancing already covers the rest, and sexp-matching edits fail on formatting drift.

**Verified**: `clojure-lsp` 2026.05.05 is installed and its CLI already provides
`rename --from … --to …`, `references --from …`, `--dry` (reports diffs, changes nothing), and `--filenames` /
`--namespace` / `--ns-exclude-regex` scoping. So
`references` is the cross-reference tool, already built.

**Verified limitation**: CLI rename is **vars and namespaces only**.
`rename --from :node/content` returns "Symbol :node/content not found in project".

Four classes rename structurally cannot reach, all live in fluent-platform:

1. **Symbol-resolved dispatch.** Its own CLAUDE.md warns that a task namespace must be required in
   `gen.interface.daemon` or you get `:action does not
   exist` — a keyword-keyed registry that a var rename leaves untouched.
2. **Non-Clojure artifacts** — `bb.edn`, `tests.edn`, `deps.edn` `:main-opts`, Kamal and Terraform configs.
3. **Docstrings and comments** — correctly untouched, so prose goes stale silently.
4. **cljc/cljs branches** — dead cljs refs pass kondo *and* the frontend CI suite; only a full ClojureScript release
   build catches them.

**The protocol**: `--dry` first, read the diff, apply — then grep the old name across the whole repo including
non-Clojure files as a **residue check**. Text search's over-matching is a liability for finding usages and an asset for
verifying zero remain: every surviving hit is either stale prose or a real dynamic reference. That single step is what
makes the result trustworthy without manual review.

`rename` streams edit maps; `rename!` consumes them. Reviewing before applying is then the default because it is the
only shape the data comes in — and edits can be filtered before applying. The safety property is a consequence of the
data discipline rather than a rule to remember.

## Keywords

**Verified** keyword census in `fluent-platform/src`:

| kind                                                   | count            | renameable     |
|--------------------------------------------------------|------------------|----------------|
| namespaced (`:node/content`, `:model/id`, `:nav/main`) | 709 distinct     | yes, precisely |
| auto-resolved (`::foo`)                                | 106 distinct     | yes            |
| fully-qualified (`:co.multiply.*/kw`)                  | **0**            | n/a            |
| bare `:title` alone                                    | 1214 occurrences | no             |

The top tier is where the domain vocabulary lives and is a tractable script over kondo's `keyword-usages` (which records
destructuring positions and `:reg` for schema registrations) even though the LSP CLI will not do it. **That is the piece
worth building.**

For bare keywords, don't build a renamer. It is not a resolution problem but a *type* problem — which map's `:title`?
Nothing static answers that. Two things help: a **census report** grouping occurrences by namespace and role
(destructure / `get` / literal map key / schema key), which converts an un-scopable edit into a reviewable list; and
using the module's Malli schema as the type-checker — rename there first, run the suite, let failures enumerate the
sites.

