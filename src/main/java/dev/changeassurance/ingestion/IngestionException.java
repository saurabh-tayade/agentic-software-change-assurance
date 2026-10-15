package dev.changeassurance.ingestion;

/** Snapshot-level failure: callers must not interpret it as an empty successful inventory. */
public final class IngestionException extends RuntimeException {
    public IngestionException(String message) {
        super(message);
    }

    public IngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
