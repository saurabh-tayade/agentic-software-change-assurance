package dev.changeassurance.persistence;

import dev.changeassurance.embedding.*;
import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.processing.EvidenceChunk;
import dev.changeassurance.retrieval.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static dev.changeassurance.persistence.PersistenceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real exact pgvector search with 768-dimensional deterministic vectors; no Ollama. */
class VectorRetrievalIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestImage.IMAGE)
            .withPassword(UUID.randomUUID().toString());
    private static DriverManagerDataSource ds;
    private static JdbcTemplate jdbc;
    private static EvidencePersistenceService publications;
    private static EmbeddingStore store;
    private static final EmbeddingProfile PROFILE = new EmbeddingProfile("ollama", "nomic-embed-text:v1.5", "a".repeat(64),
            768, EmbeddingProfile.DOCUMENT_RECIPE, EmbeddingProfile.QUERY_RECIPE, EmbeddingProfile.Options.defaults(), "cosine");
    private static final Comparator<EvidenceChunk> ORDER = Comparator.comparing(EvidenceChunk::filePath)
            .thenComparingInt(EvidenceChunk::startLine).thenComparing(EvidenceChunk::id);
    @BeforeAll static void start() {
        POSTGRES.start();
        ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds); publications = new EvidencePersistenceService(ds); store = new EmbeddingStore(ds);
        Flyway.configure().dataSource(ds).load().migrate();
    }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE chunk_embedding, embedding_profile, publication_chunk, processing_publication, evidence_chunk, source_artifact, repository_snapshot");
    }
    private PersistedPublication publish(String commit) {
        var snapshot = snapshot(commit, RepositorySelection.Scope.all());
        var input = new IngestionResult(snapshot, List.of(
                source(snapshot, "b.md", IngestionResult.Format.MARKDOWN, "# B\nPricing café 😀.\n"),
                source(snapshot, "a.md", IngestionResult.Format.MARKDOWN, "# A\nFirst.\n## Next\nSecond.\n"),
                source(snapshot, "c.md", IngestionResult.Format.MARKDOWN, "# C\nThird.\n")), List.of(), List.of("Committed only"));
        var p = publications.publish(input, process(input));
        assertEquals(4, p.processing().chunks().size());
        return p;
    }
    private List<EvidenceChunk> chunks(PersistedPublication p) { return p.processing().chunks().stream().sorted(ORDER).toList(); }
    private static float[] vector(float x, float y) { float[] v = new float[768]; v[0] = x; v[1] = y; return v; }
    private void index(PersistedPublication p, float[]... vectors) {
        store.register(PROFILE);
        var chunks = chunks(p);
        for (int i = 0; i < chunks.size(); i++) {
            var c = chunks.get(i);
            store.insert(p, c.id(), PROFILE, PROFILE.document(c.content()), vectors[i % vectors.length]);
        }
    }
    private static class Gateway implements EmbeddingGateway {
        int resolutions;
        int calls;
        float[] vector = vector(1, 0);
        EmbeddingProfile profile = PROFILE;
        EmbeddingException.Code failure;
        boolean nullResponse;
        Runnable duringInference = () -> { };
        EmbeddingProfile.Input input;
        @Override public EmbeddingProfile resolveProfile() {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            resolutions++;
            return profile;
        }
        @Override public Response embed(EmbeddingProfile p, EmbeddingProfile.Input input) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(PROFILE, p);
            assertTrue(input.text().startsWith("search_query: "));
            this.input = input;
            calls++;
            if (failure != null) throw new EmbeddingException(failure);
            duringInference.run();
            return nullResponse ? null : new Response(vector, 1);
        }
    }
    private VectorRetrievalService service(Gateway g) { return new VectorRetrievalService(ds, g); }
    private RetrievalResult search(PersistedPublication p, Gateway g, int topK) {
        return service(g).search(p.id(), "Where is pricing café 😀?", topK, PROFILE).orElseThrow();
    }
    @Test void exactCosineRankingReturnsOriginalEvidenceAndBoundedResults() {
        var p = publish("a"); var g = new Gateway();
        index(p, vector(0, 1), vector(-1, 0), vector(1, 1), vector(1, 0));
        var result = search(p, g, 100);
        var chunks = chunks(p);
        assertEquals(List.of(chunks.get(3), chunks.get(2), chunks.get(0), chunks.get(1)),
                result.matches().stream().map(RetrievalResult.Match::evidence).toList());
        double[] scores = {1, Math.sqrt(.5), 0, -1};
        for (int i = 0; i < 4; i++) {
            assertEquals(i + 1, result.matches().get(i).rank());
            assertEquals(scores[i], result.matches().get(i).score(), 1e-6);
        }
        assertEquals(PROFILE.query(result.query()), g.input);
        assertEquals("postgres-vector-v1:profile=" + PROFILE.id(), result.strategy());
        assertEquals(p.id(), result.publication().id());
        assertEquals(chunks.getFirst().snapshot(), result.publication().snapshot());
        assertEquals(result.matches().subList(0, 1), search(p, g, 1).matches());
        assertEquals(p, publications.findPublication(p.id()).orElseThrow());
        assertTrue(result.limitations().stream().anyMatch(s -> s.contains("not evidence relevance or answer confidence")));
    }
    @Test void equalDistancesHaveStablePathLineAndIdentityOrder() {
        var p = publish("a"); var g = new Gateway(); index(p, vector(1, 0));
        for (int repeat = 0; repeat < 3; repeat++)
            assertEquals(chunks(p), search(p, g, 100).matches().stream().map(RetrievalResult.Match::evidence).toList());
    }
    @Test void maximumLengthQueryIsPreservedWithoutTrimming() {
        var p = publish("a"); index(p, vector(1, 0)); var g = new Gateway();
        String query = " " + "x".repeat(510) + " ";
        var result = service(g).search(p.id(), query, 5, PROFILE).orElseThrow();
        assertEquals(query, result.query());
        assertEquals(PROFILE.query(query), g.input);
    }
    @Test void membershipIsolatesPublicationsEvenWithSharedSnapshotAndDifferentParsers() {
        var a = publish("a"); var b = publish("b");
        var changed = publications.publish(a.ingestion(), withParser(a.processing(), "test-parser-v2"));
        index(a, vector(0, 1)); index(b, vector(1, 0)); index(changed, vector(1, 0));
        var result = search(a, new Gateway(), 100);
        assertEquals(chunks(a), result.matches().stream().map(RetrievalResult.Match::evidence).toList());
        assertTrue(result.matches().stream().allMatch(m -> m.score() == 0));
    }
    @Test void unknownAndEmptyPublicationsDoNotNeedAnIndexOrProvider() {
        var g = new Gateway();
        assertTrue(service(g).search("0".repeat(64), "question", 5, PROFILE).isEmpty());
        var input = new IngestionResult(snapshot("a", RepositorySelection.Scope.all()), List.of(), List.of(), List.of());
        var p = publications.publish(input, process(input));
        var result = search(p, g, 5);
        assertTrue(result.matches().isEmpty());
        assertEquals(p.id(), result.publication().id());
        assertEquals(0, g.resolutions); assertEquals(0, g.calls);
    }
    @Test void missingAndPartialIndexesFailWithCoverageWithoutInferenceOrRepair() {
        var p = publish("a"); var g = new Gateway();
        var missing = assertThrows(VectorRetrievalException.class, () -> search(p, g, 5));
        assertEquals(VectorRetrievalException.Code.INDEX_INCOMPLETE, missing.code());
        assertEquals(new VectorRetrievalException.Coverage(p.id(), PROFILE.id(), 4, 0, 4), missing.coverage());
        store.register(PROFILE);
        var chunk = chunks(p).getFirst();
        store.insert(p, chunk.id(), PROFILE, PROFILE.document(chunk.content()), vector(1, 0));
        var partial = assertThrows(VectorRetrievalException.class, () -> search(p, g, 5));
        assertEquals(new VectorRetrievalException.Coverage(p.id(), PROFILE.id(), 4, 1, 3), partial.coverage());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM chunk_embedding", Integer.class));
        assertEquals(0, g.resolutions); assertEquals(0, g.calls);
    }
    @Test void installedModelAndStoredProfileMustMatchTheExplicitProfile() {
        var p = publish("a"); index(p, vector(1, 0)); var g = new Gateway();
        g.profile = new EmbeddingProfile(PROFILE.provider(), PROFILE.model(), "b".repeat(64), 768,
                PROFILE.documentRecipe(), PROFILE.queryRecipe(), PROFILE.options(), PROFILE.distanceMetric());
        assertEquals(EmbeddingException.Code.PROFILE_MISMATCH, assertThrows(EmbeddingException.class, () -> search(p, g, 5)).code());
        assertEquals(0, g.calls);
        g.profile = PROFILE;
        jdbc.update("UPDATE embedding_profile SET model_digest = ?", "b".repeat(64));
        assertEquals(EmbeddingException.Code.PROFILE_MISMATCH, assertThrows(EmbeddingException.class, () -> search(p, g, 5)).code());
        assertEquals(1, g.resolutions);
    }
    @Test void anotherProfilesIndexCannotSatisfyCompleteness() {
        var p = publish("a"); index(p, vector(1, 0)); var g = new Gateway();
        var other = new EmbeddingProfile(PROFILE.provider(), PROFILE.model(), "b".repeat(64), 768,
                PROFILE.documentRecipe(), PROFILE.queryRecipe(), PROFILE.options(), PROFILE.distanceMetric());
        var error = assertThrows(VectorRetrievalException.class, () -> service(g).search(p.id(), "query", 5, other));
        assertEquals(4, error.coverage().missingChunks()); assertEquals(0, g.calls);
    }
    @Test void invalidQueryVectorsAreRejectedBeforeRanking() {
        var p = publish("a"); index(p, vector(1, 0)); var g = new Gateway();
        for (float[] invalid : new float[][]{null, new float[3], new float[768], vector(Float.NaN, 1),
                vector(Float.POSITIVE_INFINITY, 1), vector(Float.NEGATIVE_INFINITY, 1)}) {
            g.vector = invalid;
            assertEquals(EmbeddingException.Code.INVALID_VECTOR, assertThrows(EmbeddingException.class, () -> search(p, g, 5)).code());
        }
        g.nullResponse = true;
        assertEquals(EmbeddingException.Code.INVALID_PROVIDER_RESPONSE, assertThrows(EmbeddingException.class, () -> search(p, g, 5)).code());
    }
    @Test void providerFailuresRemainDistinctWithoutRetriesOrFallback() {
        var p = publish("a"); index(p, vector(1, 0)); var g = new Gateway();
        var failures = List.of(EmbeddingException.Code.MODEL_UNAVAILABLE, EmbeddingException.Code.MODEL_TIMEOUT,
                EmbeddingException.Code.PROFILE_MISMATCH, EmbeddingException.Code.INVALID_PROVIDER_RESPONSE);
        for (var code : failures) {
            g.failure = code;
            assertEquals(code, assertThrows(EmbeddingException.class, () -> search(p, g, 5)).code());
        }
        assertEquals(failures.size(), g.calls);
    }
    @Test void finalReadRechecksCompletenessAfterInference() {
        var p = publish("a"); index(p, vector(1, 0)); var g = new Gateway();
        g.duringInference = () -> jdbc.update("DELETE FROM chunk_embedding WHERE chunk_id = ?", chunks(p).getLast().id());
        var error = assertThrows(VectorRetrievalException.class, () -> search(p, g, 5));
        assertEquals(VectorRetrievalException.Code.INDEX_INCOMPLETE, error.code());
        assertEquals(1, error.coverage().missingChunks()); assertEquals(1, g.calls);
    }
    @Test void finalReadRechecksProfileAfterInference() {
        var p = publish("a"); index(p, vector(1, 0)); var g = new Gateway();
        g.duringInference = () -> jdbc.update("UPDATE embedding_profile SET model_digest = ?", "b".repeat(64));
        assertEquals(EmbeddingException.Code.PROFILE_MISMATCH, assertThrows(EmbeddingException.class, () -> search(p, g, 5)).code());
    }
    @Test void corruptedEvidenceOutsideTopKStillFailsIntegrity() {
        var p = publish("a"); index(p, vector(1, 0), vector(0, 1)); var g = new Gateway();
        g.duringInference = () -> jdbc.update("UPDATE evidence_chunk SET content = 'tampered' WHERE chunk_id = ?", chunks(p).getLast().id());
        assertThrows(PublicationIntegrityException.class, () -> search(p, g, 1));
    }
    @Test void corruptedEmbeddingInputOutsideTopKFailsIntegrity() {
        var p = publish("a"); index(p, vector(1, 0), vector(0, 1));
        jdbc.update("UPDATE chunk_embedding SET input_hash = ? WHERE chunk_id = ?", "0".repeat(64), chunks(p).getLast().id());
        assertEquals(EmbeddingException.Code.INDEX_INTEGRITY_FAILURE,
                assertThrows(EmbeddingException.class, () -> search(p, new Gateway(), 1)).code());
    }
    @Test void corruptedStoredVectorOutsideTopKFailsIntegrity() {
        var p = publish("a"); index(p, vector(1, 0), vector(0, 1));
        jdbc.update("UPDATE chunk_embedding SET embedding = ?::vector WHERE chunk_id = ?",
                EmbeddingVector.sql(vector(-1, 0)), chunks(p).getLast().id());
        assertEquals(EmbeddingException.Code.INDEX_INTEGRITY_FAILURE,
                assertThrows(EmbeddingException.class, () -> search(p, new Gateway(), 1)).code());
    }
    @Test void completeIndexPreservesIncompleteSourceCoverage() {
        var snapshot = snapshot("a", RepositorySelection.Scope.all());
        var input = new IngestionResult(snapshot, List.of(source(snapshot, "a.md", IngestionResult.Format.MARKDOWN, "# A\nText.\n")),
                List.of(new IngestionResult.CoverageEntry("missing.md", IngestionResult.Disposition.FAILED,
                        IngestionResult.Reason.FILE_SIZE_LIMIT, "Too large")), List.of("Partial source"));
        var p = publications.publish(input, process(input)); index(p, vector(1, 0));
        assertEquals(p.processing().status(), search(p, new Gateway(), 5).publication().metadata().status());
        assertEquals(p.processing().ingestionCoverage(), search(p, new Gateway(), 5).publication().metadata().ingestionCoverage());
    }
    @Test void outerTransactionIsRejectedBeforeProviderCalls() {
        var p = publish("a"); index(p, vector(1, 0)); var g = new Gateway();
        new TransactionTemplate(new JdbcTransactionManager(ds)).executeWithoutResult(s ->
                assertThrows(IllegalStateException.class, () -> search(p, g, 5)));
        assertEquals(0, g.resolutions);
    }
    @Test @ExtendWith(OutputCaptureExtension.class)
    void cliPrintsProfileAndOriginalEvidenceAsJsonAndCloses(CapturedOutput output) {
        var p = publish("a"); index(p, vector(1, 0)); var g = new Gateway();
        var environment = new MockEnvironment().withProperty("vector.command", "search")
                .withProperty("vector.publication-id", p.id()).withProperty("vector.profile-id", PROFILE.id())
                .withProperty("vector.query", "Where is pricing?").withProperty("vector.top-k", "1");
        var context = new GenericApplicationContext(); context.refresh();
        new VectorRetrievalCliRunner(environment, ds, service(g), context).run(new DefaultApplicationArguments());
        var json = new JsonMapper().readTree(output.getOut());
        assertEquals(PROFILE.id(), json.path("embeddingProfileId").asString());
        assertEquals(p.id(), json.path("result").path("publication").path("id").asString());
        assertEquals(chunks(p).getFirst().id(), json.path("result").path("matches").get(0).path("evidence").path("id").asString());
        assertEquals(1, json.path("result").path("matches").size());
        assertFalse(context.isActive());
    }
    @Test @ExtendWith(OutputCaptureExtension.class)
    void cliIncompleteIndexReportsSafeCoverageAndFails(CapturedOutput output) {
        var p = publish("a"); store.register(PROFILE); var g = new Gateway();
        var environment = new MockEnvironment().withProperty("vector.command", "search")
                .withProperty("vector.publication-id", p.id()).withProperty("vector.profile-id", PROFILE.id())
                .withProperty("vector.query", "private question text");
        var context = new GenericApplicationContext(); context.refresh();
        var cli = new VectorRetrievalCliRunner(environment, ds, service(g), context);
        assertThrows(IllegalStateException.class, () -> cli.run(new DefaultApplicationArguments()));
        var json = new JsonMapper().readTree(output.getErr());
        assertEquals("INDEX_INCOMPLETE", json.path("failure").asString());
        assertEquals(4, json.path("coverage").path("missingChunks").asInt());
        assertFalse(output.getAll().contains("private question text"));
        assertFalse(context.isActive()); assertEquals(0, g.calls);
    }
}
