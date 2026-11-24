# 2026-11-24 — A new version arrives in a fresh JVM, and what that takes out

The reviews recorded in `2026-11-23-08`, `2026-11-23-10` and `2026-11-24-01` each counted loading new code into a
running JVM as an upgrade a host goes through, and several fixes existed only for that. A host gets a new version
by updating the dependency and restarting, so old and new code never share a JVM. Loading changed source into a
running one is a development convenience. CONTRIBUTING now says so.

Taken out, as serving only that case: the scratch name drawn from an id's first eight letters and digits, kept
for sessions that went by a connection's UUID (`08`); the session table consulted at mint, the mint history
seeded from it at load, and a handle recorded a second time at release (`10`, `2026-11-24-01`); the test for a
request registered under a bare id; three-letter handles in the task-id pattern. What `2026-11-24-01` observed
stands as observed. None of it happens in a host.

Kept, being true within one boot: a handle is not minted twice while the JVM runs, since an eval outlives its
session there as well.

*Session ids are five letters.* With ids no longer reused, three letters were 8000 sessions to a JVM, and the
rule that lengthens them would have acted at 4000. At five letters it acts at 1.6 million.

*An id twice in one batch.* Two requests under one id in a single legacy batch shared one record, so a notice
naming the id stopped both, where an id that names two requests is meant to stop neither. The record for such an
id now says that it is shared, and a notice treats it as it treats two records. With that check taken out, one
assertion fails.
