package dev.changeassurance.retrieval;

public final class RetrievalException extends RuntimeException {
    public RetrievalException(Throwable cause) {
        super("Could not retrieve evidence from PostgreSQL", cause);
    }
}
