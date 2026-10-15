# Controlled Repository Intelligence evaluation

This original, fictional Parcel Quotes corpus is redistributable under the
repository's [Apache 2.0 license](../LICENSE). It includes no DDIP files or claims.
It is intentionally a tiny library, not a production service or multi-service demo.

## Layout and ground truth

- [Corpus v1](corpora/parcel-quotes/v1/README.md): three Java source files, one Java
  test definition, and Markdown behavior/decision/limitation documentation.
- [Manifest](corpora/parcel-quotes/v1/manifest.json): schema version, corpus identity,
  corpus version, and SHA256 of every source artifact, relative to the corpus root.
- [Scenarios](scenarios/parcel-quotes-v1.json): seven stable IDs covering repository
  understanding, pricing behavior, feature scope, test discovery, architectural
  decisions, multi-file reasoning, and insufficient operational evidence.

The corpus is outside Maven source/resource roots. Its Java files are neither
compiled nor executed nor packaged with the application. The test definition is
retrieval ground truth, not a claim of a passing fixture test.

JSON `schema_version: 1` defines the local format. Every scenario records `id`,
`question`, `corpus_id`, `corpus_version`, nonempty `expected_findings`,
`expected_evidence`, and `expected_answerability`. Findings are reviewable semantic
expectations, not exact generated-answer strings. Answerability is `answerable`
(all requested information is supported), `partial` (only some is supported), or
`insufficient` (the requested conclusion cannot be established).

Every reference repeats corpus identity/version and supplies `path`, `start_line`,
`end_line`, and `contains`: a literal expected excerpt within that inclusive,
one-based original source span. Paths are corpus-relative and language-independent.
No chunk IDs are used. Insufficient scenarios may have empty evidence when no
relevant source exists; references to documented limitations are useful here.
Unknown answers must not be replaced with categorical claims that a feature or
deployment does not exist.

These static fixtures are not standalone Git repositories and have no invented
commit SHA. Corpus identity/version plus hashed files pin their source material.
For future Git ingestion evaluations, record the actual enclosing repository commit
and selected corpus scope in run metadata, and map references to snapshot identity
and repository-relative paths as defined in the
[conceptual contracts](../docs/architecture/repository-intelligence.md).

## Validation and maintenance

Run `python3 scripts/check_repository.py` from the repository root. The existing
local/CI check now validates all scenario suites and runs standard-library
`unittest` regression tests for the validator. No new dependency is needed.
Maven verification continues to test the application foundation independently.

Validation checks required fields/types, nonempty findings, globally unique IDs,
answerability, manifest identities and file inventories/hashes, reference version
consistency, safe existing source paths, line bounds, and literal excerpt matches.
Failures name the suite, scenario/index, reference, and offending field/location.
Mutation tests cover missing fields, duplicate IDs, invalid answerability, unknown
versions, missing files, changed source, malformed JSON, unsafe paths, invalid
ranges, and mismatched excerpts. Hashes detect accidental fixture drift, not
malicious edits to both content and manifest.

Treat published corpus versions as immutable. For source changes, copy to a new
version directory, review new hashes, and update/add scenario suites with globally
unique IDs. Preserve existing scenario IDs; corrections to expectations require
reviewed Git history. New comparisons must pin suite revision and corpus version.
Review original source spans manually: substring checks cannot prove that findings
are semantically correct or complete. These seven visible cases are a development
baseline, not a held-out benchmark. Later work must define held-out cases, metrics,
thresholds, and run metadata before claiming retrieval or answer quality.
