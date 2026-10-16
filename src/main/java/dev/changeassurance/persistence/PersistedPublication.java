package dev.changeassurance.persistence;

import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.processing.ProcessingResult;

/** One committed publication and its exact original ingestion/processing contracts. */
public record PersistedPublication(String id, String snapshotId, String derivationId,
                                   IngestionResult ingestion, ProcessingResult processing) { }
