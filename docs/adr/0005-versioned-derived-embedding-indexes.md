# ADR-0005 — Versioned Derived Embedding Indexes

## Status

Accepted for M1.7a. Explicit embedding indexing and persistence are implemented.
Semantic and hybrid retrieval remain subsequent increments.

## Context

Immutable evidence publications already establish source identity, membership,
processing coverage, and provenance. Embeddings depend on model artifacts and input
preparation; a mutable model tag cannot establish compatibility. Generating every
vector inside the evidence publication transaction would couple inference failures
and latency to authoritative evidence storage.

## Decision

Store embeddings separately under `(snapshot_id, chunk_id, profile_id)` with foreign
keys to existing evidence and an immutable profile specification. Profile identity
includes provider, resolved model digest/name, dimensions, both input recipes,
effective options, and metric. Hash exact prefixed UTF-8 model input and preserve
complete source content. Explicitly reject oversize inputs; disable truncation.

Use PostgreSQL/pgvector and the existing Spring AI Ollama integration. The first
supported profile uses Nomic text embeddings with 768 dimensions. Index only an
explicit existing publication. Generate outside database transactions and commit
one validated vector per bounded transaction. Reuse compatible rows and resume
missing work after failure; never update an existing vector or evidence record
through application operations. Derive completeness from publication membership
and persisted rows, and keep it distinct from processing coverage.

Use opt-in local execution, bounded requests, no automatic downloads, no background
jobs, and no silent model substitution. CI uses deterministic provider doubles and
real PostgreSQL. There is no vector search or approximate index in this increment.

## Alternatives considered

- Embeddings per publication: straightforward membership but duplicates embeddings
  for shared persisted chunks.
- Global input-content cache: could reuse across snapshots but adds separate
  provenance mappings and conflict rules before a measured need exists.
- Atomic whole-publication indexing: hides partial progress and requires repeating
  successful inference after a later failure, or a larger staging mechanism.
- Mutable completion flags/job records: add coordination state that can disagree
  with actual row coverage. Current explicit CLI execution can derive coverage.
- Spring AI vector-store schema: adds generic document persistence beside the
  project's existing immutable chunks and publication membership.

## Consequences

Profiles and embedding rows have insert/compare semantics, following ADR-0004's
application immutability model. Privileged database administrators remain trusted.
Processing PARTIAL/FAILED status is not erased by complete embedding coverage.
Concurrent indexers can duplicate inference but cannot insert duplicate rows;
compatible first-committed vectors win. Model tag changes observed around a call
reject its output. Replacing a model mid-run remains unsupported.

## Tradeoffs

Fixed `vector(768)` storage makes dimensionality explicit and requires a migration
for other dimensions. Source-only input may lack useful structural metadata; future
recipes must be versioned and evaluated. One-row writes favor bounded progress over
throughput. Full publication readback reuses existing bounded persistence behavior.
No tokenizer estimate guarantees fit; model context rejections remain explicit.
Snapshot-specific keys sacrifice cross-snapshot reuse for direct provenance.

See the [indexing guide](../architecture/embedding-indexing.md) for configuration,
local database preservation, failure categories, and verification.
