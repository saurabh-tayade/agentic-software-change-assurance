# Architecture overview

## Purpose and scope

The platform aims to help engineers assess what a software change could break,
which evidence supports that assessment, and what must be verified before release.
It supports human decisions; it does not certify a change as safe. The
[project charter](../../README.md) defines the product scope.

M0 establishes an executable foundation and development conventions. Change-impact
analysis and agent execution are not implemented. M1.2 adds ingestion, M1.3
parsing/chunking, M1.4 persistence, M1.5 keyword retrieval, M1.6 local grounded Q&A,
M1.7a embedding indexing, and M1.7b vector retrieval; the remaining logical
responsibilities guide future work, not separate services or deployment boundaries.

## Implemented foundation

One Spring Boot application starts from `ChangeAssuranceApplication` in
`dev.changeassurance`. Spring MVC runs with the embedded servlet container;
Actuator supplies `GET /actuator/health`. Default HTTP exposure is limited to
health. Model integrations run through opt-in local profiles and CLI commands.
Default startup needs no
external services; the explicit `persistence` profile enables PostgreSQL and Flyway.

The current request flow is HTTP client -> embedded server -> Actuator health
handling -> JSON health response. It performs no source ingestion or analysis.

The Maven Wrapper builds an executable JAR. Automated tests verify context startup
and HTTP health on a random port. GitHub Actions runs the build and offline
repository/documentation checks. These checks verify the foundation, not future
analysis quality. M0 is complete; M1 is active. M1.1 adds conceptual contracts
and controlled evaluation validation. M1.2 adds
[read-only Git snapshot ingestion](git-snapshot-ingestion.md) in
`dev.changeassurance.ingestion`, independently executable through integration tests.
It preserves committed Java/Markdown content and provenance with explicit coverage
limitations. M1.3 adds [artifact processing](artifact-processing.md) in
`dev.changeassurance.processing`, with Java/Markdown parsing, deterministic chunks,
and original line provenance. M1.4 adds [PostgreSQL persistence](evidence-persistence.md)
in `dev.changeassurance.persistence`, with atomic publication and identity readback.
M1.5 adds [keyword retrieval](keyword-retrieval.md) in `dev.changeassurance.retrieval`,
with explicit publication isolation, ranked evidence, and coverage.
M1.6 adds [grounded Q&A](grounded-question-answering.md). M1.7a adds
[versioned embedding indexing](embedding-indexing.md) with derived pgvector storage.
M1.7b adds [exact vector retrieval](vector-retrieval.md) scoped to a publication and profile.
These components add no HTTP route or automatic repository scan.

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
Selected-commit ingestion, parsing, and provenance-preserving chunking are
implemented, along with PostgreSQL persistence and keyword retrieval. Opt-in
[grounded Q&A](grounded-question-answering.md) now validates citation identities
over selected keyword evidence. Vector retrieval is available separately; hybrid
fusion, Q&A strategy selection, and broader quality evaluation remain future work.

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
| Parsing | JavaParser core 3.27.1 and CommonMark core 0.24.0 | Additional source formats when needed |
| AI | Spring AI 2.0.1 and opt-in Ollama chat adapter for grounded Q&A | Model quality and further providers are not established |
| Storage and retrieval | PostgreSQL evidence persistence, keyword/exact vector retrieval, and derived pgvector embeddings via JDBC/Flyway | Hybrid retrieval remains future work |
| Environment | Local JVM; optional PostgreSQL profile and database-only Compose | Limited AWS validation later |

## M1 readiness

The foundation provides a runnable application, repeatable build, tests, and CI.
M1.1 establishes [Repository Intelligence contracts](repository-intelligence.md)
and a [controlled evaluation foundation](../../evaluation/README.md). Repository
Intelligence is the first independently usable capability planned for developers
and non-technical stakeholders, supporting the long-term Change Assurance vision.
The ADRs remain the governing decisions; these contracts refine their M1 direction.

M1.2 now resolves selected local Git revisions, reads committed source objects, and
reports deterministic inventories with provenance and coverage. The
[ingestion guide](git-snapshot-ingestion.md) documents scope, dirty-worktree policy,
links/submodules, encodings, limits, and verification.

M1.3 consumes unchanged source content and preserves original source locations and
ingestion limitations; see the [processing guide](artifact-processing.md) for parser
selection, chunking, and verification. M1.4
[persists those contracts](evidence-persistence.md) atomically.
M1.5 [keyword retrieval](keyword-retrieval.md) now supplies ranked evidence and a
controlled baseline. M1.6 [grounds structured answers](grounded-question-answering.md)
in bounded keyword evidence with deterministic citation identity checks. M1.7 will
compare vector/hybrid retrieval against the keyword baseline; M1.8 will expand
answer quality evaluation and human-reviewed semantic support.
Before quality claims, define metrics, acceptance thresholds, held-out cases, and run
metadata including source revisions, tool/model configuration, latency, and token
usage. Further ranking improvements and model-quality claims remain open;
the conceptual contracts do not prescribe those implementations. See the
[roadmap](../roadmap.md) for milestone acceptance criteria.
