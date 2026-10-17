package dev.changeassurance.persistence;

import dev.changeassurance.embedding.*;
import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.processing.ProcessingResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.changeassurance.persistence.PersistenceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real pgvector with deterministic embeddings, never a live Ollama dependency. */
class EmbeddingIndexingIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestImage.IMAGE)
            .withPassword(UUID.randomUUID().toString());
    private static DriverManagerDataSource ds;
    private static JdbcTemplate jdbc;
    private static EvidencePersistenceService publications;
    private static EmbeddingStore store;
    private static final EmbeddingProfile PROFILE = new EmbeddingProfile("ollama", "nomic-embed-text:v1.5", "a".repeat(64),
            768, EmbeddingProfile.DOCUMENT_RECIPE, EmbeddingProfile.QUERY_RECIPE, EmbeddingProfile.Options.defaults(), "cosine");
    @BeforeAll static void start() {
        POSTGRES.start();
        ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds); publications = new EvidencePersistenceService(ds); store = new EmbeddingStore(ds);
        // Upgrade an actual populated V1/V2 database, not just a fresh schema.
        assertEquals(2, Flyway.configure().dataSource(ds).target("2").load().migrate().migrationsExecuted);
        var input = input("a", RepositorySelection.Scope.all());
        var old = publications.publish(input, process(input));
        assertEquals(1, Flyway.configure().dataSource(ds).load().migrate().migrationsExecuted);
        assertEquals(old, publications.findPublication(old.id()).orElseThrow());
        assertEquals("0.8.7", jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class));
        assertEquals(0, Flyway.configure().dataSource(ds).load().migrate().migrationsExecuted);
    }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE chunk_embedding, embedding_profile, publication_chunk, processing_publication, evidence_chunk, source_artifact, repository_snapshot");
    }
    private PersistedPublication publish(String commit) {
        var input = input(commit, RepositorySelection.Scope.all());
        return publications.publish(input, process(input));
    }
    private static class Gateway implements EmbeddingGateway {
        final AtomicInteger calls = new AtomicInteger();
        int failAt = -1;
        EmbeddingException.Code failure = EmbeddingException.Code.MODEL_UNAVAILABLE;
        float[] vector;
        Gateway() { vector = new float[768]; vector[0] = 1; vector[10] = .25f; }
        @Override public EmbeddingProfile resolveProfile() { return PROFILE; }
        @Override public Response embed(EmbeddingProfile p, EmbeddingProfile.Input input) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Provider call inside a DB transaction");
            if (calls.incrementAndGet() == failAt) throw new EmbeddingException(failure);
            return new Response(vector, 1);
        }
    }
    private EmbeddingIndexingService service(Gateway gateway) { return new EmbeddingIndexingService(publications, store, gateway); }

    @Test void indexingIsIdempotentAndPreservesCompleteEvidence() {
        var p = publish("a"); var g = new Gateway();
        var result = service(g).index(p.id(), PROFILE);
        assertEquals(EmbeddingIndexingService.Status.COMPLETE, result.status());
        assertEquals(p.processing().chunks().size(), result.insertedChunks());
        int calls = g.calls.get();
        var again = service(g).index(p.id(), PROFILE);
        assertEquals(calls, g.calls.get());
        assertEquals(result.totalChunks(), again.reusedChunks());
        assertEquals(0, again.insertedChunks());
        assertEquals(p, publications.findPublication(p.id()).orElseThrow());
        for (var chunk : p.processing().chunks()) {
            assertEquals(PROFILE.document(chunk.content()).hash(), jdbc.queryForObject(
                    "SELECT input_hash FROM chunk_embedding WHERE snapshot_id = ? AND chunk_id = ?", String.class, p.snapshotId(), chunk.id()));
        }
    }
    @Test void failedRunReportsPartialCoverageAndResumesMissingChunks() {
        var p = publish("a"); var g = new Gateway(); g.failAt = 2;
        var partial = service(g).index(p.id(), PROFILE);
        assertEquals(EmbeddingIndexingService.Status.PARTIAL, partial.status());
        assertEquals(1, partial.indexedChunks());
        assertEquals(EmbeddingException.Code.MODEL_UNAVAILABLE, partial.issues().getFirst().reason());
        assertEquals(partial.totalChunks() - 1, partial.missingChunks());
        g.failAt = -1;
        var recovered = service(g).index(p.id(), PROFILE);
        assertEquals(EmbeddingIndexingService.Status.COMPLETE, recovered.status());
        assertEquals(1, recovered.reusedChunks());
        assertEquals(p, publications.findPublication(p.id()).orElseThrow());
    }
    @Test void separatesPublicationCommitsScopesAndProfiles() {
        var a = publish("a"); var b = publish("b"); var g = new Gateway();
        service(g).index(a.id(), PROFILE);
        assertTrue(store.existing(b, PROFILE).isEmpty());
        var scopeSnapshot = snapshot("a", new RepositorySelection.Scope(List.of("src"), List.of()));
        var originalSource = a.ingestion().artifacts().getFirst();
        var scopedInput = new IngestionResult(scopeSnapshot,
                List.of(source(scopeSnapshot, originalSource.path(), originalSource.format(), originalSource.content())),
                List.of(new IngestionResult.CoverageEntry("docs/guide.md", IngestionResult.Disposition.EXCLUDED,
                        IngestionResult.Reason.OUTSIDE_SCOPE, "Outside src")), List.of("Committed only"));
        var scoped = publications.publish(scopedInput, process(scopedInput));
        assertTrue(store.existing(scoped, PROFILE).isEmpty());
        var other = new EmbeddingProfile(PROFILE.provider(), PROFILE.model(), "b".repeat(64), 768,
                PROFILE.documentRecipe(), PROFILE.queryRecipe(), PROFILE.options(), "cosine");
        store.register(other);
        assertTrue(store.existing(a, other).isEmpty());
        assertEquals(EmbeddingException.Code.PROFILE_MISMATCH,
                assertThrows(EmbeddingException.class, () -> service(g).index(a.id(), other)).code());
    }
    @Test void publicationsSharingChunksReuseOnlyTheSharedIdentities() {
        var input = input("a", RepositorySelection.Scope.all()); var original = process(input);
        var updated = withParser(original, "markdown-parser-test-v2");
        // Only the Markdown parser changes. Java evidence identity stays identical.
        var changed = new ProcessingResult(original.snapshot(), original.configuration(), original.status(),
                original.artifacts().stream().map(a -> a.path().endsWith(".md")
                        ? updated.artifacts().stream().filter(x -> x.path().equals(a.path())).findFirst().orElseThrow() : a).toList(),
                original.chunks().stream().map(c -> c.filePath().endsWith(".md")
                        ? updated.chunks().stream().filter(x -> x.filePath().equals(c.filePath()) && x.startLine() == c.startLine()).findFirst().orElseThrow() : c).toList(),
                original.ingestionCoverage(), original.ingestionLimitations(), original.ingestionSummary(), original.processingLimitations());
        var first = publications.publish(input, original); var second = publications.publish(input, changed);
        assertNotEquals(first.id(), second.id());
        var g = new Gateway(); service(g).index(first.id(), PROFILE);
        var result = service(g).index(second.id(), PROFILE);
        long shared = original.chunks().stream().filter(c -> c.filePath().endsWith(".java")).count();
        assertEquals(shared, result.reusedChunks());
        assertEquals(second.processing().chunks().size() - shared, result.insertedChunks());
        assertEquals(first, publications.findPublication(first.id()).orElseThrow());
    }
    @Test void oversizedSourceIsExplicitAndNeverSubmittedOrTruncated() {
        var snapshot = snapshot("a", RepositorySelection.Scope.all());
        var input = new IngestionResult(snapshot, List.of(source(snapshot, "large.md", IngestionResult.Format.MARKDOWN, "x".repeat(9000))), List.of(), List.of());
        var p = publications.publish(input, process(input)); var g = new Gateway();
        var result = service(g).index(p.id(), PROFILE);
        assertEquals(EmbeddingIndexingService.Status.FAILED, result.status());
        assertEquals(EmbeddingException.Code.OVERSIZED_INPUT, result.issues().getFirst().reason());
        assertEquals(0, g.calls.get()); assertEquals(1, result.missingChunks());
        assertEquals(p, publications.findPublication(p.id()).orElseThrow());
    }
    @Test void providerContextRejectionIsNotMarkedComplete() {
        var p = publish("a"); var g = new Gateway(); g.failAt = 1; g.failure = EmbeddingException.Code.MODEL_INPUT_REJECTED;
        var report = service(g).index(p.id(), PROFILE);
        assertEquals(EmbeddingIndexingService.Status.PARTIAL, report.status());
        assertEquals(1, report.missingChunks());
        assertEquals(EmbeddingException.Code.MODEL_INPUT_REJECTED, report.issues().getFirst().reason());
    }
    @Test void invalidVectorsNeverPersist() {
        var p = publish("a"); var g = new Gateway(); g.vector = new float[]{1, 2};
        var result = service(g).index(p.id(), PROFILE);
        assertEquals(EmbeddingException.Code.INVALID_VECTOR, result.issues().getFirst().reason());
        assertEquals(0, result.indexedChunks());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chunk_embedding", Integer.class));
    }
    @Test void corruptedEvidenceOrIndexFailsClosed() {
        var p = publish("a"); var g = new Gateway(); service(g).index(p.id(), PROFILE);
        jdbc.update("UPDATE chunk_embedding SET input_hash = ?", "0".repeat(64));
        assertEquals(EmbeddingException.Code.INDEX_INTEGRITY_FAILURE,
                assertThrows(EmbeddingException.class, () -> service(g).index(p.id(), PROFILE)).code());
        jdbc.update("UPDATE evidence_chunk SET content = 'tampered' WHERE chunk_id = ?", p.processing().chunks().getFirst().id());
        assertThrows(PublicationIntegrityException.class, () -> service(g).index(p.id(), PROFILE));
    }
    @Test void corruptedVectorChecksumAndProfileAreRejected() {
        var p = publish("a"); var g = new Gateway(); service(g).index(p.id(), PROFILE);
        jdbc.update("UPDATE chunk_embedding SET vector_hash = ?", "0".repeat(64));
        assertEquals(EmbeddingException.Code.INDEX_INTEGRITY_FAILURE,
                assertThrows(EmbeddingException.class, () -> service(g).index(p.id(), PROFILE)).code());
        jdbc.update("UPDATE embedding_profile SET model_digest = ?", "b".repeat(64));
        assertEquals(EmbeddingException.Code.PROFILE_MISMATCH,
                assertThrows(EmbeddingException.class, () -> service(g).index(p.id(), PROFILE)).code());
    }
    @Test void unknownPublicationAndOuterTransactionAreRejected() {
        var g = new Gateway();
        assertEquals(EmbeddingException.Code.UNKNOWN_PUBLICATION,
                assertThrows(EmbeddingException.class, () -> service(g).index("0".repeat(64), PROFILE)).code());
        var p = publish("a");
        new TransactionTemplate(new JdbcTransactionManager(ds)).executeWithoutResult(s ->
                assertThrows(IllegalStateException.class, () -> service(g).index(p.id(), PROFILE)));
        assertEquals(0, g.calls.get());
    }
    @Test void emptyPublicationCompletesWithoutAnEmbeddingCall() {
        var input = new IngestionResult(snapshot("a", RepositorySelection.Scope.all()), List.of(), List.of(), List.of());
        var p = publications.publish(input, process(input)); var g = new Gateway();
        var report = service(g).index(p.id(), PROFILE);
        assertEquals(EmbeddingIndexingService.Status.COMPLETE, report.status());
        assertEquals(0, report.totalChunks()); assertEquals(0, g.calls.get());
    }
    @Test void databaseEnforcesDimensionsAndForeignKeys() {
        store.register(PROFILE);
        var p = publish("a"); var c = p.processing().chunks().getFirst();
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "INSERT INTO chunk_embedding VALUES (?, ?, ?, ?, ?, '[1,2]'::vector)", p.snapshotId(), c.id(), PROFILE.id(), "a".repeat(64), "b".repeat(64)));
        float[] vector = new float[768]; vector[0] = 1;
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "INSERT INTO chunk_embedding VALUES (?, ?, ?, ?, ?, ?::vector)", p.snapshotId(), "0".repeat(64),
                PROFILE.id(), "a".repeat(64), "b".repeat(64), java.util.Arrays.toString(vector)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chunk_embedding", Integer.class));
    }
    @Test void concurrentIndexersStoreOneVectorPerIdentity() throws Exception {
        var p = publish("a");
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.of(
                    () -> service(new Gateway()).index(p.id(), PROFILE),
                    (java.util.concurrent.Callable<EmbeddingIndexingService.Report>) () -> service(new Gateway()).index(p.id(), PROFILE)));
            for (var future : futures) assertEquals(EmbeddingIndexingService.Status.COMPLETE, future.get().status());
        }
        assertEquals(p.processing().chunks().size(), jdbc.queryForObject("SELECT count(*) FROM chunk_embedding", Integer.class));
    }
    @Test void completeEmbeddingCoverageDoesNotErasePartialProcessing() {
        var base = input("a", RepositorySelection.Scope.all());
        var partial = new IngestionResult(base.snapshot(), base.artifacts(),
                List.of(new IngestionResult.CoverageEntry("unsupported.sql", IngestionResult.Disposition.UNSUPPORTED,
                        IngestionResult.Reason.FILE_TYPE, "Unsupported file")), base.limitations());
        var p = publications.publish(partial, process(partial));
        var result = service(new Gateway()).index(p.id(), PROFILE);
        assertEquals(EmbeddingIndexingService.Status.COMPLETE, result.status());
        assertEquals(ProcessingResult.Status.PARTIAL, result.processingStatus());
        assertEquals(p, publications.findPublication(p.id()).orElseThrow());
    }
}
