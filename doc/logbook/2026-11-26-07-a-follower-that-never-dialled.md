# 2026-11-26 — A follower that never dialled

Sixth round of the day's review, of the commit before this. Its three findings were probed again and held,
the collision that entry left unstaged among them: the request of a stopped instance alone was refused, no
session appeared, and the follower's next report was answered with nothing to drop. Two findings, both of what
that commit added.

## Descriptors kept by a start that failed

The commit before this made a follower before its port is asked for, and with it the client it dials with. A
start that then failed at its port let the follower go and dropped the client unclosed. The reviewer counted
descriptors over three such starts: 44 before, 53 after and a second later, 44 again once a collection was
forced, where the commit before stayed level throughout.

A follower that is let go before it has dialled now closes its client. Nothing was said with it and nothing is
owed through it. One that has dialled keeps its client, as before: the replies it still owes are sent with it,
the one to a call that asked for the stop among them.

The test that asks three times for a port that is taken, and counts descriptors before and after, asked as an
instance alone. It now asks as each of the three kinds of instance.

## A test that hung on what it guards

The test of the order a follower starts in waited for the port outside the block that releases what it holds.
With the dial moved ahead of the port, which is the order the test is there to refuse, the wait for the port
timed out, the port lookup threw on nothing, and the start stayed held on a dial nobody released, holding what
a stop is made under while the fixture's stop waited for it.

The wait for the port and everything after it are now inside that block, each step a bounded wait that says
which step did not come, and the start is waited for on the way out.

## Removals

Three, each alone, against the same seven suites.

| Removed | Failing assertions |
|---|---|
| A follower that never dialled closing its client | 1 |
| A follower dialling once its port is open (it dialled before asking for it) | 3 |
| A follower taking up its claim before its port opens (it took it up when it dialled) | 3 |

The second is the reviewer's reordering. The run ended by itself, in the time the others took, with the test
of the order among its failures; the other two are the test of a dial held across a stop and the descriptor
count, since a follower that has dialled keeps its client. The third is from the entry before this, run again
because its test was rewritten. The other thirty of that entry's were not run again: the source has changed
since only in what a stop does with a follower's client.

## The suite

273 tests and 1,077 assertions, without a failure.

## Not observed

The descriptor count was read only by the suite, on one JDK and one operating system; where the JVM cannot
count its descriptors the test asserts nothing of them. Nothing was run between JVMs for this round, or across
a network.
