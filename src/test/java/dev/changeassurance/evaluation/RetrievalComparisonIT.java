package dev.changeassurance.evaluation;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.changeassurance.embedding.*;
import dev.changeassurance.persistence.*;
import dev.changeassurance.retrieval.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.changeassurance.evaluation.EvaluationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalComparisonIT {
    @TempDir Path output;

    @Test void comparesActualRetrieversAndRegradesCapturedEvidenceWithoutProviderAccess() throws Exception {
        var definition = definition();
        try (var postgres = new PostgreSQLContainer(PostgresTestImage.IMAGE).withPassword(UUID.randomUUID().toString())) {
            postgres.start();
            var config = new HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl()); config.setUsername(postgres.getUsername()); config.setPassword(postgres.getPassword());
            config.setMaximumPoolSize(2);
            try (var ds = new HikariDataSource(config)) {
                Flyway.configure().dataSource(ds).load().migrate();
                var persistence = new EvidencePersistenceService(ds);
                var publications = new TreeMap<String, PersistedPublication>();
                for (var entry : publications(definition).entrySet())
                    publications.put(entry.getKey(), persistence.publish(entry.getValue().ingestion(), entry.getValue().processing()));
                var calls = new AtomicInteger();
                var gateway = new EmbeddingGateway() {
                    public EmbeddingProfile resolveProfile() { return PROFILE; }
                    public Response embed(EmbeddingProfile expected, EmbeddingProfile.Input input) {
                        assertEquals(PROFILE, expected); calls.incrementAndGet();
                        // Deterministic 768D vectors test real pgvector SQL, not model quality.
                        var vector = new float[768]; vector[0] = 1;
                        vector[1 + Math.floorMod(input.text().hashCode(), 767)] = 1;
                        return new Response(vector, 0);
                    }
                };
                var indexer = new EmbeddingIndexingService(persistence, new EmbeddingStore(ds), gateway);
                for (var publication : publications.values()) assertEquals(EmbeddingIndexingService.Status.COMPLETE, indexer.index(publication.id(), PROFILE).status());
                int indexingCalls = calls.get();
                var keyword = new KeywordRetrievalService(ds);
                var vector = new VectorRetrievalService(ds, gateway);
                var runner = new RetrievalEvaluationRunner(persistence::findPublication,
                        strategy -> EvidenceRetrievers.select(strategy, keyword, vector, strategy == RetrievalStrategy.KEYWORD ? null : PROFILE));
                var run = runner.execute(definition, ids(publications), PROFILE.id(), PROFILE, null, "TEST_DOUBLE");
                assertEquals(90, run.executions().size());
                assertTrue(run.executions().stream().allMatch(e -> e.failure() == null), () -> run.executions().stream().filter(e -> e.failure() != null).toList().toString());
                assertEquals(indexingCalls + 60, calls.get(), "One query embedding for each VECTOR/HYBRID execution, including each K");
                var directory = RetrievalEvaluationArtifacts.write(output, run);
                var grades = RetrievalEvaluationArtifacts.grade(RetrievalEvaluationArtifacts.read(directory.resolve("execution.json")));
                assertEquals(indexingCalls + 60, calls.get(), "Offline grading never invokes embeddings");
                var baseline = RetrievalComparison.JSON.readTree(Files.readString(Path.of("evaluation/retrieval/baseline-v1.json")));
                var keyword5 = grades.aggregates().stream().filter(g -> g.strategy() == RetrievalStrategy.KEYWORD && g.topK() == 5).findFirst().orElseThrow();
                assertEquals(14, keyword5.qualityDenominator());
                assertEquals(baseline.path("macro_recall_at_k").asDouble(), keyword5.macroRecallAtK(), 1e-12);
                assertEquals(baseline.path("mrr").asDouble(), keyword5.mrrAtK(), 1e-12);
                for (var execution : run.executions()) {
                    var saved = publications.values().stream().filter(p -> p.id().equals(execution.publicationId())).findFirst().orElseThrow();
                    execution.result().matches().forEach(m -> assertTrue(saved.processing().chunks().contains(m.evidence())));
                }
                // Missing a derived row remains an explicit failure and never becomes keyword fallback.
                new org.springframework.jdbc.core.JdbcTemplate(ds).update("DELETE FROM chunk_embedding WHERE profile_id = ?", PROFILE.id());
                var incomplete = runner.execute(definition, ids(publications), PROFILE.id(), PROFILE, null, "TEST_DOUBLE");
                assertEquals(60, incomplete.executions().stream().filter(e -> "VECTOR_INDEX_INCOMPLETE".equals(e.failure())).count());
                assertEquals(30, incomplete.executions().stream().filter(e -> e.failure() == null).count());
            }
        }
    }
}
