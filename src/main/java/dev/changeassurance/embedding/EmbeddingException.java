package dev.changeassurance.embedding;

/** Safe failure categories; provider bodies and source text are never public error messages. */
public final class EmbeddingException extends RuntimeException {
    public enum Code {
        UNKNOWN_PUBLICATION, PROFILE_MISMATCH, OVERSIZED_INPUT, INVALID_VECTOR,
        MODEL_UNAVAILABLE, MODEL_TIMEOUT, MODEL_INPUT_REJECTED, INVALID_PROVIDER_RESPONSE,
        INDEX_INTEGRITY_FAILURE, PERSISTENCE_FAILURE
    }
    private final Code code;
    public EmbeddingException(Code code) { super("Embedding indexing failed: " + code); this.code = code; }
    public Code code() { return code; }
}
