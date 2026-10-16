package dev.changeassurance.processing;

import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.RepositorySelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import static dev.changeassurance.ingestion.IngestionResult.*;
import static dev.changeassurance.processing.ProcessingResult.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactProcessorTests {
    private final ArtifactProcessor processor = new ArtifactProcessor();
    private final Snapshot snapshot = new Snapshot("test-repository", "a".repeat(40),
            RepositorySelection.Scope.all(), "git-snapshot-v1");

    @Test
    void preservesJavaDeclarationsAnnotationsCommentsAndEnclosingSymbols() throws Exception {
        String source = """
                package example;
                import java.util.List;
                /** Service docs. */
                @Deprecated
                public class Service {
                    private int count;
                    /** Constructor docs. */
                    Service() {
                        count = 1;
                    }
                    // Method comment.
                    @Deprecated
                    int createOrder() {
                        return count;
                    }
                    interface Nested {
                        void run();
                    }
                    record Value(int value) {
                        Value { if (value < 0) throw new IllegalArgumentException(); }
                    }
                    enum State { NEW, DONE }
                }
                """;
        var result = process(Format.JAVA, source, 80);
        assertEquals(COMPLETE, result.status(), result.toString());
        assertSource(source, result);
        var method = result.chunks().stream().filter(c -> c.context().contains("MethodDeclaration: createOrder()"))
                .findFirst().orElseThrow();
        assertEquals(11, method.startLine());
        assertEquals(15, method.endLine());
        assertEquals("    // Method comment.\n    @Deprecated\n    int createOrder() {\n        return count;\n    }\n", method.content());
        assertTrue(method.context().contains("ClassOrInterfaceDeclaration: Service"));
        assertTrue(result.chunks().stream().anyMatch(c -> c.content().contains("/** Constructor docs. */")
                && c.context().contains("ConstructorDeclaration: Service()")));
        for (String kind : List.of("PackageDeclaration", "ImportDeclaration", "RecordDeclaration: Value",
                "CompactConstructorDeclaration: Value", "EnumDeclaration: State", "ClassOrInterfaceDeclaration: Nested")) {
            assertTrue(result.chunks().stream().anyMatch(c -> c.context().contains(kind)), kind);
        }
    }

    @Test
    void supportsJava21PatternsRecordsSealedTypesAndTextBlocks() throws Exception {
        String source = """
                sealed interface Shape permits Point {}
                record Point(int x, int y) implements Shape {}
                class Patterns {
                    int classify(Object value) {
                        return switch (value) {
                            case Point(int x, int y) when x > 0 -> x + y;
                            case String s -> s.length();
                            case null, default -> 0;
                        };
                    }
                    boolean positive(Object value) {
                        return value instanceof Point(int x, int y) && x > 0;
                    }
                    String text = \"\"\"
                            # Not Markdown
                            line two
                            \"\"\";
                }
                """;
        var result = process(Format.JAVA, source, 80);
        assertEquals(COMPLETE, result.status(), result.toString());
        assertSource(source, result);
        assertEquals(1, result.chunks().stream().filter(c -> c.context().contains("MethodDeclaration: classify(Object)")).count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "// only a comment\n", "/** only docs */", "\ufeffclass A {}\n",
            "class A { void a() {} void b() {} }", "@interface Marker { String value(); }",
            "module example { requires java.base; }"})
    void handlesEmptyCommentOnlyBomAnnotationModuleAndSameLineJava(String source) throws Exception {
        var result = process(Format.JAVA, source, 80);
        assertEquals(COMPLETE, result.status(), result.toString());
        assertSource(source, result);
        assertEquals(1, result.statistics().parsed());
        if (source.isEmpty()) {
            assertTrue(result.chunks().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"class Broken { void run( }", "class A { int x = ; }",
            "void main() { System.out.println(1); }", "class A { int _ = 1; }"})
    void rejectsMalformedOrUnsupportedJavaWithoutRecoveredEvidence(String source) {
        var result = process(Format.JAVA, source, 80);
        assertEquals(FAILED, result.status());
        assertTrue(result.chunks().isEmpty());
        assertFalse(result.artifacts().getFirst().parsed());
        assertEquals(ProcessingResult.Reason.PARSE_FAILURE, result.artifacts().getFirst().limitations().getFirst().reason());
    }

    @Test
    void rejectsUnicodeEscapeCandidatesExplicitly() {
        var result = process(Format.JAVA, "class A { // " + "\\" + "u000a hidden\n}\n", 80);
        assertEquals(FAILED, result.status());
        assertTrue(result.chunks().isEmpty());
        assertEquals(ProcessingResult.Reason.UNSUPPORTED_CONSTRUCT,
                result.artifacts().getFirst().limitations().getFirst().reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void mapsOriginalPhysicalLinesAndRetainsLineTerminators(String newline) throws Exception {
        String source = String.join(newline, "// 😀 café", "class A {", "\tvoid run() {", "\t}", "}");
        var result = process(Format.JAVA, source, 80);
        assertEquals(COMPLETE, result.status(), result.toString());
        assertSource(source, result);
        var method = result.chunks().stream().filter(c -> c.context().contains("MethodDeclaration: run()"))
                .findFirst().orElseThrow();
        assertEquals(3, method.startLine());
        assertEquals(4, method.endLine());
        assertEquals("\tvoid run() {" + newline + "\t}" + newline, method.content());
    }

    @Test
    void preservesMarkdownHierarchyListsParagraphsAndFences() throws Exception {
        String source = """
                Preface without a heading.

                # Architecture
                Overview paragraph.

                ## Configuration
                - One
                - Two

                ```java
                # Not a heading
                class A {}
                ```

                ### Retry
                Retry text.

                ## Failures
                Failure text.
                """;
        var result = process(Format.MARKDOWN, source, 80);
        assertEquals(COMPLETE, result.status());
        assertSource(source, result);
        assertEquals(5, result.chunks().size());
        var configuration = result.chunks().get(2);
        assertEquals(List.of("# Architecture", "## Configuration"), configuration.context());
        assertTrue(configuration.content().contains("```java\n# Not a heading\nclass A {}\n```"));
        assertEquals(List.of("# Architecture", "## Configuration", "### Retry"), result.chunks().get(3).context());
        assertEquals(List.of("# Architecture", "## Failures"), result.chunks().get(4).context());
        assertEquals(6, configuration.startLine());
        assertEquals(14, configuration.endLine());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "Paragraph without heading.\n\n- First\n- Second\n",
            "Title\n=====\n\nBody\n", "\ufeff# BOM heading\r\nBody", "# First\rBody\n## Second\r\nText",
            "> # Quoted heading\n> quoted text\n\n    # indented code\n", "```\n# unclosed fence\n"})
    void handlesCommonMarkEdgeCasesWithoutReformatting(String source) throws Exception {
        var result = process(Format.MARKDOWN, source, 80);
        assertEquals(COMPLETE, result.status(), result.toString());
        assertSource(source, result);
    }

    @Test
    void splitsOversizedSectionsAtBlocksAndKeepsFittingFencesIntact() throws Exception {
        String source = "# Guide\n\nParagraph.\n\n```java\n# not a heading\nclass A {}\n```\n\nAfter.\n";
        var result = process(Format.MARKDOWN, source, 5);
        assertEquals(PARTIAL, result.status());
        assertSource(source, result);
        assertTrue(result.chunks().stream().anyMatch(c -> c.content().contains("```java\n# not a heading\nclass A {}\n```")));
        assertTrue(result.chunks().stream().allMatch(c -> c.context().equals(List.of("# Guide"))));
        assertTrue(result.chunks().stream().allMatch(c -> c.endLine() - c.startLine() + 1 <= 5));
    }

    @Test
    void splitsOversizedMethodsAndFencesWithVisibleReducedGranularity() throws Exception {
        for (Format format : Format.values()) {
            String source = format == Format.JAVA
                    ? "class A {\n    void run() {\n" + "        System.out.println(1);\n".repeat(15) + "    }\n}\n"
                    : "# Guide\n```\n" + "code\n".repeat(15) + "```\n";
            var result = process(format, source, 4);
            assertEquals(PARTIAL, result.status());
            assertSource(source, result);
            assertTrue(result.chunks().stream().allMatch(c -> c.endLine() - c.startLine() + 1 <= 4));
            assertTrue(result.artifacts().getFirst().limitations().stream()
                    .anyMatch(l -> l.reason() == ProcessingResult.Reason.SPLIT_ELEMENT));
            if (format == Format.JAVA) {
                assertTrue(result.chunks().stream().filter(c -> c.content().contains("println"))
                        .allMatch(c -> c.context().contains("MethodDeclaration: run()")));
            }
        }
    }

    @Test
    void ordersByPathAndMakesIdsSensitiveToIdentityContentAndConfiguration() {
        var a = artifact("a.md", Format.MARKDOWN, "# A\n");
        var b = artifact("b.md", Format.MARKDOWN, "# B\n");
        var result = processor.process(input(List.of(b, a)));
        assertEquals(result, processor.process(input(List.of(a, b))));
        assertEquals(List.of("a.md", "b.md"), result.chunks().stream().map(EvidenceChunk::filePath).toList());
        String id = result.chunks().getFirst().id();
        assertEquals(64, id.length());
        assertNotEquals(id, processor.process(input(List.of(a)), new ChunkingConfiguration(81, 10_000)).chunks().getFirst().id());
        assertNotEquals(id, processor.process(input(List.of(artifact("a.md", Format.MARKDOWN, "# Changed\n")))).chunks().getFirst().id());
        for (Snapshot other : List.of(new Snapshot("other", snapshot.commitSha(), snapshot.scope(), snapshot.policyVersion()),
                new Snapshot(snapshot.repositoryId(), "b".repeat(40), snapshot.scope(), snapshot.policyVersion()))) {
            var changed = new SourceArtifact(other, a.path(), a.type(), a.format(), a.gitBlobId(), a.sha256(), a.content());
            assertNotEquals(id, processor.process(new IngestionResult(other, List.of(changed), List.of(), List.of()))
                    .chunks().getFirst().id());
        }
        assertThrows(UnsupportedOperationException.class, () -> result.chunks().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.chunks().getFirst().context().clear());
    }

    @Test
    void carriesIngestionCoverageAndSeparatesFailedPartialAndEmptyCompleteResults() {
        var entries = List.of(new CoverageEntry("broken.java", Disposition.FAILED,
                        IngestionResult.Reason.OBJECT_READ_FAILED, "Missing blob"),
                new CoverageEntry("schema.sql", Disposition.UNSUPPORTED, IngestionResult.Reason.FILE_TYPE, "Not supported"),
                new CoverageEntry("large.md", Disposition.EXCLUDED, IngestionResult.Reason.FILE_SIZE_LIMIT, "Too big"));
        var input = new IngestionResult(snapshot, List.of(artifact("ok.md", Format.MARKDOWN, "OK")), entries,
                List.of("Committed objects only"));
        var result = processor.process(input);
        assertEquals(PARTIAL, result.status());
        assertEquals(entries, result.ingestionCoverage());
        assertEquals(input.limitations(), result.ingestionLimitations());
        assertEquals(input.summary(), result.ingestionSummary());
        assertEquals(FAILED, processor.process(new IngestionResult(snapshot, List.of(), entries, List.of())).status());
        assertEquals(COMPLETE, processor.process(input(List.of())).status());
        var excluded = new CoverageEntry("outside.md", Disposition.EXCLUDED, IngestionResult.Reason.OUTSIDE_SCOPE, "Outside");
        assertEquals(COMPLETE, processor.process(new IngestionResult(snapshot, List.of(), List.of(excluded), List.of())).status());
        var mixed = processor.process(input(List.of(artifact("bad.java", Format.JAVA, "class {"),
                artifact("ok.md", Format.MARKDOWN, "OK"))));
        assertEquals(PARTIAL, mixed.status());
        assertEquals(new ProcessingResult.Statistics(2, 1, 1, 0, 1, 1), mixed.statistics());
    }

    @Test
    void enforcesChunkBudgetAcrossArtifactsAndReportsEveryUnprocessedArtifact() {
        var result = processor.process(input(List.of(artifact("a.md", Format.MARKDOWN, "line\n".repeat(8)),
                artifact("b.md", Format.MARKDOWN, "Later"))), new ChunkingConfiguration(2, 2));
        assertEquals(PARTIAL, result.status());
        assertEquals(2, result.chunks().size());
        assertEquals(PARTIAL, result.artifacts().getFirst().status());
        assertEquals(FAILED, result.artifacts().getLast().status());
        assertTrue(result.artifacts().stream().allMatch(a -> a.limitations().stream()
                .anyMatch(l -> l.reason() == ProcessingResult.Reason.RESOURCE_LIMIT)));
        assertEquals(result, processor.process(input(List.of(artifact("a.md", Format.MARKDOWN, "line\n".repeat(8)),
                artifact("b.md", Format.MARKDOWN, "Later"))), new ChunkingConfiguration(2, 2)));
    }

    @Test
    void rejectsCorruptInputAndInvalidConfiguration() {
        var a = artifact("a.md", Format.MARKDOWN, "Original");
        var corrupt = new SourceArtifact(snapshot, a.path(), a.type(), a.format(), a.gitBlobId(), a.sha256(), "Changed");
        var result = processor.process(input(List.of(corrupt)));
        assertEquals(FAILED, result.status());
        assertEquals(ProcessingResult.Reason.INTEGRITY_FAILURE, result.artifacts().getFirst().limitations().getFirst().reason());
        assertTrue(result.chunks().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ChunkingConfiguration(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new ChunkingConfiguration(10, 100_001));
        assertThrows(IllegalArgumentException.class, () -> processor.process(input(List.of(a, a))));
        var huge = processor.process(input(List.of(artifact("huge.md", Format.MARKDOWN, "x".repeat(1024 * 1024 + 1)))));
        assertEquals(FAILED, huge.status());
        assertEquals(ProcessingResult.Reason.RESOURCE_LIMIT, huge.artifacts().getFirst().limitations().getFirst().reason());
    }

    @Test
    void preservesFittingStatementsInsideAnOversizedMethod() throws Exception {
        String source = "class A {\n    void run() {\n        int x = 1;\n"
                + "        if (x > 0) {\n            x++;\n            x++;\n        }\n"
                + "        System.out.println(x);\n    }\n}\n";
        var result = process(Format.JAVA, source, 4);
        assertEquals(PARTIAL, result.status());
        assertSource(source, result);
        assertTrue(result.chunks().stream().anyMatch(c -> c.startLine() == 4 && c.endLine() == 7));
    }

    @Test
    void lineLimitDoesNotTruncateLongLinesOrInventTerminalEmptyLines() throws Exception {
        for (String source : List.of("long ".repeat(20_000), "line\n", "line\n\n", "\r\n\r\n")) {
            var result = process(Format.MARKDOWN, source, 1);
            assertSource(source, result);
            assertTrue(result.chunks().stream().allMatch(c -> c.startLine() == c.endLine()));
            assertFalse(result.processingLimitations().isEmpty());
        }
    }

    private ProcessingResult process(Format format, String source, int maxLines) {
        return processor.process(input(List.of(artifact(format == Format.JAVA ? "Example.java" : "Guide.md", format, source))),
                new ChunkingConfiguration(maxLines, 10_000));
    }

    private IngestionResult input(List<SourceArtifact> artifacts) {
        return new IngestionResult(snapshot, artifacts, List.of(), List.of());
    }

    private SourceArtifact artifact(String path, Format format, String source) {
        return new SourceArtifact(snapshot, path, format == Format.JAVA ? ArtifactType.SOURCE : ArtifactType.DOCUMENTATION,
                format, "b".repeat(40), EvidenceChunker.hash(source), source);
    }

    private void assertSource(String source, ProcessingResult result) throws Exception {
        var physicalLines = Pattern.compile(".*?(?:\\r\\n|\\r|\\n|\\z)", Pattern.DOTALL).matcher(source)
                .results().map(match -> match.group()).filter(s -> !s.isEmpty()).toList();
        assertEquals(source, result.chunks().stream().map(EvidenceChunk::content).reduce("", String::concat));
        int next = 1;
        var ids = new ArrayList<String>();
        for (var chunk : result.chunks()) {
            assertEquals(next, chunk.startLine());
            assertEquals(String.join("", physicalLines.subList(chunk.startLine() - 1, chunk.endLine())), chunk.content());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(chunk.content().getBytes(StandardCharsets.UTF_8))), chunk.contentHash());
            assertEquals(snapshot, chunk.snapshot());
            assertEquals(EvidenceChunker.hash(source), chunk.sourceHash());
            assertFalse(ids.contains(chunk.id()));
            ids.add(chunk.id());
            next = chunk.endLine() + 1;
        }
    }
}
