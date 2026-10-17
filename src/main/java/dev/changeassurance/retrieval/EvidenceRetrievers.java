package dev.changeassurance.retrieval;

import dev.changeassurance.embedding.EmbeddingProfile;
import java.util.Objects;

/** Explicit binding only; no model calls, profile discovery, indexing, or fallback. */
public final class EvidenceRetrievers {
    private EvidenceRetrievers() { }
    public static EvidenceRetriever select(RetrievalStrategy strategy, EvidenceRetriever keyword,
                                           VectorRetrievalService vector, EmbeddingProfile profile) {
        Objects.requireNonNull(strategy);
        Objects.requireNonNull(keyword);
        if (strategy == RetrievalStrategy.KEYWORD) {
            if (profile != null) throw new IllegalArgumentException("Keyword retrieval does not use an embedding profile");
            return keyword;
        }
        if (vector == null || profile == null) throw new IllegalArgumentException("Vector and hybrid retrieval require a service and profile");
        if (strategy == RetrievalStrategy.VECTOR) return (id, query, topK) -> vector.search(id, query, topK, profile);
        var hybrid = new HybridRetrievalService(keyword, vector);
        return (id, query, topK) -> hybrid.search(id, query, topK, profile);
    }
}
