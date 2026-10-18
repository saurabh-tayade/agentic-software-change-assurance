package dev.changeassurance.evaluation;

import dev.changeassurance.persistence.*;
import dev.changeassurance.processing.EvidenceChunk;
import dev.changeassurance.qa.*;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static dev.changeassurance.evaluation.EvaluationReport.*;
import static dev.changeassurance.evaluation.EvaluationReportInputs.*;
import static dev.changeassurance.evaluation.EvaluationReportException.Code.*;

/** Offline projection of verified existing grades. No alternate metric or semantic grader. */
public final class EvaluationReportGenerator {
    public static final String VERSION = "evaluation-report-v1";
    public record Request(Path retrievalExecution, Path retrievalGrades, Path qaExecution, List<Path> qaGrades) {
        public Request { qaGrades = List.copyOf(qaGrades); }
    }
    public EvaluationReport generate(Request request) {
        try { return generateChecked(request); }
        catch (EvaluationReportException failure) { throw failure; }
        catch (RuntimeException invalid) { throw new EvaluationReportException(INVALID_INPUT); }
    }
    private EvaluationReport generateChecked(Request request) {
        var inputs = EvaluationReportInputs.load(request.retrievalExecution(), request.retrievalGrades(), request.qaExecution(), request.qaGrades());
        var limitations = new ArrayList<String>(List.of(
                "Small visible development populations; no held-out, statistical significance, or production-performance claim.",
                "Execution success and citation identity validity do not establish semantic relevance or factual correctness.",
                "Structural abstention does not establish appropriate abstention. Unreviewed dimensions earn no correctness credit.",
                "Timing samples and token usage are retained when available; no latency percentiles or inferred usage.",
                "Artifacts are local observations, not signed attestations. Coordinated edits are outside this integrity boundary.",
                "Historical M1.5 baseline files are not inputs to this report; M1.8a comparisons retain their own definition/run identities."));
        var findings = new ArrayList<Finding>(); var types = new ArrayList<String>();
        Retrieval retrieval = null; Qa qa = null;
        if (inputs.retrievalGrades != null) { types.add("RETRIEVAL"); retrieval = retrieval(inputs, findings, limitations); }
        if (inputs.qaGrades != null) { types.add("QA"); qa = qa(inputs, findings, limitations); }
        if (retrieval != null && qa != null) limitations.add("Retrieval and Q&A populations/configurations are reported separately, never pooled or ranked against each other.");
        findings.sort(Comparator.comparing(Finding::evaluationType).thenComparing(Finding::executionId).thenComparing(Finding::classification)
                .thenComparing(f -> f.claimIndex() == null ? 0 : f.claimIndex()));
        var payload = new Payload(List.copyOf(inputs.manifest), List.copyOf(types), retrieval, qa, List.copyOf(findings), List.copyOf(limitations));
        return new EvaluationReport(1, VERSION, UUID.randomUUID().toString(), Instant.now().toString(), payloadHash(payload), payload);
    }
    private Retrieval retrieval(EvaluationReportInputs i, List<Finding> findings, List<String> limitations) {
        var run = i.retrievalRun; var grades = i.retrievalGrades;
        var details = new ArrayList<RetrievalCase>(); var comparisons = new ArrayList<Comparison>();
        for (var c : grades.cases()) {
            String id = c.strategy() + ":K=" + c.topK() + ":" + c.scenarioId();
            if (c.failure() != null) findings.add(new Finding("RETRIEVAL", c.scenarioId(), c.strategy().name(), id,
                    "RETRIEVAL_FAILURE", "Recorded retrieval failure: " + c.failure(), null, null, List.of()));
            if (run == null) continue;
            var e = run.executions().stream().filter(x -> x.scenarioId().equals(c.scenarioId()) && x.requestedStrategy() == c.strategy() && x.topK() == c.topK()).findFirst().orElseThrow();
            List<Evidence> evidence = e.result() == null ? List.of() : e.result().matches().stream().map(m -> evidence(null, e.publicationId(), m.evidence())).toList();
            List<RetrievalGrading.Reference> missing = null;
            if (c.failure() == null) {
                var expected = run.definition().scenarios().stream().filter(s -> s.id().equals(c.scenarioId())).findFirst().orElseThrow().expected();
                var actual = e.result().matches().stream().map(m -> new RetrievalGrading.Reference(m.evidence().filePath(), m.evidence().startLine(), m.evidence().endLine())).toList();
                missing = expected.stream().filter(ref -> RetrievalGrading.grade(List.of(ref), actual).coveredReferences() == 0).toList();
                if (!missing.isEmpty()) findings.add(new Finding("RETRIEVAL", c.scenarioId(), c.strategy().name(), id,
                        "EXPECTED_EVIDENCE_MISSING", missing.size() + " expected source spans were not fully covered; see retrieval execution details.", null, null, evidence));
            }
            details.add(new RetrievalCase(id, c.scenarioId(), c.strategy().name(), c.topK(), missing, evidence, e.retrievalDurationMs(), e.failureDiagnostics()));
        }
        for (int k : grades.aggregates().stream().mapToInt(RetrievalEvaluationArtifacts.Aggregate::topK).distinct().sorted().toArray()) {
            var reasons = new ArrayList<String>();
            if (run == null) reasons.add("Execution unavailable: source identities, populations, configuration, and metrics cannot be verified.");
            var groups = grades.aggregates().stream().filter(a -> a.topK() == k).toList();
            if (groups.size() < 2) reasons.add("Fewer than two strategies supplied.");
            if (groups.stream().anyMatch(a -> a.failed() > 0)) reasons.add("Incomplete successful populations: compare denominators and failed case IDs; no ranking.");
            if (groups.stream().anyMatch(a -> a.qualityDenominator() == 0)) reasons.add("At least one strategy has no successful positive-evidence cases.");
            var populations = groups.stream().map(a -> grades.cases().stream().filter(c -> c.topK() == k && c.strategy() == a.strategy()
                    && c.failure() == null && c.metrics().expectedReferences() > 0).map(RetrievalEvaluationArtifacts.CaseGrade::scenarioId).sorted().toList()).distinct().count();
            if (populations > 1) reasons.add("Successful positive-evidence scenario sets differ.");
            comparisons.add(new Comparison(k, reasons.isEmpty() ? "COMPARABLE_WITHIN_RUN" : "NOT_DIRECTLY_COMPARABLE", List.copyOf(reasons)));
        }
        JsonNode provenance = run == null ? null : JSON.valueToTree(map("definition", run.definition(), "configurationId", run.configurationId(),
                "application", run.application(), "executionMode", run.executionMode(), "publicationIds", run.publicationIds(),
                "publications", summaries(run.publications()), "embeddingProfileId", run.requestedProfileId(), "embeddingProfile", run.embeddingProfile(),
                "effectiveStrategies", run.executions().stream().filter(e -> e.result() != null).map(e -> e.result().strategy()).distinct().sorted().toList()));
        if (run == null) limitations.add("Retrieval grades only: recorded metrics are unverified observations; expected spans, timing, and source provenance are unavailable.");
        else if (run.application() == null || run.application().commit() == null || run.application().version() == null)
            limitations.add("Retrieval application version or revision metadata is unavailable in the capture.");
        return new Retrieval(verification(run), provenance, grades, List.copyOf(comparisons), List.copyOf(details));
    }
    private Qa qa(EvaluationReportInputs i, List<Finding> findings, List<String> limitations) {
        var run = i.qaRun; var grades = i.qaGrades; var details = new ArrayList<QaCase>();
        for (var g : grades.cases()) {
            var e = run == null ? null : run.executions().stream().filter(x -> x.id().equals(g.executionId())).findFirst().orElseThrow();
            String[] identity = g.executionId().split(":", 2); String strategy = identity[0], scenario = identity.length == 2 ? identity[1] : "UNAVAILABLE";
            List<Evidence> retrieved = e == null || e.retrieval() == null ? List.of() : e.retrieval().matches().stream().map(m -> evidence(null, run.publicationId(), m.evidence())).toList();
            List<Evidence> context = e == null || e.retrieval() == null ? List.of() : g.contextKeys().stream().map(key -> {
                var chunk = e.retrieval().matches().stream().filter(m -> m.evidence().id().equals(key.chunkId())).findFirst().orElseThrow().evidence();
                return evidence(key.key(), run.publicationId(), chunk);
            }).toList();
            List<Evidence> citations = e == null || e.answer() == null ? List.of() : e.answer().citations().stream().map(c -> evidence(c.key(), c.publicationId(), c.evidence())).toList();
            if (e != null) details.add(new QaCase(e.id(), e.scenarioId(), strategy, e.answer() == null ? null : e.answer().status().name(),
                    e.answer() == null ? null : e.answer().claims(), e.answer() == null ? null : e.answer().uncertainties(), retrieved, context, citations,
                    e.modelInvoked(), e.failure(), e.invocation(), e.retrievalDurationMs(), e.modelCallDurationMs(), e.totalDurationMs()));
            var labels = new LinkedHashMap<String, String>();
            if (!g.reliability().equals("SUCCESS")) {
                String label = g.reliability().startsWith("MODEL_") ? "MODEL_OR_PROVIDER_FAILURE" : g.reliability().equals("MALFORMED_MODEL_OUTPUT")
                        ? "STRUCTURAL_ANSWER_FAILURE" : g.reliability().equals("INVALID_CITATION") ? "INVALID_CITATION_IDENTITY"
                        : g.reliability().startsWith("EMBEDDING_") || g.reliability().contains("RETRIEVAL") ? "RETRIEVAL_FAILURE" : "EXECUTION_FAILURE";
                labels.put(label, "Recorded failure: " + g.reliability());
            }
            if (g.provenance() == QaEvaluationArtifacts.Check.INVALID) labels.put("EVIDENCE_INTEGRITY_FAILURE", "Offline provenance checks failed.");
            if (g.context() == QaEvaluationArtifacts.Check.INVALID) labels.put("CONTEXT_INTEGRITY_FAILURE", "Captured context could not be validated against retrieval.");
            if (!g.retrievalMisses().isEmpty()) labels.put("EXPECTED_EVIDENCE_MISSING", g.retrievalMisses().size() + " expected spans were not fully retrieved.");
            if (!g.contextBudgetLosses().isEmpty()) labels.put("CONTEXT_EVIDENCE_LOSS", g.contextBudgetLosses().size() + " fully retrieved expected spans were not fully supplied to the model.");
            if (g.citationIdentity() == QaEvaluationArtifacts.Check.INVALID) labels.put("INVALID_CITATION_IDENTITY", "Citation identity validation failed; this is separate from semantic support.");
            if (g.structure() == QaEvaluationArtifacts.Check.INVALID) labels.put("STRUCTURAL_ANSWER_FAILURE", "Structured answer validation failed.");
            if (g.humanReview() == null) labels.put("SEMANTIC_NOT_REVIEWED", "No attributed review was explicitly supplied for this execution; no semantic correctness conclusion.");
            for (var label : labels.entrySet()) findings.add(new Finding("QA", scenario, strategy, g.executionId(), label.getKey(), label.getValue(), null, null, context));
            if (g.humanReview() != null) for (var issue : g.humanReview().factualIssues())
                findings.add(new Finding("QA", scenario, strategy, g.executionId(), "HUMAN_REVIEWED_FACTUAL_ISSUE",
                        "Reviewer recorded " + issue.category() + " for " + issue.target() + " " + issue.index() + "; see the source references and rationale in the review.",
                        g.humanReview().reviewId(), issue.target() == QaHumanReview.Target.CLAIM ? issue.index() : null, citations));
            if (g.humanReview() != null) for (var claim : g.humanReview().claims()) {
                if (claim.factualCorrectness() == QaHumanReview.FactualCorrectness.INCORRECT)
                    findings.add(new Finding("QA", scenario, strategy, g.executionId(), "HUMAN_REVIEWED_FACTUAL_ERROR",
                            "Reviewer marked claim " + claim.claimIndex() + " INCORRECT. Citation identity remains " + g.citationIdentity() + ". See the attributed review and rationale.",
                            g.humanReview().reviewId(), claim.claimIndex(), citations));
            }
        }
        JsonNode provenance = run == null ? null : JSON.valueToTree(map("definition", run.definition(), "selectedCaseIds", run.caseIds(),
                "configurationId", run.configurationId(), "application", run.application(), "executionMode", run.executionMode(),
                "publicationId", run.publicationId(), "publication", run.publication() == null ? null : summary(run.publication()),
                "embeddingProfileId", run.profileId(), "embeddingProfile", run.embeddingProfile(), "generationSettings", run.generationSettings(),
                "modelDigest", run.modelDigest(), "modelDigestUnavailableReason", run.modelDigestUnavailableReason(),
                "promptHashes", run.executions().stream().filter(e -> e.modelInput() != null).map(e -> hashText(e.modelInput().systemPrompt())).distinct().sorted().toList(),
                "schemaHashes", run.executions().stream().filter(e -> e.modelInput() != null).map(e -> hashText(e.modelInput().outputSchema())).distinct().sorted().toList(),
                "effectiveStrategies", run.executions().stream().filter(e -> e.retrieval() != null).map(e -> e.retrieval().strategy()).distinct().sorted().toList()));
        if (run == null) limitations.add("Q&A grades only: capture, claim population, provenance, timing, and semantic review bindings are unavailable. Recorded checks have not been replayed.");
        else {
            if (run.modelDigest() == null) limitations.add("Answer model digest unavailable: a configured model tag is mutable, so fresh generation is not exactly reproducible.");
            if (run.application() == null || run.application().commit() == null || run.application().version() == null)
                limitations.add("Q&A application version or revision metadata is unavailable in the capture.");
        }
        limitations.add("Human review is explicitly selected, may be sparse, and is not a representative correctness estimate; unreviewed/indeterminate dimensions remain separate.");
        return new Qa(verification(run), provenance, grades, qaSummary(run, grades), List.copyOf(details));
    }
    private QaSummary qaSummary(QaEvaluationRun run, QaEvaluationArtifacts.Grades grades) {
        var reliability = counts(); var citations = counts("VALID", "INVALID", "NOT_APPLICABLE", "NOT_VERIFIABLE"); var structural = counts();
        int records = 0;
        for (var g : grades.cases()) { add(reliability, g.reliability()); add(citations, g.citationIdentity().name()); add(structural, g.structuralAnswerability()); if (g.humanReview() != null) records++; }
        int successful = Math.toIntExact(reliability.getOrDefault("SUCCESS", 0L));
        int applicable = Math.toIntExact(citations.get("VALID") + citations.get("INVALID"));
        if (run == null) return new QaSummary(grades.cases().size(), successful, grades.cases().size() - successful, applicable, reliability, citations, structural, null, records, null, null, null, null, null, null, null, null, null);
        var factual = counts("CORRECT", "INCORRECT", "INDETERMINATE", "NOT_REVIEWED");
        var relevance = counts("RELEVANT", "PARTIALLY_RELEVANT", "IRRELEVANT", "INDETERMINATE", "NOT_REVIEWED");
        var support = counts("0", "1", "2", "NOT_REVIEWED"); var completeness = counts("0", "1", "2", "NOT_REVIEWED");
        var answerability = counts("APPROPRIATE", "OVERCLAIM", "UNNECESSARY_ABSTENTION", "NOT_REVIEWED");
        var uncertainty = counts("ADEQUATE", "INADEQUATE", "NOT_REVIEWED");
        int answers = 0, reviewed = 0, claims = 0;
        for (var e : run.executions()) {
            if (e.answer() == null) continue;
            answers++; claims += e.answer().claims().size();
            var review = grades.cases().stream().filter(g -> g.executionId().equals(e.id())).findFirst().orElseThrow().humanReview();
            if (review != null) reviewed++;
            for (int n = 0; n < e.answer().claims().size(); n++) {
                var claim = review == null ? null : review.claims().get(n);
                add(factual, claim == null ? "NOT_REVIEWED" : claim.factualCorrectness().name());
                add(relevance, claim == null ? "NOT_REVIEWED" : claim.citationRelevance().name());
                add(support, claim == null || claim.supportScore() == null ? "NOT_REVIEWED" : claim.supportScore().toString());
            }
            var scenario = run.definition().scenarios().stream().filter(s -> s.id().equals(e.scenarioId())).findFirst().orElseThrow();
            for (int n = 0; n < scenario.expectedFindings().size(); n++)
                add(completeness, review == null || review.findings().get(n).completenessScore() == null ? "NOT_REVIEWED" : review.findings().get(n).completenessScore().toString());
            add(answerability, review == null ? "NOT_REVIEWED" : review.answerability().name());
            add(uncertainty, review == null ? "NOT_REVIEWED" : review.uncertaintyQuality().name());
        }
        return new QaSummary(grades.cases().size(), successful, grades.cases().size() - successful, applicable, reliability, citations, structural, answers, records, reviewed, answers - reviewed,
                claims, factual, relevance, support, completeness, answerability, uncertainty);
    }
    private static Map<String, Long> counts(String... keys) { var m = new TreeMap<String, Long>(); for (String key : keys) m.put(key, 0L); return m; }
    private static void add(Map<String, Long> m, String key) { m.merge(key, 1L, Long::sum); }
    private static String verification(Object run) { return run == null ? "GRADES_ONLY_NOT_VERIFIABLE" : "REPLAYED_FROM_EXECUTION"; }
    private static Evidence evidence(String key, String publication, EvidenceChunk e) { return new Evidence(key, publication, e.id(), e.snapshot().repositoryId(), e.snapshot().commitSha(), e.filePath(), e.startLine(), e.endLine(), e.sourceHash(), e.contentHash()); }
    private static PublicationSummary summary(PersistedPublication p) {
        var r = p.processing(); return new PublicationSummary(p.id(), p.snapshotId(), p.derivationId(), p.ingestion().snapshot(), new PublicationMetadata(
                p.ingestion().artifacts().stream().map(a -> a.path()).toList(), r.configuration(), r.status(), r.artifacts(), r.ingestionCoverage(), r.ingestionLimitations(), r.ingestionSummary(), r.processingLimitations()));
    }
    private static Map<String, PublicationSummary> summaries(Map<String, PersistedPublication> pubs) { var result = new TreeMap<String, PublicationSummary>(); pubs.forEach((key, p) -> result.put(key, summary(p))); return result; }
    private static Map<String, Object> map(Object... fields) { var result = new TreeMap<String, Object>(); for (int n = 0; n < fields.length; n += 2) result.put((String) fields[n], fields[n + 1]); return result; }
    private static String hashText(String s) { return RetrievalComparison.hash(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    public static String payloadHash(Payload payload) { return RetrievalComparison.hash(JSON.writeValueAsBytes(canonical(JSON.valueToTree(payload)))); }
    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) { var result = JSON.createObjectNode(); new TreeSet<>(node.propertyNames()).forEach(key -> result.set(key, canonical(node.get(key)))); return result; }
        if (node.isArray()) { var result = JSON.createArrayNode(); node.forEach(value -> result.add(canonical(value))); return result; } return node;
    }
    public Path write(Path directory, EvaluationReport report) {
        check(report.schemaVersion() == 1 && VERSION.equals(report.generatorVersion()) && payloadHash(report.payload()).equals(report.payloadHash()), INVALID_INPUT);
        try {
            Path parent = directory.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent);
            Files.createDirectory(directory);
            RetrievalEvaluationArtifacts.writeNew(directory.resolve("report.json"), report);
            Files.writeString(directory.resolve("report.md"), EvaluationReportMarkdown.render(report), StandardOpenOption.CREATE_NEW);
            return directory;
        } catch (FileAlreadyExistsException e) { throw new EvaluationReportException(OUTPUT_EXISTS); }
        catch (java.io.IOException e) { throw new EvaluationReportException(IO_FAILURE); }
    }
}
