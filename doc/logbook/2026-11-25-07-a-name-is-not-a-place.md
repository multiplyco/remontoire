# 2026-11-25 — A name is not a place

Two things from the reviewer, after the probes of the entry before.

## A batch for two JVMs under one name

A legacy batch is carried to one place, and the places its messages belong in were compared by node name. A
cancellation is placed by the request it names, which is looked up as the request waits now; a call is placed
by its handle, in the router's one reading of the state. With a node replaced under its name between that
reading and the batch's routing, and a request of the same connection in flight on the newcomer, the two
places bore one name. The batch then went to whichever its first message named: with the cancellation first,
the newcomer was sent the notice and with it a call for a session it never held. The reviewer saw it with a
probe of their own; listed the other way round, both stayed off its queue.

Places are now attachments. A cancellation's is the attachment its request went to, a handle's the one the
reading shows under the node's name, and two of them are two places whatever they are called: the batch is
refused whole, in either order. The test gives the router the reading it had, with the node replaced since
and a request waiting on the newcomer.

## A note for an attachment gone by the time it is acted on

Seen in the probes and left there: a beat that found its attachment and came to the lock after the name had
passed on was answered 200 with nothing to drop, and a registration likewise was answered 409. The first
delays the follower's redial by a beat. The second tells it the handle is taken, and it draws another for
nothing. Both are now 410, as a note arriving a moment later already was. What arrives out of order from an
attachment still held is answered as before, 200 and 409, and the test for that stands unchanged.

The follower needed no change: a beat not answered 200 closes the stream it watches, and a registration
answered neither 204 nor 409 is unsure.

## Removals

The gateway's, the follower's, the lifecycle and the session tests, 91 of them, against each removal alone:

| Removed | Failing assertions |
|---|---|
| Places compared by node name | 2 |
| 410 for a beat whose attachment went meanwhile | 1 |
| 410 for a registration whose attachment went meanwhile | 1 |
