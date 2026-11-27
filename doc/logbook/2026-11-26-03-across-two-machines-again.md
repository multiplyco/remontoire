# 2026-11-26 — Across two machines again

The trial of 2026-11-23, on the build that came out of the reviews since: run-owned tables, order at admission,
`X-Answers`, the welcome split between the head and the stream, and the fixes of the two entries before this.

The rig is that one's. The gateway on a second machine on the same local network — a small Linux server, JDK
26, a snapshot of the commit copied over — bound to that machine's loopback; a follower on the development
machine (macOS, Java 27) dialling it through an SSH local forward; the client on the development machine
through a second forward. The client this time was a script speaking the legacy wire, where the last was
`curl` on the stateless one.

*It attaches and routes.* The follower attached under its name, and the cadence it kept read 5000 ms, which is
the gateway's and came in the head of the attach's response, through the forward. A session without `node`
answered Linux and the gateway role; one with `node` answered macOS and the follower role.

*What the hop costs.* A trivial eval, twenty times each, wall time of the whole call: median 17 ms in the
gateway's own JVM (12 to 19), 45 ms on the follower through it (40 to 107).

*Progress keeps its timing.* A 6 s eval printing every 1.5 s on the follower, held to its end: progress at
2.05, 3.06 and 5.08 s, the response at 6.11 s with all four lines.

*A batch.* 32 calls in one POST, UUIDs for ids, for a session on the follower: 32 responses on one stream in
0.13 s, every id among them, none an error.

*A cancellation.* A 20 s eval on the follower, and 1.5 s into it a `notifications/cancelled` naming its
request, posted on a connection of its own: accepted in 33 ms, and the call returned at 1.56 s, cancelled.

*A handle on both machines.* A second session on the follower, with a result it had read. The gateway's JVM
was given a session row by that name, by hand, and the follower's stream ended from the gateway. The follower
was attached again 1.02 s later. The handle was then among the gateway's retired and routed nowhere, live on
neither machine; a call naming it was refused as retired, and the follower's other session went on serving.

*A partition, with an answer under way.* The follower's forward was stopped by signal — the forward itself —
1.0 s into a 4 s eval on the follower, the call's stream open. The call returned 14.0 s after the freeze with
the gateway's message for an answer cut short. A further call was refused at once, naming the node and that
it was last heard 15 s ago, and the roster showed the node not attached, with its one session. The forward
was continued after 27 s, and the session answered 0.3 s later with the value it held. That answer's footer
said the eval that had finished during the partition was settled, and its value was then read by its id: the
result nobody was given had stayed, and was told at the next call.

*The gateway's JVM killed and started again.* Killed outright on the second machine and started afresh by
hand, some ten seconds later. The first answer from the new gateway, 14.8 s after the kill, was "not known
here yet" for the follower's session; at 15.2 s the session answered with its value. A session that had lived
in the gateway's own JVM was answered "not known here yet", as before.

*A follower that lets its gateway go.* The follower's own stop, called from an eval on it, the JVM left
running. The eval was answered. The roster showed the node as not attached 14.8 s later, looked at four times
a second, and a call to its session through the gateway was refused naming the node. At the follower's own
address the session answered with its value, the follower said it followed nothing, and a session opened
there was served.

That last figure is the one thing here worth a second look. A follower that stops closes its end of the
stream and says nothing, and through the forward the gateway learned of it no sooner than it learns of a
follower that has gone silent. The time fits the quiet, two beats after the node was last heard, and not a
write that failed; which of the two ended the attachment was not looked at. A call for one of its sessions
made in that window is queued for a node that will not read it, and is answered when the gateway gives the
node up. On loopback a killed follower was listed as gone within six seconds. Whether a stop should say so
to the gateway is not decided; nothing was changed for it.

Not tried: an upload and several evals at once, which the earlier trial covered and nothing since has
touched; the stateless wire across the hop on this build; the roles the other way round; a real suspend of
either machine; more than one follower across the hop; a single reply dropped where everything else gets
through, which the partition does not stage.
