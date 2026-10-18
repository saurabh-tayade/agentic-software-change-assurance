package dev.changeassurance.evaluation;

import dev.changeassurance.embedding.EmbeddingException;
import dev.changeassurance.embedding.EmbeddingProfile;
import dev.changeassurance.persistence.*;
import dev.changeassurance.retrieval.*;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** One attempt per strategy/K/case. Never publishes, indexes, retries, or substitutes strategies. */
public final class RetrievalEvaluationRunner {
    private final Function<String, Optional<PersistedPublication>> publications;
    private final Function<RetrievalStrategy, EvidenceRetriever> retrievers;
    public RetrievalEvaluationRunner(Function<String, Optional<PersistedPublication>> publications,
                                     Function<RetrievalStrategy, EvidenceRetriever> retrievers) {
        this.publications = publications; this.retrievers = retrievers;
    }

    public RetrievalEvaluationRun execute(RetrievalComparison definition, Map<String, String> publicationIds,
                                          String profileId, EmbeddingProfile profile,
                                          RetrievalEvaluationRun.Application application, String mode) {
        RetrievalComparison.require(Set.of("LIVE", "TEST_DOUBLE").contains(mode));
        String startedAt = Instant.now().toString();
        var ids = new TreeMap<>(publicationIds);
        RetrievalComparison.require(Set.of("full", "scoped", "limited").containsAll(ids.keySet()));
        ids.values().forEach(PublicationIntegrity::validateId);
        if (profileId != null) PublicationIntegrity.validateId(profileId);
        var saved = new TreeMap<String, PersistedPublication>();
        var failures = new HashMap<String, String>();
        for (var entry : ids.entrySet()) {
            try {
                var publication = publications.apply(entry.getValue()).orElseThrow(() -> new EvaluationFailure("UNKNOWN_PUBLICATION"));
                RetrievalComparison.require(publication.id().equals(entry.getValue()));
                saved.put(entry.getKey(), publication);
            } catch (RuntimeException e) { failures.put(entry.getKey(), category(e)); }
        }
        var executions = new ArrayList<RetrievalEvaluationRun.Execution>();
        for (var strategy : definition.strategies()) for (int k : definition.topK()) for (var scenario : definition.scenarios()) {
            String publicationId = ids.get(scenario.publicationVariant());
            String selectedProfile = strategy == RetrievalStrategy.KEYWORD ? null : profileId;
            Double duration = null;
            RetrievalResult result = null;
            String failure = null;
            VectorRetrievalException.Coverage diagnostics = null;
            try {
                if (publicationId == null) throw new EvaluationFailure("PUBLICATION_NOT_CONFIGURED");
                if (failures.containsKey(scenario.publicationVariant())) throw new EvaluationFailure(failures.get(scenario.publicationVariant()));
                var publication = saved.get(scenario.publicationVariant());
                RetrievalEvaluationArtifacts.validatePublication(definition, scenario, publication);
                // Variants must describe the same committed repository, not separately edited copies.
                for (var other : saved.values())
                    if (!other.ingestion().snapshot().commitSha().equals(publication.ingestion().snapshot().commitSha())
                            || !other.ingestion().snapshot().policyVersion().equals(publication.ingestion().snapshot().policyVersion()))
                        throw new EvaluationFailure("PUBLICATION_MISMATCH");
                if (strategy != RetrievalStrategy.KEYWORD) {
                    if (profile == null) throw new EvaluationFailure("PROFILE_NOT_AVAILABLE");
                    if (!profile.id().equals(profileId)) throw new EvaluationFailure("PROFILE_MISMATCH");
                }
                var retriever = retrievers.apply(strategy);
                if (retriever == null) throw new EvaluationFailure("STRATEGY_NOT_AVAILABLE");
                long start = System.nanoTime();
                try { result = retriever.search(publicationId, scenario.query(), k).orElseThrow(() -> new EvaluationFailure("UNKNOWN_PUBLICATION")); }
                finally { duration = (System.nanoTime() - start) / 1_000_000.0; }
                RetrievalEvaluationArtifacts.validateResult(scenario, strategy, k, selectedProfile, publication, result);
            } catch (RuntimeException e) {
                failure = category(e);
                if (e instanceof VectorRetrievalException v) diagnostics = v.coverage();
            }
            executions.add(new RetrievalEvaluationRun.Execution(scenario.id(), strategy, k, publicationId,
                    selectedProfile, duration, result, failure, diagnostics));
        }
        return new RetrievalEvaluationRun(1, UUID.randomUUID().toString(), startedAt,
                configurationId(definition, ids, profileId, mode), definition, Collections.unmodifiableMap(ids), profileId,
                profile, application, mode, Collections.unmodifiableMap(saved), List.copyOf(executions));
    }

    static String configurationId(RetrievalComparison d, Map<String, String> ids, String profileId, String mode) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("definition", d); fields.put("publications", new TreeMap<>(ids)); fields.put("profile", profileId);
        fields.put("mode", mode);
        return RetrievalComparison.hash(RetrievalComparison.JSON.writeValueAsBytes(fields));
    }
    private static String category(RuntimeException e) {
        if (e instanceof EvaluationFailure f) return f.code;
        if (e instanceof EmbeddingException f) return "EMBEDDING_" + f.code().name();
        if (e instanceof VectorRetrievalException f) return "VECTOR_" + f.code().name();
        if (e instanceof PublicationIntegrityException) return "PUBLICATION_INTEGRITY_FAILURE";
        if (e instanceof EvidencePersistenceException) return "PUBLICATION_READ_FAILURE";
        if (e instanceof RetrievalException) return "RETRIEVAL_FAILURE";
        if (e instanceof IllegalArgumentException) return "INVALID_EXECUTION";
        return "UNEXPECTED_EXECUTION_FAILURE";
    }
    static final class EvaluationFailure extends RuntimeException {
        final String code;
        EvaluationFailure(String code) { super(code); this.code = code; }
    }
}
