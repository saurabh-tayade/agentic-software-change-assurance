package dev.changeassurance.retrieval;

import dev.changeassurance.persistence.PublicationSummary;
import dev.changeassurance.processing.EvidenceChunk;

import java.util.List;

/** Scores rank evidence; they are not probabilities or supported conclusions. */
public record RetrievalResult(PublicationSummary publication, String query, int topK, String strategy,
                              List<Match> matches, List<String> limitations) {
    public RetrievalResult {
        matches = List.copyOf(matches);
        limitations = List.copyOf(limitations);
    }

    public int resultCount() { return matches.size(); }

    public record Match(int rank, double score, EvidenceChunk evidence) { }
}
