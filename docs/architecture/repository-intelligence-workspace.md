# Interactive Repository Intelligence Workspace

M1.9b implements a local React presentation layer over the five existing
[M1.9a endpoints](repository-intelligence-api.md). It discovers immutable
publications, asks grounded questions with an explicit retrieval strategy, and
opens verified persisted source excerpts. It adds no retrieval, prompting,
publication mutation, database schema, or model integration logic.

## Architecture and dependencies

[frontend/](../../frontend/) contains React and React DOM as its only runtime
dependencies. TypeScript uses strict checking, including unchecked-index and exact
optional-property checks. Vite serves/builds the application. Vitest, jsdom, and
React Testing Library provide deterministic interaction tests. Dependencies are
pinned in [package.json](../../frontend/package.json) and the npm lockfile; no router,
state library, component framework, Markdown interpreter, or syntax executor is used.

- [api/types.ts](../../frontend/src/api/types.ts) mirrors the actual Java
  [v1 wire records](../../src/main/java/dev/changeassurance/intelligence/RepositoryIntelligenceDtos.java).
  Required arrays remain arrays; nullable profile/invocation/token fields remain
  nullable. Unavailable token counts are not replaced with zero.
- [api/client.ts](../../frontend/src/api/client.ts) owns relative native-fetch calls,
  API version checking, identifier validation, cancellation, and safe problem parsing.
  It sends only `question` and `retrievalStrategy` for inference. It never retries
  inference or imposes a shorter timeout than the server.
- `useCatalog` loads capabilities and bounded publication pages independently.
  `useWorkspace` owns explicit selection, the draft question, answer, and evidence.
  Each request has an AbortController; identity/signal checks discard late results
  even if the transport ignores cancellation. A synchronous pending guard prevents
  duplicate inference. Unmount aborts outstanding requests.
- Cohesive context, question, answer, diagnostics, and evidence components render
  the data. CSS tokens define typography, colors, borders, and spacing. Desktop
  uses three panels, intermediate widths two columns, and narrow screens a stack.

There is no browser storage or conversation persistence. Changing publications
clears the answer and source, cancels pending work, and retains the editable question.
Starting another question clears the previous answer/source. Evidence failures
leave the answer intact and offer a GET retry. Submission failures preserve the
question and require an explicit new submission. Cancellation is not an error.

## Actual API integration

All paths are relative to the browser's origin. Success envelopes identify
`apiVersion: repository-intelligence-v1`.

| Method and route | Interaction and response fields |
| --- | --- |
| `GET /api/v1/capabilities` | Configured strategies, default strategy, advertised UTF-16 question limit. Server settings are not editable. Configuration is not a readiness guarantee. |
| `GET /api/v1/publications?limit=20&after=…` | `items` and nullable `nextAfter`; explicit Load more, no automatic selection. The cursor is passed back unchanged. |
| `GET /api/v1/publications/{publicationId}` | `publication`, `coverage`, artifacts, ingestion coverage, and limitations. |
| `POST /api/v1/publications/{publicationId}/answers` | `{ "question": "Where is shipping cost calculated?", "retrievalStrategy": "VECTOR" }`; answer text/status, claims, citations, uncertainty, and same-execution diagnostics. |
| `GET /api/v1/publications/{publicationId}/evidence/{chunkId}` | `evidence` authoritative provenance, `content` original excerpt, and `structuralContext`. |

An answer's publication identity is at **`publication.publication.publicationId`**:
its outer `publication` is a full publication-detail response. Citation identity
is at `citations[].evidence`; claims reference citations through `evidenceKeys`.
The UI resolves returned identities, never paths/line coordinates. It also checks
that the resolved response matches publication, chunk, snapshot, and Git revision.
The API remains responsible for membership and persisted integrity verification.

## Publication and answer semantics

No entry is chosen automatically and ordering does not imply recency. A publication
is more than a repository/commit pair: entries show include/exclude scope, chunking
limits, processing status, and abbreviated publication ID. Full IDs, commit SHA,
policy, configuration identity, snapshot/derivation, and per-artifact coverage are
available in provenance details. COMPLETE, PARTIAL, and FAILED remain distinct.
PARTIAL is saved coverage information, not an HTTP execution failure.

KEYWORD is the default advertised by the API; VECTOR and HYBRID are enabled only
when configured. A configured strategy can still fail due to an incomplete index
or unavailable model. There is no silent fallback. Questions must be nonblank,
within the server's UTF-16 length limit, valid Unicode, and NUL-free. The server
remains authoritative for validation. Ctrl/Cmd+Enter and the submit button share
the same pending/validation guard.

SUPPORTED, PARTIALLY_SUPPORTED, and INSUFFICIENT_EVIDENCE are application answer
results, never independent truth judgments. Uncertainty, publication limitations,
and context omissions remain visible. Technical details show requested/effective
strategy, ranked references and scores, context selection/omission reasons,
model/prompt metadata, invocation timing/tokens, and the exact statement:

```text
CITATION_IDENTITIES_VALIDATED_SEMANTIC_SUPPORT_NOT_VERIFIED
```

Ranking scores are not confidence percentages. Every returned citation is a button,
including citations not attached to a claim. Ranked evidence is also navigable.
Opening a citation focuses the evidence heading for keyboard/mobile navigation.
The explorer shows original inclusive source coordinates, line-numbered escaped
Java/Markdown, repository/revision, format, citation key, and expandable hashes,
Git blob, parser, chunking, and structural context. It is not a full-file browser.
Long source lines scroll inside the excerpt rather than widening the page.

## Errors and local security

The central parser maps stable `application/problem+json` codes to fixed UI messages.
It never renders arbitrary server `detail`, provider bodies, SQL, or stack traces.
Invalid requests, unknown publications/evidence, incomplete indexes/profile
mismatch, unavailable services, timeouts, invalid model output, and integrity
failures remain operational errors. Unknown codes use a generic message; the safe
code remains visible for diagnostics. They never become INSUFFICIENT_EVIDENCE.

Source, paths, Markdown, structural context, and model output are untrusted text.
React escapes them; there is no `dangerouslySetInnerHTML`, Markdown/HTML execution,
filesystem navigation, or URL derived from source paths. Answers/excerpts are
kept in memory; requests use `cache: no-store` and no credentials. The backend's
no-store responses and integrity checks are preserved through the proxy.

Both processes bind to loopback. The Vite proxy changes Host to the backend's
actual `127.0.0.1:8080`, **retains the browser's Origin**, and adds no CORS grant.
Configure the API with the exact browser origin below. Do not strip Origin, enable
wildcard CORS, bind to all interfaces, or turn off backend Host checks. All local
database publications are visible to the trusted operator. This has no authentication
or RBAC and must not be publicly exposed; localhost is not enterprise authorization.

## Installation and local startup

Use Node 24 LTS (24.15+ within major 24) and npm 11. The checked development/CI
version is pinned in [frontend/.nvmrc](../../frontend/.nvmrc). Java 21, Docker,
the existing PostgreSQL publications/indexes, and Ollama models are needed for
live use. Frontend tests/build need none of those backend services.

From the repository root:

```sh
cd frontend
nvm use # if nvm is installed; otherwise use the Node version in .nvmrc
npm ci
npm run typecheck
npm test
npm run build
cd ..
```

In the shell holding your existing local database credentials:

```sh
: "${EVIDENCE_DB_USER:?Export your existing local database user}"
: "${EVIDENCE_DB_PASSWORD:?Export your existing local database password}"
export EVIDENCE_DB_URL="${EVIDENCE_DB_URL:-jdbc:postgresql://localhost:5432/evidence}"
docker compose up -d postgres
docker compose ps postgres
```

If Ollama is not already running, start it in another terminal:

```sh
ollama serve
```

Check the existing models (no automatic downloads or index creation):

```sh
ollama list
curl --fail-with-body http://127.0.0.1:11434/api/tags
```

The current chat default is `qwen3:4b-instruct`; the existing embedding profile
normally uses `nomic-embed-text:v1.5`. Follow the [embedding guide](embedding-indexing.md)
if preparation is missing. Never substitute a different model for an existing profile.
In the credentials shell, start the read-only API:

```sh
export INTELLIGENCE_API_BROWSER_ORIGIN=http://127.0.0.1:5173
export INTELLIGENCE_API_EMBEDDING_PROFILE_ID=6360aef03c6233a05a91583849c68ae0d6a97a66b1ef985de22e92876aa2aa0b
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=intelligence-api,embeddings
```

The profile ID is an example, not a default. Use the compatible ID already in your
local database. API startup excludes CLI runners and disables Flyway/schema writes;
it does not ingest, index, or prepare missing data. Backend startup/CLI behavior is
unchanged by M1.9b.

In another terminal, from the repository root:

```sh
npm run dev --prefix frontend
```

Open **http://127.0.0.1:5173/** (use this exact origin, not `localhost`). Vite's
strict port setting prevents silently moving to a port the API does not allow.
If that port is occupied, leave the other application alone: restart your API with
`INTELLIGENCE_API_BROWSER_ORIGIN=http://127.0.0.1:5174`, run
`npm run dev --prefix frontend -- --port 5174`, and open that exact URL. Changing
the environment does not reconfigure an already-running API process.

`npm run build --prefix frontend` writes ignored `frontend/dist/`. To inspect
that build locally, stop Vite dev and run `npm run preview --prefix frontend`;
preview uses the same loopback port and proxy. This is a local preview procedure,
not a public deployment configuration. Stop owned foreground processes with Ctrl+C.

## Maintainer live acceptance

This procedure uses existing data and does not guarantee any particular model output.
The known full Parcel Quotes publication is an example, not hardcoded in the UI:

```sh
API=http://127.0.0.1:5173/api/v1
PUBLICATION=40b677d1bb8efeb02506e0de2ef624acb046a26cc2b57a65a8d7d9ab906c702c
curl --fail-with-body "$API/capabilities" | python3 -m json.tool
curl --fail-with-body "$API/publications?limit=20" | python3 -m json.tool
curl --fail-with-body "$API/publications/$PUBLICATION" | python3 -m json.tool
```

1. Open the React application. Confirm nothing is automatically selected. Choose
   the full Parcel Quotes entry by the explicit publication ID, all-path scope,
   and 10,000 chunk limit; expand provenance to confirm the full ID and revision.
   The source-only and one-chunk entries must remain distinguishable, even with
   the same commit. The full publication may still be PARTIAL.
2. Enter **Where is shipping cost calculated?**, choose **Vector**, and submit.
   Observe the pending state and disabled resubmission. Wait for the actual result.
3. Inspect answer status, claims, and uncertainty. If E5 is returned, click it and
   check `src/example/quotes/QuotePolicy.java`, original lines **4–7**. If the model
   returns different citations, inspect those and record the difference; never
   fabricate E5 or replace returned source. Identity checks do not prove the claim.
4. Expand retrieval/execution details and source provenance. Check requested versus
   effective strategy, rank, context omissions, prompt/model, timing, and nullable
   token counts. Change to Keyword, then Hybrid, explicitly asking again each time.
5. Try **Which production Kubernetes namespace is this service deployed to?**
   using Keyword. The controlled corpus does not establish deployment. Inspect the
   actual result; INSUFFICIENT_EVIDENCE must be an answer with uncertainty, not an
   error. Model variability means this question is not a deterministic guarantee.
6. Switch to the source-only or one-chunk publication, including while an answer
   or evidence request is pending. Old results must disappear and never repopulate
   the new selection. The question should remain editable.
7. Exercise keyboard-only selection, radio navigation, Cmd/Ctrl+Enter, citation
   activation, and details expansion. Repeat at a 400px viewport; panels stack,
   controls remain usable, and long code scrolls inside the excerpt.

Check safe malformed-input handling through the same proxy:

```sh
curl -i -H 'Content-Type: application/json' \
  -H 'Origin: http://127.0.0.1:5173' \
  --data '{"question":" ","retrievalStrategy":"VECTOR"}' \
  "$API/publications/$PUBLICATION/answers"
curl -i "$API/publications/not-a-publication"
```

Expect 400 with a fixed problem code/description, no SQL/provider details. To
exercise an operational error in the UI without altering stored data, stop the
API process you own and submit a question; the UI must preserve the question and
show a safe service/proxy failure. Restart the API afterwards. An allowed-origin
misconfiguration also produces a safe ORIGIN_NOT_ALLOWED notice; fix startup
configuration instead of weakening the proxy. Never stop somebody else's process.

For a direct same-proxy inference/evidence check:

```sh
curl --fail-with-body -H 'Content-Type: application/json' \
  -H 'Origin: http://127.0.0.1:5173' \
  --data '{"question":"Where is shipping cost calculated?","retrievalStrategy":"VECTOR"}' \
  "$API/publications/$PUBLICATION/answers" > /tmp/repository-workspace-answer.json
python3 -m json.tool /tmp/repository-workspace-answer.json
CITATION_PATH=$(python3 - <<'PY'
import json, re
answer = json.load(open('/tmp/repository-workspace-answer.json'))
assert answer['citations'], 'No citation returned; inspect the answer status and uncertainty'
citation = next((c for c in answer['citations'] if c['key'] == 'E5'), answer['citations'][0])
e = citation['evidence']
assert all(re.fullmatch('[0-9a-f]{64}', e[k]) for k in ('publicationId', 'chunkId'))
print('/publications/' + e['publicationId'] + '/evidence/' + e['chunkId'])
PY
) && curl --fail-with-body "$API$CITATION_PATH" | python3 -m json.tool
```

Keep live artifacts private and temporary. Do not commit answers/source captures
or treat one successful run as an aggregate quality measurement.

## Verification and remaining scope

From the repository root, the frontend checks match the single existing CI job:

```sh
npm ci --prefix frontend
npm run typecheck --prefix frontend
npm test --prefix frontend
npm run build --prefix frontend
./mvnw clean verify
python3 scripts/check_repository.py
git diff --check
git diff --cached --check
```

Vitest uses typed synthetic API fixtures and mocked fetch, not PostgreSQL/Ollama.
Tests cover discovery/pagination and explicit scopes, capabilities, validation,
submission and pending guards, all answer statuses, claim/citation navigation,
original lines and escaped malicious content, independent safe errors, publication
reset, cancellation/late responses, and keyboard interactions. Java tests remain
unchanged and run the existing backend contracts and Testcontainers regressions.
Build/type checks and DOM tests do not replace responsive browser review or live
acceptance. Hosted CI must be checked on the revision under review.

No authentication, ingestion UI, full-file browsing, persisted conversation history,
streaming, evaluation dashboard, impact analysis, agent orchestration, or public
hosting is included. The frontend trusts the versioned local API's successful
payload schema; TypeScript and version/identity checks are not a general runtime
schema validator. No new backend or model behavior is introduced.
