package dev.changeassurance.retrieval;

/** Retrieval-specific safe failures; index coverage is not a model confidence measure. */
public final class VectorRetrievalException extends RuntimeException {
    public enum Code { INDEX_INCOMPLETE, DATABASE_FAILURE }
    public record Coverage(String publicationId, String profileId, int totalChunks, int indexedChunks, int missingChunks) { }
    private final Code code;
    private final Coverage coverage;
    public VectorRetrievalException(Code code, Coverage coverage) {
        super("Vector retrieval failed: " + code);
        this.code = code; this.coverage = coverage;
    }
    public Code code() { return code; }
    public Coverage coverage() { return coverage; }
}
