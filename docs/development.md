# Development conventions

These conventions guide incremental work on the existing application. See the
[architecture overview](architecture/overview.md), [ADRs](adr/README.md), and
[roadmap](roadmap.md) for decisions and milestone scope. Build and CI commands are maintained in the [README](../README.md);
first-time local preparation is in [Getting started](getting-started.md).

## Java and Spring Boot

Use Java 21 and the existing `dev.changeassurance` namespace. Keep the application
entry point at that root so Spring Boot can discover components below it. Organize
packages around actual domain capabilities when they arrive; do not add empty
packages for planned ingestion, retrieval, agents, or evaluation. Prefer established
Spring Boot conventions and small, explicit implementations over speculative
interfaces, base classes, and modules.

Follow `.editorconfig` and `.gitattributes`: four spaces for Java, two for YAML/XML
and Markdown, and normalized text in Git. The Unix wrapper uses LF; the Windows
wrapper uses CRLF in its working tree. Keep generated outputs and IDE metadata
out of Git, and preserve the Unix wrapper's executable mode.

## Dependencies and configuration

Let the Spring Boot parent manage dependency and plugin versions. Add a dependency
only for a demonstrated milestone need; prefer managed versions and document any
necessary override. Keep test-only tools in test scope. Review updates and run
verification as described in the README; do not add infrastructure for anticipated
future features.

Prefer Spring Boot externalized configuration for environment-specific settings.
Keep committed defaults minimal and runnable for the current milestone. Never
commit credentials, tokens, private keys, or secret-bearing local configuration.
Examples may use clearly fake values; ignore rules are not a secret scanner.
Do not introduce custom profiles or configuration abstractions without a use case.

## Testing

Test observable behavior with deterministic inputs. Use unit tests for isolated
logic and integration tests when component interactions need verification. Avoid
mocks that merely repeat implementation details. Keep the foundation tests free
of external services and credentials; use random ports for HTTP integration tests.

The existing context and health tests protect startup and HTTP health behavior.
They do not establish retrieval or analysis quality. New capabilities need focused
tests for their success, failure, and unsupported cases. The
[evaluation guide](../evaluation/README.md) describes the controlled fixtures,
JSON scenarios, and Python standard-library validator regression tests run by the
existing repository check. Keep fixtures outside Maven source/resource roots.
Ingestion integration tests create and commit only temporary local repositories,
cleaned up by JUnit. They require Git 2.48+ and no network or external services;
see the [ingestion guide](architecture/git-snapshot-ingestion.md) for policies and
coverage. Production ingestion executes only read-only plumbing commands.
Processing tests also verify Java 21 syntax, deterministic chunks, historical commit
provenance, and mapping of the unchanged evaluation ground truth through chunks.
See the [processing guide](architecture/artifact-processing.md) for parser versions,
configuration, and limitations. For future Repository Intelligence work, extend
versioned evaluation scenarios in addition to software tests, following
[ADR-0003](adr/0003-evidence-provenance-and-reproducible-evaluation.md).

## Database development and verification

The explicit `persistence` profile enables PostgreSQL and Flyway; default startup
and `./mvnw test` stay database-independent. `./mvnw clean verify` additionally runs
mandatory Testcontainers PostgreSQL tests through Maven Failsafe. Docker must be
running; tests never silently skip for an unavailable daemon. Reports are in
`target/failsafe-reports/`. See the [persistence guide](architecture/evidence-persistence.md)
for environment variables, Compose, migrations, inspection, and local reset.
Add a new versioned SQL migration for schema changes; never edit an applied migration.
M1.5 retrieval tests run in the same mandatory Failsafe lifecycle, including the
[15-query baseline](../evaluation/retrieval/README.md). Run only that evaluation with
`./mvnw -Dit.test=KeywordEvaluationIT test-compile failsafe:integration-test failsafe:verify`;
inspect `target/keyword-baseline.json`. See the [retrieval guide](architecture/keyword-retrieval.md)
for query limits, ranking, integrity costs, and M1.6 integration.

## Local grounded Q&A

The [Q&A guide](architecture/grounded-question-answering.md) documents the
`persistence,qa` CLI profiles, local Ollama setup, explicit publication selection,
context and citation policy, and failure behavior. The local model is an opt-in
dependency; default startup and mandatory CI tests use no Ollama server. Keep
deterministic citation checks separate from human review of semantic support.
The [evaluation definition](../evaluation/qa/README.md) preserves the original
Parcel Quotes ground truth and records how live runs should be assessed.

## Local embedding indexing

M1.7a adds the opt-in `persistence,embeddings` CLI and a derived pgvector index.
Follow the [indexing guide](architecture/embedding-indexing.md) before upgrading an
existing local database: Compose uses a new volume, and existing publications need
an explicit export/import. Never reuse the Alpine data directory directly with the
Debian image. Model downloads and indexing are explicit; mandatory tests use no
Ollama. M1.7b adds a separate [vector retrieval CLI](architecture/vector-retrieval.md)
using the same opt-in profiles and an explicit stored profile ID. Its deterministic
768-dimensional ranking tests run in Failsafe. Live retrieval acceptance is separate
from CI. M1.7c adds [hybrid retrieval and Q&A strategy selection](architecture/hybrid-retrieval.md).
Keyword remains the default. Opting into VECTOR/HYBRID requires the embeddings
profile and an explicit compatible stored profile ID; do not index automatically
or substitute keyword retrieval after a failure.

## Local Repository Intelligence API

The [Repository Intelligence API guide](architecture/repository-intelligence-api.md)
documents the separate `intelligence-api` HTTP profile and maintainer smoke tests.
That profile excludes CLI runners, disables schema initialization, and uses
loopback HTTP and read-only connections. Prepare publications/indexes separately.
Keep HTTP DTOs explicit, preserve uncertainty/provenance, and resolve evidence only
within its publication. The React UI remains a presentation layer.
API tests use deterministic gateways and disposable PostgreSQL, not live Ollama.

## Local React workspace

The [workspace guide](architecture/repository-intelligence-workspace.md) documents
Node 24/npm 11, installation, loopback Vite proxy, exact API browser-origin setup,
tests, and live acceptance. Keep the explicit TypeScript wire types aligned with
the Java HTTP DTOs. Use native fetch, local state, and escaped text; do not add
browser persistence, duplicate retrieval logic, or interpret source as markup.
Abort requests on selection changes and guard against late responses. Model POSTs
require deliberate submission, with no automatic retries. Tests use synthetic typed
fixtures; keep raw live source/answer bundles out of version control. Release
documentation may include explicitly reviewed, sanitized screenshots and selected
result extracts, with provenance and limitations, when authorized by the maintainer.
The [v0.1.0 capture inventory](assets/v0.1.0/README.md) records that limited exception.

Run `npm ci --prefix frontend`, `npm run typecheck --prefix frontend`,
`npm test --prefix frontend`, and `npm run build --prefix frontend` in addition to
the existing checks. Keep the lockfile, but ignore dependencies, build output, and
coverage. Review narrow and desktop layouts and keyboard navigation separately
from DOM tests. Change the exact API allowed origin when changing the Vite port;
never strip browser Origin or weaken the backend's local access filter.

## Retrieval evaluation

Follow the [M1.8a guide](architecture/retrieval-evaluation.md) to execute the pinned
15-case suite against existing publications and regrade saved artifacts offline.
Keep TEST_DOUBLE verification separate from LIVE model observations. Retain needed
local artifacts before `mvnw clean`; full comparison needs explicit full, scoped,
and chunk-limited publication IDs. No publication or indexing is automatic.

## Grounded-answer evaluation

Follow the [M1.8b guide](architecture/grounded-answer-evaluation.md) for same-execution
Q&A captures, independent context/citation checks, and offline human reviews. Use
`evaluation.command=qa-run` explicitly; normal tests use model doubles. Citation-valid
answers receive no automatic semantic credit. Preserve raw captures privately,
leave raw response capture disabled routinely, and retain NOT_REVIEWED for any
human dimension not inspected. Do not edit historical definitions or observations
to improve results.

## Offline evaluation reports

Use the [M1.8c reporting guide](architecture/evaluation-reporting.md) to combine
explicit saved captures and selected grading observations without database or model
profiles. Preserve artifacts before `mvnw clean`. Never choose the newest review
automatically or pool unmatched populations. Keep private reviews/reports out of Git.

## Documentation and decisions

Update implementation status with relevant changes. Distinguish implemented,
planned, and locally verified behavior from hosted verification. Keep substantial
guidance in one place and link to it. Use inline relative file links so the existing
offline checker can validate them; its scope and limitations are in the README.

Record consequential architectural choices as ADRs with context, alternatives,
consequences, and tradeoffs. Follow the [ADR conventions](adr/README.md); an accepted
direction does not mean a planned capability already exists. Preserve historical
rationale when replacing a decision.

## Issues, branches, and pull requests

Keep work tied to a focused issue and milestone. Use a descriptive branch and a
reviewable PR; avoid unrelated cleanup. Preserve other work already in the checkout.
Before publication, run from the repository root:

```sh
python3 scripts/check_repository.py
./mvnw clean verify
git diff --check
```

Review the complete diff, including staged changes and new files. For staged work,
also run `git diff --cached --check`. Whitespace checks inspect pending changes;
they are not evidence that an already-clean checkout has validated committed
content. Report what ran, failures or limitations, and changes in observable
behavior. CI runs the documented quality gates on PRs to `main` and pushes to
`main`; inspect its result before accepting the change. The maintainer handles
review, publication, and merging.

## AI-assisted development

The README's OpenAI ChatGPT and Codex disclosures remain authoritative. AI may
assist with research, design, implementation, tests, and review. The maintainer
owns engineering decisions, validates generated work, reviews scope and evidence,
and controls publication. AI output is subject to the same testing and review as
other contributions and is not proof that a feature works or a change is safe.
