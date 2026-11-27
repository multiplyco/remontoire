# 2026-11-26 — Three findings from review

The review of yesterday's four changes kept all four and found three things to fix. Each was read against the
code before anything was changed, and each was there.

## A mint held across a stop

`session/mint!` draws a handle and asks the fleet for it through a function it has read from the hook. On a
gateway that function is the run's, and it looked at the run's routes under the run's lock and then made the
session — in the session table, which is the JVM's. A mint that had read the function when its gateway was
stopped and started again was answered by a run that had ended: a handle the new run routed to a follower was
free in the old run's routes, and was given out as a session of the gateway's own. The reviewer held a mint
there and saw it.

A run that has ended now gives nobody a handle, and its claim throws what a request to an ended run is
answered: the look at `:ended?` is under the lock a stop sets it under, so a mint is finished before the stop
or refused after it. The test takes the claim of a run, stops and starts the gateway, attaches a node holding a
handle, and asks the old claim for that handle and for a free one.

## A welcome taken after a stop

A follower looked at whether it had stopped and then took the welcome, with nothing between the two. A stop
that fell there returned, and the welcome was then acted on: the session it retired was dropped, and a report
sent. The test added yesterday stopped the follower just before the look, which is the half that worked.

This was not the head's doing. With the welcome an event the same look came before it, and it comes before
every `retire` event and every answer to a report, which drop sessions on the gateway's word too. All three
are now done under what `stop!` is made under, by an instance that has not stopped: what a gateway asks of a
follower's sessions is done when the stop returns, or is never done.

Three tests. A stop made with the welcome read and not yet taken leaves the session it would have retired. A
stop made while a welcome's retirement is under way does not return until it is over, and returns to a session
that is gone; that one waits 300 ms to see the stop not return, which a correct build cannot fail. And a
report answered with a retirement by a gateway the follower let go in the meantime drops nothing.

## Ids in a header

A part named the requests it answered by their ids. Ids are the client's to choose, a string of any length
among them, and a batch has as many as it has calls; the request carries them in its body, and the follower's
part moved them into a header. The reviewer sent a batch of 32 calls with UUIDs for ids: answered whole at the
default size of request headers, refused whole through a gateway taking 1 KiB.

An event stream answers a request with one message, which carries every response the request is owed. So the
part that carries it says `X-Answers: all`, and the gateway, which knows what was asked, counts the request
answered or not. The set of ids the gateway kept is a flag, and what it tells a stream that was given up is
either a refusal to every request or nothing.

The test asks twice, once with a number for an id and once with 2,000 letters: one part says it answers, it
is the part the response is in, and it says so in the same word both times.

## Removals

The gateway's, the follower's, the lifecycle and the session tests, against each removal alone:

| Removed | Failing assertions |
|---|---|
| An ended run refusing a mint | 3 |
| The lock a stop shares with what a gateway asks of a follower | 2 |
| The look at whether the follower has stopped, under that lock | 3 |
| A retirement being made under it | 1 |
| A welcome being taken under it | 1 |
| The follower saying a part answers | 6 |
| The gateway counting what it is told | 2 |
| The transport saying which write answers | 6 |
| What a part says being the same word for every request (it was made to vary with the response's length) | 1 |

## The suite

249 tests and 979 assertions, without a failure. Three tests are new in the follower's and one in the
gateway's; the test of a part naming its responses became the test of a part saying it answers.

## Between two JVMs

A follower started beside the dev gateway, and a batch of 32 `eval` calls with UUIDs for ids posted to the
gateway on the legacy wire, for a session on the follower: 32 responses on one event stream, each id among
them, none an error. The answer came in four parts. One said it answered, with 94 bytes of headers to its
name; the most any part carried was 174, the one with the response's head. With the ids named, that part
alone would have carried some 1,250 more. Nothing was in flight afterwards.

## Not observed

The mint and the stop were not run between two JVMs after the fix, and the reviewer's reproductions were not
repeated: no mint was held inside `mint!`, no welcome held past a stop outside the suite, and no batch was
sent through a gateway with a small budget for headers.
