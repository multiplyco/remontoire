# 2026-11-26 — Three races more, and a figure corrected

Third round of the day's review, of the two commits that hold the wire trials, the fixes before this and the
trial across two machines. It kept the split, found three races, corrected a figure in §15, and advised
against the goodbye the trial had suggested.

## A registration refused after a stop

The entry before last made a follower's claim refuse to make a session once its follower had stopped, and
tested it with the gateway granting the handle. Refused, the claim answered no like any other no, and
`session/mint!` drew again — reading the hook again, as it did at every draw. The reviewer started a gateway
in the JVM meanwhile, and the second draw was granted by it: a session of the new gateway's for a request made
of the follower.

A mint now reads the claim once and asks it at every draw. A follower's, asked again after its stop, has no
attachment to ask for the handle and makes no session, and the mint fails. The test holds a mint at its
registration, stops the follower, installs a claim that would give out anything, and answers the registration
— once granted and once refused. Both fail the mint and leave no session.

## An attach begun after a stop

An attach read what it reports under what a stop is made under, and dialled outside it. The reviewer held one
between the two, let `remontoire/stop!` return, and released it: the request was sent, and a real gateway
admitted the stopped follower and routed to its session.

Reading the sessions, sending the request and publishing its future are now one step under what a stop is
made under. A stop that comes first is found, and nothing is sent; one that comes after finds the future and
cancels it. Sending does not wait on the network, and waiting for the answer is outside.

Two tests. One holds an attach where it has read its sessions and not yet sent — at the version it puts in
its message next — and sees a stop wait for it and return once it is released. The other, of the entry before
this, held an attach before it began and saw it report no session opened since the stop; it now also sees
that the gateway was not dialled at all, which is what it had not looked at.

## An eval admitted after its session ended

An eval is begun, and then registered. The reviewer ended the session between the two: the entry went in
owned by a session that was gone, after the one lapse its claims would ever get, and stayed unread and
unevictable through five further evals at a capacity of one.

Admission now looks at the entry's session once the entry is in, and lapses its claim when the session is
gone. The order carries it: a session ends by losing its row and then lapsing its claims, so an entry that is
in before the lapse is lapsed by it, and one that goes in after finds the row gone. When both happen the claim
is lapsed twice, a moment apart, on an entry nobody has read.

Two tests, at a capacity of one. An entry admitted for a session that has ended can be read by its id, and is
gone after three more results are admitted and read. And an eval whose session is ended as it is about to be
registered — the reviewer's case — is answered, and is gone after the same.

## Removals

The gateway's, the follower's, the lifecycle, the session, the registry and the evaluation tests, against
each removal alone, on this tree. The first fifteen are those of the entries before this.

| Removed | Failing assertions |
|---|---|
| An ended run refusing a mint | 3 |
| The lock a stop shares with what a gateway asks of a follower | 3 |
| The look at whether the follower has stopped, under that lock | 11 |
| A retirement being made under it | 3 |
| A welcome being taken under it | 2 |
| The follower saying a part answers | 6 |
| The gateway counting what it is told | 2 |
| The transport saying which write answers | 6 |
| What a part says being the same word for every request | 1 |
| The gateway writing what a node must drop ahead of its queue | 1 |
| The gateway telling a node what to drop at its attach | 5 |
| A session's claims lapsing only for the call that took its row | 3, and 1 error |
| `end!` saying a session went only when it was there | 3, and 1 error |
| A stopped follower's claim making no session | 4 |
| A follower's sessions being read only by one that has not stopped | 1 |
| A mint asking the claim it began with at every draw (it read the hook at each) | 2 |
| A dial being begun under what a stop is made under (it looked at the stop and took no lock) | 1 |
| A dial being begun only by a follower that has not stopped | 1 |
| Admission looking at the entry's session | 2 |

The last of these failed nothing the first time. Both tests of it read the late entry before they pressed on
the registry — one by its id, to show it could be, and one by taking an answer that delivered the result —
and reading an entry lifts its shield whoever it was kept for. The first now only looks the entry up, and the
second is answered while its eval runs, in a namespace that outlives the session, so that nothing is
delivered. The other counts are from the run before that correction, which changed those two tests alone.

## The suite

260 tests and 1,017 assertions, without a failure.

## Between two JVMs

A follower beside the dev gateway, on loopback, the gateway recording each node it gave up and how long that
node had gone unheard.

*What ends a stopped follower's attachment.* The follower's stop, called at its own address. The gateway gave
the node up 6.5 s after the stop, the node unheard for 6.6 s: far short of the silence it waits for, so by a
write that failed, the second after the follower closed its end. Across two machines this morning the same
took 14.8 s, which is longer than two writes take and suggests the close did not come through the tunnel as
one; that was not looked at there.

*An attach held and a stop.* The follower started again in the same JVM and held where it had read its
sessions and not yet sent, by the hold the test uses. A stop called then had not returned 500 ms later, and
returned once the hold was released. The gateway's roster, read ten times a second for the next 8 s, showed
the node as not attached throughout, and the gateway gave up no second attachment: the dial was cancelled
before the gateway admitted it.

## Corrected

§15 said a client waits up to two beats, ten seconds, on a node that died without closing its socket. The
gateway takes a node for gone when it has gone unheard for more than three beats, and looks when it next
writes, which is within a beat; counted from the node's last moment, which falls somewhere between two of its
beats, that is between ten and twenty seconds. The first trial across two machines measured 10 s and this
morning's 14.0 s, both inside it. §15 now says so.

The entry before this said of the follower's stop that the 14.8 s fitted "the quiet, two beats after the node
was last heard". Three beats, by the same reading.

## Deferred

A follower saying goodbye when it stops. Giving the node up at once on the gateway would give up the replies
it still owes, and the one to the call that asked for the stop is among them: in the trial that call was
answered. A goodbye has to let those finish and has to name the attachment it closes, and is left for when it
is wanted. §15 lists it under Open.

## Not observed

The reviewer's reproductions of the first and third findings were not repeated outside the suite: no gateway
was started in the JVM of a stopped follower with a registration held, and no eval was held at its admission
in a running server. A dial cancelled by a stop after its request has reached the gateway was not staged: the
gateway would admit the node and find it gone at a write, as it does for any follower that stops while its
attach is being answered. Nothing here was run across a network.
