# PostgreSQL keyword retrieval

M1.5 implements `dev.changeassurance.retrieval` inside the existing modular monolith.
It searches [M1.4 publications](evidence-persistence.md) by explicit identity, without
reingestion, parsing, filesystem access, or model calls. ADR-0001 through ADR-0004
remain applicable; this adds no endpoint, dependency, database extension, or service.
This module performs no embedding generation or answer generation. See
[grounded Q&A](grounded-question-answering.md) and [embedding indexing](embedding-indexing.md)
for the separately implemented opt-in capabilities. Vector and hybrid retrieval
remain planned.

## Service and outcomes

With the existing `persistence` profile and database configuration enabled, Spring
wires `KeywordRetrievalService` after Flyway initialization. Default startup remains
database-independent. Direct construction with a `DataSource` supports integration
tests and application-level composition.

```java
var response = keywordRetrievalService.search(publicationId, "retry failed ingestion", 5);
```

The returned `Optional<RetrievalResult>` distinguishes an unknown publication
(empty Optional) from a known publication with no matches (present response with
an empty list). Invalid inputs throw `IllegalArgumentException`. Stored consistency
failures throw `PublicationIntegrityException`; database/transaction failures throw
`RetrievalException`, with a generic public message and the original cause for
internal diagnostics. There is no implicit latest publication or fallback corpus.

The immutable response carries original query, requested topK, result count,
`postgres-keyword-v1` strategy, ranked matches, search limitations, and a
`PublicationSummary`. Each match contains one unchanged M1.3 `EvidenceChunk`, its
one-based rank, and a double relevance score. The enclosing response supplies the
publication and strategy identities for every match. Summary metadata includes
snapshot and derivation IDs, repository/commit/scope/policy, processing configuration,
overall status, source inventory, all per-artifact outcomes and typed limitations,
ingestion coverage/limitations/summary, and general processing limitations.

Citations use the chunk's repository, resolved commit, relative file path, and
original inclusive start/end lines. Content, line endings, Unicode, blob/source
hashes, content digest, parser/configuration, and context survive unchanged. Context
is metadata that can refer outside the quoted span; it must not be presented as
source text at that location. Search never produces replacement source coordinates.

Queries must be nonblank, valid Unicode without NUL, and at most 512 Java UTF-16
code units; topK is 1–100. Punctuation-only queries are valid zero-match searches.
No filters or Boolean/phrase/prefix search-expression language is exposed. Quotes,
parentheses, SQL-like text, and search operators are data. JDBC parameters bind all
caller-controlled values; no user text is concatenated into SQL.

The caller must supply an explicitly authorized publication ID. Authentication and
authorization are not implemented; this service is not ready for exposing arbitrary
repository content to untrusted users. Source, comments, context, and diagnostics
remain untrusted data and are never executed or treated as application instructions.

## Search representation and V2 migration

`V2__keyword_retrieval.sql` adds two generated STORED columns and two GIN indexes:

- `evidence_chunk.search_document`: weighted context plus original chunk content.
- `source_artifact.search_path`: weighted repository-relative path.

V1 is unchanged. Generated columns backfill existing rows during migration and stay
consistent on writes; they do not alter evidence or logical identities. Persistence
uses an explicit chunk insert column list. Schema upgrades need normal migration
locking/time and extra disk for vectors/indexes; no online zero-downtime claim is made.

`keyword_text_v1` lowercases two representations joined with a space: whole tokens
(punctuation except underscore becomes spaces), and split tokens. Splitting inserts
spaces between ASCII lowercase/digits and uppercase, and between an acronym's final
uppercase and a following capitalized word; then all non-alphanumeric characters
become separators. Examples: `ProductService` retains `productservice` and adds
`product service`; `HTTPServer` adds `http server`; `retry_count` retains the whole
underscore token and adds `retry count`. Dots, slashes, and hyphens separate package,
filename, and path components. This is a modest text transformation, not a lexer.
Unicode terms use PostgreSQL's locale-sensitive lowercasing/alphanumeric behavior;
non-ASCII CamelCase segmentation and Unicode normalization are not implemented.

Both document and query use this same function and PostgreSQL's explicit `simple`
configuration. It preserves technical terminology without English stemming or
synonyms: `failure` and `failures` need not match. Query construction extracts unique
normalized lexemes through `to_tsvector`, quotes each lexeme, and joins them with
OR into a tsquery. It never interprets raw input as tsquery syntax. Natural-language
queries can match despite extra unmatched words, but frequent words can produce
incidental results. No stop-word list or semantic expansion is added.

PostgreSQL's FTS vectors have representation limits. To keep indexing compatible
with M1.2's 1 MiB sources and bounded metadata, content and serialized context each
index their first **65,536 PostgreSQL characters**. Paths use their full existing
bounded length. Full original content remains stored and returned. Every response
explicitly reports this search bound; terms only beyond it are not retrievable unless
present in another indexed field. PostgreSQL also ignores overlong lexemes and caps
term positions/frequencies. This policy prevents pathological large documents from
breaking publication, at the cost of incomplete keyword coverage on unusually large
chunks. Reducing chunk line limits can help but does not bound a single long line.

PostgreSQL documents [query/ranking behavior](https://www.postgresql.org/docs/17/textsearch-controls.html)
and [GIN text indexes](https://www.postgresql.org/docs/17/textsearch-indexes.html).
`plainto_tsquery` alone requires all terms; raw `websearch_to_tsquery` exposes operators
that this API does not promise. Explicit OR lexemes suit this baseline. `ts_rank`
uses weighted frequency; `ts_rank_cd` proximity would be misleading after appended
identifier expansions, so it is not used.

## Candidate selection and ranking

The SQL has two publication-restricted candidate branches: chunk document matches
and source-path matches. UNION deduplicates candidates. Keeping these branches
separate allows PostgreSQL to consider each GIN index rather than one OR predicate
across joined tables. Both join the selected `publication_chunk` membership using
snapshot plus chunk ID. Final evidence joins use the same compound keys; sharing a
chunk ID across scopes cannot leak membership. All scoring, sorting, and LIMIT run
in PostgreSQL before evidence bodies are mapped into Java.

The ranking formula is:

```text
score = ts_rank(chunk_document, expanded_OR_query, 32)
      + ts_rank(path_document, expanded_OR_query, 32)
      + (original context matches plain original query ? 1 : 0)
```

Context has weight A (1.0), content B (0.4), and path C (0.2), using PostgreSQL's
default rank weights. Normalization 32 applies rank/(rank+1) separately to each FTS
term. The additive bonus rewards intact technical terms in declaration/heading
context before identifier splitting; for multiple original terms it requires all
of them in that context. It is not whole-phrase equality. A score is a ranking
signal, not calibrated confidence, factual correctness, or cross-strategy relevance.

Ties sort by path with PostgreSQL `C` collation, original start line, then chunk ID
with `C` collation. Repeated queries against unchanged data/configuration on the
supported PostgreSQL runtime produce equal result records. Database locale/version
changes can change lexemes and scores: retain the recorded environment and version
search functions/strategy when changing behavior. Do not redefine a versioned
normalizer without rebuilding derived vectors and indexes in a new migration.

## Publication consistency and cost

`PublicationIntegrity.inspect` belongs to persistence and is reused by retrieval
and M1.4 public exact-identity reads. It verifies snapshot/derivation/publication
hash relationships, configuration/status metadata agreement, source inventory,
coverage counts, artifact flags/status, and unique outcomes. It reads narrow,
ordered membership metadata to check contiguous ordinals, Java source-path order,
contiguous nonoverlapping per-file spans, maxLines/maxChunks, parser/configuration,
source references, and expected per-artifact chunk counts. Missing first, middle,
or trailing members fail even when the query would return no matches. Empty,
PARTIAL, and FAILED publications remain legitimate.

The checker performs three bounded SQL reads: header/snapshot, source paths, and
membership metadata. It does **O(source + chunk count)** work per search, bounded
by 10,000 sources and 100,000 members, and retains counts/IDs plus metadata in Java.
It does not transfer source bodies or all chunk bodies, reparse sources, or filter
content in Java. JDBC may buffer narrow result rows. This deliberately favors
inspectable integrity over constant-time validation; large-publication throughput
has not been benchmarked. Returned chunks alone are rehashed and their deterministic
IDs checked. Search then executes one query, avoiding per-hit queries.

Search and exact-identity readback use a 30-second, read-only REQUIRES_NEW,
REPEATABLE READ transaction, so integrity checks and selection observe one database
snapshot. Concurrent normal publications are immutable. A new publication committed
after a read starts may require a subsequent call. Existing publish transactions
remain unchanged. Readbacks now explicitly reject broken membership instead of
silently reconstructing fewer chunks.

This is not tamper-proof storage. Foreign keys/unique constraints are assumed
installed and enabled. Privileged coordinated changes to both metadata and evidence
can defeat consistency checks. Searches do not rehash every source blob, validate
all original source slices again, or detect modifications committed after their
transaction snapshot. Selected evidence hashes/IDs are checked; corruption in
unselected content may remain undetected. M1.4 publish remains the deeper validation
boundary. No cache, background repair, retries, or administrator mutation API is added.

## Evaluation and M1.6 integration

The [versioned retrieval baseline](../../evaluation/retrieval/README.md) defines
15 scenarios, source-range grading, measured results, reproduction, and weaknesses.
The runner uses unchanged Parcel Quotes content and seven original M1.1 scenarios.
Actual PostgreSQL tests additionally cover complete/partial/failed/empty results,
commits/scopes/configurations/parser versions, punctuation, Unicode, ties, bounds,
corruption, startup profiles, and migration of existing V1 publications.

EXPLAIN ANALYZE runs against the actual production query. The tiny fixture uses
sequential scans plus existing key indexes; its plan does not establish a GIN
speedup or production scaling. GIN eligibility and bounded results are design
properties, not measured performance improvements.

M1.6 can pass a natural-language question and explicit authorized publication ID,
receive ranked immutable evidence, inspect all coverage and retrieval limitations,
and construct a bounded context with separately labelled source and metadata.
It must budget model input, validate generated claim-to-source citations, treat
retrieved text as untrusted, and distinguish retrieved evidence from generated
conclusions. Keyword retrieval neither answers a question nor proves feature
availability. There is no API/persistence blocker to that integration, but the
measured recall limitations must shape answerability and further evaluation.
