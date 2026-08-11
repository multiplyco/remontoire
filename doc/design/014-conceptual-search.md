# 14. Conceptual search

Design sketch, 2026-08-11 — recorded from the fifth in-situ trial's wish (see the logbook) and the design
conversation that followed. Nothing here is built; decisions are marked as such, the rest is open.

## The gap, and why it is not `vars`' gap

`vars` answers "where does *X* live" when some of X's spelling is known — its tiers are deterministic and
explainable, and 0.4.0's qualified match made the query language precise. Conceptual search answers "where do
we *do* X" when the vocabulary is unknown: "where do we throttle" finding the var whose docstring says
rate-limiting. Scored guesses that can legitimately miss are a different contract from `vars`' precision
promise, so **decided: a separate entry point** (working name `find`), not a ranking upgrade inside `vars` —
the same settlement as vars/outline: one representation, two addressing modes. `find` returns the entry shape
`vars` returns, and its envelope **says which tiers answered from day one**, so a lexical-only first release
and an embedder-warm later one return the same envelope with more in it.

## The corpus: the var is the chunk

The perpetually-botched half of semantic search over code is chunking — file-text splitters produce garbage.
In a live JVM the problem does not exist: ns/name + arglists + docstring + source is a naturally-bounded,
self-describing document, enumerable by the machinery `vars` already walks, dependency jars included — which
is where vocabulary knowledge is weakest and the tier matters most. Kebab-case names split into words when
building the text; names carry much of the signal. Loaded namespaces only, the same blind spot as all of §7,
honestly held.

## Tiers

**Lexical, always available.** Ranked BM25 over the var corpus: small, dependency-free, builds inline in well
under a second at loaded-system scale, no index to go stale beyond the dirty tracking below. Where docstring
discipline is high this closes most of the vocabulary gap on its own — which is why it ships first and alone.

**Semantic, strict opt-in.** Embedding vectors over the same corpus. At tens of thousands of vars, a
brute-force cosine scan over a flat float array is single-digit milliseconds — **decided: no ANN index, no
search-engine dependency**; reaching for one at this scale is pure complexity.

**Decided, absolute: nothing leaves the process.** No remote embedding fallback, ever. For a tool pointed at
live systems, "source and docstrings never cross the process boundary" is the property that makes the tool
trustworthy, and it holds unconditionally — an absent embedder means the semantic tier reports unavailable,
never that text goes elsewhere.

## The embedder slot

**Decided: a protocol, not a multimethod** — there is no dispatch value; an embedder is one thing with
behavior, wired one at a time, and a host with an embedder already in the JVM extends the protocol onto it
(`extend-protocol`, or `reify` over their client) and hands the instance over. The contract is small and
batch-first, and it must expose model identity, because the vector cache is keyed by it:

```clojure
(defprotocol Embedder
  (embed [this texts] "batch of strings → vectors, all-or-throw")
  (info  [this]       "{:model String, :dims Long} — keys the vector cache"))
```

`info` is load-bearing: dirty tracking is `{model, var-content-hash} → vector`, so swapping embedders
invalidates everything with no extra machinery, and hot reloads re-embed only the vars whose source or
docstring changed — the live-system promise file-based indexers structurally cannot make.

## The endorsed embedder: a second artifact

Core never depends on an embedding runtime — native libraries argue for optional-dependency, not bundled, and
core's classpath stays boring. The vendor-endorsed implementation is its own artifact (working name
`remontoire-embedder-onnx` — name the mechanism; a second endorsed embedder is conceivable): the ONNX Runtime
dependency plus the protocol implementation, so that adopting the semantic tier is two entries in deps.edn.

Model weights do not ship in the jar — a MiniLM-class model is ~90 MB and artifact repositories carry that
badly. **The artifact is a thin fetch-if-absent lib**: given a target location, download the weights there on
first use if not already present, and load from a host-supplied local path otherwise — the local path is not
optional, because a production JVM may be airgapped. Fetching does not dent the privacy absolute: that is an
egress guarantee about source and docstrings, and weights flow in.

## Wiring

Explicit wins: an `:embedder` option at `init!` (or a setter beside `capacity!`). But two deps.edn entries
should *suffice*, without classpath sniffing at init: when the first conceptual query arrives with no embedder
wired, core `requiring-resolve`s the endorsed artifact's well-known constructor — present, it is wired and the
response footer says so; absent, the lexical tier answers alone and the footer says the semantic tier is
unavailable and what enables it. Classpath presence as intent, announced in-band — consistent with
announced-never-silent, and `init!` stays inert-shaped.

## The lazy shape

Falls out of the async model. The first `find` builds the lexical index inline and answers immediately; if an
embedder is present, corpus embedding starts as a background task, its fill reported through the progress
line the way progress is always reported. Later calls merge tiers and say which answered — results that
silently improve over a session, with the mechanism visible. A warm-start cache (`{model, var-hash} →
vector` persisted to a temp path, hashes validated on load) is nice restart economics with zero correctness
burden, and strictly optional.

## Sequencing

Lexical tier first, shipped alone — expected to carry most of the value wherever docstrings are disciplined;
the embedder tier's best returns are dependency jars and poorly-documented codebases, which a general tool
must still serve. The protocol boundary and the tier-reporting envelope are the two things to get right when
`find` first ships; the embedder artifact can trail by releases without the design losing coherence.
