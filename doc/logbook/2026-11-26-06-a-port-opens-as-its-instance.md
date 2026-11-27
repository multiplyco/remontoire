# 2026-11-26 — A port opens as its instance

Fifth round of the day's review, of the commit before this. Its three findings were probed again and held: the
forwarded post of two calls refuses its second, an eval returns its value though its entry was evicted, and a
live session's result survives an entry lapsed by its admission and its session's end in either order. Three
more findings, all of how a session comes to be opened, and one more correction to a figure.

## A session opened by an instance that had stopped

The round before had a request keep what its JVM was to a fleet when it arrived. For an instance alone that
was nothing: no claim, so a handle was taken on nobody's word. One post of two calls to an instance alone, the
first stopping it and starting a gateway in its JVM with a follower attached: the second call drew a handle
the new gateway already routed to that follower, opened a session under it in the gateway's own JVM, and the
follower was told at its next beat to drop its own.

An instance alone now has a claim, as a gateway and a follower have. It gives out any handle while the
instance runs and makes no session once it has stopped, and the look and the making are one step with the
stop: a session being opened is opened before a stop returns, or is not opened. A request the instance
accepted and comes to serve after its stop fails to open a session, and says the endpoint it was sent to has
stopped.

## A request that arrived before the follower did

A follower's port opened before the follower existed. A request for a session that reached the port in
between was accepted as by an instance alone; held until the follower had attached and reported, it returned
a handle the gateway had never been asked for, and the next call under it through the gateway was refused.

The order is now the claim, then the port, then the dial. A follower is made, and its claim with it, before
its port opens, and it dials once the port answers, since what the gateway sends is served like anything that
arrives there. Until it has attached a session is opened as on a follower whose gateway is away, and reported
at the attach.

The round before had the transport read what the JVM is when a request arrives. It reads nothing now. A
server is started with what its instance is to a fleet, for all three kinds of instance, and every request it
accepts is given that: there is no moment at which a port answers as something its instance is not, and none
between a request being accepted and its instance being read. A mint that no request stands behind still
reads what is installed when it is made.

## A session said to be on another node

A session asked for on a follower through its gateway, the follower held just after the gateway's yes while
its JVM restarted its endpoint under another name: the client was told the session was on that name, and the
gateway routed it to the one asked for. The receipt read the JVM's name when it was written.

What an instance is to a fleet now carries the name it goes by beside its claim, and the receipt says that
one: the name the handle was asked for under.

## Removals

The same seven suites as the entry before this, against each removal alone, on this tree. The first
twenty-four are those of the entry before this; two of them are worded for what they are now, since a session
is minted on what its request was given, and the transport gives a request what its server was started with
where it used to read what was installed.

| Removed | Failing assertions |
|---|---|
| An ended run refusing a mint | 5 |
| The lock a stop shares with what a gateway asks of a follower | 3 |
| The look at whether the follower has stopped, under that lock | 13 |
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
| A stopped follower's claim making no session | 6 |
| A follower's sessions being read only by one that has not stopped | 1 |
| A mint asking the claim it began with at every draw | 15 |
| A dial being begun under what a stop is made under | 1 |
| A dial being begun only by a follower that has not stopped | 1 |
| Admission looking at the entry's session | 6, and 2 errors |
| A session being minted on what its request was given (the handler read what is installed) | 10 |
| A follower saying whose request it serves | 3 |
| The transport giving a request what its server was started with (it gave nothing) | 10 |
| An eval being answered from what it admitted | 2 |
| A lapse leaving a delivered entry where it was | 2, and 2 errors |
| An instance alone's claim ending with its stop | 5 |
| A session being opened by an instance alone under what its stop is made under | 1 |
| A follower taking up its claim before its port opens (it took it up when it dialled) | 3 |
| A follower's port being opened with its claim | 2 |
| A gateway's port being opened with its claim | 2 |
| An instance alone's port being opened with its claim | 3 |
| The receipt saying the name the handle was claimed under (it read the JVM's) | 2 |
| A follower's claim saying its name | 1 |
| A gateway's claim saying its name | 1 |

The twelfth fails nothing, as in the entry before this and for the reason given there.

Three tests were added for removals rather than for findings. A stop of an instance alone waits for a session
being opened on its claim. One post of two calls to a gateway, the first stopping it and starting another in
the JVM, opens no session for the second: it is the one test a gateway's port opened without its claim fails, since
what is installed is read in the claim's place and is the same until the instance changes. And a session
opened on a gateway is said to be on that gateway, which is the one test a gateway's claim without its name
fails. Neither removal was run without its test.

## The suite

273 tests and 1,067 assertions, without a failure.

## Between JVMs

Two JVMs on loopback, started for this and stopped after it, neither the dev gateway.

*An instance alone.* One post of two calls, the first stopping the instance and starting a gateway in its JVM
on another port, the second asking for a session. The post was answered 200 with an empty stream, its socket
closed by the stop. Asked at its own port, the gateway held the two sessions the JVM had before the post and
the one opened to ask it, and no other.

*A follower's port before its dial.* The second JVM, following that gateway, restarted its endpoint as the
same follower with its dial held for 3 s, and the first mint after that held until the follower was attached
and a second more. A request for a session sent to the follower's own port about a second after it opened was
answered 3.2 s later, with a session said to be on the follower. The gateway's roster then counted two
sessions on that node, and an eval through the gateway under the new handle returned its value.

*The name in the receipt.* A session asked for on the follower through the gateway, the follower held where
the session has just been made while its JVM stopped its endpoint and started one following the same gateway
under another name. The receipt named the follower that was asked. The JVM then attached under the new name
holding handles the gateway routed to the old one, and the gateway retired them, as it does a handle that
turns up on two instances: a name is not a holder.

## Corrected

The entry before this said of this morning's partition that the call "was running 1 s before the partition
and waited 14 s". It returned 14.0 s after the partition began, which is a wait of about 15 s. The 13 s and
14 s from the loss stand. §15 now says so.

## Deferred

The goodbye, still.

## Not observed

The first finding's collision was not staged: no follower held the handle the second call drew, in the suite
or between JVMs. What is shown is that the second call opens no session at all. The post to an instance alone
was sent once, on the legacy wire. The dev gateway was not used and was not reloaded: what a server is
started with has changed shape, so it takes a restart. Nothing here was run across a network.
