# 2026-11-26 — The welcome, split, and a stopped follower

Second round of the day's review. The reviewer repeated its three reproductions against the fixes of the
entry before this, and all three held; it also dropped a real acknowledgement behind a flushed result, where
yesterday's trial had answered 500 in its place, and saw the client given the value once and the follower
keep it unread. It then decided the second wire trial, set a condition on it, and found two more paths by
which a follower that has stopped acts on the JVM's sessions.

## The split

The welcome stays in the head of the attach's response, and now holds only what is of a size: the wire's
revision, the attachment's id, the cadence. What the follower must drop was in it too, a list as long as the
fleet made it, under the 393,216 bytes the JDK's client takes of headers and under whatever a proxy between
two instances takes. It rides the stream instead, as the `retire` events a follower already acted on: the
gateway writes one for each handle straight after the head, before it reads its queue.

Before the queue, because a request can be in it by then: the node is attached, and routed to, from the
moment its name is taken, which is before its head is written. The stream is read in order, so a follower
drops what it must before it serves anything the attachment brings. Nothing came back of the attach answered
and not yet welcomed: the head alone makes the attachment.

The follower's first report is not ordered against those events, and it can go out first, still naming the
handle. The gateway then asks again in its answer.

## Ended twice

Which was the reviewer's condition: a second retirement must do nothing. It did something. Ending a session
lapsed its claims and then dropped its row, whether or not there was a row, and lapsing a claim touches the
entry; so the second asking made every result the session had left newer than anything read since, and at a
capacity of one the reviewer saw it evict a result a live session had just read.

A session is now ended by the one call that takes its row, and its claims lapse for that call alone. The row
goes first, as it does when a session expires — where the same thing held, a session that had ended some
other way since it was listed being reported as expired and lapsed again. `session/drop!` says whether there
was a session to forget.

## A stopped follower, twice more

**A registration answered late.** A mint asks the fleet through a function read from the hook, and a
follower's asked the gateway and then made the session, whatever had become of the follower meanwhile. The
reviewer held one at the registration, stopped the follower, started a gateway in the same JVM, which adopted
that handle from another node, and released the registration with a yes: the handle came back as this JVM's
own. The claim is now that of the follower that installed it, and makes the session under what a stop is made
under: a follower that has stopped makes none, and the mint fails saying so.

**A report made late.** A report read the JVM's sessions when it came to be sent. One scheduled by a welcome
and held, with the follower stopped and a session minted meanwhile, named that session under the attachment
the follower had let go; a gateway still holding the attachment retired another node's handle of the same
name. What a follower says of its sessions is now read under what a stop is made under, by a follower that has
not stopped; the sending is outside it. An attach reads them the same way, having had the same gap between
its look at whether to dial and its reading of what to report.

## Tests

- The gateway's: an attach held where its head has gone out and nothing since, a request queued for the node
  meanwhile; released, the node is sent the welcome, what it must drop, and then the request.
- The registry's: at a capacity of one, a session with a result it read is ended, a live session reads a
  result, the first is ended again, and an admission follows; the live session's result is there and the
  other is not.
- The follower's, both orders of the condition: a handle retired by the answer to a report and then by an
  event, and another by an event and then by the answer to a report. When each retirement is over, the tick
  on the result the session left is noted, and is the same after the second as after the first.
- The follower's, of a stop: a mint held at its registration fails once the follower has stopped and the
  gateway said yes, and leaves no session; a report held before it has read anything, and an attach held
  before it dials, each say nothing of a session opened after the stop; and a welcome handed to a follower
  that has stopped is not taken.

Two of yesterday's tests changed with the split. The scripted gateway sends what to drop as events, so the
test of a handle retired at the attach waits for a report without it and no longer takes the first; and the
gateway's tests read what a node was told to drop from the events behind its welcome.

## Removals

The gateway's, the follower's, the lifecycle, the session, the registry and the evaluation tests, against
each removal alone. The first nine are those of the entry before this, run again on this tree.

| Removed | Failing assertions |
|---|---|
| An ended run refusing a mint | 3 |
| The lock a stop shares with what a gateway asks of a follower | 2 |
| The look at whether the follower has stopped, under that lock | 6 |
| A retirement being made under it | 3 |
| A welcome being taken under it | 2 |
| The follower saying a part answers | 6 |
| The gateway counting what it is told | 2 |
| The transport saying which write answers | 6 |
| What a part says being the same word for every request (it was made to vary with the response's length) | 1 |
| The gateway writing what a node must drop ahead of its queue (it was queued instead) | 1 |
| The gateway telling a node what to drop at its attach | 5 |
| A session's claims lapsing only for the call that took its row | 3, and 1 error |
| `end!` saying a session went only when it was there | 3, and 1 error |
| A stopped follower's claim making no session | 2 |
| A follower's sessions being read only by one that has not stopped | 2 |

Two of these were run twice. The welcome's own look failed nothing at first: with reports and retirements each
refusing a stopped follower, all a late welcome still did was mark a stopped follower attached and say so on
the console, which no test saw. A test now hands a stopped follower a welcome and sees it refused. And the
removal of the write ahead of the queue failed a second test the first time, of the follower's, which cannot
see the gateway's code: the scripted gateway recorded a message as heard before it recorded its number, and a
test that had heard the last message read the numbers at once. The two are now recorded in the other order,
and the follower's tests were run six times over without a failure. The other counts are from the run before
those two changes.

## The suite

256 tests and 1,004 assertions, without a failure.

## Between two JVMs

A follower beside the dev gateway, two sessions opened on it through the gateway, a result read in the first.
The gateway's JVM was then given a session row by the first one's name, by hand, and the follower's stream
ended from the gateway, so that it dialled again holding a handle the gateway held too. What the gateway wrote
to the new attachment, heartbeats aside, was the comment behind the head and one `retire` event naming the
handle, in that order. Afterwards the handle was among the gateway's retired and routed nowhere, the session
was live on neither JVM, the follower reported one session, and a call naming the retired one was refused as
retired. On the follower, the result that session had left bore tick 3; its retirement asked for again, by
calling what a report's answer calls, left it at 3. The other session went on serving.

## Not observed

The reviewer's reproductions of the two new findings were not repeated: no gateway was started in the JVM of
a follower that had stopped, and no report was held across a stop outside the suite. Between two JVMs the
second asking was made by hand and not by a gateway's answer, and only in the one order. Nothing was run
across a network, and the trial
between two machines is still owed.
