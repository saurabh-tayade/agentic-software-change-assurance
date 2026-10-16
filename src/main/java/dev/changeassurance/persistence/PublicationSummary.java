package dev.changeassurance.persistence;

import dev.changeassurance.ingestion.IngestionResult.Snapshot;

/** Publication identity and complete coverage, without loading source or chunk bodies. */
public record PublicationSummary(String id, String snapshotId, String derivationId,
                                 Snapshot snapshot, PublicationMetadata metadata) { }
