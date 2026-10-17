# Exact vector evidence retrieval

M1.7b adds `VectorRetrievalService` and a local CLI over the existing
[versioned embedding index](embedding-indexing.md). It returns original evidence,
not generated answers. Keyword retrieval and grounded Q&A still follow their
existing paths; hybrid fusion and Q&A strategy selection belong to M1.7c.
[ADR-0005](../adr/0005-versioned-derived-embedding-indexes.md) governs the derived
index. No tables, migrations, dependencies, or approximate indexes are added.

## Workflow and contracts

```java
Optional<RetrievalResult> result = vectorRetrievalService.search(
    publicationId, question, topK, embeddingProfile);
```

Publication and profile selection are explicit. The publication ID must be a full
SHA-256 identity. Queries follow the existing keyword bounds: nonblank valid
Unicode, no NUL, at most 512 UTF-16 characters; `topK` is 1 through 100. No query
trimming or rewriting occurs. Unknown publications return `Optional.empty()`.
Known empty publications return a present result with no matches and no provider
calls, even without a stored index. The CLI additionally requires a stored profile
ID so it can resolve the immutable profile without guessing model options.

1. In a read transaction, `PublicationIntegrity` verifies publication metadata,
   membership, and coverage. Load and verify the full stored profile and count
   indexed members. Every available published chunk must have a compatible row.
2. End that transaction before any provider operation. Resolve the installed model
   digest and compare the full effective profile, not just its tag.
3. `EmbeddingProfile.query` applies `nomic-query-v1`: exactly `search_query: ` plus
   the unchanged question. Its SHA-256 hashes the exact UTF-8 input including the
   prefix. Nomic's [task prefixes](https://huggingface.co/nomic-ai/nomic-embed-text-v1.5)
   distinguish queries from the existing `search_document: ` source input.
4. Generate one query vector through the existing Spring AI Ollama adapter. Enforce
   the profile byte cap, disabled truncation, exactly 768 dimensions, finite values,
   and nonzero norm. The adapter checks model identity before and after inference.
5. In a new repeatable-read transaction, recheck publication/profile identity and
   completeness. Stream all eligible evidence and vectors to verify chunk identity,
   source-content hash, exact document-input hash, dimensions, and vector checksum.
   This catches corruption that could affect ranking even outside the returned top K.
6. Run exact cosine search in that same transaction and return original chunks,
   complete publication metadata/coverage, and retrieval limitations.

Provider calls never hold a database transaction. Caller transactions are rejected.
The final checks and selection share one database snapshot, so missing rows or
changed profiles during inference cannot silently produce a partial result. Reads
use 30-second transaction/query timeouts and a 128-row fetch size. Publication
verification retains the existing 100,000-chunk ceiling. Integrity verification
scans eligible content/vectors, so cost is linear in publication size even for a
small `topK`; this is a deliberate correctness tradeoff, not a scale claim.

## Similarity, isolation, and ordering

The [pgvector cosine operator](https://github.com/pgvector/pgvector) is `<=>`;
smaller distance ranks first. The selection is equivalent to:

```sql
SELECT c.*, a.path, (e.embedding <=> :query_vector::vector) AS distance
FROM publication_chunk m
JOIN evidence_chunk c ON c.snapshot_id = m.snapshot_id AND c.chunk_id = m.chunk_id
JOIN source_artifact a ON a.snapshot_id = c.snapshot_id AND a.path_key = c.path_key
JOIN chunk_embedding e ON e.snapshot_id = m.snapshot_id AND e.chunk_id = m.chunk_id
WHERE m.publication_id = :publication_id AND e.profile_id = :profile_id
ORDER BY distance ASC, a.path COLLATE "C", c.start_line, c.chunk_id COLLATE "C"
LIMIT :top_k;
```

The implementation binds parameters through JDBC, selects explicit evidence
columns, and verifies returned chunks again. Snapshot/chunk joins plus explicit
publication membership and profile filtering prevent evidence leakage, including
between publications sharing a snapshot. Reused embeddings remain attached to
original evidence; no new evidence or citations are manufactured.

`RetrievalResult` and its ranked `Match` contract are unchanged. `score` means
`1 - cosine distance`, in [-1, 1], and ranks start at 1. The strategy is
`postgres-vector-v1:profile=<profile-id>` so the existing strategy field carries
the effective immutable profile identity without changing Q&A's answer schema.
The CLI also emits the complete profile and metric in a JSON envelope.

Similarity is a ranking signal, not evidence relevance, answer confidence, or
semantic support. There is no relevance threshold: even unrelated questions can
return nearest neighbors. A complete index covers only available chunks; a source
publication with incomplete processing stays incomplete in the returned metadata.
No matches or weak similarity establish repository-wide feature absence.

## Failures

| Outcome | Behavior |
| --- | --- |
| Invalid publication ID, query, or topK | `IllegalArgumentException`; CLI reports `INVALID_REQUEST` |
| Unknown publication | Empty Optional; CLI reports `UNKNOWN_PUBLICATION` |
| Missing or partial profile index | `VectorRetrievalException.INDEX_INCOMPLETE`, with publication/profile IDs and total/indexed/missing counts; no inference |
| Incompatible stored/installed profile | `EmbeddingException.PROFILE_MISMATCH` |
| Unavailable provider or missing installed model | `MODEL_UNAVAILABLE` |
| Provider timeout | `MODEL_TIMEOUT` |
| Invalid provider response or vector | `INVALID_PROVIDER_RESPONSE` or `INVALID_VECTOR` |
| Oversized query or provider input rejection | `OVERSIZED_INPUT` or `MODEL_INPUT_REJECTED`; no silent truncation |
| Corrupt publication/evidence | `PublicationIntegrityException`; CLI reports `PUBLICATION_INTEGRITY_FAILURE` |
| Corrupt stored input/vector/checksum | `INDEX_INTEGRITY_FAILURE` |
| Database failure | `VectorRetrievalException.DATABASE_FAILURE`; CLI profile lookup can instead report existing `PERSISTENCE_FAILURE` |

Failures never trigger indexing, retries, keyword fallback, or a model substitution.
The CLI exits unsuccessfully with a safe failure code; incomplete-index failures
include coverage. It does not print nested SQL details or provider bodies. Successful
JSON includes original repository content: keep output local and protect saved files.

## Local acceptance procedure

Use the existing PostgreSQL database and installed `nomic-embed-text:v1.5`; do not
reset volumes or reindex implicitly. Export `EVIDENCE_DB_URL`, `EVIDENCE_DB_USER`,
and `EVIDENCE_DB_PASSWORD` from your existing local setup, as described in the
[persistence guide](evidence-persistence.md). Do not put credentials in Git or CLI
arguments. All embedding model/options/timeouts use the existing
[indexing configuration](embedding-indexing.md); defaults are a 5-second connection
timeout and 60 seconds per HTTP request, with no automatic model download.
These bound individual requests, not total retrieval latency. Model construction
makes no requests. Default application startup and persistence-only use no Ollama.

Confirm the installed model and effective profile:

```sh
ollama list
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,embeddings \
  -Dspring-boot.run.arguments='--embeddings.command=profile'
```

The resolved profile ID must match the stored profile used for indexing. If options
were customized during indexing, export the same values before searching. Do not
replace the model tag's target while a request runs. Execute exactly one CLI command
per application invocation; do not combine `vector.command`, `embeddings.command`,
or `qa.command`.

For the existing `parcel-quotes-v1` acceptance publication:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,embeddings \
  -Dspring-boot.run.arguments='--vector.command=search --vector.publication-id=40b677d1bb8efeb02506e0de2ef624acb046a26cc2b57a65a8d7d9ab906c702c --vector.profile-id=6360aef03c6233a05a91583849c68ae0d6a97a66b1ef985de22e92876aa2aa0b --vector.query="Where is the shipping cost calculated?" --vector.top-k=5'
```

These IDs are local test inputs, not application defaults. Inspect the pretty JSON
among Maven/application logs. It contains the profile, strategy, publication,
ranked evidence with similarity scores, original source paths/lines/content, and
limitations. The application closes after the command. Investigate whether pricing
evidence such as `QuotePolicy.priceCents()` appears, then review each returned
source span. Record actual ranks, chunk IDs, paths/lines, scores, model digest,
publication/profile, query, topK, runtime versions, and any surprising matches.
Do not label relevant-looking results as improved Recall@K or answer quality.

**Live acceptance status:** not executed during M1.7b implementation because
`EVIDENCE_DB_URL`, `EVIDENCE_DB_USER`, and `EVIDENCE_DB_PASSWORD` were not exported in
the task environment. No ranked live results or quality/latency measurements are
claimed. The maintainer previously reported M1.7a indexing 33/33 chunks and reusing
all 33 on repetition; that is indexing acceptance, not vector-retrieval acceptance.
The command above remains to be executed against that publication.

## Verification and remaining limits

```sh
./mvnw -Dtest='VectorRetrieval*Tests,Embedding*Tests,OllamaEmbeddingGatewayTests' -Dit.test=VectorRetrievalIT verify
./mvnw clean verify
python3 scripts/check_repository.py
git diff --check
git diff --cached --check
```

Unit tests check request bounds, safe database failures, configuration isolation,
exact query hashing, and Spring AI serialization through a local HTTP double.
PostgreSQL tests use the existing pinned pgvector Testcontainers image and real
768-dimensional deterministic vectors. They cover exact ranking, stable ties,
publication/profile isolation, original provenance, unknown/empty publications,
missing/partial indexes, invalid vectors, provider errors, final-read consistency,
corruption outside topK, processing coverage, CLI output, and transaction boundaries.
Local verification on 2026-10-17 passed `./mvnw clean verify`: 221 tests
(155 Surefire and 66 Failsafe), with no failures, errors, or skips. This includes
19 vector PostgreSQL tests. The initial focused run passed 30 unit/adapter/configuration
tests and 17 vector PostgreSQL tests; two final vector/CLI cases also passed in the
full run. Repository/documentation checks, six Python validator tests, and staged
and unstaged whitespace checks passed. No automated test requires Ollama.
The full suite also preserves keyword baseline,
indexing, Q&A, and startup regressions; evaluation ground truth is unchanged.

Deterministic vectors verify mechanics, not the relevance of Nomic embeddings.
Live model/runtime changes can alter query vectors and ranking despite the same
model artifact. The adapter's observable digest checks cannot detect a tag changed
and restored between checks. Database administrators remain trusted. There is no
ANN index, query cache, hybrid fusion, reranker, Q&A integration, or broad retrieval
benchmark. Those remain separate increments/milestones.
