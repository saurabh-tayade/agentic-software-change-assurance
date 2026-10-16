# Architecture decision records

ADRs record decisions and their rationale, not a claim that every affected
capability has been implemented. Accepted decisions below establish M0 direction;
their application to future features is incremental.

| ADR | Decision | Status |
| --- | --- | --- |
| [ADR-0001](0001-modular-monolith.md) | Modular Monolith | Accepted |
| [ADR-0002](0002-deterministic-analysis-before-ai-interpretation.md) | Deterministic Analysis Before AI Interpretation | Accepted |
| [ADR-0003](0003-evidence-provenance-and-reproducible-evaluation.md) | Evidence, Provenance, and Reproducible Evaluation | Accepted |
| [ADR-0004](0004-atomic-evidence-publications.md) | Atomic PostgreSQL Evidence Publications | Accepted |

Use sequential four-digit identifiers and descriptive kebab-case filenames.
Include Status, Context, Decision, Alternatives considered, Consequences, and
Tradeoffs. Propose a new ADR when a consequential choice has enough evidence for
review; routine implementation details do not need one. If a decision changes,
record the replacement and link the superseded ADR to preserve its rationale.

See the [architecture overview](../architecture/overview.md) and
[development conventions](../development.md) for the surrounding guidance.
