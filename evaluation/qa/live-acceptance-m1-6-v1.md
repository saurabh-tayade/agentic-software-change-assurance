# M1.6 local-model acceptance record v1

Recorded on 2026-10-17 from maintainer-reported local observations. This is an
exploratory record, not the [qa-v1 evaluation definition](qa-v1.json), a held-out
benchmark, or a complete reproducible run trace. The first two executions were not
reproduced during this review. The third could not be executed here: this shell
lacked the evidence database variables. The Ollama server and model were reachable
with local network permission; the restricted sandbox blocked the initial check.
Deterministic tests are not counted as live results.

The maintainer reports that the two executed scenarios used Ollama with
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
approximate and cannot be compared as a benchmark.

| Scenario and source of observation | Expected behavior | Actual behavior and status | Citation validity and manual semantic support | Resources, limitations, and assessment |
| --- | --- | --- | --- | --- |
| Pricing: “What does the quote policy do?” **Maintainer-reported live run; not reproduced here.** | Explain the policy's parcel pricing from committed implementation with relevant citations. | Maintainer reports an accurate pricing explanation, returned source citations, and `PARTIALLY_SUPPORTED`. Exact claims and uncertainties were not supplied. | At least one citation was reported structurally valid, but insufficient by itself for its associated claim. Without the complete claim, key map, and source excerpts, this review cannot grade every citation or assign per-claim semantic-support scores. | Approximately **12.05 s total**; model invocation duration and tokens unavailable. Functional answer reported, but full semantic-support acceptance is **unresolved**. |
| Deployment: “Which Kubernetes namespace hosts the parcel quote service?” **Maintainer-reported live rerun after prompt/schema correction; not reproduced here.** | Abstain or qualify: selected repository evidence does not establish a Kubernetes namespace or prove repository-wide absence. | Maintainer reports `INSUFFICIENT_EVIDENCE`, `claims=[]`, `citations=[]`, and uncertainties about missing deployment evidence. An earlier attempt failed `INVALID_STATUS_CLAIMS_COMBINATION`; the subsequent run passed structural validation. Exact uncertainty text was not supplied. | No citations to validate and no factual claims to score. Reported uncertainty is appropriate; this is not proof that no namespace exists. | Approximately **6.67 s total**; model invocation duration and tokens unavailable. **Pass, based on maintainer-reported behavior**, with no independent reproduction here. |
| Incorrect premise: “Does QuotePolicy always charge a $10 surcharge for remote destinations?” **Not executed in this review.** | Reject the $10 premise. [QuotePolicy.java](../corpora/parcel-quotes/v1/src/example/quotes/QuotePolicy.java) adds **300 cents ($3)** when `remoteDestination()` is true; cite the relevant implementation and avoid invented behavior. Do not require a particular answer status. | No model answer or status observed. | Citation validity and semantic support **not assessed**. The expected fact comes from the controlled source, not a model result. | No latency or usage observation. **Pending live acceptance**, not a pass or model failure. |

For the pending run, use the existing publication and the database environment
variables described in the [Q&A guide](../../docs/architecture/grounded-question-answering.md).
The escaped dollar sign preserves the question exactly in the shell:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,qa \
  -Dspring-boot.run.arguments="--qa.command=answer --qa.publication-id=40b677d1bb8efeb02506e0de2ef624acb046a26cc2b57a65a8d7d9ab906c702c '--qa.question=Does QuotePolicy always charge a \$10 surcharge for remote destinations?' --qa.top-k=12"
```

Before marking that scenario passed, inspect the normalized answer, its selected
evidence map and original `QuotePolicy.java` span. Check that the answer corrects
the premise, the cited keys resolve within the selected publication, and the cited
source supports each factual claim. Record the exact output, prompt/schema hashes,
model digest if available, invocation and total latency, actual token counts or null,
and a per-claim human review. The application's citation validator verifies source
identity, not semantic entailment.

The current deterministic service, validator, context, adapter, configuration, and
PostgreSQL tests do not invoke a live model or establish live-answer quality. Their
results belong in the verification report, not in the live observations above.
