# Initial M1.7 retrieval observations

This record preserves descriptive local observations, not a benchmark. The
[keyword baseline v1](README.md), its query definition, corpus, and ground truth
are unchanged. Reproduction commands are in the
[Q&A guide](../../docs/architecture/grounded-question-answering.md).

## Inputs

- Repository: `parcel-quotes-v1`.
- Publication: `40b677d1bb8efeb02506e0de2ef624acb046a26cc2b57a65a8d7d9ab906c702c`.
- Embedding profile: `6360aef03c6233a05a91583849c68ae0d6a97a66b1ef985de22e92876aa2aa0b`.
- Embedding model: `nomic-embed-text:v1.5`, 768 dimensions.
- Question: “Where is the shipping cost calculated?”

These are local inputs, not application constants. Retrieve the full profile to
capture its digest and effective options; no unavailable model digest is inferred
from the profile ID.

## Maintainer-reported M1.7b live result

The maintainer reported successful standalone vector retrieval using the inputs
above. README evidence occupied ranks 1–3; the actual
`QuotePolicy.priceCents()` implementation appeared at rank 5. Rank 4, exact chunk
identities/spans/scores, invocation latency, and usage were not supplied. No Q&A
status, generated citations, or semantic-support assessment was supplied for this
retrieval-only run. It was not reproduced during M1.7c implementation.

The prominence of documentation is an observation worth comparing against keyword
and fused rankings. It does not demonstrate better Recall@K, MRR, NDCG, answer
quality, or semantic groundedness.

## M1.7c three-strategy acceptance

The maintainer subsequently executed the same question against the publication
above using all three Q&A retrieval strategies and reported these live results.
These observations were supplied by the maintainer; they were not independently
reproduced during this documentation update.

| Strategy | QuotePolicy.priceCents() retrieval | Answer status | Maintainer-observed answer and citations | Total latency |
| --- | --- | --- | --- | --- |
| KEYWORD | Not retrieved | `INSUFFICIENT_EVIDENCE` | Insufficient evidence to answer | 7,337 ms |
| VECTOR | Retrieved at rank 5 | `SUPPORTED` | Correct answer with relevant citations | 10,558 ms |
| HYBRID | Retrieved at rank 5 | `SUPPORTED` | Correct answer with a relevant citation | 7,836 ms |

The strategy versions and profile association follow the existing
[keyword](../../docs/architecture/keyword-retrieval.md),
[vector](../../docs/architecture/vector-retrieval.md), and
[hybrid](../../docs/architecture/hybrid-retrieval.md) documentation:

| Selection | Versioned strategy | Embedding profile |
| --- | --- | --- |
| KEYWORD | `postgres-keyword-v1` | Not applicable |
| VECTOR | `postgres-vector-v1:profile=<profile-id>` | The profile in Inputs above |
| HYBRID | `postgres-hybrid-rrf-v1:k=60:depth=<candidate-depth>:weights=1,1:profile=<profile-id>` | The same profile in Inputs above |

The maintainer confirmed hybrid RRF `k=60` and equal weights. The documented
three-strategy commands use topK=12, giving hybrid candidate depth 48 under
`min(100, max(20, 4 * topK))`. This describes the reproduction configuration;
raw run diagnostics were not supplied to independently confirm the effective
topK/depth or full strategy strings. Exact answer text, citation identities/spans,
complete rankings, token usage, and isolated model/retrieval timings were also not
supplied. Correctness and citation relevance above are maintainer assessments,
not a separately scored semantic-support review.

Each latency is the reported total duration of one execution. These observations
are not aggregate evaluation metrics, repeated latency measurements, or evidence
that HYBRID outperforms VECTOR. Both retrieved the implementation at rank 5 and
were reported to answer correctly. No Recall@K, MRR, NDCG, or general groundedness
improvement is established by this single question.

During the earlier M1.7c implementation task, database connection variables were
unavailable and live execution remained pending. The maintainer's later runs above
replace that pending status without changing the earlier standalone M1.7b record.
Deterministic tests separately verify RRF mechanics, real PostgreSQL composition,
citation identity, failure propagation, and CLI wiring; their answer doubles do
not establish model quality. This documentation update ran no live model requests.

For each future run, retain requested/effective strategy, profile/digest/options,
publication, query/topK, branch candidate counts, complete ranked evidence and
QuotePolicy position, context selection/omissions, answer status, cited source
identities, prompt/model settings, invocation metadata, total duration, and available
usage. Human review should assess each claim against its cited source and note
unsupported conclusions or missing facts. Do not force a particular answer status.

The [M1.6 live acceptance record](../qa/live-acceptance-m1-6-v1.md) separately
records the model's cents/dollars failures despite structurally valid citations.
Neither RRF nor citation validation establishes that this error is resolved.
