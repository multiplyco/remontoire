# 1. Premise

Two problems with the current arrangement (a CLI eval tool plus plain-text search):

**Input mangling.** Code reaches a CLI tool through shell quoting, so it passes through two layers of escaping that must
be hand-authored — fish quoting rules plus the tool's own argument parsing. Backslashes, `$`, and `\uXXXX` escapes get
eaten. A tool call whose argument is a single JSON string removes the shell layer entirely; the remaining layer is
serialized by the harness, not hand-written.

**Unstructured output, and too much of it.** A CLI returns one interleaved, ANSI-colorized stream: value, stdout, and
exception all mixed, silently truncated. Meanwhile plain-text search cannot distinguish a definition from a usage,
cannot resolve an alias, and cannot tell two same-named vars apart — which in a repo with one `query.cljc` and one
`impl.clj` per module is most of the work.

The deeper cost is **context**. Chatty tooling — fetch a list, then N calls to enrich each element — is what actually
fills a context window. A single call that reduces server-side and returns only the answer is worth an order of
magnitude more than a faster one.

