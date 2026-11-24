# 2026-11-24 — What the suite did not notice

With the base settled, a sweep over the source changed since 0.9.0: 45 guards in the session table, the registry,
the dispatcher and the transport, each removed alone and the whole suite run against it. 36 were noticed. One of
the nine that were not changes no behaviour (an upload directory is no longer minted for an unknown session
without the liveness check either: the write that records it is guarded). The other eight were behaviour nothing
tested, and a review of the same tree added two more.

Tests added, each failing with its guard removed:

- A stateless request is refused when it is a batch, when its body names another revision than its header, and
  when a task method's `Mcp-Name` is not its task id. Two of the five refusals had a test.
- A `tasks/get` refreshes the session that owns the task.
- A task answer leaves nothing to be told when its eval settles, and what its admission evicted waits for the
  session's next response.
- A response whose trailing SSE probe fails has its marks withheld: the entry is still to be told. The existing
  test looked only at whether the eval kept running, which it still does first.
- A plain-JSON answer whose write fails delivers nothing.
- `await` with both `all-of` and `any-of` returns once every one of the first and one of the second has settled,
  whichever comes first. Making `any-of` wait for all of its entries had gone unnoticed, and so had not waiting
  for it at all.

An upload refreshing its session was the ninth, and is covered by `2026-11-24-09`.

The suite also passes in three more random orders (seeds 1, 7 and 99); it runs in a fixed order by default.

The stream-close checks from `2026-11-24-06` were repeated on the final source and fail as before: a closed
stream cancelling nothing fails three assertions, no quiet probe two, the cancel hook dropped after the wait
one, a failed trailing probe made to cancel one.

Tests that need a background eval's task id hold the eval on a promise until they have it. An eval that returns
at once can settle before the dispatcher looks, and is then rightly answered inline.

Not covered by a sweep of this kind: races, which removing a lock does not make a test fail, and anything only a
real client shows.
