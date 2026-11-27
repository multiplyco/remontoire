# 2026-11-25 — A run, and a bind

The reviewer went over the entry before this one and found two things in what it fixed. Both are fixed here,
each with a test that fails without its fix. The counts are of the gateway's, the follower's, the lifecycle and
the session tests, 83 of them, run against each removal alone.

## A bind that fails

The entry before made the server unbound, gave it its doors and bound it last, so that a door that could not be
opened would not keep the port. An unbound server is already a server: it has a timer thread and open
descriptors, and when the bind then failed nothing gave them back. The reviewer counted three threads and
twelve descriptors after three starts against a taken port, there after a collection too, and found that
stopping such a server cleared the timers and not the descriptors.

The doors are now looked at before there is a server, and the server is made bound, as it was two entries ago.
That was not the whole of it. Measured in a fresh JVM on JDK 27, three failed binds through the server's own
bound constructor left three descriptors open, there after five collections: 42 before, 45 after. The
constructor opens its channel, fails to bind it, and closes nothing. Three failed binds of a plain
`ServerSocket` left none. So the port is asked of a plain socket first, which is closed again at once, and the
server is made only when that bound. A port taken in between is the server's to refuse, at a descriptor's cost.

The test counts the servers' timer threads, and the JVM's open descriptors where it can count them, across
three starts against a taken port; then the timers across a start that succeeds, so that the count is known to
be of something. Made unbound again: 2 failing assertions, the threads and the descriptors. The port asked of
the server alone: 1, the descriptors. The doors not looked at first: 1 error, the retry refused its port, and 1
failure in the count of timers, which is that test's bound and unstarted server showing up in the other.

## A request the next gateway carried

The entry before gave a gateway's server its router, so that a request accepted before a stop is still routed
after it. The router read the one roster the process has. With the gateway stopped and started again in the
same JVM, and a follower of the same name attached to the second, a request the first had accepted and not yet
routed was carried to that follower.

A gateway from its start to its stop is now a run, with an id. A node attaches to a run, and its entry says
which. What `start!` returns for the transport is that run's router and that run's door for followers: the
router carries a request only to a node of its own run, and an attach is admitted, under the lock it was always
admitted under, only while its run is the current one. The role's two handlers are private again; the entry
point knows only that a gateway's start returns what its transport is started with.

A node carried to whichever run it attached to: 2 failing assertions, the request never answered and the
second gateway's follower sent it. An attach admitted whichever run it was made to: 2 failing assertions, the
attach answered 200 and the node listed.

## A threshold, not a deadline

The entry before said an answer is given three opening beats to become a welcome. The watch that enforces it
wakes once a beat, and resetting the cadence does not wake one already asleep on the five seconds of the
attachment just lost. The reviewer measured 3.99 s at the default timings. §15 and the docstring now say what
is compared, and that the drop comes when the watch next looks.
