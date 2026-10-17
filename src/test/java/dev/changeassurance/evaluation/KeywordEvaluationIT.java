package dev.changeassurance.evaluation;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.changeassurance.ingestion.GitSnapshotIngestor;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.persistence.EvidencePersistenceService;
import dev.changeassurance.processing.ArtifactProcessor;
import dev.changeassurance.processing.ChunkingConfiguration;
import dev.changeassurance.retrieval.KeywordRetrievalService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static dev.changeassurance.evaluation.RetrievalGrading.*;
import static org.junit.jupiter.api.Assertions.*;

/** Lightweight mandatory baseline runner. Quality is measured, not asserted to be perfect. */
class KeywordEvaluationIT {
    @TempDir Path repository;
    private static final JsonMapper JSON = new JsonMapper();

    @Test void evaluatesVersionedQueriesThroughRealGitProcessingPersistenceAndSearch() throws Exception {
        Path corpus = Path.of("evaluation/corpora/parcel-quotes/v1");
        try (var paths = Files.walk(corpus)) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                var destination = repository.resolve(corpus.relativize(path));
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        }
        git("init", "-q", "--initial-branch=main");
        git("add", "--all");
        git("commit", "-q", "-m", "Parcel Quotes v1 retrieval baseline");
        var definitionPath = Path.of("evaluation/retrieval/keyword-v1.json");
        var definition = JSON.readTree(Files.readString(definitionPath));
        var originalPath = Path.of(definition.get("original_scenarios").asString());
        var original = JSON.readTree(Files.readString(originalPath));
        assertEquals(KeywordRetrievalService.STRATEGY, definition.get("strategy").asString());
        var cases = new ArrayList<JsonNode>();
        original.get("scenarios").forEach(cases::add);
        definition.get("scenarios").forEach(cases::add);
        assertEquals(15, cases.size());
        var caseIds = new HashSet<String>();
        var rows = new ArrayList<Map<String, Object>>();
        var timings = new ArrayList<Double>();
        int topK = definition.get("top_k").asInt();
        try (var postgres = new PostgreSQLContainer(dev.changeassurance.persistence.PostgresTestImage.IMAGE).withPassword(UUID.randomUUID().toString())) {
            postgres.start();
            var pool = new HikariConfig();
            pool.setJdbcUrl(postgres.getJdbcUrl()); pool.setUsername(postgres.getUsername()); pool.setPassword(postgres.getPassword());
            pool.setMaximumPoolSize(2);
            try (var ds = new HikariDataSource(pool)) {
                Flyway.configure().dataSource(ds).load().migrate();
                var persistence = new EvidencePersistenceService(ds);
                var retrieval = new KeywordRetrievalService(ds);
                var ingestion = new GitSnapshotIngestor().ingest(new RepositorySelection(repository, "parcel-quotes-v1", "HEAD"));
                for (var scenario : cases) {
                    String id = scenario.get("id").asString();
                    assertTrue(caseIds.add(id));
                    String query = scenario.has("query") ? scenario.get("query").asString() : scenario.get("question").asString();
                    var includes = new ArrayList<String>();
                    if (scenario.has("scope_includes")) scenario.get("scope_includes").forEach(v -> includes.add(v.asString()));
                    var selected = includes.isEmpty() ? ingestion : new GitSnapshotIngestor().ingest(new RepositorySelection(repository,
                            "parcel-quotes-v1", ingestion.snapshot().commitSha(), new RepositorySelection.Scope(includes, List.of())));
                    var configuration = new ChunkingConfiguration(definition.get("max_lines").asInt(),
                            scenario.has("max_chunks") ? scenario.get("max_chunks").asInt() : definition.get("max_chunks").asInt());
                    var saved = persistence.publish(selected, new ArtifactProcessor().process(selected, configuration));
                    var expected = new ArrayList<Reference>();
                    for (var ref : scenario.get("expected_evidence")) {
                        var reference = new Reference(ref.get("path").asString(), ref.get("start_line").asInt(), ref.get("end_line").asInt());
                        var source = ingestion.artifacts().stream().filter(a -> a.path().equals(reference.path())).findFirst().orElseThrow();
                        assertTrue(reference.end() <= source.content().lines().count());
                        expected.add(reference);
                    }
                    for (int i = 0; i < definition.get("warmup_runs").asInt(); i++) retrieval.search(saved.id(), query, topK);
                    var result = retrieval.search(saved.id(), query, topK).orElseThrow();
                    var caseTimings = new ArrayList<Double>();
                    for (int i = 0; i < definition.get("measured_runs").asInt(); i++) {
                        long start = System.nanoTime();
                        var repeated = retrieval.search(saved.id(), query, topK).orElseThrow();
                        double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                        assertEquals(result, repeated);
                        caseTimings.add(elapsed); timings.add(elapsed);
                    }
                    var ranked = result.matches().stream().map(m -> new Reference(m.evidence().filePath(),
                            m.evidence().startLine(), m.evidence().endLine())).toList();
                    for (var hit : result.matches()) {
                        assertTrue(saved.processing().chunks().contains(hit.evidence()));
                        assertEquals(ingestion.snapshot().commitSha(), hit.evidence().snapshot().commitSha());
                    }
                    var grade = grade(expected, ranked);
                    var row = new LinkedHashMap<String, Object>();
                    row.put("id", id); row.put("query", query); row.put("publication", saved.id());
                    row.put("scope", selected.snapshot().scope()); row.put("configuration", configuration.identity());
                    row.put("status", result.publication().metadata().status());
                    row.put("ingestion_coverage", result.publication().metadata().ingestionCoverage());
                    row.put("grade", grade); row.put("matches", result.matches().stream().map(m -> Map.of(
                            "rank", m.rank(), "score", m.score(), "chunk_id", m.evidence().id(), "path", m.evidence().filePath(),
                            "start_line", m.evidence().startLine(), "end_line", m.evidence().endLine())).toList());
                    row.put("mean_latency_ms", caseTimings.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
                    rows.add(row);
                }
                var graded = rows.stream().map(r -> (Grade) r.get("grade")).filter(g -> g.recall() != null).toList();
                timings.sort(Double::compare);
                var report = new LinkedHashMap<String, Object>();
                report.put("dataset", definition.get("version").asString()); report.put("strategy", KeywordRetrievalService.STRATEGY);
                report.put("definition_sha256", sha(definitionPath)); report.put("original_scenarios_sha256", sha(originalPath));
                report.put("corpus_manifest_sha256", sha(corpus.resolve("manifest.json")));
                report.put("implementation_sha256", Map.of(
                        "service", sha(Path.of("src/main/java/dev/changeassurance/retrieval/KeywordRetrievalService.java")),
                        "integrity", sha(Path.of("src/main/java/dev/changeassurance/persistence/PublicationIntegrity.java")),
                        "migration", sha(Path.of("src/main/resources/db/migration/V2__keyword_retrieval.sql")),
                        "grader", sha(Path.of("src/test/java/dev/changeassurance/evaluation/RetrievalGrading.java"))));
                report.put("commit", ingestion.snapshot().commitSha()); report.put("top_k", topK);
                report.put("query_count", rows.size()); report.put("queries_with_expected_evidence", graded.size());
                report.put("macro_recall_at_k", graded.stream().mapToDouble(Grade::recall).average().orElseThrow());
                report.put("mrr", graded.stream().mapToDouble(Grade::reciprocalRank).average().orElseThrow());
                report.put("latency_samples", timings.size());
                report.put("mean_latency_ms", timings.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
                report.put("median_latency_ms", timings.get(timings.size() / 2));
                report.put("p95_latency_ms", timings.get((int) Math.ceil(timings.size() * .95) - 1));
                report.put("environment", Map.of("java", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                        "architecture", System.getProperty("os.arch"), "available_processors", Runtime.getRuntime().availableProcessors(),
                        "postgres", new JdbcTemplate(ds).queryForObject("SELECT version()", String.class),
                        "connection_pool_size", 2, "warmup_runs_per_query", definition.get("warmup_runs").asInt()));
                report.put("scenarios", rows);
                Path output = Path.of("target/keyword-baseline.json");
                Files.createDirectories(output.getParent());
                Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
                System.out.println("KEYWORD BASELINE: queries=" + rows.size() + " recall@" + topK + "=" + report.get("macro_recall_at_k")
                        + " MRR=" + report.get("mrr") + " mean_ms=" + report.get("mean_latency_ms") + " report=" + output);
            }
        }
    }

    private void git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_AUTHOR_DATE", "2026-01-01T00:00:00Z");
        builder.environment().put("GIT_COMMITTER_DATE", "2026-01-01T00:00:00Z");
        var process = builder.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
