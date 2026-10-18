package dev.changeassurance.evaluation;

import dev.changeassurance.embedding.*;
import dev.changeassurance.persistence.EvidencePersistenceService;
import dev.changeassurance.qa.ModelGateway;
import dev.changeassurance.retrieval.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;

/** Q&A commands share the existing opt-in evaluation entry point and artifact writer. */
final class QaEvaluationCommands {
    private QaEvaluationCommands() { }
    static void run(Environment env, ObjectProvider<EvidencePersistenceService> publications,
                    ObjectProvider<KeywordRetrievalService> keyword, ObjectProvider<VectorRetrievalService> vector,
                    ObjectProvider<DataSource> dataSource, ConfigurableApplicationContext application) throws Exception {
        String command = required(env, "evaluation.command");
        if (Set.of("qa-grade", "qa-review-template").contains(command)) {
            Path input = Path.of(required(env, "evaluation.execution"));
            var run = QaEvaluationArtifacts.read(input);
            QaEvaluationArtifacts.grade(run); // Validate capture before creating any derived artifact.
            Path parent = input.toAbsolutePath().getParent();
            if (command.equals("qa-review-template")) {
                String id = required(env, "evaluation.execution-id");
                var template = QaHumanReview.template(run, id);
                Path output = parent.resolve("qa-review-template-" + template.reviewId() + ".json");
                RetrievalEvaluationArtifacts.writeNew(output, template);
                System.out.println("UNREVIEWED Q&A TEMPLATE: " + output);
            } else {
                String review = env.getProperty("evaluation.review");
                var reviews = review == null ? List.<QaHumanReview>of() : List.of(RetrievalComparison.JSON.readValue(
                        RetrievalComparison.boundedRead(Path.of(review), 1_000_000), QaHumanReview.class));
                var grades = QaEvaluationArtifacts.grade(run, reviews);
                Path output = parent.resolve("qa-grades-" + UUID.randomUUID() + ".json");
                RetrievalEvaluationArtifacts.writeNew(output, grades);
                System.out.println("OFFLINE Q&A GRADES: " + output);
            }
            return;
        }
        if (!command.equals("qa-run")) throw new IllegalArgumentException("Expected qa-run, qa-grade, or qa-review-template");
        var persistence = publications.getIfAvailable(); var lexical = keyword.getIfAvailable();
        if (persistence == null || lexical == null || application.getBeansOfType(ModelGateway.class).size() != 1)
            throw new IllegalArgumentException("Q&A evaluation requires persistence and qa profiles");
        var definition = QaComparison.load(Path.of("."), Path.of(env.getProperty("evaluation.definition", "evaluation/qa/qa-comparison-v1.json")));
        String selection = env.getProperty("evaluation.case-ids");
        var ids = selection == null ? definition.scenarios().stream().map(QaComparison.Scenario::id).toList()
                : Arrays.stream(selection.split(",", -1)).map(String::strip).toList();
        String profileId = env.getProperty("evaluation.profile-id");
        var profile = profileId == null ? null : new EmbeddingStore(dataSource.getObject()).findProfile(profileId).orElse(null);
        var semantic = vector.getIfAvailable();
        var runner = new QaEvaluationRunner(persistence::findPublication, strategy -> {
            if (strategy != RetrievalStrategy.KEYWORD && semantic == null) return null;
            return EvidenceRetrievers.select(strategy, lexical, semantic, strategy == RetrievalStrategy.KEYWORD ? null : profile);
        }, application.getBean(ModelGateway.class));
        var run = runner.execute(definition, ids, required(env, "evaluation.publication-id"), profileId, profile,
                RetrievalEvaluationCliRunner.applicationMetadata(), "LIVE", env.getProperty("evaluation.capture-raw-response", Boolean.class, false));
        Path output = QaEvaluationArtifacts.write(Path.of("target/evaluation"), run);
        long failed = run.executions().stream().filter(e -> e.failure() != null).count();
        System.out.println("Q&A EVALUATION: attempts=" + run.executions().size() + " failed=" + failed + " artifacts=" + output);
        if (failed > 0) throw new IllegalStateException("Q&A evaluation incomplete; inspect categorized failures in local artifacts");
    }
    private static String required(Environment env, String name) {
        String value = env.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
}
