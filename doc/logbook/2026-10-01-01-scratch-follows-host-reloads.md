# 2026-10-01 — Scratch follows host reloads; Quiescent 0.11.5

A host-side report: after a sweeping reload (clj-reload), a scratch that had aliased one of the reloaded namespaces
built an instance of the replaced generation of its `deftype`, and the host's serializer, casting to the live class,
failed with `X cannot be cast to X`. Re-requiring in a fresh scratch fixed it. The reporting conversation proposed
re-pointing scratch mappings at eval start. These claims were checked here (Clojure 1.12.4, clj-reload 1.0.0) before
anything was adopted:

- `alias` refuses to point an existing alias at a new namespace object, confirmed. `.refer` over a stale var from
  another namespace replaces it with a `WARNING` on `*err*`, confirmed. `.importClass` replaces a same-named class
  silently, which settles the proposal's open question.
- *Falsified:* `*loaded-libs*` as a "finished loading" signal. clj-reload loads through `Compiler/load` directly, and
  the only re-add is the `ns` macro at the top of the file: membership marks the start of a load. The rule
  ("re-point only to a finished namespace") was dropped rather than re-signalled. The object is what matters: the
  successor is the namespace the reload fills, and what it lacks so far, qualified symbols lack too.
- `RT/classForName` consults Clojure's class cache only when the loader is not a `DynamicClassLoader`. Quiescent
  0.11.5 fixes this for its own executor threads. A fresh `DynamicClassLoader` answers with the newest generation from
  any thread, measured from a thread whose context loader had defined the old one. The check uses that.

*The stale surface is narrower than proposed, and found by accident.* The first draft of the suite asserted that
`(rl/f)` read live after re-pointing, and the host-untouched test failed: an un-rewired host namespace read live
too. Compiled code emits `RT.var("ns", "name")` and class lookups by name, resolved when its class loads, so a stale
mapping is corrected for anything Clojure compiles. Against a host namespace with stale alias, refer and import:

| form                                                              | generation read |
|-------------------------------------------------------------------|-----------------|
| `(rl/f)`, `(f)`, `(let [x (rl/f)] x)`, `(rl/->T 1)`, `(T. 1)`     | new             |
| `(identity T)`, `((fn [] (rl/f)))`, `(defn h [] (rl/f))`          | new             |
| `(def a (rl/f))`, `(def c (f))`, `(def j (rl/->T 1))`             | old             |
| `(def k (T. 1))`, `T`, `(do T)`, `(def g T)`                      | old             |
| `(rl/m)`, `(m)`: macros                                           | old             |

Interpreted evaluation (a simple `def` init, a bare symbol) and macro expansion use the analysis-time objects, and
`(def x (alias/->T …))` is the incident's likely shape. So the suite observes re-pointing through `def`s, a bare
class symbol and a macro. Run with re-pointing disabled, it fails every assertion that depends on it.

Atomicity measured: with `removeAlias` + `addAlias`, a resolver racing a re-pointing loop missed the alias about 300
times in 200 000 lookups, over three runs. With the table swap it missed none. Cost of the per-form check on a
furnished scratch (785 mappings), with nothing stale: 42 µs as first written, 7 µs after the walk moved from `keep`
with destructuring (25 µs on its own) to `reduce-kv`, and from memoized `find-ns` to skipping the last home found
current. For scale, evaluating `(+ 1 2)` takes 70 µs and the per-eval furnishing 290 µs.

Declined: the public `rewire!` both the proposal and the original request suggested. Once re-pointing runs on every
form, a hook adds a call each host must remember to wire, and nothing else. Landed with Quiescent bumped to 0.11.5
in the same release.
