# Project roadmap

This roadmap distinguishes implemented evidence workflows from future change-assessment
capabilities. **M0 is complete. M1 is implemented; upcoming release:
v0.1.0 — Repository Intelligence Foundation.** M1.9c's UI is visually accepted and
frozen; release documentation, final verification, merge, tagging, and publication
remain maintainer-controlled closeout work.

The current product includes local Git ingestion, immutable Java/Markdown evidence,
Keyword/Vector/Hybrid retrieval, grounded Q&A, source inspection, versioned evaluation,
and a local read-only REST API with React UI. Contract compatibility, change-impact
analysis, test execution, controlled agents, and public/authenticated access are not
implemented. See the [product overview](product-overview.md),
[measured release results](releases/v0.1.0-results.md), and [demo](demo.md).

Milestones build on one another. Completion requires reproducible evidence, not
only implementation. For evaluation-based milestones, version the cases, expected
outcomes, metrics, and acceptance thresholds before measuring acceptance; retain
results and document failures and limitations. Thresholds are to be defined within
each milestone, not implied by this roadmap.

## M0 — Project Foundation

**Status: Complete.**

**Objective:** Establish a credible, reproducible foundation for incremental work.

**Expected capabilities:** Clear project scope, consistent editing/build conventions,
and a minimal runnable application with automated checks.

**Deliverables:**

- M0.1: project charter/README, roadmap, Apache 2.0 license, ignore rules, and editor
  conventions.
- M0.2: Java 21/Spring Boot/Maven bootstrap, context and HTTP health tests, and
  documented local build and run instructions.
- M0.3: GitHub Actions build/test verification, offline repository and documentation
  checks, and documented dependency-update conventions.
- M0.4: [architecture overview](architecture/overview.md),
  [ADRs](adr/README.md), [development conventions](development.md), and M1 readiness.

**Measurable completion criteria:**

- Documentation links resolve, scope and status agree, and repository hygiene checks
  pass without including local secrets or generated outputs.
- A clean checkout can build, test, and start the minimal application using the
  documented prerequisites and commands.
- CI executes the documented build and checks successfully on a reference revision.
- Architecture and ADRs distinguish current behavior from future direction, and
  M1 prerequisites and open decisions are documented and reviewed.

M0.4 is documentation only; it adds no application functionality, runtime
dependencies, deployment workflows, infrastructure, or datasets.

## M1 — Repository Intelligence MVP

**Status: Implemented; v0.1.0 release closeout pending maintainer publication.**

**Objective:** Deliver the first independently usable capability: natural-language repository
understanding for developers and non-technical stakeholders, with evaluation-driven
retrieval and evidence-grounded answers over Java and Markdown.

The [architecture overview](architecture/overview.md) describes the implemented
local Git source, evidence boundaries, and evaluation workflow.
M1.1 provides [conceptual contracts](architecture/repository-intelligence.md) and the
[evaluation foundation](../evaluation/README.md). M1.2 implements
[Git Repository Snapshot Ingestion](architecture/git-snapshot-ingestion.md).
M1.3 implements [Java/Markdown Parsing & Chunking](architecture/artifact-processing.md).
M1.4 implements [Evidence Persistence](architecture/evidence-persistence.md).
M1.5 implements [Keyword Retrieval](architecture/keyword-retrieval.md), including
publication consistency checks and [versioned retrieval evaluation](../evaluation/retrieval/README.md).
M1.6 adds [grounded Q&A](architecture/grounded-question-answering.md) and a
[Q&A evaluation definition](../evaluation/qa/README.md). M1.7 adds vector
and hybrid retrieval alongside the existing keyword baseline while preserving the answer
and citation contracts. M1.7a indexing and M1.7b semantic retrieval are implemented;
M1.7c hybrid/Q&A strategy integration is implemented. [Initial observations](../evaluation/retrieval/m1-7-observations.md)
preserve the maintainer-reported standalone and three-strategy live runs; they are
not aggregate benchmark evidence. M1.8a adds [reproducible retrieval evaluation](architecture/retrieval-evaluation.md)
using the existing ground truth, separate K=5/K=12 executions, and offline grading.
The maintainer reports 90 live M1.8a executions without failures and reproduction
of the historical keyword baseline. M1.8b implements [grounded-answer evaluation](architecture/grounded-answer-evaluation.md):
same-execution capture, independent deterministic checks, and attributed offline
human review. The maintainer accepted six live Q&A executions and one attributed
review containing an incorrect and a correct claim; five executions remain semantically
unreviewed. M1.8c implements [offline evaluation reports](architecture/evaluation-reporting.md)
with explicit input/review selection and reproducible JSON/Markdown output.
M1.9a implements the [local Repository Intelligence REST API](architecture/repository-intelligence-api.md)
for explicit publication selection, coverage, three-strategy grounded Q&A, and
verified original excerpts. M1.9b implements the
[interactive React workspace](architecture/repository-intelligence-workspace.md)
with explicit publication selection, grounded answers, uncertainty, citation-to-source
navigation, and expandable diagnostics. M1.9c.2 adds focused accessibility and
mobile polish, clearer publication/uncertainty presentation, selection reliability,
and a documented demo preflight without changing retrieval or backend APIs.
Live UI/API acceptance is separate from deterministic tests; sparse reviews are
not aggregate correctness evidence.

The Change Assurance shell now provides Overview, Repositories, Repository
Intelligence, and searchable in-app Help. The catalog groups existing publications;
connection controls remain disabled previews. The question workflow offers editable
examples, a Search Method selector, compact answers, and a complete answer dialog
with source navigation. M2 contract/dependency intelligence, M3 change impact, and
M4 assisted verification have clearly labeled planned previews only. This frontend
change does not implement their backend capabilities. The maintainer has visually accepted the current UI, including independent dashboard
body scrolling. That acceptance is separate from automated verification and does not
publish a release. Four repository identities are prepared locally: Parcel Quotes,
AI Data Explorer, Incident Triage, and DDIP. Change Assurance self-ingestion remains
planned after M1.9c merges.

**Expected capabilities:** Ingestion, provenance-aware chunking, keyword/vector
retrieval, structured answers, citations, and explicit uncertainty.

**Deliverables:**

- A reproducible ingestion and retrieval workflow for scoped real and controlled
  Java/Markdown corpora.
- An answer format carrying source references and uncertainty.
- A versioned evaluation set, retrieval/answer metrics, and a baseline report.

**Measurable completion criteria:**

- Both scoped corpora can be ingested and queried using documented commands.
- Returned citations resolve to source identity, revision, and location in the
  evaluated corpus snapshot.
- A repeatable evaluation measures retrieval relevance, citation correctness,
  answer support, and behavior when evidence is insufficient; recorded results
  meet the milestone's predefined acceptance thresholds.

## M2 — Stakeholder and Contract Intelligence

**Objective:** Explain capabilities to stakeholders using explicit evidence dimensions,
and establish contract and dependency facts for later impact analysis.

**Expected capabilities:** OpenAPI, database migration, and event schema analysis;
entity extraction; dependency relationships; and compatibility detection.

**Deliverables:**

- A documented supported scope for contract formats and compatibility rules.
- Extracted entities and dependency relationships with source provenance.
- Deterministic compatibility findings and controlled evaluation scenarios.

**Measurable completion criteria:**

- Versioned cases cover each supported contract family and include compatible and
  incompatible changes with expected findings.
- Extracted relationships and compatibility findings trace to source evidence.
- Evaluation records detection accuracy and false positives against predefined
  thresholds; unsupported constructs are explicitly reported.

## M3 — Change-Impact Analysis

**Objective:** Turn a software diff and system context into an actionable,
evidence-backed change-risk report.

**Expected capabilities:** Diff analysis, affected-component identification,
contract-risk detection, and identification of missing tests and verification work.

**Deliverables:**

- A workflow accepting explicit base and target revisions.
- A structured report covering findings, affected components, evidence, uncertainty,
  and recommended release verification.
- Controlled change scenarios with expected impacts and a scored evaluation report.

**Measurable completion criteria:**

- Documented commands reproduce a report for a fixed change and corpus snapshot.
- Important conclusions cite evidence; inferred impacts and missing-test suggestions
  are distinguishable from deterministic findings.
- Evaluation measures impact precision/recall, evidence support, and verification
  coverage against predefined thresholds, including changes with insufficient context.

## M4 — Controlled Agent

**Objective:** Add bounded investigation over available evidence and analysis tools.

**Expected capabilities:** Read-only tool calling, bounded execution, MCP integration,
recorded agent trajectories, and enforceable approval requirements.

**Deliverables:**

- Least-privileged read-only tools and a documented MCP integration.
- Execution budgets, stop conditions, tool-access policy, and approval boundaries.
- Inspectable trajectories and evaluations for tool behavior and untrusted content.

**Measurable completion criteria:**

- Controlled runs demonstrate tool access through MCP and record calls, outcomes,
  evidence references, and termination reasons.
- Tests show execution stops at configured limits and prohibited operations are
  blocked; approval-required operations cannot proceed without recorded approval.
- Adversarial retrieved-content cases cannot override tool permissions or execution
  policy in the versioned evaluation suite; observed failures are recorded.

## M5 — Production AI Engineering

**Objective:** Make quality, operational behavior, security, and cost measurable.

**Expected capabilities:** Observability, prompt/model versioning, evaluation
dashboards, security controls, cost measurement, and limited AWS validation.

**Deliverables:**

- Traces and metrics connected to versioned prompts, models, and evaluation runs.
- Dashboards for quality, latency, resource usage, and hosted-model cost.
- Documented security controls, operating limits, and a bounded AWS Bedrock
  validation report.

**Measurable completion criteria:**

- An evaluation run can be traced to its code/corpus revisions, prompt/model
  versions, configuration, and results.
- Dashboards expose measured quality, latency, and cost for a repeatable workload.
- Security checks verify documented access boundaries, secret handling, and
  untrusted-input controls against defined acceptance criteria.
- A limited Bedrock run stays within a declared budget and records comparison
  results and limitations relative to the local-model baseline.

Completion demonstrates measured engineering readiness within the evaluated scope;
it does not imply unrestricted autonomy or a guarantee of release safety.
