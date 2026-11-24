# 2026-11-24 — Cancellation and session ids across a live upgrade

This revises two things in `2026-11-23-10`: the fallback that let a notice cancel a request registered under a
bare id, and the marking of requests as unread by a notice that came with only an address. Both are gone.

A JVM running 0.9.0 held an eval across a reload. A legacy cancellation notice left that eval running. The
explicit `cancel` tool then stopped it by registry id, the original request returned and its old in-flight entry
was removed. The old entry has no sender identity, so it is safe to let the original handler drain it.

In a separate 0.9.0 JVM, a session was live with an eval paused in a host namespace when the session code was
upgraded. After release, forcing the allocator to draw the old handle made it draw again. The old eval finished
and the new session kept its own namespace. The reservation history now includes handles live at upgrade and
ones released by the new code. A handle already forgotten before the upgrade can recur.
