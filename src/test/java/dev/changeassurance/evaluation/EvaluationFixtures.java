package dev.changeassurance.evaluation;

import dev.changeassurance.embedding.EmbeddingProfile;
import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.persistence.*;
import dev.changeassurance.processing.ArtifactProcessor;
import dev.changeassurance.retrieval.*;
import java.nio.file.*;
import java.util.*;

final class EvaluationFixtures {
    static final EmbeddingProfile PROFILE = new EmbeddingProfile("ollama", "nomic-embed-text:v1.5", "a".repeat(64), 768,
            EmbeddingProfile.DOCUMENT_RECIPE, EmbeddingProfile.QUERY_RECIPE, EmbeddingProfile.Options.defaults(), "cosine");
    static RetrievalComparison definition() throws Exception {
        return RetrievalComparison.load(Path.of("."), Path.of("evaluation/retrieval/retrieval-comparison-v1.json"));
    }
    static Map<String, PersistedPublication> publications(RetrievalComparison d) throws Exception {
        var pubs = new TreeMap<String, PersistedPublication>();
        for (var scenario : d.scenarios()) {
            if (pubs.containsKey(scenario.publicationVariant())) continue;
            var snapshot = new IngestionResult.Snapshot(d.repositoryId(), "b".repeat(40), scenario.scope(), "git-snapshot-v1");
            var sources = new ArrayList<IngestionResult.SourceArtifact>();
            for (var entry : d.sourceHashes().entrySet()) {
                if (!scenario.scope().includes().isEmpty() && !entry.getKey().startsWith("src/")) continue;
                String path = entry.getKey();
                var format = path.endsWith(".java") ? IngestionResult.Format.JAVA : IngestionResult.Format.MARKDOWN;
                var type = path.startsWith("tests/") ? IngestionResult.ArtifactType.TEST : path.endsWith(".md")
                        ? IngestionResult.ArtifactType.DOCUMENTATION : IngestionResult.ArtifactType.SOURCE;
                String content = Files.readString(Path.of("evaluation/corpora/parcel-quotes/v1").resolve(path));
                byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var digest = java.security.MessageDigest.getInstance("SHA-1");
                digest.update(("blob " + bytes.length + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String blob = HexFormat.of().formatHex(digest.digest(bytes));
                sources.add(new IngestionResult.SourceArtifact(snapshot, path, type, format, blob, entry.getValue(), content));
            }
            var ingestion = new IngestionResult(snapshot, sources, List.of(), List.of());
            String id = RetrievalComparison.hash(scenario.publicationVariant().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            pubs.put(scenario.publicationVariant(), new PersistedPublication(id, id, id, ingestion,
                    new ArtifactProcessor().process(ingestion, scenario.configuration())));
        }
        return pubs;
    }
    static Map<String, String> ids(Map<String, PersistedPublication> pubs) {
        var ids = new TreeMap<String, String>(); pubs.forEach((key, value) -> ids.put(key, value.id())); return ids;
    }
    static PublicationSummary summary(PersistedPublication p) {
        var r = p.processing();
        return new PublicationSummary(p.id(), p.snapshotId(), p.derivationId(), p.ingestion().snapshot(), new PublicationMetadata(
                p.ingestion().artifacts().stream().map(a -> a.path()).toList(), r.configuration(), r.status(), r.artifacts(),
                r.ingestionCoverage(), r.ingestionLimitations(), r.ingestionSummary(), r.processingLimitations()));
    }
    static RetrievalResult result(PersistedPublication p, String query, int k, RetrievalStrategy strategy) {
        String version = switch (strategy) {
            case KEYWORD -> KeywordRetrievalService.STRATEGY;
            case VECTOR -> VectorRetrievalService.STRATEGY + ":profile=" + PROFILE.id();
            case HYBRID -> HybridRetrievalService.STRATEGY + ":k=60:depth=" + HybridRetrievalService.candidateDepth(k) + ":weights=1,1:profile=" + PROFILE.id();
        };
        var ordered = p.processing().chunks().stream().sorted(Comparator.comparing(dev.changeassurance.processing.EvidenceChunk::filePath)
                .thenComparingInt(dev.changeassurance.processing.EvidenceChunk::startLine).thenComparing(dev.changeassurance.processing.EvidenceChunk::id)).limit(k).toList();
        var matches = new ArrayList<RetrievalResult.Match>();
        for (var e : ordered) matches.add(new RetrievalResult.Match(matches.size() + 1, 1.0, e));
        return new RetrievalResult(summary(p), query, k, version, matches, List.of("Deterministic fixture; no live quality claim."));
    }
    static RetrievalEvaluationRunner runner(Map<String, PersistedPublication> pubs) {
        return new RetrievalEvaluationRunner(id -> pubs.values().stream().filter(p -> p.id().equals(id)).findFirst(),
                strategy -> (id, query, k) -> pubs.values().stream().filter(p -> p.id().equals(id)).findFirst().map(p -> result(p, query, k, strategy)));
    }
    static RetrievalEvaluationRun run(RetrievalComparison d, Map<String, PersistedPublication> pubs) {
        return runner(pubs).execute(d, ids(pubs), PROFILE.id(), PROFILE, new RetrievalEvaluationRun.Application(null, null, null, null), "TEST_DOUBLE");
    }
}
