# Repository Intelligence REST API

M1.9a implements a local, read-only HTTP adapter over the existing publication,
retrieval, and grounded Q&A services. M1.9b's React UI remains planned. This is a
trusted local demonstration, not an authenticated or publicly deployable API.

## Architecture and boundaries

The `intelligence` package contains explicit v1 wire records, the controller,
service composition, safe errors, and the local HTTP boundary. The persistence
package owns the bounded catalog SQL. No schema, dependency, retrieval algorithm,
prompt, model default, or evaluation definition is added or changed.

Each configured strategy binds an immutable `GroundedQuestionAnsweringService`
through `EvidenceRetrievers.select`. Answering calls `answerWithRetrieval` once;
the response maps that invocation's answer and retrieval trace. There is no second
retrieval for diagnostics, fallback, automatic indexing, or chat retry. The existing
context builder and proposed-answer validator remain authoritative. Empty retrieval
does not call the chat model. HYBRID retains its existing two retrieval branches.
The HTTP operation has no enclosing database transaction: embedding/chat inference
remains outside the existing short read transactions.

Catalog reads use bounded, parameterized publication-ID queries and existing
`PublicationIntegrity.inspect` checks in a repeatable-read transaction. Details
load metadata without source bodies. Evidence inspection reuses
`EvidencePersistenceService.findPublication`, selects only a member of that
publication, and calls `PublicationIntegrity.verifyChunk`. It currently loads the
whole bounded publication internally; ingestion limits source bytes to 16 MiB per
publication, with additional chunk/metadata memory. This is acceptable for the
initial local use; no cache or optimized chunk query is introduced.

Evidence is never resolved through a browser-supplied path, line range, clone
directory, branch, or global chunk lookup. The API returns stored original excerpts,
including original newlines. Publication integrity and chunk hashing retain the
existing trust in privileged database administrators; they are not a new signed
storage or semantic-validation system.

## Startup and configuration

`intelligence-api` is a profile group that includes `persistence,qa`. Add
`embeddings` and an explicit existing profile ID to configure VECTOR/HYBRID.
The API reuses the existing chat/embedding gateway beans and their settings.
Neither gateway downloads models or calls inference at startup. Resolving an
explicit embedding profile reads the database at startup, without model calls.

The API defaults to `127.0.0.1:8080`, servlet mode, disabled Flyway, disabled SQL
initialization, read-only Hikari connections, and no forwarded-header processing.
An early bean-factory guard rejects overrides that enable schema initialization,
disable read-only connections, bind outside literal `127.0.0.1`/`::1`, enable
forwarded headers, or disable the servlet server. These checks precede datasource
initializers and command execution. Prepare the schema separately through the
existing [persistence workflow](evidence-persistence.md).

All CLI runners and the embedding indexing bean are excluded in API mode. Any
presence of `qa.command`, `embeddings.command`, `vector.command`, or
`evaluation.command` rejects startup, even with an empty value. Existing CLI
profiles retain their non-web mode, commands, outputs, and migration behavior.
Profile order does not change these boundaries. Default application startup still
needs no database or model and exposes no Repository Intelligence routes.

| Setting | API behavior |
| --- | --- |
| `intelligence.api.embedding-profile-id` / `INTELLIGENCE_API_EMBEDDING_PROFILE_ID` | Empty by default: KEYWORD only. Otherwise a full stored profile ID and the `embeddings` profile are required. |
| `intelligence.api.browser-origin` / `INTELLIGENCE_API_BROWSER_ORIGIN` | Empty by default. Optional one exact loopback HTTP origin for a future Vite proxy, e.g. `http://127.0.0.1:5173`. No wildcard or remote origin. |
| `qa.top-k`, `qa.max-evidence`, `qa.max-context-bytes`, `qa.max-evidence-bytes` | Server-only defaults: 12, 6, 6000, 3000. Validated by existing `AnsweringConfiguration`. |
| `qa.model`, `qa.temperature`, `qa.context-tokens`, `qa.max-output-tokens`, `qa.timeout-seconds` | Existing defaults and environment aliases unchanged; effective values appear in capabilities/answers. |
| `qa.base-url`, embedding gateway settings | Existing loopback-only provider rules unchanged; URLs are never request fields or response fields. |
| `qa.retrieval-strategy`, `qa.embedding-profile-id` | Retain CLI meaning. API selection uses the request and the separate API embedding-profile setting. API default is always KEYWORD. |
| `qa.log-malformed-model-response` | Existing explicit debug option; keep false routinely because raw responses can contain private source. |

## v1 wire contract

Base path: `/api/v1`. Successful responses use `application/json` and contain
`apiVersion: "repository-intelligence-v1"`. Errors use `application/problem+json`.
Responses use `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.

All response fields listed below are present. Arrays are non-null, including when
empty. The only nullable fields are `nextAfter`, `embeddingProfileId`, `invocation`,
and unavailable invocation metadata described below. Source/processing fields
describe current Java/Markdown publications; they do not claim runtime behavior.
Clients should tolerate additional response fields within v1.

Publication/chunk IDs and cursors are full lowercase 64-character hexadecimal
identities. Commits and Git blob IDs preserve full Git SHA-1 or SHA-256 values.
Source lines are inclusive and one-based. Scores rank evidence; they are not
confidence probabilities and are not comparable across strategies.

### GET /capabilities

No parameters. Returns configuration, not a database/index/model readiness check.

```json
{
  "apiVersion": "repository-intelligence-v1",
  "configuredRetrievalStrategies": ["KEYWORD"],
  "defaultRetrievalStrategy": "KEYWORD",
  "embeddingProfileId": null,
  "questionLengthLimit": 512,
  "answeringConfiguration": {
    "topK": 12, "maxEvidence": 6, "maxContextBytes": 6000, "maxEvidenceBytes": 3000
  },
  "generationSettings": {
    "model": "qwen3:4b-instruct", "temperature": 0.0,
    "contextTokens": 8192, "maxOutputTokens": 768, "timeoutSeconds": 120
  },
  "availability": "CONFIGURED_NOT_RUNTIME_READINESS"
}
```

With a configured stored embedding profile, the strategy array contains KEYWORD,
VECTOR, HYBRID in that order. A selected publication may still have an incomplete
index, or the installed model may no longer match. Those fail during answering.

### GET /publications

| Query field | Required | Contract |
| --- | --- | --- |
| `repositoryId` | No | Exact nonblank valid-Unicode repository identity without NUL; parameterized equality filter. |
| `limit` | No | Integer 1..50; omitted means 20. Empty/invalid values are rejected. |
| `after` | No | Full publication ID; results have IDs strictly greater in C collation. Cursor need not identify an existing row. Empty/malformed values are rejected. |

Returns `apiVersion`, `items` (publication references), and nullable `nextAfter`.
Use a non-null `nextAfter` with the same filter/limit for the next page. Empty
databases and unmatched filters return `200` with `items: []`, `nextAfter: null`.
Pages are individually consistent; concurrent CLI publication between pages does
not create a cross-request snapshot. Restart listing to see newly inserted IDs
before a cursor. No newest/HEAD selection or total count is inferred.

Each publication reference contains these required fields:

- `publicationId`, `snapshotId`, `derivationId`, `repositoryId`, `commitSha`.
- `scope: {includes: string[], excludes: string[]}`, `policyVersion`.
- `processingConfiguration: {identity: string, maxLines: integer, maxChunks: integer}`.
- `processingStatus`: COMPLETE, PARTIAL, or FAILED.

Illustrative response shape (angle-bracket identities are placeholders):

```json
{
  "apiVersion": "repository-intelligence-v1",
  "items": [{
    "publicationId": "<publication-id>", "snapshotId": "<snapshot-id>",
    "derivationId": "<derivation-id>", "repositoryId": "parcel-quotes-v1",
    "commitSha": "<full-commit-sha>", "scope": {"includes": [], "excludes": []},
    "policyVersion": "git-snapshot-v1",
    "processingConfiguration": {
      "identity": "source-lines-v1:lines=80:chunks=10000", "maxLines": 80, "maxChunks": 10000
    },
    "processingStatus": "COMPLETE"
  }],
  "nextAfter": null
}
```

Group these references by repository in the UI. Selection is always an explicit
publication, preserving commit, scope, and processing configuration. There is no
repository registry, latest publication, timestamp ordering, or automatic HEAD.

### GET /publications/{publicationId}

Returns a publication detail object:

- `apiVersion`, `publication` (the complete reference described above).
- `coverage`: integer `discovered`, `ingested`, `excluded`, `unsupported`, `failed`
  ingestion counts, plus `artifacts`, `parsed`, `completeArtifacts`,
  `partialArtifacts`, `failedArtifacts`, `chunks` processing counts.
- `ingestionCoverage`: entries with `path`, `disposition`, `reason`, `detail`.
- `artifacts`: entries with `path`, `parser`, `status`, `parsed` (boolean),
  `chunkCount`, `limitations` (objects with `reason`, `detail`).
- `ingestionLimitations`, `processingLimitations`: arrays of strings.

Example excerpt, not a live result:

```json
{
  "coverage": {
    "discovered": 2, "ingested": 2, "excluded": 0, "unsupported": 0, "failed": 0,
    "artifacts": 2, "parsed": 2, "completeArtifacts": 1,
    "partialArtifacts": 1, "failedArtifacts": 0, "chunks": 1
  }
}
```

PARTIAL/FAILED are valid published processing states and remain `200`. Unknown
publication is `404`; malformed identity is `400`. Complete embedding coverage
does not erase incomplete source processing coverage.

### POST /publications/{publicationId}/answers

Requires `Content-Type: application/json`; body limit is 8192 bytes, including
requests without Content-Length. No answer is persisted, so success is `200`,
not `201`. The operation is synchronous and can take the configured provider
timeouts plus bounded database work.

```json
{"question":"Where is shipping cost calculated?","retrievalStrategy":"VECTOR"}
```

`question` is required, non-null, nonblank, at most 512 UTF-16 code units, valid
Unicode, and contains no NUL. `retrievalStrategy` is optional/nullable: omitted or
null means KEYWORD; otherwise it must be exactly KEYWORD, VECTOR, or HYBRID.
Unknown fields, scalar coercion, duplicate fields, trailing JSON, and malformed
JSON are rejected. Budgets, prompts, provider settings, paths, and coordinates
are never client-controlled fields.

The response includes:

| Fields | Meaning |
| --- | --- |
| `apiVersion`, `answerVersion` | Wire and existing answer-service versions. |
| `publication` | Complete publication detail object above, including coverage/limitations from this invocation. Its `publication` member is the compact reference. |
| `question`, `answer`, `status` | Original question, answer text, and SUPPORTED/PARTIALLY_SUPPORTED/INSUFFICIENT_EVIDENCE. |
| `claims` | `{text, evidenceKeys: string[]}`; keys refer to this answer's citations. |
| `citations` | `{key, evidence}`; evidence is the durable reference described below, without excerpt text. |
| `uncertainties` | Original explicit uncertainties, including coverage/context omissions added by the service. |
| `requestedRetrievalStrategy`, `embeddingProfileId`, `retrievalStrategy` | Requested enum, bound profile (null for KEYWORD), and actual versioned strategy identity. |
| `retrievedEvidence` | Same-execution `{rank, score, evidence}` references, without source bodies. Retrieved does not mean selected or cited. |
| `retrievalLimitations` | Existing strategy/coverage caveats. |
| `contextSelection` | `policy`, `selected: [{key, rank, chunkId}]`, `omissions: [{rank, chunkId, reason}]`, `contextBytes`, `limitations`. |
| `configuration` | Effective topK/evidence/context budgets with the same shape as capabilities. |
| `promptVersion`, `promptHash`, `schemaHash`, `generationSettings` | Existing reproducibility metadata; no system-prompt text or provider URL. |
| `invocation` | Null when no chat call occurred; otherwise `provider`, `model`, `durationMillis`, nullable `inputTokens`, `outputTokens`, `finishReason`, `returnedModel`, `connectTimeoutSeconds`, `thinkingEnabled`, `keepAlive`. Unavailable usage remains null. |
| `validation`, `durationMillis` | Citation-validation statement and total Q&A duration including retrieval. |

Illustrative answer excerpt, not a claimed live response:

```json
{
  "question": "Where is shipping cost calculated?",
  "status": "INSUFFICIENT_EVIDENCE",
  "answer": "No matching evidence was retrieved for this question.",
  "claims": [], "citations": [],
  "uncertainties": ["No keyword matches were retrieved. This does not prove feature absence."],
  "requestedRetrievalStrategy": "KEYWORD", "embeddingProfileId": null,
  "invocation": null,
  "validation": "CITATION_IDENTITIES_VALIDATED_SEMANTIC_SUPPORT_NOT_VERIFIED"
}
```

All three statuses are successful answer results, not independent verification of
factual correctness. Operational failures are non-2xx problems, never converted to
INSUFFICIENT_EVIDENCE. Citation validity establishes identity, not semantic support.

### GET /publications/{publicationId}/evidence/{chunkId}

Both IDs must be well formed. Unknown publication or nonmember chunk returns 404,
including chunks in another publication sharing the same snapshot. Corrupt evidence
fails closed with 500. No path/line selector is supported.

Returns `apiVersion`, `evidence`, `content` (original excerpt), and
`structuralContext` (string array). The required `evidence` reference fields are:
`publicationId`, `snapshotId`, `chunkId`, `repositoryId`, `commitSha`, `path`,
`startLine`, `endLine`, `artifactType`, `sourceFormat`, `gitBlobId`, `sourceHash`,
`contentHash`, `parser`, `chunkingConfiguration`.

Illustrative response excerpt:

```json
{
  "apiVersion": "repository-intelligence-v1",
  "content": "    return shippingCost;\n",
  "structuralContext": ["method shippingCost"]
}
```

The complete `evidence` member accompanies that text. Its coordinates come only
from persistence. Keys such as E1 are local to an answer; use the returned
publication/chunk pair for navigation. Resolution does not prove that a chunk was
cited by a particular historical answer, nor that it supports a claim.

## Errors

Problems have exactly `type` (`about:blank`), HTTP `title`, numeric `status`, stable
`code`, and a fixed safe `detail`. Exception messages, SQL, stack traces, provider
bodies, and filesystem paths do not enter problem responses.

```json
{
  "type": "about:blank", "title": "Bad Request", "status": 400,
  "code": "INVALID_REQUEST", "detail": "The request is invalid."
}
```

| HTTP | Codes |
| --- | --- |
| 400 | `INVALID_REQUEST` |
| 403 | `LOCAL_ACCESS_REQUIRED`, `ORIGIN_NOT_ALLOWED` |
| 404 | `UNKNOWN_PUBLICATION`, `UNKNOWN_EVIDENCE`, `NOT_FOUND` |
| 405 / 406 | `METHOD_NOT_ALLOWED` / `NOT_ACCEPTABLE` |
| 409 | `EMBEDDING_PROFILE_MISMATCH`, `EMBEDDING_INDEX_INCOMPLETE` |
| 413 / 415 | `REQUEST_TOO_LARGE` / `UNSUPPORTED_MEDIA_TYPE` |
| 422 | `CONTEXT_BUDGET_FAILURE`, `EMBEDDING_INPUT_REJECTED` |
| 502 | `MALFORMED_MODEL_OUTPUT`, `INVALID_CITATION`, `EMBEDDING_INVALID_RESPONSE`, `EMBEDDING_INVALID_VECTOR` |
| 503 | `MODEL_UNAVAILABLE`, `EMBEDDING_MODEL_UNAVAILABLE`, `RETRIEVAL_FAILURE`, `RETRIEVAL_NOT_CONFIGURED` |
| 504 | `MODEL_TIMEOUT`, `EMBEDDING_MODEL_TIMEOUT` |
| 500 | `PUBLICATION_INTEGRITY_FAILURE`, `EMBEDDING_INDEX_INTEGRITY_FAILURE`, `INTERNAL_ERROR` |

Other framework transport errors retain their HTTP status with `HTTP_ERROR` and
a safe description. Malformed HTTP rejected by the servlet container before
application filters (for example encoded slash traversal) may use the container's
400 response instead of this JSON envelope; it never reaches evidence resolution.

## Local security and the planned React UI

All publications in the configured local database are visible to the trusted
operator. There is no authentication, RBAC, per-repository access policy, or public
exposure claim. Loopback binding is not a substitute for authorization. A local
process can call the API; repository IDs and hashes are not credentials.

Host must be literal localhost/127.0.0.1/[::1] at the server's actual port. Browser
Origin must exactly match the request's HTTP origin or the explicitly configured
loopback browser origin. Cross-site Fetch Metadata requests are rejected, and
forwarded host headers are ignored. No CORS grants are emitted. Requests without
Origin, such as curl, remain supported. This also applies to health requests in
API mode. Do not enable a separate remotely exposed management server.

The API exposes no ingestion, indexing, publication mutation, evaluation-file
writing, or filesystem endpoints. Database connections default read-only; this
application boundary is not a replacement for database grants. Privileged database
operators remain trusted, as in the existing persistence architecture.

Treat all source, paths, Markdown, structural context, and model text as untrusted
data. M1.9b should use escaped React text and `<pre>` excerpts, not executable HTML
or unsanitized Markdown. Use relative `/api/v1` requests through a loopback Vite
proxy and set the optional browser origin to that proxy's exact origin. Do not
forget the proxy's `changeOrigin: true` setting: Host must identify the backend
port, while Origin identifies the browser's frontend origin. Do not
call models, rank retrieval, resolve paths, or reconstruct provenance in React.
Discard stale responses when selection changes; avoid automatic answer retries.
No frontend, persisted conversations, streaming, or full-file browser is included.

## Maintainer live smoke test

These commands are a procedure, not evidence of live results. They assume the
existing schema, Parcel Quotes publication, and compatible embedding index have
already been prepared using the [Q&A](grounded-question-answering.md) and
[embedding](embedding-indexing.md) CLI workflows. Missing data is not created by
API startup. The IDs below are examples, never defaults; replace them if needed.

In a shell with your existing local database credentials already exported:

```sh
: "${EVIDENCE_DB_USER:?Export the existing local database user}"
: "${EVIDENCE_DB_PASSWORD:?Export the existing local database password}"
export EVIDENCE_DB_URL="${EVIDENCE_DB_URL:-jdbc:postgresql://localhost:5432/evidence}"
docker compose up -d postgres
docker compose ps postgres
```

If Ollama is not already running, start it in another terminal:

```sh
ollama serve
```

Check the existing models; no model pull is performed by the API:

```sh
curl --fail-with-body http://127.0.0.1:11434/api/tags
ollama list
```

The existing chat default is `qwen3:4b-instruct`; embeddings use the model/options
matching the stored profile, normally `nomic-embed-text:v1.5`. If setup is missing,
follow the linked CLI guides explicitly. Replacing an embedding model can invalidate
the example profile; never silently substitute it.

Start the API in the credentials shell, keeping this terminal open:

```sh
export INTELLIGENCE_API_EMBEDDING_PROFILE_ID=6360aef03c6233a05a91583849c68ae0d6a97a66b1ef985de22e92876aa2aa0b
./mvnw spring-boot:run -Dspring-boot.run.profiles=intelligence-api,embeddings
```

For KEYWORD-only startup, omit `embeddings` and clear the API profile setting:

```sh
INTELLIGENCE_API_EMBEDDING_PROFILE_ID= ./mvnw spring-boot:run -Dspring-boot.run.profiles=intelligence-api
```

In a separate request terminal, create a private directory for local response
artifacts, list publications, and explicitly inspect the example selection:

```sh
umask 077
SMOKE_DIR=$(mktemp -d /tmp/repository-intelligence-smoke.XXXXXX)
export SMOKE_DIR
API=http://127.0.0.1:8080/api/v1
PUBLICATION=40b677d1bb8efeb02506e0de2ef624acb046a26cc2b57a65a8d7d9ab906c702c
curl --fail-with-body "$API/capabilities" | python3 -m json.tool
curl --fail-with-body "$API/publications?limit=20" | python3 -m json.tool
curl --fail-with-body "$API/publications/$PUBLICATION" | python3 -m json.tool
```

Run one request per strategy, preserving HTTP statuses and responses. These commands
do not retry, reindex, or claim an answer is correct:

```sh
curl --fail-with-body -sS -w 'KEYWORD HTTP %{http_code}\n' \
  -H 'Content-Type: application/json' \
  -d '{"question":"Where is shipping cost calculated?","retrievalStrategy":"KEYWORD"}' \
  "$API/publications/$PUBLICATION/answers" -o "$SMOKE_DIR/keyword.json"
curl --fail-with-body -sS -w 'VECTOR HTTP %{http_code}\n' \
  -H 'Content-Type: application/json' \
  -d '{"question":"Where is shipping cost calculated?","retrievalStrategy":"VECTOR"}' \
  "$API/publications/$PUBLICATION/answers" -o "$SMOKE_DIR/vector.json"
curl --fail-with-body -sS -w 'HYBRID HTTP %{http_code}\n' \
  -H 'Content-Type: application/json' \
  -d '{"question":"Where is shipping cost calculated?","retrievalStrategy":"HYBRID"}' \
  "$API/publications/$PUBLICATION/answers" -o "$SMOKE_DIR/hybrid.json"
python3 - <<'PY'
import json, os, pathlib
for strategy in ('keyword', 'vector', 'hybrid'):
    response = json.loads((pathlib.Path(os.environ['SMOKE_DIR']) / (strategy + '.json')).read_text())
    print(strategy, json.dumps({key: response.get(key) for key in
        ('status', 'code', 'uncertainties', 'validation', 'retrievalStrategy', 'contextSelection')}, indent=2))
PY
```

Resolve an actual returned keyword citation. The script stops if the answer has
no citation; do not invent one. Inspect the answer/uncertainty or use another
successful saved strategy response deliberately:

```sh
CITATION_PATH=$(python3 - <<'PY'
import json, os, pathlib, re
answer = json.loads((pathlib.Path(os.environ['SMOKE_DIR']) / 'keyword.json').read_text())
if not answer.get('citations'):
    raise SystemExit('No citation returned; inspect status, code, and uncertainty first.')
evidence = answer['citations'][0]['evidence']
publication, chunk = evidence['publicationId'], evidence['chunkId']
if not all(re.fullmatch('[0-9a-f]{64}', value) for value in (publication, chunk)):
    raise SystemExit('Unexpected evidence identifiers.')
print('/publications/' + publication + '/evidence/' + chunk)
PY
) && curl --fail-with-body "$API$CITATION_PATH" | python3 -m json.tool
```

Check malformed input and browser-origin handling (expect 400, 400, 403):

```sh
curl -i "$API/publications/not-a-publication-id"
curl -i -H 'Content-Type: application/json' \
  -d '{"question":"pricing","topK":1}' "$API/publications/$PUBLICATION/answers"
curl -i -H 'Origin: https://unexpected.example' "$API/capabilities"
```

Record observed statuses, strategy/profile, source revision, uncertainty, and
citation identity separately from human review of semantic correctness. Keep
responses private: they can contain repository content. No live API acceptance
or semantic-quality result is claimed by the deterministic test suite.

## Verification

Tests cover strict HTTP contracts, local origin/host and size boundaries, safe
problem responses, startup isolation/profile order, deterministic selection,
original source coordinates, cross-publication membership, corruption, all three
strategies, concurrency, and unchanged database contents. PostgreSQL tests prepare
their schema/indexes separately and use deterministic model doubles, never Ollama.

```sh
./mvnw -Dtest='RepositoryIntelligence*Tests' -Dit.test=RepositoryIntelligenceApiIT verify
./mvnw clean verify
python3 scripts/check_repository.py
git diff --check
git diff --cached --check
```

Full verification requires Docker and preserves the existing retrieval, Q&A, CLI,
and evaluation regressions. Local success does not establish hosted CI or live
model quality. Full-publication evidence reads and metadata integrity scans favor
correctness at current local limits over throughput; no load or public-service
capacity claim is made.
