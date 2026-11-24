# 2026-11-24 — A beat speaks for the call being waited on

Found in review, and in 0.9.0 as well. A batch's calls are answered in turn on one stream. The transport took one
progress token for the whole post, the first any call attached, and kept one cursor into "the" output bank, where
each eval has its own. With two evals carrying different tokens, printing `one` and `second-call-output`, the
second's line arrived under the first's token as `nd-call-output`: the cursor had been left four characters into
a bank it had not read.

Only the narration was wrong. Each call's response carried its output whole.

The stream's display cell now holds the call being waited on, and each `tools/call` takes it over as it starts:
its own progress token, then its eval's bank once there is one. A beat goes out under that token, as a bare
comment if the call attached none, and a bank not read before is read from its start. A post of one call behaves
as before.

The transport's tests did not look at progress messages until now. Added: a batch of three evals, two with tokens
and one without, and a single stateless call. With the cursor carried across banks one assertion fails; with the
first call's token kept for the whole post, three.

Not tried against a client: none measured so far sends a batch. The seconds a beat reports still count from when
the stream opened, not from when the call's turn came.
