package dev.changeassurance.qa;

import dev.changeassurance.persistence.PublicationSummary;
import dev.changeassurance.processing.EvidenceChunk;

import java.util.List;

/** Citation validity is deterministic; model-proposed status/claims are NOT semantic proof. */
public record GroundedAnswer(String version, PublicationSummary publication, String question, String answer,
                             Status status, List<Claim> claims, List<Citation> citations,
                             List<String> uncertainties, String retrievalStrategy, List<String> retrievalLimitations,
                             EvidenceContextBuilder.Selection contextSelection, AnsweringConfiguration configuration,
                             String promptVersion, String promptHash, String schemaHash,
                             GenerationSettings generationSettings, ModelGateway.Diagnostics invocation,
                             String validation, long durationMillis) {
    public GroundedAnswer {
        claims = List.copyOf(claims); citations = List.copyOf(citations);
        uncertainties = List.copyOf(uncertainties); retrievalLimitations = List.copyOf(retrievalLimitations);
    }
    public enum Status { SUPPORTED, PARTIALLY_SUPPORTED, INSUFFICIENT_EVIDENCE }
    public record Claim(String text, List<String> evidenceKeys) {
        public Claim { evidenceKeys = List.copyOf(evidenceKeys); }
    }
    public record Citation(String key, String publicationId, EvidenceChunk evidence) { }
}
