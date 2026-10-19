# Offline evaluation reporting (M1.8c)

M1.8c produces JSON and Markdown from saved evaluation artifacts, without PostgreSQL,
Ollama, an LLM judge, or new storage. [M1.8a](retrieval-evaluation.md) captures and grades
retrieval; [M1.8b](grounded-answer-evaluation.md) captures the same Q&A execution and
supports attributed human review; M1.8c validates and presents these observations.
Retrieval, prompts, models, answer generation, and historical ground truth are unchanged.
M1.9 now exposes Repository Intelligence through a [local API/UI](repository-intelligence-workspace.md);
evaluation dashboards remain planned.

## Inputs and validation

Use `evaluation.command=report` with explicitly selected local files:

| Property | Input |
| --- | --- |
| `evaluation.retrieval-execution` | Optional M1.8a `execution.json`, schema 1. |
| `evaluation.retrieval-grades` | Optional M1.8a grading observation, `source-span-union-first-overlap-v1`. |
| `evaluation.qa-execution` | Optional M1.8b `qa-execution.json`, schema 1. |
| `evaluation.qa-grades` | Optional JSON array of explicit Q&A grading paths, `qa-independent-dimensions-v1`; at most 32. |
| `evaluation.report-output` | Required new directory for `report.json` and `report.md`. |

Retrieval-only, Q&A-only, and combined reports are supported. At most one execution
run of each type is selected; this is sufficient for the existing multi-strategy
capture grids. The reporter never discovers nearby files, chooses a newest file,
connects to a service, or repeats retrieval/model generation. Input files are read-only,
bounded to 64 MiB each, and parsed with duplicate-key, trailing-token, and unknown-field
rejection. The historical M1.5 baseline format is not silently treated as an M1.8a run.

With an execution capture, existing graders replay its deterministic checks. A supplied
grading observation must equal the replay, including run/configuration identities,
per-case grades, aggregates, and execution hashes. Review bindings use the existing
`QaHumanReview.validate` checks. Unsupported schema/definition/grader/rubric versions,
changed metric values, mismatched artifacts, missing files, and invalid reviews fail
explicitly; they do not disappear from a successful report.

An execution alone is sufficient: its grades are derived using the existing versioned
grader. Source hashes in the manifest identify supplied files; the grading version
identifies derived grades when there is no supplied grade file.

Unreviewed **grades-only** inputs are supported as `GRADES_ONLY_NOT_VERIFIABLE`
observations. Schema, identity shape, metric ranges, and available denominator
accounting (including consistency with recorded per-case values) are checked, but
source provenance/configuration and metric replay cannot
be verified without the capture. They do not permit direct strategy comparisons.
Missing claim counts, timing, source references, and provenance remain null/unavailable.
Reviewed Q&A grades require an explicitly supplied execution capture (`EXECUTION_REQUIRED`);
otherwise their execution hash and source-bound review cannot be fully validated.
Do not use grades-only observations as verified acceptance evidence.

The error codes are `INVALID_INPUT`, `UNSUPPORTED_VERSION`, `ARTIFACT_MISMATCH`,
`EXECUTION_REQUIRED`, `DUPLICATE_INPUT`, `INVALID_REVIEW`, `CONFLICTING_REVIEW`,
`OUTPUT_EXISTS`, and `IO_FAILURE`. Messages contain bounded categories, not source,
provider exception bodies, or private review text. Recorded execution failures are
valid report data; malformed report input is a report-command failure.

## Explicit human-review selection

Supply only the grading observations you intend to include. With a capture, multiple
files may contribute reviews of **different executions**. An unreviewed base grading
file may accompany a selected reviewed observation. The reporter never merges
judgments about the same execution:

- Duplicate input content or repeated review IDs are rejected.
- Two distinct reviews of the same execution are rejected as `CONFLICTING_REVIEW`,
  even if their scores agree. Select one explicitly and generate another report if
  you want to inspect the alternative.
- Review ID, reviewer, date, rationale, rubric, and exact execution hash are preserved.
- Source observations and earlier reports are never overwritten.
- A record may review factual correctness while leaving support, relevance,
  completeness, answerability, and uncertainty NOT_REVIEWED.

No supplied review means NOT_REVIEWED, not correct. An attributed review is a human
assertion, not automatic verification or reviewer consensus. The existing one-review
per grading invocation workflow remains unchanged.

## Report contract and reproducibility

`EvaluationReport` schema 1 / generator `evaluation-report-v1` has this shape:

```json
{
  "schemaVersion": 1,
  "generatorVersion": "evaluation-report-v1",
  "reportId": "<new UUID>",
  "generatedAt": "<UTC instant>",
  "payloadHash": "<SHA256 of canonical payload JSON>",
  "payload": {
    "inputs": ["<role, absolute path, byte-content hash, run and configuration IDs>"],
    "evaluationTypes": ["RETRIEVAL", "QA"],
    "retrieval": "<provenance, original grades, comparison qualifications, execution details>",
    "qa": "<provenance, grades/reviews, independent counts, execution details>",
    "findings": ["<recorded failure or missing-judgment observations>"],
    "limitations": ["<explicit qualifications>"]
  }
}
```

This is a shape illustration, not an execution record. An absent evaluation type is
null. The JSON includes expanded definition identities/content hashes, imported source
hashes, publication/snapshot/configuration metadata, effective strategy identifiers,
K values, embedding profile/digest, actual answer settings, prompt/schema hashes,
available application revision, grading versions, and selected reviews. Per-case
source evidence includes original chunk IDs, publication, repository, commit, path,
line range, source hash, and content hash. Actual context keys remain mapped to these
identities. Retrieved evidence arrays retain captured ranking order.

Available timing and invocation/token diagnostics are retained per execution, not
converted into percentiles or filled with zero. Provider failures retain their category
and available bounded diagnostics. Raw model output, full prompts, and full repository
source are not copied to reports. Claims, source paths, review text, and attribution
may still be sensitive: keep these local outputs private unless explicitly reviewed
for publication. Markdown escapes untrusted text rather than rendering repository or
review content as links, HTML, headings, or extra table rows.

Report ID/time are outside the substantive payload. The payload hash canonicalizes
JSON object-key order, preserves array order, and excludes variable report metadata.
Identical explicit inputs and generator version produce the same payload hash even
across separate JVM processes. Input paths are part of the manifest: relocating files
changes that manifest/hash, although their content hashes still identify identical
observations. Preserve the inputs and implementation revision for reproduction.

The output directory must not already exist. A write failure may leave a partial
new directory; inspect it and select a different output path rather than overwriting.
Inputs are not altered. Local hashes detect accidental drift, not coordinated malicious
edits to captures and their identities. Saved captures permit offline replay, not a
guarantee that a fresh model invocation will reproduce the same answer.

## Denominators and comparison rules

Retrieval tables use the existing `RetrievalEvaluationArtifacts.Grades` unchanged:

- Scheduled, successful, and failed counts are separate for each strategy and K.
- Quality denominators count only successful cases with nonempty expected evidence.
- Successful empty-expectation cases have null metrics and a separate count.
- Recall uses full-span line-union coverage; MRR uses first overlap. The report
  reuses `RetrievalGrading` for missing-span diagnostics, with no alternate metric.
- A failed execution has unavailable missing-span/timing information where it was
  never captured. It does not become a zero-quality success.

Direct retrieval comparison is qualified `COMPARABLE_WITHIN_RUN` only with verified
captures, at least two strategies, nonempty positive-evidence populations, no failed
executions, and the same successful positive-evidence case sets. Existing replay
validation enforces the shared definition, expected configuration, source membership,
and strategy/profile identities. Incomplete or grades-only data is labeled
`NOT_DIRECTLY_COMPARABLE` with reasons. Tables preserve all denominators and do not
sort strategies into a leaderboard. K=5 and K=12 remain different configurations.

Combined retrieval and Q&A results have separate populations, definitions, and
configuration metadata. They are never pooled or ranked against each other. The
historical keyword baseline remains an independently versioned baseline, even if
M1.8a reproduces its numbers. This small visible corpus cannot establish statistical
significance, general retrieval superiority, or production performance.

Q&A accounting separates:

- Total executions, successful application outcomes, and categorized failures.
- Retrieval coverage versus coverage in actual model context; missing retrieved
  spans versus fully retrieved spans lost to context limits.
- VALID, INVALID, NOT_APPLICABLE, and NOT_VERIFIABLE citation identities. The
  evaluable-identity denominator is VALID + INVALID; abstentions and unverifiable
  cases remain separately visible in the complete execution population.
- Structural abstention versus human-reviewed appropriateness of abstention.
- Review records versus reviewed/unreviewed **answer** executions. Failures with no
  answer cannot create claim-correctness credit.
- Claim CORRECT, INCORRECT, INDETERMINATE, and NOT_REVIEWED counts across actual
  claims; support and citation relevance retain independent review states.
- Completeness uses expected-finding rows for answer executions; answerability and
  uncertainty use answer-execution denominators. Null scores mean NOT_REVIEWED.

No accuracy percentage is inferred from a sparse selected review. A synthetic test
example demonstrates six successes, five applicable valid identities, one reviewed
execution with one incorrect and one correct claim, and five unreviewed executions.
It does not establish new live-model quality. A PARTIALLY_SUPPORTED status alone
never produces a factual-error finding.

## Failure findings

Findings identify evaluation type, scenario/strategy/execution, recorded basis,
source identities when available, and selected review/claim references where applicable.

| Classification | Recorded basis |
| --- | --- |
| RETRIEVAL_FAILURE | Retrieval/embedding execution failed; original category retained. |
| EXPECTED_EVIDENCE_MISSING | Expected spans were not fully retrieved. |
| CONTEXT_EVIDENCE_LOSS | Fully retrieved expected spans were not fully supplied to the model. |
| INVALID_CITATION_IDENTITY | Citation identity check or application rejection was invalid. |
| STRUCTURAL_ANSWER_FAILURE | Malformed output or invalid structural grade. |
| MODEL_OR_PROVIDER_FAILURE | Recorded model unavailable/timeout or other model failure. |
| EVIDENCE_INTEGRITY_FAILURE / CONTEXT_INTEGRITY_FAILURE | Offline provenance/context checks failed. |
| EXECUTION_FAILURE | Other explicit application failure, retained without guessing a cause. |
| HUMAN_REVIEWED_FACTUAL_ERROR | A selected human review marked a claim INCORRECT. |
| HUMAN_REVIEWED_FACTUAL_ISSUE | A selected review recorded a categorized claim/uncertainty issue. |
| SEMANTIC_NOT_REVIEWED | No attributed review was supplied for the execution. |

Findings can overlap; their count is not a count of failed executions. A factual error
retains its original successful execution and VALID citation identity where recorded.
Partial review dimensions remain visible even when a record exists. Explanations are
deterministic templates based on recorded facts, not inferred model diagnoses.

## Exact local commands

Run from the repository root with Java 21 and **no persistence/qa/embeddings profiles**.
The CLI needs neither Docker nor Ollama. `spring.profiles.active=default` explicitly
selects the ordinary model-free configuration.

Retrieval-only report:

```sh
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments='--spring.profiles.active=default --spring.main.web-application-type=none --evaluation.command=report --evaluation.retrieval-execution=target/evaluation/c2c7b478-c142-462f-ac9b-79c6fd81d9de/execution.json --evaluation.report-output=target/evaluation/reports/retrieval-001'
```

For the combined acceptance report, select the **exact corrected grading file**.
Do not use a glob, modification time, or automatically choose the newest file:

```sh
export QA_FINAL_GRADES='target/evaluation/e8e2d497-8eef-43bd-9cde-b74aaca780c6/<maintainer-selected-qa-grades-file>.json'
export EVALUATION_QA_GRADES="$(python3 -c 'import json,sys; print(json.dumps([sys.argv[1]]))' "$QA_FINAL_GRADES")"
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments='--spring.profiles.active=default --spring.main.web-application-type=none --evaluation.command=report --evaluation.retrieval-execution=target/evaluation/c2c7b478-c142-462f-ac9b-79c6fd81d9de/execution.json --evaluation.qa-execution=target/evaluation/e8e2d497-8eef-43bd-9cde-b74aaca780c6/qa-execution.json --evaluation.report-output=target/evaluation/reports/combined-001'
unset EVALUATION_QA_GRADES
```

The environment variable is the JSON-array form of `evaluation.qa-grades`; no private
review text is placed in the command. Replace the selected filename with the exact
maintainer-confirmed observation and restore the retrieval capture at the shown path
if absent. A supplied retrieval grading file can additionally be checked by adding
`--evaluation.retrieval-grades=...`.

For Q&A-only, use the same explicit review selection and omit
`--evaluation.retrieval-execution=...`; select a new output directory such as
`target/evaluation/reports/qa-001`. Without selected grading files, all semantic
judgments remain NOT_REVIEWED even if other files exist nearby.

Inspect `report.json` and `report.md`. For the maintainer's accepted captures, verify
90 retrieval executions/zero failures, the historical keyword Recall@5 and MRR@5,
six Q&A executions/zero failures, five valid applicable citation identities, one
reviewed execution, one INCORRECT and one CORRECT reviewed claim, and all other
semantic dimensions unreviewed. These are acceptance expectations, not constants in
the reporter. A second invocation with a new output directory must reproduce the
same payload hash. Keep inputs/reports outside `target` before `mvnw clean` if they
must survive build cleanup.

## Verification scope

```sh
./mvnw -Dtest=EvaluationReportTests,EvaluationReportCliTests,RetrievalEvaluationTests,RetrievalGradingTests,QaEvaluationTests,QaHumanReviewTests test
./mvnw clean verify
python3 scripts/check_repository.py
```

Deterministic tests exercise all report modes, existing metrics, failure populations,
review attribution and conflict rejection, sparse/indeterminate review dimensions,
unsupported/mismatched inputs, escaping, serialization, repeated generation, and the
offline CLI without provider/database beans. Existing M1.8a/b tests remain unchanged.
Live artifact acceptance is an offline replay of saved observations, not a fresh
retrieval or model benchmark. Missing input captures prevent combined verification;
no replacement observations or semantic scores are fabricated.

Implementation verification passed 345 Java tests (260 unit, 85 PostgreSQL
integration), with no failures, errors, or skips. Focused reporting/evaluation
regressions and repository checks passed. The available saved six-execution Q&A
capture was replayed through the actual offline CLI twice in separate JVMs: both
reports had identical substantive payload hashes, six successes, zero failures,
and five VALID/evaluable citation identities (one abstention was NOT_APPLICABLE).
No grading observation was selected for that replay, pending confirmation of the
corrected review file; all seven captured claims consequently remained NOT_REVIEWED
in those two reports. This does not discard or invalidate the maintainer's existing
review. The specified retrieval capture was absent, so combined live-artifact
acceptance and its historical baseline numbers were not reproduced here.
