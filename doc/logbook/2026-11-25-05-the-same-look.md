# 2026-11-25 — The same look

The entry before had the router read the gateway's state once, and ended with a list of the role's other paths
read for a second look, with nothing found. The reviewer found one in the router itself, which that list did
not cover: the node a handle is on was taken from the one reading, and the attachment of that name was then
looked up in the roster as it was by then.

Within one run: a handle is found on `worker`; `worker` detaches, and another JVM attaches under the name with
no sessions, at which the gateway rightly drops the handle's route. The request, already on its way, was queued
for the newcomer. The reviewer saw it with an eval and with an upload.

The attachment is now taken from the reading that named the node. One that has gone since is found gone where
the request is queued for it, by the check that was there already, and the gateway answers in its own words.
The test takes the reading, replaces the node, and gives the router the reading it had: the call is refused,
the upload answered 502, and the newcomer is sent neither. The gateway's, the follower's, the lifecycle and the
session tests, 89 of them, against each removal alone: a call sent to whoever holds the name now, 2 failing
assertions; an upload likewise, 2.

The reading of the role in the entry before was of everything but the function that had just been changed.
