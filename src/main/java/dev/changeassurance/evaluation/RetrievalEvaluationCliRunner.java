package dev.changeassurance.evaluation;

import dev.changeassurance.embedding.EmbeddingProfile;
import dev.changeassurance.embedding.EmbeddingStore;
import dev.changeassurance.persistence.EvidencePersistenceService;
import dev.changeassurance.retrieval.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit local execution/offline grading. Prints paths and counts, never evidence or credentials. */
@Component
@ConditionalOnProperty(name = "evaluation.command")
public final class RetrievalEvaluationCliRunner implements ApplicationRunner {
    private final Environment env;
    private final ObjectProvider<EvidencePersistenceService> publications;
    private final ObjectProvider<KeywordRetrievalService> keyword;
    private final ObjectProvider<VectorRetrievalService> vector;
    private final ObjectProvider<DataSource> dataSource;
    private final ConfigurableApplicationContext application;
    public RetrievalEvaluationCliRunner(Environment env, ObjectProvider<EvidencePersistenceService> publications,
                                         ObjectProvider<KeywordRetrievalService> keyword, ObjectProvider<VectorRetrievalService> vector,
                                         ObjectProvider<DataSource> dataSource, ConfigurableApplicationContext application) {
        this.env = env; this.publications = publications; this.keyword = keyword; this.vector = vector;
        this.dataSource = dataSource; this.application = application;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        try {
            for (String command : List.of("qa.command", "vector.command", "embeddings.command"))
                if (env.containsProperty(command)) throw new IllegalArgumentException("Use one developer command per invocation");
            if (required("evaluation.command").startsWith("qa-")) {
                QaEvaluationCommands.run(env, publications, keyword, vector, dataSource, application);
                return;
            }
            if ("grade".equals(required("evaluation.command"))) {
                Path input = Path.of(required("evaluation.execution"));
                var run = RetrievalEvaluationArtifacts.read(input);
                Path output = input.toAbsolutePath().getParent().resolve("grades-" + UUID.randomUUID() + ".json");
                RetrievalEvaluationArtifacts.writeNew(output, RetrievalEvaluationArtifacts.grade(run));
                System.out.println("OFFLINE RETRIEVAL GRADES: " + output);
                return;
            }
            if (!"run".equals(required("evaluation.command"))) throw new IllegalArgumentException("Expected run or grade");
            var persistence = publications.getIfAvailable();
            var lexical = keyword.getIfAvailable();
            if (persistence == null || lexical == null) throw new IllegalArgumentException("Evaluation execution requires persistence");
            var definition = RetrievalComparison.load(Path.of("."), Path.of(env.getProperty("evaluation.definition",
                    "evaluation/retrieval/retrieval-comparison-v1.json")));
            var ids = new TreeMap<String, String>();
            ids.put("full", required("evaluation.publication-id"));
            for (String variant : List.of("scoped", "limited")) {
                String id = env.getProperty("evaluation." + variant + "-publication-id");
                if (id != null) ids.put(variant, id);
            }
            String profileId = env.getProperty("evaluation.profile-id");
            EmbeddingProfile profile = profileId == null ? null : new EmbeddingStore(dataSource.getObject()).findProfile(profileId).orElse(null);
            var semantic = vector.getIfAvailable();
            var runner = new RetrievalEvaluationRunner(persistence::findPublication, strategy -> {
                if (strategy != RetrievalStrategy.KEYWORD && semantic == null) return null;
                return EvidenceRetrievers.select(strategy, lexical, semantic, strategy == RetrievalStrategy.KEYWORD ? null : profile);
            });
            var run = runner.execute(definition, ids, profileId, profile, applicationMetadata(), "LIVE");
            Path output = RetrievalEvaluationArtifacts.write(Path.of("target/evaluation"), run);
            long failed = run.executions().stream().filter(e -> e.failure() != null).count();
            System.out.println("RETRIEVAL EVALUATION: attempts=" + run.executions().size() + " failed=" + failed + " artifacts=" + output);
            if (failed > 0) throw new IllegalStateException("Evaluation incomplete; inspect categorized failures in local artifacts");
        } finally { application.close(); }
    }
    private String required(String name) {
        String value = env.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
    static RetrievalEvaluationRun.Application applicationMetadata() {
        String commit = git("rev-parse", "HEAD");
        if (commit != null && !commit.matches("[0-9a-f]{40}|[0-9a-f]{64}")) commit = null;
        String status = git("status", "--porcelain", "--untracked-files=normal");
        String sourceHash = null;
        try {
            var hashes = new TreeMap<String, String>();
            for (String directory : List.of("src/main", "evaluation")) {
                try (var paths = Files.walk(Path.of(directory))) {
                    for (Path path : paths.filter(Files::isRegularFile).sorted().toList())
                        hashes.put(path.toString(), RetrievalComparison.hash(RetrievalComparison.boundedRead(path, 2_000_000)));
                }
            }
            hashes.put("pom.xml", RetrievalComparison.hash(Files.readAllBytes(Path.of("pom.xml"))));
            sourceHash = RetrievalComparison.hash(RetrievalComparison.JSON.writeValueAsBytes(hashes));
        } catch (Exception unavailable) { /* Packaged execution may not have a source checkout. */ }
        return new RetrievalEvaluationRun.Application(RetrievalEvaluationCliRunner.class.getPackage().getImplementationVersion(),
                commit, status == null ? null : !status.isEmpty(), sourceHash);
    }
    private static String git(String... args) {
        try {
            var command = new ArrayList<>(List.of("git")); command.addAll(List.of(args));
            var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); return null; }
            if (process.exitValue() != 0) return null;
            byte[] bytes = process.getInputStream().readNBytes(65537);
            return bytes.length > 65536 ? null : new String(bytes, StandardCharsets.UTF_8).strip();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        catch (Exception unavailable) { return null; }
    }
}
