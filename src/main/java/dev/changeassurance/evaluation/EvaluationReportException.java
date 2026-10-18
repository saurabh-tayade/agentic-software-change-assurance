package dev.changeassurance.evaluation;

/** Bounded diagnostics: never includes captured source, provider messages, or private review text. */
public final class EvaluationReportException extends RuntimeException {
    public enum Code { INVALID_INPUT, UNSUPPORTED_VERSION, ARTIFACT_MISMATCH, EXECUTION_REQUIRED,
        DUPLICATE_INPUT, INVALID_REVIEW, CONFLICTING_REVIEW, OUTPUT_EXISTS, IO_FAILURE }
    private final Code code;
    public EvaluationReportException(Code code) { super("Evaluation report failed: " + code); this.code = code; }
    public Code code() { return code; }
}
