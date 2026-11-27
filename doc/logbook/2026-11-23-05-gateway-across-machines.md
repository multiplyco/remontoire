# 2026-11-23 — The gateway across two machines: latency, a partition, a gateway restart

The previous entry's trials were all on loopback. Rig for this one: the gateway on a second machine on the same local
network — a small Linux server, JDK 26, the working tree copied over — bound to that machine's loopback; a follower
on the development machine (macOS, Java 27) dialling it through an SSH local forward; `curl` as the client on the
development machine through a second forward of its own. Requests therefore crossed the network to the gateway and
back to the follower, and the follower's held stream crossed it once. Ping between the machines averaged 6 ms.

*It attaches and routes.* The follower attached under its name. A session without `node` answered Linux and the
gateway role; one with `node` answered macOS and the follower role, and `(__r/upload-url)` there named the address
the client had used for the gateway.

*Progress keeps its timing.* A 6 s eval printing every 1.5 s on the follower: frames at 1.06, 2.07, 3.08, 5.10 s and
the response at 6.07 s.

*What the hop costs.* A trivial eval, twenty times each, wall time of the whole `curl`: median 34 ms in the gateway's
own JVM, 61 ms on the follower through it.

*Uploads.* 3 MB sent to the gateway's address reached the follower in 0.89 s with a matching SHA-256, having crossed
the network twice.

*Nothing queues.* Eight evals of 2 s on the follower and one on the gateway, together: 2.1 s.

*A partition.* The follower's forward was stopped by signal, so that its connections stayed open and carried
nothing; the client's forward was left alone. A call to the follower's session made 3 s in was answered after 10 s
with the refusal for a node that went away. The roster then showed the node as not attached, with its one session,
and a further call was refused naming the node and how long ago it was heard. The follower said once that it had
lost its gateway and once that it could not attach, and kept trying. With the forward continued after 27 s, the
session answered within 0.1 s, with the value it had held.

*Falsified on the way: the first attempt at that partition showed nothing.* The node stayed attached and every call
was answered, because the signal had stopped the shell wrapping the forward and not the forward. Read as a result,
it would have said a partition goes unnoticed.

*The gateway's JVM killed and started again.* Killed outright on the second machine and started afresh. The
follower's session was answered "not known here yet" 7.4 s after the kill and with its value at 8.5 s — the time a
JVM takes to boot there, and about a second more. A session that had lived in the gateway's own JVM was, in those
first seconds, also answered "not known here yet": the gateway cannot tell a handle it has not been told of from one
that died with its predecessor.

A change made for this trial: an attach that gets no answer is given up after 10 s. A follower dialling into a
network that drops packets would otherwise wait for as long as TCP does, with nothing else watching.

Not tried: the roles the other way round, a follower on the second machine; a real suspend of either machine; a
client other than `curl` against the remote gateway; more than one follower across the hop.
