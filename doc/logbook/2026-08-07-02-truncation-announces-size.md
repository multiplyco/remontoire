# 2026-08-07 — Truncation announces its size

**Built (2026-08-07) — item (3) from the in-situ wishlist, the interim form.** The value renderer's caps stay as they
are; what changed is the silence: a cut rendering now carries a footer line — `;; truncated — 9872 elided ·
(res 8) is the whole value` — produced by a mirror of the printer's own walk (counted collections counted exactly for
free, an uncounted seq probed one already-realized cell past the cap, below-`*print-level*` subtrees reported hidden,
not sized). The inline `... n more` rendering the wishlist sketched was considered and declined: it needs a custom
bounded printer, which is precisely the machinery §6's `shape` will retire the cap with — the footer line joins the
eviction announcement instead, same doctrine, and nothing gets thrown away when `shape` lands. Verified live through
the MCP connection: `(vec (range 10000))` → "9872 elided" (the wishlist's own example number), `(take 200 (range))`
→ "uncounted elision" with `(count @(__r/res 9))` → 200 behind it. One trap re-confirmed while testing: an eval
returning an *infinite* seq never settles — deep grounding walks the return value — so the infinite case cannot
reach the renderer at all; the honest uncounted case is a finite lazy seq. Suite green: 72 tests, 258 assertions.

The trap got its teaching line the same day: the manual's grounding paragraph and the eval description now both say
it — grounding walks the whole of each form's value, so a returned infinite seq never settles; work with them freely
(`(def nat-ints (range))` returns the var, `(take 5 nat-ints)` a bounded value), return them bounded. Verified live
before writing: the def-and-take shape settles cleanly through the connection.
