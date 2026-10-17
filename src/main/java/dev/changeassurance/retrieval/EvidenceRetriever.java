package dev.changeassurance.retrieval;

import java.util.Optional;

/** A publication-scoped retriever with any required model profile already bound. */
@FunctionalInterface
public interface EvidenceRetriever {
    Optional<RetrievalResult> search(String publicationId, String query, int topK);
}
