package dev.changeassurance.evaluation;

import tools.jackson.databind.JsonNode;
import java.util.*;
import static dev.changeassurance.evaluation.EvaluationReport.*;

/** Human-readable rendering of the normalized report, without a second calculation or model. */
public final class EvaluationReportMarkdown {
    private EvaluationReportMarkdown() { }
    public static String render(EvaluationReport r) {
        var b = new StringBuilder("# Evaluation report\n\n");
        b.append("## Evaluation overview\n\n");
        table(b, "Field", "Value"); row(b, "Report", r.reportId()); row(b, "Generated", r.generatedAt());
        row(b, "Generator / schema", r.generatorVersion() + " / " + r.schemaVersion()); row(b, "Payload SHA256", r.payloadHash());
        row(b, "Evaluation types", r.payload().evaluationTypes());
        b.append("\nExecution success is not answer correctness. Citation identity is not citation relevance or factual support.\n\n");
        b.append("## Configuration and provenance\n\n");
        table(b, "Input role", "Run", "Configuration", "Content SHA256", "Local path");
        for (var i : r.payload().inputs()) row(b, i.role(), i.runId(), i.configurationId(), i.sha256(), i.path());
        if (r.payload().retrieval() != null) provenance(b, "Retrieval", r.payload().retrieval().verification(), r.payload().retrieval().provenance(), r.payload().retrieval().grades().gradingVersion());
        if (r.payload().qa() != null) provenance(b, "Q&A", r.payload().qa().verification(), r.payload().qa().provenance(), r.payload().qa().grades().gradingVersion());
        b.append("\n## Retrieval comparison\n\n");
        var retrieval = r.payload().retrieval();
        if (retrieval == null) b.append("Not supplied.\n");
        else {
            b.append("Metrics retain the existing full-span-union Recall and first-overlap MRR definitions. Each K is a separate execution configuration. No strategy ranking is inferred.\n\n");
            table(b, "Strategy", "K", "Scheduled", "Successful", "Failed", "Positive-evidence denominator", "Empty expectations (successful)", "Recall@K", "MRR@K");
            for (var a : retrieval.grades().aggregates()) row(b, a.strategy(), a.topK(), a.scheduled(), a.successful(), a.failed(), a.qualityDenominator(), a.emptyExpectationSuccesses(), a.macroRecallAtK(), a.mrrAtK());
            b.append('\n'); table(b, "K", "Comparison status", "Qualification");
            for (var c : retrieval.comparisons()) row(b, c.topK(), c.status(), c.reasons().isEmpty() ? "Same verified run, scenario sets, source identities and relevant settings; no significance claim." : c.reasons());
            b.append('\n'); table(b, "Scenario", "Strategy", "K", "Failure", "Covered / expected spans", "Recall", "Reciprocal rank");
            for (var c : retrieval.grades().cases()) row(b, c.scenarioId(), c.strategy(), c.topK(), c.failure() == null ? "none" : c.failure(), c.metrics() == null ? null : c.metrics().coveredReferences() + " / " + c.metrics().expectedReferences(), c.metrics() == null ? null : c.metrics().recall(), c.metrics() == null ? null : c.metrics().reciprocalRank());
        }
        b.append("\n## Grounded-answer reliability\n\n"); var qa = r.payload().qa();
        if (qa == null) b.append("Not supplied.\n");
        else {
            b.append("These are recorded application outcomes, independently of offline checks and human judgment.\n\n");
            table(b, "Execution accounting", "Count / population");
            row(b, "Successful executions", qa.summary().successfulExecutions() + " / " + qa.summary().totalExecutions());
            row(b, "Failed executions", qa.summary().failedExecutions() + " / " + qa.summary().totalExecutions());
            row(b, "Valid / evaluable citation identities", qa.summary().citationIdentity().get("VALID") + " / " + qa.summary().evaluableCitationExecutions());
            counts(b, "Reliability", qa.summary().reliability(), qa.summary().totalExecutions());
            counts(b, "Citation identity", qa.summary().citationIdentity(), qa.summary().totalExecutions());
            counts(b, "Structural answerability (semantics unverified)", qa.summary().structuralAnswerability(), qa.summary().totalExecutions());
            b.append("\nCitation NOT_APPLICABLE (e.g. abstention) and NOT_VERIFIABLE are separate from valid or invalid applicable identities. A structural abstention is not automatically appropriate.\n\n");
            table(b, "Execution", "Reliability", "Retrieval recall", "Context recall", "Retrieval misses", "Context losses", "Citation identity", "Structure", "Semantic review");
            for (var g : qa.grades().cases()) row(b, g.executionId(), g.reliability(), g.retrievalCoverage() == null ? null : g.retrievalCoverage().recall(), g.contextCoverage() == null ? null : g.contextCoverage().recall(), g.retrievalCoverage() == null ? null : g.retrievalMisses().size(), g.contextCoverage() == null ? null : g.contextBudgetLosses().size(), g.citationIdentity(), g.structure(), g.semanticReviewStatus());
        }
        b.append("\n## Human-review coverage and correctness\n\n");
        if (qa == null) b.append("Not supplied.\n");
        else {
            var s = qa.summary(); table(b, "Population", "Count"); row(b, "Total executions", s.totalExecutions());
            row(b, "Executions with answers", s.answerExecutions()); row(b, "Explicitly selected review records", s.reviewRecords());
            row(b, "Reviewed answer executions", s.reviewedAnswerExecutions()); row(b, "Unreviewed answer executions", s.unreviewedAnswerExecutions()); row(b, "Total claims in answers", s.totalClaims());
            counts(b, "Claim factual correctness", s.factualCorrectness(), s.totalClaims());
            counts(b, "Claim citation semantic relevance", s.citationRelevance(), s.totalClaims());
            counts(b, "Claim support (0–2; null means NOT_REVIEWED)", s.claimSupport(), s.totalClaims());
            counts(b, "Expected-finding completeness", s.completeness(), total(s.completeness()));
            counts(b, "Human answerability", s.answerabilityReview(), s.answerExecutions());
            counts(b, "Human uncertainty quality", s.uncertaintyReview(), s.answerExecutions());
            b.append("\nCounts include explicit NOT_REVIEWED/INDETERMINATE categories. They are not representative accuracy estimates. A review record does not imply that every dimension was reviewed.\n\n");
            table(b, "Execution", "Review ID", "Reviewer", "Review date", "Execution SHA256", "Rubric");
            for (var g : qa.grades().cases()) if (g.humanReview() != null) { var h = g.humanReview(); row(b, g.executionId(), h.reviewId(), h.reviewer(), h.reviewedAt(), h.executionHash(), h.rubricVersion()); }
            b.append('\n'); table(b, "Execution / claim", "Observed claim", "Factual correctness", "Support", "Citation relevance", "Rationale");
            for (var g : qa.grades().cases()) if (g.humanReview() != null) for (var c : g.humanReview().claims())
                row(b, g.executionId() + " / " + c.claimIndex(), c.observedClaim(), c.factualCorrectness(), c.supportScore() == null ? "NOT_REVIEWED" : c.supportScore(), c.citationRelevance(), c.rationale());
            b.append('\n'); table(b, "Execution", "Factual issue", "Observed text", "Expected fact", "Source references", "Rationale");
            for (var g : qa.grades().cases()) if (g.humanReview() != null) for (var f : g.humanReview().factualIssues())
                row(b, g.executionId(), f.target() + ":" + f.index() + " " + f.category(), f.observedText(), f.expectedFact(), f.sourceReferences(), f.rationale());
        }
        b.append("\n## Significant failures and missing judgments\n\n");
        table(b, "Evaluation / execution", "Classification", "Recorded basis", "Review / claim", "Evidence identities");
        for (var f : r.payload().findings()) row(b, f.evaluationType() + " / " + f.executionId(), f.classification(), f.explanation(),
                f.reviewId() == null ? "not applicable" : f.reviewId() + " / " + f.claimIndex(), f.evidence().stream().map(e -> e.path() + ":" + e.startLine() + "–" + e.endLine() + " [" + e.chunkId() + "]").toList());
        b.append("\n## Reproducibility and limitations\n\n");
        for (String limitation : r.payload().limitations()) b.append("- ").append(cell(limitation)).append('\n');
        b.append("\nReproduce with the explicitly listed input paths and hashes. JSON contains per-case provenance, timing/usage when available, grades, selected reviews and missing spans. Raw model output and full source content are not copied. Compare payloadHash for substantive identity; report IDs/timestamps are intentionally variable.\n");
        return b.toString();
    }
    private static void provenance(StringBuilder b, String name, String verification, JsonNode p, String grader) {
        b.append('\n'); table(b, name + " configuration", "Value"); row(b, "Verification", verification); row(b, "Grading version", grader);
        if (p == null) { row(b, "Execution provenance", null); return; }
        for (String key : List.of("configurationId", "executionMode", "application", "publicationIds", "publicationId", "embeddingProfileId", "embeddingProfile", "generationSettings", "modelDigest", "promptHashes", "schemaHashes", "effectiveStrategies"))
            if (p.has(key)) row(b, key, p.get(key).isNull() ? null : p.get(key));
        row(b, "Definition version", p.path("definition").path("version").asString()); row(b, "Definition SHA256", p.path("definition").path("definitionHash").asString());
        if (p.path("definition").has("promptVersion")) row(b, "Prompt version", p.path("definition").path("promptVersion").asString());
        if (p.has("publications")) for (String key : new TreeSet<>(p.path("publications").propertyNames())) publication(b, key, p.path("publications").get(key));
        if (p.has("publication") && !p.path("publication").isNull()) publication(b, "selected", p.path("publication"));
    }
    private static void publication(StringBuilder b, String name, JsonNode p) {
        row(b, name + " publication", p.path("id").asString()); row(b, name + " repository / commit / scope", p.path("snapshot"));
        row(b, name + " processing configuration", p.path("metadata").path("configuration"));
    }
    private static void counts(StringBuilder b, String title, Map<String, Long> values, Integer denominator) {
        b.append('\n'); table(b, title, "Count / population");
        if (values == null) row(b, "unavailable", null);
        else values.forEach((key, count) -> row(b, key, count + " / " + (denominator == null ? "unavailable" : denominator)));
    }
    private static Integer total(Map<String, Long> values) { return values == null ? null : Math.toIntExact(values.values().stream().mapToLong(Long::longValue).sum()); }
    private static void table(StringBuilder b, String... headers) { row(b, (Object[]) headers); b.append('|'); for (var ignored : headers) b.append(" --- |"); b.append('\n'); }
    private static void row(StringBuilder b, Object... values) { b.append('|'); for (var value : values) b.append(' ').append(cell(value == null ? "unavailable" : value.toString())).append(" |"); b.append('\n'); }
    static String cell(String value) {
        var escaped = new StringBuilder(); value.codePoints().forEach(c -> {
            if (Character.isISOControl(c) || c == 0x2028 || c == 0x2029) escaped.append(' ');
            else if ("&<>|`*_[\\]#".indexOf(c) >= 0) escaped.append("&#").append(c).append(';');
            else escaped.appendCodePoint(c);
        }); return escaped.toString();
    }
}
