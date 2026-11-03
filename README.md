# Remontoire

[![Clojars Project](https://img.shields.io/clojars/v/co.multiply/remontoire.svg)](https://clojars.org/co.multiply/remontoire)
[![cljdoc](https://cljdoc.org/badge/co.multiply/remontoire)](https://cljdoc.org/d/co.multiply/remontoire)

A live MCP connection into a running Clojure system. Remontoire is loaded as a library into the JVM you want to work on
and serves MCP over HTTP from inside it. A coding agent can explore loaded namespaces, read docstrings and source,
inspect live data, and evaluate code in the actual system. Every eval can run in the background, leaving the connection
available for further work even when a call takes longer than expected.

Remontoire is a small library that works alongside the agent's other tools. The agent can edit files, use the shell,
and run builds as usual, then use Remontoire wherever interacting with the running system helps answer the question.
It also works when the agent has no local checkout of the application.

## Why use Remontoire?

### Keep working while an eval runs

An innocent-looking call can turn into a long wait, a retry loop, or work whose duration the agent did not anticipate.
Remontoire treats every eval as potentially long-running: each has an id, captured output, and a retained result.
When an eval outlasts the call's wait, control returns while the work continues. The agent can keep evaluating,
inspect the running work, or request its cancellation, without restarting the application just to regain REPL control.

This also makes deliberately long-running work useful. An agent can start a polling loop, read its latest output
from another eval, investigate what it sees, and cancel the loop when finished. For work expected to complete, the
agent can queue follow-up work against its running eval handle: a report can wait on an analysis already in progress
and start when its result is ready. The agent can arrange those dependencies up front and continue working elsewhere.

The evaluated code can be ordinary synchronous Clojure. [Quiescent](https://github.com/multiplyco/quiescent) manages
the eval as a task and propagates cancellation through its child tasks. Cancellation is cooperative: code that ignores
interruption can keep running, and cancelling an eval does not roll back side effects. The guarantee is that a long
eval does not monopolize the REPL connection.

### Ask the system that is actually running

The agent can find namespaces, functions, and macros that are loaded in the connected JVM, including those from
dependency jars. Searches use names and docstrings; namespace outlines show signatures, private and macro flags, and
multimethod dispatch values. Source is available where the JVM can access it; a checkout on the agent's machine is not
required for navigation or evaluation. Outlines and metadata describe what is loaded; source readers show the files
on the classpath, which can differ from definitions changed at the REPL.

This is useful both locally and when debugging a dev, staging, or QA server over a secure tailnet. The agent can ask
what that instance has loaded and what state it is in, even when a local checkout differs from the deployed build.
Remote connections rely on the private network for access control; see [Security](#security).

### Explore results without repeating the work

Results stay as live values in the JVM. While an entry is retained, the agent can use `(__r/res id)` to filter,
summarize, or otherwise inspect its result without rerunning the original operation. An expensive query can be run
once and explored in several directions, sending only the relevant slices back to the conversation. Collection
previews are bounded in length and depth, and truncation is announced with a reminder that the entry holds the whole
value.

Failures remain inspectable too. The response shows the root cause and exception chain with evaluation machinery
trimmed from the trace; the original throwable remains available for inspecting `ex-data`, causes, or the full stack.

### Guide the agent toward hygiene

The manual and response cues teach the agent to work considerately in a shared, long-lived JVM: keep temporary
definitions out of application namespaces, consider cleanup before cancelling work with side effects, release unread
results it no longer needs, and leave other sessions' running work to its owners. Structured concurrency also cancels
unfinished child tasks when their eval settles, unless the work was explicitly detached.

Scratch namespaces are one concrete example. Each session starts in its own, where the agent can put temporary
definitions and aliases while calling into the application. It can create additional scratch namespaces for separate
investigations and retire them when finished. These are working conventions and lifecycle tools within the shared JVM;
evaluated code still has full access to application state.

Scratch bindings also follow host reloads: stale aliases, refers, and imports are refreshed before the next form.
The agent is told when this happens and reminded that values and functions it defined before the reload may still
refer to the old code.

### Share work between sessions

Each session has its own current namespace and scratch space, while eval handles and retained results are shared
across the JVM. One session can hand another an id to inspect or build on, without copying the value into a message
or rerunning the operation. A session roster, descriptions of what each session is doing, and ownership labels on
evals help agents coordinate. This is attribution within a trusted system, not access isolation.

### Learn the tools from the connected server

The connection greets the agent with a short introduction and one instruction: run `(__r/manual)` before the first
real eval. The manual supplies the conceptual model as prose and the API reference as live data from the build
actually running. Responses provide further hints as the agent works, and a session that skips the manual is reminded
once. This teaches the workflow, including scratch namespaces and background work, without project-specific prompt
setup or a separately maintained copy of the API reference.

The server also includes discoverable Clojure recipe namespaces for common patterns: cleanup, deadlines, bounded
parallelism, partial results, and repeating work. The agent can read them using the same navigation tools.

MCP carries eval forms as data, avoiding an extra layer of shell quoting. Clients that support MCP tasks use their
existing controls to follow and cancel background work. Most of the toolkit remains ordinary Clojure functions
returning data, so the agent can narrow and combine results using `clojure.core`.

## What the agent gets

A few MCP tools, and a library of ordinary Clojure functions called through `eval`:

- **`eval`** — namespace-aware evaluation. Every eval runs as a [Quiescent](https://github.com/multiplyco/quiescent)
  task under a registry id carried on the response, and the id is a live handle — compose on it, cancel it, read its
  value later. Work that outlasts the call keeps running: a client that supports MCP tasks gets it as a background task
  after 10 s, delivered when it settles; other clients get `running` at the call's deadline. Printed output is captured
  per eval and attributed across its whole task tree; while an eval runs, its latest printed line is the live progress
  message.
- **`await`** — wait for ids to settle, with a mandatory deadline.
- **`cancel`** — cancel ids, cooperatively, and report where each stands.
- **`create-session`** — for clients speaking the stateless 2026-07-28 MCP revision, which carries no transport
  session: called first, and its id passed to every other tool.

`await` and `cancel` are offered only to clients without MCP task support; with it, the client's own task handling
waits and stops. Clients that open with `initialize` get the earlier revisions' handshake and no `create-session`.

Inside evals, the toolkit is the `__r` namespace, aliased everywhere the agent goes: `nses`, `vars`, `outline`, `doc`,
`source` and `file` for navigation; `res`, `evals`, `out` and `failure` for the registry; `sessions`, `introduce!`
and `release!` for sharing the JVM with other connected sessions. Running entries remain addressable. Settled results
are protected until delivered or released, including release when their session ends; they then enter a bounded
least-recently-used pool. Eviction is always announced, never silent.

The composition language is `clojure.core`: filter and transform the returned data in the JVM, then return just what
you need. The live API reference describes each function's accepted inputs, options, and output shape.

## Requirements

- JDK 21 or newer (25 recommended) — Quiescent runs evals on virtual threads.
- Clojure 1.12+ provided by the host.

The endpoint uses `com.sun.net.httpserver` from the JDK and `data.json` for JSON. Evals run on Quiescent; a host
carrying its own Quiescent dependency resolves a single version for both.

Add it from [Clojars](https://clojars.org/co.multiply/remontoire):

```clojure
co.multiply/remontoire {:mvn/version "0.8.2"}
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
{"server":"remontoire","version":"0.8.2","describe":"the production system behind example.com"}
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
shop-local-repl    → the JVM on your desk
shop-prod-repl     → the one that pages you
```

`:describe` complements the name from the inside: the server states its own environment, in its own voice, as part of
the intro every connecting session reads —

```clojure
(remontoire/init! {:describe "the production system behind example.com"})
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
[doc/design/](doc/design/): binding and origin policy in §4, retention in §5, the eval model in §9, sessions and output
attribution in §11. When behavior surprises you, the design doc says why it is the way it is. Working on Remontoire
itself is covered in [CONTRIBUTING.md](CONTRIBUTING.md).
