# 2026-11-24 — The interactive client declares tasks on the call too

`2026-11-23-09` found a headless claude-code 2.1.330 declaring the tasks extension when it listed tools and not
when it called one, and left the interactive client untried. Tried now, claude-code 2.1.331, interactive, on the
stateless wire against the dev server: the list it holds is the tasks one (`eval` with `background` and no
`await`, and no `await` or `cancel` tools), its `create-session` call carried the extension in its capabilities,
and an eval that slept 4 s came back inline with its value, where the path for a call that declares no tasks
answers `running` at 2 s. So the mismatch is the headless client's, as far as measured.

The 2026-07-28 revision does not require a client to declare the same capabilities on every request, and tells a
server to take each request by itself: no task may be returned to a call that did not declare the extension,
"regardless of prior declarations" (read through a fetch agent on this date). The server does that.

A server cannot see that a client holds a list its calls do not match, with one exception: `background` is
listed only to a declaring client, so its arrival on a call that declares nothing is the mismatch showing. That
call now answers with a line saying the flag was not applied, and, when the eval is still running, that `await`
waits longer and that evaluating `(__r/res n)` picks the result up. Tried against the dev server with `curl`
declaring no capabilities: the line appeared on a call still running at 2 s and on one that had finished, and
not on a call without the flag. With the line taken out, three assertions fail.

Then with the headless client at 2.1.331, against a throwaway JVM with a request log. It is as it was at
2.1.330: the extension declared on `server/discover` and `tools/list`, and on no `tools/call`. Asked to pass
`background`, it did, was answered `running` at 2 s with the line, and its model reported that the flag had not
been applied and why. Asked to pass `await` although its list has no such parameter, it did that too: the
argument arrived, as the string `"8000"`, was honoured, and the 5 s eval came back inline with its value. So
both halves of the hint work for the client that needs them.
