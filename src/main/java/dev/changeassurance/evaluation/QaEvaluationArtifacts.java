package dev.changeassurance.evaluation;

import dev.changeassurance.qa.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.changeassurance.evaluation.RetrievalComparison.*;

/** Deterministic offline checks. Identity validity never grants factual or semantic credit. */
public final class QaEvaluationArtifacts {
    private QaEvaluationArtifacts() { }
    private static final tools.jackson.databind.json.JsonMapper CONTEXT_JSON = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public enum Check { VALID, INVALID, NOT_APPLICABLE, NOT_VERIFIABLE }
    public record CaseGrade(String executionId, String executionHash, String reliability,
                            Check provenance, Check context, Check citationIdentity, Check structure,
                            String structuralAnswerability, RetrievalGrading.Grade retrievalCoverage,
                            RetrievalGrading.Grade contextCoverage, List<RetrievalGrading.Reference> retrievalMisses,
                            List<RetrievalGrading.Reference> contextBudgetLosses,
                            List<EvidenceContextBuilder.Selected> contextKeys,
                            String semanticReviewStatus, QaHumanReview humanReview) { }
    public record Grades(int schemaVersion, String artifactType, String gradingVersion, String runId,
                         String configurationId, List<CaseGrade> cases) { }

    public static Grades grade(QaEvaluationRun run) { return grade(run, List.of()); }
    public static Grades grade(QaEvaluationRun run, List<QaHumanReview> reviews) {
        require(run.schemaVersion() == 1 && "grounded-qa-execution".equals(run.artifactType()));
        require(run.configurationId().equals(QaEvaluationRunner.configurationId(run.definition(), run.caseIds(), run.publicationId(),
                run.profileId(), run.generationSettings(), run.executionMode(), run.captureRawResponse())));
        var reviewed = new HashMap<String, QaHumanReview>();
        for (var review : reviews) { review.validate(run); require(reviewed.put(review.executionId(), review) == null); }
        var seen = new HashSet<String>(); var grades = new ArrayList<CaseGrade>();
        for (var e : run.executions()) {
            require(run.caseIds().contains(e.scenarioId()) && run.definition().strategies().contains(e.requestedStrategy()));
            require(e.id().equals(e.requestedStrategy() + ":" + e.scenarioId()) && seen.add(e.id()));
            require((e.answer() == null) != (e.failure() == null));
            require(e.modelInvoked() == (e.modelInput() != null));
            require(Double.isFinite(e.totalDurationMs()) && e.totalDurationMs() >= 0);
            require(e.retrievalDurationMs() == null || Double.isFinite(e.retrievalDurationMs()) && e.retrievalDurationMs() >= 0);
            require(e.modelCallDurationMs() == null || Double.isFinite(e.modelCallDurationMs()) && e.modelCallDurationMs() >= 0);
            var scenario = run.definition().scenarios().stream().filter(s -> s.id().equals(e.scenarioId())).findFirst().orElseThrow();
            Check provenance = Check.NOT_VERIFIABLE, contextCheck = Check.NOT_VERIFIABLE;
            Check citations = e.failure() != null && "INVALID_CITATION".equals(e.failure().code()) ? Check.INVALID : Check.NOT_VERIFIABLE;
            Check structure = e.failure() != null && "MALFORMED_MODEL_OUTPUT".equals(e.failure().code()) ? Check.INVALID : Check.NOT_VERIFIABLE;
            RetrievalGrading.Grade retrieved = null, selected = null;
            List<RetrievalGrading.Reference> misses = List.of(), losses = List.of();
            List<EvidenceContextBuilder.Selected> keys = List.of();
            EvidenceContextBuilder.Context context = null;
            if (e.retrieval() != null) {
                try {
                    require(run.publication() != null && run.publicationId().equals(run.publication().id()));
                    if (e.requestedStrategy() != dev.changeassurance.retrieval.RetrievalStrategy.KEYWORD)
                        require(run.embeddingProfile() != null && run.embeddingProfile().id().equals(run.profileId()));
                    var retrievalCase = run.definition().retrievalCase(scenario);
                    RetrievalEvaluationArtifacts.validatePublication(run.definition().retrievalDefinition(), retrievalCase, run.publication());
                    RetrievalEvaluationArtifacts.validateResult(retrievalCase, e.requestedStrategy(), run.definition().answering().topK(),
                            run.profileId(), run.publication(), e.retrieval());
                    provenance = Check.VALID;
                    var spans = e.retrieval().matches().stream().map(m -> ref(m.evidence())).toList();
                    retrieved = RetrievalGrading.grade(scenario.expected(), spans);
                    misses = missing(scenario.expected(), spans);
                } catch (RuntimeException invalid) { provenance = Check.INVALID; }
            }
            if (provenance == Check.VALID) {
                if (e.modelInvoked()) {
                    try {
                        // Recompute selection only to verify the captured input; never repeat retrieval or inference.
                        context = new EvidenceContextBuilder().build(e.retrieval(), run.definition().answering());
                        require(!context.entries().isEmpty() && sameContext(context.json(), e.modelInput().contextJson()));
                        contextCheck = Check.VALID; keys = context.selection().selected();
                        var spans = context.entries().stream().map(entry -> ref(entry.evidence())).toList();
                        selected = RetrievalGrading.grade(scenario.expected(), spans);
                        losses = missing(scenario.expected(), spans).stream().filter(r -> !missesFor(scenario, e).contains(r)).toList();
                    } catch (RuntimeException invalid) { context = null; contextCheck = Check.INVALID; }
                } else if (e.answer() != null && e.retrieval().matches().isEmpty()) {
                    contextCheck = Check.NOT_APPLICABLE; selected = RetrievalGrading.grade(scenario.expected(), List.of());
                }
            }
            if (e.answer() != null) {
                try { validateStructure(e.answer()); structure = Check.VALID; }
                catch (RuntimeException invalid) { structure = Check.INVALID; }
                if (provenance == Check.VALID && (context != null || contextCheck == Check.NOT_APPLICABLE)) {
                    try {
                        validateAnswerIdentity(run, e, context);
                        citations = e.answer().claims().isEmpty() ? Check.NOT_APPLICABLE : Check.VALID;
                    } catch (RuntimeException invalid) { citations = Check.INVALID; }
                }
            }
            String abstention = structure != Check.VALID ? "NOT_VERIFIABLE" : e.answer().status() == GroundedAnswer.Status.INSUFFICIENT_EVIDENCE
                    ? "STRUCTURAL_ABSTENTION_SEMANTICS_NOT_VERIFIED" : "STRUCTURAL_ANSWER_SEMANTICS_NOT_VERIFIED";
            var review = reviewed.get(e.id());
            grades.add(new CaseGrade(e.id(), executionHash(e), e.failure() == null ? "SUCCESS" : e.failure().code(), provenance,
                    contextCheck, citations, structure, abstention, retrieved, selected, misses, losses, keys,
                    review == null ? "NOT_REVIEWED" : "HUMAN_REVIEW_RECORDED", review));
        }
        require(seen.size() == run.caseIds().size() * run.definition().strategies().size());
        return new Grades(1, "grounded-qa-grades", "qa-independent-dimensions-v1", run.runId(), run.configurationId(), List.copyOf(grades));
    }
    private static List<RetrievalGrading.Reference> missesFor(QaComparison.Scenario s, QaEvaluationRun.Execution e) {
        return missing(s.expected(), e.retrieval().matches().stream().map(m -> ref(m.evidence())).toList());
    }
    private static List<RetrievalGrading.Reference> missing(List<RetrievalGrading.Reference> expected, List<RetrievalGrading.Reference> actual) {
        return expected.stream().filter(r -> RetrievalGrading.grade(List.of(r), actual).coveredReferences() == 0).toList();
    }
    private static RetrievalGrading.Reference ref(dev.changeassurance.processing.EvidenceChunk e) { return new RetrievalGrading.Reference(e.filePath(), e.startLine(), e.endLine()); }

    private static void validateStructure(GroundedAnswer a) {
        require(a.status() != null && a.claims().size() <= 8 && a.uncertainties().size() <= 10);
        text(a.answer(), 4000); a.uncertainties().forEach(s -> text(s, 500));
        for (var c : a.claims()) { text(c.text(), 1000); require(!c.evidenceKeys().isEmpty() && c.evidenceKeys().size() <= 8
                && new HashSet<>(c.evidenceKeys()).size() == c.evidenceKeys().size()); }
        if (a.status() == GroundedAnswer.Status.INSUFFICIENT_EVIDENCE) require(a.claims().isEmpty() && a.citations().isEmpty() && !a.uncertainties().isEmpty());
        else {
            require(!a.claims().isEmpty());
            require(a.answer().equals(String.join("\n", a.claims().stream().map(GroundedAnswer.Claim::text).toList())));
            if (a.status() == GroundedAnswer.Status.PARTIALLY_SUPPORTED) require(!a.uncertainties().isEmpty());
        }
    }
    static void text(String text, int bound) { require(text != null && !text.isBlank() && text.length() <= bound && text.indexOf('\0') < 0 && StandardCharsets.UTF_8.newEncoder().canEncode(text)); }
    static boolean sameContext(String expected, String actual) {
        // Map.of field iteration order can differ between JVMs. Preserve arrays/source text,
        // but do not mistake equivalent JSON object ordering for different evidence.
        return CONTEXT_JSON.readTree(expected).equals(CONTEXT_JSON.readTree(actual));
    }
    private static void validateAnswerIdentity(QaEvaluationRun run, QaEvaluationRun.Execution e, EvidenceContextBuilder.Context context) {
        var a = e.answer();
        require(a.publication().equals(e.retrieval().publication()) && a.question().equals(e.retrieval().query())
                && a.retrievalStrategy().equals(e.retrieval().strategy()) && a.retrievalLimitations().equals(e.retrieval().limitations())
                && a.configuration().equals(run.definition().answering()) && a.generationSettings().equals(run.generationSettings())
                && a.version().equals(run.definition().answerVersion()) && a.promptVersion().equals(run.definition().promptVersion())
                && Objects.equals(a.invocation(), e.invocation()));
        var expected = new LinkedHashMap<String, GroundedAnswer.Citation>();
        if (context != null) {
            require(a.contextSelection().equals(context.selection()));
            require(a.promptHash().equals(hash(e.modelInput().systemPrompt().getBytes(StandardCharsets.UTF_8)))
                    && a.schemaHash().equals(hash(e.modelInput().outputSchema().getBytes(StandardCharsets.UTF_8))));
            for (var c : a.claims()) for (String key : c.evidenceKeys()) {
                var entry = context.entries().stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
                expected.putIfAbsent(key, new GroundedAnswer.Citation(key, run.publicationId(), entry.evidence()));
            }
        } else require(a.claims().isEmpty() && a.contextSelection().selected().isEmpty() && e.invocation() == null);
        require(a.citations().equals(List.copyOf(expected.values())));
    }
    public static String executionHash(QaEvaluationRun.Execution e) { return hash(JSON.writeValueAsBytes(e)); }
    public static Path write(Path root, QaEvaluationRun run) throws IOException {
        var grades = grade(run); require(run.runId().matches("[0-9a-f-]{36}"));
        Files.createDirectories(root); Path directory = Files.createDirectory(root.resolve(run.runId()));
        RetrievalEvaluationArtifacts.writeNew(directory.resolve("qa-execution.json"), run);
        RetrievalEvaluationArtifacts.writeNew(directory.resolve("qa-grades.json"), grades); return directory;
    }
    public static QaEvaluationRun read(Path path) throws IOException {
        return JSON.readValue(boundedRead(path, 64 * 1024 * 1024), QaEvaluationRun.class);
    }
}
