# 2026-11-24 — Expiry is decided when the row goes

Sessions are reaped at admission: the silent ones are listed, then each is ended. Nothing asked again between
the two. Found in review, in 0.9.0 as well, by pausing a sweep after it had listed a session: the session made a
call, was refreshed, and was answered `running` for a new eval; the sweep resumed and dropped it. The eval's
result was then owed to a handle the server refused, and its entry, admitted after the session's claims had been
released, stayed shielded from eviction with nobody to read it.

Ending a session for its silence now asks, in the step that takes the row, whether it is still silent. A call
that refreshed the session since it was listed keeps it. One that comes after the row is gone is refused before
it starts anything. The claims lapse after the row goes, where an outright release lapses them first.

That order puts the session's files between the row and the claims, and deleting them could throw. Found in the
same review: with an expired session's upload directory made unreadable, the walk that deletes it threw
`AccessDeniedException`; reproduced on the deleting function alone. It was documented as best effort and caught
each file's failure, but not the listing's. Thrown here, it would have skipped the lapse and left the dead
session's results shielded for good. And in 0.9.0 already, the throw escaped the admission doing the reaping:
admission comes after an eval has started, so that eval ran on with no registry entry while its caller was
answered with the filesystem error.

The deletion no longer throws. It walks the tree, deletes what it can, passes over what it cannot list or
delete, and returns.

- With the sweep put back to ending what it listed, its test fails on one assertion.
- With the old deletion put back, its test errors: the admission throws.

The expiry gap is a few instructions wide, and the session must have been silent a day and call at that instant.
The server makes the upload directory itself, so the second takes something changing its permissions
afterwards, which an eval can do. Neither was observed outside the review.
