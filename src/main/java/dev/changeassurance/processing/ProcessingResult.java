package dev.changeassurance.processing;

import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.IngestionResult.CoverageEntry;
import dev.changeassurance.ingestion.IngestionResult.Snapshot;

import java.util.List;

/** Coverage is carried unchanged from ingestion, including intentional exclusions. */
public record ProcessingResult(Snapshot snapshot, ChunkingConfiguration configuration,
                               Status status, List<ArtifactResult> artifacts,
                               List<EvidenceChunk> chunks, List<CoverageEntry> ingestionCoverage,
                               List<String> ingestionLimitations, IngestionResult.Summary ingestionSummary,
                               List<String> processingLimitations) {
    public ProcessingResult {
        artifacts = List.copyOf(artifacts);
        chunks = List.copyOf(chunks);
        ingestionCoverage = List.copyOf(ingestionCoverage);
        ingestionLimitations = List.copyOf(ingestionLimitations);
        processingLimitations = List.copyOf(processingLimitations);
    }

    public Statistics statistics() {
        return new Statistics(artifacts.size(), (int) artifacts.stream().filter(ArtifactResult::parsed).count(),
                count(Status.COMPLETE), count(Status.PARTIAL), count(Status.FAILED), chunks.size());
    }

    private int count(Status value) {
        return (int) artifacts.stream().filter(a -> a.status() == value).count();
    }

    public enum Status { COMPLETE, PARTIAL, FAILED }
    public enum Reason { PARSE_FAILURE, UNSUPPORTED_CONSTRUCT, RESOURCE_LIMIT, INTEGRITY_FAILURE, SPLIT_ELEMENT }

    /** No source coordinates are guessed for parser failures. */
    public record Limitation(Reason reason, String detail) { }
    public record ArtifactResult(String path, String parser, Status status, boolean parsed,
                                 int chunkCount, List<Limitation> limitations) {
        public ArtifactResult {
            limitations = List.copyOf(limitations);
        }
    }
    public record Statistics(int artifacts, int parsed, int complete, int partial, int failed, int chunks) { }
}
