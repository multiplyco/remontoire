# 2026-11-25 — Order at admission

The finding set aside two rounds of review ago: a cancellation forwarded to a node could be acted on before
the request it names was cancellable there, and stop nothing. It was recorded as the gateway adding a hop to a
race an instance alone has too. Read again, the hop had no need to add anything. An attachment's stream
delivers envelopes in order; the order was lost at either end of it, in two places.

**At the gateway.** A forwarded request was put among the requests in flight, which is where a cancellation is
routed from, and then put in the attachment's queue. A cancellation arriving between the two found its request,
was routed after it, and could be queued ahead of it. Both are now done holding the queue, so a cancellation
that finds a request in flight queues behind it.

**At the follower.** Each envelope was handed to a thread of its own, and a request was registered for
cancellation on that thread, by the transport's handler. Two envelopes read in order could be registered in
either. The follower now registers a legacy-wire request on the thread that reads the stream, with the base's
own `admit`, and the handler finds the records on the exchange and takes them over where it would have made its
own; the follower lets them go when the request has been served. The base already acted on a stop that found a
request registered and not yet waiting. Nothing is ordered but the registration: the evals run as before.

The stateless wire has no notice to order — a client cancels by closing its stream — and is left as it was.

What remains is a notice that reaches the gateway before the gateway has taken its request, on a socket of its
own. That is the case an instance alone has, and stays as §5 has it.

## Tests

At the follower, a request and then its cancellation are sent down the stream, and the request's thread is
kept from starting until the notice's has run to its end: the eval is cancelled all the same, and once the
request is answered no notice names it any more. At the gateway, a cancellation is posted the moment its
request appears among those in flight, from a watch on that table: the node is sent the request first.

The gateway's, the follower's, the lifecycle and the session tests, 96 of them, against each removal alone:

| Removed | Failing assertions |
|---|---|
| The follower registering a request on the thread that reads the stream | 3 |
| The handler taking over the records made for it (it makes its own: the pair names two requests) | 3 |
| The gateway queueing a request in one step with putting it in flight | 1 |
| The follower letting the records go when the request is served | 1 |

The third rests on a pause of 300 ms in the test, in which an unheld cancellation is routed and queued. On a
machine slow enough to miss it the removal would pass unnoticed; the test itself cannot fail for it.
