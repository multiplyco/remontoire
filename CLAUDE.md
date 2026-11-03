# Remontoire — working notes for Claude

Running, testing, the design method and releasing are in CONTRIBUTING.md, shared with human maintainers:

@CONTRIBUTING.md

## Style notes

Prefer method values for Java interop. `(Class/.method …)` over `(.method ^Class …)`.

## Releases

Henrik runs the deploy.

## Public-repo hygiene

The repo is public-bound. Docs, docstrings, comments, commit messages and examples speak in general terms —
"a Tailscale tailnet", "the application's public port" — never Multiply's own setup: no environment names, no
public port numbers, no internal app or artifact names, no first-person infra claims ("our deployment", "the
production X already uses"). Reasoning about deployment shapes is fine; using ourselves as the example is not.

## Method

- Prefer the in-process MCP eval tool (`mcp__remontoire__*`) for multi-line Clojure against the running system —
  never `clojure -M -e`.
- Henrik's shell is fish.
