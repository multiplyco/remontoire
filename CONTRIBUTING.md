# Working on Remontoire

Notes for whoever maintains this library. Using it is covered by the [README](README.md).

## Running it locally

`bb dev` starts Remontoire's MCP endpoint on `127.0.0.1:7888` and an nREPL server beside it, in a JVM running this
checkout's source (`dev/co/multiply/remontoire/dev.clj`). The repo's `.mcp.json` points Claude Code at that endpoint,
so a session opened in the repo can drive the library it is changing. Requests reach the handlers through vars, so
reloading a namespace takes effect without a restart.

## Tests and checks

- `bb test` runs the suite (kaocha). `tests.edn` sets `:capture-output? false` deliberately: the suite installs the
  real root tees (`tee/install!`) exactly as a live host does, and kaocha's capture plugin would swap the root `*out*`
  per test and displace them. Don't turn capture back on.
- `bb format:check` / `bb format:fix` (cljstyle), and `bb warn:reflection`.

Test invariants, not wording. Exact error messages, instructions and renderings drift by design — don't pin them.
Error-free behavior, retention guarantees and async semantics are the contract.

`test_util.clj` carries the rigging: state resets reach both private atoms via `@#'`, and MCP dispatch tests must
emulate the transport's delivery discipline by invoking the `::mcp/delivered` metadata mark after each response.

## Things that break subtly if forgotten

- `src/co/multiply/remontoire/api/manual.md` **ships in the jar** — `(__r/manual)` slurps it as a resource. It's also
  slurped per call, so local edits are live immediately, but jar consumers only see them at the next release. The eval
  tool description in `mcp.clj` makes overlapping claims (orphan cancellation, implicit qdo, the async model); when one
  changes, check the other. Passages that differ by client are written both ways in `manual.md`, between
  `<!-- deadline -->`, `<!-- tasks -->` and `<!-- end -->` markers.
- Orphan cancellation lands **at eval settle, not the form border** (auto-flattening). Both the manual and the eval
  description say "by the time the eval settles" — the form-border phrasing is a falsified claim that keeps wanting to
  come back.

## Design and the logbook

Design decisions are settled empirically, against a live connection. Doctrine lives in [doc/design/](doc/design/), one
file per section, and `§<n>` anywhere in the codebase or docs refers to the file numbered `<n>` there (zero-padded on
disk, so §4 is `doc/design/004-connection.md`): the leading number is the reference, the title after the dash is
decorative.

Dated records — smoke-test findings, trials, measurements, suite-falsified claims — go in
[doc/logbook/](doc/logbook/), one file per entry named `YYYY-MM-DD-NN-slug.md` (the two-digit sequence orders a day's
entries). The logbook is the record: add entries, don't rewrite history.

## Cutting a release

The version lives in **four places**, kept in sync by hand; `bb version:check` is the gate, and `bb deploy` runs it
first:

- `build.clj` — `(def version "…")`, the source of truth the check reads
- `README.md` — the `:mvn/version` coordinate
- `src/co/multiply/remontoire/mcp.clj` — server-info `:version`
- `CHANGELOG.md` — a `## <version> ` heading

`bb deploy` tags (`v<version>`, pushed) and deploys to Clojars; credentials come from `CLOJARS_DEPLOY_MAVEN_USERNAME` /
`CLOJARS_DEPLOY_MAVEN_PASSWORD`.
