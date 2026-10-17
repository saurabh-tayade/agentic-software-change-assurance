# Versioned embedding indexing

M1.7a adds explicit local embedding generation and PostgreSQL/pgvector storage for
an existing publication. M1.7b adds [exact vector search](vector-retrieval.md);
M1.7c adds [hybrid search and Q&A strategy selection](hybrid-retrieval.md). Embeddings are derived
indexes; the existing evidence and publication contracts remain authoritative. See
[ADR-0005](../adr/0005-versioned-derived-embedding-indexes.md).

## Architecture and identity

`EmbeddingIndexingService` reads one explicit publication through
`EvidencePersistenceService`, verifies every chunk with `PublicationIntegrity`,
and calls a small `EmbeddingGateway`. `OllamaEmbeddingGateway` uses Spring AI
2.0.1's `OllamaEmbeddingModel`; no additional Maven dependencies were needed.
`EmbeddingStore` uses Spring JDBC and insert-only application operations.

`EmbeddingProfile` contains provider, model name, resolved full SHA-256 model
digest, dimensions, document/query recipe versions, effective options, and metric.
Its ID hashes fixed-order, length-prefixed UTF-8 fields under
`embedding-profile-v1`. Options include `num_ctx=2048`, `truncate=false`,
`keep_alive=0s`, and the input-byte limit. The model digest identifies the installed
artifact; changing a tag's target does not silently reuse an earlier profile.
Hardware and Ollama runtime versions can still affect floating-point results.

The initial supported adapter uses `nomic-embed-text:v1.5` and 768 dimensions.
[Nomic's model card](https://huggingface.co/nomic-ai/nomic-embed-text-v1.5) specifies
document/query task prefixes. The
[Ollama package](https://registry.ollama.com/library/nomic-embed-text) is approximately
274 MB and advertises a 2K context window. This is a practical local candidate,
not a retrieval-quality result.

`nomic-document-v1` is exactly `search_document: ` followed by complete original
chunk content. No trimming, newline conversion, Unicode normalization, parser
metadata, path, or line-number decoration is added. SHA-256 covers the exact UTF-8
bytes sent to Ollama, including the prefix. `nomic-query-v1` defines the matching
`search_query: ` recipe, implemented for query generation in M1.7b. Neither recipe
nor the existing profile identities changed when query generation was added.

The default input cap is 8,192 bytes including the prefix. It is not an exact token
budget. Ollama truncation is explicitly disabled, so an input within the byte cap
can still be rejected by the model's tokenizer/context limit. Neither path silently
embeds a shortened source chunk. Local inspection with the existing processor found:

| Corpus | Max lines | Chunks | Largest prefixed input |
| --- | --- | --- | --- |
| Parcel Quotes v1 | 4 | 33 | 308 bytes |
| Parcel Quotes v1 | 80 | 28 | 337 bytes |
| Application Java source before M1.7a | 4 | 1,104 | 570 bytes |
| Application Java source before M1.7a | 80 | 772 | 6,058 bytes |

These are local chunk-size observations, not token counts or live model results.

## Schema and indexing lifecycle

V3 adds `embedding_profile` and `chunk_embedding` and enables the `vector`
extension. V1 and V2 are unchanged. Profiles are compared in full on identity reuse.
`chunk_embedding` uses `(snapshot_id, chunk_id, profile_id)` as its primary key and
has foreign keys to the original chunk and profile. `vector(768)` enforces the
initial dimension. Application validation also rejects zero vectors, nonfinite
values, response-count mismatches, and incompatible models. A stored vector checksum
is verified during reuse, using float32 bytes with signed zero canonicalized.

Publication membership comes from `publication_chunk`. A parser change affecting
only some artifacts can create a new publication that reuses unchanged chunks.
Those shared identities reuse embeddings. Different snapshots, including scopes,
do not share rows even when their chunk IDs or source text coincide. There is no
global content cache and no approximate vector index.

Indexing performs the following explicit sequence:

1. Read and verify the publication and every chunk; never ingest or parse again.
2. Resolve and compare the provider profile; insert or verify its stored record.
3. Verify existing vectors and their exact input hashes.
4. Generate each missing eligible chunk outside database transactions.
5. Verify returned dimensions/values and check the model digest again.
6. Insert one vector in a short transaction and verify the stored row.
7. Read back coverage and return a report.

An outer caller transaction is rejected so model calls cannot accidentally hold a
caller write transaction. Each write has a 30-second transaction timeout. Existing-vector verification uses
a read transaction with a 128-row JDBC fetch size to avoid buffering all vectors. A run
commits progress one chunk at a time; publication data is never updated. Unique
keys prevent duplicate persisted rows. Concurrent indexers may duplicate inference;
on conflict the existing compatible vector wins. No leases, background jobs,
automatic retry loops, or distributed locks are introduced.

## Completeness, failures, and recovery

The report contains publication/profile identity, source processing status,
`COMPLETE`, `PARTIAL`, or `FAILED` indexing status, total/indexed/missing counts,
reused and inserted counts, measured gateway duration, and up to 100 chunk issues
with original IDs, paths, and lines. `issueCount` reports all encountered issues.
The report includes no source quotations or vectors. A known empty publication can
complete without embedding calls. Processing coverage remains independent: a
`PARTIAL` source publication can have a complete index of its available chunks.

Oversized inputs report `OVERSIZED_INPUT`. Provider HTTP 400 responses report
`MODEL_INPUT_REJECTED`; the provider body is not exposed, so this does not assert
that every HTTP 400 is specifically a context-length failure. Indexing continues
past these chunk-specific rejections. Invalid vectors, profile mismatch, provider
unavailability/timeouts, or persistence failures stop further generation. Earlier
committed rows remain reusable. Partial/failed reports cause a nonzero CLI exit.
Preflight failures or a database unavailable during final readback may instead
produce a safe failure without a coverage report; rerun to inspect persisted state.

Oversized chunks require smaller chunks in a separately published processing
configuration, or a future explicitly versioned input recipe; repeating the same
rejected input does not repair it.

Rerun with the same publication and profile to resume missing work. Valid existing
rows require no new embedding calls. A model/profile change creates a separate
index; the CLI requires the explicitly expected full digest before indexing.
Do not overwrite a mismatching stored row or use Flyway repair to hide a conflict.
The database administrator remains trusted, as with existing evidence persistence.

## PostgreSQL setup and existing data

Compose and Testcontainers pin the multi-platform image
`pgvector/pgvector:0.8.7-pg17-bookworm` by digest in configuration. The tested image
reports PostgreSQL 17.11 and pgvector 0.8.7, with amd64 and arm64 variants. The
extension must be installed on the server before V3 runs; migration credentials
must be permitted to create it. Default application startup still uses no database.
Persistence-only startup now requires this extension-capable database but no Ollama.

Compose now uses the new `evidence-pgvector` volume. The previous
`evidence-postgres` volume is retained and is never mounted directly into the
Debian image. Do not delete/reset either volume. Existing publications are not
implicitly copied; export/import is an explicit local maintenance step.

For a fresh database, use the credentials and connection variables in the
[persistence guide](evidence-persistence.md), then `docker compose up -d --wait`.
For an existing running pre-M1.7a Compose database, stop application writers and
export **before recreating the database container**. Preserve the existing login
credentials. Example, from the same Compose project directory:

```sh
umask 077
mkdir -p "$HOME/.local/share/changeassurance-backups"
embedding_backup="$HOME/.local/share/changeassurance-backups/evidence-before-pgvector-$(date +%Y%m%d-%H%M%S).dump"
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d evidence -Fc' > "$embedding_backup"
test -s "$embedding_backup"
docker compose exec -T postgres pg_restore --list < "$embedding_backup"
```

Proceed only after a successful export and archive listing. If the old container
is already stopped or replaced, recover it against its original volume/image
before exporting; do not run the following import against a nonempty destination.
Record existing publication IDs for comparison. Backups contain repository content
and must remain outside Git. The following creates the new volume and restores into
its empty database, leaving the old volume and backup intact:

```sh
docker compose stop postgres
docker compose up -d --wait postgres
docker compose exec -T postgres sh -c 'pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d evidence' < "$embedding_backup"
```

Verify the original publication IDs/readback before indexing. The first
persistence-enabled application run then applies V3 to the restored V1/V2 schema.
Do not initialize the destination with application migrations before this restore.
The Testcontainers upgrade test verifies V3 over populated V1/V2 data; it does not
claim that a user's local volume export/import was executed.

## Local Ollama and CLI

Install/start Ollama separately, then explicitly download only the chosen model:

```sh
ollama pull nomic-embed-text:v1.5
ollama list
```

Activate `persistence,embeddings`. Construction of the gateway makes no model
requests; only explicit CLI operations do. The adapter accepts loopback HTTP
endpoints, disables redirects and model pulls, and checks `/api/tags` before and
after generation. This catches observable tag changes but cannot provide atomic
protection against an administrator changing and restoring a tag mid-request.
Do not replace the installed model during indexing.

First inspect the resolved profile:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,embeddings \
  -Dspring-boot.run.arguments='--embeddings.command=profile'
```

Use the returned `profile.modelDigest` and an explicit existing publication ID:

```sh
export EMBEDDINGS_EXPECTED_DIGEST='<full-64-character-model-digest>'
export EMBEDDINGS_PUBLICATION_ID='<existing-publication-id>'
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,embeddings \
  -Dspring-boot.run.arguments="--embeddings.command=index --embeddings.publication-id=$EMBEDDINGS_PUBLICATION_ID --embeddings.expected-digest=$EMBEDDINGS_EXPECTED_DIGEST"
```

| Environment variable | Default / purpose |
| --- | --- |
| `EMBEDDINGS_BASE_URL` | `http://localhost:11434`, loopback only |
| `EMBEDDINGS_MODEL` | `nomic-embed-text:v1.5`, explicit Nomic tag |
| `EMBEDDINGS_MAX_INPUT_BYTES` | `8192`, configurable from 32 through 8192 |
| `EMBEDDINGS_CONNECT_TIMEOUT_SECONDS` | `5`, range 1 through 30 |
| `EMBEDDINGS_TIMEOUT_SECONDS` | `60`, range 1 through 300 per HTTP request |

Timeouts bound individual HTTP requests; a run can contain many requests. Digest
checks add local HTTP calls. The report sums successful generation/post-check durations; failed calls, identity
prechecks, and database time are excluded. It is not total indexing latency;
no token counts are fabricated. Spring AI's embedding conversion replaces missing
usage with zero, so this implementation does not report usage as measured tokens.
No raw model/input logging or external telemetry is introduced.

## Verification and remaining limits

Focused checks:

```sh
./mvnw -Dtest='Embedding*Tests,OllamaEmbeddingGatewayTests' -Dit.test=EmbeddingIndexingIT verify
python3 scripts/check_repository.py
./mvnw clean verify
```

Tests use deterministic vectors and a disposable loopback HTTP double exercising
actual Spring AI serialization; no Ollama is required in CI. PostgreSQL tests cover
V1/V2 upgrade and old evidence readback, dimensions/foreign keys, idempotency,
concurrency, publication isolation/reuse, partial processing, failed-run recovery,
oversized input, profile mismatches, and corrupted evidence/index rejection.
Default/persistence-only model-free configuration remains tested.

Local verification on 2026-10-17 passed 22 focused unit/adapter/configuration tests
and 14 focused PostgreSQL tests. Full `clean verify` passed 194 tests (147 Surefire,
47 Failsafe), with no failures, errors, or skips. Repository/documentation checks,
six Python evaluation-validator tests, and staged/unstaged whitespace checks passed.
No live Ollama indexing run or existing local database migration was performed.

No live embedding quality result is claimed. This indexing component supplies
derived vectors for the separate M1.7b retrieval service; indexing itself performs
no similarity search, hybrid fusion, Q&A strategy selection, semantic-grounding
improvement, or full retrieval benchmarking.
