package dev.changeassurance.evaluation;

import java.time.OffsetDateTime;
import java.util.*;
import static dev.changeassurance.evaluation.RetrievalComparison.require;
import static dev.changeassurance.evaluation.QaEvaluationArtifacts.text;

/** Human assertions, never automatic factual grades. Bind each append-only review to exact execution bytes. */
public record QaHumanReview(int schemaVersion, String rubricVersion, String reviewId, String runId,
                            String executionId, String executionHash, String reviewer, String reviewedAt,
                            String rationale, List<ClaimReview> claims, List<FindingReview> findings,
                            Answerability answerability, Uncertainty uncertaintyQuality, List<FactualIssue> factualIssues) {
    public enum FactualCorrectness { CORRECT, INCORRECT, INDETERMINATE, NOT_REVIEWED }
    public enum CitationRelevance { RELEVANT, PARTIALLY_RELEVANT, IRRELEVANT, INDETERMINATE, NOT_REVIEWED }
    public enum Answerability { APPROPRIATE, OVERCLAIM, UNNECESSARY_ABSTENTION, NOT_REVIEWED }
    public enum Uncertainty { ADEQUATE, INADEQUATE, NOT_REVIEWED }
    public enum IssueCategory { NUMERICAL_UNIT_CONVERSION, NUMERICAL_VALUE, UNSUPPORTED_ASSERTION, FALSE_ABSENCE, RUNTIME_INFERENCE, OTHER }
    public enum Target { CLAIM, UNCERTAINTY }
    public record ClaimReview(int claimIndex, String observedClaim, CitationRelevance citationRelevance,
                              Integer supportScore, FactualCorrectness factualCorrectness, List<String> evidenceKeys, String rationale) { }
    public record FindingReview(int findingIndex, Integer completenessScore, String rationale) { }
    public record FactualIssue(Target target, int index, IssueCategory category, String observedText,
                               String expectedFact, List<RetrievalGrading.Reference> sourceReferences, String rationale) { }
    public QaHumanReview { claims = List.copyOf(claims); findings = List.copyOf(findings); factualIssues = List.copyOf(factualIssues); }

    public static QaHumanReview template(QaEvaluationRun run, String executionId) {
        var e = run.executions().stream().filter(x -> x.id().equals(executionId)).findFirst().orElseThrow();
        var claims = new ArrayList<ClaimReview>(); var findings = new ArrayList<FindingReview>();
        if (e.answer() != null) {
            for (var claim : e.answer().claims()) claims.add(new ClaimReview(claims.size() + 1, claim.text(),
                    CitationRelevance.NOT_REVIEWED, null, FactualCorrectness.NOT_REVIEWED, claim.evidenceKeys(), "NOT_REVIEWED"));
            var scenario = run.definition().scenarios().stream().filter(s -> s.id().equals(e.scenarioId())).findFirst().orElseThrow();
            for (var ignored : scenario.expectedFindings()) findings.add(new FindingReview(findings.size() + 1, null, "NOT_REVIEWED"));
        }
        // A template is intentionally not a valid review until a person supplies attribution and rationale.
        return new QaHumanReview(1, "qa-human-rubric-v1", UUID.randomUUID().toString(), run.runId(), e.id(),
                QaEvaluationArtifacts.executionHash(e), null, null, null, claims, findings,
                Answerability.NOT_REVIEWED, Uncertainty.NOT_REVIEWED, List.of());
    }

    public void validate(QaEvaluationRun run) {
        require(schemaVersion == 1 && "qa-human-rubric-v1".equals(rubricVersion));
        require(UUID.fromString(reviewId).toString().equals(reviewId) && runId.equals(run.runId()));
        text(reviewer, 200); text(rationale, 4000); OffsetDateTime.parse(reviewedAt);
        require(answerability != null && uncertaintyQuality != null);
        var e = run.executions().stream().filter(x -> x.id().equals(executionId)).findFirst().orElseThrow();
        require(executionHash.equals(QaEvaluationArtifacts.executionHash(e)));
        if (e.answer() == null) {
            require(claims.isEmpty() && findings.isEmpty() && factualIssues.isEmpty()
                    && answerability == Answerability.NOT_REVIEWED && uncertaintyQuality == Uncertainty.NOT_REVIEWED); return;
        }
        var a = e.answer();
        var scenario = run.definition().scenarios().stream().filter(s -> s.id().equals(e.scenarioId())).findFirst().orElseThrow();
        require(claims.size() == a.claims().size() && findings.size() == scenario.expectedFindings().size() && factualIssues.size() <= 32);
        // Every claim/finding needs an explicit score or NOT_REVIEWED; an omitted row earns no hidden credit.
        var allowed = new HashSet<String>();
        if (e.modelInput() != null && e.retrieval() != null) {
            var context = new dev.changeassurance.qa.EvidenceContextBuilder().build(e.retrieval(), run.definition().answering());
            require(QaEvaluationArtifacts.sameContext(context.json(), e.modelInput().contextJson())); context.entries().forEach(x -> allowed.add(x.key()));
        }
        for (int i = 0; i < claims.size(); i++) {
            var c = claims.get(i);
            require(c.claimIndex() == i + 1 && c.observedClaim().equals(a.claims().get(i).text()));
            require(c.citationRelevance() != null && c.factualCorrectness() != null && c.evidenceKeys() != null
                    && c.evidenceKeys().size() <= 8 && allowed.containsAll(c.evidenceKeys()));
            require(c.supportScore() == null || c.supportScore() >= 0 && c.supportScore() <= 2);
            if (c.supportScore() != null && c.supportScore() > 0) require(!c.evidenceKeys().isEmpty());
            text(c.rationale(), 2000);
        }
        for (int i = 0; i < findings.size(); i++) {
            var f = findings.get(i); require(f.findingIndex() == i + 1 && (f.completenessScore() == null || f.completenessScore() >= 0 && f.completenessScore() <= 2));
            text(f.rationale(), 2000); // null means NOT_REVIEWED, never missing/wrong (0).
        }
        for (var issue : factualIssues) {
            require(issue.target() != null && issue.category() != null && issue.index() >= 1);
            var texts = issue.target() == Target.CLAIM ? a.claims().stream().map(dev.changeassurance.qa.GroundedAnswer.Claim::text).toList() : a.uncertainties();
            require(issue.index() <= texts.size()); text(issue.observedText(), 1000);
            require(texts.get(issue.index() - 1).contains(issue.observedText()));
            text(issue.expectedFact(), 2000); text(issue.rationale(), 2000);
            require(run.publication() != null && !issue.sourceReferences().isEmpty() && issue.sourceReferences().size() <= 16);
            for (var ref : issue.sourceReferences()) {
                var source = run.publication().ingestion().artifacts().stream().filter(s -> s.path().equals(ref.path())).findFirst().orElseThrow();
                require(ref.end() <= source.content().lines().count());
            }
        }
    }
}
