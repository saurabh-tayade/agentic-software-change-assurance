package dev.changeassurance.evaluation;

import dev.changeassurance.qa.*;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Report identity/time are separate from the deterministic, hashable substantive payload. */
public record EvaluationReport(int schemaVersion, String generatorVersion, String reportId, String generatedAt,
                               String payloadHash, Payload payload) {
    public record Input(String role, String path, String sha256, String runId, String configurationId) { }
    public record Payload(List<Input> inputs, List<String> evaluationTypes, Retrieval retrieval, Qa qa,
                          List<Finding> findings, List<String> limitations) { }
    public record Comparison(Integer topK, String status, List<String> reasons) { }
    public record Evidence(String key, String publicationId, String chunkId, String repositoryId, String commit,
                           String path, int startLine, int endLine, String sourceHash, String contentHash) { }
    public record RetrievalCase(String executionId, String scenarioId, String strategy, int topK,
                                List<RetrievalGrading.Reference> missingEvidence, List<Evidence> evidence,
                                Double durationMs, dev.changeassurance.retrieval.VectorRetrievalException.Coverage failureDiagnostics) { }
    public record Retrieval(String verification, JsonNode provenance, RetrievalEvaluationArtifacts.Grades grades,
                            List<Comparison> comparisons, List<RetrievalCase> executions) { }
    public record QaCase(String executionId, String scenarioId, String strategy, String answerStatus,
                         List<GroundedAnswer.Claim> claims, List<String> uncertainties, List<Evidence> retrieved,
                         List<Evidence> context, List<Evidence> citations, boolean modelInvoked, QaEvaluationRun.Failure failure, ModelGateway.Diagnostics invocation,
                         Double retrievalDurationMs, Double modelCallDurationMs, Double totalDurationMs) { }
    public record QaSummary(int totalExecutions, int successfulExecutions, int failedExecutions, int evaluableCitationExecutions, Map<String, Long> reliability, Map<String, Long> citationIdentity,
                            Map<String, Long> structuralAnswerability, Integer answerExecutions, int reviewRecords,
                            Integer reviewedAnswerExecutions, Integer unreviewedAnswerExecutions, Integer totalClaims,
                            Map<String, Long> factualCorrectness, Map<String, Long> citationRelevance,
                            Map<String, Long> claimSupport, Map<String, Long> completeness,
                            Map<String, Long> answerabilityReview, Map<String, Long> uncertaintyReview) { }
    public record Qa(String verification, JsonNode provenance, QaEvaluationArtifacts.Grades grades,
                     QaSummary summary, List<QaCase> executions) { }
    public record Finding(String evaluationType, String scenarioId, String strategy, String executionId,
                          String classification, String explanation, String reviewId, Integer claimIndex,
                          List<Evidence> evidence) { }
}
