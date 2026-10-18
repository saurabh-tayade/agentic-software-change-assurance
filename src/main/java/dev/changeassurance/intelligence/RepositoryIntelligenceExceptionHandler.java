package dev.changeassurance.intelligence;

import dev.changeassurance.persistence.EvidencePersistenceException;
import dev.changeassurance.persistence.PublicationIntegrityException;
import dev.changeassurance.qa.QaException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Profile("intelligence-api")
public final class RepositoryIntelligenceExceptionHandler {
    public record ProblemResponse(String type, String title, int status, String code, String detail) { }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemResponse> handle(Exception failure) {
        int status;
        String code;
        String detail;
        if (failure instanceof ApiException api) {
            status = api.status; code = api.code; detail = api.getMessage();
        } else if (failure instanceof QaException qa) {
            status = switch (qa.code()) {
                case INVALID_REQUEST -> 400;
                case UNKNOWN_PUBLICATION -> 404;
                case EMBEDDING_PROFILE_MISMATCH, EMBEDDING_INDEX_INCOMPLETE -> 409;
                case CONTEXT_BUDGET_FAILURE, EMBEDDING_INPUT_REJECTED -> 422;
                case MALFORMED_MODEL_OUTPUT, INVALID_CITATION, EMBEDDING_INVALID_RESPONSE, EMBEDDING_INVALID_VECTOR -> 502;
                case MODEL_UNAVAILABLE, EMBEDDING_MODEL_UNAVAILABLE, RETRIEVAL_FAILURE, RETRIEVAL_NOT_CONFIGURED -> 503;
                case MODEL_TIMEOUT, EMBEDDING_MODEL_TIMEOUT -> 504;
                case PUBLICATION_INTEGRITY_FAILURE, EMBEDDING_INDEX_INTEGRITY_FAILURE -> 500;
            };
            code = qa.code().name(); detail = description(status);
        } else if (failure instanceof PublicationIntegrityException) {
            status = 500; code = "PUBLICATION_INTEGRITY_FAILURE"; detail = description(status);
        } else if (failure instanceof EvidencePersistenceException) {
            status = 503; code = "RETRIEVAL_FAILURE"; detail = description(status);
        } else if (failure instanceof IllegalArgumentException || failure instanceof HttpMessageNotReadableException
                || failure instanceof MethodArgumentTypeMismatchException) {
            status = 400; code = "INVALID_REQUEST"; detail = description(status);
        } else if (failure instanceof ErrorResponse error) {
            status = error.getStatusCode().value();
            code = switch (status) {
                case 400 -> "INVALID_REQUEST";
                case 404 -> "NOT_FOUND";
                case 405 -> "METHOD_NOT_ALLOWED";
                case 406 -> "NOT_ACCEPTABLE";
                case 413 -> "REQUEST_TOO_LARGE";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                default -> "HTTP_ERROR";
            };
            detail = description(status);
        } else {
            status = 500; code = "INTERNAL_ERROR"; detail = description(status);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Cache-Control", "no-store").body(problem(status, code, detail));
    }

    static ProblemResponse problem(int status, String code, String detail) {
        return new ProblemResponse("about:blank", HttpStatus.valueOf(status).getReasonPhrase(), status, code, detail);
    }

    private static String description(int status) {
        return switch (status) {
            case 400 -> "The request is invalid.";
            case 404 -> "The requested resource was not found.";
            case 405 -> "The HTTP method is not supported.";
            case 406 -> "The requested response format is not supported.";
            case 409 -> "The selected embedding profile or publication index is incompatible or incomplete.";
            case 413 -> "The request body exceeds the allowed size.";
            case 415 -> "The request content type is not supported.";
            case 422 -> "The input cannot be processed within the configured limits.";
            case 502 -> "The model response failed validation.";
            case 503 -> "A required service is unavailable or not configured.";
            case 504 -> "A model operation timed out.";
            default -> "The operation failed. No answer or unverified evidence is returned.";
        };
    }
}
