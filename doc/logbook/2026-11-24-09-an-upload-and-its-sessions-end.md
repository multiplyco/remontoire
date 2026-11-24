# 2026-11-24 — An upload and its session's end

Found in review, in 0.9.0 as well: a PUT was paused after its bytes were copied, another eval's admission expired
the session, and the PUT resumed. It answered 201. Storing the file had recreated the directory the expiry had
just deleted, so the file sat on disk under no live session, in no roster, until the JVM exited.

Two changes. An upload refreshes its session when it arrives, where it did so only once the file was stored, so a
session silent for nearly a day is not reaped while its upload is in progress. And after moving the file into
place, the store asks whether the session still has that directory; if not, it takes the file and the recreated
directories back and refuses with 404. The question comes after the move on purpose: an ending session loses its
row before its files, so either the store sees the row gone, or the session's cleanup sees the file.

The receipt was a third thing, found in the same review. It was read back from disk after the move, so it
described whatever was there by then: with two PUTs to one name overlapping, the one-byte upload was answered with
the other's six, and with the session ended just before the read the answer was `201 {}`. The receipt is now
built from what this request copied and where it put it, and the question above decides whether it succeeds.

Tests: a session ended between the copy and the move; the session's stamp as seen when the store begins; and a
receipt that does not follow what the disk says.

- No refresh on arrival: one assertion fails.
- No question after the move: two fail.
- The receipt read back from disk: one fails.
