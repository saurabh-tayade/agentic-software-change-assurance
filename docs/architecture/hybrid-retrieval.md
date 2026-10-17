# Hybrid retrieval and Q&A strategy selection

M1.7c composes the existing [keyword](keyword-retrieval.md) and
[exact vector](vector-retrieval.md) retrievers. It adds no persistence schema,
indexing, model, approximate index, or retrieval-quality claim. Existing
[immutable publications](evidence-persistence.md) and
[embedding profiles](embedding-indexing.md) remain authoritative.

## Composition and rank fusion

`EvidenceRetriever.search(publicationId, query, topK)` returns the unchanged
`Optional<RetrievalResult>`. `KeywordRetrievalService` implements that small
contract without changing its SQL or behavior. `EvidenceRetrievers.select` binds
`KEYWORD`, `VECTOR`, or `HYBRID` to an explicit profile where needed. Selection
performs no inference, indexing, provider routing, or fallback.

`HybridRetrievalService.search(publicationId, query, topK, profile)` calls each
existing retriever once at candidate depth `min(100, max(20, 4 * topK))`. Final topK
remains 1 through 100; candidate depth is greater than topK except at the shared
100-result ceiling. Vector runs first, allowing its preflight to reject unknown
publications or incomplete indexes before keyword work. Branches run sequentially;
there are no repeated inference calls or additional database integrity scans.

Each candidate receives equal-weight Reciprocal Rank Fusion:

```text
RRF(e) = 1 / (60 + keywordRank(e)) + 1 / (60 + vectorRank(e))
```

An absent branch contributes zero. Ranks are 1-based. Raw keyword and cosine scores
are not combined. Identical repeated evidence within one branch contributes only
its first/best rank. Shared evidence across branches contributes once per branch.
Deduplication uses `(snapshotId, chunkId)`; conflicting records for one identity
fail integrity validation. Original `EvidenceChunk` objects are retained.

Results sort by descending RRF score, then path in unsigned UTF-8 byte order
(matching PostgreSQL `COLLATE "C"`), starting line, and chunk ID. Only the final topK
is returned, with new contiguous ranks. The effective strategy identifier is:

```text
postgres-hybrid-rrf-v1:k=60:depth=<candidate-depth>:weights=1,1:profile=<profile-id>
```

The constant, equal weights, and depth rule are fixed for this version, not tuned
per question. Limits and effective branch/unique/returned candidate counts appear
in `RetrievalResult.limitations`, together with labelled branch limitations. The
strategy identifies all effective fusion parameters. RRF scores express ranking
priority, not a probability, evidence relevance, or answer confidence. Candidate
truncation can change results; M1.8 must measure that effect before tuning.

## Publication consistency and failures

Both branches must return identical publication summaries, the requested query,
the requested candidate depth, and their expected versioned strategies/profile.
Fusion also verifies snapshot, artifact/parser/configuration membership, chunk hash
and identity, finite branch scores, and contiguous ranks. Each underlying SQL
retriever enforces actual publication membership. No evidence crosses publications.

The vector branch retains all M1.7b completeness, compatibility, query-vector, and
read-boundary integrity checks. Provider inference occurs outside transactions.
Each branch has its own read snapshot; publication summaries and duplicate evidence
are compared across those reads. This relies on application-level publication/index
immutability, not a transaction spanning model inference and both branches.
Privileged administrators remain trusted and must not mutate evidence or indexes
while a request runs. No distributed locking or extra full-index scan is added.

An unknown publication returns an empty Optional. If it disappears or differs
between branches, retrieval fails integrity validation. Known empty publications
return an empty result without inference. An empty keyword candidate list is valid;
vector candidates can still contribute. A successful empty vector list is also a
valid fusion input; the current complete-index vector retriever normally returns
neighbors for every nonempty publication. Missing/incomplete indexes are failures,
never equivalent to empty candidates. No branch failure triggers fallback or
embedding generation.

## Grounded Q&A integration

The existing three-argument `answer` call and `GroundedAnswer` schema remain intact.
The service now accepts an `EvidenceRetriever`; existing keyword constructor calls
remain source-compatible. Programmatic vector/hybrid callers can bind a retriever
with `EvidenceRetrievers.select(strategy, keyword, vector, profile)` and pass it to
`GroundedQuestionAnsweringService`.

`QaConfiguration` defaults to `KEYWORD`. Nonkeyword selection requires
`persistence,qa,embeddings`, an explicit stored profile ID, and the same embedding
options used for indexing. The configuration resolves that profile read-only;
the vector service checks installed model compatibility during the request.
Keyword selection needs neither an embedding profile nor an embedding gateway.
Supplying an embedding profile with KEYWORD is rejected rather than silently ignored.
Default application startup remains database/model-free; configuration creates no
Ollama requests. Answer and embedding gateways retain distinct model settings and
timeouts, with no automatic downloads.

The selected retriever feeds the existing bounded context builder. Its complete
chunk selection, budgets, omissions, and citation allowlist are unchanged. The
nonkeyword context describes ranked rather than lexical search; the keyword
context text remains unchanged. Prompt `repository-qa-v2`, schema, model settings,
and `ProposedAnswerValidator` are unchanged. Empty retrieval abstains without a
chat call; model-proposed insufficient evidence and malformed output follow the
same validation rules for all strategies.

The CLI preserves existing top-level answer fields and adds:

- `requestedRetrievalStrategy`: the explicitly selected strategy, default KEYWORD.
- `embeddingProfileId`: present for VECTOR/HYBRID.
- `retrieval`: the actual ranked result, publication, effective strategy, and
  limitations from this invocation, without repeating retrieval.

`contextSelection` shows which retrieved ranks reached the model. Citations still
resolve only to those selected original chunks. The CLI retrieval trace includes
source content beyond the selected/cited context; treat saved output as sensitive
repository data. It is a local developer tool, not a public endpoint.

New Q&A failure codes distinguish `RETRIEVAL_NOT_CONFIGURED`,
`EMBEDDING_INDEX_INCOMPLETE`, `EMBEDDING_PROFILE_MISMATCH`,
`EMBEDDING_MODEL_UNAVAILABLE`, `EMBEDDING_MODEL_TIMEOUT`,
`EMBEDDING_INVALID_VECTOR`, `EMBEDDING_INVALID_RESPONSE`,
`EMBEDDING_INPUT_REJECTED`, and `EMBEDDING_INDEX_INTEGRITY_FAILURE`.
Database failures retain `RETRIEVAL_FAILURE`; corrupt evidence retains
`PUBLICATION_INTEGRITY_FAILURE`. Incomplete-index CLI errors include safe coverage
counts. Bad strategy/profile configuration fails clearly at startup; other failures
occur during the request. No provider response bodies or SQL details enter public
errors. Chat failure codes remain unchanged and separate from embedding failures.

## Local comparison and evaluation

Use the exact three-strategy commands in the
[Q&A guide](grounded-question-answering.md). The
[M1.7 observations](../../evaluation/retrieval/m1-7-observations.md) separate the
maintainer's M1.7b vector result from pending M1.7c runs. Review full retrieval ranks,
selected context ranks, and cited source spans separately. Changing evidence order
may change model output; valid citations alone still do not establish semantic
support. Hybrid retrieval does not fix the previously observed cents/dollars error.

Focused deterministic verification:

```sh
./mvnw -Dtest='HybridRetrievalServiceTests,GroundedQuestionAnsweringServiceTests,QaConfigurationTests' -Dit.test=HybridQuestionAnsweringIT verify
./mvnw clean verify
python3 scripts/check_repository.py
```

Pure fusion tests cover rank arithmetic, overlap, one-sided/empty inputs, duplicates,
conflicts, candidate bounds, profile/publication consistency, and UTF-8 ties. Real
pgvector tests cover branch composition, isolation, missing/partial indexes,
failures without fallback, original provenance, Q&A context/citations, malformed
answers, and actual strategy configuration/CLI output. Embedding and chat gateways
are deterministic doubles; CI requires Docker but no Ollama server.

Local verification on 2026-10-17 passed 33 focused unit/configuration tests and
13 focused PostgreSQL tests. Full `./mvnw clean verify` passed 248 tests
(169 Surefire, 79 Failsafe), with no failures, errors, or skips. Repository
hygiene/documentation checks, six Python validator tests, and staged/unstaged
whitespace checks passed. Live three-strategy acceptance remains pending.

M1.8 should reuse unchanged versioned corpora, scenarios, and keyword baselines.
Record model digest/options, publication/profile, strategy parameters, query/topK,
full rankings, context omissions, prompt/model settings, latency, and actual usage
when available. Measure retrieval metrics separately from citation correctness,
answerability, semantic support, and completeness. Do not infer superiority from
one visible development question or tune RRF solely around it.
