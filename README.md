# Remontoire

[![Clojars Project](https://img.shields.io/clojars/v/co.multiply/remontoire.svg)](https://clojars.org/co.multiply/remontoire)
[![cljdoc](https://cljdoc.org/badge/co.multiply/remontoire)](https://cljdoc.org/d/co.multiply/remontoire)

A live MCP connection into a running Clojure system. Remontoire is loaded as a library into the JVM you want to work on
and serves MCP over HTTP from inside it — no separate server process, no REPL wire protocol in between. An agent
connects and works among the loaded namespaces and running state of the actual system: navigating and reading first —
namespaces, signatures, docstrings, source, live data — and evaluating code, where the running system answers faster and
more precisely than file search.

The agent teaches itself. The connection greets it with a short note on what Remontoire is for and one instruction — run
`(__r/manual)` before the first real eval — and the manual arrives from the running server: the conceptual model as
prose, the API reference as live data, always the truth of the build actually running. You write no prompt glue and
maintain no documentation for it; a session that skips the manual is told so on every response until it reads it.

## What the agent gets

Three MCP tools, and a library of ordinary Clojure functions called through the first of them:

- **`eval`** — namespace-aware evaluation. Every eval runs as a [Quiescent](https://github.com/multiplyco/quiescent)
  task under a registry id carried on the response: work that outlasts the call's deadline returns `running` and
  continues, and the id is a live handle — compose on it, cancel it, read its value later. Printed output is captured
  per eval and attributed across its whole task tree; while an eval runs, its latest printed line is the live progress
  message.
- **`await`** — wait for ids to settle, with a mandatory deadline.
- **`cancel`** — cancel ids, cooperatively, and report where each stands.

Inside evals, the toolkit is the `__r` namespace, aliased everywhere the agent goes: `nses`, `vars`, `outline`, `doc`,
`source` and `file` for navigation; `res`, `evals`, `out` and `failure` for the registry; `sessions`, `introduce!`
and `release!` for sharing the JVM with other connected sessions. Results you haven't read are never evicted; everything
else lives in a bounded least-recently-used pool, and eviction is always announced, never silent.

The composition language is `clojure.core` — there is no query language and no per-function option surface. The
invariant that makes it work: the elements of every result are valid inputs to every other function.

## Requirements

- JDK 21 or newer (25 recommended) — Quiescent runs evals on virtual threads.
- Clojure 1.12+ provided by the host.

Dependency hygiene is a design constraint, because the library rides along inside your JVM: the HTTP server is
`com.sun.net.httpserver` from the JDK, JSON is `data.json` (pure Clojure, no transitive deps), and optional capabilities
resolve dynamically and degrade gracefully where absent. Quiescent is the one hard dependency — a host carrying its own
resolves a single version for both.

Add it from [Clojars](https://clojars.org/co.multiply/remontoire):

```clojure
co.multiply/remontoire {:mvn/version "0.6.0"}
```

## Starting the endpoint

Remontoire is inert until started — carrying the dependency does nothing, and no configuration mentions it. Opening the
endpoint is a deliberate act, from your REPL or from wherever your system wires such things:

```clojure
(require '[co.multiply.remontoire :as remontoire])

(remontoire/init!)
;; remontoire listening on http://127.0.0.1:7888/mcp
```

`init!` is idempotent — a second call returns the running endpoint rather than binding another. Options, with their
defaults:

| Option             | Default       | Meaning                                                                                           |
|--------------------|---------------|---------------------------------------------------------------------------------------------------|
| `:port`            | `7888`        | Port for the `/mcp` endpoint.                                                                     |
| `:bind`            | `"127.0.0.1"` | Interface to bind. Keep it loopback, or a private (e.g. tailnet) address.                         |
| `:allowed-origins` | `#{}`         | Browser `Origin` values accepted. Empty refuses every browser (see below).                        |
| `:capacity`        | `20`          | Settled, delivered registry entries retained; raise if results you reach back for report evicted. |
| `:describe`        | `nil`         | The server's self-description, woven into the connect-time intro (see below).                     |

`(remontoire/stop!)` releases the endpoint; `(remontoire/status)` reports it; `(remontoire/capacity! n)` adjusts
retention mid-run.

Beside `/mcp`, the server answers `GET /probe` — a sessionless identity check for discovery tooling, health checks
and post-deploy sanity. Plain JSON, no session minted, no state touched:

```
$ curl http://127.0.0.1:7888/probe
{"server":"remontoire","version":"0.1.3","describe":"the production system behind multiply.co"}
```

`server` is the fixed discriminator to key on; `describe` is the `init!`-time self-description, `null` when unset.
Exposure and origin policy are exactly `/mcp`'s.

## Connecting Claude Code

Point an HTTP MCP entry at the endpoint:

```
claude mcp add --transport http remontoire http://127.0.0.1:7888/mcp
```

That writes the default, *local* scope — the connection is yours, for this project directory, kept in your home config.
Two other scopes matter:

- **Project scope** (`-s project`) writes `.mcp.json` in the repo root, made to be committed. This is the distribution
  vehicle: a repo that ships its own `.mcp.json` hands every collaborator the connection on clone — Claude Code asks
  once for approval (reset with `claude mcp reset-project-choices`). On a name collision, local scope wins over project.
- **User scope** (`-s user`) makes the entry global to your machine, for a server every project should see.

The connect-time intro is delivered when a conversation starts and cached for its duration — a changed `:describe`
or an upgraded server shows up in the next conversation, not mid-session.

## Naming servers

The entry name in the config is the name the model sees — it becomes the tool prefix (`mcp__remontoire__eval`) and the
"Calling remontoire…" line. The server cannot set it; the config key is the whole story.

That makes the name worth choosing deliberately when more than one system is connected: it is the model's only handle on
*which* system an eval will run in. Name entries after the project and the environment —

```
mpf-local-repl    → the JVM on your desk
mpf-prod-repl     → the one that pages you
```

`:describe` complements the name from the inside: the server states its own environment, in its own voice, as part of
the intro every connecting session reads —

```clojure
(remontoire/init! {:describe "the production system behind multiply.co"})
```

Seriousness is inferred together with the environment. A model told it is inside the production system behaves
accordingly; one told it is in a local dev JVM knows it can play.

## Security

**This is a remote-execution endpoint.** `eval` runs arbitrary code with the full privileges of your JVM — that is its
purpose. Treat the port exactly as you would treat an nREPL port, because it is the same thing wearing MCP.

The defaults are built for the presumed case — a local dev JVM, a client on the same machine:

- **Binding**: `127.0.0.1` by default. If the endpoint must be reachable remotely, bind a private interface — a tailnet
  address, not `0.0.0.0` — and let the network, not the endpoint, be what keeps the public out.
- **Origin**: browsers always send an `Origin` header and non-browser clients send none, so absence is allowed and
  presence is checked against `:allowed-origins` — which defaults to empty, refusing every browser. Remontoire never
  emits permissive CORS headers; do not add them to make a browser test pass.
- **No authentication is built in.** The trust boundary is the network: loopback, or a private network whose membership
  *is* the authentication.

Wiring Remontoire into a deployed system is a deliberate act, and its consequences belong to whoever performs it.
Nothing here makes a production REPL safe to expose; the design keeps it inert until someone with the authority to open
it does so, on a network arranged so that only the right people can reach it.

## Reading the manual yourself

Everything the agent is taught, you can read too: call `(co.multiply.remontoire.api/manual)` from your own REPL —
in-process it marks no session and simply prints the prose and returns the API outline — or read
[`api/manual.md`](src/co/multiply/remontoire/api/manual.md) and the docstrings of
[`api.clj`](src/co/multiply/remontoire/api.clj) directly.

## Design

Every decision in this library was settled empirically against a live connection and recorded with its evidence in
[doc/design/](doc/design/) — one file per section: binding and origin policy in §4, retention in §5, the eval model
in §9, sessions and output attribution in §11. The dated record of the smoke tests and trials behind them is
[doc/logbook.md](doc/logbook.md). When behavior surprises you, the design doc says why it is the way it is.
