# 2026-11-24 — What the gateway owed

The nine things the second review left owed on the gateway (2026-11-23), settled on the rebased branch. Each has
a test that fails without its fix; the counts are of the gateway's and the follower's tests, 42 of them, run
against each removal alone.

## An answer that ends

- **An abort the gateway did not confirm.** A follower now takes only the gateway's word that an answer is over:
  that it took the abort, that it holds no such request, or that the client is gone. An error or silence is asked
  again, three times, and then the attachment is dropped. With every answer met by a 503 the follower gave the
  attachment up and attached afresh. Taking any answer as heard: 1 failure.
- **A lost end.** The part that closes an event stream carries nothing but the end, and is now said again as an
  end. Given up like any lost part: 1 failure.
- **A refusal after the result.** The gateway reads the frames of a stream it pipes and remembers which requests
  have had their response. A stream given up before its response gets the gateway's last message; one given up
  after it gets nothing. Adding the refusal to any open stream: 1 failure.

## Two sides that agree

- **The wire has a revision.** An attach names it, and a gateway refuses one that is not its own — a missing one
  included — with a 409 that says what differs. Unchecked: 2 failing assertions.
- **A frame has a length a gateway writes.** 64 KiB at most; a longer one is not a frame and is not allocated.
  Taken at any length: 2 failing assertions, an oversized body staged as a file.

## Handles as state

The review found four ways for a follower and its gateway to disagree about a handle for good, and the entry
that recorded them proposed one answer. It is built as proposed, with two additions.

- **The beat carries the follower's sessions,** and the gateway settles it with the function it settles an
  attach with. A handle given out while the gateway was away is adopted from the next beat; one that another
  holder has is retired on both, wherever it is found; a route nobody reports is dropped. The follower no
  longer keeps a list of unconfirmed handles or asks about them again, and the message for a session that
  ended is gone: it is absent from the next beat. A beat that settles nothing: 6 failing assertions in three
  tests. A follower that ignores what a beat is answered with: 1.
- **Retirements do not expire.** Forgotten with the nodes that went away: 6 failing assertions in two tests.
- **Added: what a follower says of its sessions is numbered.** A beat read before a registration and arriving
  after it would say the handle is not held. The attach, each registration and each beat carry a number taken
  as the sessions are read, and the gateway acts on one only when it is later than the last. Acted on in
  whatever order they arrive: 2 failing assertions.
- **Added: a handle is asked for, and its session made, under the lock a beat is read and sent under.** The
  hook `mint!` calls changed for it: the fleet is handed what makes the session and calls it when it grants
  the handle, where it used to answer and leave the session to be made after. The gateway's own minting uses
  the same hook, under the lock registrations are settled under. Without the follower's lock: 2 failing
  assertions. The first test written for this passed without the lock as well — it watched for beats in a
  window in which a follower sends none, its first second — and was rewritten to wait for the cadence first.

## The two small ones

- **A batch pairing a cancellation for a request running in the gateway's own JVM with a call for a node** is
  for two machines, and refused whole. The notice named no place before, and the batch was carried to the
  node: 2 failing assertions.
- **A wait for a node that is already attached** sweeps the finished waits like any other: 1 failure.

## Tried between JVMs

A gateway and a follower as separate JVMs on one machine, driven over the legacy wire:

- A session opened on the node through the gateway evaluated there; the gateway's roster showed it.
- A session opened directly on the follower was routed through the gateway at once: attached, a direct mint
  registers too.
- A session released on the node was refused through the gateway at once, and again after a beat had passed,
  in the same words both times.
- The gateway was killed. The follower went on serving directly and minted a session while detached. A gateway
  started at the same address had the follower back within the time it took to ask, and routed both the old
  session and the one minted while it was away. A session that had lived in the first gateway's own JVM was
  refused.
- An event stream and an upload went through as before.

Faults were not injected between the JVMs this time; the suite carries those.

210 tests, 846 assertions.
