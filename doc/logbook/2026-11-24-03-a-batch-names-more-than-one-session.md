# 2026-11-24 — A batch can name more than one session

Since a session became a tool argument (`2026-11-23-02`), one post can carry calls for several. The transport
took a batch's first: only that session was told how the client had addressed the request, and only it was kept
fresh while the batch was being answered. Found in review, with a batch of two calls for two new sessions, each
evaluating `(__r/upload-url)`: the first got a URL and the second nil.

Every session a payload names is now told its address, refreshed when the post arrives, and refreshed again on
each beat of its stream.

The arrival refresh is there because a batch's calls are answered in turn, and each call's admission reaps the
silent. A session named by a later call was otherwise first heard from at a beat, or at its own turn when the
batch is answered as plain JSON, which has no beats. Reproduced in review with a controlled clock: a batch
arrived while both its sessions were live, the clock passed the second session's expiry during the first call's
admission, and the second call was refused. For a post of one call the arrival refresh is the one its dispatch
did a moment later anyway.

A beat refreshes the whole batch's sessions and not only those whose calls are still to come: the stream being
open is what says the client is there, and that is true for all of them.

Two tests. One holds a batch's first call open, waits for its admission, and requires the waiting session's
stamp to move after that, which only a beat can do; it also requires both sessions to be told their address. The
other moves the session clock past a waiting session's expiry between two earlier calls of its batch.

- Serving only a batch's first session: four assertions fail.
- No arrival refresh: two fail.
- Beats refreshing nobody, or only the first session: one fails.

Not tried: a real client that batches. None of the clients measured so far sends a batch. A session has to have
been silent for a day less a moment, and then be named late in a batch, for the arrival refresh to matter.
