package dev.changeassurance.evaluation;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import static dev.changeassurance.evaluation.EvaluationFixtures.*;
import static dev.changeassurance.evaluation.QaEvaluationTests.*;
import static dev.changeassurance.evaluation.EvaluationReportException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationReportTests {
    @TempDir Path files;
    final EvaluationReportGenerator generator = new EvaluationReportGenerator();
    RetrievalEvaluationRun retrieval; QaEvaluationRun qa;
    Path retrievalFile, qaFile, reviewedFile;
    QaHumanReview review;

    @BeforeEach void fixtures() throws Exception {
        var d = definition(); retrieval = run(d, publications(d)); retrievalFile = write("retrieval.json", retrieval);
        var q = definitionQa(); var p = publication(q);
        String claims = "{\"status\":\"PARTIALLY_SUPPORTED\",\"claims\":[{\"text\":\"The method adds a $300 surcharge.\",\"evidenceKeys\":[\"E1\"]},{\"text\":\"The method returns cents.\",\"evidenceKeys\":[\"E1\"]}],\"uncertainties\":[\"Runtime configuration is not established.\"]}";
        qa = new QaEvaluationRunner(id -> Optional.of(p), s -> (id, question, k) -> {
            var result = resultQa(p, question, k, s);
            if (s == dev.changeassurance.retrieval.RetrievalStrategy.KEYWORD && question.equals("Where is the shipping cost calculated?"))
                result = new dev.changeassurance.retrieval.RetrievalResult(result.publication(), question, k, result.strategy(), List.of(), result.limitations());
            return Optional.of(result);
        }, model(claims)).execute(q, List.of("QA-REG-SHIPPING", "QA-REG-UNITS"), p.id(), PROFILE.id(), PROFILE, null, "TEST_DOUBLE", false);
        qaFile = write("qa.json", qa);
        var e = qa.executions().stream().filter(x -> x.id().equals("VECTOR:QA-REG-UNITS")).findFirst().orElseThrow();
        var template = QaHumanReview.template(qa, e.id());
        review = new QaHumanReview(1, template.rubricVersion(), template.reviewId(), qa.runId(), e.id(), template.executionHash(),
                "synthetic reviewer", "2026-10-18T12:00:00Z", "Controlled test, not live acceptance", List.of(
                new QaHumanReview.ClaimReview(1, e.answer().claims().get(0).text(), QaHumanReview.CitationRelevance.NOT_REVIEWED, null,
                        QaHumanReview.FactualCorrectness.INCORRECT, List.of("E1"), "Incorrect unit conversion"),
                new QaHumanReview.ClaimReview(2, e.answer().claims().get(1).text(), QaHumanReview.CitationRelevance.NOT_REVIEWED, null,
                        QaHumanReview.FactualCorrectness.CORRECT, List.of("E1"), "Source unit retained")), template.findings(),
                QaHumanReview.Answerability.NOT_REVIEWED, QaHumanReview.Uncertainty.NOT_REVIEWED, List.of());
        reviewedFile = write("reviewed.json", QaEvaluationArtifacts.grade(qa, List.of(review)));
    }
    Path write(String name, Object value) throws Exception { Path p = files.resolve(name); RetrievalEvaluationArtifacts.writeNew(p, value); return p; }
    EvaluationReportGenerator.Request request(Path r, Path rg, Path q, Path... qg) { return new EvaluationReportGenerator.Request(r, rg, q, List.of(qg)); }
    void error(EvaluationReportException.Code code, EvaluationReportGenerator.Request request) { assertEquals(code, assertThrows(EvaluationReportException.class, () -> generator.generate(request)).code()); }
    ObjectNode node(Object value) { return (ObjectNode) RetrievalComparison.JSON.valueToTree(value); }

    @Test void retrievalOnlyReusesExactMetricsAndDenominators() {
        var report = generator.generate(request(retrievalFile, null, null));
        assertNull(report.payload().qa()); assertEquals(List.of("RETRIEVAL"), report.payload().evaluationTypes());
        assertEquals(RetrievalEvaluationArtifacts.grade(retrieval), report.payload().retrieval().grades());
        assertEquals(90, report.payload().retrieval().grades().cases().size());
        for (var a : report.payload().retrieval().grades().aggregates()) { assertEquals(15, a.scheduled()); assertEquals(14, a.qualityDenominator()); assertEquals(1, a.emptyExpectationSuccesses()); }
        assertTrue(report.payload().retrieval().comparisons().stream().allMatch(c -> c.status().equals("COMPARABLE_WITHIN_RUN")));
    }
    @Test void qaOnlySeparatesSuccessfulExecutionCitationIdentityAndFactualError() {
        var report = generator.generate(request(null, null, qaFile, reviewedFile)); var q = report.payload().qa();
        assertNull(report.payload().retrieval()); assertEquals(6, q.summary().totalExecutions()); assertEquals(6, q.summary().reliability().get("SUCCESS"));
        assertEquals(5, q.summary().citationIdentity().get("VALID")); assertEquals(1, q.summary().citationIdentity().get("NOT_APPLICABLE"));
        assertEquals(1, q.summary().reviewedAnswerExecutions()); assertEquals(5, q.summary().unreviewedAnswerExecutions());
        assertEquals(1, q.summary().factualCorrectness().get("INCORRECT")); assertEquals(1, q.summary().factualCorrectness().get("CORRECT"));
        assertEquals(8, q.summary().factualCorrectness().get("NOT_REVIEWED")); assertEquals(10, q.summary().claimSupport().get("NOT_REVIEWED"));
        assertEquals(6, q.summary().answerabilityReview().get("NOT_REVIEWED"));
        var failure = report.payload().findings().stream().filter(f -> f.classification().equals("HUMAN_REVIEWED_FACTUAL_ERROR")).findFirst().orElseThrow();
        assertEquals("VECTOR:QA-REG-UNITS", failure.executionId()); assertEquals(review.reviewId(), failure.reviewId());
        assertTrue(failure.explanation().contains("VALID")); assertFalse(failure.evidence().isEmpty());
    }
    @Test void combinedReportKeepsPopulationsSeparateAndContainsInputHashes() {
        var report = generator.generate(request(retrievalFile, null, qaFile, reviewedFile));
        assertEquals(3, report.payload().inputs().size()); assertEquals(2, report.payload().evaluationTypes().size());
        assertTrue(report.payload().inputs().stream().allMatch(i -> i.sha256().matches("[0-9a-f]{64}")));
        assertTrue(report.payload().limitations().stream().anyMatch(s -> s.contains("never pooled")));
    }
    @Test void emptyExpectationsRemainUnavailableRatherThanPerfect() {
        var report = generator.generate(request(retrievalFile, null, null));
        var empty = report.payload().retrieval().grades().cases().stream().filter(c -> c.metrics().expectedReferences() == 0).toList();
        assertEquals(6, empty.size()); assertTrue(empty.stream().allMatch(c -> c.metrics().recall() == null && c.metrics().reciprocalRank() == null));
    }
    @Test void failedRetrievalExecutionsAreExcludedAndComparisonsQualified() throws Exception {
        var d = definition(); var p = publications(d);
        var failed = new RetrievalEvaluationRunner(id -> p.values().stream().filter(x -> x.id().equals(id)).findFirst(), s -> null)
                .execute(d, ids(p), PROFILE.id(), PROFILE, null, "TEST_DOUBLE");
        var report = generator.generate(request(write("failed-retrieval.json", failed), null, null));
        for (var a : report.payload().retrieval().grades().aggregates()) { assertEquals(15, a.failed()); assertEquals(0, a.qualityDenominator()); assertNull(a.macroRecallAtK()); }
        assertTrue(report.payload().retrieval().comparisons().stream().allMatch(c -> c.status().equals("NOT_DIRECTLY_COMPARABLE")));
    }
    @Test void modelFailureAndInvalidCitationRemainDistinctFromAbstention() throws Exception {
        for (String response : List.of("malformed", WRONG_UNITS.replace("E1", "E99"))) {
            var failed = runQa(response); var report = generator.generate(request(null, null, write(UUID.randomUUID()+".json", failed)));
            assertEquals(0, report.payload().qa().summary().answerExecutions()); assertEquals(0, report.payload().qa().summary().totalClaims());
            assertFalse(report.payload().qa().summary().structuralAnswerability().containsKey("STRUCTURAL_ABSTENTION_SEMANTICS_NOT_VERIFIED"));
            assertTrue(report.payload().findings().stream().anyMatch(f -> f.classification().equals(response.equals("malformed") ? "STRUCTURAL_ANSWER_FAILURE" : "INVALID_CITATION_IDENTITY")));
        }
    }
    @Test void abstentionHasNoAutomaticCorrectnessAndUnreviewedAnswersRemainUnreviewed() {
        var report = generator.generate(request(null, null, qaFile)); var s = report.payload().qa().summary();
        assertEquals(1, s.structuralAnswerability().get("STRUCTURAL_ABSTENTION_SEMANTICS_NOT_VERIFIED"));
        assertEquals(0, s.reviewedAnswerExecutions()); assertEquals(10, s.factualCorrectness().get("NOT_REVIEWED")); assertEquals(0, s.factualCorrectness().get("CORRECT"));
    }
    @Test void missingMetadataRemainsExplicitAndDoesNotPreventReporting() {
        var report = generator.generate(request(retrievalFile, null, qaFile));
        assertTrue(report.payload().qa().provenance().path("application").isNull()); assertTrue(report.payload().qa().provenance().path("modelDigest").isNull());
        assertTrue(report.payload().limitations().stream().anyMatch(s -> s.contains("digest unavailable")));
    }
    @Test void suppliedGradesMustEqualReplayOfTheExactExecution() throws Exception {
        var rg = RetrievalEvaluationArtifacts.grade(retrieval);
        assertDoesNotThrow(() -> generator.generate(request(retrievalFile, write("rg.json", rg), qaFile, reviewedFile)));
        var wrong = node(rg); wrong.put("configurationId", "a".repeat(64));
        error(ARTIFACT_MISMATCH, request(retrievalFile, write("bad-config.json", wrong), null));
        var wrongQa = node(QaEvaluationArtifacts.grade(qa)); wrongQa.put("runId", UUID.randomUUID().toString());
        error(ARTIFACT_MISMATCH, request(null, null, qaFile, write("bad-run.json", wrongQa)));
    }
    @Test void changedMetricsAndDefinitionsAreNotSilentlyAccepted() throws Exception {
        var g = node(RetrievalEvaluationArtifacts.grade(retrieval));
        ((ObjectNode) g.path("aggregates").get(0)).put("macroRecallAtK", 0.99);
        error(ARTIFACT_MISMATCH, request(retrievalFile, write("bad-metrics.json", g), null));
        var e = node(qa); ((ObjectNode) e.path("definition")).put("definitionHash", "f".repeat(64));
        error(INVALID_INPUT, request(null, null, write("bad-definition.json", e)));
    }
    @Test void wrongHumanExecutionHashIsRejected() throws Exception {
        var g = node(QaEvaluationArtifacts.grade(qa, List.of(review)));
        for (var c : g.path("cases")) if (!c.path("humanReview").isNull()) ((ObjectNode) c.path("humanReview")).put("executionHash", "e".repeat(64));
        error(INVALID_REVIEW, request(null, null, qaFile, write("bad-review.json", g)));
    }
    @Test void duplicateFilesAndDuplicateReviewsAreRejected() throws Exception {
        error(DUPLICATE_INPUT, request(null, null, qaFile, reviewedFile, reviewedFile));
        var copy = files.resolve("copy.json"); Files.copy(reviewedFile, copy);
        error(DUPLICATE_INPUT, request(null, null, qaFile, reviewedFile, copy));
        var second = Files.readString(reviewedFile)+"\n"; var p = files.resolve("same-review.json"); Files.writeString(p, second);
        error(DUPLICATE_INPUT, request(null, null, qaFile, reviewedFile, p));
    }
    @Test void conflictingReviewVersionsRequireExplicitSelection() throws Exception {
        var alternate = QaHumanReviewTests.mutateReview(review, n -> { n.put("reviewId", UUID.randomUUID().toString()); n.put("rationale", "Another observation"); });
        var p = write("other-review.json", QaEvaluationArtifacts.grade(qa, List.of(alternate)));
        error(CONFLICTING_REVIEW, request(null, null, qaFile, reviewedFile, p));
        assertEquals(alternate.reviewId(), generator.generate(request(null, null, qaFile, p)).payload().qa().grades().cases().stream()
                .filter(c -> c.humanReview() != null).findFirst().orElseThrow().humanReview().reviewId());
    }
    @Test void gradesOnlyAreExplicitlyUnverifiedAndReviewsRequireExecution() throws Exception {
        var r = generator.generate(request(null, write("r-grades.json", RetrievalEvaluationArtifacts.grade(retrieval)), null));
        assertEquals("GRADES_ONLY_NOT_VERIFIABLE", r.payload().retrieval().verification()); assertNull(r.payload().retrieval().provenance());
        assertTrue(r.payload().retrieval().comparisons().stream().noneMatch(c -> c.status().equals("COMPARABLE_WITHIN_RUN")));
        var q = generator.generate(request(null, null, null, write("q-grades.json", QaEvaluationArtifacts.grade(qa))));
        assertNull(q.payload().qa().summary().totalClaims()); assertNull(q.payload().qa().summary().factualCorrectness());
        error(EXECUTION_REQUIRED, request(null, null, null, reviewedFile));
    }
    @Test void gradesOnlyRejectInconsistentAggregateArithmetic() throws Exception {
        var g = node(RetrievalEvaluationArtifacts.grade(retrieval));
        ((ObjectNode) g.path("aggregates").get(0)).put("macroRecallAtK", 0.99);
        error(INVALID_INPUT, request(null, write("inconsistent-mean.json", g), null));
    }
    @Test void gradesOnlyRejectInconsistentCoverageCounts() throws Exception {
        var g = node(RetrievalEvaluationArtifacts.grade(retrieval));
        var metric = (ObjectNode) g.path("cases").get(0).path("metrics");
        metric.put("recall", metric.path("recall").asDouble() == 1 ? 0 : 1);
        error(INVALID_INPUT, request(null, write("inconsistent-coverage.json", g), null));
    }
    @Test void unsupportedVersionsAndHistoricalBaselineShapeAreRejected() throws Exception {
        var run = node(qa); run.put("schemaVersion", 2); error(UNSUPPORTED_VERSION, request(null, null, write("v2.json", run)));
        var g = node(QaEvaluationArtifacts.grade(qa)); g.put("gradingVersion", "future"); error(UNSUPPORTED_VERSION, request(null, null, qaFile, write("future.json", g)));
        error(UNSUPPORTED_VERSION, request(null, Path.of("evaluation/retrieval/baseline-v1.json"), null));
    }
    @Test void malformedIncompleteAndUnknownFieldsFailClearly() throws Exception {
        Path p = files.resolve("malformed.json"); Files.writeString(p, "{"); error(INVALID_INPUT, request(p, null, null));
        error(INVALID_INPUT, request(null, null, null));
        var run = node(qa); run.putArray("executions"); error(INVALID_INPUT, request(null, null, write("incomplete.json", run)));
        var extra = node(qa); extra.put("unexpected", "value"); error(INVALID_INPUT, request(null, null, write("extra.json", extra)));
        error(IO_FAILURE, request(files.resolve("absent.json"), null, null));
    }
    @Test void markdownAndJsonShareReviewedCountsAndTraceableEvidence() throws Exception {
        var r = generator.generate(request(retrievalFile, null, qaFile, reviewedFile)); var md = EvaluationReportMarkdown.render(r);
        assertTrue(md.contains("| INCORRECT | 1 / 10 |")); assertTrue(md.contains("| CORRECT | 1 / 10 |"));
        assertTrue(md.contains("| VALID | 5 / 6 |")); assertTrue(md.contains(review.reviewId()));
        assertTrue(md.contains("| Reviewed answer executions | 1 |")); assertTrue(md.contains("| Unreviewed answer executions | 5 |"));
        assertFalse(RetrievalComparison.JSON.writeValueAsString(r).contains("rawResponse"));
        assertFalse(RetrievalComparison.JSON.writeValueAsString(r).contains("originalSourceContent"));
        assertTrue(md.contains("NOT_REVIEWED")); assertTrue(md.contains("## Reproducibility and limitations"));
        var dir = generator.write(files.resolve("report"), r);
        assertEquals(md, Files.readString(dir.resolve("report.md")));
        assertEquals(r, EvaluationReportInputs.JSON.readValue(Files.readString(dir.resolve("report.json")), EvaluationReport.class));
    }
    @Test void markdownEscapesUntrustedReviewAndPathContent() {
        String safe = EvaluationReportMarkdown.cell("[click](https://example.invalid) | <script>\n# forged `code`");
        assertFalse(safe.contains("[click]")); assertFalse(safe.contains("<script>")); assertFalse(safe.contains("\n")); assertFalse(safe.contains("|"));
    }
    @Test void repeatGenerationChangesOnlyEnvelopeMetadataAndNeverMutatesInputs() throws Exception {
        byte[] original = Files.readAllBytes(qaFile); var req = request(retrievalFile, null, qaFile, reviewedFile);
        var first = generator.generate(req); var second = generator.generate(req);
        assertEquals(first.payload(), second.payload()); assertEquals(first.payloadHash(), second.payloadHash()); assertNotEquals(first.reportId(), second.reportId());
        generator.write(files.resolve("one"), first); generator.write(files.resolve("two"), second);
        assertEquals(OUTPUT_EXISTS, assertThrows(EvaluationReportException.class, () -> generator.write(files.resolve("one"), second)).code());
        assertArrayEquals(original, Files.readAllBytes(qaFile));
    }
    @Test void inputOrderDoesNotSelectNewestReviewOrChangeSubstantiveHash() throws Exception {
        var unreviewed = write("base-grades.json", QaEvaluationArtifacts.grade(qa));
        var first = generator.generate(request(null, null, qaFile, unreviewed, reviewedFile));
        var second = generator.generate(request(null, null, qaFile, reviewedFile, unreviewed));
        assertEquals(first.payloadHash(), second.payloadHash()); assertEquals(1, first.payload().qa().summary().reviewedAnswerExecutions());
    }
}
