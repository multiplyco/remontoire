# 2026-11-26 — Two tests that could fail a sound tree

Seventh round of the day's review, of the commit before this. Its two findings were probed again and held:
three follower starts at a taken port left the descriptor count where it was without a collection being
forced, and the start order reversed fails its test, which ends by itself. Between two JVMs the reviewer also
had an eval stop its own follower and saw its reply reach the client through the gateway. Two findings, both
of tests. Nothing in the source changed.

## A count moved by something else

The test of a port that is taken counted the JVM's descriptors around three follower starts, to see that each
closed the client it would have dialled with. The reviewer ran it alone six times. On the second the count
went from 42 to 44 with all three clients terminated. Over the six runs all eighteen clients terminated on
that commit and none on the one before it: the count is the JVM's, and moves for reasons that are not a
client's.

The test now asks each of the three clients whether it has terminated. Descriptors are counted around the
starts of an instance alone, as they were before the entry before this, which is where a failed bind's own
cost shows. A gateway's three starts are asked for and refused, and no timer is left by any of the nine.

## A mark made after the next test had begun

A cancel of a followed task marks its entry delivered once the task has settled, which is after the cancel has
been answered. One test cancelled a running task as its last act and returned. The fixture of the next test
set the registry's ids back to 1, and the mark, made late, was made on whatever held that id by then: an entry
of another session, in another test. The reviewer held the mark until the next test had made its entry and saw
both of that test's assertions fail, the two a full run had failed with.

A test that cancels a running task now waits for the mark before it ends. There are three in that namespace:
the one found, one that slept 200 ms in the mark's place, and one that already waited, with a loop of its
own. Nothing outside that namespace cancels a task this way.

## Shown

The mark was held, for this only, until the entry under its id belonged to another session or a second had
passed. The cancelling test and one that asserts an entry of its own was not marked were then run alone, in
that order. A namespace's tests run in the order of their names, so the second was renamed for the run.

| | Failing assertions |
|---|---|
| The mark held, the test waiting for it | 0 |
| The mark held, the test not waiting | 2 |
| The mark held, the test waiting, the whole namespace | 0 |

Waited for, the mark was made on an entry of the session that cancelled. Not waited for, it was made on an
entry of another session, and the two failures were the next test's.

Two removals, each alone, against the same seven suites as the entries before this.

| Removed | Failing assertions |
|---|---|
| A follower that never dialled closing its client | 1 |
| A follower dialling once its port is open (it dialled before asking for it) | 3 |

Both as in the entry before this, in the same tests: the first fails where the clients are asked whether they
terminated.

The test of a port that is taken was then run alone six times, a JVM each: 14 assertions and no failure each
time.

## The suite

273 tests and 1,079 assertions, without a failure.

## Not observed

The count moving with every client terminated was not seen here, before the change or after it, and what
moved it is not known. The test was not run alone before the change. The late mark was not seen without
being held. The suite was run in its fixed order only. Nothing was run between JVMs for this round, or across
a network.
