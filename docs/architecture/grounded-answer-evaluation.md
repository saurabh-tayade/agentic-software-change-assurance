# Grounded-answer evaluation (M1.8b)

M1.8b captures actual Q&A executions and grades independent dimensions offline.
It reuses [M1.8a retrieval evaluation](retrieval-evaluation.md), the existing
`GroundedQuestionAnsweringService`, and the [human rubric](../../evaluation/qa/README.md).
It does not change retrieval, context selection, prompts, answer validation, or model
settings. Citation identity validity never implies semantic support or factual truth.
[M1.8c reporting](evaluation-reporting.md) now presents these saved observations
with explicit review selection and independent denominators.

## Versioned comparison

[Q&A comparison v1](../../evaluation/qa/qa-comparison-v1.json) pins the existing
`qa-v2.json`, original scenarios, retrieval comparison, and rubric by SHA256. It
imports the nine existing Q&A cases without changing historical files, expected
source spans, or corpus hashes. It adds two explicitly **DEVELOPMENT_REGRESSION**
cases, both referencing the existing RI-002 pricing evidence:

- `QA-REG-SHIPPING`: where shipping cost is calculated. Earlier keyword retrieval
  missed the method; vector and hybrid found it at rank 5. Those observations are
  not a required rank or an aggregate benchmark.
- `QA-REG-UNITS`: the incorrect $10 remote-surcharge premise. Human review must
  check that 300 cents is $3.00, including units in uncertainty statements. The
  [M1.6 record](../../evaluation/qa/live-acceptance-m1-6-v1.md) preserves separate
  v1/v2 failures; neither prompt nor hybrid retrieval has proven this issue resolved.

There are 11 cases across KEYWORD, VECTOR, and HYBRID: **33 serial executions**.
All are visible development cases, not held-out benchmark data. The full corpus
publication uses unrestricted ingestion and 4-line/10,000-chunk processing. No
specialized KW-007/KW-008 publications are required for this Q&A definition.

The comparison imports topK=12 and context limits from `qa-v2.json` (six chunks,
6,000 context bytes, 3,000 bytes per complete chunk). These are suite settings;
`qa.top-k` and other standalone CLI context overrides do not override the suite.
Generation settings come from the actual configured `ModelGateway`; the definition's
recommended settings remain separately visible. Changed effective settings produce
a different configuration identity. The current prompt is `repository-qa-v2` and
answer contract is `grounded-answer-v1`.

The historical KW-006 zero-match/no-model expectation is **keyword-specific**.
VECTOR and HYBRID may return nearest neighbors for an unanswerable question. Such
matches do not prove Kafka functionality or justify an answer. Expected answerability
is a human-review input, conditional on the context actually shown, not a rule that
all strategies must reproduce a lexical zero-match result.

## Capture workflow and artifacts

`QaComparison` validates/imports the suite. `QaEvaluationRunner` wraps the chosen
`EvidenceRetriever` and `ModelGateway` with capture decorators, then calls the existing
Q&A service once per case/strategy. It captures the same retrieval and the exact
system prompt, context JSON, and output schema passed to the gateway. It never
repeats retrieval to reconstruct an answer and does not repair, retry, or fallback.

The existing evaluation CLI writes a fresh `target/evaluation/<UUID>/` directory:

| Artifact | Contents |
| --- | --- |
| `qa-execution.json` | Schema 1, artifact type `grounded-qa-execution`, run/time/configuration identities, expanded and hashed definition, selected case IDs, source publication, embedding profile/digest/options, available application metadata, actual generation settings, LIVE/TEST_DOUBLE mode, and individual execution captures. |
| `qa-grades.json` | Schema 1, artifact type `grounded-qa-grades`, grading version `qa-independent-dimensions-v1`, execution hashes, independent deterministic checks, and explicit `NOT_REVIEWED` semantic status. |
| `qa-grades-<UUID>.json` | New offline grading observation, optionally including one attributed human-review record. Earlier grades and captures are not overwritten. |
| `qa-review-template-<UUID>.json` | Incomplete review template, with null reviewer/date/rationale and unreviewed scores. It is not a completed human review. |

Execution IDs are `STRATEGY:scenario-id`. Each capture retains actual ranked
EvidenceChunks, publication/snapshot/source identities and hashes, exact source
content, effective retrieval strategy/settings/limitations, and original ranks.
The model input contains the actual context-key-to-evidence mapping and selected
source text. Successful answers also contain context selection and omission reasons,
claims/citations/uncertainties, prompt/schema hashes, configuration, and invocation
metadata. Offline grading verifies captured input against the versioned deterministic
context builder before interpreting its keys. JSON object order is ignored across
JVMs; array order, values, and source text must still match exactly. It does not treat all retrieved chunks
as model-visible evidence. For rejected answers, the actual model input still permits
coverage/key verification even though no `GroundedAnswer` exists.

Failures retain a category and available diagnostics: retrieval/provider failures,
unknown publications, incomplete indexes, malformed output (including validation
reason), invalid citations, and context-budget failure. A failed invocation has no
answer and never becomes successful `INSUFFICIENT_EVIDENCE`. Provider exception
bodies, database errors, credentials, and unrelated environment configuration are
not serialized. Setup failures before runner execution, such as database connection
failure during Spring startup, cannot produce a per-case capture.

Durations use monotonic clocks: retrieval call, gateway call, and total Q&A attempt.
Total excludes shared publication preflight, artifact serialization, and grading.
The existing answer's own duration and provider diagnostics are also retained when
available; these timings have different boundaries and must not be added together.
Uninstrumented stages are unavailable, not inferred. Non-invoked stages have null
durations/usage, and failed attempted stages retain elapsed time. Input/output token
usage is captured only when the gateway supplies it. A null invocation means no
provider diagnostics were received, not a zero-token success.

The chat gateway does not expose an immutable model digest: `modelDigest` is null
with an explicit reason, even if `returnedModel` supplies a tag. Embedding profile
identity includes the resolved digest. Application version, commit, dirty state, and
source hash use the existing M1.8a metadata collector; missing values remain null.
Capture the local Ollama version/digest separately for stronger live reproduction.
There is one sample per case/configuration, no latency percentile or quality summary.

Raw model response content is **disabled by default**, even after validation failure.
Use `--evaluation.capture-raw-response=true` only for private local debugging. It
stores at most 8,192 UTF-8 bytes, without splitting a Unicode code point, plus original
length and truncation status. JSON encoding escapes control characters. The encoded
JSON may be larger than the retained text. Raw content is never printed by this
runner or included in grade documents; false/unset disables it again. The capture
still records response availability/length when content is disabled.

Captures contain repository source and normalized answers even with raw capture off.
Treat all artifacts as sensitive local files; do not publish them automatically.
They are replayable evidence, not signed attestations against coordinated tampering.
Preserve the implementation checkout for replay of this grading/context-policy version.
`mvnw clean` deletes `target`; copy needed artifacts to a private location first.
Historical retrieval `execution.json` and baseline formats are unchanged.

## Independent deterministic grades

`QaEvaluationArtifacts` operates without PostgreSQL, Ollama, or current corpus files:

- **Provenance/ranking:** reuse M1.8a's publication, corpus hashes, original source
  spans, chunk integrity, strategy/profile, rank ordering, and membership checks.
- **Retrieval coverage:** unchanged `RetrievalGrading` requires the union of original
  lines to cover a complete expected span. Reciprocal rank credits first overlap.
- **Context coverage:** the same grader operates on evidence actually passed to the
  model, after captured-context verification. `retrievalMisses` identifies references
  not fully retrieved. `contextBudgetLosses` identifies references fully retrieved
  but not fully selected. Neither means the source is absent from the repository.
- **Citation identity:** keys must exist in the supplied context; final authoritative
  citations must equal those original records, including publication, snapshot,
  content/hash, path, and lines. Unknown or omitted keys and altered records fail.
- **Structure:** check normalized answer status/claims/uncertainties and bounds.
  `INSUFFICIENT_EVIDENCE` requires empty claims/citations and nonempty uncertainty.
  Supported responses require claims; partial responses also require uncertainty.
  Application-added coverage warnings are permitted. This is structural abstention,
  not a semantic finding that abstention was appropriate.
- **Reliability:** `SUCCESS` describes application completion; explicit failure
  categories remain separate from citation/semantic dimensions. A synthetically
  altered capture can have successful recorded execution but invalid offline checks.

Checks use `VALID`, `INVALID`, `NOT_APPLICABLE`, and `NOT_VERIFIABLE`. An abstention
has no citation identities to grade (`NOT_APPLICABLE`). A provider failure with no
answer has unverifiable citations; a recorded invalid-citation rejection is `INVALID`.
Unavailable or invalid context produces no invented coverage metric. No-retrieval
abstention has empty actual context; cases with empty expected evidence have null
recall/reciprocal rank, never perfect scores. No aggregate silently absorbs failures
or unreviewed outputs. Broader denominators and comparisons belong to M1.8c.

## Offline human review

`QaHumanReview` uses `qa-human-rubric-v1`, extending the unchanged existing 0–2
rubric with explicit factual correctness and issue categories. A review binds its
UUID, reviewer, ISO-8601 date/time, rationale, run ID, execution ID, and execution
SHA256. It cannot be silently applied to a different captured answer. Every claim
and expected finding gets a row; omit no row to imply success.

| Dimension | Values |
| --- | --- |
| Citation semantic relevance per claim | RELEVANT, PARTIALLY_RELEVANT, IRRELEVANT, INDETERMINATE, NOT_REVIEWED |
| Claim support | 2 fully supported, 1 partially supported/needs qualification, 0 unsupported or contradicted; null means NOT_REVIEWED |
| Factual correctness per claim | CORRECT, INCORRECT, INDETERMINATE, NOT_REVIEWED |
| Completeness per expected finding | 2 covered, 1 partial, 0 missing/wrong; null means NOT_REVIEWED |
| Answerability | APPROPRIATE, OVERCLAIM, UNNECESSARY_ABSTENTION, NOT_REVIEWED |
| Uncertainty | ADEQUATE, INADEQUATE, NOT_REVIEWED |

Review claim support against **selected** evidence, using explicit context keys and
rationales. Accurate information supported only by omitted evidence earns support 0.
Assess answerability with the actual context, while retaining missed findings in
completeness. Inspect uncertainty statements as well as claims for unsupported facts.
Factual issues target a one-based claim or uncertainty index, quote actual observed
text, describe the expected fact, identify original source spans, and give a rationale.
Categories include numerical-unit conversion, numerical value, unsupported assertion,
false absence, runtime inference, and other. Source references must resolve inside
the captured publication. Nothing in the general grader encodes pricing rules.

For example, a **review fixture**, not a new live observation, records "$300 surcharge"
against `QuotePolicy.priceCents()`: citation identity VALID, factual correctness
INCORRECT, support 0 (failed), expected fact "300 cents = $3.00", issue category
NUMERICAL_UNIT_CONVERSION. The unit tests use synthetic gateway output and an
explicitly identified test reviewer. They do not assert live semantic-quality scores.

No review means `NOT_REVIEWED`, never automatic factual correctness. A review may
leave individual dimensions unreviewed; `HUMAN_REVIEW_RECORDED` means an attributed
record exists, not that every dimension passed. Failed executions cannot receive
semantic scores. Reviewer judgment is not independently verified by schema validation;
consistent rubric use and later reviewer-agreement studies remain necessary.

## Local acceptance commands

Run from the repository root with Java 21 and the already migrated/indexed full
Parcel Quotes publication. Configure `EVIDENCE_DB_URL`, `EVIDENCE_DB_USER`, and
`EVIDENCE_DB_PASSWORD` privately. Follow the [Q&A](grounded-question-answering.md)
and [embedding](embedding-indexing.md) guides for existing model setup. Verify
`ollama list` shows `qwen3:4b-instruct` and the compatible `nomic-embed-text:v1.5`;
no command below downloads models or creates publications/indexes.

First run six regression executions (two questions, all three strategies):

```sh
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=persistence,qa,embeddings \
  -Dspring-boot.run.arguments='--evaluation.command=qa-run --spring.flyway.enabled=false --qa.retrieval-strategy=KEYWORD --qa.embedding-profile-id= --qa.model=qwen3:4b-instruct --embeddings.model=nomic-embed-text:v1.5 --evaluation.publication-id=40b677d1bb8efeb02506e0de2ef624acb046a26cc2b57a65a8d7d9ab906c702c --evaluation.profile-id=6360aef03c6233a05a91583849c68ae0d6a97a66b1ef985de22e92876aa2aa0b --evaluation.case-ids=QA-REG-SHIPPING,QA-REG-UNITS'
```

For all 33 executions, use the same command without `--evaluation.case-ids=...`.
The `qa.retrieval-strategy`/empty profile flags leave the unrelated standalone Q&A
bean at its defaults; the evaluation runner binds all three strategies explicitly
using `evaluation.profile-id`. Requested/effective strategies appear per execution.
The suite freezes context settings; `qa.model`, temperature, output/context-token
limits, and timeouts remain actual externalized gateway configuration. The CLI prints
only artifact path and attempt/failure counts. A nonzero failure count exits with an
error **after writing the captured outcomes**. It does not represent semantic failure.

Inspect each execution's actual retrieval ranks, context, answer, and checks. Verify
whether pricing reached the context and whether the source substantively supports
each cited claim. Treat a unit error as a factual failure even with valid citations.
Do not force a particular answer status or infer hybrid superiority from these cases.

Regrade a capture offline, without database/model profiles:

```sh
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments='--spring.main.web-application-type=none --evaluation.command=qa-grade --evaluation.execution=target/evaluation/<run-id>/qa-execution.json'
```

Create an unreviewed template for one actual answer:

```sh
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments='--spring.main.web-application-type=none --evaluation.command=qa-review-template --evaluation.execution=target/evaluation/<run-id>/qa-execution.json --evaluation.execution-id=VECTOR:QA-REG-UNITS'
```

Replace `<run-id>` with the emitted run UUID. Copy the emitted template to a new
private review file, fill reviewer/date/rationale, and grade every dimension you
actually inspected. Retain null/NOT_REVIEWED elsewhere. Add factual issues if found;
do not alter captured claims or execution hashes. Validate and attach the review:

```sh
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments='--spring.main.web-application-type=none --evaluation.command=qa-grade --evaluation.execution=target/evaluation/<run-id>/qa-execution.json --evaluation.review=/absolute/private/review.json'
```

The resulting new grades document embeds the validated review. The CLI accepts one
review per invocation; the Java grader accepts multiple distinct execution reviews.
Earlier review observations are preserved, not implicitly merged or overwritten.
The [M1.8c reporter](evaluation-reporting.md) selects explicit observations and
rejects duplicate/conflicting reviews rather than choosing a newest record.

## Verification and limitations

```sh
./mvnw -Dtest=QaEvaluationTests,QaHumanReviewTests,QaEvaluationCliTests,RetrievalGradingTests,RetrievalEvaluationTests,QaDefinitionTests test
./mvnw -Dit.test=QaComparisonIT,RetrievalComparisonIT,KeywordEvaluationIT test-compile failsafe:integration-test failsafe:verify
./mvnw clean verify
python3 scripts/check_repository.py
```

Deterministic tests cover same-call capture, missing metadata, malformed output,
provider/retrieval failures, context losses, citation/provenance mutations, abstention,
human-review validation, and offline serialization without inference. PostgreSQL tests
exercise the actual three retrievers and Q&A service with deterministic model/embedding
doubles, including missing-index failures. Existing M1.8a baseline tests remain unchanged.

No live M1.8b model run or human quality score was produced during implementation:
local database connection variables were unavailable. The maintainer reported all
90 M1.8a retrieval executions completed and reproduced the historical keyword baseline;
that is separate from Q&A quality and was not reproduced as a live run here.

This visible, small dataset is vulnerable to overfitting and does not establish
performance on new repositories. Model generation is not guaranteed reproducible
from a mutable tag or even fixed settings; hardware, model digest, and load matter.
Offline grades replay recorded evidence, not future model behavior. Semantic and
numerical failures remain possible. No LLM judge, statistical latency report, UI,
new schema/migration, or automatic quality claim is introduced.

After M1.8b implementation, the maintainer accepted six live Q&A executions with
zero recorded execution failures and five applicable citation identities, all valid.
One attributed review recorded one INCORRECT and one CORRECT claim; five executions
remained semantically unreviewed. These sparse observations do not establish general
answer accuracy. The [M1.8c reporter](evaluation-reporting.md) preserves the distinction
between execution success, citation identity, and explicitly selected human judgments.
