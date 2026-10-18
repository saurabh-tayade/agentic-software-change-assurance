# Keyword retrieval baseline v1

This small development baseline measures PostgreSQL lexical retrieval, not answer
correctness or semantic reasoning. The [definition](keyword-v1.json) pins strategy
`postgres-keyword-v1`, Parcel Quotes v1, topK=5, and chunking at 4 lines / 10,000
chunks. It imports the seven [original scenarios](../scenarios/parcel-quotes-v1.json)
unchanged and adds eight cases: class/method identifiers, CamelCase terms,
documentation, multiple required sources, absent Kafka evidence, deliberately
excluded documentation, and resource-limited processing. There are **15 queries**,
14 with expected references and one without. No corpus or original expectation was
changed to improve scores. These are visible development cases, not held-out data.

## Reproduction

From the repository root with Java 21, Git, and Docker running:

```sh
python3 scripts/check_repository.py
./mvnw -Dit.test=KeywordEvaluationIT test-compile failsafe:integration-test failsafe:verify
```

Or run `./mvnw clean verify` to include all regression and database tests. No cloud
services, persistent database, developer credentials, or model are needed. Docker
unavailability fails verification. The existing CI already executes this Failsafe
lifecycle; no workflow change or optional test profile is required.

The runner copies the controlled corpus into a disposable Git repository, makes a
commit with fixed author/committer dates and identity, ingests that actual commit,
processes, publishes, and searches PostgreSQL 17.11 in Testcontainers. Scope and
chunk-budget variants have distinct publications. It writes
`target/keyword-baseline.json` with actual commit/publication IDs, definition and
corpus hashes, implementation/grader hashes, per-query ranks/scores/source ranges,
coverage, metrics, and environment. It verifies repeated response equality and exact
membership/provenance independently of relevance grading. Sources are never executed.
The original manifest remains unsupported ingestion coverage, correctly making the
full corpus processing PARTIAL; additional chunk splitting is also reported.

The [recorded report](baseline-v1.json) is one local run on 2026-10-16. Updating
this report is an explicit reviewed action, not a side effect of tests. Re-running
writes only under `target`; latency will differ. Review the printed query plan from
`KeywordRetrievalIT` when changing SQL/indexing. For a plan with real workloads,
measure separately; this tiny corpus cannot establish scaling or index speedups.

## Grading rules

Each expected reference identifies an exact corpus-relative path and original
inclusive line range. Publication repository/commit identity is verified separately
against the selected corpus snapshot. For a reference crossing chunk boundaries,
collect the union of its lines covered by the top K chunks on that exact path. A
reference counts as retrieved **only if every line is covered**; duplicate/overlapping
hits never add credit. Partial overlap alone is a miss for Recall@K, even for blank
lines or declaration boundaries. Per-query recall is fully covered references /
expected references. Reported Recall@5 is the macro mean across queries with
nonempty expectations, including the two deliberately incomplete selections.

Reciprocal rank is 1 / the one-based rank of the first chunk with any original-line
overlap on an expected path, or zero when none overlaps. MRR averages those values
over the same 14 queries. This measures first relevant location, not full support:
MRR can be positive while strict reference recall is zero. Similar words, matching
filenames with different paths, and nonoverlapping lines receive no credit.

The no-evidence case has null recall/RR and is excluded from their denominators;
its returned matches are reported separately, not rewarded as perfect recall.
This baseline returned zero matches for that case. Empty retrieval does not prove
feature absence. Coverage-missing cases retain full-corpus expected references so
scope/budget omissions remain visible rather than being removed from the metric.

Grader unit tests cover multi-chunk union, duplicate overlap, partial spans, exact
paths, rank position, zero matches, and empty expectations. Tests assert correctness,
provenance, and deterministic behavior; they do not assert perfect retrieval quality
or brittle latency thresholds. There is no quality improvement claim or model score.

## Measured results

| Metric | Recorded result |
| --- | ---: |
| Queries / queries with expected evidence | 15 / 14 |
| Macro Recall@5 | 0.571429 |
| MRR@5 | 0.636905 |
| Timed search samples | 150 |
| Mean latency (ms) | 1.610 |
| Median latency (ms) | 1.579 |
| P95 latency (ms) | 2.259 |

Environment: macOS/aarch64, Java 21.0.7, 10 JVM-visible processors, Docker-hosted
PostgreSQL 17.11 Alpine/aarch64, Hikari pool size 2, serial queries. Each scenario
has two warmups plus an untimed reference response before ten timed repeats.
Timing includes transaction, metadata integrity checks, SQL search, mapping, and
returned-chunk hashing; it excludes Git ingestion, parsing, publication, migrations,
and container startup. Mean is arithmetic, median is the upper middle sample, and
P95 is nearest-rank over 150 sorted samples. These warm tiny-corpus measurements
vary with hardware, database cache, Docker, and load. They are not production SLOs.

Observed failures:

- RI-004 and RI-007 retrieve relevant test/limitation text but miss other lines of
  their complete expected references in the top five.
- RI-006 and KW-004 miss complete multi-file validation/pricing references. Lexical
  terms and contextual matches rank fragments above the required supporting spans.
- KW-007 cannot retrieve deliberately out-of-scope README evidence; exclusions are
  returned. KW-008 cannot retrieve pricing omitted by its one-chunk budget; PARTIAL
  outcomes and resource limitations survive retrieval.
- The eight other evidence-bearing cases fully cover their references. KW-006
  (Kafka broker) returns no matches and is not included in recall/MRR averages.

Small chunks, broad OR matching, no stemming/synonyms, inherited context, and path
matches can all affect precision and complete-reference coverage. Do not interpret
scores as support probabilities. See the [retrieval design](../../docs/architecture/keyword-retrieval.md)
for indexed-text bounds, ranking, isolation, and integrity limitations.

M1.8 can extend the same source-reference grading with held-out corpora, precision
and graded relevance, configuration comparisons, cold/warm workload distributions,
larger publication sizes, and independently assessed answer/citation support.
It should retain original expectations and record corpus, strategy, implementation,
and grading versions when comparing results.

## Multi-strategy comparison

M1.8a reuses this grader and imports these same 15 scenarios through
[retrieval-comparison-v1.json](retrieval-comparison-v1.json). See the
[execution and offline grading guide](../../docs/architecture/retrieval-evaluation.md)
for separate KEYWORD/VECTOR/HYBRID runs at K=5 and K=12, explicit publication
variants, failure denominators, and local artifacts. This historical baseline and
its ground truth remain unchanged; new captures are written only under `target`.
