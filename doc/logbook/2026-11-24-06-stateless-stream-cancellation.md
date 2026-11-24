# 2026-11-24 — A closed stream as cancellation on the stateless wire

A JDK HTTP client closed a silent eval's SSE stream 2.5 seconds into the default 10-second task grace. With a
15-second heartbeat, the server's writes of both the task response and the trailing probe succeeded after the
client had closed. The eval kept running. With a quiet SSE comment at each one-second idle poll, the third comment
failed, the eval was cancelled and the server sent no task response. Progress messages retain their usual cadence.

The observable handoff is the flush of the final response. The HTTP tests also exercise a failed write of that
response and a failure of the subsequent probe. The first cancels the eval on the stateless wire. The second
withholds delivery marks while a task whose handle was flushed stays available to poll. A successful socket
flush can only establish what the server observed.

Repeated the same day with `curl` against a JVM of its own, default timings. A stateless eval held with a 60 s
wait, the client gone at 3 s: cancelled within about 3 s of that, and announced as cancelled on the next
call. The same with tasks declared, the client gone 2.5 s into the grace: cancelled, no task handed
back. A stateless call read to its end: marked delivered, the comments notwithstanding. The legacy wire, the
client gone at 3 s of a 20 s eval: the eval ran to its end. A stateless `await` of a running entry, the client
gone at 3 s: the entry still running.

And with the headless claude-code 2.1.331, its tool timeout at 8 s, against a throwaway JVM with a request log:
an eval held with a 60 s wait; the client reported the call timed out after 8 s; the server's next quiet comment
failed 9.0 s after the request arrived and the eval was cancelled at that write. The client's next call listed
the entry as cancelled and its footer announced it.

Decided the same day to act on a closed stream. An eval whose client has dropped the call is not accounted for:
nobody holds its id, and nobody is waiting for it. Stopping it is the safer side to err on, at the price that a
timeout or a lost connection stops work a client might have come back for.

Not tried: a real client's own stop, as against its timeout.
