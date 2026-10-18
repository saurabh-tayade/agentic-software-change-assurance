package dev.changeassurance.evaluation;

import dev.changeassurance.embedding.EmbeddingProfile;
import dev.changeassurance.persistence.PersistedPublication;
import dev.changeassurance.retrieval.RetrievalResult;
import dev.changeassurance.retrieval.RetrievalStrategy;
import dev.changeassurance.retrieval.VectorRetrievalException;
import java.util.List;
import java.util.Map;

/** Artifact v1: execution evidence, independent of grading. Null means unavailable, never estimated. */
public record RetrievalEvaluationRun(int schemaVersion, String runId, String startedAt,
                                    String configurationId, RetrievalComparison definition,
                                    Map<String, String> publicationIds, String requestedProfileId,
                                    EmbeddingProfile embeddingProfile, Application application,
                                    String executionMode, Map<String, PersistedPublication> publications,
                                    List<Execution> executions) {
    public record Application(String version, String commit, Boolean dirty, String sourceHash) { }
    public record Execution(String scenarioId, RetrievalStrategy requestedStrategy, int topK,
                            String publicationId, String profileId, Double retrievalDurationMs,
                            RetrievalResult result, String failure, VectorRetrievalException.Coverage failureDiagnostics) { }
}
