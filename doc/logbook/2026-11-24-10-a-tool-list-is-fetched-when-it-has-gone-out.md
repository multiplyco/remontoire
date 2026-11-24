# 2026-11-24 — A tool list is fetched when it has gone out

A legacy connection is told `tools/list_changed` on each stream until it has fetched the current list (§4).
Found in review, in 0.9.0 as well: with the write of a `tools/list` response failed, the next call carried no
announcement. The handler recorded the list as fetched while building the response, before the transport wrote it.

The record is now the response's delivery mark, which the transport runs after the write that carried it, like
every other mark (§5).

A mark runs later than the handler did, and a connection can be gone by then. Found in review of the change: with
the list's write held open and a `DELETE` sent meanwhile, the mark put the connection back, holding nothing but a
fetched list. The record is now written only to a connection that is still there, as a session's are.

The test dispatches a `tools/list` and looks before and after running its mark, then does the same with the
connection forgotten in between. Against the old handler it fails on one assertion and errors on the next, there
being no mark to run. With the record written to a forgotten connection, two assertions fail.

A batch carrying both a `tools/list` and a tool call on one stream now gets an announcement it did not need: the
stream asks whether the list is stale before the batch's marks have run. It costs the client one extra fetch. No
client measured so far sends a batch.

Left as it is: a connection id that is forgotten and then presented again is adopted afresh, and a list written
under the old row then counts as fetched by the new one. In review this took a held write, a `DELETE` and a ping
on the same id, in that order. The record answers a narrow question, whether the current list was written out
under this connection id, and for a cache hint the id coming back is basis enough to keep the answer. It says
nothing about who the client is: an id never seen before is adopted from whoever presents it.
