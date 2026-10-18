package dev.changeassurance.evaluation;

import dev.changeassurance.persistence.*;
import dev.changeassurance.processing.EvidenceChunk;
import dev.changeassurance.qa.*;
import dev.changeassurance.retrieval.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static dev.changeassurance.evaluation.EvaluationFixtures.*;
import static dev.changeassurance.evaluation.QaEvaluationArtifacts.Check.*;
import static org.junit.jupiter.api.Assertions.*;

class QaEvaluationTests {
    @TempDir Path output;
    static final String WRONG_UNITS = "{\"status\":\"SUPPORTED\",\"claims\":[{\"text\":\"The method adds a $300 surcharge.\",\"evidenceKeys\":[\"E1\"]}],\"uncertainties\":[]}";
    static final String ABSTAIN = "{\"status\":\"INSUFFICIENT_EVIDENCE\",\"claims\":[],\"uncertainties\":[\"The selected evidence does not establish the requested fact.\"]}";
    static QaComparison definitionQa() throws Exception {
        return QaComparison.load(Path.of("."), Path.of("evaluation/qa/qa-comparison-v1.json"));
    }
    static ModelGateway model(String json) {
        return new ModelGateway() {
            public GenerationSettings settings() { return GenerationSettings.defaults(); }
            public Response generate(String prompt, String context, String schema) {
                return new Response(json, new Diagnostics("test-double", settings().model(), 1, null, null, "stop"));
            }
        };
    }
    static PersistedPublication publication(QaComparison d) throws Exception { return publications(d.retrievalDefinition()).get("full"); }
    static EvidenceChunk pricing(PersistedPublication p) {
        return p.processing().chunks().stream().filter(c -> c.filePath().endsWith("QuotePolicy.java") && c.content().contains("return base")).findFirst().orElseThrow();
    }
    static RetrievalResult resultQa(PersistedPublication p, String query, int k, RetrievalStrategy s) {
        var result = result(p, query, k, s);
        return new RetrievalResult(result.publication(), query, k, result.strategy(), List.of(new RetrievalResult.Match(1, 1, pricing(p))), result.limitations());
    }
    static QaEvaluationRun runQa(String json) throws Exception {
        var d = definitionQa(); var p = publication(d);
        return new QaEvaluationRunner(id -> Optional.of(p), s -> (id, q, k) -> Optional.of(resultQa(p, q, k, s)), model(json))
                .execute(d, List.of("QA-REG-UNITS"), p.id(), PROFILE.id(), PROFILE, null, "TEST_DOUBLE", false);
    }
    static QaEvaluationRun mutate(QaEvaluationRun run, Consumer<ObjectNode> change) {
        var node = (ObjectNode) RetrievalComparison.JSON.valueToTree(run); change.accept(node);
        return RetrievalComparison.JSON.treeToValue(node, QaEvaluationRun.class);
    }
    static ObjectNode first(ObjectNode n) { return (ObjectNode) n.path("executions").get(0); }

    @Test void importsPinnedGroundTruthAndMarksRegressionsAsDevelopment() throws Exception {
        var d = definitionQa(); assertEquals(11, d.scenarios().size()); assertEquals(3, d.strategies().size());
        var prior = QaComparison.reference(d.retrievalDefinition(), "RI-002");
        for (var s : d.scenarios().stream().filter(s -> s.id().startsWith("QA-REG")).toList()) {
            assertEquals("DEVELOPMENT_REGRESSION", s.classification()); assertEquals(prior.expected(), s.expected());
        }
        assertTrue(d.scenarios().stream().filter(s -> s.id().equals("KW-006")).findFirst().orElseThrow().expectedFindings().getFirst().startsWith("Do not conclude:"));
    }
    @Test void capturesOneActualRetrievalAndModelCallPerStrategy() throws Exception {
        var d = definitionQa(); var p = publication(d); var calls = new AtomicInteger(); var models = new AtomicInteger();
        var model = new ModelGateway() {
            public GenerationSettings settings() { return GenerationSettings.defaults(); }
            public Response generate(String prompt, String context, String schema) {
                models.incrementAndGet(); assertEquals(QaPrompt.SYSTEM, prompt); assertEquals(QaPrompt.SCHEMA, schema);
                return QaEvaluationTests.model(WRONG_UNITS).generate(prompt, context, schema);
            }
        };
        var run = new QaEvaluationRunner(id -> Optional.of(p), s -> (id, q, k) -> {
            calls.incrementAndGet(); return Optional.of(resultQa(p, q, k, s));
        }, model).execute(d, List.of("QA-REG-UNITS"), p.id(), PROFILE.id(), PROFILE, null, "TEST_DOUBLE", false);
        assertEquals(3, calls.get()); assertEquals(3, models.get());
        var grades = QaEvaluationArtifacts.grade(run);
        assertEquals(3, calls.get()); assertEquals(3, models.get());
        for (var grade : grades.cases()) {
            assertEquals("SUCCESS", grade.reliability()); assertEquals(VALID, grade.provenance());
            assertEquals(VALID, grade.context()); assertEquals(VALID, grade.citationIdentity()); assertEquals(VALID, grade.structure());
            assertEquals("NOT_REVIEWED", grade.semanticReviewStatus()); assertNull(grade.humanReview());
        }
        assertNull(run.application()); assertNull(run.modelDigest()); assertNotNull(run.modelDigestUnavailableReason());
        assertNull(run.executions().getFirst().invocation().inputTokens());
        assertTrue(run.executions().stream().allMatch(e -> e.rawResponse().available() && e.rawResponse().content() == null));
    }
    @Test void distinguishesContextBudgetLossFromRetrievalMiss() throws Exception {
        var d = definitionQa(); var p = publication(d);
        d = new QaComparison(d.version(), d.definitionHash(), d.inputHashes(), d.retrievalDefinition(), d.strategies(),
                new AnsweringConfiguration(12, 1, 6000, 3000), d.recommendedGeneration(), d.promptVersion(), d.answerVersion(), d.contextPolicy(), d.scenarios());
        var run = new QaEvaluationRunner(id -> Optional.of(p), s -> (id, q, k) -> {
            var r = resultQa(p, q, k, s);
            var other = p.processing().chunks().stream().filter(c -> c.filePath().endsWith("README.md") && c.content().length() < 500).findFirst().orElseThrow();
            return Optional.of(new RetrievalResult(r.publication(), q, k, r.strategy(), List.of(
                    new RetrievalResult.Match(1, 2, other), new RetrievalResult.Match(2, 1, pricing(p))), r.limitations()));
        }, model(ABSTAIN)).execute(d, List.of("QA-REG-UNITS"), p.id(), PROFILE.id(), PROFILE, null, "TEST_DOUBLE", false);
        for (var g : QaEvaluationArtifacts.grade(run).cases()) {
            assertEquals(1, g.retrievalCoverage().recall()); assertEquals(0, g.contextCoverage().recall());
            assertTrue(g.retrievalMisses().isEmpty()); assertEquals(1, g.contextBudgetLosses().size());
            assertEquals(NOT_APPLICABLE, g.citationIdentity()); assertEquals("STRUCTURAL_ABSTENTION_SEMANTICS_NOT_VERIFIED", g.structuralAnswerability());
        }
    }
    @Test void emptyKeywordRetrievalAbstainsWithoutModelButVectorsCanReturnNeighbors() throws Exception {
        var d = definitionQa(); var p = publication(d); var calls = new AtomicInteger();
        var run = new QaEvaluationRunner(id -> Optional.of(p), s -> (id, q, k) -> {
            var r = resultQa(p, q, k, s); calls.incrementAndGet();
            return Optional.of(s == RetrievalStrategy.KEYWORD ? new RetrievalResult(r.publication(), q, k, r.strategy(), List.of(), r.limitations()) : r);
        }, model(ABSTAIN)).execute(d, List.of("KW-006"), p.id(), PROFILE.id(), PROFILE, null, "TEST_DOUBLE", false);
        assertEquals(3, calls.get()); assertFalse(run.executions().getFirst().modelInvoked());
        assertNull(run.executions().getFirst().modelCallDurationMs()); assertNull(run.executions().getFirst().invocation());
        assertTrue(run.executions().get(1).modelInvoked());
        for (var g : QaEvaluationArtifacts.grade(run).cases()) {
            assertEquals("SUCCESS", g.reliability()); assertEquals(VALID, g.structure());
            assertEquals(NOT_APPLICABLE, g.citationIdentity()); assertNull(g.contextCoverage().recall());
        }
    }
    @Test void malformedOutputRetainsActualContextAndInvocationWithoutRawText() throws Exception {
        var run = runQa("secret-malformed-response");
        for (var e : run.executions()) {
            assertEquals("MALFORMED_MODEL_OUTPUT", e.failure().code()); assertEquals("INVALID_JSON", e.failure().malformedReason());
            assertNotNull(e.modelInput()); assertNotNull(e.invocation()); assertNull(e.answer()); assertNull(e.rawResponse().content());
        }
        var grade = QaEvaluationArtifacts.grade(run).cases().getFirst();
        assertEquals(VALID, grade.context()); assertEquals(INVALID, grade.structure()); assertEquals(NOT_VERIFIABLE, grade.citationIdentity());
        assertFalse(RetrievalComparison.JSON.writeValueAsString(run).contains("secret-malformed-response"));
    }
    @Test void invalidCitationsStayDistinctFromMalformedOrAbstainedAnswers() throws Exception {
        var run = runQa(WRONG_UNITS.replace("E1", "E99")); var g = QaEvaluationArtifacts.grade(run).cases().getFirst();
        assertEquals("INVALID_CITATION", g.reliability()); assertEquals(INVALID, g.citationIdentity()); assertNull(run.executions().getFirst().answer());
    }
    @Test void providerFailuresHaveNoFabricatedResponseOrTokens() throws Exception {
        var d = definitionQa(); var p = publication(d);
        var gateway = new ModelGateway() {
            public GenerationSettings settings() { return GenerationSettings.defaults(); }
            public Response generate(String prompt, String context, String schema) { throw new QaException(QaException.Code.MODEL_TIMEOUT, new RuntimeException("private provider body")); }
        };
        var run = new QaEvaluationRunner(id -> Optional.of(p), s -> (id, q, k) -> Optional.of(resultQa(p, q, k, s)), gateway)
                .execute(d, List.of("QA-REG-UNITS"), p.id(), PROFILE.id(), PROFILE, null, "TEST_DOUBLE", false);
        for (var e : run.executions()) {
            assertEquals("MODEL_TIMEOUT", e.failure().code()); assertNull(e.invocation()); assertFalse(e.rawResponse().available());
            assertNotNull(e.modelInput()); assertNotNull(e.modelCallDurationMs());
        }
        assertFalse(RetrievalComparison.JSON.writeValueAsString(run).contains("private provider body"));
        assertEquals(VALID, QaEvaluationArtifacts.grade(run).cases().getFirst().context());
    }
    @Test void retrievalFailureIsNotAbstentionAndHasNoModelContext() throws Exception {
        var d = definitionQa(); var p = publication(d);
        var run = new QaEvaluationRunner(id -> Optional.of(p), s -> (id, q, k) -> { throw new RetrievalException(new RuntimeException("private SQL")); }, model(WRONG_UNITS))
                .execute(d, List.of("QA-REG-UNITS"), p.id(), PROFILE.id(), PROFILE, null, "TEST_DOUBLE", false);
        var e = run.executions().getFirst(); assertEquals("RETRIEVAL_FAILURE", e.failure().code()); assertFalse(e.modelInvoked());
        assertNull(e.modelInput()); assertNull(e.answer()); assertNull(QaEvaluationArtifacts.grade(run).cases().getFirst().retrievalCoverage());
    }
    @Test void unknownPublicationAndMissingProfilesRemainExplicitFailures() throws Exception {
        var d = definitionQa(); var p = publication(d);
        var unknown = new QaEvaluationRunner(id -> Optional.empty(), s -> null, model(ABSTAIN))
                .execute(d, List.of("KW-006"), p.id(), null, null, null, "TEST_DOUBLE", false);
        assertTrue(unknown.executions().stream().allMatch(e -> "UNKNOWN_PUBLICATION".equals(e.failure().code()) && e.retrievalDurationMs() == null));
        assertEquals(3, QaEvaluationArtifacts.grade(unknown).cases().size());
        var missing = new QaEvaluationRunner(id -> Optional.of(p), s -> (id, q, k) -> Optional.of(resultQa(p, q, k, s)), model(ABSTAIN))
                .execute(d, List.of("KW-006"), p.id(), null, null, null, "TEST_DOUBLE", false);
        assertNull(missing.executions().getFirst().failure()); assertEquals("EMBEDDING_PROFILE_MISMATCH", missing.executions().get(1).failure().code());
    }
    @Test void offlineReplayIsStableAndNeverOverwritesArtifacts() throws Exception {
        var run = runQa(WRONG_UNITS); var directory = QaEvaluationArtifacts.write(output, run);
        var decoded = QaEvaluationArtifacts.read(directory.resolve("qa-execution.json")); assertEquals(run, decoded);
        assertEquals(QaEvaluationArtifacts.grade(run), QaEvaluationArtifacts.grade(decoded));
        assertThrows(FileAlreadyExistsException.class, () -> QaEvaluationArtifacts.write(output, run));
    }
    @Test void boundedRawCaptureIsOptInUnicodeSafeAndJsonEscaped() throws Exception {
        String response = "\n\r\t\u0001" + "😀".repeat(3000);
        assertNull(QaEvaluationRunner.capture(response, false).content());
        var raw = QaEvaluationRunner.capture(response, true); assertTrue(raw.truncated());
        assertTrue(raw.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 8192);
        assertTrue(java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(raw.content()));
        String json = RetrievalComparison.JSON.writeValueAsString(raw); assertFalse(json.contains("\n")); assertTrue(json.contains("\\n"));
        var d = definitionQa(); var p = publication(d);
        var run = new QaEvaluationRunner(id -> Optional.of(p), s -> (id, q, k) -> Optional.of(resultQa(p, q, k, s)), model(response))
                .execute(d, List.of("QA-REG-UNITS"), p.id(), PROFILE.id(), PROFILE, null, "TEST_DOUBLE", true);
        assertEquals(raw, run.executions().getFirst().rawResponse());
    }
    @Test void unknownAndOmittedCitationKeysFailOfflineIdentityGrading() throws Exception {
        for (String key : List.of("E99", "E2")) {
            var tampered = mutate(runQa(WRONG_UNITS), n -> ((ObjectNode) first(n).path("answer").path("claims").get(0)).putArray("evidenceKeys").add(key));
            assertEquals(INVALID, QaEvaluationArtifacts.grade(tampered).cases().getFirst().citationIdentity());
        }
    }
    @Test void alteredCitationContentAndPublicationAreInvalid() throws Exception {
        for (String field : List.of("publicationId", "content", "sourceHash")) {
            var tampered = mutate(runQa(WRONG_UNITS), n -> {
                var citation = (ObjectNode) first(n).path("answer").path("citations").get(0);
                if (field.equals("publicationId")) citation.put(field, "f".repeat(64));
                else ((ObjectNode) citation.path("evidence")).put(field, field.equals("sourceHash") ? "f".repeat(64) : "modified source");
            });
            assertEquals(INVALID, QaEvaluationArtifacts.grade(tampered).cases().getFirst().citationIdentity());
        }
    }
    @Test void alteredRetrievalProvenanceIsNotVerifiableAsCitations() throws Exception {
        var tampered = mutate(runQa(WRONG_UNITS), n -> ((ObjectNode) first(n).path("retrieval").path("publication")).put("id", "f".repeat(64)));
        var g = QaEvaluationArtifacts.grade(tampered).cases().getFirst(); assertEquals(INVALID, g.provenance()); assertEquals(NOT_VERIFIABLE, g.citationIdentity());
    }
    @Test void contextAndStructuralMutationsAreIndependentFailures() throws Exception {
        var run = runQa(WRONG_UNITS);
        var wrongContext = mutate(run, n -> ((ObjectNode) first(n).path("modelInput")).put("contextJson", "{}"));
        assertEquals(INVALID, QaEvaluationArtifacts.grade(wrongContext).cases().getFirst().context());
        var wrongStatus = mutate(run, n -> ((ObjectNode) first(n).path("answer")).put("status", "INSUFFICIENT_EVIDENCE"));
        var g = QaEvaluationArtifacts.grade(wrongStatus).cases().getFirst(); assertEquals(INVALID, g.structure()); assertEquals(VALID, g.citationIdentity());
    }
    @Test void offlineContextVerificationToleratesJvmObjectFieldOrderButNotChangedData() throws Exception {
        var run = runQa(WRONG_UNITS);
        var reordered = mutate(run, n -> {
            var input = (ObjectNode) first(n).path("modelInput");
            var original = RetrievalComparison.JSON.readTree(input.path("contextJson").asString());
            var reversed = RetrievalComparison.JSON.createObjectNode();
            var names = new ArrayList<>(original.propertyNames()); Collections.reverse(names);
            for (var name : names) reversed.set(name, original.get(name));
            input.put("contextJson", reversed.toString());
        });
        assertEquals(VALID, QaEvaluationArtifacts.grade(reordered).cases().getFirst().context());
        QaHumanReviewTests.reviewed(reordered).validate(reordered);
        for (String suffix : List.of(" {}", " trailing instructions")) {
            var bad = mutate(run, n -> {
                var input = (ObjectNode) first(n).path("modelInput"); input.put("contextJson", input.path("contextJson").asString() + suffix);
            });
            assertEquals(INVALID, QaEvaluationArtifacts.grade(bad).cases().getFirst().context());
        }
        assertThrows(RuntimeException.class, () -> QaEvaluationArtifacts.sameContext("{}", "{\"a\":1,\"a\":2}"));
    }
    @Test void incompleteExecutionGridAndChangedSettingsAreRejected() throws Exception {
        var run = runQa(WRONG_UNITS);
        assertThrows(IllegalArgumentException.class, () -> QaEvaluationArtifacts.grade(mutate(run, n -> n.putArray("executions"))));
        assertThrows(IllegalArgumentException.class, () -> QaEvaluationArtifacts.grade(mutate(run, n -> ((ObjectNode) n.path("generationSettings")).put("temperature", 0.2))));
    }
}
