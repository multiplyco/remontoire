# 2026-08-11 — Fourth in-situ trial: losing the decision moment, and the qualified match

The fourth in-situ session surfaced an electivity failure with unusually clean introspection attached. Asked where
a function lived, a tenant that had read the manual — `vars` docstring included — reached for grep anyway: two
guessed file paths, one nonexistent, and an eyeballed sed window. Run afterward, the `vars` call answered strictly
better: namespace, both arities with option maps, line, a private sibling flagged. The tenant's own post-mortem:
tool choice runs on recall cost at the decision moment, not on manuals read earlier — grep is zero-recall muscle
memory, an options map is not; the instructions block is the only surface reliably in context when the question
arises; and what wins there is a concrete incantation, not an abstract preference ("prefer it for var lookups" was
in context and lost anyway).

Built the same day, both halves. The query language: a `/` in the match qualifies it — `(vars "helpers/await")`,
string or symbol, scopes to namespaces matching the ns side and matches the name side within them, docstrings
included; `"helpers/"` lists a scope whole; `"/x"` degenerates to the bare search; `*` is a wildcard in every
match site; a regex containing `/` matches whole qualified names, slashless regexes unchanged; `:ns-match` is
retired. The natural spelling had been a silent `[]` — a trap worse than absence, reading as "no such var" — and
now it either works or throws (`"/"`, multi-slash; the var named `/` is reached as `'clojure.core//`, split by the
reader). The `vars` docstring grew an `Examples:` section — one call per operator, paste-ready — after the
observation that a query mini-language is better shown than described, and its first line became a capability
contrast: grep guesses paths over the repo's text; this reads the JVM.

The instructions carry the same contrast plus four intent→incantation pairs (`vars` / `outline` / `doc`+`source` /
`file`), each phrased as the question a model asks itself, with "effectively instant" retiring the observed
misprice of navigation calls as async round-trips. The bet on named helpers is structural, not stylistic: the
server exposes three tools — eval, await, cancel — so at the tool-list level "var search" does not exist as an
affordance; the instructions are the only eager surface that can give navigation a name, and a named call turns
"faster than grep" from a claim into a one-eval experiment. Cost accepted with eyes open: the block roughly
doubles (~90 → ~170 words), paid per conversation per connected server — a client reaching several Remontoire
hosts pays it once each — against the doctrine that the eager channel carries only what must be eager. The
evidence said the lean version loses the moment it exists for; whether the card flips the default is the next
in-situ trial's question.
