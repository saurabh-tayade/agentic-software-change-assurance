package dev.changeassurance.persistence;

import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.processing.ArtifactProcessor;
import dev.changeassurance.processing.ChunkingConfiguration;
import dev.changeassurance.processing.ProcessingResult;
import dev.changeassurance.retrieval.KeywordRetrievalService;
import dev.changeassurance.retrieval.RetrievalException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static dev.changeassurance.ingestion.IngestionResult.*;
import static dev.changeassurance.persistence.PersistenceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class KeywordRetrievalIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withPassword(UUID.randomUUID().toString());
    private static JdbcTemplate jdbc;
    private static EvidencePersistenceService persistence;
    private static KeywordRetrievalService retrieval;

    @BeforeAll static void start() {
        POSTGRES.start();
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        persistence = new EvidencePersistenceService(ds);
        // Exercise upgrade with real V1 data, not just two migrations into an empty schema.
        assertEquals(1, Flyway.configure().dataSource(ds).target("1").load().migrate().migrationsExecuted);
        var input = fixture("a", RepositorySelection.Scope.all());
        var saved = persistence.publish(input, process(input));
        assertEquals(1, Flyway.configure().dataSource(ds).load().migrate().migrationsExecuted);
        retrieval = new KeywordRetrievalService(ds);
        assertFalse(retrieval.search(saved.id(), "ProductService", 10).orElseThrow().matches().isEmpty());
        assertEquals(saved, persistence.findPublication(saved.id()).orElseThrow());
    }

    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE publication_chunk, processing_publication, evidence_chunk, source_artifact, repository_snapshot");
    }

    static IngestionResult fixture(String commit, RepositorySelection.Scope scope) {
        var s = snapshot(commit, scope);
        var sources = List.of(
                source(s, "src/ProductService.java", Format.JAVA,
                        "package example;\r\n/** café */\r\nclass ProductService {\r\n"
                        + "    void retryFailedIngestion() {\r\n        String retry_count = \"résumé\";\r\n    }\r\n}\r\n"),
                source(s, "docs/FailureHandling.md", Format.MARKDOWN,
                        "# Failure Handling\nRetry failed ingestion safely.\n\n## Recovery\nManual repair.\n"),
                source(s, "docs/deployment-guide.md", Format.MARKDOWN, "# Operations\nNo environments supplied.\n"));
        if (!scope.includes().isEmpty()) sources = sources.stream().filter(a -> a.path().startsWith("src/")).toList();
        return new IngestionResult(s, sources, List.of(), List.of("Committed only"));
    }

    PersistedPublication publish() {
        var input = fixture("a", RepositorySelection.Scope.all());
        return persistence.publish(input, process(input));
    }

    @Test void searchesTechnicalTermsHeadingsPathsAndUnicodeWithoutChangingEvidence() {
        var p = publish();
        for (String query : List.of("ProductService", "product service", "ProductService.java", "retryFailedIngestion",
                "retry failed ingestion", "retry_count", "retry count", "example.ProductService", "failure handling",
                "deployment-guide.md", "CAFÉ", "résumé", "product service implementation")) {
            var result = retrieval.search(p.id(), query, 100).orElseThrow();
            assertFalse(result.matches().isEmpty(), query);
            assertEquals(p.id(), result.publication().id());
            assertEquals(PublicationMetadata.from(p.ingestion(), p.processing()), result.publication().metadata());
            assertEquals(query, result.query());
            for (var match : result.matches()) assertTrue(p.processing().chunks().contains(match.evidence()));
        }
        assertTrue(retrieval.search(p.id(), "zyxnonexistent", 10).orElseThrow().matches().isEmpty());
        assertTrue(retrieval.search(p.id(), "🦄 !!! ()", 10).orElseThrow().matches().isEmpty());
        assertTrue(retrieval.search("0".repeat(64), "ProductService", 10).isEmpty());
    }

    @Test void ranksExactSymbolContextAndReturnsStableBoundedUniqueMatches() {
        var p = publish();
        var full = retrieval.search(p.id(), "retryFailedIngestion", 100).orElseThrow();
        assertTrue(full.matches().getFirst().evidence().context().stream().anyMatch(c -> c.contains("retryFailedIngestion")));
        assertTrue(full.matches().getFirst().score() >= 1.0);
        assertEquals(full, retrieval.search(p.id(), "retryFailedIngestion", 100).orElseThrow());
        assertEquals(full.matches().subList(0, 1), retrieval.search(p.id(), "retryFailedIngestion", 1).orElseThrow().matches());
        assertEquals(full.resultCount(), full.matches().stream().map(m -> m.evidence().id()).distinct().count());
        for (int i = 0; i < full.resultCount(); i++) assertEquals(i + 1, full.matches().get(i).rank());
        var s = snapshot("b", RepositorySelection.Scope.all());
        var input = new IngestionResult(s, List.of(source(s, "b.md", Format.MARKDOWN, "same token"),
                source(s, "a.md", Format.MARKDOWN, "same token")), List.of(), List.of());
        var tied = persistence.publish(input, process(input));
        assertEquals(List.of("a.md", "b.md"), retrieval.search(tied.id(), "token", 10).orElseThrow().matches()
                .stream().map(m -> m.evidence().filePath()).toList());
    }

    @Test void isolatesCommitsScopesRepositoriesConfigurationsAndParserDerivations() {
        var all = publish();
        var scopedInput = fixture("a", new RepositorySelection.Scope(List.of("src"), List.of()));
        var scoped = persistence.publish(scopedInput, process(scopedInput));
        assertTrue(all.processing().chunks().stream().anyMatch(c -> scoped.processing().chunks().stream().anyMatch(d -> c.id().equals(d.id()))));
        var otherInput = fixture("b", RepositorySelection.Scope.all());
        var other = persistence.publish(otherInput, process(otherInput));
        var input = all.ingestion();
        var split = persistence.publish(input, new ArtifactProcessor().process(input, new ChunkingConfiguration(1, 10_000)));
        var parser = persistence.publish(input, withParser(all.processing(), "test-parser-v2"));
        var s = new Snapshot("different-repository", input.snapshot().commitSha(), input.snapshot().scope(), input.snapshot().policyVersion());
        var repositoryInput = new IngestionResult(s, input.artifacts().stream().map(a -> source(s, a.path(), a.format(), a.content())).toList(), List.of(), List.of());
        var repository = persistence.publish(repositoryInput, process(repositoryInput));
        for (var p : List.of(all, scoped, other, split, parser, repository)) {
            var result = retrieval.search(p.id(), "ProductService", 100).orElseThrow();
            assertEquals(p.snapshotId(), result.publication().snapshotId());
            assertEquals(p.processing().status(), result.publication().metadata().status());
            for (var match : result.matches()) assertTrue(p.processing().chunks().contains(match.evidence()));
        }
        assertTrue(retrieval.search(scoped.id(), "environments", 10).orElseThrow().matches().isEmpty());
        assertFalse(retrieval.search(all.id(), "environments", 10).orElseThrow().matches().isEmpty());
    }

    @Test void preservesEmptyFailedPartialAndIngestionGapCoverage() {
        var s = snapshot("a", RepositorySelection.Scope.all());
        var empty = new IngestionResult(s, List.of(), List.of(), List.of());
        var saved = persistence.publish(empty, process(empty));
        assertEquals(ProcessingResult.Status.COMPLETE, retrieval.search(saved.id(), "anything", 10).orElseThrow().publication().metadata().status());
        s = snapshot("b", RepositorySelection.Scope.all());
        var failed = new IngestionResult(s, List.of(source(s, "Bad.java", Format.JAVA, "class {")),
                List.of(new CoverageEntry("large.md", Disposition.EXCLUDED, Reason.FILE_SIZE_LIMIT, "Too large"),
                        new CoverageEntry("data.bin", Disposition.UNSUPPORTED, Reason.FILE_TYPE, "Unsupported"),
                        new CoverageEntry("lost.java", Disposition.FAILED, Reason.OBJECT_READ_FAILED, "Missing object")), List.of("Incomplete"));
        var failure = persistence.publish(failed, process(failed));
        var result = retrieval.search(failure.id(), "anything", 10).orElseThrow();
        assertTrue(result.matches().isEmpty());
        assertEquals(ProcessingResult.Status.FAILED, result.publication().metadata().status());
        assertEquals(failed.coverage(), result.publication().metadata().ingestionCoverage());
        var partialInput = fixture("c", RepositorySelection.Scope.all());
        var partial = persistence.publish(partialInput, new ArtifactProcessor().process(partialInput, new ChunkingConfiguration(1, 1)));
        assertEquals(ProcessingResult.Status.PARTIAL, retrieval.search(partial.id(), "failure", 10).orElseThrow().publication().metadata().status());
    }

    @Test void detectsMissingMembershipEvenForQueriesThatWouldReturnNoMatches() {
        var p = publish();
        jdbc.update("DELETE FROM publication_chunk WHERE publication_id = ? AND ordinal = 0", p.id());
        assertThrows(PublicationIntegrityException.class, () -> retrieval.search(p.id(), "nonexistent", 10));
        assertThrows(PublicationIntegrityException.class, () -> persistence.findPublication(p.id()));
    }

    @Test void detectsTrailingMissingMembershipAndInconsistentExpectedCount() {
        var p = publish();
        jdbc.update("DELETE FROM publication_chunk WHERE publication_id = ? AND ordinal = ?", p.id(), p.processing().chunks().size() - 1);
        assertThrows(PublicationIntegrityException.class, () -> retrieval.search(p.id(), "nonexistent", 10));
        clear(); var changed = publish();
        jdbc.update("UPDATE processing_publication SET metadata = jsonb_set(metadata, '{artifacts,0,chunkCount}', '999') WHERE publication_id = ?", changed.id());
        String id = changed.id();
        assertThrows(PublicationIntegrityException.class, () -> retrieval.search(id, "nonexistent", 10));
    }

    @Test void detectsSwappedContiguousOrdinalsAndMalformedMetadata() {
        var p = publish();
        jdbc.update("UPDATE publication_chunk SET ordinal = 10000 WHERE publication_id = ? AND ordinal = 0", p.id());
        jdbc.update("UPDATE publication_chunk SET ordinal = 0 WHERE publication_id = ? AND ordinal = 1", p.id());
        jdbc.update("UPDATE publication_chunk SET ordinal = 1 WHERE publication_id = ? AND ordinal = 10000", p.id());
        assertThrows(PublicationIntegrityException.class, () -> retrieval.search(p.id(), "anything", 10));
        clear(); var malformed = publish();
        jdbc.update("UPDATE processing_publication SET metadata = metadata - 'configuration' WHERE publication_id = ?", malformed.id());
        assertThrows(PublicationIntegrityException.class, () -> retrieval.search(malformed.id(), "anything", 10));
    }

    @Test void detectsOrderingDerivationSnapshotAndReturnedContentCorruption() {
        var p = publish();
        jdbc.update("UPDATE publication_chunk SET ordinal = ordinal + 100 WHERE publication_id = ?", p.id());
        assertThrows(PublicationIntegrityException.class, () -> retrieval.search(p.id(), "anything", 10));
        clear(); var derivation = publish();
        jdbc.update("UPDATE evidence_chunk SET parser = 'wrong' WHERE snapshot_id = ?", derivation.snapshotId());
        assertThrows(PublicationIntegrityException.class, () -> retrieval.search(derivation.id(), "anything", 10));
        clear(); var snapshot = publish();
        jdbc.update("UPDATE repository_snapshot SET repository_id = 'wrong' WHERE snapshot_id = ?", snapshot.snapshotId());
        assertThrows(PublicationIntegrityException.class, () -> retrieval.search(snapshot.id(), "anything", 10));
        clear(); var corrupt = publish();
        jdbc.update("UPDATE evidence_chunk SET content = content || ' corruption' WHERE snapshot_id = ?", corrupt.snapshotId());
        assertThrows(PublicationIntegrityException.class, () -> retrieval.search(corrupt.id(), "corruption", 10));
    }

    @Test void reportsUnmappableStoredContextAsIntegrityFailure() {
        var p = publish();
        jdbc.update("UPDATE evidence_chunk SET context = '[{}]'::jsonb WHERE snapshot_id = ?", p.snapshotId());
        assertThrows(PublicationIntegrityException.class, () -> retrieval.search(p.id(), "ProductService", 10));
    }

    @Test void treatsSqlAndSearchPunctuationAsDataAndValidatesBounds() {
        var p = publish();
        for (String query : List.of("' OR 1=1; DROP TABLE evidence_chunk; --", "\"ProductService\"", "(failure)",
                "ProductService & !retry:*", "\\", "résumé", "OR", "!!!")) {
            assertNotNull(retrieval.search(p.id(), query, 10).orElseThrow());
        }
        assertEquals(p, persistence.findPublication(p.id()).orElseThrow());
        for (String query : new String[]{null, "", "  \t", "x".repeat(513), "x\0y", "\ud800"})
            assertThrows(IllegalArgumentException.class, () -> retrieval.search(p.id(), query, 10));
        for (int topK : new int[]{-1, 0, 101, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> retrieval.search(p.id(), "valid", topK));
        assertThrows(IllegalArgumentException.class, () -> retrieval.search("not-an-id", "valid", 10));
        assertThrows(RetrievalException.class, () -> new KeywordRetrievalService(
                new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=1", "unused", "unused"))
                .search(p.id(), "valid", 10));
    }

    @Test void normalizesWithoutSynonymsAndMaintainsGeneratedIndexes() {
        assertEquals("httpserver retry_count example productservice java http server retry count example product service java",
                jdbc.queryForObject("SELECT keyword_text_v1(?)", String.class, "HTTPServer retry_count example.ProductService.java"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN ('evidence_chunk_keyword_gin', 'source_artifact_keyword_gin')", Integer.class));
        var p = publish();
        assertTrue(retrieval.search(p.id(), "failures", 10).orElseThrow().matches().isEmpty());
        var plan = dev.changeassurance.retrieval.KeywordQueryPlan.explain(jdbc, p.id(), "ProductService", 10);
        assertFalse(plan.isEmpty());
        System.out.println("KEYWORD EXPLAIN (tiny fixture, planner may choose sequential scan): " + String.join("\n", plan));
    }

    @Test void boundsDerivedSearchWithoutChangingLargeSourceOrCitation() {
        var s = snapshot("a", RepositorySelection.Scope.all());
        String content = "earlyterm " + "padding ".repeat(10_000) + "lateterm";
        var input = new IngestionResult(s, List.of(source(s, "large.md", Format.MARKDOWN, content)), List.of(), List.of());
        var p = persistence.publish(input, process(input));
        var result = retrieval.search(p.id(), "earlyterm", 10).orElseThrow();
        assertEquals(content, result.matches().getFirst().evidence().content());
        assertTrue(retrieval.search(p.id(), "lateterm", 10).orElseThrow().matches().isEmpty());
        assertTrue(result.limitations().stream().anyMatch(l -> l.contains("65536")));
    }
}
