package dev.changeassurance.evaluation;

import dev.changeassurance.embedding.EmbeddingException;
import dev.changeassurance.persistence.*;
import dev.changeassurance.retrieval.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static dev.changeassurance.evaluation.EvaluationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalEvaluationTests {
    @TempDir Path temporary;

    @Test void importsAllFifteenCasesWithoutChangingHistoricalInputs() throws Exception {
        var d = definition();
        assertEquals(15, d.scenarios().size());
        assertEquals(14, d.scenarios().stream().filter(s -> !s.expected().isEmpty()).count());
        assertEquals(List.of(5, 12), d.topK());
        assertEquals("scoped", d.scenarios().get(13).publicationVariant());
        assertEquals("README.md", d.scenarios().get(13).expected().getFirst().path());
        assertEquals(1, d.scenarios().get(14).configuration().maxChunks());
        assertFalse(d.scenarios().stream().anyMatch(s -> s.query().equals("Where is the shipping cost calculated?")));
    }
    @Test void rejectsChangedImportHashAndUnsafeImport() throws Exception {
        var original = Files.readString(Path.of("evaluation/retrieval/retrieval-comparison-v1.json"));
        var file = temporary.resolve("definition.json");
        Files.writeString(file, original.replace("225166c71a7ce0fee376fcf024a401852f375c7cb9691ac6455cf481ef19826b", "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> RetrievalComparison.load(Path.of("."), file));
        Files.writeString(file, original.replace("evaluation/retrieval/keyword-v1.json", "../keyword-v1.json"));
        assertThrows(Exception.class, () -> RetrievalComparison.load(Path.of("."), file));
    }
    @Test void executesHybridKSeparatelyAndRecordsActualStrategySettings() throws Exception {
        var d = definition(); var pubs = publications(d); var calls = new ArrayList<String>();
        var runner = new RetrievalEvaluationRunner(id -> pubs.values().stream().filter(p -> p.id().equals(id)).findFirst(), strategy -> (id, q, k) -> {
            calls.add(strategy + ":" + k); return Optional.of(result(pubs.values().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(), q, k, strategy));
        });
        var run = runner.execute(d, ids(pubs), PROFILE.id(), PROFILE, null, "TEST_DOUBLE");
        assertEquals(90, calls.size());
        assertEquals(15, Collections.frequency(calls, "HYBRID:5"));
        assertEquals(15, Collections.frequency(calls, "HYBRID:12"));
        assertTrue(run.executions().stream().allMatch(e -> e.failure() == null));
        for (var e : run.executions()) if (e.requestedStrategy() == RetrievalStrategy.HYBRID)
            assertTrue(e.result().strategy().contains(":depth=" + (e.topK() == 5 ? 20 : 48) + ":"));
    }
    @Test void explicitDenominatorsExcludeEmptyExpectationsAndFailures() throws Exception {
        var d = definition(); var pubs = publications(d);
        var onlyFull = Map.of("full", pubs.get("full").id());
        var run = runner(pubs).execute(d, onlyFull, PROFILE.id(), PROFILE, null, "TEST_DOUBLE");
        var grades = RetrievalEvaluationArtifacts.grade(run);
        for (var a : grades.aggregates()) {
            assertEquals(15, a.scheduled()); assertEquals(13, a.successful()); assertEquals(2, a.failed());
            assertEquals(1, a.emptyExpectationSuccesses()); assertEquals(12, a.qualityDenominator());
        }
        assertEquals(12, run.executions().stream().filter(e -> "PUBLICATION_NOT_CONFIGURED".equals(e.failure())).count());
        assertTrue(grades.cases().stream().filter(g -> g.scenarioId().equals("KW-006")).allMatch(g -> g.metrics().recall() == null));
    }
    @Test void roundTripPreservesEvidenceAndRecomputesIdenticalOfflineMetrics() throws Exception {
        var d = definition(); var run = run(d, publications(d));
        var directory = RetrievalEvaluationArtifacts.write(temporary, run);
        var reloaded = RetrievalEvaluationArtifacts.read(directory.resolve("execution.json"));
        assertEquals(run, reloaded);
        assertEquals(RetrievalEvaluationArtifacts.grade(run), RetrievalEvaluationArtifacts.grade(reloaded));
        assertNull(reloaded.application().commit());
        assertThrows(FileAlreadyExistsException.class, () -> RetrievalEvaluationArtifacts.write(temporary, run));
        assertTrue(Files.readString(directory.resolve("execution.json")).contains("\"retrievalDurationMs\""));
    }
    @Test void providerFailuresAreRecordedWithoutExceptionBodiesOrQualityCredit() throws Exception {
        var d = definition(); var pubs = publications(d);
        var runner = new RetrievalEvaluationRunner(id -> pubs.values().stream().filter(p -> p.id().equals(id)).findFirst(), strategy -> (id, q, k) -> {
            if (strategy != RetrievalStrategy.KEYWORD) throw new EmbeddingException(EmbeddingException.Code.MODEL_UNAVAILABLE);
            throw new RetrievalException(new IllegalStateException("password=DO_NOT_PERSIST"));
        });
        var run = runner.execute(d, ids(pubs), PROFILE.id(), PROFILE, null, "TEST_DOUBLE");
        assertTrue(run.executions().stream().allMatch(e -> e.failure() != null && e.retrievalDurationMs() != null));
        assertEquals(60, run.executions().stream().filter(e -> "EMBEDDING_MODEL_UNAVAILABLE".equals(e.failure())).count());
        assertFalse(RetrievalComparison.JSON.writeValueAsString(run).contains("DO_NOT_PERSIST"));
        for (var aggregate : RetrievalEvaluationArtifacts.grade(run).aggregates()) {
            assertEquals(0, aggregate.qualityDenominator()); assertNull(aggregate.mrrAtK()); assertNull(aggregate.macroRecallAtK());
        }
    }
    @Test void unknownPublicationAndMissingProfileRemainExplicit() throws Exception {
        var d = definition(); var pubs = publications(d);
        var unknown = new RetrievalEvaluationRunner(id -> Optional.empty(), strategy -> { fail("No retrieval allowed"); return null; });
        var run = unknown.execute(d, ids(pubs), PROFILE.id(), PROFILE, null, "TEST_DOUBLE");
        assertTrue(run.executions().stream().allMatch(e -> "UNKNOWN_PUBLICATION".equals(e.failure()) && e.retrievalDurationMs() == null));
        var noProfile = runner(pubs).execute(d, ids(pubs), null, null, null, "TEST_DOUBLE");
        assertEquals(60, noProfile.executions().stream().filter(e -> "PROFILE_NOT_AVAILABLE".equals(e.failure())).count());
        assertEquals(30, noProfile.executions().stream().filter(e -> e.failure() == null).count());
        var mismatch = runner(pubs).execute(d, ids(pubs), "f".repeat(64), PROFILE, null, "TEST_DOUBLE");
        assertEquals(60, mismatch.executions().stream().filter(e -> "PROFILE_MISMATCH".equals(e.failure())).count());
        RetrievalEvaluationArtifacts.grade(mismatch);
    }
    @Test void rejectsWrongVariantEvenWhenPublicationContainsRelevantEvidence() throws Exception {
        var d = definition(); var pubs = publications(d);
        var ids = new TreeMap<>(ids(pubs)); ids.put("scoped", ids.get("full")); ids.put("limited", ids.get("full"));
        var run = runner(pubs).execute(d, ids, PROFILE.id(), PROFILE, null, "TEST_DOUBLE");
        assertEquals(12, run.executions().stream().filter(e -> "PUBLICATION_MISMATCH".equals(e.failure())).count());
    }
    @ParameterizedTest @ValueSource(strings = {"strategy", "query", "rank", "publication", "evidence", "tie", "topK"})
    void rejectsInvalidReturnedResults(String mutation) throws Exception {
        var d = definition(); var pubs = publications(d); var s = d.scenarios().getFirst(); var p = pubs.get("full");
        var r = result(p, s.query(), 5, RetrievalStrategy.KEYWORD);
        var matches = new ArrayList<>(r.matches());
        if (mutation.equals("rank")) matches.set(0, new RetrievalResult.Match(3, 1, matches.getFirst().evidence()));
        if (mutation.equals("evidence")) matches.set(0, new RetrievalResult.Match(1, 1, pubs.get("scoped").processing().chunks().getFirst()));
        if (mutation.equals("tie")) { Collections.reverse(matches); for (int i = 0; i < matches.size(); i++) matches.set(i, new RetrievalResult.Match(i + 1, 1, matches.get(i).evidence())); }
        var wrong = new RetrievalResult(mutation.equals("publication") ? summary(pubs.get("scoped")) : r.publication(),
                mutation.equals("query") ? "other" : r.query(), mutation.equals("topK") ? 12 : 5,
                mutation.equals("strategy") ? "other-strategy" : r.strategy(), matches, r.limitations());
        assertThrows(RuntimeException.class, () -> RetrievalEvaluationArtifacts.validateResult(s, RetrievalStrategy.KEYWORD, 5, null, p, wrong));
    }
    @Test void incompleteIndexDiagnosticsAndPartialProcessingCoverageAreRetained() throws Exception {
        var d = definition(); var pubs = publications(d);
        var runner = new RetrievalEvaluationRunner(id -> pubs.values().stream().filter(p -> p.id().equals(id)).findFirst(), strategy -> (id, q, k) -> {
            if (strategy != RetrievalStrategy.KEYWORD) throw new VectorRetrievalException(VectorRetrievalException.Code.INDEX_INCOMPLETE,
                    new VectorRetrievalException.Coverage(id, PROFILE.id(), 33, 30, 3));
            return Optional.of(result(pubs.values().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(), q, k, strategy));
        });
        var run = runner.execute(d, ids(pubs), PROFILE.id(), PROFILE, null, "TEST_DOUBLE");
        assertEquals(60, run.executions().stream().filter(e -> e.failureDiagnostics() != null).count());
        var directory = RetrievalEvaluationArtifacts.write(temporary, run);
        assertEquals(run, RetrievalEvaluationArtifacts.read(directory.resolve("execution.json")));
        assertFalse(run.publications().get("limited").processing().processingLimitations().isEmpty());
        assertEquals(14, RetrievalEvaluationArtifacts.grade(run).aggregates().getFirst().qualityDenominator());
    }
    @Test void rejectsMissingOrDuplicatedExecutionRowsDuringOfflineGrading() throws Exception {
        var d = definition(); var r = run(d, publications(d)); var rows = new ArrayList<>(r.executions());
        rows.removeLast();
        var missing = new RetrievalEvaluationRun(r.schemaVersion(), r.runId(), r.startedAt(), r.configurationId(), r.definition(),
                r.publicationIds(), r.requestedProfileId(), r.embeddingProfile(), r.application(), r.executionMode(), r.publications(), rows);
        assertThrows(IllegalArgumentException.class, () -> RetrievalEvaluationArtifacts.grade(missing));
        rows.add(rows.getFirst());
        assertThrows(IllegalArgumentException.class, () -> RetrievalEvaluationArtifacts.grade(missing));
    }
}
