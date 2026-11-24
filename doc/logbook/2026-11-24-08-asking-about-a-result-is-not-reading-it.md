# 2026-11-24 — Asking about a result is not reading it

An entry never delivered is never evicted (§5). Found in review, in 0.9.0 as well, two operations that marked an
entry delivered without handing anything over, after which its unread result was evicted under pressure:

- `(__r/failure id)` read the entry through `res`, which delivers, before looking at how it stood. For an eval
  still running it answered "Entry 1 is running" and the entry was marked.
- `tasks/cancel` marked its entry whatever state the task was in. For a task that had already settled the cancel
  did nothing, returned no result, and the entry was marked.

`failure` now takes one look at the entry and delivers last, and only a failure it has rendered. Delivering first
had a second fault: once delivered the entry can be evicted, and the later look for its baseline then found
"Entry 1 was evicted" where the exception had been. Seen in review with admissions running alongside.

`tasks/cancel` marks by outcome. Its mark, run once the response is out, waits for the task to settle and marks
the entry if it settled cancelled: that is the ending the caller authored, as with the `cancel` tool. A task that
had finished already, or that finishes on its own before the cancel bites, keeps its result owed, and `tasks/get`
still collects it. The second case was forced in review by letting the eval finish between the cancel's look and
its `q/cancel`. A cancelled task that is slow to die stays protected until it has actually settled.

Tests: a failure asked about while running and once failed; a cancel that comes after the task settled, one that
loses to the task's own ending, and one that ends its task. With `tasks/cancel` marking whatever the outcome,
three assertions fail; with it never marking, one.

The eviction between delivering a failure and reading its baseline has a test as well: a watch on the registry
admits entries the moment the mark is made, the entry goes, and the failure must still come back whole. With
`failure` delivering first it errors with "was evicted".
