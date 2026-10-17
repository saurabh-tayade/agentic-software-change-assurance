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

| Strategy | Live execution during M1.7c | Ranked evidence / QuotePolicy position | Q&A status, citations, latency, usage |
| --- | --- | --- | --- |
| KEYWORD | Not executed | Pending | Pending |
| VECTOR | Not executed | Pending; prior retrieval-only observation above | Pending |
| HYBRID | Not executed | Pending | Pending |

The task environment did not export `EVIDENCE_DB_URL`, `EVIDENCE_DB_USER`, or
`EVIDENCE_DB_PASSWORD`. The existing local database was not modified and no live
Ollama answer was generated. Deterministic tests separately verify RRF mechanics,
real PostgreSQL composition, citation identity, failure propagation, and CLI wiring;
their answer doubles do not establish model quality.

For each future run, retain requested/effective strategy, profile/digest/options,
publication, query/topK, branch candidate counts, complete ranked evidence and
QuotePolicy position, context selection/omissions, answer status, cited source
identities, prompt/model settings, invocation metadata, total duration, and available
usage. Human review should assess each claim against its cited source and note
unsupported conclusions or missing facts. Do not force a particular answer status.

The [M1.6 live acceptance record](../qa/live-acceptance-m1-6-v1.md) separately
records the model's cents/dollars failures despite structurally valid citations.
Neither RRF nor citation validation establishes that this error is resolved.
