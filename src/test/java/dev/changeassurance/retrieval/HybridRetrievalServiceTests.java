package dev.changeassurance.retrieval;

import dev.changeassurance.embedding.EmbeddingProfile;
import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.persistence.PublicationIntegrityException;
import dev.changeassurance.persistence.PublicationMetadata;
import dev.changeassurance.persistence.PublicationSummary;
import dev.changeassurance.processing.ArtifactProcessor;
import dev.changeassurance.processing.EvidenceChunk;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class HybridRetrievalServiceTests {
    private static final String ID = "a".repeat(64);
    private static final EmbeddingProfile PROFILE = new EmbeddingProfile("ollama", "nomic-embed-text:v1.5", "a".repeat(64),
            768, EmbeddingProfile.DOCUMENT_RECIPE, EmbeddingProfile.QUERY_RECIPE, EmbeddingProfile.Options.defaults(), "cosine");
    private final List<EvidenceChunk> chunks;
    private final PublicationSummary publication;
    HybridRetrievalServiceTests() throws Exception {
        var snapshot = new IngestionResult.Snapshot("rrf-fixture", "b".repeat(40), RepositorySelection.Scope.all(), "git-snapshot-v1");
        var sources = new ArrayList<IngestionResult.SourceArtifact>();
        for (String path : List.of("a.md", "b.md", "\ue000.md", "😀.md")) {
            String content = "# " + path + "\nEvidence.\n";
            sources.add(new IngestionResult.SourceArtifact(snapshot, path, IngestionResult.ArtifactType.DOCUMENTATION,
                    IngestionResult.Format.MARKDOWN, "c".repeat(40), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))), content));
        }
        var processed = new ArtifactProcessor().process(new IngestionResult(snapshot, sources, List.of(), List.of()));
        chunks = processed.chunks();
        publication = new PublicationSummary(ID, "d".repeat(64), "e".repeat(64), snapshot,
                new PublicationMetadata(sources.stream().map(IngestionResult.SourceArtifact::path).toList(), processed.configuration(),
                        processed.status(), processed.artifacts(), processed.ingestionCoverage(), processed.ingestionLimitations(),
                        processed.ingestionSummary(), processed.processingLimitations()));
    }
    private EvidenceChunk chunk(String path) { return chunks.stream().filter(c -> c.filePath().equals(path)).findFirst().orElseThrow(); }
    private RetrievalResult branch(boolean keyword, int... indices) {
        var matches = IntStream.range(0, indices.length).mapToObj(i -> new RetrievalResult.Match(i + 1,
                keyword ? 999 - i : -0.5, chunks.get(indices[i]))).toList();
        return new RetrievalResult(publication, "question", 20, keyword ? KeywordRetrievalService.STRATEGY
                : VectorRetrievalService.STRATEGY + ":profile=" + PROFILE.id(), matches, List.of("Branch limitation"));
    }
    private RetrievalResult fuse(RetrievalResult k, RetrievalResult v, int topK) {
        return HybridRetrievalService.fuse(ID, "question", topK, PROFILE, k, v);
    }
    @Test void sumsRanksForOverlapWithoutUsingRawScores() {
        var result = fuse(branch(true, 0, 1), branch(false, 1, 2), 3);
        assertSame(chunks.get(1), result.matches().getFirst().evidence());
        assertEquals(1.0 / 62 + 1.0 / 61, result.matches().getFirst().score(), 1e-12);
        assertSame(chunks.get(0), result.matches().get(1).evidence());
        assertEquals(1.0 / 61, result.matches().get(1).score());
        assertSame(chunks.get(2), result.matches().get(2).evidence());
        assertEquals(1.0 / 62, result.matches().get(2).score());
        assertTrue(result.strategy().contains(":k=60:depth=20:weights=1,1:profile=" + PROFILE.id()));
        assertTrue(result.limitations().stream().anyMatch(s -> s.contains("uniqueCandidates=3")));
    }
    @Test void eitherEmptyCandidateListIsValidAndBothEmptyRemainEmpty() {
        assertEquals(chunks.getFirst(), fuse(branch(true), branch(false, 0), 1).matches().getFirst().evidence());
        assertEquals(chunks.getFirst(), fuse(branch(true, 0), branch(false), 1).matches().getFirst().evidence());
        assertTrue(fuse(branch(true), branch(false), 1).matches().isEmpty());
    }
    @Test void duplicateIdentityContributesOnlyItsBestRankPerBranch() {
        var result = fuse(branch(true, 0, 0, 1), branch(false, 0, 0), 3);
        assertEquals(2, result.resultCount());
        assertEquals(2.0 / 61, result.matches().getFirst().score());
        assertEquals(1.0 / 63, result.matches().get(1).score());
    }
    @Test void equalScoresUsePathOrderRatherThanBranchOrder() {
        var result = fuse(branch(true, 1, 0), branch(false, 0, 1), 2);
        assertEquals(List.of(chunks.get(0), chunks.get(1)), result.matches().stream().map(RetrievalResult.Match::evidence).toList());
        assertEquals(result.matches().getFirst().score(), result.matches().getLast().score());
    }
    @Test void unicodeTiesUseUtf8CollationMatchingPostgresC() {
        var k = branch(true);
        var v = new RetrievalResult(publication, "question", 20, branch(false).strategy(),
                List.of(new RetrievalResult.Match(1, 0, chunk("😀.md"))), List.of());
        k = new RetrievalResult(publication, "question", 20, k.strategy(),
                List.of(new RetrievalResult.Match(1, 0, chunk("\ue000.md"))), List.of());
        assertEquals("\ue000.md", fuse(k, v, 2).matches().getFirst().evidence().filePath());
    }
    @Test void finalTopKIsEnforcedAfterFusion() {
        var result = fuse(branch(true, 0, 1, 2, 3), branch(false, 3, 2, 1, 0), 1);
        assertEquals(1, result.resultCount()); assertEquals(1, result.topK()); assertEquals(1, result.matches().getFirst().rank());
    }
    @Test void candidateDepthRuleIsBoundedAndRejectsInvalidTopK() {
        assertEquals(20, HybridRetrievalService.candidateDepth(1));
        assertEquals(48, HybridRetrievalService.candidateDepth(12));
        assertEquals(100, HybridRetrievalService.candidateDepth(26));
        assertEquals(100, HybridRetrievalService.candidateDepth(100));
        for (int invalid : new int[]{0, -1, 101, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> HybridRetrievalService.candidateDepth(invalid));
    }
    @Test void oversizedCandidateListAndInvalidRanksFailClosed() {
        assertThrows(PublicationIntegrityException.class, () -> fuse(branch(true, new int[21]), branch(false), 1));
        var broken = new RetrievalResult(publication, "question", 20, branch(true).strategy(),
                List.of(new RetrievalResult.Match(2, 1, chunks.getFirst())), List.of());
        assertThrows(PublicationIntegrityException.class, () -> fuse(broken, branch(false), 1));
    }
    @Test void conflictingDuplicateEvidenceFailsRatherThanChoosingABranch() {
        var c = chunks.getFirst();
        var changed = new EvidenceChunk(c.id(), c.snapshot(), c.filePath(), c.artifactType(), c.sourceFormat(), c.gitBlobId(),
                c.sourceHash(), c.startLine(), c.endLine(), "tampered", c.contentHash(), c.context(), c.parser(), c.chunkingConfiguration());
        var broken = new RetrievalResult(publication, "question", 20, branch(false).strategy(),
                List.of(new RetrievalResult.Match(1, 1, changed)), List.of());
        assertThrows(PublicationIntegrityException.class, () -> fuse(branch(true, 0), broken, 1));
    }
    @Test void differentPublicationOrProfileOrQueryIsRejected() {
        var base = branch(false, 0);
        var different = new PublicationSummary("f".repeat(64), publication.snapshotId(), publication.derivationId(),
                publication.snapshot(), publication.metadata());
        for (var broken : List.of(new RetrievalResult(different, "question", 20, base.strategy(), base.matches(), List.of()),
                new RetrievalResult(publication, "other", 20, base.strategy(), base.matches(), List.of()),
                new RetrievalResult(publication, "question", 19, base.strategy(), base.matches(), List.of()),
                new RetrievalResult(publication, "question", 20, "postgres-vector-v1:profile=" + "f".repeat(64), base.matches(), List.of())))
            assertThrows(PublicationIntegrityException.class, () -> fuse(branch(true, 0), broken, 1));
    }
    @Test void crossSnapshotEvidenceIsRejected() {
        var c = chunks.getFirst();
        var other = new IngestionResult.Snapshot("other", c.snapshot().commitSha(), c.snapshot().scope(), c.snapshot().policyVersion());
        var changed = new EvidenceChunk(c.id(), other, c.filePath(), c.artifactType(), c.sourceFormat(), c.gitBlobId(),
                c.sourceHash(), c.startLine(), c.endLine(), c.content(), c.contentHash(), c.context(), c.parser(), c.chunkingConfiguration());
        var broken = new RetrievalResult(publication, "question", 20, branch(false).strategy(),
                List.of(new RetrievalResult.Match(1, 1, changed)), List.of());
        assertThrows(PublicationIntegrityException.class, () -> fuse(branch(true), broken, 1));
    }
    @Test void selectionRequiresExplicitVectorServiceAndProfileWithoutKeywordFallback() {
        EvidenceRetriever keyword = (id, query, k) -> { throw new AssertionError("Unexpected fallback"); };
        assertSame(keyword, EvidenceRetrievers.select(RetrievalStrategy.KEYWORD, keyword, null, null));
        for (var strategy : List.of(RetrievalStrategy.VECTOR, RetrievalStrategy.HYBRID))
            assertThrows(IllegalArgumentException.class, () -> EvidenceRetrievers.select(strategy, keyword, null, PROFILE));
    }
}
