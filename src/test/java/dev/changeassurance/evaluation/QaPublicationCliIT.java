package dev.changeassurance.evaluation;

import dev.changeassurance.ingestion.GitSnapshotIngestor;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.persistence.*;
import dev.changeassurance.processing.ArtifactProcessor;
import dev.changeassurance.processing.ChunkingConfiguration;
import dev.changeassurance.qa.QaCliRunner;
import dev.changeassurance.retrieval.KeywordRetrievalService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual CLI -> Git ingestion -> processing -> immutable PostgreSQL publication; no Ollama. */
class QaPublicationCliIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestImage.IMAGE)
            .withPassword(UUID.randomUUID().toString());
    private static EvidencePersistenceService persistence;
    private static KeywordRetrievalService retrieval;
    @TempDir Path repository;
    private String commit;

    @BeforeAll static void database() {
        POSTGRES.start();
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        persistence = new EvidencePersistenceService(ds); retrieval = new KeywordRetrievalService(ds);
    }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void corpus() throws Exception {
        Path source = Path.of("evaluation/corpora/parcel-quotes/v1");
        try (var paths = Files.walk(source)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                var destination = repository.resolve(source.relativize(path));
                Files.createDirectories(destination.getParent()); Files.copy(path, destination);
            }
        }
        git("init", "-q", "--initial-branch=main"); git("add", "--all");
        git("commit", "-q", "-m", "Publication CLI fixture"); commit = git("rev-parse", "HEAD").strip();
    }
    @Test @ExtendWith(OutputCaptureExtension.class)
    void defaultsPreserveLegacyPublicationIdentityAndExplicitDefaults(CapturedOutput output) {
        var legacyInput = new GitSnapshotIngestor().ingest(new RepositorySelection(repository, "parcel-quotes-v1", commit));
        var legacy = persistence.publish(legacyInput, new ArtifactProcessor().process(legacyInput, new ChunkingConfiguration(80, 10000)));
        var defaultPublication = publish(options(), output);
        assertEquals(legacy, defaultPublication);
        assertEquals(RepositorySelection.Scope.all(), defaultPublication.ingestion().snapshot().scope());
        assertEquals(new ChunkingConfiguration(80, 10000), defaultPublication.processing().configuration());
        var explicit = options().withProperty("qa.chunk-max-lines", "80").withProperty("qa.chunk-max-chunks", "10000");
        assertEquals(defaultPublication, publish(explicit, output));
        assertEquals(defaultPublication, publish(options(), output));
    }
    @ParameterizedTest @ValueSource(strings = {"KW-007", "KW-008", "combined"})
    @ExtendWith(OutputCaptureExtension.class)
    void publishesExplicitVariantsWithCanonicalScopeAndDeterministicIdentity(String variant, CapturedOutput output) throws Exception {
        var environment = options().withProperty("qa.chunk-max-lines", "4");
        if (variant.equals("KW-007")) environment.withProperty("qa.scope-includes", "src");
        else if (variant.equals("combined")) environment.withProperty("qa.scope-includes", " tests, src,src ");
        if (!variant.equals("KW-007")) environment.withProperty("qa.chunk-max-chunks", "1");
        var publication = publish(environment, output);
        assertEquals(publication, publish(environment, output));
        assertEquals(commit, publication.ingestion().snapshot().commitSha());
        if (variant.equals("combined")) {
            assertEquals(List.of("src", "tests"), publication.ingestion().snapshot().scope().includes());
            assertEquals(new ChunkingConfiguration(4, 1), publication.processing().configuration());
            environment.withProperty("qa.scope-includes", "src,tests");
            assertEquals(publication.id(), publish(environment, output).id());
        } else {
            var definition = EvaluationFixtures.definition();
            var scenario = definition.scenarios().stream().filter(s -> s.id().equals(variant)).findFirst().orElseThrow();
            // Reuse the actual evaluation publication contract, with the historical references intact.
            RetrievalEvaluationArtifacts.validatePublication(definition, scenario, publication);
            var result = retrieval.search(publication.id(), scenario.query(), 5).orElseThrow();
            var grade = RetrievalGrading.grade(scenario.expected(), result.matches().stream().map(m -> new RetrievalGrading.Reference(
                    m.evidence().filePath(), m.evidence().startLine(), m.evidence().endLine())).toList());
            assertEquals(0.0, grade.recall());
            if (variant.equals("KW-007")) {
                assertEquals(List.of("src"), publication.ingestion().snapshot().scope().includes());
                assertTrue(publication.ingestion().artifacts().stream().allMatch(a -> a.path().startsWith("src/")));
                assertTrue(publication.ingestion().coverage().stream().anyMatch(c -> c.path().equals("README.md")));
            }
        }
        if (!variant.equals("KW-007")) {
            assertEquals(1, publication.processing().chunks().size());
            assertFalse(publication.processing().processingLimitations().isEmpty());
        }
    }
    private MockEnvironment options() {
        return new MockEnvironment().withProperty("qa.command", "publish").withProperty("qa.publish-repository", repository.toString())
                .withProperty("qa.repository-id", "parcel-quotes-v1").withProperty("qa.revision", commit);
    }
    private PersistedPublication publish(MockEnvironment environment, CapturedOutput output) {
        var application = new GenericApplicationContext(); application.refresh();
        int start = output.getOut().length();
        new QaCliRunner(environment, persistence, null, application).run(new DefaultApplicationArguments());
        var json = new JsonMapper().readTree(output.getOut().substring(start));
        var publication = persistence.findPublication(json.path("publicationId").asString()).orElseThrow();
        assertEquals(publication.ingestion().snapshot(), new JsonMapper().treeToValue(json.path("snapshot"),
                dev.changeassurance.ingestion.IngestionResult.Snapshot.class));
        assertEquals(publication.processing().configuration(), new JsonMapper().treeToValue(json.path("configuration"), ChunkingConfiguration.class));
        assertEquals(publication.processing().status().name(), json.path("processingStatus").asString());
        assertFalse(application.isActive()); return publication;
    }
    private String git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_AUTHOR_DATE", "2026-01-01T00:00:00Z");
        builder.environment().put("GIT_COMMITTER_DATE", "2026-01-01T00:00:00Z");
        var process = builder.start(); assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        String text = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), text); return text;
    }
}
