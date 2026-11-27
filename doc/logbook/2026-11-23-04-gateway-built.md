# 2026-11-23 — The gateway, built: tried between JVMs on one machine

The gateway of §15 built into the library — `gateway.clj`, `follower.clj`, and three seams in the transport and the
session table — replacing the morning's spike, whose file is removed. Rig: the `bb dev` JVM turned into a gateway in
place by reloading, and up to three further JVMs started as followers with `bb dev:follower`, each with an endpoint
of its own on a free port. Loopback only, Java 27. `curl` played a stateless client and a legacy one; Claude Code
stayed connected to the gateway throughout and drove it at the end.

*A session opened on a node lives there.* `create-session` with `node: "box-b"` answered a five-letter handle "on
node box-b", and an eval naming it answered the follower's pid and role. One without `node` answered "on node
dev-gateway" and the gateway's pid. A `node` naming nothing attached was refused with the names that were.

*Progress keeps its timing.* A 6 s eval printing every 1.5 s, on the follower through the gateway: frames at 1.02,
2.03, 4.04, 5.04 s and the response at 6.04 s.

*Uploads.* `(__r/upload-url)` on the follower named the gateway. 3 MB of random bytes sent there were staged on the
follower with a matching SHA-256.

*Tasks.* A background eval declared with the tasks extension answered as task `fxfcs-6`; `tasks/get` for that id,
naming no session, reached the follower and returned its result.

*A legacy client.* `initialize` and `tools/list` were answered by the gateway, the list showing `node` on
`create-session`. A session created on the node under that connection showed its client's name in the node's
`(__r/sessions)`: what the client said at the handshake had ridden along.

*A session minted at a follower's own endpoint is routed.* Created directly on the follower's port, then named in a
call to the gateway: answered by the follower. The gateway's roster counted it.

*Nothing queues.* Nine evals of 2 s each, four on each of two followers and one on the gateway, sent together: 2.1 s.

*A client hanging up* 2 s into a 5 s eval on a node: the node's next response in that session announced the eval as
settled and unread, as on a direct connection.

*A held name is held while its holder answers.* A second JVM started as `box-b` was refused, said so once, and kept
trying; the first went on serving.

*A frozen holder loses its name.* With the first JVM stopped by signal, a second claimed `box-b` and was attached
within the ping's two seconds. A session of the first, named afterwards, was refused by the second as not live.
Continued, the first found its stream closed, redialled and was refused the name.

*Sleep and waking.* A follower stopped by signal for some twenty seconds. A call to its session made three seconds
in was answered after 15 s, when the gateway took the node for gone, with a refusal naming the node. The roster then
showed it as not attached with one session. Continued, it redialled, attached, and the session answered with the
value it had held. The call made while it was frozen ran on waking — the envelope had been written before the
gateway gave up — which is what the refusal's wording now allows for.

*A node killed mid-eval.* Killed outright 2 s into a 30 s eval, the client's stream ended 10 s later, on the second
failed keepalive write. First with the stream simply closing; after a change, with a last message saying the node
went away.

*A restarted node refuses what it does not know.* A handle from before the restart was refused by the new JVM —
"minted before a restart" — and the roster's count for the node fell to what it reported.

*A gateway restarted in place.* The role stopped and started again in the same JVM with two followers attached, a
session on each. Calls made at 0.0 and 0.6 s were answered "not known here yet"; from 1.1 s both sessions answered
with the values they held.

*One handle on two nodes.* A handle planted in two followers' session tables, behind the gateway's back. After the
first had redialled and reported it, it was routed there. After the second had too, it was routed nowhere and live
on neither, and a call naming it was refused as not live.

*Waiting for a node is an eval.* `(__r.gw/node "box-b")` evaluated before the follower's JVM had booted: followed
as a task once, settled within the eval's own wait another time, each with the node's roster line.

*Claude Code as the client.* Its tool list predated the server becoming a gateway and showed no `node`. It passed
`node` anyway, and the session it was given was on the follower: an eval there showed the follower's pid, the
client's own name and version in the roster, and progress. `__r.gw` resolved in its session on the gateway and not in
the one on the node. The gateway's manual ended with the gateway passage.

Falsified on the way:

- *The first run of the name test in the suite said a holder that answers loses its name.* It did not: the test's
  scripted follower posted its pong from a thread without the test's bindings, so the pong went nowhere. The same
  exchange by hand against the running gateway gave 409 in 3 ms.
- *A follower waking to a request the gateway had given up on printed a stack trace.* The write failed as a client
  hangup does, and nothing caught it on the follower's thread, where the JDK's server would have swallowed it.

Not tried: a network hop, or a partition that drops packets without closing anything; more than three followers; a
client that reads `node` from the tool list; a legacy batch; uploads larger than 3 MB.
