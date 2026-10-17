package dev.changeassurance.evaluation;

import dev.changeassurance.qa.AnsweringConfiguration;
import dev.changeassurance.qa.EvidenceContextBuilder;
import dev.changeassurance.qa.GenerationSettings;
import dev.changeassurance.qa.GroundedAnswer;
import dev.changeassurance.qa.GroundedQuestionAnsweringService;
import dev.changeassurance.qa.QaPrompt;
import dev.changeassurance.retrieval.KeywordRetrievalService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Fixture integrity checks, not generated-answer quality measurements. */
class QaDefinitionTests {
    private static final JsonMapper JSON = new JsonMapper();
    private static final Path CORPUS = Path.of("evaluation/corpora/parcel-quotes/v1");

    @Test void importsTheUnchangedSevenScenariosAndVerifiableOriginalReferences() throws Exception {
        var definition = definition();
        assertEquals(1, definition.get("schema_version").asInt());
        assertEquals("qa-v2", definition.get("version").asString());
        assertEquals("parcel-quotes/v1", definition.get("corpus").asString());
        assertEquals(definition.get("corpus_manifest_sha256").asString(), sha(CORPUS.resolve("manifest.json")));
        var imported = definition.get("original_scenarios");
        var original = verifiedImport(imported);
        assertEquals(7, original.get("scenarios").size());
        assertEquals(ids(original.get("scenarios")), strings(imported.get("ids")));
        assertEquals(List.of("RI-001", "RI-002", "RI-003", "RI-004", "RI-005", "RI-006", "RI-007"), strings(imported.get("ids")));
        for (var scenario : original.get("scenarios")) {
            assertFalse(scenario.get("question").asString().isBlank());
            assertTrue(scenario.get("expected_findings").size() > 0);
            assertEquals("parcel-quotes", scenario.get("corpus_id").asString());
            assertEquals("v1", scenario.get("corpus_version").asString());
            var mapped = definition.get("answerability_mapping").get(scenario.get("expected_answerability").asString());
            assertNotNull(mapped);
            assertDoesNotThrow(() -> GroundedAnswer.Status.valueOf(mapped.asString()));
            for (var reference : scenario.get("expected_evidence")) {
                assertEquals("parcel-quotes", reference.get("corpus_id").asString());
                assertEquals("v1", reference.get("corpus_version").asString());
                Path source = CORPUS.resolve(reference.get("path").asString()).normalize();
                assertTrue(source.startsWith(CORPUS));
                var lines = Files.readAllLines(source);
                int start = reference.get("start_line").asInt(), end = reference.get("end_line").asInt();
                assertTrue(start > 0 && end >= start && end <= lines.size());
                assertTrue(String.join("\n", lines.subList(start - 1, end)).contains(reference.get("contains").asString()));
            }
        }
    }

    @Test void keepsTheExistingKafkaCaseAsDeterministicNonInvocation() throws Exception {
        var definition = definition();
        var imported = definition.get("retrieval_scenarios");
        var retrieval = verifiedImport(imported);
        assertEquals(List.of("KW-006"), strings(imported.get("ids")));
        var kafka = find(retrieval.get("scenarios"), "KW-006");
        assertEquals("Kafka broker", kafka.get("query").asString());
        assertTrue(kafka.get("expected_evidence").isEmpty());
        var expectation = definition.get("zero_match_case");
        assertEquals(kafka.get("id"), expectation.get("id"));
        assertEquals(0, expectation.get("expected_matches").asInt());
        assertEquals("INSUFFICIENT_EVIDENCE", expectation.get("expected_status").asString());
        assertFalse(expectation.get("expected_model_invoked").asBoolean());
        assertFalse(expectation.get("forbidden_conclusion").asString().isBlank());
    }

    @Test void definesOneAdversarialQuestionVariantWithoutReplacingGroundTruth() throws Exception {
        var definition = definition();
        var original = verifiedImport(definition.get("original_scenarios"));
        Set<String> allIds = new HashSet<>(strings(definition.get("original_scenarios").get("ids")));
        allIds.addAll(strings(definition.get("retrieval_scenarios").get("ids")));
        assertEquals(1, definition.get("question_variants").size());
        for (var variant : definition.get("question_variants")) {
            assertTrue(allIds.add(variant.get("id").asString()));
            var source = find(original.get("scenarios"), variant.get("source_scenario").asString());
            assertEquals("RI-003", source.get("id").asString());
            assertFalse(variant.has("expected_evidence"));
            assertFalse(variant.has("expected_findings"));
            assertFalse(variant.has("expected_answerability"));
            String question = source.get("question").asString() + variant.get("question_suffix").asString();
            assertTrue(question.startsWith(source.get("question").asString()));
            assertTrue(question.contains("E999"));
            assertTrue(question.contains("without citations"));
            assertFalse(variant.get("expected_behavior").asString().isBlank());
        }
        assertEquals(9, allIds.size());
    }

    @Test void pinsCurrentPromptRetrievalAndConservativeDefaultSettings() throws Exception {
        var definition = definition();
        assertEquals(KeywordRetrievalService.STRATEGY, definition.get("retrieval_strategy").asString());
        assertEquals(QaPrompt.VERSION, definition.get("prompt_version").asString());
        assertEquals(GroundedQuestionAnsweringService.VERSION, definition.get("answer_contract_version").asString());
        assertEquals(EvidenceContextBuilder.POLICY, definition.get("context_policy").asString());
        assertEquals(QaPrompt.SCHEMA, Files.readString(Path.of(definition.get("proposed_answer_schema").asString())));
        var configuration = definition.get("answering");
        assertEquals(AnsweringConfiguration.defaults(), new AnsweringConfiguration(
                configuration.get("top_k").asInt(), configuration.get("max_evidence").asInt(),
                configuration.get("max_context_bytes").asInt(), configuration.get("max_evidence_bytes").asInt()));
        var generation = definition.get("generation");
        assertEquals(GenerationSettings.defaults(), new GenerationSettings(generation.get("model").asString(),
                generation.get("temperature").asDouble(), generation.get("context_tokens").asInt(),
                generation.get("max_output_tokens").asInt(), generation.get("timeout_seconds").asInt()));
        var retrieval = verifiedImport(definition.get("retrieval_scenarios"));
        assertEquals(retrieval.get("max_lines"), definition.get("processing").get("max_lines"));
        assertEquals(retrieval.get("max_chunks"), definition.get("processing").get("max_chunks"));
        assertEquals(Set.of("retrieval", "citation_validity", "answerability", "semantic_support",
                "answer_completeness", "model_reliability", "latency_and_usage"), new HashSet<>(strings(definition.get("dimensions"))));
    }

    @Test void preservesThePriorEvaluationAndPromptVersion() throws Exception {
        var prior = JSON.readTree(Files.readString(Path.of("evaluation/qa/qa-v1.json")));
        var current = definition();
        assertEquals("qa-v1", prior.get("version").asString());
        assertEquals("repository-qa-v1", prior.get("prompt_version").asString());
        assertTrue(Files.readString(Path.of("src/main/resources/qa/repository-qa-v1.txt"))
                .contains("Distinguish implementation, test definitions"));
        for (String name : prior.propertyNames()) {
            if (!name.equals("version") && !name.equals("prompt_version")) {
                assertEquals(prior.get(name), current.get(name), "Evaluation ground truth changed: " + name);
            }
        }
    }

    private static JsonNode definition() throws Exception {
        return JSON.readTree(Files.readString(Path.of("evaluation/qa/qa-v2.json")));
    }

    private static JsonNode verifiedImport(JsonNode imported) throws Exception {
        Path path = Path.of(imported.get("path").asString());
        assertEquals(imported.get("sha256").asString(), sha(path), "Imported evaluation source changed");
        return JSON.readTree(Files.readString(path));
    }

    private static JsonNode find(JsonNode scenarios, String id) {
        var matches = new ArrayList<JsonNode>();
        scenarios.forEach(s -> { if (s.get("id").asString().equals(id)) matches.add(s); });
        assertEquals(1, matches.size(), "Expected one imported scenario " + id);
        return matches.getFirst();
    }

    private static List<String> ids(JsonNode scenarios) {
        var ids = new ArrayList<String>();
        scenarios.forEach(s -> ids.add(s.get("id").asString()));
        assertEquals(ids.size(), new HashSet<>(ids).size());
        return ids;
    }

    private static List<String> strings(JsonNode values) {
        var result = new ArrayList<String>();
        values.forEach(value -> result.add(value.asString()));
        return result;
    }

    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
