package dev.changeassurance.evaluation;

import dev.changeassurance.persistence.EvidencePersistenceService;
import dev.changeassurance.retrieval.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import javax.sql.DataSource;
import java.nio.file.*;
import static dev.changeassurance.evaluation.QaEvaluationTests.*;
import static org.junit.jupiter.api.Assertions.*;

class QaEvaluationCliTests {
    @TempDir Path output;
    @Test void offlineCommandsNeedNoModelDatabaseOrCorpusAndPreserveEarlierArtifacts() throws Exception {
        var run = runQa(WRONG_UNITS); var directory = QaEvaluationArtifacts.write(output, run);
        var env = new MockEnvironment().withProperty("evaluation.command", "qa-grade")
                .withProperty("evaluation.execution", directory.resolve("qa-execution.json").toString());
        invoke(env); invoke(env);
        env.setProperty("evaluation.command", "qa-review-template"); env.setProperty("evaluation.execution-id", run.executions().getFirst().id());
        invoke(env);
        try (var files = Files.list(directory)) { assertEquals(5, files.count()); }
        Path review = directory.resolve("test-review.json");
        RetrievalEvaluationArtifacts.writeNew(review, QaHumanReviewTests.reviewed(run));
        env.setProperty("evaluation.command", "qa-grade"); env.setProperty("evaluation.review", review.toString()); invoke(env);
        try (var files = Files.list(directory)) {
            assertEquals(1, files.filter(p -> p.getFileName().toString().startsWith("qa-grades-")).map(p -> {
                try { return Files.readString(p); } catch (Exception e) { throw new RuntimeException(e); }
            }).filter(s -> s.contains("HUMAN_REVIEW_RECORDED")).count());
        }
    }
    @Test void rejectsLiveCommandWithoutOptInProfilesAndUnknownCommands() {
        assertThrows(IllegalArgumentException.class, () -> invoke(new MockEnvironment().withProperty("evaluation.command", "qa-run")));
        assertThrows(IllegalArgumentException.class, () -> invoke(new MockEnvironment().withProperty("evaluation.command", "qa-unknown")));
    }
    private void invoke(MockEnvironment env) throws Exception {
        var beans = new StaticListableBeanFactory();
        new RetrievalEvaluationCliRunner(env, beans.getBeanProvider(EvidencePersistenceService.class),
                beans.getBeanProvider(KeywordRetrievalService.class), beans.getBeanProvider(VectorRetrievalService.class),
                beans.getBeanProvider(DataSource.class), new GenericApplicationContext()).run(new DefaultApplicationArguments());
    }
}
