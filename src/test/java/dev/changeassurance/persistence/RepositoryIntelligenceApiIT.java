package dev.changeassurance.persistence;

import dev.changeassurance.ChangeAssuranceApplication;
import dev.changeassurance.embedding.*;
import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.processing.ArtifactProcessor;
import dev.changeassurance.processing.ChunkingConfiguration;
import dev.changeassurance.qa.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.changeassurance.persistence.PersistenceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real random-port HTTP + PostgreSQL/pgvector; all inference uses deterministic doubles. */
class RepositoryIntelligenceApiIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestImage.IMAGE)
            .withPassword(UUID.randomUUID().toString());
    private static final EmbeddingProfile PROFILE = new EmbeddingProfile("ollama", "nomic-embed-text:v1.5", "a".repeat(64),
            768, EmbeddingProfile.DOCUMENT_RECIPE, EmbeddingProfile.QUERY_RECIPE, EmbeddingProfile.Options.defaults(), "cosine");
    private static final JsonMapper JSON = new JsonMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static ConfigurableApplicationContext app;
    private static String base;
    private static JdbcTemplate jdbc;
    private static EvidencePersistenceService persistence;
    private static EmbeddingStore store;
    private static Chat chat;
    private static Embeddings embeddings;

    @BeforeAll static void start() {
        POSTGRES.start();
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds); persistence = new EvidencePersistenceService(ds); store = new EmbeddingStore(ds);
        store.register(PROFILE);
        app = new SpringApplicationBuilder(ChangeAssuranceApplication.class, Doubles.class)
                .profiles("intelligence-api", "embeddings")
                .run("--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--intelligence.api.embedding-profile-id=" + PROFILE.id(), "--intelligence.api.browser-origin=",
                        "--qa.log-malformed-model-response=false", "--qa.top-k=12", "--qa.max-evidence=6",
                        "--qa.max-context-bytes=6000", "--qa.max-evidence-bytes=3000");
        base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort() + "/api/v1";
        chat = app.getBean(Chat.class); embeddings = app.getBean(Embeddings.class);
        assertFalse(app.containsBean("flyway"));
        assertFalse(app.containsBean("embeddingIndexingService"));
        assertEquals(0, chat.calls.get()); assertEquals(0, embeddings.calls.get());
    }

    @AfterAll static void stop() {
        if (app != null) app.close();
        POSTGRES.stop();
    }

    @BeforeEach void prepare() {
        jdbc.execute("TRUNCATE chunk_embedding, publication_chunk, processing_publication, evidence_chunk, source_artifact, repository_snapshot");
        chat.calls.set(0); embeddings.calls.set(0); chat.failure = null; embeddings.failure = null;
    }

    @Test void discoversRepositoriesCommitsScopesAndConfigurationsWithStablePagination() throws Exception {
        var a = publish("repo-a", "a", RepositorySelection.Scope.all(), ChunkingConfiguration.defaults());
        var b = publish("repo-a", "b", RepositorySelection.Scope.all(), ChunkingConfiguration.defaults());
        var c = publish("repo-b", "c", new RepositorySelection.Scope(List.of("docs"), List.of()), ChunkingConfiguration.defaults());
        var d = publish("repo-a", "a", RepositorySelection.Scope.all(), new ChunkingConfiguration(1, 100));
        var expected = List.of(a.id(), b.id(), c.id(), d.id()).stream().sorted().toList();
        var ids = new ArrayList<String>();
        String after = null;
        do {
            var result = get("/publications?limit=1" + (after == null ? "" : "&after=" + after), 200);
            assertEquals(1, result.get("items").size());
            ids.add(result.at("/items/0/publicationId").asString());
            after = result.get("nextAfter").isNull() ? null : result.get("nextAfter").asString();
        } while (after != null);
        assertEquals(expected, ids);
        var filtered = get("/publications?repositoryId=repo-b", 200);
        assertEquals(c.id(), filtered.at("/items/0/publicationId").asString());
        assertEquals("docs", filtered.at("/items/0/scope/includes/0").asString());
        assertEquals(0, get("/publications?repositoryId=missing", 200).get("items").size());
        assertEquals(0, get("/publications?after=" + "f".repeat(64), 200).get("items").size());
        assertEquals(0, chat.calls.get()); assertEquals(0, embeddings.calls.get());
    }

    @Test void catalogRejectsBadPaginationWithoutSourceOrSqlLeakage() throws Exception {
        for (String query : List.of("limit=", "limit=0", "limit=51", "limit=no", "after=", "after=../file", "repositoryId=")) {
            assertEquals("INVALID_REQUEST", get("/publications?" + query, 400).get("code").asString());
        }
        assertEquals(0, get("/publications?repositoryId=%27%20OR%20true--", 200).get("items").size());
    }

    @Test void detailsPreservePartialFailedCoverageAndUnknownIdentitySemantics() throws Exception {
        var partial = publish("repo", "a", RepositorySelection.Scope.all(), new ChunkingConfiguration(1, 1));
        var body = get("/publications/" + partial.id(), 200);
        assertEquals(partial.snapshotId(), body.at("/publication/snapshotId").asString());
        assertEquals(partial.derivationId(), body.at("/publication/derivationId").asString());
        assertEquals("a".repeat(40), body.at("/publication/commitSha").asString());
        assertEquals("PARTIAL", body.at("/publication/processingStatus").asString());
        assertEquals(1, body.at("/coverage/chunks").asInt());
        assertFalse(body.get("processingLimitations").isEmpty());
        var snapshot = snapshot("b", RepositorySelection.Scope.all());
        var badInput = new IngestionResult(snapshot, List.of(source(snapshot, "Bad.java", IngestionResult.Format.JAVA,
                "class { broken syntax")), List.of(), List.of());
        var failed = persistence.publish(badInput, process(badInput));
        assertEquals("FAILED", get("/publications/" + failed.id(), 200).at("/publication/processingStatus").asString());
        assertEquals("UNKNOWN_PUBLICATION", get("/publications/" + "0".repeat(64), 404).get("code").asString());
        assertEquals("INVALID_REQUEST", get("/publications/not-an-id", 400).get("code").asString());
    }

    @Test void evidenceReturnsOriginalPersistedTextWithoutRequiringRepositoryFiles() throws Exception {
        var p = publish("repo", "a", RepositorySelection.Scope.all(), ChunkingConfiguration.defaults());
        for (var chunk : p.processing().chunks()) {
            var body = get("/publications/" + p.id() + "/evidence/" + chunk.id(), 200);
            assertEquals(chunk.content(), body.get("content").asString());
            assertEquals(chunk.startLine(), body.at("/evidence/startLine").asInt());
            assertEquals(chunk.endLine(), body.at("/evidence/endLine").asInt());
            assertEquals(chunk.filePath(), body.at("/evidence/path").asString());
            assertEquals(chunk.gitBlobId(), body.at("/evidence/gitBlobId").asString());
            assertEquals(chunk.contentHash(), body.at("/evidence/contentHash").asString());
            assertEquals(chunk.sourceHash(), body.at("/evidence/sourceHash").asString());
            assertEquals(p.snapshotId(), body.at("/evidence/snapshotId").asString());
        }
    }

    @Test void evidenceIsPublicationScopedEvenForSharedSnapshotAndFailsClosedOnCorruption() throws Exception {
        var full = publish("repo", "a", RepositorySelection.Scope.all(), new ChunkingConfiguration(1, 100));
        var limited = publish("repo", "a", RepositorySelection.Scope.all(), new ChunkingConfiguration(1, 1));
        assertEquals(full.snapshotId(), limited.snapshotId());
        var absent = full.processing().chunks().getLast();
        get("/publications/" + limited.id() + "/evidence/" + absent.id(), 404);
        var other = publish("repo", "b", RepositorySelection.Scope.all(), ChunkingConfiguration.defaults());
        get("/publications/" + other.id() + "/evidence/" + absent.id(), 404);
        get("/publications/" + full.id() + "/evidence/not-an-id", 400);
        get("/publications/" + full.id() + "/evidence/..%2F..%2Fetc%2Fpasswd", 400);
        jdbc.update("UPDATE evidence_chunk SET content = ? WHERE snapshot_id = ? AND chunk_id = ?",
                "CORRUPT", full.snapshotId(), absent.id());
        assertEquals("PUBLICATION_INTEGRITY_FAILURE", get("/publications/" + full.id() + "/evidence/" + absent.id(), 500)
                .get("code").asString());
    }

    @Test void allStrategiesUseOneInvocationAndResolveActualCitationsWithoutWrites() throws Exception {
        var p = publish("repo", "a", RepositorySelection.Scope.all(), ChunkingConfiguration.defaults());
        index(p);
        var before = databaseState();
        for (String strategy : List.of("KEYWORD", "VECTOR", "HYBRID")) {
            chat.calls.set(0); embeddings.calls.set(0);
            var body = answer(p.id(), "pricing", strategy, 200);
            assertEquals("SUPPORTED", body.get("status").asString());
            assertEquals(strategy, body.get("requestedRetrievalStrategy").asString());
            assertTrue(body.get("retrievalStrategy").asString().contains(strategy.toLowerCase()));
            assertEquals(1, chat.calls.get());
            assertEquals(strategy.equals("KEYWORD") ? 0 : 1, embeddings.calls.get());
            assertEquals(p.id(), body.at("/publication/publication/publicationId").asString());
            assertTrue(body.get("retrievedEvidence").size() > 0);
            for (var citation : body.get("citations")) {
                String chunk = citation.at("/evidence/chunkId").asString();
                var evidence = get("/publications/" + p.id() + "/evidence/" + chunk, 200);
                assertEquals(p.processing().chunks().stream().filter(c -> c.id().equals(chunk)).findFirst().orElseThrow().content(),
                        evidence.get("content").asString());
            }
        }
        get("/capabilities", 200); get("/publications", 200); get("/publications/" + p.id(), 200);
        assertEquals(before, databaseState());
        try (var connection = app.getBean(DataSource.class).getConnection()) { assertTrue(connection.isReadOnly()); }
    }

    @Test void concurrentStrategiesDoNotMutateSharedSelection() throws Exception {
        var p = publish("repo", "a", RepositorySelection.Scope.all(), ChunkingConfiguration.defaults()); index(p);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = List.of("KEYWORD", "VECTOR", "HYBRID").stream().map(strategy ->
                    executor.submit(() -> answer(p.id(), "pricing", strategy, 200))).toList();
            for (int i = 0; i < tasks.size(); i++) assertEquals(List.of("KEYWORD", "VECTOR", "HYBRID").get(i),
                    tasks.get(i).get().get("requestedRetrievalStrategy").asString());
        }
        assertEquals(3, chat.calls.get()); assertEquals(2, embeddings.calls.get());
    }

    @Test void incompleteIndexesAndProviderFailuresNeverBecomeInsufficientEvidence() throws Exception {
        var p = publish("repo", "a", RepositorySelection.Scope.all(), ChunkingConfiguration.defaults());
        assertEquals(3, get("/capabilities", 200).get("configuredRetrievalStrategies").size());
        for (String strategy : List.of("VECTOR", "HYBRID"))
            assertEquals("EMBEDDING_INDEX_INCOMPLETE", answer(p.id(), "pricing", strategy, 409).get("code").asString());
        assertEquals(0, chat.calls.get()); assertEquals(0, embeddings.calls.get());
        index(p); embeddings.failure = EmbeddingException.Code.MODEL_TIMEOUT;
        assertEquals("EMBEDDING_MODEL_TIMEOUT", answer(p.id(), "pricing", "VECTOR", 504).get("code").asString());
        embeddings.failure = null; chat.failure = QaException.Code.MODEL_UNAVAILABLE;
        assertEquals("MODEL_UNAVAILABLE", answer(p.id(), "pricing", "KEYWORD", 503).get("code").asString());
    }

    @Test void httpJsonContractAndUnknownRoutesAreNormalizedOnTheRealServer() throws Exception {
        var p = publish("repo", "a", RepositorySelection.Scope.all(), ChunkingConfiguration.defaults());
        for (String body : List.of("{\"question\":\"pricing\",\"path\":\"/etc/passwd\"}",
                "{\"question\":12}", "{\"question\":\"\\ud800\"}", "{\"question\":\"pricing\"} {}")) {
            request("POST", "/publications/" + p.id() + "/answers", body, 400);
        }
        request("POST", "/publications/" + p.id() + "/answers", "x".repeat(8193), 413);
        get("/not-an-endpoint", 404);
        request("DELETE", "/publications/" + p.id(), null, 405);
        request("POST", "/publications", "{}", 405);
        assertEquals(0, chat.calls.get()); assertEquals(0, embeddings.calls.get());
    }

    @Test void databaseFailuresAreSafeServiceErrorsAndDoNotRepairTheSchema() throws Exception {
        jdbc.execute("ALTER TABLE processing_publication RENAME TO temporarily_unavailable_publication");
        try {
            var body = get("/publications", 503);
            assertEquals("RETRIEVAL_FAILURE", body.get("code").asString());
            assertFalse(body.toString().contains("processing_publication"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                    + "AND table_name = 'processing_publication'", Integer.class));
        } finally {
            jdbc.execute("ALTER TABLE temporarily_unavailable_publication RENAME TO processing_publication");
        }
    }

    private static PersistedPublication publish(String repository, String commit, RepositorySelection.Scope scope,
                                                ChunkingConfiguration configuration) {
        var snapshot = new IngestionResult.Snapshot(repository, commit.repeat(40), scope, "git-snapshot-v1");
        var input = new IngestionResult(snapshot, List.of(source(snapshot, "docs/pricing.md", IngestionResult.Format.MARKDOWN,
                "# Pricing\r\nShipping pricing is 800 cents.\r\n<script>alert('untrusted')</script>\r\n")), List.of(), List.of("Committed only"));
        return persistence.publish(input, new ArtifactProcessor().process(input, configuration));
    }

    private static float[] vector() { var v = new float[768]; v[0] = 1; return v; }
    private static void index(PersistedPublication p) {
        for (var c : p.processing().chunks()) store.insert(p, c.id(), PROFILE, PROFILE.document(c.content()), vector());
    }
    private static List<List<String>> databaseState() {
        return List.of("repository_snapshot", "source_artifact", "evidence_chunk", "processing_publication",
                        "publication_chunk", "embedding_profile", "chunk_embedding", "flyway_schema_history").stream()
                .map(table -> jdbc.queryForList("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY row_to_json(t)::text", String.class)).toList();
    }
    private static JsonNode answer(String id, String question, String strategy, int status) throws Exception {
        return request("POST", "/publications/" + id + "/answers",
                JSON.writeValueAsString(Map.of("question", question, "retrievalStrategy", strategy)), status);
    }
    private static JsonNode get(String path, int status) throws Exception { return request("GET", path, null, status); }
    private static JsonNode request(String method, String path, String body, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        var response = HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body());
        // Servlet-container rejection of encoded separators occurs before Spring/filter handling.
        if (path.contains("%2F")) return null;
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow()
                .startsWith(status >= 400 ? "application/problem+json" : "application/json"));
        var json = JSON.readTree(response.body());
        if (status >= 400) {
            assertEquals(status, json.get("status").asInt());
            assertNotNull(json.get("code")); assertNull(json.get("trace"));
        }
        return json;
    }

    // Explicit SpringApplicationBuilder source, not component-scanned by other context tests.
    static class Doubles {
        @Bean @Primary Chat testChat() { return new Chat(); }
        @Bean @Primary Embeddings testEmbeddings() { return new Embeddings(); }
    }
    static final class Chat implements ModelGateway {
        final AtomicInteger calls = new AtomicInteger();
        volatile QaException.Code failure;
        @Override public GenerationSettings settings() { return GenerationSettings.defaults(); }
        @Override public Response generate(String system, String context, String schema) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); calls.incrementAndGet();
            if (failure != null) throw new QaException(failure);
            return new Response("{\"status\":\"SUPPORTED\",\"claims\":[{\"text\":\"Pricing is documented.\",\"evidenceKeys\":[\"E1\"]}],\"uncertainties\":[]}",
                    new Diagnostics("test-double", settings().model(), 1, null, null, "stop"));
        }
    }
    static final class Embeddings implements EmbeddingGateway {
        final AtomicInteger calls = new AtomicInteger();
        volatile EmbeddingException.Code failure;
        @Override public EmbeddingProfile resolveProfile() {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return PROFILE;
        }
        @Override public Response embed(EmbeddingProfile profile, EmbeddingProfile.Input input) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); calls.incrementAndGet();
            if (failure != null) throw new EmbeddingException(failure);
            return new Response(vector(), 1);
        }
    }
}
