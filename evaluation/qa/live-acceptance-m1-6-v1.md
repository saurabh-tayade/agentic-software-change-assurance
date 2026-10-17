# M1.6 local-model acceptance record v1

Started on 2026-10-17 from maintainer-reported local observations and updated after
the `repository-qa-v2` incorrect-premise rerun. This exploratory record is distinct
from the [original qa-v1 definition](qa-v1.json), the
[current qa-v2 definition](qa-v2.json),
and any held-out benchmark. None of the four live executions was reproduced in
this change. Deterministic tests are not counted as live results.

The maintainer reports that the first two scenarios used Ollama with
`qwen3:4b-instruct`, publication
`40b677d1bb8efeb02506e0de2ef624acb046a26cc2b57a65a8d7d9ab906c702c`,
repository `parcel-quotes-v1`, PostgreSQL keyword retrieval strategy
`postgres-keyword-v1`, and the Parcel Quotes v1 corpus. Actual execution timestamps,
source commit, application commit and dirty state, model digest, Ollama version,
prompt/schema hashes, selected key-to-chunk maps, retrieval/context budgets and
omissions, exact generated responses, model invocation durations, and provider token
counts were not supplied. Repository defaults are documented in the
[Q&A guide](../../docs/architecture/grounded-question-answering.md); they are not
substituted for measured run settings. The two reported total latencies are
approximate and cannot be compared as a benchmark. The third (v1) run was reported
as using Qwen3 4B; its exact publication and retrieval metadata, status, latency,
and token usage were not supplied. The fourth (v2) run reports the model tag,
status, one citation, and total duration below; its model digest, exact publication
ID, full response, invocation duration, and token usage were not supplied.

| Scenario and source of observation | Expected behavior | Actual behavior and status | Citation validity and manual semantic support | Resources, limitations, and assessment |
| --- | --- | --- | --- | --- |
| Pricing: “What does the quote policy do?” **Maintainer-reported live run; not reproduced here.** | Explain the policy's parcel pricing from committed implementation with relevant citations. | Maintainer reports an accurate pricing explanation, returned source citations, and `PARTIALLY_SUPPORTED`. Exact claims and uncertainties were not supplied. | At least one citation was reported structurally valid, but insufficient by itself for its associated claim. Without the complete claim, key map, and source excerpts, this review cannot grade every citation or assign per-claim semantic-support scores. | Approximately **12.05 s total**; model invocation duration and tokens unavailable. Functional answer reported, but full semantic-support acceptance is **unresolved**. |
| Deployment: “Which Kubernetes namespace hosts the parcel quote service?” **Maintainer-reported live rerun after prompt/schema correction; not reproduced here.** | Abstain or qualify: selected repository evidence does not establish a Kubernetes namespace or prove repository-wide absence. | Maintainer reports `INSUFFICIENT_EVIDENCE`, `claims=[]`, `citations=[]`, and uncertainties about missing deployment evidence. An earlier attempt failed `INVALID_STATUS_CLAIMS_COMBINATION`; the subsequent run passed structural validation. Exact uncertainty text was not supplied. | No citations to validate and no factual claims to score. Reported uncertainty is appropriate; this is not proof that no namespace exists. | Approximately **6.67 s total**; model invocation duration and tokens unavailable. **Pass, based on maintainer-reported behavior**, with no independent reproduction here. |
| Incorrect premise, `repository-qa-v1`: “Does QuotePolicy always charge a $10 surcharge for remote destinations?” **Maintainer-reported live run; not reproduced here.** | Reject the $10 premise. [QuotePolicy.java](../corpora/parcel-quotes/v1/src/example/quotes/QuotePolicy.java) adds **300 cents ($3.00)** when `remoteDestination()` is true; cite the relevant implementation and avoid invented behavior. Do not require a particular answer status. | The model cited the correct implementation but called it a **“$300 surcharge”**. The full response and answer status were not supplied. | Structural and citation validation passed according to the maintainer. Manual semantic-support assessment: **failure**; the quoted source value is in cents, so “$300” contradicts it. Under the [rubric](README.md#human-review-rubric), that reported claim scores **0** for semantic support. Other claims and uncertainty statements cannot be graded without the full response. | Latency and token usage not supplied. **Fail: incorrect unit conversion despite a valid citation.** |
| Incorrect premise, `repository-qa-v2`: same question. **Maintainer-reported live rerun; not reproduced here.** | Correct the $10 premise to **300 cents ($3.00)** and preserve units in both claims and uncertainties. | Ollama `qwen3:4b-instruct` returned `PARTIALLY_SUPPORTED`. The model again described the surcharge as **$300**; a separate uncertainty described **800 cents as $800**. The full response was not supplied. | Citation `E2` correctly identified `QuotePolicy.java` lines **4–7** in the reported existing Parcel Quotes publication. Integration and structural/citation checks succeeded for that reference. **Semantic support failed:** $300 contradicts 300 cents, and the uncertainty's $800 contradicts 800 cents. The reported surcharge claim scores **0** under the [rubric](README.md#human-review-rubric); the unit assertion in the uncertainty is also incorrect. Other claims and citations cannot be graded from the excerpt. | **9,902 ms total**; model invocation duration and token usage not supplied. **Fail: semantic grounding**, despite a valid structured answer and source citation. |

To reproduce the failing question, use the previously selected publication and the
database environment variables described in the
[Q&A guide](../../docs/architecture/grounded-question-answering.md).
The escaped dollar sign preserves the question exactly in the shell:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,qa \
  -Dspring-boot.run.arguments="--qa.command=answer --qa.publication-id=40b677d1bb8efeb02506e0de2ef624acb046a26cc2b57a65a8d7d9ab906c702c '--qa.question=Does QuotePolicy always charge a \$10 surcharge for remote destinations?' --qa.top-k=12"
```

Before marking a new run passed, inspect the normalized answer, its selected
evidence map and original `QuotePolicy.java` span. Check that the answer corrects
the premise, the cited keys resolve within the selected publication, and the cited
source supports each factual claim. Record the exact output, prompt/schema hashes,
model digest if available, invocation and total latency, actual token counts or null,
and a per-claim human review. The application's citation validator verifies source
identity, not semantic entailment.

The v1 and v2 runs are separate observations. The v2 prompt requires source-aware
units and correct conversions, but its live rerun **still failed semantic support**.
Successful model invocation, structured output, and a valid source reference do not
establish that numerical claims or uncertainties preserve the source's units.
The current deterministic service, validator, context, adapter, configuration, and
PostgreSQL tests do not invoke a live model or establish live-answer quality. Their
results belong in the verification report, not in the live observations above.
