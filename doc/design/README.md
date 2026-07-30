# Remontoire — design

A REPL-first toolkit for reading, navigating and inspecting a Clojure codebase and its running system, built so that an
agent (and a human) can drive it through a single connection.

Design conversation with Henrik, 2026-07-30. Findings marked **verified** were checked against `fluent-platform` during
that conversation; everything else is a decision or an open question.

Revised 2026-07-31: transport, lifecycle and exposure settled — §4 rewritten, §5 and §9 amended, §11 and §12 updated.
§4's deployment shape is the presumed target, not independently checked.

Split into one file per section 2026-08-07. The reference convention, here and throughout the codebase's docstrings
and comments: **`§<n>` refers to the file numbered `<n>` in this directory** — the leading number is the reference
(zero-padded on disk for sort order, so §4 is `004-connection.md`), the title after the dash is decorative. The dated record of builds, trials and smoke tests is [`../logbook.md`](../logbook.md).

1. [Premise](001-premise.md)
2. [Architecture: one tool, many functions](002-architecture.md)
3. [The core invariant](003-core-invariant.md)
4. [The connection (v0)](004-connection.md)
5. [The registry](005-registry.md)
6. [`shape`](006-shape.md)
7. [`nav` — static navigation](007-nav.md)
8. [Refactoring](008-refactoring.md)
9. [Quiescent integration](009-quiescent-integration.md)
10. [Task-tree tracing](010-task-tree-tracing.md)
11. [Open questions](011-open-questions.md)
12. [Build order](012-build-order.md)
13. [Fixture corpus](013-fixture-corpus.md)
