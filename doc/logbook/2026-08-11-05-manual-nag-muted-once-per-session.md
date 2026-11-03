# 2026-08-11 — The manual nag muted to once per session

Settled after the fleet observation: with several same-build servers reachable from one conversation, or
across an app restart, the per-response `;; manual unread` line was almost always inaccurate — the manual is
per-conversation, the read-mark per-session, and the server structurally cannot see conversation state
(sessions don't span reconnects; a mid-conversation instructions swap would bust the client's prompt cache,
so nothing else delivers late corrections either). Three findings drove the shape chosen. The repeating nag
cannot do its apparent job: it rides eval responses, so the first eval it could inform is the second — the
pre-first-eval channels are the instructions and the eval description, which already carry the directive with
the conversation-scoped conditional. Its real cost is footer credibility: the footer is where truncation,
eviction and settled news are announced, and a line that is usually wrong trains tenants to skim the channel
that must be believed. And dropping it entirely was rejected for one asymmetry: instructions delivery is
client-dependent, while the footer is in-band and always arrives — so a single first-response line is the one
server-controlled channel guaranteed to reach a genuinely fresh tenant. Hence: once per session, worded as
fact with the judgment left where the knowledge is (`new session — (__r/manual) unless this conversation
has`), burned only when the response carrying it actually goes out — a client that timed out was never
nudged, the same delivery discipline as every mark.
