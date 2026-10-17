package dev.changeassurance.qa;

/** System failures are separate from an answer's model-proposed support status. */
public final class QaException extends RuntimeException {
    public enum Code {
        INVALID_REQUEST, UNKNOWN_PUBLICATION, RETRIEVAL_FAILURE, PUBLICATION_INTEGRITY_FAILURE,
        MODEL_UNAVAILABLE, MODEL_TIMEOUT, MALFORMED_MODEL_OUTPUT, INVALID_CITATION, CONTEXT_BUDGET_FAILURE
    }
    public enum MalformedReason {
        INVALID_JSON, UNEXPECTED_OR_MISSING_FIELDS, INVALID_STATUS,
        INVALID_CLAIM_STRUCTURE, INVALID_UNCERTAINTY_STRUCTURE,
        INVALID_STATUS_CLAIMS_COMBINATION, TEXT_LIMIT_VIOLATION,
        RESPONSE_SIZE_LIMIT_VIOLATION, NO_MODEL_RESPONSE, INVALID_PROVIDER_RESPONSE
    }
    private final Code code;
    private final MalformedReason malformedReason;
    public QaException(Code code) { this(code, null); }
    public QaException(Code code, Throwable cause) {
        super("Question answering failed: " + code.name(), cause);
        this.code = code;
        this.malformedReason = null;
    }
    private QaException(MalformedReason reason, Throwable cause) {
        super("Question answering failed: " + Code.MALFORMED_MODEL_OUTPUT.name(), cause);
        this.code = Code.MALFORMED_MODEL_OUTPUT;
        this.malformedReason = reason;
    }
    public static QaException malformed(MalformedReason reason) { return new QaException(reason, null); }
    public static QaException malformed(MalformedReason reason, Throwable cause) { return new QaException(reason, cause); }
    public Code code() { return code; }
    public MalformedReason malformedReason() { return malformedReason; }
}
