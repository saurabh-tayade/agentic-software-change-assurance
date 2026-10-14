# ADR-0003 — Evidence, Provenance, and Reproducible Evaluation

## Status

Accepted as foundational requirements. Evidence storage and RAG evaluation are
not implemented; detailed contracts and schemas remain M1 decisions.

## Context

Source repositories change over time. An answer cannot be checked reliably if
its evidence points only to a current filename or an unversioned excerpt.
Similarly, an apparent retrieval improvement is not meaningful if the inputs,
expected findings, or evaluation conditions changed without being recorded.

## Decision

Treat provenance and reproducible evaluation as architectural requirements from
the first evidence-producing capability. Future evidence should trace to source
artifacts, repository identity and revision, and relevant locations. Preserve that
traceability through parsing, chunking, retrieval, and reporting.

Evaluation scenarios should version their inputs, queries or changes, expected
findings, and expected supporting evidence. Record the configuration, tool/model
versions where available, metrics, and results needed to repeat and compare runs.
Define acceptance criteria before claiming improvement. Include cases where the
correct outcome is uncertainty or insufficient evidence. Do not prescribe record
fields, database tables, storage technology, or dataset layout before M1.

## Alternatives considered

- Store only extracted text or current paths: simpler ingestion, but weakens
  revision-specific citations and makes stale evidence difficult to detect.
- Evaluate through ad hoc demonstrations: quick feedback, but no stable basis for
  comparing quality or detecting regressions.
- Specify a comprehensive evidence schema now: gives early uniformity, but risks
  encoding assumptions before concrete ingestion and evaluation cases exist.

## Consequences

Future ingestion and retrieval work must account for traceability rather than
adding citations after answer generation. Keep evaluation fixtures scoped and
authorized for use and redistribution. Record failures and coverage gaps alongside
successes. Preserve enough run context to explain changes in measured outcomes.

## Tradeoffs

Provenance and curated expectations increase storage and maintenance work. Source
access restrictions may prevent redistributing the evidence itself. Versioning
improves repeatability but cannot guarantee identical model output or latency;
report variability and measurement conditions. A citation can be traceable yet fail
to support its claim, so evaluation must assess support as well as link resolution.
