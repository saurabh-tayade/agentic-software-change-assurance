package dev.changeassurance.qa;

import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.processing.EvidenceChunk;
import dev.changeassurance.retrieval.RetrievalResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static dev.changeassurance.qa.GroundedQuestionAnsweringServiceTests.assertCode;
import static dev.changeassurance.qa.QaFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceContextBuilderTests {
    private final EvidenceContextBuilder builder = new EvidenceContextBuilder();

    @Test void selectionIsDeterministicAndPreservesRankingAndOriginalEvidence() {
        var retrieval = retrieval("First evidence.\n", "Second evidence.\n", "Third evidence.\n");
        var first = builder.build(retrieval, AnsweringConfiguration.defaults());
        var repeated = builder.build(retrieval, AnsweringConfiguration.defaults());
        assertEquals(first.json(), repeated.json());
        assertEquals(first.selection(), repeated.selection());
        assertEquals(List.of("E1", "E2", "E3"), first.entries().stream().map(EvidenceContextBuilder.Entry::key).toList());
        assertEquals(List.of(1, 2, 3), first.entries().stream().map(EvidenceContextBuilder.Entry::rank).toList());
        assertEquals(retrieval.matches().stream().map(RetrievalResult.Match::evidence).toList(),
                first.entries().stream().map(EvidenceContextBuilder.Entry::evidence).toList());
        assertEquals(first.json().getBytes(StandardCharsets.UTF_8).length, first.selection().contextBytes());
    }

    @Test void limitsEvidenceCountAndReportsEveryOmission() {
        var context = builder.build(retrieval("One\n", "Two\n", "Three\n"), new AnsweringConfiguration(12, 1, 6000, 3000));
        assertEquals(1, context.entries().size());
        assertEquals(2, context.selection().omissions().size());
        assertTrue(context.selection().omissions().stream().allMatch(o -> o.reason().equals("EVIDENCE_COUNT_LIMIT")));
        assertEquals(List.of(2, 3), context.selection().omissions().stream().map(EvidenceContextBuilder.Omission::rank).toList());
    }

    @Test void countsJsonMetadataAndEscapingWithinTheTotalByteBudget() {
        var input = retrieval("\\\"\t".repeat(100) + "\n", "More source content.\n", "Another chunk.\n");
        var bounded = builder.build(input, new AnsweringConfiguration(12, 6, 1500, 1500));
        assertTrue(bounded.selection().contextBytes() <= 1500);
        assertEquals(bounded.json().getBytes(StandardCharsets.UTF_8).length, bounded.selection().contextBytes());
        assertFalse(bounded.selection().omissions().isEmpty());
        assertEquals(input.matches().size(), bounded.entries().size() + bounded.selection().omissions().size());
    }

    @Test void omitsLargeCompleteChunksAndContinuesToNextEligibleChunkWithoutTruncation() {
        String large = "Large original source: " + "x".repeat(5000) + "\n";
        var input = retrieval(large, "Small original source.\n");
        var context = builder.build(input, AnsweringConfiguration.defaults());
        assertEquals(1, context.entries().size());
        assertEquals("Small original source.\n", context.entries().getFirst().evidence().content());
        assertEquals(2, context.entries().getFirst().rank());
        assertEquals("E1", context.entries().getFirst().key());
        assertEquals("EVIDENCE_BYTE_LIMIT", context.selection().omissions().getFirst().reason());
        assertFalse(context.json().contains("Large original source"));
        assertEquals(large, input.matches().getFirst().evidence().content());
    }

    @Test void preservesUnicodeAndPhysicalLineEndingsExactly() {
        String original = "\ufeffcafé 😀 漢字\r\nSecond line\rLast line";
        var context = builder.build(retrieval(original), AnsweringConfiguration.defaults());
        assertEquals(original, context.entries().getFirst().evidence().content());
        var data = new JsonMapper().readTree(context.json()).get("evidence").get(0);
        assertEquals(original, data.get("originalSourceContent").asString());
        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(context.json()));
        assertEquals(context.json().getBytes(StandardCharsets.UTF_8).length, context.selection().contextBytes());
    }

    @Test void repeatedEvidenceIsIncludedOnlyOnceAndExplicitlyReported() {
        var input = retrieval("Repeated source.\n");
        var match = input.matches().getFirst();
        var duplicate = withMatches(input, List.of(match, new RetrievalResult.Match(2, .5, match.evidence())));
        var context = builder.build(duplicate, AnsweringConfiguration.defaults());
        assertEquals(1, context.entries().size());
        assertEquals(1, context.selection().omissions().size());
        assertEquals("DUPLICATE", context.selection().omissions().getFirst().reason());
    }

    @Test void separatesOriginalSourceStructuralContextAndRetrievalMetadata() {
        String original = "# Policy\nThe policy returns 800 cents.\n";
        var input = retrieval(original);
        var context = builder.build(input, AnsweringConfiguration.defaults());
        var envelope = new JsonMapper().readTree(context.json());
        assertEquals("UNTRUSTED_REPOSITORY_EVIDENCE", envelope.get("dataType").asString());
        assertEquals(input.query(), envelope.get("question").asString());
        for (int i = 0; i < context.entries().size(); i++) {
            var entry = envelope.get("evidence").get(i);
            var evidence = context.entries().get(i).evidence();
            assertEquals(evidence.content(), entry.get("originalSourceContent").asString());
            assertEquals(evidence.filePath(), entry.get("retrievalAndSourceMetadata").get("path").asString());
            assertTrue(entry.get("structuralContext").isArray());
            assertFalse(entry.get("originalSourceContent").isObject());
        }
        assertFalse(context.selection().limitations().isEmpty());
    }

    @Test void rejectsEvidenceFromAnotherRepositoryCommitOrScope() {
        var input = retrieval("Original source.\n");
        var c = input.matches().getFirst().evidence();
        for (var snapshot : List.of(
                new IngestionResult.Snapshot("another-repository", c.snapshot().commitSha(), c.snapshot().scope(), c.snapshot().policyVersion()),
                new IngestionResult.Snapshot(c.snapshot().repositoryId(), "e".repeat(40), c.snapshot().scope(), c.snapshot().policyVersion()),
                new IngestionResult.Snapshot(c.snapshot().repositoryId(), c.snapshot().commitSha(),
                        new RepositorySelection.Scope(List.of("src"), List.of()), c.snapshot().policyVersion()))) {
            var other = new EvidenceChunk(c.id(), snapshot, c.filePath(), c.artifactType(), c.sourceFormat(), c.gitBlobId(),
                    c.sourceHash(), c.startLine(), c.endLine(), c.content(), c.contentHash(), c.context(), c.parser(), c.chunkingConfiguration());
            var poisoned = withMatches(input, List.of(new RetrievalResult.Match(1, 1, other)));
            assertCode(QaException.Code.PUBLICATION_INTEGRITY_FAILURE, () -> builder.build(poisoned, AnsweringConfiguration.defaults()));
        }
    }

    @Test void rejectsOutOfOrderRanksAndNonfiniteScores() {
        var input = retrieval("One\n", "Two\n");
        var first = input.matches().getFirst();
        var second = input.matches().getLast();
        assertCode(QaException.Code.PUBLICATION_INTEGRITY_FAILURE, () -> builder.build(
                withMatches(input, List.of(second, first)), AnsweringConfiguration.defaults()));
        assertCode(QaException.Code.PUBLICATION_INTEGRITY_FAILURE, () -> builder.build(withMatches(input,
                List.of(new RetrievalResult.Match(1, Double.NaN, first.evidence()))), AnsweringConfiguration.defaults()));
    }
}
