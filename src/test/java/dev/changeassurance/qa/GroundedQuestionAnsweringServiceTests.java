package dev.changeassurance.qa;

import dev.changeassurance.persistence.PublicationIntegrityException;
import dev.changeassurance.persistence.PublicationMetadata;
import dev.changeassurance.persistence.PublicationSummary;
import dev.changeassurance.processing.ProcessingResult;
import dev.changeassurance.retrieval.KeywordRetrievalService;
import dev.changeassurance.retrieval.RetrievalException;
import dev.changeassurance.retrieval.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static dev.changeassurance.qa.QaFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class GroundedQuestionAnsweringServiceTests {
    private KeywordRetrievalService retrieval;
    private Gateway gateway;
    private GroundedQuestionAnsweringService service;
    private final AnsweringConfiguration options = AnsweringConfiguration.defaults();
    private final RetrievalResult evidence = retrieval("The policy returns 800 cents.\n");

    @BeforeEach void setUp() {
        retrieval = mock(KeywordRetrievalService.class);
        gateway = new Gateway();
        service = new GroundedQuestionAnsweringService(retrieval, gateway);
        when(retrieval.search(PUBLICATION, evidence.query(), options.topK())).thenReturn(Optional.of(evidence));
    }

    @Test void invokesRealRetrievalContractAndPreservesExactEvidenceAndDiagnostics() {
        var answer = service.answer(PUBLICATION, evidence.query(), options);
        assertEquals(GroundedAnswer.Status.SUPPORTED, answer.status());
        assertEquals(evidence.publication(), answer.publication());
        assertEquals(evidence.query(), answer.question());
        assertEquals("The policy returns 800 cents.", answer.answer());
        assertEquals(evidence.matches().getFirst().evidence(), answer.citations().getFirst().evidence());
        assertEquals(PUBLICATION, answer.citations().getFirst().publicationId());
        assertEquals("E1", answer.citations().getFirst().key());
        assertEquals(evidence.strategy(), answer.retrievalStrategy());
        assertTrue(answer.retrievalLimitations().containsAll(evidence.limitations()));
        assertEquals(QaPrompt.VERSION, answer.promptVersion());
        assertEquals(QaPrompt.hash(QaPrompt.SYSTEM), answer.promptHash());
        assertEquals(QaPrompt.hash(QaPrompt.SCHEMA), answer.schemaHash());
        assertEquals(gateway.settings(), answer.generationSettings());
        assertEquals(25, answer.invocation().durationMillis());
        assertNull(answer.invocation().inputTokens());
        assertNull(answer.invocation().outputTokens());
        assertEquals(1, gateway.calls);
        assertEquals(QaPrompt.SYSTEM, gateway.system);
        assertEquals(QaPrompt.SCHEMA, gateway.schema);
        assertTrue(gateway.context.contains("The policy returns 800 cents."));
        verify(retrieval).search(PUBLICATION, evidence.query(), options.topK());
        verifyNoMoreInteractions(retrieval);
    }

    @Test void answerIsSerializableForTheDeveloperCliWithoutLosingCitationProvenance() {
        var answer = service.answer(PUBLICATION, evidence.query(), options);
        var json = new JsonMapper().readTree(new JsonMapper().writeValueAsString(answer));
        assertEquals(PUBLICATION, json.get("publication").get("id").asString());
        assertEquals("E1", json.get("citations").get(0).get("key").asString());
        assertEquals(evidence.matches().getFirst().evidence().filePath(),
                json.get("citations").get(0).get("evidence").get("filePath").asString());
        assertEquals(QaPrompt.VERSION, json.get("promptVersion").asString());
    }

    @Test void emptyRetrievalAbstainsDeterministicallyWithoutModelInvocation() {
        when(retrieval.search(PUBLICATION, evidence.query(), options.topK()))
                .thenReturn(Optional.of(withMatches(evidence, List.of())));
        var answer = service.answer(PUBLICATION, evidence.query(), options);
        assertEquals(GroundedAnswer.Status.INSUFFICIENT_EVIDENCE, answer.status());
        assertTrue(answer.claims().isEmpty());
        assertTrue(answer.citations().isEmpty());
        assertFalse(answer.uncertainties().isEmpty());
        assertEquals(evidence.publication(), answer.publication());
        assertEquals(0, gateway.calls);
        assertNull(answer.invocation());
    }

    @Test void preservesIncompletePublicationEvenWhenModelProposesSupportedAnswer() {
        var m = evidence.publication().metadata();
        var partial = new PublicationMetadata(m.sourcePaths(), m.configuration(), ProcessingResult.Status.PARTIAL,
                m.artifacts(), m.ingestionCoverage(), m.ingestionLimitations(), m.ingestionSummary(), List.of("Resource limit"));
        var publication = new PublicationSummary(PUBLICATION, evidence.publication().snapshotId(),
                evidence.publication().derivationId(), evidence.publication().snapshot(), partial);
        var result = new RetrievalResult(publication, evidence.query(), evidence.topK(), evidence.strategy(), evidence.matches(), evidence.limitations());
        when(retrieval.search(PUBLICATION, evidence.query(), options.topK())).thenReturn(Optional.of(result));
        var answer = service.answer(PUBLICATION, evidence.query(), options);
        assertEquals(ProcessingResult.Status.PARTIAL, answer.publication().metadata().status());
        assertEquals(List.of("Resource limit"), answer.publication().metadata().processingLimitations());
    }

    @Test void unknownPublicationIsFailureRatherThanEmptyEvidence() {
        when(retrieval.search(PUBLICATION, evidence.query(), options.topK())).thenReturn(Optional.empty());
        assertCode(QaException.Code.UNKNOWN_PUBLICATION, () -> service.answer(PUBLICATION, evidence.query(), options));
        assertEquals(0, gateway.calls);
    }

    @Test void rejectsInvalidRequestsBeforeRetrieval() {
        for (String question : new String[]{null, "", "  ", "x".repeat(513), "x\0y", "\ud800"}) {
            assertCode(QaException.Code.INVALID_REQUEST, () -> service.answer(PUBLICATION, question, options));
        }
        for (String id : new String[]{null, "", "latest", "g".repeat(64)}) {
            assertCode(QaException.Code.INVALID_REQUEST, () -> service.answer(id, "valid", options));
        }
        assertCode(QaException.Code.INVALID_REQUEST, () -> service.answer(PUBLICATION, "valid", null));
        verifyNoInteractions(retrieval);
        assertEquals(0, gateway.calls);
    }

    @Test void retrievalAndPublicationIntegrityRemainDistinctFailures() {
        when(retrieval.search(PUBLICATION, evidence.query(), options.topK())).thenThrow(new RetrievalException(new IllegalStateException("private server details")));
        var failure = assertCode(QaException.Code.RETRIEVAL_FAILURE, () -> service.answer(PUBLICATION, evidence.query(), options));
        assertFalse(failure.getMessage().contains("private server details"));
        doThrow(new PublicationIntegrityException()).when(retrieval).search(PUBLICATION, evidence.query(), options.topK());
        assertCode(QaException.Code.PUBLICATION_INTEGRITY_FAILURE, () -> service.answer(PUBLICATION, evidence.query(), options));
        assertEquals(0, gateway.calls);
    }

    @Test void refusesDifferentPublicationReturnedByRetrieval() {
        var p = evidence.publication();
        var different = new PublicationSummary("f".repeat(64), p.snapshotId(), p.derivationId(), p.snapshot(), p.metadata());
        when(retrieval.search(PUBLICATION, evidence.query(), options.topK())).thenReturn(Optional.of(
                new RetrievalResult(different, evidence.query(), evidence.topK(), evidence.strategy(), evidence.matches(), evidence.limitations())));
        assertCode(QaException.Code.PUBLICATION_INTEGRITY_FAILURE, () -> service.answer(PUBLICATION, evidence.query(), options));
        assertEquals(0, gateway.calls);
    }

    @Test void modelFailuresDoNotMasqueradeAsInsufficientEvidenceOrRetry() {
        for (var code : List.of(QaException.Code.MODEL_UNAVAILABLE, QaException.Code.MODEL_TIMEOUT)) {
            gateway.failure = new QaException(code);
            assertCode(code, () -> service.answer(PUBLICATION, evidence.query(), options));
        }
        assertEquals(2, gateway.calls);
    }

    @Test void modelMalformedOutputAndInventedCitationsFailClosedWithoutRetry() {
        gateway.output = "This is an uncited answer";
        assertCode(QaException.Code.MALFORMED_MODEL_OUTPUT, () -> service.answer(PUBLICATION, evidence.query(), options));
        gateway.output = VALID.replace("E1", "E8");
        assertCode(QaException.Code.INVALID_CITATION, () -> service.answer(PUBLICATION, evidence.query(), options));
        assertEquals(2, gateway.calls);
    }

    @Test void malformedJsonLogsOneReasonWithoutRawContentByDefault(CapturedOutput output) {
        gateway.output = "not JSON with private text\nnext line";
        var failure = assertCode(QaException.Code.MALFORMED_MODEL_OUTPUT,
                () -> service.answer(PUBLICATION, evidence.query(), options));
        assertEquals(QaException.MalformedReason.INVALID_JSON, failure.malformedReason());
        assertEquals("Question answering failed: MALFORMED_MODEL_OUTPUT", failure.getMessage());
        assertTrue(output.toString().contains("reason=INVALID_JSON"));
        assertTrue(output.toString().contains("rawResponseLengthChars=" + gateway.output.length()));
        assertTrue(output.toString().contains("rawResponseLogged=false"));
        assertFalse(output.toString().contains("private text"));
        assertEquals(1, occurrences(output.toString(), "Q&A model response rejected"));
    }

    @Test void optInLogsEscapedBoundedRawResponseAndStatusCombinationReason(CapturedOutput output) {
        service = new GroundedQuestionAnsweringService(retrieval, gateway, true);
        gateway.output = VALID.replace("SUPPORTED", "INSUFFICIENT_EVIDENCE");
        var failure = assertCode(QaException.Code.MALFORMED_MODEL_OUTPUT,
                () -> service.answer(PUBLICATION, evidence.query(), options));
        assertEquals(QaException.MalformedReason.INVALID_STATUS_CLAIMS_COMBINATION, failure.malformedReason());
        assertTrue(output.toString().contains("rawResponseLogged=true rawResponseTruncated=false"));
        assertEquals(1, occurrences(output.toString(), "Q&A model response rejected"));

        gateway.output = "untrusted\n\u001b[31m";
        assertCode(QaException.Code.MALFORMED_MODEL_OUTPUT,
                () -> service.answer(PUBLICATION, evidence.query(), options));
        assertTrue(output.toString().contains("\\n\\u001B"));
        assertFalse(output.toString().contains("\u001b"));
        assertEquals(2, occurrences(output.toString(), "Q&A model response rejected"));

        gateway.output = "x".repeat(25000);
        assertCode(QaException.Code.MALFORMED_MODEL_OUTPUT,
                () -> service.answer(PUBLICATION, evidence.query(), options));
        assertTrue(output.toString().contains("reason=RESPONSE_SIZE_LIMIT_VIOLATION"));
        assertTrue(output.toString().contains("rawResponseLengthBytes=25000"));
        assertTrue(output.toString().contains("rawResponseTruncated=true"));
        assertFalse(output.toString().contains("x".repeat(8193)));
        assertEquals(3, occurrences(output.toString(), "Q&A model response rejected"));
    }

    @Test void gatewayFailureBeforeRawResponseIsNotMisreportedAsCaptured(CapturedOutput output) {
        gateway.failure = QaException.malformed(QaException.MalformedReason.NO_MODEL_RESPONSE);
        assertCode(QaException.Code.MALFORMED_MODEL_OUTPUT,
                () -> service.answer(PUBLICATION, evidence.query(), options));
        assertTrue(output.toString().contains("reason=NO_MODEL_RESPONSE rawResponseAvailable=false"));
        assertFalse(output.toString().contains("rawResponseLogged=true"));
        assertEquals(1, occurrences(output.toString(), "Q&A model response rejected"));
    }

    @Test void validAnswersAndInvalidCitationsProduceNoMalformedResponseLog(CapturedOutput output) {
        assertEquals(GroundedAnswer.Status.SUPPORTED, service.answer(PUBLICATION, evidence.query(), options).status());
        gateway.output = VALID.replace("E1", "E8");
        assertCode(QaException.Code.INVALID_CITATION,
                () -> service.answer(PUBLICATION, evidence.query(), options));
        assertEquals(0, occurrences(output.toString(), "Q&A model response rejected"));
    }

    private static int occurrences(String value, String fragment) {
        return (value.length() - value.replace(fragment, "").length()) / fragment.length();
    }

    static QaException assertCode(QaException.Code code, org.junit.jupiter.api.function.Executable operation) {
        var failure = assertThrows(QaException.class, operation);
        assertEquals(code, failure.code());
        return failure;
    }
}
