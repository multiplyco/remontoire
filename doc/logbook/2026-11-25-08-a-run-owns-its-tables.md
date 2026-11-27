# 2026-11-25 — A run owns its tables

Four entries today end the same way: a handler of a gateway that had stopped reached something that belonged to
the gateway started after it in the same JVM. The roster, the requests in flight, the pings and the waits were
the namespace's, a run was an id written among them, and each fix showed at one more access that the handler
was still entitled to what it found there. The reviewer, asked what it would change having read all of it, put
this first: give each run tables of its own.

A run is now made by `start!` with its name, its lock and its tables, and is what the router and the
followers' door are closed over. Every function of the role takes the run it works for. Three things are left
that are not a run's: the constants, the transport's doors, and one reference to the run this JVM is the
gateway of now — for what is asked outside any request, which is the roster, a wait for a node, and a handle
this JVM takes for itself.

## What went

- The run written on every attachment, and the comparison of it wherever an attachment was looked up. A
  handler finds only its own run's nodes.
- The run asked for at admission as an id that might have changed. A run has ended or it has not, which it
  says of itself, and it never starts again.
- The emptying of the routes and the retirements at a stop. Nothing reads an ended run but its own handlers,
  which refuse; a request found live just before the stop is placed by what the run knew. The finding of
  entry 04 — a retired handle looked up after the stop had emptied the retirements — has nothing left to
  happen to.

What stayed is everything within a run: a request is placed by one reading of its run's state, it is queued
for the attachment that reading showed, a batch is for one attachment, and what a follower says is acted on
only for the attachment its name still holds.

## What changed for a caller

A wait for a node, `(__r.gw/node "name")`, was kept across a stop and settled by a node attaching to the next
gateway in the JVM. It is its run's now, and fails when the gateway stops, saying so. One made as the gateway
stops is told too, and one made on an instance that is no gateway fails at once where it used to wait for
ever.

## Removals

The gateway's, the follower's, the lifecycle and the session tests against each removal alone:

| Removed | Failing assertions |
|---|---|
| An ended run refusing what it accepted | 1 |
| An ended run admitting no attach | 2 |
| A stop telling the run's waits | 2 |
| A wait made as the run ends being told | 2 |
| A welcome settling waits only for the attachment its name still holds | 2 |
| A call queued for the attachment the reading showed | 2 |
| An upload likewise | 2 |
| A batch placed by attachment | 2 |
| 410 for a beat whose attachment went meanwhile | 1 |
| 410 for a registration whose attachment went meanwhile | 1 |

The fifth failed nothing at first. The test that had caught it stops and starts the gateway, and a welcome of
the first run no longer has the second's waits to reach, with or without the check. Within a run the check
still decides something — a welcome for an attachment that gave the name up meanwhile would settle a wait with
nothing — and a test now gives the name up under a held welcome.

The two guards of entry 03 that only a pair of removals caught are gone with the comparisons they guarded.
