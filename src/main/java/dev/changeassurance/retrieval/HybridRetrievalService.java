package dev.changeassurance.retrieval;

import dev.changeassurance.embedding.EmbeddingProfile;
import dev.changeassurance.persistence.PublicationIntegrity;
import dev.changeassurance.persistence.PublicationIntegrityException;
import dev.changeassurance.processing.EvidenceChunk;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Equal-weight rank fusion over verified immutable-publication results. No writes or fallback. */
public final class HybridRetrievalService {
    public static final String STRATEGY = "postgres-hybrid-rrf-v1";
    public static final int FUSION_CONSTANT = 60;
    private final EvidenceRetriever keyword;
    private final VectorRetrievalService vector;

    public HybridRetrievalService(EvidenceRetriever keyword, VectorRetrievalService vector) {
        this.keyword = Objects.requireNonNull(keyword); this.vector = Objects.requireNonNull(vector);
    }

    public static int candidateDepth(int topK) {
        if (topK < 1 || topK > KeywordRetrievalService.MAX_TOP_K) throw new IllegalArgumentException("topK must be between 1 and 100");
        return Math.min(100, Math.max(20, 4 * topK));
    }

    public Optional<RetrievalResult> search(String publicationId, String query, int topK, EmbeddingProfile profile) {
        PublicationIntegrity.validateId(publicationId);
        int depth = candidateDepth(topK);
        if (profile == null || query == null || query.isBlank() || query.length() > KeywordRetrievalService.MAX_QUERY_LENGTH
                || query.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(query))
            throw new IllegalArgumentException("Expected a profile and bounded valid Unicode query");
        // Vector first: its preflight detects unknown/incomplete indexes without redundant keyword work.
        var semantic = vector.search(publicationId, query, depth, profile);
        if (semantic.isEmpty()) return Optional.empty();
        var lexical = keyword.search(publicationId, query, depth).orElseThrow(PublicationIntegrityException::new);
        return Optional.of(fuse(publicationId, query, topK, profile, lexical, semantic.orElseThrow()));
    }

    static RetrievalResult fuse(String publicationId, String query, int topK, EmbeddingProfile profile,
                                 RetrievalResult lexical, RetrievalResult semantic) {
        int depth = candidateDepth(topK);
        check(lexical.publication().equals(semantic.publication()));
        check(KeywordRetrievalService.STRATEGY.equals(lexical.strategy()));
        check((VectorRetrievalService.STRATEGY + ":profile=" + profile.id()).equals(semantic.strategy()));
        var candidates = new HashMap<Identity, Candidate>();
        add(candidates, lexical, publicationId, query, depth, true);
        add(candidates, semantic, publicationId, query, depth, false);
        var ordered = candidates.values().stream().sorted(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparing(c -> c.evidence.filePath(), HybridRetrievalService::compareUtf8)
                .thenComparingInt(c -> c.evidence.startLine()).thenComparing(c -> c.evidence.id())).toList();
        var matches = new ArrayList<RetrievalResult.Match>();
        for (var c : ordered.subList(0, Math.min(topK, ordered.size())))
            matches.add(new RetrievalResult.Match(matches.size() + 1, c.score(), c.evidence));
        var limitations = new ArrayList<String>();
        limitations.add("RRF score is ranking priority, not semantic relevance probability or answer confidence.");
        limitations.add("Equal branch weights; k=60; candidateDepth=" + depth + "; keywordCandidates=" + lexical.resultCount()
                + "; vectorCandidates=" + semantic.resultCount() + "; uniqueCandidates=" + candidates.size()
                + "; returned=" + matches.size() + ". Candidates outside either bounded list cannot contribute.");
        for (String limit : lexical.limitations()) limitations.add("Keyword branch: " + limit);
        for (String limit : semantic.limitations()) limitations.add("Vector branch: " + limit);
        return new RetrievalResult(lexical.publication(), query, topK,
                STRATEGY + ":k=60:depth=" + depth + ":weights=1,1:profile=" + profile.id(), matches, limitations);
    }

    private static void add(Map<Identity, Candidate> candidates, RetrievalResult result, String id,
                            String query, int depth, boolean lexical) {
        check(id.equals(result.publication().id()) && query.equals(result.query()) && result.topK() == depth
                && result.matches().size() <= depth);
        int expectedRank = 1;
        var seen = new HashSet<Identity>();
        for (var match : result.matches()) {
            var e = match.evidence();
            var p = result.publication();
            check(match.rank() == expectedRank++ && Double.isFinite(match.score()) && e != null
                    && p.snapshot().equals(e.snapshot()) && p.metadata().sourcePaths().contains(e.filePath())
                    && p.metadata().configuration().identity().equals(e.chunkingConfiguration())
                    && p.metadata().artifacts().stream().anyMatch(a -> a.path().equals(e.filePath()) && a.parsed()
                    && a.chunkCount() > 0 && a.parser().equals(e.parser())));
            PublicationIntegrity.verifyChunk(e);
            var identity = new Identity(p.snapshotId(), e.id());
            var candidate = candidates.computeIfAbsent(identity, key -> new Candidate(e));
            check(candidate.evidence.equals(e));
            // Identical repeated rows contribute only their first (best) rank in a branch.
            if (seen.add(identity)) {
                if (lexical) candidate.keywordRank = match.rank(); else candidate.vectorRank = match.rank();
            }
        }
    }
    // PostgreSQL COLLATE "C" orders UTF-8 bytes, unlike Java's UTF-16 String order for supplementary characters.
    private static int compareUtf8(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
    private static void check(boolean valid) { if (!valid) throw new PublicationIntegrityException(); }
    private record Identity(String snapshotId, String chunkId) { }
    private static final class Candidate {
        final EvidenceChunk evidence;
        int keywordRank;
        int vectorRank;
        Candidate(EvidenceChunk evidence) { this.evidence = evidence; }
        double score() {
            return (keywordRank == 0 ? 0 : 1.0 / (FUSION_CONSTANT + keywordRank))
                    + (vectorRank == 0 ? 0 : 1.0 / (FUSION_CONSTANT + vectorRank));
        }
    }
}
