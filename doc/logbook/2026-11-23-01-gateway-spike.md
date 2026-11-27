# 2026-11-23 — A gateway spike: MCP requests routed to a JVM that dials in

One MCP entry per instance does not suit a fleet of machines that come and go. Spiked the alternative (§15): one
instance as a gateway, another attaching to it and serving what it is sent. The spike is
`dev/co/multiply/remontoire/gateway_spike.clj`, never in the jar, reaching into the library through private vars
rather than changing it. Two JVMs on one machine, Java 27, loopback only: the `bb dev` JVM with the gateway on a port
of its own beside the ordinary endpoint, and a second JVM attached as `box-b` with no port open. `curl` played a
stateless-era client; Claude Code drove the gateway JVM's own REPL throughout.

*A streamed request body arrives two writes late.* The first design sent each response as one POST whose body was
streamed with `BodyPublishers/ofInputStream`. Through it, the stream's opening comment reached the client 2 s after it
was written, and progress frames ran 2–3 s behind. Isolated against a throwaway `HttpServer`: five lines written 400
ms apart, at 0, 405, 810, 1212 and 1614 ms, arrived at 812, 1213, 1615, 2018 and 2018 — each when the write two
after it was made, the last two when the stream closed. The same with `send` and with `sendAsync`, with a
`PipedInputStream` and with a queue-backed stream of the spike's own, with the server reading raw and through a
`BufferedReader`.

*Falsified on the way: "the JDK client does not buffer".* The very first run of that measurement, earlier the same
day, showed the first line arriving 23 ms after it was written and the rest within 3 ms, and was reported as the
answer. Every run after it lagged as above. Why the first differed is not known; one run was not a measurement.

*A pushed body did not lag.* The same test over `BodyPublishers/fromPublisher` with a `SubmissionPublisher`: each
line arrived within 2 ms of its write, twice over. It also dropped the first line both times, submitted before the
client had subscribed.

*One acknowledged POST per write keeps the direct connection's timing.* The follower sends each handler write as a
POST, and the gateway answers after writing those bytes to the client. A 6 s eval printing every 1.5 s, over the
tunnel: frames at 0.01, 1.01, 2.03, 3.04, 5.05, 6.04 s. The same eval on the gateway JVM directly: 0.01, 1.02, 2.03,
3.03, 5.04, 6.03 s.

*The library's handlers served the follower unchanged.* A `proxy` of `HttpExchange` built from the request envelope
was handed to `http/handle-exchange!` and `http/handle-upload!` as they are.

*The handle routes.* `create-session` with `node: "box-b"` got a handle minted by the gateway, and an eval under it
answered the second JVM's pid; one without `node` answered the gateway JVM's. A `node` naming nothing attached was
refused with the names that were.

*Tasks route by their id.* A background eval declared with the tasks extension answered as task `dvh-4`; `tasks/get`
for that id, carrying no session argument, reached the follower and returned its result.

*Uploads come along.* `(__r/upload-url)` on the follower named the gateway's address. A 74-byte source file and 3 MB
of random bytes sent there were staged on the follower, the larger with a matching SHA-256, and `load-file` of the
source ran in the follower's JVM.

*Nothing queues behind anything.* Eight evals on the follower and one on the gateway, each sleeping 2 s, sent
together: 2.1 s of wall time.

*A client hanging up is seen on the follower.* `curl` gave up 2 s into a 5 s eval. The next call in that session
announced the abandoned eval as settled and unread — the follower's write had failed as a direct one would, and no
delivery was marked.

*A restarted follower refuses what it does not know.* The follower JVM was killed and started again under its name. A
handle from before drew the existing refusal — not live, minted before a restart — from the follower itself; the
gateway had only forwarded it.

*A held name is held.* A second attach as `box-b` while the first was live: `409`.

*A follower dying mid-eval releases the client.* Killed 2 s into a 30 s eval, the client's stream was cut about 8 s
later: the gateway notices on the second failed write of its 5 s keepalive.

*Waiting for a node is an eval.* `(node "box-b")` returned a task that settled when the second JVM had booted and
attached, and the eval that returned it simply took that long.

*Version skew, by accident.* The gateway JVM had loaded its `mcp` namespace before the day's version bump and the
follower after it, so the two answered with different `serverInfo` versions through one address. It showed at a
glance which JVM had served a response.

Not tried: Claude Code or any real client against the gateway, whose `create-session` did not advertise `node`; a
network hop; a follower that goes silent without closing its socket; a legacy-era client, whose requests the spike
serves on the gateway JVM and never routes.

What the spike is not. It predates what the design conversation settled afterwards (§15): its handles are three
letters and minted by the gateway alone; nothing is reported at attach, so a restarted gateway forgets its routes and
stale ones are never pruned; a held name is refused outright rather than challenged; a fresh gateway refuses an
unknown handle at once. The follower adopts the gateway's handle by replacing `session/mint-handle!` at startup,
standing in for a mint that is given one. The roster and the wait are plain functions in the spike's namespace, not
an alias a gateway's sessions carry.

Decided against on the way: the streamed POST body, for the lag above and because one acknowledgement at the end
cannot say which writes reached the client; and a design in which the follower only announces itself and the gateway
dials its endpoint, which needs every follower to be addressable from the gateway.
