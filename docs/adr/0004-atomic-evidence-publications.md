# ADR-0004 — Atomic PostgreSQL Evidence Publications

## Status

Accepted for M1.4. PostgreSQL evidence persistence and exact-identity readback are
implemented; keyword/vector retrieval and answer generation are not.

## Context

M1.2 snapshots include repository, commit, selection scope, and ingestion policy.
M1.3 chunks have deterministic IDs independent of scope, while outcomes and coverage
belong to one processing result. Storing a bag of chunks would merge configurations
and could conceal partial processing. ADR-0003 requires preserving this distinction
but deliberately left persistence technology and schemas to M1.

## Decision

Use PostgreSQL with Spring JDBC and Flyway inside the modular monolith. Publish one
validated ingestion/processing result in an explicit transaction, identified by its
snapshot, processing configuration, and versioned parser derivation. Preserve exact
source text and retain all coverage, outcomes, and limitations.

Use scope-specific source/chunk rows with explicit ordered publication membership.
Retain the existing chunk ID beneath the snapshot key. Store provenance/relationships
relationally and nested outcome/coverage metadata in JSONB. Compare complete stored
payloads on identity reuse; reject conflicts and never silently overwrite evidence.
Serialize same-snapshot writers using a database row lock. A processing PARTIAL or
FAILED status can be published successfully; database failure rolls back publication.

Enable database connectivity/migration through the explicit `persistence` profile.
Default application startup stays database-independent. Full Maven verification
requires real PostgreSQL integration tests via Testcontainers, with no silent skip.

## Alternatives considered

- JPA: adds entity lifecycle and ORM mapping without a current mutable entity model
  or benefit over explicit relational inserts and joins.
- Canonical shared evidence across scopes: saves storage but adds separate snapshot
  artifact membership and more provenance reconstruction. It can be revisited if
  measured duplication justifies that complexity.
- Full-result JSON documents: simplify writes but weaken relational source/member
  integrity and future evidence access.
- Independent per-chunk commits: permit half-published results and mix coverage.

## Consequences

The service commits independently of an outer caller transaction (`REQUIRES_NEW`).
Publications are immutable and reads choose explicit identities. Same-identity
payload changes, including recovered coverage, are rejected; overwrite, attempt
history, supersession, deletion, and retention need future explicit policy.
Snapshots/configurations remain distinguishable even when chunk IDs overlap.

## Tradeoffs

Scope-specific records duplicate source/evidence across selections. Snapshot locking
limits concurrent publication throughput for one selection, and full payload
comparison/readback costs bounded memory and I/O. JDBC requires maintaining SQL and
mapping code. These costs favor inspectable correctness at M1's existing resource
limits over a generalized content-addressable store or ORM infrastructure.

See the [persistence guide](../architecture/evidence-persistence.md) for schema,
identity encoding, configuration, verification, and limitations.
