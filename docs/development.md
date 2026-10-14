# Development conventions

These conventions guide incremental work on the existing application. See the
[architecture overview](architecture/overview.md), [ADRs](adr/README.md), and
[roadmap](roadmap.md) for decisions and milestone scope. Build, run, and CI commands
are maintained in the [README](../README.md).

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
tests for their success, failure, and unsupported cases. For future RAG work, add
versioned evaluation scenarios in addition to software tests, following
[ADR-0003](adr/0003-evidence-provenance-and-reproducible-evaluation.md).

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
