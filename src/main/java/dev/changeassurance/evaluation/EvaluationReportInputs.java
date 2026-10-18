package dev.changeassurance.evaluation;

import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static dev.changeassurance.evaluation.EvaluationReportException.Code.*;

/** Explicit files only; no directory discovery, newest-review selection, or provider access. */
final class EvaluationReportInputs {
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    final List<EvaluationReport.Input> manifest = new ArrayList<>();
    private final Set<String> hashes = new HashSet<>();
    RetrievalEvaluationRun retrievalRun; RetrievalEvaluationArtifacts.Grades retrievalGrades;
    QaEvaluationRun qaRun; QaEvaluationArtifacts.Grades qaGrades;

    static EvaluationReportInputs load(Path retrieval, Path retrievalGrade, Path qa, List<Path> qaGradePaths) {
        var result = new EvaluationReportInputs();
        try { result.loadAll(retrieval, retrievalGrade, qa, qaGradePaths); return result; }
        catch (EvaluationReportException e) { throw e; }
        catch (RuntimeException invalid) { throw new EvaluationReportException(INVALID_INPUT); }
    }
    private void loadAll(Path r, Path rg, Path q, List<Path> qgs) {
        check(qgs != null && qgs.size() <= 32, INVALID_INPUT);
        check(r != null || rg != null || q != null || !qgs.isEmpty(), INVALID_INPUT);
        if (r != null) {
            retrievalRun = read(r, "retrieval-execution", RetrievalEvaluationRun.class, null, null);
            check("retrieval-comparison-v1".equals(retrievalRun.definition().version()), UNSUPPORTED_VERSION);
            retrievalGrades = RetrievalEvaluationArtifacts.grade(retrievalRun);
        }
        if (rg != null) {
            var supplied = read(rg, "retrieval-grades", RetrievalEvaluationArtifacts.Grades.class, null, "source-span-union-first-overlap-v1");
            check(retrievalGrades == null || retrievalGrades.equals(supplied), ARTIFACT_MISMATCH);
            validateRetrievalGrades(supplied);
            retrievalGrades = supplied;
        }
        if (q != null) {
            qaRun = read(q, "qa-execution", QaEvaluationRun.class, "grounded-qa-execution", null);
            check("qa-comparison-v1".equals(qaRun.definition().version()) && "complete-ranked-chunks-v1".equals(qaRun.definition().contextPolicy())
                    && "grounded-answer-v1".equals(qaRun.definition().answerVersion()), UNSUPPORTED_VERSION);
            qaGrades = QaEvaluationArtifacts.grade(qaRun);
        }
        var reviews = new TreeMap<String, QaHumanReview>(); var reviewIds = new HashSet<String>();
        for (Path path : qgs) {
            var supplied = read(path, "qa-grades", QaEvaluationArtifacts.Grades.class, "grounded-qa-grades", "qa-independent-dimensions-v1");
            check(!supplied.cases().isEmpty(), INVALID_INPUT);
            var current = supplied.cases().stream().map(QaEvaluationArtifacts.CaseGrade::humanReview).filter(Objects::nonNull).toList();
            for (var review : current) {
                check("qa-human-rubric-v1".equals(review.rubricVersion()) && review.schemaVersion() == 1, UNSUPPORTED_VERSION);
                check(qaRun != null, EXECUTION_REQUIRED);
                try { review.validate(qaRun); } catch (RuntimeException invalid) { throw new EvaluationReportException(INVALID_REVIEW); }
                check(reviewIds.add(review.reviewId()), DUPLICATE_INPUT);
                check(reviews.putIfAbsent(review.executionId(), review) == null, CONFLICTING_REVIEW);
            }
            if (qaRun != null) {
                check(supplied.equals(QaEvaluationArtifacts.grade(qaRun, current)), ARTIFACT_MISMATCH);
            } else {
                check(qgs.size() == 1, EXECUTION_REQUIRED);
                var seen = new HashSet<String>();
                for (var c : supplied.cases()) {
                    check(c.executionId() != null && c.executionId().matches("(KEYWORD|VECTOR|HYBRID):[^\\s:]+") && seen.add(c.executionId()) && c.executionHash().matches("[0-9a-f]{64}")
                            && c.reliability() != null && c.provenance() != null && c.context() != null && c.structure() != null
                            && c.citationIdentity() != null && c.structuralAnswerability() != null
                            && c.retrievalMisses() != null && c.contextBudgetLosses() != null && c.contextKeys() != null
                            && "NOT_REVIEWED".equals(c.semanticReviewStatus()), INVALID_INPUT);
                    metric(c.retrievalCoverage()); metric(c.contextCoverage());
                }
                qaGrades = supplied;
            }
        }
        if (qaRun != null) qaGrades = QaEvaluationArtifacts.grade(qaRun, List.copyOf(reviews.values()));
        manifest.sort(Comparator.comparing(EvaluationReport.Input::role).thenComparing(EvaluationReport.Input::sha256));
    }
    private <T> T read(Path path, String role, Class<T> type, String artifactType, String gradingVersion) {
        byte[] bytes;
        try { bytes = RetrievalComparison.boundedRead(path, 64 * 1024 * 1024); }
        catch (Exception failure) { throw new EvaluationReportException(IO_FAILURE); }
        String hash = RetrievalComparison.hash(bytes); check(hashes.add(hash), DUPLICATE_INPUT);
        var node = JSON.readTree(bytes);
        check(node.isObject() && node.path("schemaVersion").asInt() == 1, UNSUPPORTED_VERSION);
        if (artifactType != null) check(artifactType.equals(node.path("artifactType").asString()), UNSUPPORTED_VERSION);
        if (gradingVersion != null) check(gradingVersion.equals(node.path("gradingVersion").asString()), UNSUPPORTED_VERSION);
        String run = node.path("runId").asString(), config = node.path("configurationId").asString();
        check(run != null && UUID.fromString(run).toString().equals(run) && config != null && config.matches("[0-9a-f]{64}"), INVALID_INPUT);
        T value = JSON.treeToValue(node, type);
        manifest.add(new EvaluationReport.Input(role, path.toAbsolutePath().normalize().toString(), hash, run, config)); return value;
    }
    private static void validateRetrievalGrades(RetrievalEvaluationArtifacts.Grades grades) {
        check(!grades.cases().isEmpty() && !grades.aggregates().isEmpty(), INVALID_INPUT);
        var seen = new HashSet<String>();
        for (var c : grades.cases()) {
            check(c.strategy() != null && c.scenarioId() != null && c.topK() > 0 && seen.add(c.strategy() + ":" + c.topK() + ":" + c.scenarioId()), INVALID_INPUT);
            check((c.failure() == null) == (c.metrics() != null), INVALID_INPUT); metric(c.metrics());
        }
        var groups = new HashSet<String>(); int total = 0;
        for (var a : grades.aggregates()) {
            check(groups.add(a.strategy() + ":" + a.topK()), INVALID_INPUT);
            var cases = grades.cases().stream().filter(c -> c.strategy() == a.strategy() && c.topK() == a.topK()).toList();
            long success = cases.stream().filter(c -> c.failure() == null).count();
            long empty = cases.stream().filter(c -> c.metrics() != null && c.metrics().expectedReferences() == 0).count();
            check(a.scheduled() == cases.size() && a.successful() == success && a.failed() == cases.size() - success
                    && a.emptyExpectationSuccesses() == empty && a.qualityDenominator() == success - empty, INVALID_INPUT);
            check(a.qualityDenominator() == 0 ? a.macroRecallAtK() == null && a.mrrAtK() == null
                    : probability(a.macroRecallAtK()) && probability(a.mrrAtK()), INVALID_INPUT);
            // Accounting consistency only: metrics remain the existing recorded grades.
            // No source matching or alternate Recall/MRR definition is performed here.
            var eligible = cases.stream().filter(c -> c.failure() == null && c.metrics().expectedReferences() > 0).toList();
            if (!eligible.isEmpty()) check(
                    Math.abs(a.macroRecallAtK() - eligible.stream().mapToDouble(c -> c.metrics().recall()).average().orElseThrow()) < 1e-12
                    && Math.abs(a.mrrAtK() - eligible.stream().mapToDouble(c -> c.metrics().reciprocalRank()).average().orElseThrow()) < 1e-12, INVALID_INPUT);
            total += a.scheduled();
        }
        check(total == grades.cases().size(), INVALID_INPUT);
    }
    private static void metric(RetrievalGrading.Grade g) {
        if (g == null) return;
        check(g.expectedReferences() >= 0 && g.coveredReferences() >= 0 && g.coveredReferences() <= g.expectedReferences(), INVALID_INPUT);
        check(g.expectedReferences() == 0 ? g.recall() == null && g.reciprocalRank() == null
                : probability(g.recall()) && probability(g.reciprocalRank())
                && Math.abs(g.recall() - (double) g.coveredReferences() / g.expectedReferences()) < 1e-12, INVALID_INPUT);
    }
    private static boolean probability(Double n) { return n != null && Double.isFinite(n) && n >= 0 && n <= 1; }
    static void check(boolean valid, EvaluationReportException.Code code) { if (!valid) throw new EvaluationReportException(code); }
}
