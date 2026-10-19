# Local setup

This guide prepares **v0.1.0 — Repository Intelligence Foundation**, an upcoming
release. Use a trusted local machine. Do not expose the API or Vite server publicly.
The [workspace operations guide](architecture/repository-intelligence-workspace.md)
contains detailed troubleshooting; this page covers the shortest complete path.

## Prerequisites

- JDK **21**, Git **2.48+**, and Python **3.9+** on PATH.
- Docker engine with Docker Compose for the pinned PostgreSQL/pgvector service.
- Ollama for live answers and embedding generation.
- Node **24.19.0**, pinned in [frontend/.nvmrc](../frontend/.nvmrc), and npm **11**.
- Network access for initial Maven, npm, container, and model downloads.

The [Maven Wrapper](../.mvn/wrapper/maven-wrapper.properties) downloads Maven 3.9.16.
[compose.yml](../compose.yml) pins PostgreSQL 17 with pgvector by image digest.
No separate vector database is required. Use `nvm use` inside `frontend` if nvm is
installed, or activate the pinned Node version with your existing version manager.

Run commands from the project root unless indicated otherwise. Start only services
you own; do not stop another application to reclaim its port.

## 1. Prepare PostgreSQL and schema

For an **existing** database, load its existing credentials privately in the terminal
that will run the backend. Do not generate replacement credentials for an existing
volume. Compose's `.env` loading does not export variables into the JVM shell.

```sh
: "${EVIDENCE_DB_USER:?Load the existing database user}"
: "${EVIDENCE_DB_PASSWORD:?Load the existing password privately}"
export EVIDENCE_DB_URL="${EVIDENCE_DB_URL:-jdbc:postgresql://localhost:5432/evidence}"
docker compose up -d --wait postgres
docker compose exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d evidence'
```

For a **new, disposable local database only**, the existing
[persistence guide](architecture/evidence-persistence.md#configuration-and-local-postgresql)
shows how to generate a development password without printing it. Retain it privately
for later sessions. Never put real credentials in source files, command examples,
screenshots, or logs. `EVIDENCE_DB_PORT` optionally changes Compose's host port;
update the JDBC URL to match.

If schema preparation is required, deliberately start the write-enabled process:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence
```

Wait for successful Flyway/application startup, then stop **this foreground process**
with Ctrl+C. Do not reset the database. Existing pre-pgvector volumes require the
[preservation procedure](architecture/embedding-indexing.md#postgresql-setup-and-existing-data)
before upgrade; that is not a routine demo-startup step.

## 2. Prepare one explicit local Git publication

A fresh database has **no demo publications**. The four repositories shown in the
screenshots were separately prepared on the maintainer's machine; they are not
cloned, seeded, or indexed by this application at startup.

Use a compatible local checkout you have permission to process. Choose a full
committed SHA deliberately; publication reads committed objects, not worktree edits.
This is a **state-changing CLI operation**, separate from the read-only API.

```sh
export QA_PUBLISH_REPOSITORY='/absolute/path/to/your/local-checkout'
export QA_REPOSITORY_ID='your-repository-id'
export QA_REVISION='<full-committed-Git-SHA>'
git -C "$QA_PUBLISH_REPOSITORY" cat-file -e "$QA_REVISION^{commit}"
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,qa \
  -Dspring-boot.run.arguments='--qa.command=publish --spring.flyway.enabled=false --qa.chunk-max-lines=20 --qa.chunk-max-chunks=10000'
```

The CLI reads these standard Spring environment properties. No source URL is fetched.
With `qa.scope-includes` absent from all configuration sources, scope is unrestricted
within supported formats and ingestion policy. To intentionally bound it, add the
existing comma-separated path-prefix option, for example DDIP's approved scope:

```text
--qa.scope-includes=README.md,docs,ingestion/src/main/java,services/content-api
```

Record the emitted `publicationId`, `snapshot.commitSha`, `snapshot.scope`,
`processingStatus`, and `configuration`. Inspect coverage through the API after
startup below, **before indexing**. Stop on unexpected parsing/resource failures or
identity mismatches. `PARTIAL` can reflect expected unsupported formats or split
syntax regions; it must not be relabeled complete. Identical inputs reuse the same
immutable publication; conflicts fail rather than overwrite existing evidence.

For corpus-specific preparation, consult the [Q&A CLI](architecture/grounded-question-answering.md)
and [controlled evaluation publication instructions](architecture/retrieval-evaluation.md).
The Parcel Quotes fixture is included as files; tests create their own temporary Git
commits. Do not expect those generated publication IDs in a fresh database.

## 3. Models and explicit embedding indexing

If Ollama is not running, start `ollama serve` in another terminal. Initial downloads
are deliberate setup operations, not part of the live demo:

```sh
ollama pull qwen3:4b-instruct
ollama pull nomic-embed-text:v1.5
ollama list
```

Inspect the resolved profile, then record its `profileId` and full `profile.modelDigest`:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,embeddings \
  -Dspring-boot.run.arguments='--embeddings.command=profile --spring.flyway.enabled=false'
```

The model tag alone is insufficient: the immutable profile also binds digest,
768 dimensions, input recipes, options, and metric. Review publication coverage
before authorizing this separate **database-writing** indexing step:

```sh
export EMBEDDINGS_PUBLICATION_ID='<actual-returned-publicationId>'
export EMBEDDINGS_EXPECTED_DIGEST='<actual-full-profile.modelDigest>'
./mvnw spring-boot:run -Dspring-boot.run.profiles=persistence,embeddings \
  -Dspring-boot.run.arguments="--embeddings.command=index --spring.flyway.enabled=false --embeddings.publication-id=$EMBEDDINGS_PUBLICATION_ID --embeddings.expected-digest=$EMBEDDINGS_EXPECTED_DIGEST"
```

Require `status=COMPLETE`, `missingChunks=0`, and `indexedChunks=totalChunks`.
Record inserted/reused chunks, issues, and duration. Complete indexing covers emitted
chunks only. Stop and inspect failures; no automatic retry is needed. After diagnosing
a failure, an explicit same-profile rerun can reuse validated rows and resume missing
work; see [indexing and recovery](architecture/embedding-indexing.md).

## 4. Start the read-only API and frontend

The API may be started after schema/publication preparation and before indexing to
inspect coverage. Index separately only after coverage passes. In the credentials
terminal, select your actual compatible stored profile ID:

```sh
export INTELLIGENCE_API_EMBEDDING_PROFILE_ID='<actual-profileId>'
export INTELLIGENCE_API_BROWSER_ORIGIN=http://127.0.0.1:5173
./mvnw spring-boot:run -Dspring-boot.run.profiles=intelligence-api,embeddings
```

The profile group includes `persistence,qa` but disables Flyway, SQL initialization,
and CLI runners; connections are read-only. It does not prepare missing publications
or indexes. The default answer strategy remains KEYWORD. For keyword-only operation,
omit `embeddings` and unset the optional profile ID; Vector/Hybrid will not be configured.

In the frontend terminal:

```sh
npm ci --prefix frontend
npm run dev --prefix frontend
```

Open **http://127.0.0.1:5173/**. Vite binds to loopback, uses a strict port, and proxies
`/api/v1` to `http://127.0.0.1:8080`. Browser requests are same-origin; the proxy retains
Origin and does not enable wildcard CORS. If choosing port 5174, configure the API
origin as `http://127.0.0.1:5174` **before starting it**, and run:

```sh
npm run dev --prefix frontend -- --port 5174
```

Do not substitute `localhost` for `127.0.0.1` in the browser origin. Configuration
changes do not update an already-running process. Coordinate any owned-process restart.

## 5. Verify readiness without inference

```sh
export WEB=http://127.0.0.1:5173
curl --fail-with-body http://127.0.0.1:8080/api/v1/capabilities
curl --fail-with-body "$WEB/api/v1/capabilities"
curl --fail-with-body "$WEB/api/v1/publications?limit=50"
export PUBLICATION_ID='<actual-returned-publicationId>'
curl --fail-with-body "$WEB/api/v1/publications/$PUBLICATION_ID"
```

Catalog responses contain `items` and nullable `nextAfter`; follow `after` until null
when checking the complete inventory. Details contain `publication`, `coverage`, and
**`artifacts`**, not `artifactCoverage`. Check `coverage.failed`, `failedArtifacts`,
`chunks`, per-artifact limitations, and expected repository/revision/scope.
Capabilities describe configuration, not model availability or per-publication index
completeness. The successful indexing report is the completion evidence.

The local example Nomic profile is
`6360aef03c6233a05a91583849c68ae0d6a97a66b1ef985de22e92876aa2aa0b`;
it is not a default or guaranteed identity on a fresh installation.

## Development checks

Use the [README verification commands](../README.md#development-and-testing).
Frontend tests/build need no database or model. Full Maven verification needs Docker;
its disposable PostgreSQL databases do not use or reset the demo database. Default
`./mvnw spring-boot:run` exposes health without database/model requirements and does
not enable the intelligence API. See [development conventions](development.md) for
CI, failure reports, and the boundary between deterministic tests and live evaluation.
