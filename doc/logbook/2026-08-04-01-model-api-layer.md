# 2026-08-04 — The model API layer

Built 2026-08-04 — see §4. Contents to be tuned in use; candidates from §12 join it
as they land. 2026-08-05: its docstrings made self-contained — no § pointers, no internal namespace names, no
design-doc episodes — because `(outline '__r {:include-docstrings true})` is the planned `instructions` bootstrap:
the docstrings are the manual a fresh model reads, assuming only the conceptual foundation `instructions` itself
will carry. The § apparatus stays in the internal namespaces, whose readers have this document. The full-docstring
outline measures ~1.4 k tokens — one roundtrip replacing piecemeal `doc` discovery, cheap for a once-per-session
orientation.
