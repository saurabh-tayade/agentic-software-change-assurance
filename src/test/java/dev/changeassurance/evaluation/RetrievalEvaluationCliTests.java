package dev.changeassurance.evaluation;

import dev.changeassurance.ChangeAssuranceApplication;
import dev.changeassurance.embedding.EmbeddingGateway;
import dev.changeassurance.persistence.EvidencePersistenceService;
import dev.changeassurance.retrieval.KeywordRetrievalService;
import dev.changeassurance.retrieval.VectorRetrievalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import javax.sql.DataSource;
import java.nio.file.*;
import static dev.changeassurance.evaluation.EvaluationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

class RetrievalEvaluationCliTests {
    @TempDir Path output;
    @Test void ordinaryStartupDoesNotCreateEvaluationRunnerOrModel() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ChangeAssuranceApplication.class).run(context ->
                        assertThat(context).hasNotFailed().doesNotHaveBean(RetrievalEvaluationCliRunner.class).doesNotHaveBean(EmbeddingGateway.class));
    }
    @Test void offlineCliNeedsNeitherDatabaseNorEmbeddingGatewayAndDoesNotOverwrite() throws Exception {
        var d = definition(); var run = run(d, publications(d));
        var directory = RetrievalEvaluationArtifacts.write(output, run);
        var environment = new MockEnvironment().withProperty("evaluation.command", "grade")
                .withProperty("evaluation.execution", directory.resolve("execution.json").toString());
        cli(environment).run(new DefaultApplicationArguments());
        cli(environment).run(new DefaultApplicationArguments());
        try (var files = Files.list(directory)) {
            assertEquals(4, files.count());
        }
        assertEquals(RetrievalEvaluationArtifacts.grade(run), RetrievalComparison.JSON.readValue(
                Files.readString(directory.resolve("grades.json")), RetrievalEvaluationArtifacts.Grades.class));
    }
    @Test void rejectsMissingPersistenceAndConflictingDeveloperCommands() {
        assertThrows(IllegalArgumentException.class, () -> cli(new MockEnvironment().withProperty("evaluation.command", "run")).run(new DefaultApplicationArguments()));
        assertThrows(IllegalArgumentException.class, () -> cli(new MockEnvironment().withProperty("evaluation.command", "grade")
                .withProperty("qa.command", "answer")).run(new DefaultApplicationArguments()));
    }
    private RetrievalEvaluationCliRunner cli(MockEnvironment environment) {
        var factory = new StaticListableBeanFactory();
        return new RetrievalEvaluationCliRunner(environment, factory.getBeanProvider(EvidencePersistenceService.class),
                factory.getBeanProvider(KeywordRetrievalService.class), factory.getBeanProvider(VectorRetrievalService.class),
                factory.getBeanProvider(DataSource.class), new GenericApplicationContext());
    }
}
