# 2026-11-24 — `init!` through its public face

Found in review, in 0.9.0 as well, by calling `init!`, `status` and `stop!` as a host does:

- With the port taken, `init!` threw after it had set the registry capacity and the server description and
  swapped the tees in at the roots of `*out*` and `*err*`. Nothing was running, so `status` was nil and `stop!`
  did nothing: the host's output stayed wrapped, and the capacity and description were those of the failed call.
- With an `:allowed-origins` that was not a collection, `init!` threw after the port was bound and kept it. A
  corrected call on the same port failed with "Address already in use" for as long as the JVM lived.
- With `:port 0`, `init!` and `status` reported the address with port 0, though the server had bound a real one.
  The transport's test fixture got the port out of private state instead.
- With `:bind "::1"` the address was `http://::1:<port>/mcp`, which does not parse as a URL.

`init!` adopts its options first and binds last, as before, so that no request arrives ahead of the
configuration. What it adopted it now puts back when the start fails: the capacity and the description that were
there, and the tees if this call installed them. The transport reads its options before it takes the port.

Binding first and adopting after was tried and withdrawn. It leaves a server that has bound and not started, and
the JDK's `HttpServer` does not give such a port back: `stop` closes a channel still registered with a selector
that only the dispatcher thread, never started, would close. Measured: a server stopped without starting left its
port "Address already in use"; one started and then stopped did not. Under that order a `:capacity` refused
after the bind kept the port too. So nothing that can fail sits between binding and starting.

The address reported is the one the server holds once bound, host and port both. Writing it from what was asked
for went wrong three ways: port 0; `::1` without brackets, and `[::1]` with two pairs once brackets were added by
hand; and `127.1`, which binds loopback and which `java.net.URI` refuses as a host. That last one threw after the
server had started, leaving an endpoint that answered while `status` was nil and `stop!` did nothing. A literal
address always makes a URL, so a `:bind` given as a name is now reported as the address it resolved to. And a
failure once the server answers stops it and puts back what was adopted, whatever the failure is. The fixture
uses the address `init!` returns.

A start that fails puts the old capacity back, so the public `capacity!` now takes `init!`'s lock. Without it, a
capacity set while a start was under way was overwritten when that start failed: in review the setter returned
37 and the capacity ended at 9.

Last, the line `init!` prints once it listens. With `*out*` bound to a closed writer the print threw, and `init!`
passed that on with the endpoint started and recorded: `status` showed it and `stop!` could stop it, but the
caller had an exception where the result should be. `stop!` prints the same way. Both lines are now best effort.

Tests, in a namespace of their own: a start on a taken port after a successful one with other settings; a start
refused for its `:capacity` and one for its `:allowed-origins`, each followed by a start on the same port; a
start that fails once the server answers, followed by a start on the same port; a capacity set while a failing
start is held open; a start on any free port, connected to at the address returned; the address for
`127.0.0.1`, `127.1`, `::1` and `[::1]`, each of which must resolve back to what was bound; and a start and a stop
with `*out*` closed.

- Capacity not put back: one assertion fails. Description not put back: one. Tees left in: two.
- The port taken before the transport has read its options: the retry errors.
- Capacity applied after the server has started: the retry errors.
- A late failure leaving the server answering: the retry errors. Putting nothing back after it: three fail.
- The capacity setter not waiting for a start: two fail.
- The lines printed without a guard: the test with a closed `*out*` errors.

No single removal brings back the address written as asked for, since the function no longer takes that string.
Checked by hand: `URI` built from `127.1` throws, and from the bound address gives `http://127.0.0.1:<port>/mcp`.
