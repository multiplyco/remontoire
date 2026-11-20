# 2026-11-20 — The upload door, built and tried against Claude Code

A file edited on the client's disk cannot be loaded by a JVM on another host: `require :reload` and `load-file` read
the host's disk, and the only way across MCP offers is a tool argument, written out by the model. Built a staging
route beside `/mcp` (§4) and ran it against Claude Code through `bb dev`, two sessions (`pbg`, `knk`), loopback only.

*The loop works without the source passing through the model.* `curl -T a/greeter.clj …/upload/pbg/greeter.clj`
answered `201` and one JSON line naming the staged path; `(load-file path)` in the next eval defined
`trial.greeter/greet`. The file was edited and sent again, landed at the same path, and a second `load-file` picked up
the new definition. The path is stable for the session, so the eval is the same call on every cycle.

*A trailing slash is enough.* `curl -T a/greeter.clj …/upload/pbg/` staged the file as `greeter.clj`: curl appends the
local file's name to a URL ending in `/`.

*`load-file` leaves the session where it was.* The staged file opened with `(ns trial.greeter …)`; the eval ended in
the session's scratch namespace as it began.

*The staged path is what the vars remember.* `:file` on the loaded var was the staged path, `:line` correct, and
`clojure.repl/source-fn` answered nil for it — source readers look on the classpath, and a staged file is not on it.

*Ordinary Clojure is enough afterwards.* `Files/move` took one staged file out and the next `(__r/uploads)` no longer
listed it; nothing had to be told. The directory it was nested in stayed behind, empty, until the session ended.

*Uploads die with their session.* Releasing session `knk` deleted its directory and the file in it; `pbg`'s listing,
which had shown that file with `:by "knk"`, stopped showing it.

*Refusals leave nothing behind.* An unknown session: `404`, and no session by that name afterwards. `../../escape.clj`
sent with `--path-as-is`, and `%2e%2e/escape.clj`: `400`. A foreign `Origin`: `403`. `GET`: `405`. A name beneath a
staged file (`greeter.clj/inner.clj`): `409`. A 70 MB body: `413` with its length declared, and `413` again sent
chunked with no length; neither left a file in the session's directory or a partial beside it.

*The address guess was right on a direct connection.* `(__r/upload-url)` answered `http://127.0.0.1:7888/upload/pbg/`
and printed that it was a guess; a file sent there landed.

*Placing is mechanical on a checkout.* An unchanged copy of `patterns/cleanup.clj` was staged, moved over the original
with `patterns.uploads/place!`, and reloaded with `(require … :reload)`; the working tree showed no change. Given a
file declaring `clojure.data.json`, which lives in a jar, `place!` refused and left the file staged.

*Many files travel as one archive.* In a throwaway repository with two edited files and one untracked, `git archive
HEAD` carried the committed text, not the edits; `zip -@` over `git diff --name-only HEAD` and `git ls-files --others
--exclude-standard` carried all three as the working tree had them. The 609-byte zip was staged like any file, and a
`ZipInputStream` loop in an eval unpacked it under a temp root with the repository's paths intact. Tar would have
needed a tool on the host; zip needs only the JDK. Not built into the toolkit: which files, and where the root is, are
the client's to know. The walkthrough is in the `uploads` pattern's comment block.

*Suite-falsified: the listing is not the caller's alone.* The first test of the route asserted that a session's
`(__r/uploads)` held exactly the one file it had sent, and failed against `["notes/a.edn" "a.edn"]` — another test's
session, live in the same JVM, had staged a file too. The listing is shared by design; the test now filters on `:by`.

Not tried: a remote host, a host running from a jar, a proxy in front of the endpoint. The forwarded-header handling
is covered by the suite only.

Decided against on the way: a registry of uploads (the directory is the record), a name-to-path resolver and an
eviction call (the answer to the `PUT` carries the path, and deleting is `Files/delete`), and a sweep of old upload
directories at startup — it would have had a dev server and a test run on one machine deleting each other's files, so
each JVM has a root of its own, removed at exit.

The experimental MCP file-upload extension was read the same day as the alternative. Its shape fits — a tool argument
the client fills from disk, the bytes kept out of model context, inline for small files — and nothing implements it
yet; the route above is what works meanwhile, for a client with a shell.
