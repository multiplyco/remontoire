# 2026-11-24 — The gateway on the settled base

The gateway work was rebased onto 0.10.0. Three things it carried in provisional form were settled on the base
in the meantime, and the provisional forms are gone:

- **Request cancellation.** The branch keyed a request by its id within its sender, the peer's address where the
  wire has no connection. The base scopes a notice to its connection and gives the stateless wire no notice at
  all: a client there cancels by closing its stream (§5). The gateway routes a notice by the base's rule, to the
  node the one matching request on that connection went to, and leaves a notice without a connection to its own
  JVM, which ignores it.
- **A released session written back by its eval,** which the base closed by guarding every writer.
- **The scratch name of a session from before handles.** Dropped without a replacement: a new version arrives in
  a fresh JVM, so no such session exists.

What the base changed for the gateway, read from the code and then tried:

- Minting draws against every handle minted since the JVM started and then asks the fleet. A handle the fleet
  refuses stays on the JVM's list, which costs nothing: it is somebody else's.
- A session's end is reported to the fleet from the one place a session ends, so one that expires is reported as
  one that is dropped is.
- A failed start undoes the role as it undoes everything else, and a role that fails as it is taken up stops the
  server it was started on. Three removals, each caught: the role not put back (2 failing assertions), the
  server left running (the retry on its port errors), the role not given up (1). A `:follow` that is no address
  is not such a failure: the follower starts, and dials it for ever.
- **A stream the gateway could not deliver cancels its eval.** A follower writes each frame as a part the
  gateway acknowledges once the client has it. With the stream opened and every later part answered as
  undelivered, a stateless `eval` waiting on `(Thread/sleep 30000)` settled cancelled; with every part
  acknowledged it ran on, and so it did with the transport's cancel-on-close taken out (one failing assertion
  each way).

Left open, and listed in §15: any unacknowledged part cancels, so a gateway restarted mid-answer cancels what a
hangup would; and a notice the gateway declines as ambiguous does not withhold the delivery marks of the
requests it named, as an instance alone would.

200 tests, 808 assertions.
