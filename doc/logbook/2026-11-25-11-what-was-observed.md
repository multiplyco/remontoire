# 2026-11-25 — What was observed, for review

Four changes were decided today and built: a run owning its tables, order at admission, a follower saying
which requests a write answers, and the welcome in the head of the attach's response. Entries 08, 09 and 10
say what each is and why. This one says only what was observed of each, how, and what was not, for whoever
reviews them.

## How things were observed

**Trees.** A is the commit "a gateway run owns its tables". B is the commit after it, "a cancellation does not
overtake its request on the way to a node". C is B with both wire trials on it, uncommitted when this was
written.

**The suite.** `bb test`, on JDK 27.

| Tree | Tests | Assertions | Failures | Runs |
|---|---|---|---|---|
| A | 241 | 949 | 0 | 1 |
| B | 243 | 954 | 0 | 3 |
| C | 246 | 966 | 0 | 3 |

`bb warn:reflection` is clean on B and C. The touched files pass `cljstyle check`; the check over the whole
repository was not run again.

**Removals.** One edit to the source, alone, then the gateway, follower, lifecycle and session test
namespaces (`bb test --focus …` for each), then the source restored. The count is failing assertions. Every
removal below was run again on tree C, one at a time, and those are the counts given; where a count differs
from an earlier entry it is said. Each is written as the text replaced and what replaced it, so it can be
repeated. One test was tightened after that run, the one of an answer without a welcome in section 4; it was
then run by itself three times and with the whole suite once, and no removal was run against it again.

**Live.** The dev gateway (`bb dev`) with this checkout loaded, driven through its own MCP endpoint by the
client of the session doing the work. Scripted followers attach to it over HTTP from inside its JVM; for the
trials a second JVM was started beside it with `bb dev:follower`. A hold or a fault is a function of the
gateway replaced for the length of one probe and put back.

## 1. A run owns its tables

### Removals

| | Replaced | By | Failing | In test |
|---|---|---|---|---|
| a | `(if-not (:ended? fleet)` in `route!` | `(if true` | 1 | a-request-for-the-gateway-itself-is-not-served-once-it-has-stopped |
| b | `(:ended? @state) :ended` in `admit!` | `false :ended` | 2 | an-attach-a-gateway-accepted-is-none-of-the-next-ones-business |
| c | `(first (reset-vals! waiters {}))` in `stop!` | `nil` | 2 | a-wait-for-a-node-ends-with-its-gateway |
| d | the `(when (:ended? @state) (q/fail p (stopped)))` in `await-node` | `nil` | 2 | a-wait-made-as-its-gateway-stops-ends-too |
| e | `(when (identical? entry (get-in @state [:nodes name]))` where a welcome settles waits | `(when true` | 2 | a-welcome-of-an-attachment-gone-meanwhile-settles-no-wait |
| f | `(get-in fleet [:nodes node])` for a call's entry in `dispatch!` | `(get-in @(:state run) [:nodes node])` | 2 | a-request-goes-to-the-attachment-its-handle-was-found-on |
| g | the same for an upload's entry | the same | 2 | a-request-goes-to-the-attachment-its-handle-was-found-on |
| h | the three `cond` clauses that make a place of `(:entry t)`, of the entry `fleet` shows for `(:node t)`, and of `t` | one clause, `t (or (:node t) (some-> (:entry t) (->> (node-of run))) t)` | 2 | a-batch-is-carried-to-one-attachment |
| i | the beat's `(cond (not (attached?)) ::gone (fresh? entry seq) (settle! …))` | `(when (and (attached?) (fresh? entry seq)) (settle! …))` | 1 | what-is-said-as-an-attachment-gone-meanwhile-is-not-its-successors |
| j | the registration's `(not (attached?)) ::gone` clause | `(attached?)` joined to the next clause's test | 1 | the same |

Removal e failed nothing when first run. The test that had caught it before restarts the gateway, and a
welcome of the first run has no way to the second run's waits any more. A test was added that gives the name
up within a run, under a held welcome.

### Live

On a dev gateway restarted on this tree, five probes. In each a scripted node is replaced by another under
its name — hung up, seen gone, another attached reporting no sessions — while something of the first is held
in a handler.

| Held | Where | Observed |
|---|---|---|
| A beat reporting a handle | its attachment found, the lock not yet taken | answered 410; no route made, nothing retired, the successor's number unchanged, the successor sent nothing |
| A registration of a handle | the same | answered 410; no route made, the successor sent nothing |
| A call for a session on the node | the attachment chosen, the request not yet queued | refused in the gateway's words, "did not answer"; the successor sent nothing |
| An upload for that session | the same | answered 502; the successor sent nothing |
| An attach contesting the name | waiting on its ping to the holder, which hangs up; a third takes the name | the contender answered 409; the third keeps the name and is sent nothing |

A wait for a node, made in a session on the gateway, settled when a node of that name attached. After the
probes the gateway had no node and no route of theirs.

### Not observed

- Anything of a stop, live. A probe that stops the gateway stops the endpoint it is driven through, so what
  an ended run refuses, and what becomes of its waits, is observed in the suite only.
- The reviewer's script of eight checks, which passed on the commit before A, reaches into state that is no
  longer the namespace's and was not run on A or later.

## 2. Order at admission

### Removals

| | Replaced | By | Failing | In test |
|---|---|---|---|---|
| a | `(let [admitted (admit event)]` in the follower's `event!` | `(let [admitted nil]` | 3 | a-cancellation-finds-the-request-that-arrived-before-it |
| b | `(mcp/with-admission (merge admitted {:peer peer :connection cid}) payload` in the transport | the same without `(merge admitted …)` | 3 | the same |
| c | `(locking (:queue entry)` around the two steps in the gateway's `forward!` | `(do` | 1 | a-cancellation-is-queued-behind-the-request-it-names |
| d | `(some-> admitted mcp/dismiss)` in the `finally` of the follower's `serve!` | `nil` | 1 | a-cancellation-finds-the-request-that-arrived-before-it |

Entry 09 first gave 1 for a and b. That was counted before two assertions were added to the test for the
sake of d, and was corrected there.

### Things a reviewer should know

- **Removal c rests on a pause.** The gateway's test posts the cancellation from a watch on the table of
  requests in flight, the moment its request appears there, and the watch then sleeps 300 ms: long enough for
  an unheld cancellation to be routed and queued ahead. On a machine slow enough to miss that, the removal
  would pass unnoticed. The test itself cannot fail for it.
- **The follower's test was written twice.** The uncommitted change to the follower's tests was lost while
  the work was being split into two commits, and that test was put back from the record of the session, not
  from a copy of the file. The two source files lost with it were restored from copies and compared. The test
  as it stands is what the removals above were run against, and it has passed every run since in which
  nothing it guards was removed, the removals of the other three sections among them.
- **Removal d failed nothing when first run**, and the assertion that no request of that name is left
  waiting once it is answered was added for it.

### Not observed

- A cancellation between two JVMs. No notice was sent through a live gateway to a live follower; the order
  is observed at each end of the hop in the suite, by a scripted peer.
- A notice that reaches the gateway before the gateway has taken its request. Nothing was changed for it and
  nothing was tested of it.

## 3. A follower says what it sends

### Removals

| | Replaced | By | Failing | In test |
|---|---|---|---|---|
| a | `(when (HttpExchange/.getAttribute exchange tunnelled-attribute)` in the transport's `announce!` | `(when false` | 5 | a-part-names-the-responses-it-carries, a-result-whose-acknowledgement-was-lost-stays-unread |
| b | `(seq ids) (assoc "X-Answers" …)` where the follower sends a part | `false (assoc "X-Answers" …)` | 5 | the same two |
| c | `(swap! answered into (answers-in headers))` in the gateway's `handle-answer!` | `nil` | 2 | a-stream-that-gave-its-response-is-not-given-a-refusal |

### The case it was to be judged by

A result flushed to the client, and the gateway's acknowledgement of that part lost on its way to the
follower.

In the suite, by halves, since a gateway and a follower are never one process:

- A scripted gateway answers 500 to the part that names a response and 204 to every other. The follower's
  last word on the request is that it gives the answer up; the entry is owed to its session and untouched.
- The same with every part acknowledged: the entry is touched and nothing is owed.
- A scripted node opens a stream, sends a response naming its request, and gives the answer up. The client's
  stream holds one message, the node's.
- The same with the response split over two parts, the second naming the request: one message, the node's.

Between two JVMs, once. The gateway's function that answers a follower was replaced so that the first part
naming a response was answered 500 after it had been written to the client, and the function that composes
the gateway's own refusals so that its calls were recorded. One eval was then sent to a session on the node.

| Looked at | Observed |
|---|---|
| The client | the eval's value, once |
| The part answered 500 | named one request, `[12]` |
| The gateway's refusals | none composed |
| The gateway's requests in flight, 1.5 s later | none |
| The node | attached still |
| The entry, on the node | owed to its session, status done, untouched |
| The entry of the eval before it, acknowledged | touched |
| The next answer from that session | carried the note that the entry had settled |

### Not observed

- **A lost reply as such.** A 500 stands in for it in the suite and live. A follower takes anything but 204,
  404 and 499 the same way, no answer at all included, which is by reading: the path through the 30 s a part
  may take was not run.
- **A batch through a follower.** That a part carrying a batch's responses names each of them is by reading.
  The gateway's side of a batch is tested for a stream that had answered none.
- **The stateless wire.** The tests and the live run are of the legacy wire. Both wires answer on the one
  function that announces.
- **An `X-Answers` that is not a list**, which the gateway is written to ignore.
- **A second occurrence live.** The fault was armed once and fired once.

A plain JSON answer names nothing, by design: what a stream has answered is consulted only for a stream.

## 4. The welcome in the head

### Removals

| | Replaced | By | Failing | In test |
|---|---|---|---|---|
| a | `(Headers/.set "X-Welcome"` in the gateway's `handle-attach!` | `(Headers/.set "X-Unwelcome"` | 31, and 1 error | ten of the gateway's tests |
| b | `(when-not (= fleet/wire wire)` in the follower's `welcome!` | `(when false` | 2 | a-welcome-in-another-wire-is-not-an-attachment |
| c | `(when-not (and (string? attachment) (not (str/blank? attachment)))` there | `(when false` | 2 | a-welcome-that-names-no-attachment-is-not-acted-on |
| d | `(when-not @stopped` ahead of `(welcome! node welcome)` | `(when true` | 1 | a-follower-that-stops-as-its-attach-is-answered-takes-no-welcome |

Removal d failed nothing when first run. The test of a welcome arriving late is of an attach cancelled before
it is answered, which never reaches the guard. A test was added that stops the follower with the answer in
hand and its welcome read.

### Measured

- **The head waits for a byte.** A JDK `HttpServer` handler set a header, sent the response's head as a
  chunked 200, waited 1.5 s and wrote its first bytes of body. A JDK `HttpClient` asking for the body as a
  stream had the response 1653 ms after sending the request. One run, on JDK 27, both ends in one JVM.
- **A head has a size.** The same pair, the header's value a run of one letter: 8,000, 80,000 and 380,000
  bytes arrived whole; 400,000 and 800,000 were refused by the client, "Header size too big: 393217 >
  393216". The property that sets the limit was unset.

### Live

A follower started beside the dev gateway attached; the roster listed it with its version. On the node, the
cadence the follower keeps read 5000 ms, which is the gateway's; the one it is written to keep while not
attached is 1000 ms. A session opened on the node through the gateway served evals. With the follower's JVM stopped, the roster listed the
node as not attached when looked at six seconds later.

### Things a reviewer should know

- **The gateway's tests read the welcome as they did.** Their helper for attaching reads `X-Welcome` and puts
  it at the head of the queue of events it hands a test, as an event of type `welcome`. Twenty-six places that
  take the welcome off that queue are unchanged for it, and none of them reads it off the stream.
- **Two of the follower's tests went**, of an attach answered and never welcomed and of something sent ahead
  of the welcome, with the two switches of the scripted gateway that staged them. One test replaces them: an
  answer that carries no welcome is not an attachment, and nothing sent on it is served. It runs with the
  cadence a follower keeps while not attached set to 60 s, so that the watch, three of those away, cannot be
  what drops the answer; the gateway must be dialled again within 3 s all the same. No single removal fails
  it: an answer with no welcome names neither a revision nor an attachment, and either refusal alone turns it
  away.
- **The byte behind the head is not shown by a removal.** With the comment the gateway writes there taken
  out, two tests were run: one passed, the head arriving with the first heartbeat 50 ms later as the tests
  set the cadence, and the other failed only because it waits on that comment being written. What shows the
  byte is needed is the measurement above.

### Not observed

- **An answer without a welcome at the default timings.** Under the event the drop came 3.99 s after the
  answer, measured between JVMs. Under the head it was measured in the suite only, against a scripted gateway
  and with 50 ms between dials where the default is longer: from the first attach being heard to the second,
  77, 83 and 78 ms in three runs, with the watch set three minutes away.
- **A welcome too large for a head.** The limit was measured with a header made for the purpose. That a
  follower given one would be dialled again for good is by reading.
- **A proxy between follower and gateway.** Both JVMs were on one machine, and nothing stood between them.

## What none of this is

The trial recorded between two machines was of a build before today's changes, and nothing here was run
across a network. All of it is one machine, one JDK, and in the suite one process playing the other side of
the wire by hand.
