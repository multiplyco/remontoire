# 2026-11-23 — The base fixes reviewed: five findings, and what the earlier entry got wrong

A review of the three fixes in the entry before last (`08`) found five faults, four reproduced in isolated JVMs and
one traced through the source. All five are fixed. The suite is at 139 tests, 556 assertions.

*What `08` claimed that does not hold.* "A cancellation acts only when its id names exactly one record" was
offered as what keeps one client's cancellation off another's request. It does not: counting needs every request
the id could name to be on the books at once, and a notice that crosses its own request's response arrives after
that request has left them. "The books cover that window by construction" was said of a cancellation arriving
during the write; the books covered it and the delivery marks ignored them. "Every writer is guarded" was true of
writing to a session that is gone, and silent on writing to a different session by the same name.

*A late notice from an address stopped a neighbour's eval.* Two stateless clients on one machine, each with
request 9 outstanding; the first's answer is written and its record removed before its cancellation arrives; the
second is then alone under the id, and is cancelled. The cure is not a better count. An address is not a sender,
and the measurement in `09` showed there is nothing to rescue: on that wire the client cancels by closing its
stream and sends no notice. So work is now stopped only by a notice that arrives on a connection, where an id is
used once and the pair names one request or none. A notice with only an address stops nothing; it still records
what it could name as unread, since that errs toward announcing an outcome twice.

*A cancellation during the write still marked the result delivered.* Whether the request had been cancelled was
asked when the answer was built; the marks were run later, after the write. They now ask at the moment they are
run, for every kind of answer, a poll of a followed task included. A cancellation that arrives after the write is
left alone: the answer may have been read.

*`scratch!` could still leave a namespace behind.* The check that the session was live and the furnishing were two
steps, and a release could land between them. Furnishing a session's scratch and sweeping a session's family now
exclude each other, so a scratch is made before the sweep and taken by it, or not made. A second way in turned up
beside it: the eval entered its scratch by name, and `in-ns` creates what it does not find, so a scratch swept
between furnishing and entry came back bare. It is entered as the object now.

*An old eval wrote to a new session that had drawn its handle.* Handles were redrawn against live sessions only,
so one could be minted twice in a JVM's life, and everything an ended session left running — an eval about to
record where it ended, a task id in a client's hands, the registry entries it owns — spoke under the second
session's name. A handle is now redrawn against every handle minted since the JVM started. Three letters are
8000 handles, so the length grows by a letter each time half the current ones are spent, rather than leaving a
long-lived JVM drawing forever. Not covered: a handle released before this code was loaded, which no record
remembers.

*A request waiting across the reload could not be cancelled.* The table of requests in flight survives a reload,
and code up to 0.9.0 kept it as request id → thunk; the new lookup did not find those. A notice on a connection
that names nothing in the new shape now falls back to the old one, and cancels as that code would have. The
withheld marks are not carried across: such a request's answer is marked delivered as before.

*On the tests.* Five were added and three of the earlier ones changed. Each fix was taken out in turn, the others left in,
and the suite run: without the check at the marks, 3 assertions fail, and 2 in the transport test that holds a
real write open while the notice arrives; with an address counted as a sender, 2; with handles redrawn against
live sessions only, 3; with the scratch entered by name, 2; without the old table shape, 1. For the furnishing
race a first test ran a furnishing against a release a hundred times, and passed against the unguarded code twice
running; it was replaced by one that holds a furnishing open and observes the release wait, which fails there, 2
assertions.

Not tried: any of this through a real client. `09` measured what the stateless client does; the legacy client's
notice was last measured on 2026-08-04.
