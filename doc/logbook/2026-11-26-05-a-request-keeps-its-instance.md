# 2026-11-26 — A request keeps its instance, and an entry is lapsed once

Fourth round of the day's review, of the commit before this. The three races of the round before were probed
again and held: a registration answered either way after a stop mints nothing, a dial held across a stop never
reaches the gateway, and a late eval's result goes under pressure. Three more findings, and a correction to
what §15 says of how long a lost node goes unnoticed.

## A session opened for the wrong gateway

Between two JVMs, by one ordinary post of two calls for a follower, nothing held. The first call stopped the
follower and started a gateway in its JVM; the second asked for a session, and was answered with one, on the
node the new gateway named itself. The gateway the client was connected to had no route for it and refused
the next call.

The round before made a mint ask one claim at every draw. It still read that claim when the tool ran, which in
a batch is after whatever the calls ahead of it did.

What the JVM is to a fleet is now read when a request arrives, before anything of it is, and is kept with the
request: every call in it that opens a session mints on those terms. A follower says of each request it takes
off its gateway that it is that follower's, so the reading is not the JVM's at all there. A request is
answered by the instance that accepted it, or fails: a follower that has stopped makes no session, and a
gateway's run that has ended gives nobody one, both from before.

A mint that no request stands behind reads the hooks when it is made, as before.

Three tests. A follower is sent one post of two calls: the first stops it and installs hooks that would give
out anything, the second asks for a session. The first is answered, the second is refused, and the JVM holds
the one session it held. An instance alone is sent one post of two calls over its own port: the first
installs hooks that refuse everything, the second asks for a session and gets one, where a mint made
afterwards is refused. And a follower still running, with such hooks installed over its own, is sent a
request for a session and asks its gateway for the handle all the same.

## A reply replaced by an eviction

The same late eval as in the round before: its session ended as it was admitted, so its result was owed to
nobody. It returned its value, other admissions evicted the entry, and the handler, which had waited on the
task all along, then looked the entry up to render its answer. The client was told the entry was evicted, as
an error, in place of the value.

The eviction is right. The handler now keeps what it admitted, with the id it was given, and renders from
that; the call's own answer is not a lookup. The same holds for the answer that says an eval became a task,
though an entry still running is never evicted.

One test, at a capacity of one: an eval whose session ends as it is admitted, and three results of a live
session admitted and read between the eval's end and its answer. The entry is gone when the call answers,
and the call answers with the value, not as an error.

## Lapsed twice

The round before had admission lapse the claim of an entry whose session is gone, and noted that when the
sweep of the session's end also reaches the entry, it is lapsed twice, "a moment apart, on an entry nobody
has read". That was wrong about the moment. Between the two a live session can read a result of its own, and
the second lapse then stamps the dead session's entry newer than it: at a capacity of one, the next admission
evicted what the living had just read and kept what nobody was left to read. The reviewer saw it in both
orders, the sweep last and the admission's look last.

A lapse now delivers an entry only when it had not been delivered, and marks it told. One already delivered
— by a reading, or by an earlier lapse — stays where it was among the read. That makes a lapse of one entry
the same done once or twice, by either path, and it also means the end of a session no longer makes the
results it had read newer than anything: a lapse is not a reading.

Two tests, one for each order, each at a capacity of one: the entry lapsed by its admission and then swept,
and the entry swept and then looked at by its admission, a live session reading a result between the two.
After one more admission the live session's result is there and the other is not.

## Removals

The gateway's, the follower's, the lifecycle, the session, the registry, the evaluation and now the
transport's tests, against each removal alone, on this tree. The first nineteen are those of the entries
before this.

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
| A session's claims lapsing only for the call that took its row | 0 |
| `end!` saying a session went only when it was there | 1 |
| A stopped follower's claim making no session | 4 |
| A follower's sessions being read only by one that has not stopped | 1 |
| A mint asking the claim it began with at every draw | 5 |
| A dial being begun under what a stop is made under | 1 |
| A dial being begun only by a follower that has not stopped | 1 |
| Admission looking at the entry's session | 6, and 2 errors |
| A session being minted on the terms its request was accepted on (the handler read the hooks itself) | 3 |
| A follower saying whose request it serves | 1 |
| The transport reading what the JVM is when a request arrives (it passed nothing on) | 1 |
| An eval being answered from what it admitted (it looked the entry up) | 2 |
| A lapse leaving a delivered entry where it was | 2, and 2 errors |

Three of these need a word.

The twelfth fails nothing now, where it failed three and an error before. A session's claims lapsing twice
is harmless once a lapse of one entry is: the two tests that saw it pass either way. The rule is kept, since
a second sweep has nothing to do, but nothing holds it in place any more.

The thirteenth failed nothing at first, for the same reason: what told `end!`'s answer apart was the second
sweep it let through. The session's tests now ask it directly, and a session dropped twice is said to have
gone once.

And the removal of a follower saying whose request it serves failed nothing at first. The transport reads the
JVM's hooks when a request arrives, and while a follower runs those are its own, so the two tests of the
batch passed either way. The third test above is for it. The other counts are from the run before those two
tests were added.

## The suite

266 tests and 1,037 assertions, without a failure.

## Between two JVMs

The reviewer's post, sent through the dev gateway to a follower beside it: two calls for the follower, the
first stopping its instance and starting a gateway in its JVM under another name and port, the second asking
for a session on the follower. The first was answered with the new gateway's address. The second was refused:
the gateway did not confirm the session, nothing was opened, try again. Asked at its own port afterwards, the
new gateway held the session the first call had been made in and the one opened to ask it, and no other.

## Corrected

The entry before this had §15 say a client waits between ten and twenty seconds on a node that died without
closing its socket, and called the two trials' 10 s and 14 s "both inside it". Ten to twenty seconds is how
long the gateway is expected to take to notice the loss of a follower that was beating regularly, as long as
its own writes to that follower return; it was not measured as such. What a client waits runs from when its
call arrives. In the first trial the call was made 3 s into the partition and waited 10 s; in this morning's
it was running 1 s before the partition and waited 14 s. From the loss, that is 13 s and 14 s. §15 now says
the one as expected and the other as measured.

## Deferred

The goodbye, still.

## Not observed

The second and third findings were not repeated outside the suite: no eval was held at its admission in a
running server, and no entry was lapsed twice in one. The post between two JVMs was sent once, on the legacy
wire. Nothing here was run across a network.
