# 2026-11-03 — The stateless era and MCP tasks, smoke-tested against Claude Code

The §11 design (session as a `create-session` handle, the tool list following each request's tasks declaration, a
10 s grace before an eval answers as a task) built as a spike and run against claude-code 2.1.317 through `bb dev`,
with a temporary request log in the transport. One conversation, one session (`bfw`).

*The client is modern and declares tasks everywhere.* It opens with `server/discover` — no `initialize` — then
`tools/list`, and every request, `tools/list` included, carries `io.modelcontextprotocol/tasks` under
`_meta.clientCapabilities.extensions`, beside `roots` and `elicitation`. So the reduced list (`create-session`,
`eval` without `await`) is what it got, and the varying list is decidable from the listing request alone. Tool
calls also carry a `claudecode/toolUseId` in `_meta`.

*What the model sees when a call becomes a task* is the client's own text, not the server's: "started server-side
task bfw-2 … moved to the background as task k71c3shcl … use TaskStop with task_id …". The `statusMessage` never
reached the model. The registry bridge survives anyway, by the task id's shape: `bfw-2` names entry 2, so `(res 2)`
is one inference away. The notification on settling carried the full rendering — value, `;; out`, footer — and the
entry was delivered through it (touched, told), never appearing as `;; settled` news.

*Polling honours `pollIntervalMs`*: 16 `tasks/get` for a 25 s eval, gaps 1002–1030 ms against the advertised 1000.

*TaskStop sends `tasks/cancel`* — twice, for one stop; idempotent here. The entry settled `:cancelled`; no
notification followed.

*A failure after the grace* arrives as a `failed` task notification ("Task failed: …") with the rendering and
footer — but flattened to one line, where a completed result keeps its newlines. Readable, and the frames
survive, but a multi-line error rendering loses its shape on this path.

*A refused handle mints nothing*: `eval` with `zzz` came back a tool error naming `create-session`, and the session
table still held one session.

Not reached, by design: a task answer after the client's own 120 s backgrounding — the grace keeps every task
answer far inside it. Found in passing: data.json writes a keyword key by its `name`, so `_meta`'s namespaced keys
lost their prefix until the encoder got a key function.
