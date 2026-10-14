# Project roadmap

This roadmap describes planned work for the Agentic Software Change Assurance
Platform. The current scope is **M0.2: application bootstrap**. Repository hygiene
and a minimal executable application with context and HTTP health tests are in
place. No AI or change-analysis capabilities exist yet, and M0 as a whole is not complete.
See the [project charter](../README.md) for the product principles and data strategy.

Milestones build on one another. Completion requires reproducible evidence, not
only implementation. For evaluation-based milestones, version the cases, expected
outcomes, metrics, and acceptance thresholds before measuring acceptance; retain
results and document failures and limitations. Thresholds are to be defined within
each milestone, not implied by this roadmap.

## M0 — Project Foundation

**Objective:** Establish a credible, reproducible foundation for incremental work.

**Expected capabilities:** Clear project scope, consistent editing/build conventions,
and a minimal runnable application with automated checks.

**Deliverables:**

- M0.1: project charter/README, roadmap, Apache 2.0 license, ignore rules, and editor
  conventions.
- M0.2: Java 21/Spring Boot/Maven bootstrap, context and HTTP health tests, and
  documented local build and run instructions.
- Subsequent M0 work: CI and engineering conventions.

**Measurable completion criteria:**

- Documentation links resolve, scope and status agree, and repository hygiene checks
  pass without including local secrets or generated outputs.
- A clean checkout can build, test, and start the minimal application using the
  documented prerequisites and commands.
- CI executes the documented build and checks successfully on a reference revision.

M0.2 excludes AI functionality, persistence, CI, infrastructure, and datasets.

## M1 — RAG Foundation

**Objective:** Deliver the first RAG release: evaluation-driven retrieval
and evidence-grounded answers over Java and Markdown.

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

## M2 — Contract and Dependency Intelligence

**Objective:** Establish explicit contract and dependency facts for later impact
analysis.

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
