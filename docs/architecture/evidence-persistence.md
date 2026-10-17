# PostgreSQL evidence persistence

M1.4 adds `dev.changeassurance.persistence`, consuming the unchanged M1.2/M1.3
contracts. It publishes immutable evidence and coverage atomically and reads them
by explicit publication identity. It implements no HTTP endpoint, keyword/vector
retrieval, embeddings, LLM integration, Q&A, UI, or change-impact analysis.
[ADR-0004](../adr/0004-atomic-evidence-publications.md) records the storage and
publication decision under ADR-0001 through ADR-0003.

## Service contract

With the `persistence` Spring profile enabled, inject `EvidencePersistenceService`:

```java
var ingestion = ingestor.ingest(selection);
var processing = processor.process(ingestion);
var published = persistence.publish(ingestion, processing);
var restored = persistence.findPublication(published.id()).orElseThrow();
var chunks = persistence.findChunks(published.id()).orElseThrow();
```

`PersistedPublication` contains the logical publication ID, snapshot ID, derivation
ID, and round-tripped `IngestionResult` and `ProcessingResult`. Missing identities
return `Optional.empty()`; a known empty publication returns a present empty chunk
list. Invalid input raises `IllegalArgumentException` (null required values also
fail immediately). Immutable conflicts/database failures raise
`EvidencePersistenceException`, with database causes retained. The service can be
constructed with a `DataSource` for independent tests. It accepts no filesystem path
and never executes Git, JavaParser, or CommonMark.

## Technology and schema

Spring JDBC provides prepared SQL, batching, exception translation, and explicit
transactions. Immutable records and publication membership do not benefit from JPA
entity state tracking, lazy associations, or ORM-generated schema. PostgreSQL stores
original text without normalization; JSONB stores structured metadata only.

Boot 4.1.1 manages JDBC/HikariCP, PostgreSQL JDBC 42.7.13, Flyway 12.4.0, and
Testcontainers 2.0.5. `spring-boot-starter-flyway` enables Boot's Flyway integration;
`flyway-database-postgresql` supplies the required
[PostgreSQL database support](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database).
No transitive versions are overridden. Local development and tests pin
`pgvector/pgvector:0.8.7-pg17-bookworm` by multi-platform digest (PostgreSQL 17.11).
See the [embedding guide](embedding-indexing.md#postgresql-setup-and-existing-data)
for the required export/import when moving an existing Alpine volume.
Review image/dependency updates together with migration and round-trip tests.

| Table | Identity and responsibility |
| --- | --- |
| `repository_snapshot` | SHA-256 snapshot ID; repository, full commit, canonical scope JSONB, ingestion policy |
| `source_artifact` | Composite snapshot/path-key primary key; original path, classification, format, blob ID, SHA-256, exact source text |
| `evidence_chunk` | Composite snapshot/M1.3 chunk-ID primary key; source reference, inclusive lines, quoted text/hash, parser, configuration, context JSONB |
| `processing_publication` | Logical publication ID; snapshot, derivation, configuration, status, metadata JSONB |
| `publication_chunk` | Publication/chunk primary key and unique ordinal; explicit ordered membership with composite foreign keys enforcing snapshot agreement |

`path_key` is SHA-256 of the exact UTF-8 relative path. The original path remains a
column and is checked on readback. A fixed-size key avoids PostgreSQL B-tree entry
limits for long repository paths; a hash collision is a payload conflict, not an
overwrite. No surrogate replaces the M1.3 evidence ID exposed to callers.

Publication metadata includes ordered source paths, configuration values, artifact
outcomes (parser/status/parsed/count/typed limitations), original ingestion coverage,
ingestion limitations/summary, overall processing status, and processing limitations.
These nested diagnostics have no current independent query lifecycle, so JSONB avoids
extra tables. Source/chunk content remains relational TEXT, separate from context.
Primary keys, foreign keys, unique ordinal/derivation constraints, required columns,
digest shapes, source-size limits, and valid line bounds provide database checks.
Only primary/unique indexes are added; M1.5 search indexes are not implemented.

## Snapshot, derivation, and publication identities

Identity inputs are serialized as compact JSON arrays using the module's fixed
Jackson mapper, encoded as UTF-8, then SHA-256 hashed. Arrays make field order and
boundaries explicit; no timestamps, clone paths, or random values participate.

- Snapshot: `["snapshot-v1", repositoryId, commitSha, scope.includes,
  scope.excludes, policyVersion]`. Scope is canonicalized by the existing M1.2
  record, so duplicates/order in selection inputs have their established semantics.
- Derivation: `["processing-contract-v1", sortedDistinctOutcomeParserIdentities]`.
  The fixed contract version identifies the processing/outcome interpretation.
  Empty selections have an empty parser list because no artifact was parsed.
- Publication: `["publication-v1", snapshotId, chunkingConfiguration.identity(),
  derivationId]`. Configuration includes strategy version, max lines, and max chunks.

M1.3 chunk IDs are retained exactly and verified against its documented length-
prefixed SHA-256 format. The validator repeats only that small identity format,
not the parser/chunker; regression tests compare it with actual M1.3 output.

This uses **scope-specific evidence with explicit publication membership**. Same
repository/commit/span chunks can keep the same M1.3 ID across two scopes, while
being stored beneath different snapshot IDs. Different configurations share source
artifacts in a snapshot but have their own publications and chunk memberships.
No read merges publications or selects an implicit latest result.

Idempotency compares complete stored records, not only hashes or conflict counts.
If the same logical identity arrives with different evidence, ordering, outcomes,
or diagnostics, publication fails. Results are immutable, not retry histories:
recovery from transient ingestion gaps with unchanged snapshot/configuration/parser
identities is a conflict after a prior publication. There is no overwrite or automatic
supersession. A future explicit attempt/supersession policy requires a versioned
contract decision; callers should publish their intended final processing result.

## Validation and transaction boundary

Before beginning database work, validation checks snapshot agreement, unique
inventory paths, supported inventory/byte/chunk limits, source SHA-256 and Git blob
digests, and unchanged ingestion coverage. It checks every chunk's provenance,
parser/configuration, deterministic ID, content hash, exact original physical-line
slice, ordering, and absence of overlaps. LF, CRLF, CR, BOM, Unicode, and final-
newline state remain unchanged. Outcome counts, parsed flags, statuses, and
limitations must agree with emitted coverage. Missing source lines require an
explicit resource limitation. Malformed input is never reparsed to repair it.

Each publish uses a Spring `TransactionTemplate` with `REQUIRES_NEW`, READ COMMITTED,
and a 120-second statement transaction budget. It commits independently of any
caller transaction; caller rollback does not undo a completed publication.

1. Insert/reuse snapshot and lock its row `FOR UPDATE`; compare snapshot payload.
2. If the publication exists, read it and compare the entire immutable payload.
3. Batch insert/reuse source artifacts and chunks in groups of 256.
4. Insert the publication metadata and ordered chunk membership.
5. Read the result inside the transaction and compare all records with the input.
6. Commit only after equivalence is established; any exception rolls back writes.

Snapshot locking serializes writers for the same selected snapshot, including first
inserts, while allowing independent snapshots to proceed. Inserts use `ON CONFLICT
DO NOTHING` only in conjunction with full payload comparison. Conflicting source or
chunk records fail rather than silently reusing incorrect data. Database errors in
membership insertion leave no half-publication or orphan writes from that operation.
Concurrent identical writers return one logical publication. Hash collisions are
handled as immutable conflicts. A connection failure during commit can leave the
client uncertain whether PostgreSQL committed; retry the identical payload to resolve
that uncertainty idempotently, rather than assuming every transport error rolled back.

Processing PARTIAL/FAILED statuses are legitimate stored outcomes, not database
failures. Original unsupported, failed, resource-excluded, and intentionally excluded
coverage survives readback. Successful publication means database consistency, not
complete parsing or verified software behavior.

Reads are exact-identity operations over immutable committed records. M1.5 adds
[focused read-path verification](keyword-retrieval.md) in a read-only repeatable-read
transaction before reconstruction, detecting incomplete or inconsistent membership.
The database and its administrators remain trusted: this is not tamper-proof storage
against arbitrary privileged SQL. Schema modification, deletion, and retention are
outside the service contract.

## Configuration and local PostgreSQL

Default startup excludes DataSource and Flyway auto-configuration only when the
`persistence` profile is absent. There is no database bean, persistence service,
migration, database health check, or Docker auto-start in this mode. Existing startup
and HTTP health tests remain unchanged and database-independent.

With `persistence` active, standard Boot DataSource/Flyway auto-configuration runs.
The service depends on database initialization. Missing URL/credentials or an
unreachable/invalid database fails startup; it does not fall back to in-memory
storage. Flyway validates and applies migrations before the service becomes usable.
The enabled profile adds normal database health behavior. External properties are:

- `EVIDENCE_DB_URL`: PostgreSQL JDBC URL.
- `EVIDENCE_DB_USER`: database login.
- `EVIDENCE_DB_PASSWORD`: database password.
- `EVIDENCE_DB_PORT`: optional Compose host port, default 5432.

**Existing pre-M1.7a databases:** follow the
[database preservation procedure](embedding-indexing.md#postgresql-setup-and-existing-data)
before recreating the Compose container. The new volume starts empty.

For fresh disposable local development, generate a password in your shell (not production
credentials). Keep these exports in the same shell; an ignored `.env` may also hold
local values, but Compose's `.env` is not automatically exported to the JVM.

```sh
export EVIDENCE_DB_USER=evidence_dev
export EVIDENCE_DB_PASSWORD="$(openssl rand -hex 24)"
export EVIDENCE_DB_URL=jdbc:postgresql://localhost:5432/evidence
docker compose up -d --wait
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence
```

Compose runs one PostgreSQL service, binds only to `127.0.0.1`, and uses a named
volume. Credentials are mandatory substitutions, never committed values. Initial
credentials initialize a new volume; changing environment values does not reset an
existing database user. Preserve the local password while reusing that volume.

Inspect evidence by explicit ID (substitute an actual returned ID):

```sh
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d evidence'
```

```sql
SELECT publication_id, snapshot_id, configuration, status FROM processing_publication;
SELECT c.chunk_id, a.path, c.start_line, c.end_line, c.content_hash
FROM publication_chunk m
JOIN evidence_chunk c USING (snapshot_id, chunk_id)
JOIN source_artifact a USING (snapshot_id, path_key)
WHERE m.publication_id = '<publication-id>' ORDER BY m.ordinal;
```

Stop while preserving data: `docker compose down`. For an intentional destructive
reset of **this local development database**, use `docker compose down -v`, then
`docker compose up -d --wait` and restart the persistence-enabled application.
No reset operation is exposed by production Java code.

Migrations live in `src/main/resources/db/migration`. Add a new `V<N>__description.sql`
for changes; never edit an applied migration or use Flyway repair to hide drift.
Flyway clean is disabled. Tests create a fresh PostgreSQL database and verify both
first migration and subsequent no-op validation. No application startup ingests
repositories or publishes data automatically.

## Testing, CI, and limits

```sh
python3 scripts/check_repository.py
./mvnw test
./mvnw clean verify
```

`test` runs the existing non-database tests plus boundary/configuration tests.
`verify` additionally runs `*IT` via Maven Failsafe against real PostgreSQL using
[Testcontainers](https://java.testcontainers.org/modules/databases/postgres/).
Docker must be running, and initial image/dependency downloads need network access.
Tests generate disposable database credentials and require no cloud services.
Missing Docker causes failure, not skipping; Failsafe also fails if no integration
tests are discovered. CI checks Docker availability and runs the same full verify.
To focus on database tests after compilation, use
`./mvnw test-compile failsafe:integration-test failsafe:verify`.
Do not pass `-DskipTests` for database verification: it also skips Failsafe tests.

Tests cover migration constraints, exact records/content, idempotency, commits,
scopes, configurations, parser versions, concurrency, conflicting identities,
rollback, startup configuration, and processing coverage. The unchanged Parcel
Quotes corpus passes through Git ingestion, processing, PostgreSQL, and readback;
all 10 ground-truth references are verified across chunk boundaries. This validates
source provenance, not retrieval relevance or semantic answer support.

Ingestion limits remain 10,000 entries, 1 MiB/source, and 16 MiB total source;
processing limits remain those in `ChunkingConfiguration`. Persistence additionally
bounds serialized publication metadata and aggregate chunk-context JSON to 16 MiB
each, and validates paths using M1.2's
scope-path safety rules (including its 4,096-character limit). PostgreSQL TEXT
cannot represent NUL; source validation rejects it. Whole-publication reads and
validation retain bounded source plus chunk objects in memory. Readback during
publish adds one bounded result copy; `findChunks` currently reconstructs the whole
publication. No pagination, cache, async jobs, or indefinite retries are introduced.
Transaction query timeout does not impose a hard CPU deadline on in-memory validation.

M1.5 [keyword retrieval](keyword-retrieval.md) selects a publication ID, verifies
consistency, and ranks its member chunks in PostgreSQL without loading full source
bodies. V2 adds generated search vectors and indexes; V1 is unchanged. Public
`PublicationSummary` and immutable `PublicationMetadata` expose identity and coverage
without source bodies. Existing publication/read signatures and IDs are unchanged.
Pagination and retention remain unimplemented.
