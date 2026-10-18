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

class EvaluationReportCliTests {
    @TempDir Path files;
    @Test void dispatcherReportsOfflineWithNoDatabaseOrGatewayBeans() throws Exception {
        var run = runQa(ABSTAIN); Path input = files.resolve("qa capture, v1.json");
        RetrievalEvaluationArtifacts.writeNew(input, run);
        var env = new MockEnvironment().withProperty("evaluation.command", "report")
                .withProperty("evaluation.qa-execution", input.toString()).withProperty("evaluation.report-output", files.resolve("report").toString());
        invoke(env); assertTrue(Files.isRegularFile(files.resolve("report/report.json"))); assertTrue(Files.isRegularFile(files.resolve("report/report.md")));
        assertEquals(EvaluationReportException.Code.OUTPUT_EXISTS, assertThrows(EvaluationReportException.class, () -> invoke(env)).code());
    }
    @Test void cliExplicitJsonArraySelectsReviewWithSpacesAndCommasInPath() throws Exception {
        var run = runQa(WRONG_UNITS); Path input = files.resolve("qa.json"), grades = files.resolve("chosen review, v1.json");
        RetrievalEvaluationArtifacts.writeNew(input, run);
        RetrievalEvaluationArtifacts.writeNew(grades, QaEvaluationArtifacts.grade(run, java.util.List.of(QaHumanReviewTests.reviewed(run))));
        invoke(new MockEnvironment().withProperty("evaluation.command", "report").withProperty("evaluation.qa-execution", input.toString())
                .withProperty("evaluation.qa-grades", EvaluationReportInputs.JSON.writeValueAsString(java.util.List.of(grades.toString())))
                .withProperty("evaluation.report-output", files.resolve("review-report").toString()));
        var report = EvaluationReportInputs.JSON.readValue(Files.readString(files.resolve("review-report/report.json")), EvaluationReport.class);
        assertEquals(1, report.payload().qa().summary().reviewRecords());
    }
    @Test void malformedOptionsAndConflictingCommandsAreRejected() {
        var env = new MockEnvironment().withProperty("evaluation.command", "report");
        assertThrows(EvaluationReportException.class, () -> invoke(env));
        env.setProperty("evaluation.report-output", files.resolve("bad").toString()); env.setProperty("evaluation.qa-grades", "not-json");
        assertThrows(EvaluationReportException.class, () -> invoke(env));
        env.setProperty("qa.command", "answer"); assertThrows(IllegalArgumentException.class, () -> invoke(env));
        assertFalse(Files.exists(files.resolve("bad")));
    }
    private void invoke(MockEnvironment env) throws Exception {
        var beans = new StaticListableBeanFactory();
        new RetrievalEvaluationCliRunner(env, beans.getBeanProvider(EvidencePersistenceService.class), beans.getBeanProvider(KeywordRetrievalService.class),
                beans.getBeanProvider(VectorRetrievalService.class), beans.getBeanProvider(DataSource.class), new GenericApplicationContext()).run(new DefaultApplicationArguments());
    }
}
