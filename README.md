# Agentic Software Change Assurance Platform

An applied AI engineering and backend/platform project to produce evidence-backed
change-risk reports from software changes and their surrounding system context.

> Is this software change safe, what could it break, how do we know, and what must
> be verified before release?

## Problem and value

Change review requires connecting evidence scattered across code, tests, API
contracts, database migrations, event schemas, architecture decisions, incidents,
and service dependencies. A passing test suite alone does not establish which
consumers or operational assumptions a change might affect.

The platform aims to help engineers and reviewers identify affected components,
explain compatibility risks, and prioritize release verification. Its intended
output is a structured report with cited evidence, uncertainty, and required
checks. It supports human release decisions; it does not certify a change as safe.
Repository Intelligence is the first independently usable capability planned for
this vision: developers and non-technical stakeholders will ask natural-language
questions about existing repositories and receive evidence-backed, cited answers.

## Current implementation status

**M0 — Project Foundation: Complete. M1 — Repository Intelligence MVP: Active.**
M1.1 established [conceptual contracts](docs/architecture/repository-intelligence.md)
and a [controlled evaluation foundation](evaluation/README.md). M1.2 implements
[read-only Git snapshot ingestion](docs/architecture/git-snapshot-ingestion.md):
explicit local repositories/revisions, Java/Markdown artifact discovery, source
provenance and integrity, and coverage diagnostics through a reusable Java component.
M1.3 adds [Java/Markdown parsing and deterministic evidence chunking](docs/architecture/artifact-processing.md),
preserving original source lines, hashes, context, and ingestion coverage.
M1.4 adds [PostgreSQL evidence persistence](docs/architecture/evidence-persistence.md),
with atomic publications, preserved processing coverage, and exact-identity readback.
M1.5 adds [publication-scoped PostgreSQL keyword retrieval](docs/architecture/keyword-retrieval.md),
deterministic ranking, read-path integrity checks, and a [measured retrieval baseline](evaluation/retrieval/README.md).
M1.6 adds [evidence-grounded Q&A](docs/architecture/grounded-question-answering.md)
through an opt-in local Ollama CLI, bounded keyword evidence, structured output,
and deterministic citation validation. The [Q&A evaluation definition](evaluation/qa/README.md)
is versioned; a separate local acceptance record distinguishes reported live
observations from deterministic tests and pending scenarios.
M1.7a adds [versioned embedding generation and persistence](docs/architecture/embedding-indexing.md)
for existing publications through an opt-in CLI.
The HTTP application still exposes only the framework `/actuator/health` endpoint.

Vector search, hybrid retrieval, a public Q&A API, Repository
Intelligence UI, and change-impact analysis are not implemented. Tests cover
ingestion and processing with temporary repositories, Java 21 and Markdown syntax,
deterministic chunks, original source mappings, application startup/HTTP health,
PostgreSQL persistence/rollback/retrieval, and evaluation-fixture consistency. The
controlled retrieval baseline measures lexical evidence coverage, not answer quality
or production availability. Hosted CI must be checked
for the revision under review; local verification does not establish it.

See the [architecture overview](docs/architecture/overview.md),
[development conventions](docs/development.md), and [ADR directory](docs/adr/).

## Local Development

Prerequisites: Java 21 (JDK), Git 2.48+ on `PATH` for ingestion/tests, and network
access for initial Maven dependency downloads. Full `verify` also requires a running
Docker engine for disposable PostgreSQL integration tests; `./mvnw test` remains
database-independent.
The Maven Wrapper downloads Maven 3.9.16; a global Maven installation is not needed.
Run these commands from the repository root.

Build and verify:

```sh
./mvnw clean verify
```

Start the application (stop with Ctrl+C):

```sh
./mvnw spring-boot:run
```

In another terminal, verify health:

```sh
curl http://localhost:8080/actuator/health
```

Expected: HTTP 200 with a JSON body containing `"status":"UP"` (the default
response also lists liveness and readiness groups). Only health is exposed over HTTP
by Actuator's default configuration. Default startup needs no external services or
credentials. Enable the explicit
`persistence` profile and follow the [database setup guide](docs/architecture/evidence-persistence.md)
to connect PostgreSQL and run Flyway migrations.

Run tests, including the health test against an embedded server on a random port:

```sh
./mvnw test
```

For opt-in Q&A, use the [local Ollama and CLI guide](docs/architecture/grounded-question-answering.md).
Default startup and mandatory tests do not require Ollama. Spring AI 2.0.1 is
managed through its BOM; the initial model is `qwen3:4b-instruct` with an 8,192-token
context setting and bounded output. Citation validation verifies immutable source
identity, not semantic support for generated claims. Local model results and human
review must be recorded separately from deterministic tests.

## CI and quality checks

The [CI workflow](.github/workflows/ci.yml) runs on pull requests targeting `main`,
pushes to `main`, and manual dispatch. One Ubuntu job uses Temurin Java 21,
Maven caching, read-only repository permissions, and a 15-minute timeout.
It requires no repository secrets or cloud services. Testcontainers starts disposable
PostgreSQL through the runner's Docker engine; missing Docker fails verification.

Reproduce its checks from the repository root with Java 21, Git, and Python 3.9+:

```sh
python3 scripts/check_repository.py
./mvnw -B -ntp clean verify
```

The [repository check](scripts/check_repository.py) rejects tracked files matching
Git ignore rules and verifies the Unix wrapper's executable Git mode. It checks
all repository Markdown files for LF endings, a final newline, tabs, trailing
whitespace (allowing two-space hard breaks), and unclosed code fences. It also
checks relative file targets in inline Markdown links and images outside code.
New non-ignored files are included locally. This is a small convention check,
not a complete Markdown linter or secret scanner: reference-style links, HTML
links, heading anchors, and external URLs are not validated. Use inline relative
file links for repository documentation. It also validates controlled evaluation
fixtures and runs validator regression tests; see the [evaluation guide](evaluation/README.md). No network requests are
made by this check.

Failures stop the job and appear in the PR checks and Actions step logs, with
file/line diagnostics where applicable. Maven prints test failures in its log;
local detailed reports are in `target/surefire-reports/` and `target/failsafe-reports/`.
Application, ingestion,
and processing tests run during `verify`, including an HTTP request to a random-port
embedded server. Ingestion/processing integration tests use local temporary Git
repositories without network access. PostgreSQL tests run in Failsafe during `verify`
and need Docker (initial image downloads require network access).
Check the GitHub-hosted result for the revision being reviewed; local verification
does not establish a hosted result. The maintainer can require the CI job in
`main` branch protection.

Dependency updates are reviewed in focused pull requests: update the Spring Boot
parent to receive managed dependency versions, review release notes and Java
compatibility, and rerun these checks. Review Maven/Wrapper and GitHub Actions
versions periodically in the same way. Avoid independent transitive-version
overrides unless a concrete need is documented. M0 adds no runtime dependencies.

## Local embedding indexing

M1.7a implements explicit, versioned Ollama embedding generation and pgvector
persistence for existing publications. See the
[indexing guide](docs/architecture/embedding-indexing.md) for model/profile setup,
CLI commands, limits, and recovery. Existing PostgreSQL users must follow its
export/import instructions: the new Compose volume preserves the old volume but
does not automatically copy publications. Vector/hybrid retrieval and Q&A strategy
selection remain planned. Default startup and CI require no Ollama server.

## Planned capabilities

- Extend implemented keyword retrieval with evaluated vector/hybrid approaches.
- Evaluate structured answers for semantic support, completeness, and uncertainty.
- Analyze API, database, and event contracts alongside dependency relationships.
- Interpret diffs to identify affected components, compatibility risks, and missing
  tests or verification steps.
- Generate evidence-backed change-risk reports grounded in deterministic findings.
- Use bounded, read-only agent tools with explicit approval requirements.
- Measure retrieval, answer quality, safety, latency, and cost through repeatable
  evaluation and observability.

## Initial technical direction

| Area | Planned direction |
| --- | --- |
| Application | Java 21, Spring Boot, Maven |
| AI integration | Spring AI; local LLMs during development |
| Retrieval and storage | PostgreSQL with pgvector and full-text search |
| Local environment | Docker Compose |
| Observability | OpenTelemetry and Micrometer |
| Hosted validation | Limited AWS Bedrock validation |

The application foundation is implemented, including Actuator's baseline
Micrometer support, plus PostgreSQL persistence and local database Compose setup.
The remaining integrations are planned, not finalized
architecture. Components will be introduced only when a milestone needs them.

## Data strategy

Two complementary corpora support the evaluation direction:

- **Real corpus:** the Distributed Data Intelligence Platform repository, including
  Java code, tests, documentation, and Git history, to exercise realistic changes.
- **Controlled corpus:** the included fictional Parcel Quotes Java/Markdown library
  provides small, versioned evidence scenarios. Broader change-impact cases come later.

The real corpus is not included or ingested. Its access and redistribution permissions
must be established before inclusion. The controlled corpus is also ingested from a
temporary commit in tests; no real-corpus ingestion result is claimed.

## Roadmap overview

| Milestone | Focus |
| --- | --- |
| M0 — Project Foundation (Complete) | Charter, application bootstrap, CI, and conventions |
| M1 — Repository Intelligence MVP (Active) | Evaluation-driven Java/Markdown retrieval and cited answers |
| M2 — Stakeholder and Contract Intelligence | Contracts, entities, dependencies, and compatibility checks |
| M3 — Change-Impact Analysis | Diff-driven impact analysis and change-risk reports |
| M4 — Controlled Agent | Bounded tools, MCP integration, trajectories, and approvals |
| M5 — Production AI Engineering | Observability, versioning, security, cost, and limited AWS validation |

See the [detailed roadmap](docs/roadmap.md) for deliverables and completion criteria.
The first Repository Intelligence release will focus on evaluation-driven retrieval;
agent execution and full change-impact reporting come later.

## Development approach

Deterministic analysis establishes facts; AI interprets and explains them. Important
conclusions must cite evidence, and unsupported conclusions must expose uncertainty.
Retrieved content is untrusted input. Tool access should be read-only by default
and use least-privileged execution.

Work proceeds in small, measurable increments with evaluation as a first-class
subsystem. Favor engineering depth over technology count, preserve provenance and
version information, and distinguish implemented behavior from roadmap items.
Future milestones will define reproducible checks and record results before
claiming completion.

## AI assistance disclosure

This project uses AI tools provided by OpenAI: ChatGPT for architectural
discussions, technical research, planning, documentation, and engineering guidance;
Codex for implementation assistance, tests, refactoring, and code review.

The project maintainer owns architectural decisions, engineering validation, and
publication. AI-assisted changes undergo review and testing appropriate to their
scope. AI assistance does not establish that a capability is implemented or correct.

This is an independent project, not affiliated with, sponsored by, or endorsed by
OpenAI.

## License

Licensed under the [Apache License 2.0](LICENSE).

## AI Assistance & Development Transparency

This project is independently developed and maintained with assistance
from AI-powered development tools, primarily OpenAI's ChatGPT and Codex.

AI tools are used throughout the development lifecycle, including:

- **ChatGPT:** Technical research, architectural discussions,
  design trade-off analysis, project planning, documentation,
  and engineering guidance.
- **Codex:** Assisting with code implementation, refactoring,
  test development, and code review.

AI serves as an engineering assistant, not an autonomous
decision-maker.

All architectural decisions, implementation choices, code reviews,
testing, and final approvals remain the responsibility of the
project maintainer.

AI-generated contributions are subject to the same engineering
standards, validation, and review processes as manually written code.

This project is an independent initiative and is not affiliated
with, sponsored by, or endorsed by OpenAI.
