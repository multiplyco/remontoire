# 2026-11-03 — `background` skips the grace, smoke-tested against Claude Code

The tasks path's `eval` gained a boolean `background` (§11): work the caller knows is long answers as a task at once
instead of holding the call through the 10 s grace. Run against Claude Code through `bb dev`, one session (`pnq`).

*A reconnect re-lists the schema.* After `/mcp` reconnected, the eval tool carried `background` with no further step.

*An immediate task answer is followed like a late one.* An unbounded `Thread/sleep` loop with `background: true` came
back at once with the client's usual text — "started server-side task pnq-2 … moved to the background as task
knl0zug1x" — and the next eval, a read of the loop's counter, ran 2.6 s after the loop started, counter at 24.

*The work keeps running and stays composable.* A second read at 8.9 s had the counter at 86 and entry 2 `:running`.

*TaskStop cancels it.* Stopping `knl0zug1x` left entry 2 `:cancelled`; the counter held at 107 across a 500 ms sleep.
