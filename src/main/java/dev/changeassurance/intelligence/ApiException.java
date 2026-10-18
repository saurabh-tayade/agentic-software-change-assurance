package dev.changeassurance.intelligence;

/** Fixed transport errors; never constructed from exception/provider messages. */
final class ApiException extends RuntimeException {
    final int status;
    final String code;
    ApiException(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }
}
