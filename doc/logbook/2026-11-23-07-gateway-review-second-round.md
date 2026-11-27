# 2026-11-23 — The gateway under review again: what the first fixes left

The fixes of the previous entry went back to the same reader, who ran faults against real gateway and follower JVMs
rather than reading alone. The suite was green — 160 tests, 593 assertions — and the reader found a client left
waiting, one request answered twice, and an upload stored wrong, all in code the previous entry calls fixed. Read
against the code, every finding holds. None is fixed here: the ones that belong to an instance alone are taken to
the base first, and this entry records what stays owed on the gateway.

## Falsified in the previous entry

- *"A follower whose part fails now says the answer is given up."* It says so once and takes any HTTP answer as
  heard. With the answer endpoint returning 503 while beats passed, the gateway kept the request, the follower
  stayed attached, and the client had nothing.
- *"The gateway answers the client itself."* It also does when the only part lost was the one that closes an event
  stream, after the result had been written: the client then has the request's id twice, a result and a refusal.
- *"Settled waits are swept at the next wait or attach."* Not at a wait for a node already attached, which returns
  before the sweep.
- *"Each fix has a test that fails on the code as it was."* Thirteen of fourteen do. The cancellation test names a
  function the old code lacks and cannot be compiled against it.

## Owed on the gateway

1. **An abort the gateway did not confirm.** Anything but a confirmation — a request settled, or no longer held —
   has to be retried, and the attachment dropped when it never comes.
2. **A lost end is not a lost answer.** The part that closes a stream carries no bytes and can be sent again as
   what it is. Giving an answer up is for a part that carried bytes; the gateway must not add a refusal to a
   stream that already delivered its result.
3. **Body framing across versions.** A follower from before the frames stores the frame lengths as file content
   and answers 201; a follower with them, behind a gateway without, reads file bytes as a length and allocates it.
   The attach should carry a revision of the wire and a mismatch be refused there, and the reader of frames bound
   the length it will take.
4. **A welcome erases a registration pending beside it.** The welcome replaces the set of unconfirmed handles with
   one computed from a list of sessions read earlier; a session minted in between is live, unconfirmed nowhere,
   and never asked about.
5. **A registration recorded whose reply was lost.** The follower gives the handle back and tells nobody; the
   gateway keeps the route.
6. **A handle taken at reconciliation is retired on one holder.** The follower drops its session on a 409, though
   its client has the handle and the gateway routes it to the other holder — the case the rule of retiring both
   exists for.
7. **A retirement forgotten after a day.** A holder that missed it, kept alive through its own endpoint, attaches
   later and is given the handle back.
8. **A batch with a cancellation for a request running in the gateway's own JVM** and a call for a node is carried
   whole to the node: a cancellation matched here has no destination, and only messages with ids count as local.
9. **The sweep of finished waits** skipped on the path that returns at once.

## Where the fixes are meant to go

Items 4 to 7 are one fault seen four times. A follower's handles are copied to the gateway by separate messages —
a registration, a release, the report at attach, a second asking after a beat — each of which can be lost alone,
with two threads writing the follower's side. The intent is to send state instead: the beat carries the follower's
live handles, and the gateway settles every report with the function it settles an attach with, so that a handle
on two holders is retired on both wherever it is found. Registration stays as the check made before a client is
given a handle; whatever it leaves uncertain the next beat settles. Retirements are kept until every holder has
confirmed, with no expiry: they arise only from collisions.

No queue of incoming requests is wanted anywhere. Calls in one session run side by side by design, and the parts
of one answer are already sent one after another.

## Taken to the base

Three findings are not the gateway's and are fixed where they live, before this branch is picked up again: a
cancellation reaching another caller's request, which is as old as the table of requests in flight; a session
released while its eval runs, whose row the eval's ending writes back; and the scratch name of a session that
survived a reload from 0.9.0. The first and last are in this branch's working state in a first form, which the
base's own will replace.

## Proposed to leave

Loading this code into a running follower from before it breaks that follower's `stop!`, its state lacking two
keys. Both sides of that reload are unreleased, and a development follower is restarted in seconds.
