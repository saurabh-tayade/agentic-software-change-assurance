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
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.changeassurance.evaluation.EvaluationFixtures.*;
import static dev.changeassurance.evaluation.QaEvaluationTests.*;
import static org.junit.jupiter.api.Assertions.*;

class QaComparisonIT {
    @TempDir Path output;
    @Test void capturesRealThreeStrategyQaExecutionsAndRegradesWithoutInference() throws Exception {
        var definition = definitionQa();
        try (var postgres = new PostgreSQLContainer(PostgresTestImage.IMAGE).withPassword(UUID.randomUUID().toString())) {
            postgres.start();
            var config = new HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl()); config.setUsername(postgres.getUsername()); config.setPassword(postgres.getPassword());
            config.setMaximumPoolSize(2);
            try (var ds = new HikariDataSource(config)) {
                Flyway.configure().dataSource(ds).load().migrate();
                var persistence = new EvidencePersistenceService(ds); var input = publication(definition);
                var publication = persistence.publish(input.ingestion(), input.processing());
                var embeddings = new AtomicInteger();
                var gateway = new EmbeddingGateway() {
                    public EmbeddingProfile resolveProfile() { return PROFILE; }
                    public Response embed(EmbeddingProfile expected, EmbeddingProfile.Input input) {
                        assertEquals(PROFILE, expected); embeddings.incrementAndGet();
                        var vector = new float[768]; vector[0] = 1; vector[1 + Math.floorMod(input.text().hashCode(), 767)] = 1;
                        return new Response(vector, 0);
                    }
                };
                assertEquals(EmbeddingIndexingService.Status.COMPLETE,
                        new EmbeddingIndexingService(persistence, new EmbeddingStore(ds), gateway).index(publication.id(), PROFILE).status());
                int indexed = embeddings.get(); var keywords = new KeywordRetrievalService(ds); var vectors = new VectorRetrievalService(ds, gateway);
                var searches = new AtomicInteger();
                var runner = new QaEvaluationRunner(persistence::findPublication, strategy -> {
                    var retriever = EvidenceRetrievers.select(strategy, keywords, vectors, strategy == RetrievalStrategy.KEYWORD ? null : PROFILE);
                    return (p, q, k) -> { searches.incrementAndGet(); return retriever.search(p, q, k); };
                }, model(ABSTAIN));
                var run = runner.execute(definition, definition.scenarios().stream().map(QaComparison.Scenario::id).toList(),
                        publication.id(), PROFILE.id(), PROFILE, null, "TEST_DOUBLE", false);
                assertEquals(33, searches.get()); assertEquals(indexed + 22, embeddings.get());
                assertTrue(run.executions().stream().allMatch(e -> e.failure() == null), () -> run.executions().stream().filter(e -> e.failure() != null).toList().toString());
                var dir = QaEvaluationArtifacts.write(output, run);
                var grades = QaEvaluationArtifacts.grade(QaEvaluationArtifacts.read(dir.resolve("qa-execution.json")));
                assertEquals(33, grades.cases().size());
                assertTrue(grades.cases().stream().allMatch(g -> g.provenance() == QaEvaluationArtifacts.Check.VALID
                        && g.structure() == QaEvaluationArtifacts.Check.VALID && g.citationIdentity() == QaEvaluationArtifacts.Check.NOT_APPLICABLE
                        && g.semanticReviewStatus().equals("NOT_REVIEWED")));
                assertEquals(33, searches.get()); assertEquals(indexed + 22, embeddings.get());
                var kafka = run.executions().stream().filter(e -> e.id().equals("KEYWORD:KW-006")).findFirst().orElseThrow();
                assertFalse(kafka.modelInvoked()); assertTrue(kafka.retrieval().matches().isEmpty());
                assertTrue(run.executions().stream().filter(e -> e.id().equals("VECTOR:KW-006")).findFirst().orElseThrow().modelInvoked());
                new org.springframework.jdbc.core.JdbcTemplate(ds).update("DELETE FROM chunk_embedding WHERE profile_id = ?", PROFILE.id());
                var incomplete = runner.execute(definition, List.of("QA-REG-SHIPPING"), publication.id(), PROFILE.id(), PROFILE, null, "TEST_DOUBLE", false);
                assertNull(incomplete.executions().getFirst().failure());
                for (var e : incomplete.executions().subList(1, 3)) {
                    assertEquals("EMBEDDING_INDEX_INCOMPLETE", e.failure().code()); assertNotNull(e.failure().indexCoverage());
                    assertFalse(e.modelInvoked()); assertNull(e.answer());
                }
                assertEquals(3, QaEvaluationArtifacts.grade(incomplete).cases().size());
            }
        }
    }
}
