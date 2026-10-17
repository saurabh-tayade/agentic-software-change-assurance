package dev.changeassurance.persistence;

import dev.changeassurance.embedding.*;
import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.qa.*;
import dev.changeassurance.retrieval.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.changeassurance.persistence.PersistenceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Existing real keyword/vector SQL composed with deterministic embedding and answer gateways. */
class HybridQuestionAnsweringIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestImage.IMAGE)
            .withPassword(UUID.randomUUID().toString());
    private static DriverManagerDataSource ds;
    private static JdbcTemplate jdbc;
    private static EvidencePersistenceService persistence;
    private static EmbeddingStore store;
    private static final EmbeddingProfile PROFILE = new EmbeddingProfile("ollama", "nomic-embed-text:v1.5", "a".repeat(64),
            768, EmbeddingProfile.DOCUMENT_RECIPE, EmbeddingProfile.QUERY_RECIPE, EmbeddingProfile.Options.defaults(), "cosine");
    private Embeddings embeddings;
    private KeywordRetrievalService keyword;
    private VectorRetrievalService vector;
    @BeforeAll static void start() {
        POSTGRES.start();
        ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds); persistence = new EvidencePersistenceService(ds); store = new EmbeddingStore(ds);
        Flyway.configure().dataSource(ds).load().migrate();
    }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE chunk_embedding, embedding_profile, publication_chunk, processing_publication, evidence_chunk, source_artifact, repository_snapshot");
        embeddings = new Embeddings(); keyword = new KeywordRetrievalService(ds); vector = new VectorRetrievalService(ds, embeddings);
    }
    private PersistedPublication publish(String commit) {
        var input = input(commit, RepositorySelection.Scope.all());
        return persistence.publish(input, process(input));
    }
    private static float[] vector(float x, float y) { var v = new float[768]; v[0] = x; v[1] = y; return v; }
    private void index(PersistedPublication p) {
        store.register(PROFILE);
        for (var c : p.processing().chunks()) store.insert(p, c.id(), PROFILE, PROFILE.document(c.content()),
                c.filePath().endsWith(".java") ? vector(1, 0) : vector(0, 1));
    }
    private EvidenceRetriever selected(RetrievalStrategy strategy) {
        return EvidenceRetrievers.select(strategy, keyword, vector, strategy == RetrievalStrategy.KEYWORD ? null : PROFILE);
    }
    private static class Embeddings implements EmbeddingGateway {
        int calls;
        EmbeddingException.Code failure;
        EmbeddingProfile installed = PROFILE;
        @Override public EmbeddingProfile resolveProfile() { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return installed; }
        @Override public Response embed(EmbeddingProfile p, EmbeddingProfile.Input input) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(input.text().startsWith("search_query: ")); calls++;
            if (failure != null) throw new EmbeddingException(failure);
            return new Response(vector(1, 0), 1);
        }
    }
    private static class Chat implements ModelGateway {
        int calls;
        String context;
        String output = "{\"status\":\"SUPPORTED\",\"claims\":[{\"text\":\"Selected source is available.\",\"evidenceKeys\":[\"E1\"]}],\"uncertainties\":[]}";
        @Override public GenerationSettings settings() { return GenerationSettings.defaults(); }
        @Override public Response generate(String prompt, String data, String schema) {
            context = data; calls++;
            return new Response(output, new Diagnostics("test-double", settings().model(), 7, null, null, "stop"));
        }
    }
    @Test void hybridCombinesBothBranchesAndPreservesPublicationAndOriginalChunks() {
        var p = publish("a"); index(p);
        var result = selected(RetrievalStrategy.HYBRID).search(p.id(), "café", 5).orElseThrow();
        assertEquals(p.id(), result.publication().id());
        assertTrue(result.strategy().startsWith(HybridRetrievalService.STRATEGY));
        for (var m : result.matches()) assertTrue(p.processing().chunks().contains(m.evidence()));
        assertEquals(1, embeddings.calls);
        assertEquals(p, persistence.findPublication(p.id()).orElseThrow());
    }
    @Test void noKeywordMatchStillReturnsVectorEvidenceAndDoesNotFallback() {
        var p = publish("a"); index(p);
        assertTrue(keyword.search(p.id(), "zqxunknown", 5).orElseThrow().matches().isEmpty());
        var result = selected(RetrievalStrategy.HYBRID).search(p.id(), "zqxunknown", 5).orElseThrow();
        assertFalse(result.matches().isEmpty());
        assertTrue(result.limitations().stream().anyMatch(s -> s.contains("keywordCandidates=0")));
        assertEquals(1, embeddings.calls);
    }
    @Test void candidateCallsAreBoundedAndChatGetsFinalRanks() {
        var snapshot = snapshot("a", RepositorySelection.Scope.all());
        var sources = new ArrayList<IngestionResult.SourceArtifact>();
        for (int i = 0; i < 30; i++) sources.add(source(snapshot, "file-%02d.md".formatted(i), IngestionResult.Format.MARKDOWN, "# Pricing\nPricing rules.\n"));
        var input = new IngestionResult(snapshot, sources, List.of(), List.of());
        var p = persistence.publish(input, process(input)); index(p);
        var depth = new AtomicInteger();
        EvidenceRetriever counted = (id, q, k) -> { depth.set(k); return keyword.search(id, q, k); };
        var result = new HybridRetrievalService(counted, vector).search(p.id(), "pricing", 3, PROFILE).orElseThrow();
        assertEquals(20, depth.get()); assertEquals(3, result.resultCount()); assertEquals(1, embeddings.calls);
        assertTrue(result.limitations().stream().anyMatch(s -> s.contains("keywordCandidates=20; vectorCandidates=20")));
        assertEquals(List.of(1, 2, 3), result.matches().stream().map(RetrievalResult.Match::rank).toList());
    }
    @Test void publicationsRemainIsolatedEvenWhenOtherPublicationsAreIndexed() {
        var a = publish("a"); var b = publish("b"); index(b);
        assertEquals(VectorRetrievalException.Code.INDEX_INCOMPLETE, assertThrows(VectorRetrievalException.class,
                () -> selected(RetrievalStrategy.HYBRID).search(a.id(), "café", 5)).code());
        index(a);
        var result = selected(RetrievalStrategy.HYBRID).search(a.id(), "café", 5).orElseThrow();
        assertTrue(result.matches().stream().allMatch(m -> m.evidence().snapshot().equals(a.ingestion().snapshot())));
    }
    @Test void unknownAndEmptyPublicationsUseNoInference() {
        for (var s : RetrievalStrategy.values()) assertTrue(selected(s).search("0".repeat(64), "query", 5).isEmpty());
        var input = new IngestionResult(snapshot("a", RepositorySelection.Scope.all()), List.of(), List.of(), List.of());
        var p = persistence.publish(input, process(input));
        for (var s : RetrievalStrategy.values()) {
            var chat = new Chat();
            var answer = new GroundedQuestionAnsweringService(selected(s), chat).answer(p.id(), "query", AnsweringConfiguration.defaults());
            assertEquals(GroundedAnswer.Status.INSUFFICIENT_EVIDENCE, answer.status()); assertEquals(0, chat.calls);
        }
        assertEquals(0, embeddings.calls);
    }
    @Test void partialIndexFailsBeforeKeywordOrChatAndReportsCoverage() {
        var p = publish("a"); index(p);
        jdbc.update("DELETE FROM chunk_embedding WHERE chunk_id = ?", p.processing().chunks().getFirst().id());
        var keywordCalls = new AtomicInteger();
        EvidenceRetriever counted = (id, q, k) -> { keywordCalls.incrementAndGet(); return keyword.search(id, q, k); };
        var hybrid = new HybridRetrievalService(counted, vector);
        var chat = new Chat();
        var error = assertThrows(QaException.class, () -> new GroundedQuestionAnsweringService(
                (id, q, k) -> hybrid.search(id, q, k, PROFILE), chat).answer(p.id(), "café", AnsweringConfiguration.defaults()));
        assertEquals(QaException.Code.EMBEDDING_INDEX_INCOMPLETE, error.code());
        assertEquals(1, ((VectorRetrievalException) error.getCause()).coverage().missingChunks());
        assertEquals(0, keywordCalls.get()); assertEquals(0, chat.calls); assertEquals(0, embeddings.calls);
    }
    @Test void embeddingAndAnswerModelFailuresRemainSeparate() {
        var p = publish("a"); index(p);
        for (var s : List.of(RetrievalStrategy.VECTOR, RetrievalStrategy.HYBRID)) {
            var chat = new Chat(); var qa = new GroundedQuestionAnsweringService(selected(s), chat);
            embeddings.failure = EmbeddingException.Code.MODEL_UNAVAILABLE;
            assertEquals(QaException.Code.EMBEDDING_MODEL_UNAVAILABLE, assertThrows(QaException.class,
                    () -> qa.answer(p.id(), "café", AnsweringConfiguration.defaults())).code());
            embeddings.failure = EmbeddingException.Code.MODEL_TIMEOUT;
            assertEquals(QaException.Code.EMBEDDING_MODEL_TIMEOUT, assertThrows(QaException.class,
                    () -> qa.answer(p.id(), "café", AnsweringConfiguration.defaults())).code());
            assertEquals(0, chat.calls);
        }
        assertEquals(4, embeddings.calls);
    }
    @Test void profileMismatchAndCorruptEvidenceFailBeforeAnAnswer() {
        var p = publish("a"); index(p); var chat = new Chat();
        var qa = new GroundedQuestionAnsweringService(selected(RetrievalStrategy.HYBRID), chat);
        embeddings.installed = new EmbeddingProfile(PROFILE.provider(), PROFILE.model(), "b".repeat(64), 768,
                PROFILE.documentRecipe(), PROFILE.queryRecipe(), PROFILE.options(), "cosine");
        assertEquals(QaException.Code.EMBEDDING_PROFILE_MISMATCH, assertThrows(QaException.class,
                () -> qa.answer(p.id(), "café", AnsweringConfiguration.defaults())).code());
        embeddings.installed = PROFILE;
        jdbc.update("UPDATE evidence_chunk SET content = 'corrupted' WHERE chunk_id = ?", p.processing().chunks().getFirst().id());
        assertEquals(QaException.Code.PUBLICATION_INTEGRITY_FAILURE, assertThrows(QaException.class,
                () -> qa.answer(p.id(), "café", AnsweringConfiguration.defaults())).code());
        assertEquals(0, chat.calls);
    }
    @Test void differentBranchPublicationCannotBeFused() {
        var a = publish("a"); var b = publish("b"); index(a); index(b);
        EvidenceRetriever incorrect = (id, q, k) -> keyword.search(b.id(), q, k);
        assertThrows(PublicationIntegrityException.class, () -> new HybridRetrievalService(incorrect, vector).search(a.id(), "café", 5, PROFILE));
    }
    @Test void disappearingPublicationBetweenBranchesFailsInsteadOfReturningUnknown() {
        var p = publish("a"); index(p);
        assertThrows(PublicationIntegrityException.class, () -> new HybridRetrievalService((id, q, k) -> Optional.empty(), vector)
                .search(p.id(), "café", 5, PROFILE));
    }
    @Test void allStrategiesPreserveRankedContextAndApplicationControlledCitations() {
        var p = publish("a"); index(p);
        for (var strategy : RetrievalStrategy.values()) {
            var chat = new Chat();
            var run = new GroundedQuestionAnsweringService(selected(strategy), chat)
                    .answerWithRetrieval(p.id(), "café", AnsweringConfiguration.defaults());
            var answer = run.answer();
            var selected = answer.contextSelection().selected().getFirst();
            var cited = answer.citations().getFirst();
            assertEquals(run.retrieval().matches().getFirst().evidence(), cited.evidence());
            assertEquals(selected.chunkId(), cited.evidence().id()); assertEquals(p.id(), cited.publicationId());
            assertEquals(run.retrieval().strategy(), answer.retrievalStrategy());
            assertTrue(answer.retrievalLimitations().containsAll(run.retrieval().limitations()));
            assertEquals(GroundedAnswer.Status.SUPPORTED, answer.status()); assertEquals(1, chat.calls);
            assertEquals(QaPrompt.VERSION, answer.promptVersion());
            assertEquals(7, answer.invocation().durationMillis());
        }
        assertEquals(2, embeddings.calls); // keyword never calls the embedding model
    }
    @Test void nonKeywordMalformedAnswersAndInventedCitationsStillFailClosed() {
        var p = publish("a"); index(p);
        for (var strategy : List.of(RetrievalStrategy.VECTOR, RetrievalStrategy.HYBRID)) {
            var chat = new Chat(); var qa = new GroundedQuestionAnsweringService(selected(strategy), chat);
            chat.output = "not JSON";
            assertEquals(QaException.Code.MALFORMED_MODEL_OUTPUT, assertThrows(QaException.class,
                    () -> qa.answer(p.id(), "café", AnsweringConfiguration.defaults())).code());
            chat.output = "{\"status\":\"SUPPORTED\",\"claims\":[{\"text\":\"Invented\",\"evidenceKeys\":[\"E999\"]}],\"uncertainties\":[]}";
            assertEquals(QaException.Code.INVALID_CITATION, assertThrows(QaException.class,
                    () -> qa.answer(p.id(), "café", AnsweringConfiguration.defaults())).code());
            chat.output = "{\"status\":\"INSUFFICIENT_EVIDENCE\",\"claims\":[],\"uncertainties\":[\"Selected sources cannot answer this.\"]}";
            assertEquals(GroundedAnswer.Status.INSUFFICIENT_EVIDENCE, qa.answer(p.id(), "café", AnsweringConfiguration.defaults()).status());
        }
    }
    @Test @ExtendWith(OutputCaptureExtension.class)
    void configuredCliUsesEachRequestedStrategyAndPrintsActualRetrievalTrace(CapturedOutput output) {
        var p = publish("a"); index(p);
        for (var strategy : RetrievalStrategy.values()) {
            var chat = new Chat();
            var runner = new ApplicationContextRunner().withUserConfiguration(QaConfiguration.class)
                    .withPropertyValues("spring.profiles.active=persistence,qa", "qa.retrieval-strategy=" + strategy)
                    .withBean(DataSource.class, () -> ds).withBean(KeywordRetrievalService.class, () -> keyword)
                    .withBean(VectorRetrievalService.class, () -> vector)
                    .withBean("testChat", ModelGateway.class, () -> chat, definition -> definition.setPrimary(true));
            if (strategy != RetrievalStrategy.KEYWORD) runner = runner.withPropertyValues("qa.embedding-profile-id=" + PROFILE.id());
            runner.run(context -> {
                assertNull(context.getStartupFailure());
                var environment = new MockEnvironment().withProperty("qa.command", "answer").withProperty("qa.question", "café")
                        .withProperty("qa.publication-id", p.id()).withProperty("qa.retrieval-strategy", strategy.name());
                if (strategy != RetrievalStrategy.KEYWORD) environment.setProperty("qa.embedding-profile-id", PROFILE.id());
                var application = new GenericApplicationContext(); application.refresh();
                int start = output.getOut().length();
                new QaCliRunner(environment, persistence, context.getBean(GroundedQuestionAnsweringService.class), application)
                        .run(new DefaultApplicationArguments());
                var json = new JsonMapper().readTree(output.getOut().substring(start));
                assertEquals(strategy.name(), json.path("requestedRetrievalStrategy").asString());
                assertEquals(json.path("retrievalStrategy").asString(), json.path("retrieval").path("strategy").asString());
                assertFalse(json.path("retrieval").path("matches").isEmpty());
                assertEquals(p.id(), json.path("publication").path("id").asString());
                if (strategy != RetrievalStrategy.KEYWORD) assertEquals(PROFILE.id(), json.path("embeddingProfileId").asString());
                assertFalse(application.isActive()); assertEquals(1, chat.calls);
            });
        }
        assertEquals(2, embeddings.calls);
    }
}
