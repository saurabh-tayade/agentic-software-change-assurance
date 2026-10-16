package dev.changeassurance.processing;

/** Expected unsupported input or parser failure, reported per artifact. */
final class SourceParsingException extends RuntimeException {
    private final ProcessingResult.Reason reason;

    SourceParsingException(ProcessingResult.Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    ProcessingResult.Reason reason() {
        return reason;
    }
}
