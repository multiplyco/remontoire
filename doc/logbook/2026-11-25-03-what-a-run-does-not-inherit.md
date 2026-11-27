# 2026-11-25 — What a run does not inherit

The reviewer went over the entry before this one and found two more ways for a gateway stopped and started
again in one JVM to act on what the first run accepted. Both are fixed here. The counts are of the gateway's,
the follower's, the lifecycle and the session tests, 86 of them, run against each removal alone, and against
two pairs.

## An attach that challenged the next run's follower

Admission answered nil both when the name was held and when the run had ended, and nil sent the attach on to
challenge whoever held the name. An attach made to the first run pinged the second run's follower of the same
name; one that missed the ping was dropped, and the stale attach was then refused as it should have been.

Admission now answers three ways, and asks whether the run has ended before it asks whether the name is free.
The holder that may be challenged is looked up as a holder of the run the attach was made to. The test attaches
a follower that answers no ping to the second run, then makes the first run's attach under its name and under
a free one: both refused 503, the follower not asked, and still attached.

- The name asked before the run: 1 failure, the attach refused 409.
- Any holder of the name challenged: no failure. With the run asked first, nothing reaches the challenge for
  that to matter; what it covers is a run that ends between the verdict and the lookup, which no test holds
  open.
- Both together, which is the code as the reviewer found it: 2 failures, the follower pinged and dropped.

## A request served by the next run, as itself

The run check of the entry before was made where a node's entry is chosen. A request that named no node the
old way never got there: a `create-session` naming `second`, accepted by a gateway called `first` and held,
was read against the name the JVM went by when its turn came. Restarted as `second` in between, the gateway
took the request for its own and minted a session.

Two changes. A run now carries the name it started under, and a `node` argument is read against that. And a
request whose run has ended is refused whole before anything is decided about it, whatever it names, a
session of this JVM's included: a stopped gateway serves nothing it accepted. That is stricter than an instance
alone, which runs a request it had accepted when it was stopped.

- A request dispatched whether or not its run has ended: 1 failure, a session with no `node` minted after the
  stop.
- A node named against what the JVM goes by now: 1 failure, in the test that asks the classification directly.
  The request held across the restart is refused either way, by the check above.
- A node carried to whichever run it attached to, the entry before's fix: no failure alone any more, for the
  same reason. With the first of these as well: 3 failures, the second run's follower sent the request.

Two of the guards, then, are caught only in a pair: each covers the interval after the check in front of it,
and the suite has no way to hold a request there.

## A qualification

§15 said a start that fails leaves nothing. A port that is taken costs nothing; one that another process takes
between the probe and the server's bind costs one descriptor on JDK 27, as the entry before had it. The
doctrine now says so.
