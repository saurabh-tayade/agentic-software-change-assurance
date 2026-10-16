# Java/Markdown parsing and evidence chunking

M1.3 implements `dev.changeassurance.processing` inside the existing modular
monolith. It consumes [M1.2 ingestion](git-snapshot-ingestion.md), parses Java and
Markdown, and emits deterministic evidence chunks with original source provenance.
It introduces no endpoint, startup scan, persistence, retrieval, embeddings, LLM,
Q&A, UI, or change-impact analysis. ADR-0001 through ADR-0003 govern this work;
parser libraries and the line-based policy refine those decisions, not replace them.

## Integration and contracts

```java
import dev.changeassurance.processing.ArtifactProcessor;
import dev.changeassurance.processing.ChunkingConfiguration;

// ingestionResult is the unchanged result of GitSnapshotIngestor.ingest(selection).
var result = new ArtifactProcessor().process(
        ingestionResult, new ChunkingConfiguration(80, 10_000));
var chunks = result.chunks();
var statistics = result.statistics();
```

`ArtifactProcessor` is also a Spring component. The one-argument `process` method
uses the same defaults. Configuration is explicit per call, immutable, and recorded
in the result. No repository path or filesystem reader is accepted by processing.
The M1.2 production contracts and byte/selection policies are unchanged.

The pipeline is ingestion result -> format-specific syntax tree -> disjoint source
regions -> shared chunker -> normalized chunks and processing outcomes. Parser
classes, source-line indexing, and intermediate regions are package-private; there
is no registry, plugin framework, or speculative interface. A parser is created per
artifact, avoiding mutable global parser settings or shared parser state.

`EvidenceChunk` carries:

- `id`: deterministic SHA-256 identifier.
- `snapshot`: the original repository identity, resolved commit SHA, selected scope,
  and ingestion policy version.
- `filePath`, `artifactType`, `sourceFormat`: original path and classification;
  classification values are strings so the core evidence contract needs no Java
  symbol hierarchy or closed set of future languages.
- `gitBlobId`, `sourceHash`: unchanged original blob identity and SHA-256 digest.
- `startLine`, `endLine`: inclusive, one-based physical lines in the committed file.
- `content`, `contentHash`: original source slice and its UTF-8 SHA-256 digest.
- `context`: optional ordered declaration or heading labels, separate from source.
- `parser`, `chunkingConfiguration`: pinned derivation identities.

All output collections are immutable. No Java AST objects escape into the evidence
model. Python, TypeScript, OpenAPI, SQL, and event schemas could supply the same
source-region contract later; they are not implemented.

## Parser choices and scope

### Java

Use **JavaParser core 3.27.1**, explicitly configured for `JAVA_21`, rather than its
default language level. The pinned
[configuration](https://github.com/javaparser/javaparser/blob/javaparser-parent-3.27.1/javaparser-core/src/main/java/com/github/javaparser/ParserConfiguration.java)
provides a Java 21 validator. Tests verify record patterns, pattern matching in
switch, guarded cases, null/default cases, sealed types, and text blocks, as well
as the actual Parcel Quotes fixtures. JavaParser has an established AST and source
range API; no custom grammar or regex Java parser is needed.

Only `javaparser-core` is added: no symbol solver, classpath scanner, compiler, or
transitive runtime libraries. JDK compiler trees would avoid a dependency but tie
runtime parsing to compiler availability and require additional comment/source
handling. Eclipse JDT would add a broader compiler/tooling surface. JavaParser's
standalone syntax tree and attached comments fit this milestone's narrower needs.
Dependency updates must rerun the syntax and coordinate regression tests and change
the recorded parser identity. Versions are explicit because Boot does not manage
these parser dependencies.

Regions preserve package/import declarations, types (classes, interfaces, records,
enums, annotation types), direct members, constructors including compact record
constructors, annotations, attached comments, JavaDoc, and nested member types.
Type headers, inter-member gaps, and closing braces remain source evidence too.
Callables carry their enclosing type chain and signature. Local/anonymous classes
remain inside their containing method or field; they are not separately indexed.
Enum constants remain in their enclosing enum region. Modules retain their source
as a single declaration. There is no symbol resolution, type checking, compilation,
framework interpretation, dependency analysis, or claim that a test passed.

Declarations sharing physical lines are grouped to prevent overlapping quotations.
Their context contains the enclosing labels followed by the grouped declaration
labels; it is navigation metadata, not a resolved symbol identity. The chunker never
pretty-prints an AST. Unsupported/malformed Java with any parser problem emits no
chunks, even when JavaParser returns a recovered partial tree. Diagnostics are
bounded to five messages and 2,000 characters. Preview/later-Java syntax is outside
the supported contract; parser validation is not a complete Java compiler.

Java Unicode escape candidates (a backslash immediately followed by `u`) are
conservatively rejected with `UNSUPPORTED_CONSTRUCT`, including candidates in string
literals/comments. Escape translation can change logical lines, and that mapping
is not supported by this policy. A leading UTF-8 BOM is replaced with one space
only in the Java parser input; the original BOM remains in quoted content. Tests
verify line accuracy with BOM, tabs, Unicode, CRLF, CR, and missing final newlines.

### Markdown

Use **CommonMark Java 0.24.0 core**, with block source spans enabled. Its established
[block/source-span API](https://github.com/commonmark/commonmark-java#source-positions)
provides structural boundaries without hand-written fence/heading recognition.
It adds no transitive runtime libraries or optional extensions. The
[0.24.0 release](https://github.com/commonmark/commonmark-java/releases/tag/commonmark-parent-0.24.0)
includes source-position improvements. Larger Markdown processors and extension
bundles are unnecessary for the current corpus.

Document-level ATX and Setext headings establish a hierarchy by level. A section
runs from its heading through the line before the next document-level heading;
child sections carry ancestor heading labels without copying ancestor source text.
Paragraphs, lists, block quotes, indented code, fenced code, and other core blocks
supply safe split points. Heading-like text in code fences is never a section.
An unclosed fence is a valid CommonMark block through EOF, not a parse failure.
Empty files produce zero chunks; heading-free text remains a document region.

Only document-level headings create sections: headings inside lists or block quotes
remain inside those blocks. HTML is retained as source, never executed/rendered.
Tables, task lists, YAML front matter, directives, and other Markdown extensions
receive core CommonMark semantics, not extension-specific structure. This scope is
reported in `processingLimitations`; `COMPLETE` means complete within that scope.
A leading BOM is omitted only from Markdown parser input, without shifting lines.

## Chunking policy and resource bounds

`source-lines-v1` measures **physical source lines**, not tokens or characters.
Defaults are 80 lines per chunk and 10,000 chunks per processing result. Allowed
configuration ranges are 1–10,000 lines and 1–100,000 chunks. This needs no tokenizer
and has exact citation boundaries. One long line can still be up to the 1 MiB
M1.2 file limit; this is not a model-context-size guarantee. Future token budgeting
must be an explicit new strategy with its own provenance policy.

Java methods/direct declarations and Markdown sections remain intact when they
fit. Types are partitioned into their header, members, and intervening source.
No packing across unrelated regions or content overlap is introduced. Every physical
line, including comments and blank gaps, belongs to exactly one emitted chunk when
coverage is complete; concatenating chunks for a file reproduces its original text.

Oversized regions split greedily at the last available structural boundary within
the configured line window. Markdown uses both sides of top-level blocks; Java
callables use both sides of immediate statements. Thus a fitting fence, list, or
statement can move into the next chunk intact. When no such boundary fits, splitting
falls back to physical lines. An oversized fence is not repaired with synthesized
fence markers. Split fragments retain their original context and produce a
`SPLIT_ELEMENT` limitation and `PARTIAL` artifact status, even if all source lines
were emitted. No claim is made that fragments are independently valid syntax.

Input processing rechecks M1.2's 10,000-entry, 1 MiB/file, and 16 MiB aggregate byte
bounds. Snapshot equality and source SHA-256 are checked before parsing. The Git
blob digest remains the ingestor's verified metadata; processing does not access
Git. Duplicate input artifact paths are invalid caller input. The chunk budget is
shared across artifacts sorted by exact path; exhaustion emits an explicit
limitation for remaining content and failed outcomes for subsequent nonempty
artifacts. No unbounded chunk list is generated. Parser runtime failures and stack
exhaustion are surfaced per artifact. Parsing is in-process, with no hard CPU
sandbox or wall-clock deadline; extreme nesting may exceed JVM stack capacity and
is reported as a resource failure. JVM out-of-memory errors are not swallowed.

## Provenance and deterministic identity

`SourceLines` indexes offsets into the original string; LF, CRLF, and CR terminate
one physical line each. A terminal newline does not create an invented extra line.
Slices include original line terminators, BOM, whitespace, and final-newline state.
There is no reformatting, text normalization, added context, or noncontiguous
concatenation inside a chunk. Bounds and complete region coverage are checked
before any chunks for an artifact are published.

Context labels may refer to declarations/headings outside the chunk's source range.
They are explicitly metadata, never part of `content` or its content digest. They
must not be presented as quotations at the chunk's line range. Labels and parser
diagnostics are untrusted source-derived data, not instructions.

IDs hash these fields in order using SHA-256: repository identity, resolved commit,
relative path, artifact type, format, original source SHA-256, start line, end line,
chunk content SHA-256, parser identity, configuration identity, then each context
label. Each field is UTF-8 encoded and prefixed with its four-byte big-endian byte
length, avoiding delimiter collisions. Configuration identity includes strategy
version, max lines, and max chunks. Snapshot scope is retained but does not affect
a chunk's identity when the same source span is selected through another scope.
There are no random values, timestamps, machine paths, or runtime durations in
output. Repeated processing produces equal result records for the same input and
configuration under the same supported runtime conditions.

## Outcomes and coverage

Each artifact has path, parser, status, parsed flag, chunk count, and typed
limitations. Aggregate statistics count input artifacts, successful parses,
complete/partial/failed artifacts, and chunks. Successfully parsed empty files count
as parsed and complete, with zero chunks. Invalid configuration/inventory raises
`IllegalArgumentException`; expected source failures become artifact outcomes.

| Status | Artifact | Overall result |
| --- | --- | --- |
| COMPLETE | All supported source processed without splitting/omission | All selected supported artifacts complete; no nonintentional ingestion gaps |
| PARTIAL | Trustworthy chunks emitted with split or resource limitations | At least one artifact parsed, but processing or ingestion gaps remain |
| FAILED | No trustworthy processing result for the artifact | Gaps exist and no artifact parsed successfully |

Original ingestion coverage, limitations, summary, and snapshot are preserved
unchanged. Failed/unsupported ingestion and size-budget exclusions degrade aggregate
status; deliberate scope/generated-directory exclusions remain visible but do not
prevent complete processing of the selected scope. Empty selections with only
intentional exclusions are complete. General parser-scope limitations are separate
from per-artifact failures and always accompany the result.

## Verification and M1.4 handoff

Unit tests cover syntax, exact ranges/content hashes, semantic boundaries, oversized
regions, configuration, budget exhaustion, stable ordering/IDs, and failures.
Integration tests process real M1.2 output from temporary historical Git commits,
ignore dirty/deleted working-tree content, and preserve test classification and
coverage. They map all 10 original evidence references across the seven unchanged
M1.1 scenarios through chunks, including references crossing chunk boundaries.
This verifies source location validity, not retrieval relevance or answer support.

M1.4 can persist snapshots, artifact identities/digests, chunks, derivation
configuration, and coverage/outcomes directly. Indexable metadata includes repository,
commit, path, type/format, line range, context, parser/configuration, and hashes.
Keep source content separate from contextual metadata, retain failures/coverage,
and use the deterministic ID for idempotence within a derivation configuration.
Scope/run membership needs its own association because chunk identity is independent
of scope. No production contract blocker is known; database schemas, migrations,
transactions, indexing, retention, and reprocessing policy remain M1.4 decisions.
Database persistence, keyword/vector search, embeddings, grounded Q&A, Repository
Intelligence UI, and change-impact analysis remain **not implemented**.
