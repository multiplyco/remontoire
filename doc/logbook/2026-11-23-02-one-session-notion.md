# 2026-11-23 — One session notion: the legacy wire moved onto the handle

The legacy wire's session came from its handshake and rode `Mcp-Session-Id`; the stateless wire's was a handle minted
by a tool and named in every call. Unified on the handle (§11), so that whatever is built on sessions next is built
once. Reloaded into the `bb dev` JVM and driven with `curl` as a legacy client, loopback only, while Claude Code
stayed connected to the same JVM on the stateless wire.

*The handshake mints no session.* `initialize` answered with an `Mcp-Session-Id` and instructions ending in the
`create-session` step; the roster was unchanged by it.

*The legacy tool list is the stateless one.* `tools/list` named `create-session`, `eval`, `await` and `cancel`, each
but the first requiring `session-id`.

*A call without a session is refused in words that say what to do.* No `session-id`: told to call `create-session`.
The connection's own id passed as `session-id`: refused as not live.

*A session created over a connection knows its client.* `create-session` answered handle `tps`, and `(__r/sessions)`
showed that row with the client name and version given at `initialize`.

*A session survives a reconnect.* A second `initialize` got a different connection id; an eval over it, naming `tps`,
read a var the session had defined over the first.

*A goodbye ends the connection only.* `DELETE` with the first connection's id: `204`, and the next eval naming `tps`
still read the var.

*No connection is needed.* With no `Mcp-Session-Id` at all, `tools/list` answered `200` and an eval naming `tps`
ran.

*A stale tool list is announced to the connection, and only until it is fetched.* The connection that had fetched
its list got no `tools/list_changed` on its eval stream. The reconnected one, which had not, got one ahead of its
response. So did a connection id the server had never issued, as a client presents after a server restart; after a
`tools/list` under that id, the next stream carried none.

*A client holding the old tool shape is told twice over.* An `eval` call with no `session-id`, under an unknown
connection id, over SSE: the stream carried `tools/list_changed` and then the refusal.

*The stateless wire did not notice.* Claude Code's session, created before the reload, kept answering through it.

Suite-falsified on the way: `(evals)` threw `StringIndexOutOfBoundsException` for another session's entry — `:by`
took the first eight characters of an owner id that is now three. The prefix was a UUID's; `:by` is the handle now.

Not tried: a real legacy client. The suite's legacy client and `curl` both send what the code expects; whether a
client that cached the old tool list re-fetches on the announcement, as Claude Code was measured to on 2026-08-04, is
untested for any client that still speaks the legacy wire.

Decided on the way: `DELETE` ends no session, since a session is the conversation's and the stateless wire never had
a goodbye; a request without `Mcp-Session-Id` is no longer refused, since nothing conversational hangs off the id;
and the tool-list fetch marker stays with the connection, because `tools/list` names no session.
