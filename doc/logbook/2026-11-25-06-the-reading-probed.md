# 2026-11-25 — The reading, probed

Two entries back the role's other paths were read for a second look at the gateway's state and nothing was
found; the entry after it found what that reading had left out. The paths were then probed, on a live gateway:
the dev server, running this checkout, with scripted followers attaching to it over HTTP from inside its own
JVM and a hold placed in one handler for the length of each probe. In each, a node is replaced by another JVM
under its name — hung up, noticed gone, another attached with no sessions — while something of the first is
held in an interval. All within one run: a probe that stops the gateway would stop the endpoint it is driven
through.

| Held | Where | Then |
|---|---|---|
| A beat reporting a handle | its attachment found, the lock not yet taken | no route made, nothing retired, the successor's number untouched, the successor sent nothing; answered 200, nothing to drop |
| A registration of a handle | the same | answered 409, no route made, the successor sent nothing |
| A call for a session on the node | the attachment chosen, the request not yet queued | refused in the gateway's words, "did not answer"; the successor sent nothing |
| An upload for that session | the same | answered 502; the successor sent nothing |
| An attach contesting the name | waiting on its ping to the holder, which hangs up; a third takes the name | the contender refused 409, the third keeps the name and is sent nothing |

Nothing was found. The holds were `with-redefs` over the gateway's own functions and gone with each probe; no
node and no route was left on the gateway afterwards.

One thing seen and left: the held beat is answered 200 with nothing to drop, though it speaks for an
attachment that is gone by the time it is acted on. A follower still alive behind it learns so from its next
beat, which is answered 410.
