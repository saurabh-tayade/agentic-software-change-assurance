package dev.changeassurance.ingestion;

import dev.changeassurance.processing.ArtifactProcessor;
import dev.changeassurance.processing.ChunkingConfiguration;
import dev.changeassurance.processing.EvidenceChunk;
import dev.changeassurance.processing.ProcessingResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactProcessingIntegrationTests {
    @TempDir
    Path repository;
    private final ArtifactProcessor processor = new ArtifactProcessor();
    private final GitSnapshotIngestor ingestor = new GitSnapshotIngestor();

    @Test
    void processesHistoricalCommittedBytesDespiteDirtyAndMissingWorkingTreeFiles() throws Exception {
        initialize();
        String java = "package example;\r\nclass Service {\r\n    int run() {\r\n        return 1;\r\n    }\r\n}\r\n";
        String markdown = "# Original\r\n\r\nCommitted café 😀.\r\n";
        write("src/Service.java", java);
        write("tests/ServiceTest.java", "class ServiceTest {\n    @Test void testRun() {}\n}\n");
        write("README.md", markdown);
        write("unsupported.sql", "select 1;");
        write("target/generated.md", "excluded");
        commit();
        String old = git("rev-parse", "HEAD").strip();
        write("src/Service.java", "class Service { int changed; }\n");
        commit();
        write("README.md", "# Staged\n");
        git("add", "README.md");
        write("README.md", "# Dirty\n");
        write("untracked.md", "Not evidence");
        Files.delete(repository.resolve("src/Service.java"));
        var ingestion = ingestor.ingest(new RepositorySelection(repository, "historical-fixture", old));
        var result = processor.process(ingestion);
        assertEquals(result, processor.process(ingestor.ingest(new RepositorySelection(repository, "historical-fixture", old))));
        assertEquals(ProcessingResult.Status.PARTIAL, result.status()); // SQL remains unsupported coverage.
        assertEquals(3, result.statistics().parsed());
        assertEquals(ingestion.coverage(), result.ingestionCoverage());
        assertEquals(ingestion.summary(), result.ingestionSummary());
        assertTrue(result.chunks().stream().anyMatch(c -> c.artifactType().equals("TEST")));
        for (var artifact : ingestion.artifacts()) {
            var chunks = result.chunks().stream().filter(c -> c.filePath().equals(artifact.path())).toList();
            assertEquals(artifact.content(), chunks.stream().map(EvidenceChunk::content).reduce("", String::concat));
            String committed = git("show", old + ":" + artifact.path());
            assertEquals(committed, artifact.content());
            for (var chunk : chunks) {
                assertEquals(old, chunk.snapshot().commitSha());
                assertEquals("historical-fixture", chunk.snapshot().repositoryId());
                assertEquals(artifact.gitBlobId(), chunk.gitBlobId());
                assertEquals(artifact.sha256(), chunk.sourceHash());
                assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(chunk.content().getBytes(StandardCharsets.UTF_8))), chunk.contentHash());
                assertEquals(committed.lines().skip(chunk.startLine() - 1).limit(chunk.endLine() - chunk.startLine() + 1).toList(),
                        chunk.content().lines().toList());
            }
        }
        assertEquals(markdown, result.chunks().stream().filter(c -> c.filePath().equals("README.md"))
                .map(EvidenceChunk::content).reduce("", String::concat));
        assertEquals(java, result.chunks().stream().filter(c -> c.filePath().equals("src/Service.java"))
                .map(EvidenceChunk::content).reduce("", String::concat));
    }

    @Test
    void mapsEveryEvaluationGroundTruthSpanToOriginalEvidenceWithoutChangingFixtures() throws IOException {
        initialize();
        Path corpus = Path.of("evaluation/corpora/parcel-quotes/v1");
        try (var files = Files.walk(corpus)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path destination = repository.resolve(corpus.relativize(file));
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination);
            }
        }
        commit();
        var ingestion = ingestor.ingest(new RepositorySelection(repository, "parcel-quotes-v1", "HEAD"));
        var result = processor.process(ingestion, new ChunkingConfiguration(4, 10_000));
        assertEquals(5, result.statistics().parsed());
        assertEquals(0, result.statistics().failed());
        var suite = new ObjectMapper().readTree(Files.readString(Path.of("evaluation/scenarios/parcel-quotes-v1.json")));
        int references = 0;
        for (var scenario : suite.get("scenarios")) {
            for (var reference : scenario.get("expected_evidence")) {
                String path = reference.get("path").asString();
                int start = reference.get("start_line").asInt();
                int end = reference.get("end_line").asInt();
                var original = ingestion.artifacts().stream().filter(a -> a.path().equals(path)).findFirst().orElseThrow();
                var expected = original.content().lines().skip(start - 1).limit(end - start + 1).toList();
                var reconstructed = new ArrayList<String>();
                for (var chunk : result.chunks().stream().filter(c -> c.filePath().equals(path)
                        && c.endLine() >= start && c.startLine() <= end).toList()) {
                    int from = Math.max(start, chunk.startLine());
                    int to = Math.min(end, chunk.endLine());
                    reconstructed.addAll(chunk.content().lines().skip(from - chunk.startLine()).limit(to - from + 1).toList());
                    assertEquals(ingestion.snapshot(), chunk.snapshot());
                }
                assertEquals(expected, reconstructed, scenario.get("id").asString());
                assertTrue(String.join("\n", reconstructed).contains(reference.get("contains").asString()));
                references++;
            }
        }
        assertEquals(10, references);
        assertEquals(result, processor.process(ingestion, new ChunkingConfiguration(4, 10_000)));
    }

    private void initialize() {
        git("init", "-q", "--initial-branch=main", "--object-format=sha1");
    }

    private void write(String path, String content) throws IOException {
        Files.createDirectories(repository.resolve(path).getParent());
        Files.writeString(repository.resolve(path), content);
    }

    private void commit() {
        git("add", "--all");
        git("commit", "-q", "-m", "Controlled processing fixture");
    }

    private String git(String... arguments) {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Fixture Author",
                "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false",
                "-c", "core.hooksPath=/dev/null", "-c", "init.templateDir="));
        command.addAll(List.of(arguments));
        return new String(GitProcess.execute(new ProcessBuilder(command).directory(repository.toFile()),
                Duration.ofSeconds(10), 4 * 1024 * 1024), StandardCharsets.UTF_8);
    }
}
