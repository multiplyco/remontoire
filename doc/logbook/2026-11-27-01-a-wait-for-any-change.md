# 2026-11-27 — A wait for any change to the fleet

A gateway's sessions could list the fleet and wait for a node by name. An agent that looks after a fleet also
wants to be told when it is another fleet than the one it last saw, whoever came or went. That is now a function
beside the other two, `(__r.gw/change seen)`, and like the wait for a node it is a task.

## What it compares

The names a roster lists beside the gateway, whether each is attached, and when it attached. A node being heard
from and the sessions it has are left out: both move all the time. When a node attached is kept in, since a
name can pass from one JVM to another and the roster then lists the same name, attached, as before.

## What was seen

The wait takes the roster the caller last had, and settles at once when the fleet is already another. Without
it an agent that looks, acts and then waits misses a change that falls in between, and waits for the one after.
A counter handed out with each listing would have done the same and was not built: the roster is at hand and
says what the caller knows.

## Told by a watch

A change to who is in the fleet is made in four places: an attach admitted, a node found gone, a node forgotten
for its silence, and a report that takes the last route of a node that is away. The waits are told by a watch
on the run's state, wherever it changed from. The watch hands the telling to a thread of its own, since the
state is changed holding the run's lock and a wait is not settled under it.

## Removals

The gateway's suite, 48 tests and 200 assertions, against each removal alone.

| Removed | Failing assertions |
|---|---|
| A change in the state telling the waits | 5 |
| The look again once a wait is among the run's | 5 |
| When a node attached being compared | 1 |
| A stop telling the waits for a change | 2 |
| Nothing being told of a run that has ended | 1 |
| A departed node counting only while sessions are routed to it | 2 |
| A wait that is over being swept | 1 |

The fifth failed nothing at first. A stop lets its nodes go, which the watch sees as a change, and the wait is
then settled with a fleet or failed with the stop by whichever comes first; the stop came first every time. The
test now holds a stop for a moment after each node it lets go, and the removal fails it.

## The suite

278 tests and 1,102 assertions, without a failure.

## Between JVMs

Two JVMs on loopback, started for this and stopped after it, neither the dev gateway. A client on the stateless
wire, with a session on the gateway.

| | Waited |
|---|---|
| The fleet kept in a var, a wait given it, a follower started 3 s into the wait | 5.2 s, and the fleet with the node |
| A wait given an empty fleet before the node had attached | until it attached |
| A wait given the fleet it was last given, the node attached | still waiting after 3 s |
| A wait given an empty fleet, the node attached | 1 ms |
| A wait given the fleet, the node's process killed 2 s into it | 9.9 s, and the node listed as away |
| A wait given nothing, nothing changing | still waiting after 2 s |

The node that was killed had one session, so the roster listed it afterwards. It was listed as away 7.9 s
after its process was killed, which is the gateway finding a node gone and not this wait: it finds out at a
write that fails.

## Not observed

A node forgotten for its silence was not waited out between JVMs: a day. A name passing to another JVM was not
staged between JVMs. Nothing was run across a network. The dev gateway was not used: a run has a table more
than it had, so it takes a restart.
