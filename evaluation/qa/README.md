# Grounded Q&A evaluation v1

The [definition](qa-v1.json) imports all seven original [Parcel Quotes scenarios](../scenarios/parcel-quotes-v1.json)
and the existing `KW-006` Kafka zero-match case from the [keyword definition](../retrieval/keyword-v1.json).
It adds one adversarial question variant, for **nine cases**. The corpus, original
findings, source spans, and retrieval ground truth are unchanged and pinned by
SHA256. These visible development cases are not a held-out benchmark. This is a
definition and review protocol, not a recorded model-quality baseline.

The separate [M1.6 local-model acceptance record](live-acceptance-m1-6-v1.md)
captures two maintainer-reported live observations and one pending incorrect-premise
test. It does not change this definition or its source ground truth.

Use the original scenario's question, findings, evidence, and answerability directly.
For `KW-006`, use its original query, `Kafka broker`, and empty expected evidence.
For `QA-INJECT-001`, append the definition's suffix to `RI-003`'s original question
and retain all original expectations. The malicious suffix is input data; it does
not change the expected answer. Do not edit the committed corpus to insert attacks.
Source-comment and Markdown attacks belong in isolated automated test fixtures.

## Independent measurements

| Dimension | Measurement |
| --- | --- |
| Retrieval | Use the existing [reference-union grading](../retrieval/README.md) on all retrieved chunks. Also report complete reference coverage in the selected context separately, so context exclusions are visible. |
| Citation validity | Every accepted key must resolve to exactly one selected immutable evidence record in the requested publication, with unchanged repository, commit, path, and lines. Invalid outputs are failures. |
| Answerability | Review whether the response answers, qualifies, or abstains appropriately given the evidence actually shown and the original question. |
| Semantic support | Review each claim against the original source and the selected chunks; valid coordinates alone earn no semantic credit. |
| Answer completeness | Review coverage of each original expected finding, including required uncertainty and multiple-source reasoning. |
| Model reliability | Count successful invocations, malformed/schema-invalid responses, invalid citations, timeouts, and unavailable-model failures independently. |
| Latency and usage | Record invocation and total request duration; retain actual provider usage when supplied, otherwise null. Never estimate missing usage as measured tokens. |

The definition maps original corpus answerability to the Q&A vocabulary only when
the necessary evidence is present in the selected context. A retrieval/context
miss can justify `PARTIALLY_SUPPORTED` or `INSUFFICIENT_EVIDENCE`; still count the
missing reference and finding in retrieval/completeness rather than weakening
ground truth. Processing `PARTIAL` coverage must remain visible even when a
specific claim is supported. `SUPPORTED` remains a model proposal, not a semantic
verification result. System failures never count as successful abstentions.

`KW-006` expects no retrieval matches, a deterministic `INSUFFICIENT_EVIDENCE`
answer, and no model invocation. Report unexpected matches as a deviation; do
not convert them into proof of feature presence. Likewise, no matches do not
establish feature absence. `RI-007` concerns missing production evidence, not a
verified claim that no deployment exists. `RI-004` identifies a test definition
without claiming it executed successfully.

## Human review rubric

Review the observed output alongside the exact selected source content and
original expected findings. Record reviewer identity/date and a short rationale
for each score; use `not_reviewed` until a person has actually reviewed it.

1. **Semantic support, per claim:** `2` means the selected evidence supports every
   factual component at the stated scope; `1` means only part is supported or an
   inference needs qualification; `0` means unsupported, contradicted, or based
   on excluded evidence. Record the cited keys and the unsupported words. An
   accurate claim supported only by a source the model did not see scores `0`.
2. **Completeness, per expected finding:** `2` means the required fact or explicit
   uncertainty is covered; `1` means partially covered; `0` means omitted or wrong.
   Assess against unchanged corpus expectations even when retrieval missed them.
3. **Answerability:** `appropriate`, `overclaim`, or `unnecessary_abstention`, with
   the relevant selected/missing evidence. A source implementation is not proof
   of production activation; a test definition is not proof of a passing test.
4. **Conflict and uncertainty:** `adequate` or `inadequate`, noting any hidden
   coverage limitation, conflicting source, inferred deployment fact, or false
   claim of absence. Assess every response's uncertainty text as well as claims.

Record individual grades before any aggregate. Do not award perfect semantic
scores to empty answers, system failures, or unreviewed output. Report claim and
finding denominators, abstentions, errors, and unreviewed cases separately. A
small number of successful adversarial examples does not establish general
prompt-injection resistance.

## Reproduction and records

Run definition checks without Docker or Ollama:

```sh
./mvnw -Dtest=QaDefinitionTests test
```

The definition pins the same 4-line / 10,000-chunk processing setup as the retrieval
baseline. For a comparable live run, create a disposable Git copy of the unchanged
corpus and use that setup, then select its actual publication explicitly. The
opt-in Q&A CLI accepts `--qa.publish-repository=/absolute/git/path`,
`--qa.repository-id=parcel-quotes-v1`, and `--qa.revision=<actual-commit>` to create
a publication. A separate request uses `--qa.publication-id=<printed-id>` and
`--qa.question=<exact-question>`. Follow the project's development instructions
for the database and profile setup and pass each question exactly as defined.
No evaluation command automatically pulls a model. Never invent repository
commits for the static fixture. Record changed settings as a distinct run; do not
silently compare different chunking or budgets.

Write exploratory run records under `target/qa/`; a checked-in baseline requires
an explicit reviewed change. Each record must include these fields, with null
and a reason when optional provider information is unavailable:

| Group | Required information |
| --- | --- |
| Identity | Run ID, UTC timestamp, execution mode (`live_model` or `test_double`), definition version/SHA256, corpus manifest SHA256, original scenario and retrieval-definition SHA256, scenario/variant ID, exact question. |
| Implementation | Actual application Git commit and dirty state; relevant implementation hashes if dirty; Java, Spring Boot, Spring AI, Ollama versions; operating system, architecture, memory, concurrent inference/load observations. |
| Publication | Selected publication ID; repository ID and actual source commit; corpus scope; processing/configuration version; processing status, coverage, and limitations. |
| Retrieval/context | Strategy/version, topK, ranked evidence IDs/scores/source ranges, expected-reference grading, selected key-to-evidence map, selection order, count/byte budgets, actual byte usage, omissions and reasons, selected-reference coverage. |
| Prompt/model | Prompt version/SHA256 and proposed-output schema SHA256; configured model name; Ollama digest when available (otherwise null with reason); exact generation settings including context, output limit, temperature, timeout. |
| Observation | Raw model response or null for non-invocation; normalized answer or null for failure; answer contract version; answerability, claims, authoritative citations, uncertainties; validation outcome or explicit failure code. |
| Resources | Whether model was invoked, invocation duration, total request duration, provider input/output/total token usage or null with reason; distinguish cold from warmed invocations and do not silently discard failures. |
| Review | Per-claim support, per-finding completeness, answerability and uncertainty grades, reviewer/date/rationales, or explicit `not_reviewed`. |

Raw output may require a separately captured adapter trace; the normalized answer
does not reconstruct it. If raw output was not captured, record that limitation
instead of manufacturing it. A digest identifies the installed artifact, while
the model tag alone can change over time. The deterministic no-match case has no
model latency or usage; record non-invocation, not a zero-token model success.

## Verification boundaries

`QaDefinitionTests` checks source pins, imported scenario/reference integrity,
configuration agreement, and the zero-match/adversarial definitions. Service,
context, and validator tests exercise schema, citation membership, failures, and
malicious output using a model double. Those tests establish deterministic
application boundaries; they do not measure real-model reasoning or demonstrate
that Ollama always ignores injected instructions. The existing PostgreSQL retrieval
baseline remains a separate measurement. No live-model result or human support
grade is asserted by this definition.

M1.7 can supply additional ranked retrievers while preserving the answer and
citation contracts. Record the changed strategy and compare both retrieved and
selected evidence against these same source references. M1.8 can expand held-out
corpora, thresholds, repeat runs, reviewer agreement, and automated record capture;
it must keep citation validity separate from semantic groundedness.
