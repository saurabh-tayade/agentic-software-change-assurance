package dev.changeassurance.persistence;

import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.processing.ChunkingConfiguration;
import dev.changeassurance.processing.ProcessingResult;

import java.util.List;

/** JSONB stores bounded nested diagnostics, never source/chunk text or replacement provenance. */
record PublicationMetadata(List<String> sourcePaths, ChunkingConfiguration configuration,
                           ProcessingResult.Status status, List<ProcessingResult.ArtifactResult> artifacts,
                           List<IngestionResult.CoverageEntry> ingestionCoverage,
                           List<String> ingestionLimitations, IngestionResult.Summary ingestionSummary,
                           List<String> processingLimitations) {
    static PublicationMetadata from(IngestionResult ingestion, ProcessingResult processing) {
        return new PublicationMetadata(ingestion.artifacts().stream().map(IngestionResult.SourceArtifact::path).toList(),
                processing.configuration(), processing.status(), processing.artifacts(), processing.ingestionCoverage(),
                processing.ingestionLimitations(), processing.ingestionSummary(), processing.processingLimitations());
    }
}
