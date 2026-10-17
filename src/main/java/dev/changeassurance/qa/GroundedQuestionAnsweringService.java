package dev.changeassurance.qa;

import dev.changeassurance.persistence.PublicationIntegrity;
import dev.changeassurance.persistence.PublicationIntegrityException;
import dev.changeassurance.processing.ProcessingResult;
import dev.changeassurance.retrieval.KeywordRetrievalService;
import dev.changeassurance.retrieval.EvidenceRetriever;
import dev.changeassurance.retrieval.VectorRetrievalException;
import dev.changeassurance.embedding.EmbeddingException;
import dev.changeassurance.retrieval.RetrievalException;
import dev.changeassurance.retrieval.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One explicit publication, one retrieval, at most one model call, fail-closed validation. */
public final class GroundedQuestionAnsweringService {
    public static final String VERSION = "grounded-answer-v1";
    private static final Logger LOG = LoggerFactory.getLogger(GroundedQuestionAnsweringService.class);
    private static final int MAX_LOGGED_RESPONSE_BYTES = 8192;
    private final EvidenceRetriever retrieval;
    private final ModelGateway model;
    private final boolean logMalformedModelResponse;
    private final EvidenceContextBuilder contexts = new EvidenceContextBuilder();
    private final ProposedAnswerValidator validator = new ProposedAnswerValidator();

    public GroundedQuestionAnsweringService(EvidenceRetriever retrieval, ModelGateway model) {
        this(retrieval, model, false);
    }

    public GroundedQuestionAnsweringService(EvidenceRetriever retrieval, ModelGateway model,
                                            boolean logMalformedModelResponse) {
        this.retrieval = Objects.requireNonNull(retrieval); this.model = Objects.requireNonNull(model);
        this.logMalformedModelResponse = logMalformedModelResponse;
    }

    public GroundedAnswer answer(String publicationId, String question, AnsweringConfiguration configuration) {
        return answerWithRetrieval(publicationId, question, configuration).answer();
    }

    /** CLI trace from the same invocation, without repeating retrieval or changing GroundedAnswer. */
    public record AnswerRun(GroundedAnswer answer, RetrievalResult retrieval) { }

    public AnswerRun answerWithRetrieval(String publicationId, String question, AnsweringConfiguration configuration) {
        long start = System.nanoTime();
        try { PublicationIntegrity.validateId(publicationId); }
        catch (IllegalArgumentException exception) { throw new QaException(QaException.Code.INVALID_REQUEST); }
        if (configuration == null || question == null || question.isBlank() || question.length() > KeywordRetrievalService.MAX_QUERY_LENGTH
                || question.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(question)) {
            throw new QaException(QaException.Code.INVALID_REQUEST);
        }
        RetrievalResult result;
        EvidenceContextBuilder.Context context;
        try {
            result = retrieval.search(publicationId, question, configuration.topK())
                    .orElseThrow(() -> new QaException(QaException.Code.UNKNOWN_PUBLICATION));
            if (!result.publication().id().equals(publicationId) || !result.query().equals(question)
                    || result.topK() != configuration.topK() || result.matches().size() > configuration.topK()) {
                throw new QaException(QaException.Code.PUBLICATION_INTEGRITY_FAILURE);
            }
            context = contexts.build(result, configuration);
        } catch (PublicationIntegrityException exception) {
            throw new QaException(QaException.Code.PUBLICATION_INTEGRITY_FAILURE, exception);
        } catch (EmbeddingException exception) {
            throw QaException.embeddingFailure(exception);
        } catch (VectorRetrievalException exception) {
            throw new QaException(exception.code() == VectorRetrievalException.Code.INDEX_INCOMPLETE
                    ? QaException.Code.EMBEDDING_INDEX_INCOMPLETE : QaException.Code.RETRIEVAL_FAILURE, exception);
        } catch (RetrievalException exception) {
            throw new QaException(QaException.Code.RETRIEVAL_FAILURE, exception);
        }
        ProposedAnswerValidator.Validated validated;
        ModelGateway.Diagnostics invocation = null;
        if (result.matches().isEmpty()) {
            validated = new ProposedAnswerValidator.Validated(GroundedAnswer.Status.INSUFFICIENT_EVIDENCE,
                    List.of(), List.of(), List.of((KeywordRetrievalService.STRATEGY.equals(result.strategy())
                            ? "No keyword matches were retrieved." : "No evidence candidates were retrieved.")
                            + " This does not prove feature absence."),
                    "No matching evidence was retrieved for this question.");
        } else {
            if (context.entries().isEmpty()) throw new QaException(QaException.Code.CONTEXT_BUDGET_FAILURE);
            ModelGateway.Response response = null;
            try {
                response = model.generate(QaPrompt.SYSTEM, context.json(), QaPrompt.SCHEMA);
                if (response == null) throw QaException.malformed(QaException.MalformedReason.NO_MODEL_RESPONSE);
                validated = validator.validate(response.json(), context);
                invocation = response.diagnostics();
            } catch (QaException failure) {
                if (failure.code() == QaException.Code.MALFORMED_MODEL_OUTPUT)
                    logMalformedResponse(failure, response == null ? null : response.json());
                throw failure;
            }
        }
        var uncertainties = new ArrayList<>(validated.uncertainties());
        if (result.publication().metadata().status() != ProcessingResult.Status.COMPLETE) {
            uncertainties.add("Publication processing is " + result.publication().metadata().status() + "; repository evidence is incomplete.");
        }
        if (!context.selection().omissions().isEmpty()) uncertainties.add("Some retrieved evidence was omitted from the model context; inspect contextSelection.");
        return new AnswerRun(new GroundedAnswer(VERSION, result.publication(), question, validated.answer(), validated.status(),
                validated.claims(), validated.citations(), uncertainties, result.strategy(), result.limitations(),
                context.selection(), configuration, QaPrompt.VERSION, QaPrompt.hash(QaPrompt.SYSTEM), QaPrompt.hash(QaPrompt.SCHEMA),
                model.settings(), invocation, "CITATION_IDENTITIES_VALIDATED_SEMANTIC_SUPPORT_NOT_VERIFIED",
                (System.nanoTime() - start) / 1_000_000), result);
    }

    private void logMalformedResponse(QaException failure, String raw) {
        String reason = failure.malformedReason() == null ? "INVALID_PROVIDER_RESPONSE" : failure.malformedReason().name();
        if (raw == null) {
            LOG.warn("Q&A model response rejected: category=MALFORMED_MODEL_OUTPUT reason={} rawResponseAvailable=false", reason);
            return;
        }
        int lengthBytes = raw.getBytes(StandardCharsets.UTF_8).length;
        if (!logMalformedModelResponse) {
            LOG.warn("Q&A model response rejected: category=MALFORMED_MODEL_OUTPUT reason={} rawResponseAvailable=true "
                    + "rawResponseLengthChars={} rawResponseLengthBytes={} rawResponseLogged=false",
                    reason, raw.length(), lengthBytes);
            return;
        }
        StringBuilder escaped = new StringBuilder();
        int loggedBytes = 0;
        int index = 0;
        while (index < raw.length()) {
            int codePoint = raw.codePointAt(index);
            String piece = switch (codePoint) {
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                case '"' -> "\\\"";
                case '\\' -> "\\\\";
                default -> Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029
                        || (codePoint >= 0xD800 && codePoint <= 0xDFFF)
                        ? "\\u" + String.format("%04X", codePoint) : new String(Character.toChars(codePoint));
            };
            int pieceBytes = piece.getBytes(StandardCharsets.UTF_8).length;
            if (loggedBytes + pieceBytes > MAX_LOGGED_RESPONSE_BYTES) break;
            escaped.append(piece);
            loggedBytes += pieceBytes;
            index += Character.charCount(codePoint);
        }
        LOG.warn("Q&A model response rejected: category=MALFORMED_MODEL_OUTPUT reason={} rawResponseAvailable=true "
                + "rawResponseLengthChars={} rawResponseLengthBytes={} rawResponseLogged=true rawResponseTruncated={} "
                + "rawResponse=\"{}\"", reason, raw.length(), lengthBytes, index < raw.length(), escaped);
    }
}
