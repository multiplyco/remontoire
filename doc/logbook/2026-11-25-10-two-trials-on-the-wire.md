# 2026-11-25 — Two trials on the wire

The two changes to the wire between followers and gateways that were owed as trials, each built to be judged.
The wire has no public revision, so neither costs one.

## A follower says what it sends

A gateway has to know which requests an event stream has answered: a stream given up part-way gets a last
message for the requests still owed one, and none for those that have theirs. It learned this by reading each
part of a node's answer back as JSON — `data: ` and then a message or a batch — which made one frame to a
write a rule the node's transport had to keep for the gateway's sake, and had the gateway parse every
heartbeat that passed through it.

Now the transport says which responses a write carries, on a tunnelled exchange, just ahead of that write; the
follower sends the ids with the part, in `X-Answers`; and the gateway counts them once the part is with the
client. It reads nothing of what it carries.

**The case it was to be judged by:** a result flushed to the client, and the gateway's acknowledgement of
that part lost on the way back to the follower.

- In the suite, the two halves. A scripted gateway answers 500 to the part that names a response: the follower
  gives the answer up and the result is owed still, untouched; acknowledged, it is marked read. A scripted node
  sends a response naming its request and then gives the answer up: the client has one message, the node's.
- Between two JVMs, the dev gateway and a follower started beside it from this checkout, through the client
  this session is. The gateway was made to answer 500, once, to the first part naming a response after writing
  it to its client. The eval's result arrived, once. The gateway composed no refusal and held no request
  afterwards. On the node the entry was owed and untouched, where the eval before it was marked read, and the
  next call's answer carried the note that it had settled.

A response split over two writes is counted with the write that says so, which the reading could not do.

What it cost: a header on one part of an answer, a function of six lines in the transport that knows a
tunnelled exchange from another, and a rule in place of the one removed — the transport says what a write answers on the thread
that then makes the write, with no other writer between, which is the discipline frames were already written
under.

| Removed | Failing assertions |
|---|---|
| The transport saying what a write answers | 5 |
| The follower passing it on with the part | 5 |
| The gateway counting what a part says it answers | 2 |

## The welcome in the head

The welcome — the wire's revision, the attachment's id, what the follower must drop, the cadence — was the
first event of the attach's stream. It is now `X-Welcome` in the head of the response, the same object.

What went with the event is the attach that is answered and not yet welcomed. A follower read a stream it was
not attached to, waiting; nothing the stream brought counted as hearing from the gateway until the welcome
did; a watch dropped the attach after three opening beats, 3.99 s at the default timings; and an event ahead
of the welcome was a refusal of its own. Now the answer is the welcome or it is nothing, and one that carries
none is dropped as it arrives and nothing is read from it.

Three things it did not deliver, or delivered at a price:

- **The opening cadence stays.** It was owed as the reason a follower opens every attach at a cadence of its
  own. That was one reason of two: a refusal is read from a body that may never end, under the same watch, and
  the watch needs a cadence while there is no attachment to give it one.
- **The head does not arrive by itself.** The JDK's server keeps a response's head back until there is a byte
  of body to send with it. Measured: a head whose first byte of body was written 1.5 s later was read by the
  client 1.65 s after the request. The gateway writes a comment behind the head.
- **A head has a size.** What the follower must drop rides the head with the rest. The JDK's client refused a
  response with 400,000 bytes of header and took one with 380,000; the limit it named is 393,216. At eight
  bytes a handle that is some 49,000 handles held on two instances at one attach, which is not a fleet anyone
  has, and is recorded as open in §15.

A follower that stops with the answer in hand takes no welcome. With the event that fell out of the body being
closed under it; with the head it is asked. The removal of that failed nothing at first, the test of a late
welcome being of an attach cancelled before it is answered, and a test now stops the follower with the welcome
read and not yet taken.

| Removed | Failing assertions |
|---|---|
| The gateway putting the welcome in the head | 31, and 1 error |
| The follower refusing an answer in another revision, or none | 2 |
| The follower refusing an answer that names no attachment | 2 |
| A stopped follower taking no welcome | 1 |

Between the two JVMs: the follower attached, kept the gateway's cadence of 5 s as the head gave it, and a
session opened on it through the gateway served evals.

## Both

The suite is 246 tests and 966 assertions, run twice without a failure.
