# Repository Intelligence contracts

M1.1 established these conceptual contracts. M1.2 implements their snapshot and
source-artifact responsibilities in the [Git ingestion component](git-snapshot-ingestion.md);
M1.3 implements [parsing and normalized evidence chunks](artifact-processing.md).
M1.4 [persists coherent evidence publications](evidence-persistence.md) and supports
readback by explicit identity. M1.5 implements [keyword retrieval](keyword-retrieval.md)
with ranked evidence and publication coverage. Vector retrieval and answers remain planned.
Repository Intelligence is the first independently usable capability planned for
developers and non-technical stakeholders: ask questions
about existing software and receive evidence-backed answers with verifiable
citations. It supports the longer-term Change Assurance and Stakeholder Intelligence
vision. The [overview](overview.md) and existing [ADRs](../adr/README.md) govern it.

## Minimum contracts

| Concept | Required meaning | Constraints and optional context |
| --- | --- | --- |
| RepositorySnapshot | Repository identity, resolved full Git commit SHA, selected scope, snapshot identity | Identity distinguishes repositories independently of a mutable clone path. Scope records included/excluded paths and selection rules. Reproduction requires the same committed bytes and scope; a branch name alone is insufficient. |
| SourceArtifact | Snapshot identity, repository-relative file path, artifact type, language or document format, source provenance | Type may be source, test, or documentation without assuming classes or Java symbols. Preserve original bytes or a verifiable content digest and decoding information; unknown language is explicit. |
| EvidenceReference | Repository identity, commit SHA, relative file path, original inclusive one-based start/end lines | Optional symbol or document section improves navigation but never replaces the original location. References point to source, not generated chunks. |
| RetrievalResult | Query context, retrieved evidence references, retrieval method, ranking/relevance metadata | Record rank and method/configuration identity; scores are optional and method-specific, not calibrated confidence or comparable across methods by default. No search implementation is required by this contract. |
| GroundedAnswer | Question, answer, claim-to-evidence references, limitations, answerability and uncertainty | Distinguish supported claims from interpretations and unknowns. Use answerable, partial, or insufficient evidence outcomes; no invented citations or unsupported certainty. |

For example, an evidence reference can be expressed as
`{repository: "selected-repository", commit: "<resolved-full-sha>", path:
"src/example/quotes/QuotePolicy.java", startLine: 4, endLine: 6}`. The commit value
here is a placeholder, not a claimed revision. A pricing claim would carry that
reference; a chunk identifier or a floating branch name would not be ground truth.

Snapshots select committed content. Dirty working-tree bytes must not be silently
labelled with the HEAD commit. The [ingestion policy](git-snapshot-ingestion.md)
specifies caller-provided identity, literal scope rules, committed-only reads,
links/submodules, missing objects, and encodings. These implementation details
refine the conceptual contracts without prescribing later retrieval or parsing.

Before a future answer is accepted, resolve each citation against the selected
snapshot, check file and line bounds, and compare cited material with the claim.
Location validity alone does not prove semantic support. An LLM-generated reference
is a proposal to validate, not trusted evidence. Preserve failures, conflicting
sources, and incomplete coverage as answer limitations.

## Boundaries and extensibility

1. **Repository discovery** selects a repository/commit/scope and inventories
   artifacts with provenance. Discovery does not claim parsing support.
2. **Language/format-specific parsing** interprets supported syntax, recording
   parser version, supported scope, errors, and original source mappings.
3. **Normalized evidence** carries excerpts, references, derivation method, and
   limitations independently of language. Parsing or chunking may change without
   changing the meaning of original source coordinates.
4. **Retrieval** selects and ranks normalized evidence for a query. It preserves
   provenance and coverage limitations without deciding that a feature is deployed.
5. **Grounded question answering** synthesizes claims from evidence, validates
   citations, and states uncertainty. Retrieved content remains untrusted data.

Java source/test files and Markdown are the initial formats implemented by M1.3;
M1.1 itself established contracts and fixtures only. Python, TypeScript, OpenAPI,
SQL/Flyway, and event schemas can later supply the same normalized evidence with format-specific derivation details.
No parser registry, plugin system, or framework analyzer is needed now.

Discovery should distinguish excluded artifacts from discovered but unsupported
ones. Record unsupported format, unreadable content, parse failure, and partial
parse coverage with a reason and affected artifact/location where available.
Do not turn these into an empty successful result or imply full repository coverage.
Raw source may remain evidence even when semantic extraction is unavailable, but
must not be described as successfully parsed.

## Evidence and capability semantics

**Source evidence** is observed content at an immutable source location, including
code, test definitions, and written claims. Documentation is evidence of what was
written, not automatic proof of runtime behavior.

**Deterministically derived facts** retain source references, tool/rule version,
inputs, and limitations. A rule can establish a signature or calculate a price
within its assumptions; deterministic does not mean infallible.

**AI-generated interpretations** retain supporting evidence and explicit inference
and uncertainty labels. Plausibility does not promote an interpretation to a fact.

These capability dimensions are independent, not a linear progression:

| Dimension | Relevant evidence | What it does not establish |
| --- | --- | --- |
| Implemented | Method body or other implementation at a revision | Correctness, enabled state, deployment |
| Tested | Test definition for intended behavior; execution result separately records pass/fail, revision, and environment | A definition alone does not establish a passing run |
| Exposed | API declaration, route, or public library entry point | Production availability or authorization |
| Enabled | Effective configuration/feature flag for an environment and time | Deployment health or all-user availability |
| Deployed | Deployment-specific artifact/revision and environment record | Correct behavior or current enablement |
| Verified | Explicit check, result, scope, revision, environment, and time | Universal correctness beyond that check |

Unknown or conflicting evidence remains unknown or conflicting for each dimension.
Absence of evidence does not establish absence of functionality. Deployment needs
deployment-specific evidence. Future Change Assurance and Stakeholder Intelligence
must preserve these dimensions and provenance when explaining capabilities or risk;
M1.1 implements no automatic classification system.

The [evaluation foundation](../../evaluation/README.md) exercises these distinctions
using controlled source evidence. It does not evaluate model or retrieval quality.
