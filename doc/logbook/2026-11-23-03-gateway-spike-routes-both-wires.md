# 2026-11-23 — The gateway spike on one session notion: a legacy client is routed too

The spike of the day's first entry served a legacy client on the gateway's own JVM and never routed it: a legacy
session came from the handshake, before a node could be named. With the session a handle on both wires (the second
entry, §11), the spike was rebased onto that change and run again. Same rig: the `bb dev` JVM with the spike's
gateway on a port of its own, a second JVM attached as `box-b`, `curl` as the client, loopback only.

*The routing code did not change.* The spike routes on a payload's method and arguments, which now read the same on
both wires. Its one edit was to the node's handle adoption, which reached for a minting function that had been
renamed.

*A legacy client's session lands on the node.* `initialize` at the gateway, then `create-session` with
`node: "box-b"` under the connection id it answered: a handle, and an eval naming it answered the second JVM's pid.

*A stateless client's still does.* The same two calls with the stateless headers answered the same pid.

*The node does not know a legacy client.* `(__r/sessions)` on the node showed the legacy session with no client:
the gateway's JVM answered the handshake, so the node's connection record for that id was empty when
`create-session` copied from it. §15 has the gateway pass the record along.

Not tried: anything else from the first entry's list on the legacy wire — progress frames, uploads, a client hanging
up.
