package dev.changeassurance.evaluation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Consumer;
import static dev.changeassurance.evaluation.QaEvaluationTests.*;
import static dev.changeassurance.evaluation.QaHumanReview.*;
import static org.junit.jupiter.api.Assertions.*;

class QaHumanReviewTests {
    static QaHumanReview reviewed(QaEvaluationRun run) {
        var e = run.executions().getFirst(); var template = QaHumanReview.template(run, e.id());
        var source = e.answer().citations().getFirst().evidence();
        return new QaHumanReview(1, "qa-human-rubric-v1", template.reviewId(), run.runId(), e.id(), template.executionHash(),
                "deterministic test fixture, not a live review", "2026-10-17T12:00:00Z",
                "Synthetic numerical regression: a source identity is not a semantic guarantee.",
                List.of(new ClaimReview(1, e.answer().claims().getFirst().text(), CitationRelevance.RELEVANT, 0,
                        FactualCorrectness.INCORRECT, List.of("E1"), "The cited priceCents method returns cents; the proposed dollar amount contradicts it.")),
                List.of(new FindingReview(1, 0, "Wrong amount"), new FindingReview(2, 0, "Units are incorrect")),
                Answerability.OVERCLAIM, Uncertainty.INADEQUATE,
                List.of(new FactualIssue(Target.CLAIM, 1, IssueCategory.NUMERICAL_UNIT_CONVERSION, "$300 surcharge",
                        "300 cents = $3.00", List.of(new RetrievalGrading.Reference(source.filePath(), source.startLine(), source.endLine())),
                        "The source method expresses its result in cents.")));
    }
    static QaHumanReview mutateReview(QaHumanReview review, Consumer<ObjectNode> edit) {
        var node = (ObjectNode) RetrievalComparison.JSON.valueToTree(review); edit.accept(node);
        return RetrievalComparison.JSON.treeToValue(node, QaHumanReview.class);
    }
    @Test void citationValidNumericalFailureRequiresAndRetainsAnExplicitHumanAssertion() throws Exception {
        var run = runQa(WRONG_UNITS); var review = reviewed(run); review.validate(run);
        var grade = QaEvaluationArtifacts.grade(run, List.of(review)).cases().getFirst();
        assertEquals(QaEvaluationArtifacts.Check.VALID, grade.citationIdentity());
        assertEquals(FactualCorrectness.INCORRECT, grade.humanReview().claims().getFirst().factualCorrectness());
        assertEquals(0, grade.humanReview().claims().getFirst().supportScore());
        assertEquals(IssueCategory.NUMERICAL_UNIT_CONVERSION, grade.humanReview().factualIssues().getFirst().category());
        assertEquals("NOT_REVIEWED", QaEvaluationArtifacts.grade(run, List.of(review)).cases().get(1).semanticReviewStatus());
    }
    @Test void templateNeverFabricatesReviewerOrSemanticGrades() throws Exception {
        var run = runQa(WRONG_UNITS); var t = QaHumanReview.template(run, run.executions().getFirst().id());
        assertNull(t.reviewer()); assertNull(t.reviewedAt()); assertNull(t.claims().getFirst().supportScore());
        assertNull(t.findings().getFirst().completenessScore()); assertEquals(FactualCorrectness.NOT_REVIEWED, t.claims().getFirst().factualCorrectness());
        assertThrows(RuntimeException.class, () -> t.validate(run));
    }
    @Test void invalidScoresAttributionAndBindingAreRejected() throws Exception {
        var run = runQa(WRONG_UNITS); var review = reviewed(run);
        var mutations = List.<Consumer<ObjectNode>>of(n -> n.put("reviewer", ""), n -> n.put("reviewedAt", "yesterday"),
                n -> n.put("executionHash", "f".repeat(64)), n -> n.put("runId", UUID.randomUUID().toString()),
                n -> ((ObjectNode) n.path("claims").get(0)).put("supportScore", 3),
                n -> ((ObjectNode) n.path("claims").get(0)).put("observedClaim", "a different answer"),
                n -> ((ObjectNode) n.path("findings").get(0)).put("completenessScore", -1),
                n -> ((ObjectNode) n.path("claims").get(0)).putArray("evidenceKeys").add("E99"),
                n -> n.putArray("findings"), n -> ((ObjectNode) n.path("factualIssues").get(0)).put("observedText", "not actually observed"),
                n -> ((ObjectNode) n.path("factualIssues").get(0).path("sourceReferences").get(0)).put("end", 10000));
        for (var edit : mutations) assertThrows(RuntimeException.class, () -> mutateReview(review, edit).validate(run));
        assertThrows(IllegalArgumentException.class, () -> QaEvaluationArtifacts.grade(run, List.of(review, review)));
    }
    @Test void failureReviewsCannotAwardSemanticCredit() throws Exception {
        var run = runQa("invalid json"); var t = QaHumanReview.template(run, run.executions().getFirst().id());
        assertTrue(t.claims().isEmpty()); assertTrue(t.findings().isEmpty());
        var review = mutateReview(t, n -> { n.put("reviewer", "test fixture"); n.put("reviewedAt", "2026-10-17T12:00:00Z"); n.put("rationale", "Generation failed"); });
        review.validate(run);
        assertThrows(IllegalArgumentException.class, () -> mutateReview(review, n -> n.put("answerability", "APPROPRIATE")).validate(run));
    }
    @Test void unreviewedDimensionsRemainExplicitEvenInAnAttributedReview() throws Exception {
        var run = runQa(WRONG_UNITS); var t = QaHumanReview.template(run, run.executions().getFirst().id());
        var review = mutateReview(t, n -> { n.put("reviewer", "test fixture"); n.put("reviewedAt", "2026-10-17T12:00:00Z"); n.put("rationale", "Pending semantic inspection"); });
        var roundTrip = RetrievalComparison.JSON.readValue(RetrievalComparison.JSON.writeValueAsBytes(review), QaHumanReview.class);
        roundTrip.validate(run); assertNull(roundTrip.claims().getFirst().supportScore()); assertEquals(Answerability.NOT_REVIEWED, roundTrip.answerability());
    }
}
