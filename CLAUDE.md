# Remontoire — working notes for Claude

## Style notes

Prefer method values for Java interop. `(Class/.method …)` over `(.method ^Class …)`.

## Cutting a release

The version lives in **four places**, kept in sync by hand; `bb version:check` is the gate (and `bb deploy`
runs it first):

- `build.clj` — `(def version "…")`, the source of truth the check reads
- `README.md` — the `:mvn/version` coordinate
- `src/co/multiply/remontoire/mcp.clj` — server-info `:version`
- `CHANGELOG.md` — a `## <version> ` heading

`bb deploy` tags (`v<version>`, pushed) and deploys to Clojars; credentials come from
`CLOJARS_DEPLOY_MAVEN_USERNAME` / `CLOJARS_DEPLOY_MAVEN_PASSWORD`. Henrik runs the deploy.

## Testing

`bb test` (kaocha). `tests.edn` runs with `:capture-output? false` deliberately: the suite installs the real
root tees (`tee/install!`) exactly as a live host does, and kaocha's capture plugin would swap the root `*out*`
per test and displace them. Don't turn capture back on.

Test philosophy: invariants over wording. Exact error messages, instructions, and renderings drift by design —
don't pin them. Error-free behavior, retention guarantees, and async semantics are the contract.

`test_util.clj` carries the rigging: state resets reach both private atoms via `@#'`, and MCP dispatch tests
must emulate the transport's delivery discipline by invoking the `::mcp/delivered` metadata mark after each
response.

## Things that break subtly if forgotten

- `src/co/multiply/remontoire/api/manual.md` **ships in the jar** — `(__r/manual)` slurps it as a resource.
  It's also slurped per call, so local edits are live immediately, but jar consumers only see them at the next
  release. The eval tool description in `mcp.clj` makes overlapping claims (orphan cancellation, implicit qdo);
  when one changes, check the other.
- Orphan cancellation lands **at eval settle, not the form border** (auto-flattening). Both the manual and the
  eval description say "by the time the eval settles" — the form-border phrasing is a falsified claim that keeps
  wanting to come back.

## Public-repo hygiene

The repo is public-bound. Docs, docstrings, comments, commit messages and examples speak in general terms —
"a Tailscale tailnet", "the application's public port" — never Multiply's own setup: no environment names, no
public port numbers, no internal app or artifact names, no first-person infra claims ("our deployment", "the
production X already uses"). Reasoning about deployment shapes is fine; using ourselves as the example is not.

## Method

- Design decisions are settled empirically. Doctrine is folded into `doc/design/` — one file per section, and
  `§<n>` anywhere in the codebase or docs refers to the file numbered `<n>` there (zero-padded on disk, so §4 is
  `doc/design/004-connection.md`): the leading number is the reference, the title after the dash is decorative. Dated records — smoke-test findings, trials, measurements,
  suite-falsified claims — are appended to `doc/logbook.md`, oldest first. The logbook is the record; keep
  appending, don't rewrite history.
- Prefer the in-process MCP eval tool (`mcp__remontoire__*`) for multi-line Clojure against the running system —
  never `clojure -M -e`.
- Henrik's shell is fish.
