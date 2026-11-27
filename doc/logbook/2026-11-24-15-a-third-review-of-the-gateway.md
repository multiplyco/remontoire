# 2026-11-24 — A third review of the gateway

A review of the gateway branch as it stood after the owed work (the entry before this one) found twelve things.
The suite had passed for the reviewer too: each finding came from an isolated check, with a fault injected or
an interval held open. All twelve were confirmed by reading the code. Eleven are fixed here, each with a test
that fails without its fix; one is left open, and why is at the end. The counts are of the gateway's, the
follower's, the lifecycle and the session tests, 74 of them, run against each removal alone.

## Who is speaking

- **A name is not an attachment.** A beat or a registration was matched to a node by name, and its number
  compared with the last from that name. Each JVM counts from zero, so a beat the old holder of a name sent on
  its way out — numbered 81, say — was taken for news from its successor, replaced the successor's routes with
  the old JVM's, and made the successor's own next beats stale. The welcome now gives each attachment an id,
  and everything a follower says of its sessions names it; from any other attachment it is answered 410. Taken
  from any holder of the name: 5 failing assertions. A follower not saying its attachment: 2.
- **A name is not a holder either.** A retirement was on record until every holder had reported without the
  handle, and holders were names. Another JVM attaching under the name of one that slept reported no sessions,
  which ended the record, and the sleeper was given the handle back when it woke. A retired handle now stays
  retired for as long as the gateway runs, and nothing is tracked of who dropped it. The same record ending
  when its reporter reports without the handle: 1 failure. Forgotten with the nodes that went away: 1.
- **A retired handle is refused by the gateway.** Past the startup grace a request naming one fell through to
  the gateway's own JVM, where a task method could still find a result retained under the handle — possibly the
  other conversation's. The gateway now answers it itself, before anything is looked up. The first test of this
  passed with the fix removed: it asked within the startup grace, when any unknown handle is refused. It now
  asks past the grace, and fails without the fix (1).

## The edges of an attachment

- **A gateway routes for as long as its port is open.** The port opened before the router was in place, and the
  router came away before the port closed. A `create-session` naming a node in either interval was served by
  the gateway's own JVM, and answered as created. The router now goes in before the port opens and comes out
  after it closes, and `/gateway` is opened by the transport with its own doors. Either order put back: 1
  failure each.
- **An attach that could not be answered.** A failure writing the response headers came after the name was
  taken and before the block that gives it up, leaving a node listed as attached with no stream. Put back: 1
  failure.
- **An attach answered and never welcomed.** The follower's wait for an answer ended with the headers, and its
  watch for silence began with the welcome. Silence is now watched for from the answer on. The test had to be
  told one thing about the JDK's server: it keeps the headers back until there is a byte to send with them, so
  a gateway that only holds the response open is covered by the wait for an answer already. Watched only once
  welcomed: 1 failure.
- **A welcome names its wire.** A gateway refuses an attach in another revision; a follower took any welcome.
  It now takes none that names another revision or none. Taken regardless: 2 failing assertions.
- **An answer given up late.** A follower that got no word on an abort dropped whatever attachment was current,
  which by then could be a fresh and healthy one. It drops the one the request arrived on. Put back: 1 failure.

## Answers

- **A lost part on the legacy wire.** A part that fails ended the answer for the handler, and the gateway was
  told when the handler closed the exchange. On the legacy wire a failed write cancels nothing, so that was
  when the eval finished: the client waited on a dead stream for the eval's whole deadline. The abort is now
  said when the part fails. Said only at the close: 1 failure, with the eval held open throughout.
- **A stream cut short under a batch.** The gateway's last message answered the first request and dropped the
  batch's shape. It answers every request still owed one, as a batch when asked as one. Put back: 3 failing
  assertions.
- **A fetched body left open.** An upload refused before its body was read left the fetch from the gateway
  open, and the gateway writing into it. The fetch is closed with its request. Left open: 1 failure.
- **A malformed stateless request.** It was routed before its headers were checked, so one naming an absent
  node got a routing refusal with a 200 where the same request served here got the binding's 400. Checked
  first now. Put back: 1 failure.
- **Connections on a follower.** A follower adopts the connection ids the gateway forwards and never answers a
  handshake, which is where silent connections were swept; its table only grew. An adoption sweeps too. Put
  back: 1 failure.

## Between two JVMs

A gateway and a follower as separate processes on one machine, since the suite only ever plays one side
against a script and the attachment id is new on both. A session opened on the node through the gateway
evaluated there; one opened on the follower directly was routed through the gateway; the roster counted both.
A beat naming another attachment, and one naming none, were each answered 410, and the node's sessions were
still routed after its next real beat. A malformed stateless request naming an absent node was answered 400.
An event stream and an upload went through. The gateway was killed and started again: the follower served a
new direct session meanwhile, attached afresh, and both the old session and the new one were routed. The
faults themselves were not injected between the JVMs; those are the suite's.

## Left open

**A cancellation that overtakes its request.** The gateway carries a notice to the node its request went to,
after the request, and the node serves each envelope on a thread of its own. With the request held just before
it is admitted, the notice finds nothing to stop and the eval completes. The reviewer showed this with the
interval held open. The same holds on an instance alone: a request and a notice are two posts served on two
threads, and a notice acted on first stops nothing, because no record is kept of a stop that came early (§5).
The gateway adds a hop on which the same can happen, and nothing of a new kind. Closing it on the follower
alone would mean the transport telling the follower when a request is admitted; closing it for both would mean
the base remembering an early stop. Neither is done here.
