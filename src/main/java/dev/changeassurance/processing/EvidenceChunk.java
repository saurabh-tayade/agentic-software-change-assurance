package dev.changeassurance.processing;

import dev.changeassurance.ingestion.IngestionResult.Snapshot;

import java.util.List;

/** Content is one continuous original source span, never rendered or enriched text. */
public record EvidenceChunk(String id, Snapshot snapshot, String filePath,
                            String artifactType, String sourceFormat, String gitBlobId,
                            String sourceHash, int startLine, int endLine, String content,
                            String contentHash, List<String> context, String parser,
                            String chunkingConfiguration) {
    public EvidenceChunk {
        if (startLine < 1 || endLine < startLine || content.isEmpty()) {
            throw new IllegalArgumentException("Evidence requires a nonempty original source span");
        }
        context = List.copyOf(context);
    }
}
