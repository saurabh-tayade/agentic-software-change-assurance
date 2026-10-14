# Architecture overview

## Purpose and scope

The platform aims to help engineers assess what a software change could break,
which evidence supports that assessment, and what must be verified before release.
It supports human decisions; it does not certify a change as safe. The
[project charter](../../README.md) defines the product scope.

M0 establishes an executable foundation and development conventions. RAG,
retrieval, AI analysis, change-impact analysis, and agent execution are not
implemented. The logical responsibilities below guide future work, not existing
modules, APIs, services, or deployment boundaries.

## Implemented foundation

One Spring Boot application starts from `ChangeAssuranceApplication` in
`dev.changeassurance`. Spring MVC runs with the embedded servlet container;
Actuator supplies `GET /actuator/health`. Default HTTP exposure is limited to
health. Configuration currently names the application. There are no business
endpoints, persistence stores, model integrations, or external service requirements.

The current request flow is HTTP client -> embedded server -> Actuator health
handling -> JSON health response. It performs no source ingestion or analysis.

The Maven Wrapper builds an executable JAR. Automated tests verify context startup
and HTTP health on a random port. GitHub Actions runs the build and offline
repository/documentation checks. These checks verify the foundation, not future
analysis quality. M0.1 through M0.3 are merged; this documentation is M0.4.

## Planned responsibilities and evidence flow

| Responsibility | Intended role | Earliest roadmap scope |
| --- | --- | --- |
| Source ingestion | Read an explicitly selected local Git repository revision | M1 |
| Parsing and provenance | Extract Java/Markdown content and preserve source identity, revision, and locations through chunking | M1 |
| Retrieval | Find relevant evidence using keyword and vector search | M1 |
| Evaluation | Compare retrieval and answers against versioned scenarios and supporting evidence | M1 onward |
| Contract and dependency analysis | Establish supported relationships and compatibility facts | M2 |
| Change-impact analysis | Relate a diff to affected components and verification needs | M3 |
| Evidence-backed reporting | Produce cited answers in M1; change-risk reports later | M1, then M3 |
| Controlled agent execution | Investigate through bounded, least-privileged tools with approval boundaries | M4 |

The planned M1 flow is a selected repository commit -> Java/Markdown artifacts ->
parsed content and chunks with provenance -> keyword/vector retrieval -> structured
answers with citations and explicit uncertainty. Versioned evaluation scenarios
exercise retrieval and answer generation and retain results for comparison.
Later work adds deterministic contract/dependency findings and diff analysis to
support risk reports; controlled agents may gather evidence within defined limits.
None of this evidence flow exists in the current application.

## Architectural principles

- Establish deterministic facts before AI interpretation when reliable parsers or
  algorithms exist. State the tool's supported scope and limitations.
- Back conclusions with evidence. Distinguish observed facts, interpretations,
  unknowns, and verification still required; a citation alone does not prove a claim.
- Preserve source identity, revision, and location so evidence can be revisited
  after a repository changes.
- Make evaluation reproducible through versioned inputs, expected findings,
  expected evidence, configuration, and recorded results.
- Use least-privileged execution. Treat retrieved content as data, not instructions;
  introduce tool permissions and approval boundaries only with the relevant feature.
- Express uncertainty and missing evidence instead of inferring safety from silence.
- Evolve incrementally inside one deployable application. Add boundaries and
  dependencies when implemented capabilities justify them.

The rationale is recorded in the [architecture decisions](../adr/README.md).

## Technology decisions and preferences

| Area | Implemented now | Future direction, not installed or finalized |
| --- | --- | --- |
| Application | Java 21, Spring Boot 4.1.1, Spring MVC, `dev.changeassurance` | Domain packages as capabilities emerge |
| Build and tests | Maven 3.9.16 via Wrapper, Boot-managed testing and JUnit | Additional test fixtures when needed |
| Operations | Actuator health and baseline Micrometer support | OpenTelemetry and broader observability |
| Quality | GitHub Actions, offline repository/documentation checks | Evaluation of retrieval and answer quality |
| AI | None | Spring AI and local models are preferences to evaluate in M1 |
| Storage and retrieval | None | PostgreSQL, pgvector, and full-text search are preferences to evaluate in M1 |
| Environment | Local JVM; no external services needed | Docker Compose only when dependencies warrant it; limited AWS validation later |

## M1 readiness

The foundation provides a runnable application, repeatable build, tests, and CI
configuration. Before closing M0, review M0.4 and confirm successful hosted CI on
the revision being accepted. A merged issue is not itself evidence of a CI result.
No M1 corpus, retrieval implementation, or evaluation dataset is present yet.

Start M1 with a small, measurable path through the following work:

1. Select an authorized local Git repository and explicit commit, initially scoped
   to Java and Markdown. Establish corpus access and redistribution permissions
   before adding fixtures; preserve repository and commit provenance.
2. Define versioned scenarios with queries, expected findings, and expected
   supporting evidence before comparing retrieval approaches. Include insufficient
   evidence cases and separate tuning cases from held-out evaluation cases.
3. Implement ingestion, parsing, and chunking while retaining source locations.
   Make the same input revision and configuration usable for repeatable runs.
4. Establish keyword and vector retrieval baselines against those scenarios.
   Choose basic metrics such as recall@k and precision@k, define relevance and
   acceptance thresholds before evaluating, and retain measured results.
5. Produce structured answers with citations, evidence limitations, and uncertainty.
   Evaluate citation support and answer correctness alongside retrieval quality.
6. Record latency and token usage with model/configuration identifiers and the
   measurement conditions. Distinguish measured, estimated, and unavailable token
   counts; do not report unsupported measurements as zero.

Ingestion contracts, handling of dirty worktrees and unsupported files, parser and
chunking strategies, retrieval interfaces and ranking, persistence schema,
evaluation dataset layout, and local model/embedding integration remain open.
Resolve them through scoped M1 implementation and experiments. These prerequisites
do not prescribe a schema, provider, API, or new deployment topology. See the
[roadmap](../roadmap.md) for milestone acceptance criteria.
