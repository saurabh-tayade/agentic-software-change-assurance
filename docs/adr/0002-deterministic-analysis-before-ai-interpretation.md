# ADR-0002 — Deterministic Analysis Before AI Interpretation

## Status

Accepted as an architectural principle. No analysis or AI integration is implemented.

## Context

Useful change-risk conclusions must distinguish what was observed from what was
inferred. Models can synthesize context but can also produce unsupported claims.
Parsers, algorithms, and test results can establish narrower technical facts with
repeatable behavior, although their coverage and assumptions remain limited.

## Decision

Use deterministic techniques to establish technical facts wherever reliable
algorithms or parsers exist. Use AI for interpretation, synthesis, explanation,
and reasoning only where evaluation demonstrates value. Keep confirmed findings
and model-generated interpretations distinguishable, each with supporting evidence
and stated limitations. Do not promote an interpretation to a fact merely because
it is plausible or repeated by a model.

For example, a future parser may establish that a method signature changed at a
specific revision; whether a particular consumer breaks requires additional
evidence. Neither a parser result nor a passing test suite proves overall safety.

## Alternatives considered

- AI-first analysis of raw artifacts: flexible initially, but harder to reproduce
  and more likely to mix observations with unsupported interpretation.
- Deterministic analysis alone: reproducible within supported cases, but insufficient
  for many cross-artifact explanations and ambiguous contextual questions.
- Unlabelled blending of both: simpler presentation, but hides the basis and
  confidence of each conclusion from reviewers.

## Consequences

Future analysis must retain its evidence and the method used to derive a finding.
Evaluation should assess deterministic findings and AI interpretations separately
as well as the usefulness of the resulting report. Unsupported syntax, incomplete
dependencies, and conflicting evidence must remain visible as limitations.

## Tradeoffs

Maintaining parsers, rules, and interpretation paths costs more than a single prompt.
Deterministic tools can be wrong or incomplete, especially with dynamic behavior,
and models may still add no measurable value for a task. The approach improves
auditability without eliminating uncertainty; it does not mandate a parser library,
model, prompt design, or output schema before those choices are evaluated.
