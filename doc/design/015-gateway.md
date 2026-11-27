# 15. The gateway

Design sketch, 2026-11-23, recorded from the design conversation and the spike that ran beside it; built the same
day as `gateway.clj` and `follower.clj`, and tried between JVMs on one machine and then between two machines (see
the logbook). "As built" below says where the build settled what the sketch left open. Unreleased.

## The gap

One instance, one MCP entry. A fleet of development machines therefore costs every client one entry per machine, each
carrying its own copy of the tool set, and the fleet is not still: machines are added, restarted, put to sleep. A
client learns of a new one only by being reconfigured and reconnected.

**Decided: one instance acts as a gateway, and the others attach to it as followers.** A client holds one entry, the
gateway's. It lists the followers attached right now, opens a session on one by name, and its requests are routed
there and answered with that machine's answer. A machine that appears later is reachable without the client changing
anything. This adds to the direct connection (§4) and replaces nothing: an instance started without a gateway is
exactly what it is today.

## The session is the route

**Decided: routing is per session, not per call.** `create-session` on a gateway takes a `node` argument, and the
handle it returns routes everything after it. No other tool grows an argument. `tasks/get`, `tasks/update` and
`tasks/cancel` carry only a task id, which is `<handle>-<n>`, so the handle would have had to be the routing key
anyway; `/upload/<handle>/…` routes the same way.

- **Without `node`, the session is on the gateway's own JVM**, so a single-machine setup behaves as it does today.
  Forgetting the name then runs code on the wrong machine without an error, so the answer to `create-session` says
  which node the session landed on.
- **Both wires route (§11).** A session is a handle named in a tool call's arguments whichever MCP revision the
  client speaks, so the gateway routes a legacy client's calls exactly as a stateless one's. It answers
  `initialize` and `tools/list` itself, and passes what a client said at `initialize` along with a routed
  `create-session`, since the follower never saw the handshake.
- **The `node` argument exists only in a gateway's tool list.** A follower's and a standalone instance's
  `create-session` is unchanged.

## A follower is an ordinary instance that also attaches

**Decided: a follower keeps its own endpoint.** The first thought was the opposite — a follower dials out and exposes
nothing — and it was reversed for one reason: when the gateway is the thing that is broken, the direct REPL is how
anyone finds out what the follower is doing. A mode that removes it would have to be switched off, with a restart, at
exactly the wrong moment. Where the endpoint is bound stays `:bind`'s business; a follower in a container with no
published port still has one, reachable from inside.

**Decided: the follower dials the gateway, and redials for ever.** Followers are the ones that come and go, and may
not be addressable from the gateway at all; the gateway is the stable address. Redialling without end is what makes a
gateway restartable: take it down, bring it back, and the fleet reassembles.

## Transport between follower and gateway

**Decided: plain HTTP in both directions, on the classes the JDK ships.** The follower holds one request open on
the gateway whose event stream delivers request envelopes — the SSE shape this transport already writes for
progress. It answers with POSTs. The rule in `deps.edn` decides the choice: nothing that can conflict with the host.

- **One acknowledged POST per write, not one streamed body.** Each write a handler makes on the follower is sent as a
  POST of its own, and the gateway answers it only after writing those bytes to the waiting client. A write on the
  follower then succeeds or fails as it would on a direct connection, which is what delivery marks and hangup
  detection are built on (§5): the mark lands only after the write that carried the response, and a failed beat is
  how a departed client is noticed. A single POST with a streamed body was tried first and delivered each write two
  writes late (logbook, 2026-11-23).
- **The handlers do not change.** `HttpExchange` is an abstract class, so the follower hands the ordinary `/mcp` and
  `/upload` handlers a synthetic exchange built from the envelope. Attaching needs no second transport inside the
  library.
- **A large request body is fetched, not enveloped.** A JSON-RPC body rides the envelope; an upload's body stays at
  the gateway until the follower asks for it.
- **Considered and set aside.** A raw socket with framing of our own: one duplex connection per follower, at the price
  of a second port, a multiplexer, and keeping a large upload from stalling the streams beside it. WebSocket: the JDK
  has a client and no server, and `HttpServer` does not hand over its socket, so it is the raw-socket option with a
  standard handshake and the same multiplexer. A reverse tunnel, the follower dialling sockets the gateway then
  speaks HTTP over: the follower is untouched, and the gateway needs a hand-written HTTP client.
- **When to revisit.** Plain HTTP stays until complexity piles up because the transport is not ours to control —
  races, lost wakes, anything in that family. The two-writes-late finding was the first of the kind, and was answered
  inside HTTP.

## Names and liveness

**Decided: the name is the identity.** A follower names itself, by default with its hostname, and the name must be
unique among the fleet. A free name is welcome whoever claims it; a held name is a collision. There is no instance
identity beneath the name, because in a containerised fleet nothing beneath it is stable, and none is needed:

- **A claimant challenges the holder.** When a follower arrives for a held name, the gateway pings the holder and
  replaces it if it does not answer. Without this, a machine waking from sleep is refused its own name until its stale
  entry expires.
- **Whether a handle is still good is the follower's to answer.** The gateway routes a handle to whoever holds the
  name. A JVM that does not know the handle already says so — "minted before a restart" — so a machine that woke up
  keeps its sessions and a replacement refuses them, with no bookkeeping at the gateway.

A held connection makes a process's death known at once: the kernel closes the socket however the process ended, so a
shutdown hook adds only a reason. Sleep and a network partition say nothing, and only a ping catches those. The roster
therefore carries when each follower was last heard from, and a request forwarded to one that has gone quiet needs a
short timeout and an error that says the node is not answering.

## Handles

**Decided: five consonants, everywhere.** Three give 8,000 handles, which is sparse for one JVM with a handful of
sessions. Uniqueness never depended on the size — the minter redraws against live handles — but sparsity does: a stale
or mistyped handle should name nothing and be refused loudly (§11). Across a fleet there are more live sessions, and a
hit means running code on someone else's machine. With 100 live sessions, a wrong handle lands on one of them 1 time
in 80 at three letters, 1 in 1,600 at four, 1 in 32,000 at five. Everywhere, not only under a gateway, because a
follower mints handles too and they must look like the gateway's; the task-id pattern widens with it.

**Decided: a follower mints, then registers.** Minting is one path with two timings.

- **Attached:** the follower registers the fresh handle with the gateway before `create-session` answers. If the
  handle is taken it redraws and tries again; no client has seen the rejected handle, so nothing else is disturbed.
  Registration is atomic at the gateway — of two followers trying one handle at once, the first wins. A gateway
  naming a node in a routed `create-session` is the same act seen from the other side.
- **Detached:** the follower mints and answers, because a gateway outage is when a new direct session is most needed,
  and reports the handle at the next attach.
- **A registration that gets no answer** is given a short timeout, after which the follower treats itself as detached.

**Decided: a follower reports its live handles at every attach,** and the gateway answers with a verdict before any
request flows. A handle the gateway does not know, or already knows as this follower's, is adopted. This is also the
whole of a restarted gateway's recovery, so the gateway persists nothing.

**Decided: a handle found on two followers is retired on both.** It can only happen to a handle given to a client
before the gateway could check it — one minted while detached, or one the gateway forgot across a restart while its
follower was away.

- **Not a replacement id.** The client holds the handle in its context and would keep sending the old one, and the
  handle is baked into the scratch namespace's name, the task ids and the upload directory.
- **Not "the latecomer loses".** After a gateway restart the order of arrival is an accident, and whichever side
  loses, its client may be coming through the gateway — where its next call would be routed into the winner's session
  on another machine, with no error. Retiring both gives every affected client the existing refusal and a new
  session; results survive, since the registry is per JVM (§5), and the scratch namespace is what is lost.
- **It needs one message the other way:** the gateway telling an attached follower to drop a handle.

## A stateless gateway

Because routes are rebuilt from what followers report, a gateway can be replaced by another at the same address:
followers redial, attach and report. Handover — the new instance up before the old one goes — shortens the gap from a
JVM's boot to about one redial, and is not seamless:

- Followers move only when the old instance lets go of their connections, so the overlap buys a warm JVM, not a warm
  roster.
- Requests in flight are cut. To the follower that is a client hanging up, with what that means on each wire
  (§5): on the stateless wire a closed stream cancels an eval still being waited on, and on the legacy wire the
  eval runs on and its result is announced as unread on the next call. Work followed as a task is unaffected.
- The gateway's own sessions — those created without naming a node — are state like any JVM's, and go with it.

**Decided: a gateway that has just started does not refuse an unknown handle outright.** Until its followers have
reported, it cannot tell a dead handle from one it has not heard of yet, and "not live, create a new session" would
have a client abandon a healthy session. For a short while after starting — a few redial intervals — the answer is
"not attached yet, try again", or the request is held. This holds for a plain restart as much as for a handover.

A list of gateways for a follower to try in order was considered and judged more than is needed: a replacement at the
same address already serves, the client has one configured address and would not follow the fleet to a second, and
the direct endpoint covers an outage.

## The roster is data in the gateway's REPL

**Decided: listing and waiting are functions, not tools.** A gateway's sessions get an alias of their own beside
`__r` — working name `__r.gw` — absent everywhere else, so that followers and standalone instances carry no noise for
a role they do not have; the manual's passage on it is likewise a gateway's only. Listing the fleet is a call there.
Waiting for a node is a task that settles when a follower of that name attaches, so an eval that returns it is
followed like any long eval, by deadline or as a task the client is notified of (§11). That is what lets an agent
start a machine and then wait for its REPL.

## Riding along

- **Uploads.** A follower learns the address a client used from the forwarded `Host`, so `(__r/upload-url)` there
  names the gateway, which routes the `PUT` by its handle (§4).
- **Version skew.** A gateway advertises its own tool list on behalf of followers that may run another release. The
  attach should carry the follower's version so the roster can show it; "As built" says what is refused.
- **Trust.** No new boundary, as long as the gateway listens on the network the followers are on (§4). One address
  now reaches every REPL, and attaching is unauthenticated, so anything that can reach the gateway can take the name
  of a machine that is away and receive what is sent to it — less power than evaluating on every machine directly,
  which the same position already grants.

## As built

- **The wire.** Everything a follower says is under `/gateway` on the gateway's ordinary port. `attach` is a POST,
  not a GET, because it carries the follower's name, version and live handles; its response is the held event
  stream, and the head of that response is the welcome: `X-Welcome` carries the wire's revision, the
  attachment's id and the cadence. The verdict on what the follower reported follows on the stream, a `retire`
  event for each handle it must drop, ahead of anything queued for it. `answer/<rid>` carries one write,
  with the response's status and headers riding the first in `X-Head`, a word in `X-Answers` on the write that
  carries the request's responses, and a body of declared length ending with its last byte, so a plain JSON response is one
  POST. `body/<rid>` serves a request body that did not ride its envelope. `register`, `beat` and `pong` are small
  POSTs: a handle just drawn, liveness together with the sessions held, and the answer to a ping.
- **A name is held by an attachment, and what is said names the attachment.** The welcome gives each attachment
  an id, and a registration or a beat says which one it speaks for; from any but the one its name holds when the
  gateway acts on it, it is answered as from a node that is not attached. A name passes from one JVM to the next, each counting what it
  says from zero, and a beat the first sent on its way out once replaced the routes of the second.
- **A fetched body arrives in frames**, a length and that many bytes, a zero length last. An upload cut short on
  its way in to the gateway is found out when the response carrying it to the follower is already under way and can
  only end, so the end of the stream cannot mean the end of the body. A follower that reads no last frame stages
  nothing, and neither does one answered with anything but the body: the gateway's "no such request" was once
  stored as the file. A frame holds 64 KiB at most, and a follower takes none longer: a length is otherwise
  whatever four bytes say. The fetch is closed with the request it was made for, read to its end or not.
- **An answer is finished or given up, never left.** A part that fails on its way to the gateway ends the answer
  for the handler, as a failed write does, and the follower says so there and then: `answer/<rid>` with `X-End:
  abort`. Not when the handler closes the exchange — on the legacy wire a failed write cancels nothing (§5), so
  that is when the eval has its answer, and a client on a stream nothing more will be written to once waited out
  the eval's whole deadline. Only the gateway's word settles it — that it took the abort, that it holds no such
  request, or that the client is gone. An error or silence is neither, and is asked again; when its word never
  comes the follower drops the attachment the request arrived on, which ends everything it owed through it, and
  no other: by then it may be attached afresh. Before this a lost final part left the client waiting for as long
  as the node stayed attached, and after the first fix an abort answered with an error still did.
- **An end is not an answer.** The part that closes an event stream carries nothing but the end, so when it is
  lost it is said again as what it is and nothing is given up. And the gateway is told what a stream has
  carried: the part that carries the request's responses says so, and the request counts as answered once
  that part is with the client. A stream that was given up owing them gets a last message in the gateway's own
  words, answering each request, as a batch when they were asked as one; one that had delivered its responses
  gets nothing more. A client was once given a result and then a refusal for the same request. The gateway first
  learned what a stream had carried by reading the node's bytes back as JSON, a frame to a write, every
  heartbeat included: how a node frames what it writes was a rule the gateway depended on. The transport knows
  which write carries the responses and says so, the follower passes that on with the write, and a node's bytes
  are bytes to the gateway. It is said in one word, the same for every request: an event stream gives every
  response a request is owed in one message, and the gateway knows what was asked. The part first named the
  ids, which are the client's to choose and of any length, in a header, which has a size: in review, a batch of
  32 calls with UUIDs for ids was answered through a gateway taking the default size of headers and refused
  whole through one taking 1 KiB. When a result has reached the client and the gateway's word of it is lost on the
  way back, the gateway owes no second answer and gives none, and the follower keeps the result as one nobody
  read: the client is told of it again at its next call.
- **The seams in the library are three.** The transport asks a router before serving a request and serves it
  itself unless the router answered; `session/mint!` asks the fleet for each handle it draws, and the fleet makes
  the session, under its own lock, when it grants one; and the transport serves an exchange it is handed as it
  serves one from its own socket. Role and name live in a namespace of their own that requires nothing, so the
  tool list, the instructions, the manual and the scratch wiring vary by role without knowing how either role
  works.
- **A run owns what it routes by.** A run is a gateway from its start to its stop, and is made with tables of
  its own: its name, the roster and the routes, the requests in flight, the pings and the waits. The router
  and `/gateway` are what a run hands the transport to be started with, each holding that run, and they go with
  every request the server accepts for as long as one is being served. A gateway stopped and started again in
  one JVM is two runs, and a handler of the first holds nothing of the second: a node attaches to the run its
  attach was made to and is carried only that run's requests, a `node` argument is read against the name of
  the run that accepted it, and a welcome settles the waits of its own run. A run that has ended refuses
  whatever it had accepted, whole and whatever it named, and admits no attach; nothing of it is emptied, so a
  request found live just before the stop is placed by what the run knew. Whoever waits for a node is told
  when the gateway stops: the wait was that run's. And a mint asks the run it began under for its handle: one
  that has ended gives none, since the table a session is made in is the JVM's and the next run's to answer
  for.

  It was not built so. The router was first installed beside the server and taken away again, and a request
  accepted before a stop found no router when its turn came: a `create-session` naming a node was served by
  the gateway's own JVM, a session minted there as if nothing were amiss. Then the tables were the
  namespace's, with each run a mark on them, and every access by a handler of an earlier run had to be shown
  not to reach what belonged to the next. Each of these happened first: the first run's request carried to a
  follower of the second, a session minted in the second for a request naming it under a name the first did
  not have, the second's follower pinged and dropped by an attach made to the first, a retired handle looked
  up after a stop had emptied the retirements, a welcome written for one run ending a wait of the next
  with nothing, and a mint held across a stop and a start given, as a session of the gateway's own, a handle
  the second run routed to a follower.
- **A request stays with the attachment its handle was found on.** Where a request goes is read from its run's
  state once, and the same reading says which attachment is the node a handle is on: a name passes from one JVM
  to the next within a run, and a request for a handle found on the first was once queued for the second, which
  had reported no such session. An attachment that has gone since is found gone where the request is queued
  for it. And an attach tells those waiting for its name only while it is still that name's node, asked under
  the lock a node is forgotten under.
- **A start that fails leaves nothing.** What a role adds to the transport is not among a host's options, and
  its doors are looked at before there is a server. The server is made bound: one made unbound and bound
  afterwards held a timer thread and descriptors for good each time the port turned out to be taken. The JDK's
  server keeps one descriptor even so when the bind in its constructor fails, so the port is asked of a plain
  socket first, which keeps nothing when it is refused. Nothing, then, of a start against a port that is taken;
  a port another process takes between the two binds still costs that one descriptor (JDK 27).
- **A request is checked before it is routed.** What the stateless wire requires of a request's headers (§11) is
  asked of it before the router sees it, so a malformed request is answered the same wherever it would have gone.
- **A departed node keeps its routes.** A machine that sleeps still has its sessions, so a call to one is refused
  with that reason and the node's name, not with "create a new session". The routes are replaced by what the node
  reports when it returns, and forgotten if it stays away longer than a session survives silence.
- **Sessions are reported as state, with every beat.** A beat carries the handles its follower holds, and the
  gateway settles it with the function it settles an attach with: a handle nobody else holds is routed, one no
  longer reported is unrouted, one found on two holders is retired on both. No single message then has to arrive.
  A registration whose reply was lost, a session that ended, a handle given out while the gateway was away are
  each put right by the next beat, and there is no message for a session that ended. Before this the same
  handles were copied by four separate messages, each of which could be lost alone, and a review found four
  ways for the two sides to disagree for good.
- **What a follower says of its sessions is numbered, and read under one lock.** An attach, a registration and a
  beat each carry a number taken as the follower reads its sessions, and the gateway acts on one only when it is
  later than the last it acted on: one that arrives late — its reply lost, the follower gone on — would put back
  a state a later one replaced. On the follower a handle is asked for, and its session made, under the lock a
  beat is read and sent under, so that a beat never says "not held" of a handle the gateway has just granted.
- **Liveness runs both ways at one cadence**, five seconds. The gateway writes at least a comment to each attach
  stream, and a follower posts a beat. A follower unheard for three beats is detached, which is how sleep and a
  partition are noticed; a follower whose beat fails or whose stream goes quiet closes it and redials. An attach
  that gets no answer is given up after ten seconds, so a follower dialling into a network that drops packets keeps
  trying instead of waiting on one connection.
- **An attach is covered from end to end.** On the gateway, a name is given up however the attach that took it
  ends, a failure to answer it included: a node was once listed as attached with no stream to reach it by. On the
  follower, the answer to an attach is the welcome or it is nothing: one whose head does not name this revision
  and an attachment is dropped at once, nothing is read from it, and the gateway is dialled again. The welcome
  was first the stream's first event, which left an attach answered and not yet welcomed. A watch dropped it
  three opening beats later — measured at the default timings, 3.99 s from the answer to the drop — and nothing
  else the stream brought counted as hearing from the gateway meanwhile, since keepalives without a welcome had
  kept a follower on its first attempt for good. A gateway writes a byte behind the head, because the JDK's
  server keeps a head back until there is one: a head whose first byte of body came 1.5 s later was read
  1.65 s after the request. What rides the head is of a size, and what is not rides the stream: the handles a
  follower must drop were in the head with the rest, a list as long as the fleet made it, where the JDK's
  client takes 393,216 bytes of headers and whatever stands between two instances may take far fewer. They are
  written as events before the queue is read, since a request can be queued for a node by the time its welcome
  is written, and a stream is read in order. The gateway says them again in its answer to a report that still
  names the handle, and a follower's first report is not ordered against the events: a session ended twice is
  ended once, and its results are not made newer by the second asking than what the living have read since.
  The same is kept entry by entry, since an entry admitted as its session ends is lapsed both by its admission
  and by the sweep of that end, in either order: a lapse delivers what had not been delivered, and leaves what
  had been where it was among the read.
  A refusal is still read from a body under a watch, at the cadence a follower keeps
  while it is not attached, and that body is closed by a stop like a stream is, since a refusal that never ends
  is as easy to send as a stream. What a gateway asks of a follower's sessions — at a welcome, in a stream's
  event, in its answer to a report — is done under what a stop is made under, by an instance that has not
  stopped: it is done when the stop returns, or it is never done. A welcome was once taken after a stop had
  returned, and a session dropped by it. The same holds of what a follower says and takes for itself. The
  sessions are the JVM's, and a follower speaks for them only while it runs: what it reports of them, at an
  attach or a beat, is read under what a stop is made under and sent or not by what was found there, and the
  claim a mint asks is that of the follower that installed it, which makes no session once it has stopped. A
  mint asks the one claim it began with at every draw, since a handle refused is drawn again and whoever answers
  for the JVM's sessions by then was not asked for anything. An attach reads what it reports, sends its request
  and publishes the future a stop cancels in one step under what a stop is made under, so that a stop finds the
  dial and cancels it or is found by it; waiting for the answer is outside. And a request is served, to the
  end, as the instance that accepted it. A server is started for one instance and carries what that instance is
  to a fleet, its claim and the name it goes by, from before its port opens: a follower takes up its claim,
  then opens its port, then dials, and until it has attached a session is opened as on a follower whose gateway
  is away. One that is let go before it has dialled closes the client it would have dialled with, since a start
  that fails at its port is such a follower, and a client dropped unclosed kept its descriptors until it was
  collected: nine of them over three such starts. Every request a server accepts is given what it carries, a follower says the same of each request
  it takes off its gateway, and a session a call of that request opens is minted on those terms and said to be
  on that name, whatever the JVM has become or is called since. An instance alone has a claim like the others:
  it gives out any handle while the instance runs and makes no session once it has stopped, asked and taken in
  one step with the stop. A
  registration answered after a stop once gave out, as this JVM's own, a handle that a gateway started in the
  same JVM since had adopted from another node, and one refused after a stop was drawn again and granted by the
  gateway that had taken the follower's place; a report held across a stop once named a session opened
  after it, under the attachment the follower had let go; and an attach held between reading its sessions and
  dialling was sent after the stop had returned, and admitted. And one post of two calls for a follower, the
  first stopping it and starting a gateway in its JVM, had the second answered with a session of that gateway's,
  which the gateway the client had asked could not route. The same post made of an instance alone had the
  second call open a session on nobody's word in the JVM of the gateway that replaced it, under a handle that
  gateway routed to a follower, which was then told to drop its own. A request that reached a follower's port
  before the follower existed was held until it had attached, and was given a handle the gateway never heard
  of. And a session opened on a follower whose JVM took another name meanwhile was said to be on that name,
  where the gateway routed it to the one asked for. Three beats is what the watch compares against, not when it wakes: it looks
  once a beat, and one that fell asleep on the cadence of an attachment just lost sleeps that beat out first.
- **The startup grace is a refusal, not a hold:** for ten seconds after starting, a handle the gateway knows
  nothing of is answered "not known here yet, try again in a few seconds". Measured between JVMs on one machine,
  followers had reported within about a second.
- **A retired handle stays retired for as long as the gateway runs.** It is routed nowhere, minted by nobody,
  and told to drop at every attach and beat that reports it. Whether its holders have dropped it is not tracked:
  the record once ended when every holder had reported without the handle, and a name is not a holder — another
  JVM attaching under the name of one that slept reported nothing, the record ended, and the sleeper was given
  the handle back when it woke. Nor does it end with the sessions: two conversations hold the handle in their
  context, and whoever was given it next would have both. A request naming a retired handle is refused by the
  gateway in those words, a task method included, before anything is looked up under it: a result this JVM
  retains under the handle may be the other conversation's. Retirements arise only from collisions, so the
  record stays small. It is the one thing the gateway keeps that followers do not report, and a restart loses
  it. A holder that had dropped the handle reports nothing of it, and both conversations are refused. One that
  never heard of the retirement — asleep throughout — returns with the handle, which is then adopted, and the
  calls of both conversations are carried to it: the conversation whose session that never was is refused
  nothing. That is the price of keeping the record in memory alone.
- **A registration the gateway did not answer is settled by the next beat.** A follower gives a session opened
  directly its handle all the same, and its next report says it holds it: adopted, or retired on both holders
  when another has it. A session opened through the gateway is not given out on those terms — its next call
  would arrive at a gateway that cannot route it — so that `create-session` fails and says to try again.
- **A client waiting on a node that goes away is answered.** If the node had not begun to answer, the gateway
  refuses the call in its own words; if an event stream was open, it adds one last message saying the node went
  away. Either says what the gateway cannot know, and does not guess at it: the call may never have started —
  the stream opened, and the node was not told so — may have been stopped by that same loss, or may have run on.
- **Cancellations follow their request, or nobody.** `notifications/cancelled` names a request id and no session,
  so the gateway sends it to the node the matching request on the same connection was forwarded to. A notice
  without a connection names nothing, as on an instance alone (§5), and when the connection has more than one
  such request in flight — on nodes or in the gateway's own JVM — the notice is accepted and nothing is
  cancelled. A notice whose request runs in the gateway's own JVM belongs there, so a batch that pairs it with a
  call for a node is for two machines. On the stateless wire a client cancels by closing its stream, which a
  follower sees as a write the gateway could not deliver.
- **A cancellation does not overtake its request on the way to a node.** An attachment delivers envelopes in
  order, and two things once let the pair change places all the same. A gateway put a request among those in
  flight, where a cancellation is routed by it, before it put it in the attachment's queue; the two are one
  step now. And a follower gave each envelope a thread of its own, on which a request became cancellable only
  when that thread ran; a follower now registers a request on the thread that reads the stream, before handing
  it on, and the handler takes those records over. A stop that finds a request registered and not yet waiting
  is acted on as its wait begins (§5). Evals run as concurrently as before: it is the registration that is
  ordered, not the work.
- **A request is carried to one place, whole.** A legacy batch whose calls belong on more than one machine, the
  gateway's own JVM counted, is refused before any of it runs. Sorting a batch by machine and stitching the
  answers together would be a second transport, for a construct later revisions of the protocol dropped. A
  place is an attachment and not a name: a cancellation follows its request to the JVM that has it, a handle is
  found on the JVM the router's reading shows, and when a name has passed from one to the other between them
  the batch is for two places, in whichever order it lists them.
- **A stopped follower stays stopped:** the attach under way is abandoned, and a welcome that arrives anyway is
  not acted on.
- **Version skew is shown; another wire is refused.** The attach carries the follower's version, which the roster
  shows, and the revision of the wire it speaks, which has to be the gateway's own. A mismatch is refused at the
  attach, before anything is read as what it is not: a follower from before body frames once stored the frame
  lengths as file content and answered 201. The welcome names the revision in turn, and a follower takes no
  welcome that names another or none.
- **Connections are forgotten where they are adopted.** A follower never answers a handshake that came through
  the gateway, so the connection ids it sees are the gateway's, adopted as they arrive, and the sweep of silent
  ones that a handshake runs (§11) never ran there. An adoption runs it too.
- **The tool list is the gateway's to announce.** A follower does not announce `tools/list_changed` on a request
  that came through the gateway, since its client fetched the list elsewhere. The gateway announces on streams it
  serves itself and not on those it forwards, so a legacy client working only on nodes learns of a changed list at
  its next request served by the gateway.

## Owed

Decided on 2026-11-25, after the reviews of the build above, to be done before a release: a run owning its
tables, order at admission, and two trials that change the wire — a follower saying which requests a write
answers, and the welcome in the head of the attach's response. All four are built and described above; the
trials and what they did not deliver are in the logbook of that day. The wire is free to change while no
revision of it is public, and costs a revision afterwards.

The two-machine trial was run again on what resulted, on 2026-11-26, and is in the logbook of that day with
what five further rounds of review found. It left one question, which is under Open: a follower that stops
says nothing to its gateway.

## Deferred

- **Authentication at the gateway.** The shape imagined: followers attach with tags, accounts are granted tags, and a
  client authenticated to the gateway sees the followers its tags cover. A matter of internal management, not of the
  perimeter, which the private network draws better. HTTP between follower and gateway keeps the way open: a
  follower's credential would be a header, its tags a field of what it announces.
- **A list of gateways,** as above.

## Open

- Two machines on one local network have run it, the gateway on one and a follower on the other. Sleep was
  simulated by stopping the follower's process, and a partition by stopping the tunnel its connections ran
  through; neither a real suspend nor a network that loses packets at random has been tried.
- For its first ten seconds a restarted gateway cannot tell a node's session it has not been told of yet from a
  session that lived in its predecessor's own JVM, which no follower will ever report. Telling them apart would
  take the gateway remembering its own handles across a restart, which is the one thing it is built not to do, so
  the refusal names both cases and says what to do in each. The answer becomes the ordinary refusal afterwards.
- A client has driven a gateway — Claude Code, through the tool list of a server that became a gateway after it
  connected, so `node` was passed unadvertised. A client that reads `node` from the tool list and chooses to use it
  has not been observed.
- The beat cadence fixes how long a node that died without closing its socket goes unnoticed. The gateway takes
  a node for gone when it has gone unheard for more than three beats, and looks when it next writes to it,
  which is within a beat as long as its writes return: between ten and twenty seconds after a node that was
  beating regularly is lost, by where the loss fell between two of its beats. That is what is expected of
  noticing, and was not measured as such. A client waits from when its call arrives until then: across two
  machines, a call made 3 s into a partition waited 10 s, and one begun 1 s before a partition returned 14 s
  into it, a wait of about 15 s: 13 s and 14 s from the loss. Whether that is short enough is a matter of use.
- A follower that stops says nothing to its gateway. On loopback the gateway gave one up 6.5 s after its stop,
  at a write that failed; across two machines it took 14.8 s, and whether a failed write or the silence ended
  the attachment there was not looked at. A goodbye
  is deferred: giving the node up at once would also give up the replies it still owes, the one to the call
  that asked for the stop among them, so a goodbye has to let those finish, and has to name the attachment it
  closes.
- A route outlives its session by up to a beat. A call naming a session that ended on a node in the last five
  seconds is carried there and refused there, as it would be directly.
- A retired handle is retired until the gateway restarts, and the gateway then knows nothing of it: a holder
  that returns with the handle afterwards is given both conversations' calls.
- A cancellation notice that reaches the gateway before the gateway has taken its request names nothing, and is
  left to the gateway's own JVM to ignore: the two arrive on sockets of their own, with no order between them to
  keep. An instance alone is the same (§5), keeping no record of a stop that came early. Once the gateway has
  taken a request, the way to its node adds nothing to this.
- On the stateless wire an eval still being waited on is cancelled by any part the gateway does not acknowledge
  (§5). That is a client that hung up, and also a gateway restarted mid-answer or a part that timed out on its
  way. An instance alone is as unable to tell a stop from a lost connection; a gateway adds a hop to lose one on.
- A cancellation notice the gateway declines as naming more than one request is acted on by nobody. An instance
  alone also withholds the delivery marks of each request such a notice names (§5); the gateway does not pass
  that on to the nodes.
