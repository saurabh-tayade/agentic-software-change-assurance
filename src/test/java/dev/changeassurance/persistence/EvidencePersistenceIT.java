package dev.changeassurance.persistence;

import dev.changeassurance.ChangeAssuranceApplication;
import dev.changeassurance.ingestion.GitSnapshotIngestor;
import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.processing.ArtifactProcessor;
import dev.changeassurance.processing.ChunkingConfiguration;
import dev.changeassurance.processing.ProcessingResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static dev.changeassurance.ingestion.IngestionResult.*;
import static dev.changeassurance.persistence.PersistenceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class EvidencePersistenceIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withPassword(UUID.randomUUID().toString());
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static EvidencePersistenceService service;
    private static Flyway flyway;
    @TempDir Path temporary;

    @BeforeAll
    static void startDatabase() {
        // Deliberately fail, never skip, when Docker/PostgreSQL is unavailable.
        POSTGRES.start();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway = Flyway.configure().dataSource(dataSource).load();
        assertEquals(2, flyway.migrate().migrationsExecuted);
        service = new EvidencePersistenceService(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clearFixtures() {
        jdbc.execute("TRUNCATE publication_chunk, processing_publication, evidence_chunk, source_artifact, repository_snapshot");
    }

    @Test
    void migrationsInitializeConstraintsAndAreRepeatable() {
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'", Integer.class));
        assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO publication_chunk VALUES (?, ?, ?, 0)",
                "a".repeat(64), "b".repeat(64), "c".repeat(64)));
        assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO repository_snapshot VALUES (?, ?, ?, '{}'::jsonb, ?)",
                "invalid", "repo", "a".repeat(40), "v1"));
    }

    @Test
    void roundTripsExactContentMetadataAndIdempotentMembership() {
        var input = input("a", RepositorySelection.Scope.all());
        var processing = process(input);
        var saved = service.publish(input, processing);
        assertEquals(input, saved.ingestion());
        assertEquals(processing, saved.processing());
        assertEquals(saved, service.publish(input, processing));
        assertEquals(saved, service.findPublication(saved.id()).orElseThrow());
        assertEquals(processing.chunks(), service.findChunks(saved.id()).orElseThrow());
        assertEquals(1, count("processing_publication"));
        assertEquals(2, count("source_artifact"));
        assertEquals(processing.chunks().size(), count("publication_chunk"));
        assertTrue(service.findPublication("0".repeat(64)).isEmpty());
        assertTrue(service.findChunks("0".repeat(64)).isEmpty());
    }

    @Test
    void separatesCommitsScopesConfigurationsAndParserDerivations() {
        var input = input("a", RepositorySelection.Scope.all());
        var p = process(input);
        var original = service.publish(input, p);
        var otherCommit = input("b", RepositorySelection.Scope.all());
        var commit = service.publish(otherCommit, process(otherCommit));
        var scoped = new IngestionResult(snapshot("a", new RepositorySelection.Scope(List.of("src"), List.of())),
                List.of(), List.of(), List.of());
        var source = input.artifacts().getFirst();
        scoped = new IngestionResult(scoped.snapshot(), List.of(source(scoped.snapshot(), source.path(), source.format(), source.content())),
                List.of(new CoverageEntry("docs/guide.md", Disposition.EXCLUDED, IngestionResult.Reason.OUTSIDE_SCOPE, "Outside src")), input.limitations());
        var scope = service.publish(scoped, process(scoped));
        var configuration = service.publish(input, new ArtifactProcessor().process(input, new ChunkingConfiguration(3, 10_000)));
        var parser = service.publish(input, withParser(p, "future-parser-test"));
        assertEquals(5, java.util.Set.of(original.id(), commit.id(), scope.id(), configuration.id(), parser.id()).size());
        assertEquals(3, count("repository_snapshot"));
        assertEquals(5, count("source_artifact"));
        var shared = p.chunks().stream().filter(c -> c.filePath().equals("src/A.java")).map(c -> c.id()).toList();
        assertEquals(shared, scope.processing().chunks().stream().map(c -> c.id()).toList());
        assertEquals(scope, service.findPublication(scope.id()).orElseThrow());
        assertEquals(original, service.findPublication(original.id()).orElseThrow());
    }

    @Test
    void persistsPartialFailedEmptyAndIngestionCoverage() {
        var snapshot = snapshot("a", RepositorySelection.Scope.all());
        var coverage = List.of(new CoverageEntry("missing.java", Disposition.FAILED, IngestionResult.Reason.OBJECT_READ_FAILED, "Missing blob"),
                new CoverageEntry("schema.sql", Disposition.UNSUPPORTED, IngestionResult.Reason.FILE_TYPE, "Unsupported"),
                new CoverageEntry("large.md", Disposition.EXCLUDED, IngestionResult.Reason.FILE_SIZE_LIMIT, "Resource exclusion"),
                new CoverageEntry("target/generated.md", Disposition.EXCLUDED, IngestionResult.Reason.GENERATED_OR_DEPENDENCY_DIRECTORY, "Generated"));
        var partial = new IngestionResult(snapshot, List.of(source(snapshot, "good.md", Format.MARKDOWN, "# Good\n"),
                source(snapshot, "bad.java", Format.JAVA, "class {")), coverage, List.of("Ingestion limitations"));
        var partialResult = process(partial);
        assertEquals(ProcessingResult.Status.PARTIAL, partialResult.status());
        assertEquals(partialResult, service.publish(partial, partialResult).processing());
        var failedSnapshot = snapshot("b", RepositorySelection.Scope.all());
        var failed = new IngestionResult(failedSnapshot, List.of(source(failedSnapshot, "bad.java", Format.JAVA, "class {")), coverage, List.of());
        assertEquals(ProcessingResult.Status.FAILED, service.publish(failed, process(failed)).processing().status());
        var empty = new IngestionResult(snapshot("c", RepositorySelection.Scope.all()), List.of(), List.of(), List.of());
        var emptySaved = service.publish(empty, process(empty));
        assertEquals(ProcessingResult.Status.COMPLETE, emptySaved.processing().status());
        assertEquals(List.of(), service.findChunks(emptySaved.id()).orElseThrow());
        var coverageOnly = new IngestionResult(snapshot("d", RepositorySelection.Scope.all()), List.of(), coverage, List.of());
        assertEquals(ProcessingResult.Status.FAILED, service.publish(coverageOnly, process(coverageOnly)).processing().status());
    }

    @Test
    void rejectsConflictingPublicationAndExistingSourceWithoutOverwritingEvidence() {
        var input = input("a", RepositorySelection.Scope.all());
        var p = process(input);
        var original = service.publish(input, p);
        var changed = new ProcessingResult(p.snapshot(), p.configuration(), p.status(), p.artifacts(), p.chunks(),
                p.ingestionCoverage(), p.ingestionLimitations(), p.ingestionSummary(), List.of("Different immutable diagnostics"));
        assertThrows(EvidencePersistenceException.class, () -> service.publish(input, changed));
        var other = new IngestionResult(input.snapshot(), List.of(source(input.snapshot(), "src/A.java", Format.JAVA, "class A { int changed; }\n")),
                List.of(), input.limitations());
        var otherProcessing = withParser(process(other), "another-parser");
        assertThrows(EvidencePersistenceException.class, () -> service.publish(other, otherProcessing));
        assertEquals(1, count("processing_publication"));
        assertEquals(p.chunks().size(), count("evidence_chunk"));
        assertEquals(original, service.findPublication(original.id()).orElseThrow());
    }

    @Test
    void rollsBackAllWritesAfterDatabaseFailureDuringMembershipInsertion() {
        jdbc.execute("ALTER TABLE publication_chunk ADD CONSTRAINT fixture_failure CHECK (ordinal < 1)");
        try {
            var input = input("a", RepositorySelection.Scope.all());
            assertThrows(EvidencePersistenceException.class, () -> service.publish(input, process(input)));
            for (String table : List.of("repository_snapshot", "source_artifact", "evidence_chunk", "processing_publication", "publication_chunk")) {
                assertEquals(0, count(table), table);
            }
        } finally {
            jdbc.execute("ALTER TABLE publication_chunk DROP CONSTRAINT fixture_failure");
        }
    }

    @Test
    void concurrentIdenticalWritersPublishOnlyOnce() throws Exception {
        var input = input("a", RepositorySelection.Scope.all());
        var processing = process(input);
        try (var executor = Executors.newFixedThreadPool(4)) {
            Callable<PersistedPublication> publish = () -> service.publish(input, processing);
            var results = executor.invokeAll(List.of(publish, publish, publish, publish), 30, TimeUnit.SECONDS);
            var first = results.getFirst().get();
            for (var result : results) {
                assertEquals(first, result.get());
            }
        }
        assertEquals(1, count("processing_publication"));
        assertEquals(processing.chunks().size(), count("publication_chunk"));
    }

    @Test
    void persistenceProfileMigratesAndWiresTheService() {
        try (var context = new SpringApplicationBuilder(ChangeAssuranceApplication.class)
                .web(WebApplicationType.NONE).profiles("persistence")
                .run("--EVIDENCE_DB_URL=" + POSTGRES.getJdbcUrl(), "--EVIDENCE_DB_USER=" + POSTGRES.getUsername(),
                        "--EVIDENCE_DB_PASSWORD=" + POSTGRES.getPassword())) {
            assertNotNull(context.getBean(EvidencePersistenceService.class));
            assertNotNull(context.getBean(dev.changeassurance.retrieval.KeywordRetrievalService.class));
            assertEquals(2, context.getBean(Flyway.class).info().applied().length);
        }
    }

    @Test
    void endToEndCorpusRoundTripRetainsEveryEvaluationSourceReference() throws Exception {
        Path corpus = Path.of("evaluation/corpora/parcel-quotes/v1");
        try (var paths = Files.walk(corpus)) {
            for (Path source : paths.filter(Files::isRegularFile).toList()) {
                Path destination = temporary.resolve(corpus.relativize(source));
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
            }
        }
        git("init", "-q", "--initial-branch=main");
        git("add", "--all");
        git("commit", "-q", "-m", "Controlled corpus");
        var ingestion = new GitSnapshotIngestor().ingest(new RepositorySelection(temporary, "parcel-quotes-v1", "HEAD"));
        var p = new ArtifactProcessor().process(ingestion, new ChunkingConfiguration(4, 10_000));
        var saved = service.publish(ingestion, p);
        var read = service.findPublication(saved.id()).orElseThrow();
        assertEquals(ingestion, read.ingestion());
        assertEquals(p, read.processing());
        var suite = new JsonMapper().readTree(Files.readString(Path.of("evaluation/scenarios/parcel-quotes-v1.json")));
        int references = 0;
        for (var scenario : suite.get("scenarios")) {
            for (var ref : scenario.get("expected_evidence")) {
                String path = ref.get("path").asString();
                int start = ref.get("start_line").asInt();
                int end = ref.get("end_line").asInt();
                var lines = new ArrayList<String>();
                for (var chunk : read.processing().chunks()) {
                    if (chunk.filePath().equals(path) && chunk.startLine() <= end && chunk.endLine() >= start) {
                        int from = Math.max(start, chunk.startLine());
                        int to = Math.min(end, chunk.endLine());
                        lines.addAll(chunk.content().lines().skip(from - chunk.startLine()).limit(to - from + 1).toList());
                    }
                }
                var source = read.ingestion().artifacts().stream().filter(a -> a.path().equals(path)).findFirst().orElseThrow();
                assertEquals(source.content().lines().skip(start - 1).limit(end - start + 1).toList(), lines);
                assertTrue(String.join("\n", lines).contains(ref.get("contains").asString()));
                references++;
            }
        }
        assertEquals(10, references);
    }

    @Test
    void storesResourceLimitedPublicationAndRejectsInvalidInputBeforeWriting() {
        var input = input("a", RepositorySelection.Scope.all());
        var limited = new ArtifactProcessor().process(input, new ChunkingConfiguration(1, 1));
        assertEquals(ProcessingResult.Status.PARTIAL, limited.status());
        assertEquals(limited, service.publish(input, limited).processing());
        var other = input("b", RepositorySelection.Scope.all());
        assertThrows(IllegalArgumentException.class, () -> service.publish(other, limited));
        assertEquals(1, count("repository_snapshot"));
        assertEquals(1, count("processing_publication"));
    }

    @Test
    void detectsConflictingExistingChunkPayloadRatherThanTrustingItsId() {
        var input = input("a", RepositorySelection.Scope.all());
        var p = process(input);
        var saved = service.publish(input, p);
        jdbc.update("UPDATE evidence_chunk SET context = '[\"corrupted\"]'::jsonb WHERE snapshot_id = ? AND chunk_id = ?",
                saved.snapshotId(), p.chunks().getFirst().id());
        assertThrows(EvidencePersistenceException.class, () -> service.publish(input, p));
        assertEquals(1, count("processing_publication"));
    }

    @Test
    void rejectsMembershipAcrossScopesAndInvalidLineRanges() {
        var input = input("a", RepositorySelection.Scope.all());
        var saved = service.publish(input, process(input));
        var other = input("b", RepositorySelection.Scope.all());
        var different = service.publish(other, process(other));
        assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO publication_chunk VALUES (?, ?, ?, 999)",
                saved.id(), different.snapshotId(), different.processing().chunks().getFirst().id()));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE evidence_chunk SET start_line = 0 WHERE snapshot_id = ?",
                saved.snapshotId()));
        assertEquals(saved, service.findPublication(saved.id()).orElseThrow());
    }

    @Test
    void persistenceProfileFailsOnInvalidDatabaseCredentials() {
        assertThrows(RuntimeException.class, () -> {
            try (var ignored = new SpringApplicationBuilder(ChangeAssuranceApplication.class)
                    .web(WebApplicationType.NONE).profiles("persistence")
                    .run("--EVIDENCE_DB_URL=" + POSTGRES.getJdbcUrl(), "--EVIDENCE_DB_USER=" + POSTGRES.getUsername(),
                            "--EVIDENCE_DB_PASSWORD=" + UUID.randomUUID(),
                            "--spring.datasource.hikari.connection-timeout=250",
                            "--spring.datasource.hikari.initialization-fail-timeout=1")) {
                fail("Invalid database credentials must fail startup");
            }
        });
    }

    @Test
    void storesLongRepositoryPathsWithoutBtreeEntryOverflow() {
        var snapshot = snapshot("a", RepositorySelection.Scope.all());
        String path = "docs/" + "x".repeat(3000) + ".md";
        var input = new IngestionResult(snapshot, List.of(source(snapshot, path, Format.MARKDOWN, "Original")), List.of(), List.of());
        var p = process(input);
        assertEquals(p, service.publish(input, p).processing());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Fixture Author", "-c", "user.email=fixture@example.invalid",
                "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "-c", "init.templateDir="));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).directory(temporary.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Fixture Git timeout");
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
