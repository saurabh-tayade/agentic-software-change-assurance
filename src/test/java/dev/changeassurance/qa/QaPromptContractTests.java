package dev.changeassurance.qa;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class QaPromptContractTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void promptAndSchemaAgreeOnInsufficientEvidenceAndSupportedStatuses() {
        var schema = JSON.readTree(QaPrompt.SCHEMA).get("properties");
        assertTrue(QaPrompt.SYSTEM.contains("INSUFFICIENT_EVIDENCE requires claims=[] and at least one uncertainty"));
        assertTrue(QaPrompt.SYSTEM.contains("missing evidence in uncertainties, never in cited claims"));
        assertTrue(QaPrompt.SYSTEM.contains("does not prove absence across the repository"));
        assertTrue(schema.get("status").get("description").asString().contains("INSUFFICIENT_EVIDENCE requires no claims"));
        assertTrue(schema.get("claims").get("description").asString().contains("this must be empty"));
        assertTrue(schema.get("uncertainties").get("description").asString().contains("at least one explanation"));
        assertTrue(schema.get("status").get("description").asString().contains("PARTIALLY_SUPPORTED requires cited claims"));
        assertTrue(schema.get("status").get("description").asString().contains("SUPPORTED requires cited claims"));
    }

    @Test void negativeQuestionExampleUsesTheValidatorContract() {
        int start = QaPrompt.SYSTEM.indexOf("return {\"status\":\"INSUFFICIENT_EVIDENCE\"");
        assertTrue(start >= 0);
        int end = QaPrompt.SYSTEM.indexOf('}', start);
        String example = QaPrompt.SYSTEM.substring(start + "return ".length(), end + 1);
        var context = new EvidenceContextBuilder().build(QaFixtures.retrieval("Unrelated source content.\n"),
                AnsweringConfiguration.defaults());
        var accepted = new ProposedAnswerValidator().validate(example, context);
        assertEquals(GroundedAnswer.Status.INSUFFICIENT_EVIDENCE, accepted.status());
        assertTrue(accepted.claims().isEmpty());
        assertTrue(accepted.citations().isEmpty());
        assertFalse(accepted.uncertainties().isEmpty());
    }

    @Test void numericalFactsPreserveSourceUnitsAndUncertaintiesDoNotInventConclusions() {
        assertEquals("repository-qa-v2", QaPrompt.VERSION);
        assertTrue(QaPrompt.SYSTEM.contains("establish its unit from the quoted source and nearby source context"));
        assertTrue(QaPrompt.SYSTEM.contains("a bare numeric literal does not establish a unit"));
        assertTrue(QaPrompt.SYSTEM.contains("Preserve the source quantity and unit in each claim"));
        assertTrue(QaPrompt.SYSTEM.contains("calculate the conversion correctly"));
        assertTrue(QaPrompt.SYSTEM.contains("Never relabel a source value with a different unit"));
        assertTrue(QaPrompt.SYSTEM.contains("state that limitation rather than inventing a numerical conclusion"));
        assertTrue(QaPrompt.SYSTEM.contains("without adding unsupported factual conclusions"));
    }
}
