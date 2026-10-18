# Reproducible retrieval evaluation (M1.8a)

M1.8a executes existing KEYWORD, VECTOR, and HYBRID retrievers and grades their
captured evidence independently. It does not invoke Q&A, change retrieval, judge
answers, or tune a model. Citation identity and semantic answer correctness remain
separate properties. The M1.6 cents-to-dollars failures are not addressed by this
retrieval comparison.

## Definition and execution

[Comparison v1](../../evaluation/retrieval/retrieval-comparison-v1.json) pins hashes
of the existing keyword definition, original scenarios, and corpus manifest. It
imports all 15 scenarios, validates reference spans and corpus file hashes, and
preserves the historical expectations. It does not add the shipping-cost observation
as held-out data. The [original baseline](../../evaluation/retrieval/README.md) and
its timing methodology remain unchanged.

`RetrievalComparison` loads the definition. `RetrievalEvaluationRunner` reads explicit
existing publications through `EvidencePersistenceService`, binds existing retrievers
through `EvidenceRetrievers`, and performs one serial attempt for each of 15 cases,
three strategies, and two K values: **90 executions**. There are no warmups, retries,
fallbacks, publication creation, indexing, or downloads in this runner.

K=5 and K=12 are separate searches. HYBRID uses its existing equal-weight RRF k=60
and candidate depths 20 and 48 respectively. Truncating K=12 would not reproduce
K=5 hybrid retrieval. The actual `RetrievalResult` records effective strategy,
profile, topK, ranks/scores, coverage, and branch candidate diagnostics. Results with
unexpected strategy/version/configuration, noncontiguous ranks, duplicate identities,
nonfinite scores, incorrect score/tie order, or mismatched provenance fail validation.
Artifact v1 freezes these versioned ordering/configuration expectations for replay;
a future algorithm version needs a new comparison contract.

The full corpus, src-only scope, and one-chunk processing cases require **three
explicit publications from the same repository commit and ingestion policy**:

| Variant | Scenarios | Scope | Chunk configuration |
| --- | --- | --- | --- |
| full | Original seven and KW-001 through KW-006 | All sources | 4 lines, 10,000 chunks |
| scoped | KW-007 | Include `src`, no exclusions | 4 lines, 10,000 chunks |
| limited | KW-008 | All sources | 4 lines, 1 chunk |

The runner checks source inventory/hashes against the versioned corpus, scopes,
and processing configurations. A different scope or chunk size is not an equivalent
comparison. KW-007 still expects README evidence that its publication excludes;
KW-008 still expects pricing evidence omitted by its chunk budget. Their expectations
must not be weakened to improve scores. Processing PARTIAL is preserved and does
not exclude an otherwise valid execution from grading.

Variant IDs are supplied explicitly; missing IDs become `PUBLICATION_NOT_CONFIGURED`
for the affected executions. The runner never substitutes the full publication.
Prepare missing variants explicitly with the existing Q&A publication CLI using
`qa.scope-includes` and `qa.chunk-max-chunks`, then index each publication separately.
The [commands below](#preparing-kw-007-and-kw-008) use the existing ingestion,
processing, persistence, and indexing contracts. Setup remains outside evaluation
execution. Integration tests prepare only disposable fixtures.

## Metrics and failure semantics

The existing `RetrievalGrading` has moved from test sources to production sources;
the historical runner and new offline grader share its unchanged calculation.

- Recall@K counts an expected reference only if the union of retrieved original
  lines on its exact source path covers its entire inclusive span. Recall is covered
  references / expected references. Duplicate/overlapping spans add no extra credit.
- Reciprocal rank credits the first chunk overlapping any expected span on the exact
  path: 1 / its one-based rank, or zero if none overlaps. MRR@K is the macro mean.
  Thus positive reciprocal rank does not imply complete-span coverage for Recall.
- Empty expected-evidence lists have null recall and reciprocal rank, never perfect
  recall. Their returned evidence is retained for inspection.
- Each strategy/K aggregate reports scheduled, successful, failed, successful empty-
  expectation, and quality-denominator counts. Only successful cases with nonempty
  expectations enter macro Recall/MRR. Zero eligible cases yields null averages.
  Compare denominators and case IDs before comparing means; unequal failure subsets
  are not a fair paired comparison.
- Unknown/missing publications, corpus/provenance mismatches, invalid rankings,
  missing profiles, incomplete vector indexes, embedding-provider failures, and
  retrieval failures remain categorized execution failures. No exception bodies or
  credentials are serialized. Index completeness diagnostics are retained when
  available. An unexpected exception becomes `UNEXPECTED_EXECUTION_FAILURE`.

## Local artifacts and offline grading

`target/evaluation/<UUID>/` is ignored by Git. Each run creates a new directory and
refuses to overwrite an existing one:

- `execution.json`: artifact schemaVersion 1, UUID, start time, deterministic
  configuration ID, expanded comparison/expected references and original input
  hashes, publication bindings, requested profile ID and resolved model digest/options,
  application metadata, execution mode, captured publications with original source
  and processing coverage, and all per-case execution outcomes.
- `grades.json`: schemaVersion 1, run/configuration IDs, grading version
  `source-span-union-first-overlap-v1`, per-case grades/failures, and six aggregates.

Each execution captures the actual `RetrievalResult` including complete ranked
EvidenceChunks, source lines/content/hashes, repository/commit/publication identities,
strategy settings and limitations. Duration uses a monotonic clock around the retrieval
call, including query embedding and retrieval integrity checks, excluding initial
publication loading and grading. Preflight failures have null retrieval duration;
failed attempted calls retain their elapsed duration. Token usage is unavailable from
`RetrievalResult` and is not estimated. This is one sample per case/configuration;
no P50/P95 or statistical latency claim is produced.

Application version, Git commit, dirty state, and source-tree content hash are
captured when available; missing values are null. The source hash covers `src/main`,
`evaluation`, and `pom.xml`, not credentials. Git metadata describes the execution
checkout; retain that checkout (including dirty changes) for live reproduction.
The configuration hash covers the imported definition, versioned strategy settings,
publication bindings, requested profile ID, and LIVE/TEST_DOUBLE mode. UUID/time
identify an execution separately from its configuration.

`RetrievalEvaluationArtifacts.grade` requires no database, Ollama, current corpus
files, or rerun. It checks the captured grid, requested/effective identities, source
hashes, publication membership and source spans before reusing the same grader.
Artifacts are local evidence, not signed attestations: coordinated malicious edits
to a capture are outside this trust boundary. Treat captures as potentially sensitive
repository content. Do not publish them automatically. A crash can leave an incomplete
run directory; never overwrite it. If `execution.json` completed, regrade it offline.

The execution envelope and independent grade document are the M1.8b boundary:
answer evaluations can later reference run/configuration/case IDs and add separate
answer grading artifacts. No GroundedAnswer or citation contract changed here.
M1.8c can add comparative reporting without replacing these captures.

## Preparing KW-007 and KW-008

Use the **same Git repository and exact commit** as the existing full publication
`40b677d1bb8efeb02506e0de2ef624acb046a26cc2b57a65a8d7d9ab906c702c`.
Do not create a new corpus commit for these variants: identical files in a different
commit are not the same evaluation snapshot. Set `PARCEL_REPOSITORY` to that existing
local Git copy. Read `PARCEL_COMMIT` from its original publication output
(`snapshot.commitSha`) or the full publication captured in a live evaluation run.
For example, with an existing execution artifact:

```sh
export PARCEL_REPOSITORY=/tmp/parcel-quotes-qa
export EVALUATION_EXECUTION='target/evaluation/<run-id>/execution.json'
export PARCEL_COMMIT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["publications"]["full"]["ingestion"]["snapshot"]["commitSha"])' "$EVALUATION_EXECUTION")"
git -C "$PARCEL_REPOSITORY" cat-file -t "$PARCEL_COMMIT"
```

Replace `<run-id>` and the repository path with the existing local values. The Git
check must print `commit`. Supply the existing `EVIDENCE_DB_URL`, `EVIDENCE_DB_USER`,
and `EVIDENCE_DB_PASSWORD` environment variables. These commands use the already
migrated V1–V3 database and do not invoke the chat model. Leave Q&A retrieval settings
at their keyword defaults when publishing.

**KW-007 — source-only publication:**

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,qa \
  -Dspring-boot.run.arguments="--qa.command=publish --spring.flyway.enabled=false --qa.publish-repository='$PARCEL_REPOSITORY' --qa.repository-id=parcel-quotes-v1 --qa.revision=$PARCEL_COMMIT --qa.scope-includes=src --qa.chunk-max-lines=4 --qa.chunk-max-chunks=10000"
```

**KW-008 — unrestricted, one-chunk publication:**

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,qa \
  -Dspring-boot.run.arguments="--qa.command=publish --spring.flyway.enabled=false --qa.publish-repository='$PARCEL_REPOSITORY' --qa.repository-id=parcel-quotes-v1 --qa.revision=$PARCEL_COMMIT --qa.chunk-max-lines=4 --qa.chunk-max-chunks=1"
```

For KW-008, ensure `qa.scope-includes` is absent from other configuration sources
(including `QA_SCOPE_INCLUDES`); do not pass an empty value as an override.
Inspect the JSON printed after each command:

| Field | KW-007 | KW-008 |
| --- | --- | --- |
| `snapshot.scope.includes` | `["src"]` | `[]` |
| `snapshot.scope.excludes` | `[]` | `[]` |
| `configuration.maxLines` | `4` | `4` |
| `configuration.maxChunks` | `10000` | `1` |
| `snapshot.commitSha` | The full publication's commit | The same commit |
| `publicationId` | Save as `SCOPED_PUBLICATION_ID` | Save as `LIMITED_PUBLICATION_ID` |

Also inspect `processingStatus`. Limited processing retains its omissions; publishing
it successfully does not mean complete repository coverage. Repeating either command
with identical inputs returns the same publication identity. The full publication
and historical ground truth are unchanged.

Before indexing, inspect the existing embedding model/profile:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,embeddings \
  -Dspring-boot.run.arguments='--embeddings.command=profile --spring.flyway.enabled=false --embeddings.model=nomic-embed-text:v1.5'
```

Proceed only if `profileId` equals
`6360aef03c6233a05a91583849c68ae0d6a97a66b1ef985de22e92876aa2aa0b`.
Otherwise resolve the model/options mismatch using the [indexing guide](embedding-indexing.md);
do not index under a different profile for this comparison. Copy its
`profile.modelDigest` into `EMBEDDINGS_EXPECTED_DIGEST` (the model digest is **not**
the profile ID), and copy the two publication IDs into the variables above:

```sh
export SCOPED_PUBLICATION_ID='<KW-007 publicationId>'
export LIMITED_PUBLICATION_ID='<KW-008 publicationId>'
export EMBEDDINGS_EXPECTED_DIGEST='<verified profile.modelDigest>'
for publication_id in "$SCOPED_PUBLICATION_ID" "$LIMITED_PUBLICATION_ID"; do
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,embeddings \
    -Dspring-boot.run.arguments="--embeddings.command=index --spring.flyway.enabled=false --embeddings.model=nomic-embed-text:v1.5 --embeddings.publication-id=$publication_id --embeddings.expected-digest=$EMBEDDINGS_EXPECTED_DIGEST" || break
done
```

Inspect both reports: the expected `publicationId` and `profileId`, `status=COMPLETE`,
`missingChunks=0`, `issueCount=0`, and matching `indexedChunks`/`totalChunks`.
A complete derived index covers each publication's available chunks, not omitted
source evidence. Only then supply both IDs to the full comparison command below.
These are manual live acceptance instructions; automated tests verify publication
behavior using temporary Git repositories and disposable PostgreSQL without Ollama.

## Local commands

Run from the repository root with Java 21. Set `EVIDENCE_DB_URL`, `EVIDENCE_DB_USER`,
and `EVIDENCE_DB_PASSWORD` in your local environment without putting secrets in
commands or artifacts. The existing database must already have V1–V3 migrations.
Follow the [embedding guide](embedding-indexing.md) for explicit model setup and
indexing. Ollama must serve the exact digest/options of the stored profile; matching
the model tag alone is insufficient. Evaluation does not download or index models.

The existing local full-publication inputs can be exercised immediately:

```sh
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=persistence,embeddings \
  -Dspring-boot.run.arguments="--evaluation.command=run --spring.flyway.enabled=false --evaluation.publication-id=40b677d1bb8efeb02506e0de2ef624acb046a26cc2b57a65a8d7d9ab906c702c --evaluation.profile-id=6360aef03c6233a05a91583849c68ae0d6a97a66b1ef985de22e92876aa2aa0b"
```

Without the two variant IDs this intentionally records 12 missing-publication
failures and exits unsuccessfully **after writing artifacts**. Up to 78 other
executions can succeed. This is an incomplete comparison, not a 15-case benchmark.
For all 90 executions, supply your already published and indexed variant IDs:

```sh
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=persistence,embeddings \
  -Dspring-boot.run.arguments="--evaluation.command=run --spring.flyway.enabled=false --evaluation.publication-id=40b677d1bb8efeb02506e0de2ef624acb046a26cc2b57a65a8d7d9ab906c702c --evaluation.scoped-publication-id=${SCOPED_PUBLICATION_ID} --evaluation.limited-publication-id=${LIMITED_PUBLICATION_ID} --evaluation.profile-id=6360aef03c6233a05a91583849c68ae0d6a97a66b1ef985de22e92876aa2aa0b"
```

All three strategies are part of this pinned suite. Running with only `persistence`
can collect keyword outcomes, but missing profile/vector wiring is explicitly failed
for the other strategies. The CLI logs only artifact locations and outcome counts.
Setup errors (such as unreachable PostgreSQL during startup) can prevent capture;
they are not successful or zero-score retrieval executions.

Offline replay, without database/model profiles:

```sh
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --evaluation.command=grade --evaluation.execution=target/evaluation/<run-id>/execution.json"
```

Replace `<run-id>` with the emitted UUID. This writes a new `grades-<UUID>.json`
next to the capture and preserves earlier grading files. `mvnw clean` removes
`target`, so retain needed runs in a private location before cleaning.

## Verification and limits

Focused verification:

```sh
./mvnw -Dtest=RetrievalGradingTests,RetrievalEvaluationTests,RetrievalEvaluationCliTests test
./mvnw -Dit.test=RetrievalComparisonIT,KeywordEvaluationIT test-compile failsafe:integration-test failsafe:verify
python3 scripts/check_repository.py
./mvnw clean verify
```

Unit tests replay all 15 historical per-case grades, test explicit denominators,
failures, provenance/configuration rejection, serialization, missing metadata, and
separate K execution. The comparison integration test uses real pgvector and all
three actual retrievers with deterministic 768-dimensional embeddings. It verifies
historical keyword aggregate compatibility, offline replay, and incomplete-index
failure without fallback. Those embeddings do not establish semantic retrieval
quality. Mandatory CI uses no Ollama server.

During initial M1.8a implementation, live quality was not measured because local
database connection variables were unavailable. The maintainer subsequently reported
a live comparison with 12 executions blocked by missing scoped/limited publications.
The publication CLI extension supplies explicit preparation commands; no new live
publication, indexing, or comparison was executed for that extension. The earlier
[M1.7 observations](../../evaluation/retrieval/m1-7-observations.md) remain descriptive
maintainer reports, not scores in this dataset. This small visible development suite
is vulnerable to overfitting and does not establish general repository performance.
Source overlap measures retrieval location/coverage, not semantic relevance, factual
answer correctness, or feature absence. [M1.8b](grounded-answer-evaluation.md) adds
independent answer capture, structural grading, and human semantic review.
[M1.8c](evaluation-reporting.md) presents saved observations through offline
JSON/Markdown reports with explicit review selection and separate denominators.
The maintainer subsequently reported completion of all 90 live executions without
failures and reproduction of the historical keyword baseline. That report does not
establish answer correctness or imply a new checked-in retrieval baseline.
