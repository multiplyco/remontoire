# 2026-08-05 — The manual rides the instructions block

**Superseded the same day by the final form: the manual rides the instructions block itself.** The trials taught
a hierarchy — options get stripped, instructions get followed, ambient context gets absorbed — so the manual
moved to the top tier: `render-manual` in `mcp` renders the API layer's public vars (signature plus live
docstring, markdown, not `pr-str` — it is an instruction manual now, not a return value) and the `instructions`
def appends it to the prose half at load. ~1.55 k tokens all told, zero roundtrips, zero drift by construction,
and — the sleeper argument — a system prompt survives the conversation compaction that would eventually swallow a
fetched manual. Publics only; privates stay readable through a real outline. With the delivery job gone, `outline`
reverted to the signatures-only glance and the bootstrap imperative left the instructions — replaced by \"its
manual follows at the end of these instructions\"; the eval description keeps the full-incantation pointer as the
fallback for clients that ignore `instructions` entirely. Wire-verified: initialize serves prose + ten-entry
manual, 6.2 k chars. Trial four (fresh tenant, manual-bearing block): it operated `introduce!` and `sessions`
correctly — arities, composition, timing — *before any introspection*, and since the description slimming the
block is the only place those functions are taught: knowledge at birth, where trial one had to outline to learn
the same surface. Its later `(outline '__r)` was the *bare* form with \"checking the toolkit\" narration —
feature-under-test in a smoke test, not re-orientation; a model already holding the docstrings has no reason to
re-order the manual, and the signatures-only ping is what \"does outline work?\" costs. Its full battery surfaced
the first field-driven manual tuning: it concluded \"the API doesn't offer programmatic access to exception data\"
— false (`@(q/catch (res id) identity)` then `ex-data`, verified live) but instructively so, since `failure`'s own
docstring never pointed across to the composition; and it flagged `doc` as possibly not printing, when its bare
symbol simply didn't resolve in the `user` namespace it had navigated to — `clojure.repl/doc` prints nothing for
an unresolvable name. Both closed as docstring amendments, and the manual re-rendered itself on reload: the
single-source loop working as designed.
