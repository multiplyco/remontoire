# 2026-11-23 — A stateless client cancels by closing its stream, and sends no notice

Asked because of a review finding: on the stateless wire a `notifications/cancelled` can be attributed to nothing
narrower than an address, so the question was whether such a notice carries anything more, and what the client
does at all. Run against claude-code 2.1.330 in its headless mode (`claude -p`), with `MCP_TOOL_TIMEOUT` at 8 s,
against a throwaway JVM on a port of its own: this checkout's source, a request log in the transport, the default
wait raised so that a call is held open, and heartbeats every second so that a hangup is noticed promptly.

*Giving up on a call, the client closes the stream and says nothing.* An eval of a 90 s sleep was held open. At
8 s the client reported "timed out after 8s" to its model; the server's next heartbeat write failed with a broken
pipe, about 9 s after the request arrived. No `notifications/cancelled` followed, then or later. The client was
alive throughout: its next two calls were served normally, on a new TCP connection. The eval ran on to its end —
nothing here treats a closed stream as a cancellation.

That is what the 2026-07-28 revision asks of a client, as its pages read today: over HTTP, closing the response
stream is the cancellation and no notice is expected. It is the opposite of what the same client did on the legacy
wire (2026-08-04), where it sent the notice and kept the socket open.

*Request ids count up from zero.* `tools/list` was request 0, the calls 1, 2, 3, 4; the discovery probe alone had
a string id. Two clients on one machine therefore share ids as a matter of course, not coincidence.

*Nothing in a request ties it to a client instance.* `_meta` carries the protocol version, client name and
version, capabilities, a progress token that is the request id again, and `claudecode/toolUseId` on tool calls.
The headers are the method, the tool name, the protocol version and a user agent. Requests reuse one keep-alive
TCP connection while it is free.

*Found in passing: this client declares tasks on `tools/list` and not on `tools/call`.* `server/discover` and
`tools/list` carried the tasks extension among the client's capabilities; the tool calls did not. It was therefore
listed the reduced tools — `eval` without `await`, and no `await` or `cancel` tool — and then answered on the
deadline path: an eval outlasting the default 2 s came back `running`, to a model with no listed way to wait for
it. Whether this is the headless mode or the version is not known; 2.1.317, run interactively, declared tasks on
every request (2026-11-03). Not addressed here.

Not tried: the interactive client, and a stop by the user rather than a timeout.
