# Evidence-grounded question answering (M1.6)

The opt-in Q&A path answers questions about one explicit, already-persisted evidence
publication. It calls the [M1.5 keyword retriever](keyword-retrieval.md), selects
complete chunks for the model, asks the local Ollama model for a structured proposal,
and validates references before returning an answer. The [M1.4 publication](evidence-persistence.md)
retains snapshot identity and processing coverage. The Q&A service does not ingest,
parse, persist, or choose a latest publication.

## Boundaries and contract

`GroundedQuestionAnsweringService.answer(publicationId, question, configuration)`
returns `GroundedAnswer` or a `QaException` with a distinct failure code. The answer
contains the selected `PublicationSummary`, question, answer text, status, claims,
resolved citations, uncertainties, retrieval strategy and limitations, context
selection, prompt/schema hashes, generation settings, model diagnostics when invoked,
and total duration. The status is `SUPPORTED`, `PARTIALLY_SUPPORTED`, or
`INSUFFICIENT_EVIDENCE`. A supported status is **model-proposed**: the validator
establishes citation identity, not that the source semantically supports the claim.
The returned validation marker says this explicitly.
For `INSUFFICIENT_EVIDENCE`, the model must return no claims and at least one
uncertainty; an explanation that selected evidence cannot establish an answer belongs
in uncertainties, without citations to unrelated chunks. Prompt and schema guidance
reinforce this rule, but the validator still rejects inconsistent model output.

Unknown publications, invalid requests, retrieval failure, publication integrity
failure, model unavailability or timeout, malformed output, invalid citations, and
context-budget failure have separate error codes. A known publication with no keyword
matches returns `INSUFFICIENT_EVIDENCE` without a model call. No matches do not prove
that a repository feature is absent. Partial processing and context omissions remain
visible even if a model proposes a supported claim. Failure codes do not masquerade
as an answer. Provider exception details are not part of the CLI's public failure
JSON. There are no automatic generation retries.

## Context and citations

The initial policy is `complete-ranked-chunks-v1`. Defaults request top 12 results,
select at most six unique complete chunks, limit each serialized evidence entry to
3,000 UTF-8 bytes, and limit the complete serialized context to 6,000 UTF-8 bytes.
Oversized chunks and chunks that do not fit are omitted with an explicit reason;
none is silently truncated. Rank order is retained. Duplicate chunk IDs are shown
once, and conflicting duplicate records fail integrity validation. The JSON context
separates `originalSourceContent`, `structuralContext`, and
`retrievalAndSourceMetadata`. The selected context summarizes publication coverage;
the answer retains the full publication summary and retrieval limitations.

The builder assigns `E1`, `E2`, and so on only to selected entries. The model may
propose these keys for individual factual claims. It is not allowed to provide paths,
commits, hashes, or line numbers in its output. The validator requires the exact
schema fields, bounded text and claim counts, consistent status/uncertainty, and
keys from the actual selected context. It rejects extra coordinate fields and
unknown keys. Java resolves each accepted key to its original `EvidenceChunk` and
publication ID after provenance and integrity checks; citations are never generated
from model-supplied coordinates. The complete answer text is assembled from cited
claims. Keyword ranking and source coordinates still cannot prove entailment.

The byte limits are conservative input bounds, **not token counts**. The local model
is configured for 8,192 context tokens and at most 768 output tokens by default.
Prompt and protocol overhead also consume context. A future integration can measure
actual tokenizer use; M1.6 does not claim exact token budgeting. Large or omitted
evidence may make an otherwise answerable question partial or unanswerable.

## Model and trust boundary

Spring AI 2.0.1 is managed by its BOM alongside Spring Boot 4.1.1. The Ollama
adapter is behind `ModelGateway`; no provider objects enter the answer contract.
It sends a versioned system prompt (`repository-qa-v2`) and a JSON data envelope
as separate messages. The provider receives the versioned proposed-output JSON
schema through Ollama's native schema option. Application validation still treats
all output as untrusted. The adapter disables automatic model pulls and thinking,
uses zero temperature, bounded output, a five-second connection timeout and a
120-second response timeout by default. Q&A is enabled only with `persistence,qa`;
ordinary startup and persistence-only use remain model-free. The URL must refer to
local loopback HTTP. Missing or invalid model settings fail clearly.

Repository comments, Java literals, Markdown, paths, structural context, and the
question are lower-trust data. They can ask the model to ignore instructions, but
cannot extend the citation allowlist or add authoritative coordinates. Tests with
malicious data and model-double output verify this application boundary. They do
not establish that a live LLM always resists prompt injection or that every accepted
claim is true. The CLI is a developer tool, not an authenticated public endpoint.

## Local exercise

Use the [database setup guide](evidence-persistence.md) to start PostgreSQL and
set `EVIDENCE_DB_URL`, `EVIDENCE_DB_USER`, and `EVIDENCE_DB_PASSWORD` in your shell.
Install Ollama separately, then explicitly run `ollama pull qwen3:4b-instruct`.
Check `ollama list` and `curl http://localhost:11434/api/tags` before invoking Q&A.
The quantized model is about 2.5 GB; its 8,192-token configured context and
`keepAlive=0s` are conservative starting points for a 16 GB Mac. Startup and CI
never pull a model. The `qa` profile disables the HTTP server.

To publish a local Git repository, identify an **actual** commit SHA and run:

For the controlled corpus, first make a disposable Git copy outside the project:

```sh
mkdir -p /tmp/parcel-quotes-qa
cp -R evaluation/corpora/parcel-quotes/v1/. /tmp/parcel-quotes-qa/
git -C /tmp/parcel-quotes-qa init
git -C /tmp/parcel-quotes-qa add .
git -C /tmp/parcel-quotes-qa -c user.name='Local QA' -c user.email='qa@example.invalid' commit -m 'Local QA corpus'
git -C /tmp/parcel-quotes-qa rev-parse HEAD
```

Use `/tmp/parcel-quotes-qa` and the printed commit SHA in the command below. The
disposable corpus commit is local and does not change this project's Git history.

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,qa \
  -Dspring-boot.run.arguments='--qa.command=publish --qa.publish-repository=/absolute/path/to/repository --qa.repository-id=parcel-quotes-v1 --qa.revision=ACTUAL_COMMIT_SHA --qa.chunk-max-lines=4'
```

The CLI prints a `publicationId`. Use that exact ID in a separate run:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,qa \
  -Dspring-boot.run.arguments="--qa.command=answer --qa.publication-id=ACTUAL_PUBLICATION_ID '--qa.question=What does the quote policy do?' --qa.top-k=12"
```

The `qa` profile requires the persistence profile and a reachable database.
Publishing runs ingestion and processing in the CLI only; answering uses the saved
publication. The output includes citations, uncertainty, coverage, model/prompt and
retrieval identities, context omissions, duration, and provider usage when available.
Provider token counts remain null when Spring AI does not expose reliable counts.
Use the [Q&A evaluation definition](../../evaluation/qa/README.md) for the controlled
Parcel Quotes questions and run record. No real-model quality result is claimed
without a separate live run and human review.
The [M1.6 acceptance record](../../evaluation/qa/live-acceptance-m1-6-v1.md)
separates maintainer-reported live observations from pending and deterministic tests.

### Diagnose malformed model output

When a model response fails validation, the Q&A service writes one warning to the
application console. By default it includes `category=MALFORMED_MODEL_OUTPUT`, a
fixed reason such as `INVALID_JSON` or `INVALID_STATUS_CLAIMS_COMBINATION`, response
length in characters and UTF-8 bytes when available, and whether raw content was
available. It does **not** include the raw response by default. A failure in the
Ollama gateway before it returns text reports `rawResponseAvailable=false`.
The CLI still reports the existing generic `MALFORMED_MODEL_OUTPUT` failure code.

To inspect the model text for the existing answer command, opt in for that run:

```sh
QA_LOG_MALFORMED_MODEL_RESPONSE=true ./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,qa \
  -Dspring-boot.run.arguments="--qa.command=answer --qa.publication-id=ACTUAL_PUBLICATION_ID '--qa.question=Which Kubernetes namespace hosts the parcel quote service?' --qa.top-k=12"
```

Inspect the `Q&A model response rejected` warning in that terminal's application
log. Raw content is escaped onto one line; at most 8,192 UTF-8 bytes of the escaped
text are shown, with `rawResponseTruncated=true` if more was available. The warning
also reports the original response length. Model output may contain sensitive or
untrusted repository content, so use this only in a private local session and avoid
sharing captured logs. Omit the environment variable or set it to `false` on the
next run to disable raw-response logging again. This flag does not alter validation,
retry behavior, or public error messages.

## Next retrieval step

M1.7 can introduce vector or hybrid retrieval behind the ranked-evidence boundary.
It must retain the explicit publication, immutable chunk provenance, context-key
allowlist, answer contract, and separate retrieval strategy identity. Compare both
retrieved and selected evidence against the existing references before comparing
answer quality. Vector storage, embeddings, and hybrid scoring are not part of M1.6.
