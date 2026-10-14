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
This is a change-assurance system, not a general-purpose repository chatbot.

## Current implementation status

**M0.4 — Architecture & Development Conventions:** architecture guidance and ADRs
now complement the merged CI and repository checks, Java 21 / Spring Boot 4.1.1
application, Maven Wrapper, context test, and HTTP health integration test.
Only the application foundation exists: it starts without external infrastructure
and exposes the framework-provided `/actuator/health` endpoint.

RAG, retrieval, AI analysis, ingestion, persistence, and datasets are not
implemented. M0.4 is the final foundation issue; completion requires review and
successful CI on the accepted revision.

See the [architecture overview and M1 readiness](docs/architecture/overview.md),
[development conventions](docs/development.md), and [ADR directory](docs/adr/).

## Local Development

Prerequisites: Java 21 (JDK) and network access for initial dependency downloads.
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
by Actuator's default configuration. No external services or credentials are needed.

Run tests, including the health test against an embedded server on a random port:

```sh
./mvnw test
```

## CI and quality checks

The [CI workflow](.github/workflows/ci.yml) runs on pull requests targeting `main`,
pushes to `main`, and manual dispatch. One Ubuntu job uses Temurin Java 21,
Maven caching, read-only repository permissions, and a 15-minute timeout.
It requires no repository secrets or external application services.

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
file links for repository documentation. No network requests are made by this check.

Failures stop the job and appear in the PR checks and Actions step logs, with
file/line diagnostics where applicable. Maven prints test failures in its log;
local detailed reports are in `target/surefire-reports/`. Both application tests
run during `verify`, including an HTTP request to a random-port embedded server.
Check the GitHub-hosted result for the revision being reviewed; local verification
does not establish a hosted result. The maintainer can require the CI job in
`main` branch protection.

Dependency updates are reviewed in focused pull requests: update the Spring Boot
parent to receive managed dependency versions, review release notes and Java
compatibility, and rerun these checks. Review Maven/Wrapper and GitHub Actions
versions periodically in the same way. Avoid independent transitive-version
overrides unless a concrete need is documented. M0 adds no runtime dependencies.

## Planned capabilities

- Retrieve Java and Markdown evidence with source locations and version provenance.
- Produce structured answers with citations and explicit uncertainty.
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
Micrometer support. The remaining integrations are planned, not finalized
architecture. Components will be introduced only when a milestone needs them.

## Data strategy

Two complementary corpora are planned:

- **Real corpus:** the Distributed Data Intelligence Platform repository, including
  Java code, tests, documentation, and Git history, to exercise realistic changes.
- **Controlled corpus:** a fictional multi-service system with known compatibility,
  dependency, authorization, and change-impact scenarios for repeatable evaluation.

Neither corpus is included or ingested yet. Future ingestion must preserve source
identity, revision, and location so evidence can be traced to a specific version.
Corpus access and redistribution permissions must be established before inclusion
in this public project.

## Roadmap overview

| Milestone | Focus |
| --- | --- |
| M0 — Project Foundation | Charter, repository hygiene, then application bootstrap, CI, and conventions |
| M1 — RAG Foundation | Evaluation-driven Java/Markdown retrieval and cited answers |
| M2 — Contract and Dependency Intelligence | Contracts, entities, dependencies, and compatibility checks |
| M3 — Change-Impact Analysis | Diff-driven impact analysis and change-risk reports |
| M4 — Controlled Agent | Bounded tools, MCP integration, trajectories, and approvals |
| M5 — Production AI Engineering | Observability, versioning, security, cost, and limited AWS validation |

See the [detailed roadmap](docs/roadmap.md) for deliverables and completion criteria.
The first RAG release will focus on evaluation-driven retrieval; agent execution
and full change-impact reporting come later.

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
