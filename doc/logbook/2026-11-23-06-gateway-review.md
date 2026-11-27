# 2026-11-23 — The gateway under review: nine findings, all of them real

A second reader went over everything since 0.9.0. The suite was green and both smoke tests had passed; none of the
nine was something either would have shown, because each needs one message lost, or two things at once. All were
confirmed against the code and fixed. Each fix has a test that fails on the code as it was.

*A registration the gateway did not answer left a session it could not route.* The follower took anything but
"taken" as a yes. With the gateway answering errors while its beats still passed, a `create-session` sent through it
succeeded and the next call was refused as not live. A handle given out without the gateway's word is now asked
about again after every beat, and a session opened through the gateway is not given out without it.

*The gateway's refusal was staged as an uploaded file.* The follower passed whatever answered its fetch of the body
to the upload handler, status unread: "No such request waiting" became the file, and the upload answered 201. The
status is checked, and the body travels in frames that end with an explicit last one, since a body cut short on its
way in to the gateway is noticed only after the response carrying it out has begun.

*A retired handle came back.* Two nodes reported one handle; the one attached was sent the retirement and went away
before reading it; on its return it reported the handle and was given it. The gateway now keeps every holder on
record until that holder says it released the handle or attaches without it.

*A lost final part left the client waiting.* The follower marked its answer ended before the part carrying the end
had arrived. Closing the exchange then sent nothing, and the gateway waited for as long as the node stayed
attached. A follower whose part fails now says the answer is given up, and the gateway answers the client itself.

*A batch for two machines ran on one.* The router sent the whole body to the first node named in it. Such a batch
is refused before any of it runs.

*A cancellation could stop another client's eval.* The gateway matched a request by id, address and connection, and
two clients on one machine speaking the stateless wire share all three. The table inside a single instance was
keyed by the bare id, and had been since before the gateway: two clients with request 5 in flight shared an entry.
Both are keyed by the id within its sender now, and a cancellation that still names two requests cancels neither.

*A follower served after it was stopped.* An attach waiting for its answer when `stop!` ran was not cancelled, and
the welcome that arrived later was acted on.

*Finished waits for a node were kept.* `(__r.gw/node …)` stored its promise before looking at the roster, and a
cancelled wait for a name that never came stayed for good. Settled waits are swept at the next wait or attach.

*A session from before handles lost its scratch on a reload.* Up to 0.9.0 a legacy session went by its
connection's UUID and its scratch by the first eight characters; the one-session change named the scratch by the
whole id, while the session table survives a reload. The eight-character rule is back. A handle is five, so it
names itself either way.

Tried afterwards between two JVMs on one machine, the gateway reloaded in place: 3 MB uploaded through the gateway
to a session on the follower, SHA-256 matching; an upload declaring 3 MB that sent 1 MB and hung up, and a chunked
one cut before its last chunk, both under the name of a file already staged, which kept its content; a scripted
second node claiming the follower's handle, after which the follower had dropped the session, the gateway routed
the handle nowhere, and its record held the scripted node alone — the follower had said it let go.

Not tried live: the lost part, which needs a POST to fail while beats pass. It is covered on each side by a test
that plays the other.

What the review says about the method: the suite scripts the far side of the wire by hand, so each side is tested
against what its author believed the other does. Three of the nine sat exactly there — a status nobody read, an
end nobody confirmed, a message assumed heard.
