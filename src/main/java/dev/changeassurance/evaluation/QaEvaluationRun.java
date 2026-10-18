package dev.changeassurance.evaluation;

import dev.changeassurance.embedding.EmbeddingProfile;
import dev.changeassurance.persistence.PersistedPublication;
import dev.changeassurance.qa.*;
import dev.changeassurance.retrieval.*;
import java.util.List;

/** Additive Q&A artifact; historical retrieval execution v1 remains unchanged. */
public record QaEvaluationRun(int schemaVersion, String artifactType, String runId, String startedAt,
                              String configurationId, QaComparison definition, List<String> caseIds,
                              String publicationId, PersistedPublication publication, String profileId,
                              EmbeddingProfile embeddingProfile, RetrievalEvaluationRun.Application application,
                              String executionMode, GenerationSettings generationSettings, String modelDigest,
                              String modelDigestUnavailableReason, boolean captureRawResponse, List<Execution> executions) {
    public QaEvaluationRun { caseIds = List.copyOf(caseIds); executions = List.copyOf(executions); }
    public record ModelInput(String systemPrompt, String contextJson, String outputSchema) { }
    public record RawResponse(boolean available, Integer lengthChars, Integer lengthBytes,
                              String content, boolean truncated) { }
    public record Failure(String code, String malformedReason, VectorRetrievalException.Coverage indexCoverage) { }
    public record Execution(String id, String scenarioId, RetrievalStrategy requestedStrategy,
                            RetrievalResult retrieval, ModelInput modelInput, boolean modelInvoked,
                            ModelGateway.Diagnostics invocation, RawResponse rawResponse, GroundedAnswer answer,
                            Double retrievalDurationMs, Double modelCallDurationMs, double totalDurationMs,
                            Failure failure) { }
}
