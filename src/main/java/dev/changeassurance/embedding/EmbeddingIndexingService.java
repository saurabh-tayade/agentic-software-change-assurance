package dev.changeassurance.embedding;

import dev.changeassurance.persistence.EvidencePersistenceService;
import dev.changeassurance.persistence.PublicationIntegrity;
import dev.changeassurance.processing.ProcessingResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Explicit, resumable indexing; no changes to publication or source records. */
public final class EmbeddingIndexingService {
    private final EvidencePersistenceService publications;
    private final EmbeddingStore store;
    private final EmbeddingGateway gateway;
    public EmbeddingIndexingService(EvidencePersistenceService publications, EmbeddingStore store, EmbeddingGateway gateway) {
        this.publications = publications; this.store = store; this.gateway = gateway;
    }
    public Report index(String publicationId, EmbeddingProfile profile) {
        Objects.requireNonNull(profile);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Indexing must run outside a caller transaction");
        var publication = publications.findPublication(publicationId)
                .orElseThrow(() -> new EmbeddingException(EmbeddingException.Code.UNKNOWN_PUBLICATION));
        publication.processing().chunks().forEach(PublicationIntegrity::verifyChunk);
        if (!profile.equals(gateway.resolveProfile())) throw new EmbeddingException(EmbeddingException.Code.PROFILE_MISMATCH);
        store.register(profile);
        var existing = store.existing(publication, profile);
        var issues = new ArrayList<Issue>();
        int issueCount = 0;
        int inserted = 0;
        long modelDurationMillis = 0;
        // Validate every reused row before any new provider work, including rows after a failed chunk.
        for (var chunk : publication.processing().chunks()) {
            if (existing.containsKey(chunk.id())) {
                try {
                    if (!profile.document(chunk.content()).hash().equals(existing.get(chunk.id())))
                        throw new EmbeddingException(EmbeddingException.Code.INDEX_INTEGRITY_FAILURE);
                } catch (EmbeddingException e) { throw new EmbeddingException(EmbeddingException.Code.INDEX_INTEGRITY_FAILURE); }
            }
        }
        for (var chunk : publication.processing().chunks()) {
            if (existing.containsKey(chunk.id())) continue;
            try {
                var input = profile.document(chunk.content());
                var response = gateway.embed(profile, input);
                if (response == null) throw new EmbeddingException(EmbeddingException.Code.INVALID_PROVIDER_RESPONSE);
                float[] vector = response.vector();
                EmbeddingVector.validate(vector, profile.dimensions());
                if (store.insert(publication, chunk.id(), profile, input, vector)) inserted++;
                modelDurationMillis += response.durationMillis();
            } catch (EmbeddingException e) {
                issueCount++;
                if (issues.size() < 100) issues.add(new Issue(chunk.id(), chunk.filePath(), chunk.startLine(), chunk.endLine(), e.code()));
                // Input rejection is chunk-specific; other failures stop this run without retries.
                if (e.code() != EmbeddingException.Code.OVERSIZED_INPUT && e.code() != EmbeddingException.Code.MODEL_INPUT_REJECTED) break;
            }
        }
        var finalRows = store.existing(publication, profile);
        int total = publication.processing().chunks().size();
        for (var chunk : publication.processing().chunks()) {
            if (finalRows.containsKey(chunk.id()) && !profile.document(chunk.content()).hash().equals(finalRows.get(chunk.id())))
                throw new EmbeddingException(EmbeddingException.Code.INDEX_INTEGRITY_FAILURE);
        }
        int indexed = finalRows.size();
        Status status = issueCount == 0 && indexed == total ? Status.COMPLETE : indexed == 0 ? Status.FAILED : Status.PARTIAL;
        return new Report(publicationId, profile.id(), profile, publication.processing().status(), status,
                total, indexed, total - indexed, existing.size(), inserted, issueCount, issues, modelDurationMillis);
    }
    public enum Status { COMPLETE, PARTIAL, FAILED }
    public record Issue(String chunkId, String path, int startLine, int endLine, EmbeddingException.Code reason) { }
    public record Report(String publicationId, String profileId, EmbeddingProfile profile,
                         ProcessingResult.Status processingStatus, Status status, int totalChunks, int indexedChunks,
                         int missingChunks, int reusedChunks, int insertedChunks, int issueCount,
                         List<Issue> issues, long modelDurationMillis) {
        public Report { issues = List.copyOf(issues); }
    }
}
