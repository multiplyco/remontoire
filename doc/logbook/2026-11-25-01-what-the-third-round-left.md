# 2026-11-25 — What the third round of gateway fixes left

The reviewer went over the third round (the entry before this one) and found four things in it, and two
sentences that said more than was known. Three of the four are fixed here, each with a test that fails without
its fix; the fourth is not taken up, and why is below. The counts are of the gateway's, the follower's, the
lifecycle and the session tests, 80 of them, run against each removal alone.

## A request accepted before the gateway stopped

The round before put the router in place before the port opened and took it away after the port closed. That
covered what arrives; it did not cover what had arrived. Closing the listener leaves handlers already running,
and one held between reading its request and routing it found the router gone when it went on: a
`create-session` naming a node minted a session in the gateway's own JVM after `stop!` had returned.

The router was a global the role installed and removed, and no order of installing and removing covers a
request in flight. It is now part of what a gateway's server is started with, and goes with every exchange that
server accepts. A stopped gateway has nobody attached, so the held request is routed to nobody and refused. The
test holds a real request at that point, stops the gateway and lets the request go. This fix is not a line to
remove, so its check was the test run against the commit before: one failure, a session minted here.

## An attach that never becomes an attachment

- **Keepalives without a welcome.** Every line an attach stream brought counted as hearing from the gateway,
  the welcome or not, so a gateway that answered 200 and sent only comments was never dropped. Nothing counts
  now until the welcome, which is given three opening beats from the answer. Counting every line again: 1
  failure. The test's gateway had been sending one comment and going quiet, which the earlier fix covered; it
  now keeps them coming.
- **A refusal that never ends.** A refusal's body was read to its end with nothing watching it and nothing able
  to close it, `stop!` included. It is held where a stream is held: watched for silence, and closed by a stop.
  Published nowhere again: 2 failures, one for the watch and one for the stop.
- **Asked before welcomed.** A request, a ping or a retirement that arrived before the welcome was acted on. A
  gateway that sends one first is refused and dialled again. Acted on again: 2 failing assertions.
- **A welcome without an attachment.** Refused like one in another revision, before its list of handles to drop
  is acted on. Taken anyway: 1 failure.

The cadence a welcome sets is now that attachment's alone: the next attach is given the opening cadence to be
welcomed in, not the three long beats of the gateway it lost.

## A door that cannot be opened

The round before had the transport open `/gateway` beside its own doors, from an option. The option went
through `init!` like any other, so a host could name a door too, and a path the server refused threw after the
port was bound: a server that has bound and never started does not give its port back. What a role adds to the
transport is now an argument of its own, which `init!` builds and a host's options never reach, and the server
is made unbound, given its doors, and bound last. The port taken first again: the test errors, the retry
refused its port. A host's options passed through as wiring: 1 failure.

## Two sentences

- §15 said that after a restart a returning holder's retired handle is adopted, "which routes one conversation
  correctly and leaves the other with a refusal". The other is refused nothing: both conversations hold the
  handle, and both are carried to the holder that returned. The doctrine now says so.
- The last message of a stream cut short said the call "ran on and its result waits there, unread". The gateway
  does not know that. A stream can open at the gateway and the node not be told so, in which case the call
  never began, and on the stateless wire the same loss stops an eval. The message says the outcome is unknown.

## Between two JVMs

The handshake changed on the follower's side, and the suite plays the gateway from a script, so a gateway and a
follower were run as separate processes on one machine again. The follower was started first and dialled a
gateway that was not there; it attached when one came up. A session opened on the node through the gateway
evaluated there. The gateway was killed and started again, the follower attached afresh, and the same session
was routed. No fault was injected between the two; those are the suite's.

## Not taken up: the wire revision

The attachment id changed what a follower must say, and the wire is still revision 1, so a follower built from
the commit before attaches to a gateway built from this one and is then disowned at every beat. Both are
commits on a branch that has not been released, and no released follower or gateway speaks any revision at
all: 1 is the wire as it first ships. The number counts wires that hosts can be running, and none is.
