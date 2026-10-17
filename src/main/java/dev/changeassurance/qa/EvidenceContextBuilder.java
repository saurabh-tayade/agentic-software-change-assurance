package dev.changeassurance.qa;

import dev.changeassurance.persistence.PublicationIntegrity;
import dev.changeassurance.persistence.PublicationSummary;
import dev.changeassurance.processing.EvidenceChunk;
import dev.changeassurance.retrieval.RetrievalResult;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete chunks only. Budget includes JSON escaping, question, and all envelope metadata. */
public final class EvidenceContextBuilder {
    public static final String POLICY = "complete-ranked-chunks-v1";
    private static final JsonMapper JSON = new JsonMapper();

    public Context build(RetrievalResult retrieval, AnsweringConfiguration configuration) {
        var entries = new ArrayList<Entry>();
        var omissions = new ArrayList<Omission>();
        var seen = new HashMap<String, EvidenceChunk>();
        String json = envelope(retrieval, entries);
        if (bytes(json) > configuration.maxContextBytes()) throw new QaException(QaException.Code.CONTEXT_BUDGET_FAILURE);
        int previousRank = 0;
        for (var match : retrieval.matches()) {
            var chunk = match.evidence();
            verifyEvidence(retrieval.publication(), chunk);
            if (match.rank() <= previousRank || !Double.isFinite(match.score())) integrityFailure();
            previousRank = match.rank();
            var previous = seen.putIfAbsent(chunk.id(), chunk);
            String reason = null;
            if (previous != null) {
                if (!previous.equals(chunk)) integrityFailure();
                reason = "DUPLICATE";
            } else if (entries.size() >= configuration.maxEvidence()) {
                reason = "EVIDENCE_COUNT_LIMIT";
            } else {
                var candidate = new Entry("E" + (entries.size() + 1), match.rank(), match.score(), chunk);
                if (bytes(JSON.writeValueAsString(data(candidate))) > configuration.maxEvidenceBytes()) {
                    reason = "EVIDENCE_BYTE_LIMIT";
                } else {
                    entries.add(candidate);
                    String proposed = envelope(retrieval, entries);
                    if (bytes(proposed) > configuration.maxContextBytes()) {
                        entries.removeLast();
                        reason = "CONTEXT_BYTE_LIMIT";
                    } else {
                        json = proposed;
                    }
                }
            }
            if (reason != null) omissions.add(new Omission(match.rank(), chunk.id(), reason));
        }
        var limitations = List.of(
                "Only complete selected chunks were shown; omitted chunks cannot be cited.",
                "UTF-8 byte limits are conservative input bounds, not exact model token counts.",
                "Coverage in model context is summarized; the answer retains complete publication coverage.");
        var selection = new Selection(POLICY, entries.stream().map(e -> new Selected(e.key(), e.rank(), e.evidence().id())).toList(),
                omissions, bytes(json), limitations);
        return new Context(retrieval.publication(), retrieval.query(), entries, selection, json);
    }

    static void verifyEvidence(PublicationSummary publication, EvidenceChunk chunk) {
        var metadata = publication.metadata();
        if (!publication.snapshot().equals(chunk.snapshot()) || !metadata.sourcePaths().contains(chunk.filePath())
                || !metadata.configuration().identity().equals(chunk.chunkingConfiguration())
                || metadata.artifacts().stream().noneMatch(a -> a.path().equals(chunk.filePath())
                && a.parsed() && a.chunkCount() > 0 && a.parser().equals(chunk.parser()))) integrityFailure();
        PublicationIntegrity.verifyChunk(chunk);
    }

    private static void integrityFailure() { throw new QaException(QaException.Code.PUBLICATION_INTEGRITY_FAILURE); }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    private static String envelope(RetrievalResult result, List<Entry> entries) {
        var m = result.publication().metadata();
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("dataType", "UNTRUSTED_REPOSITORY_EVIDENCE");
        envelope.put("question", result.query());
        envelope.put("publicationId", result.publication().id());
        envelope.put("processingCoverage", Map.of("status", m.status(), "ingestionSummary", m.ingestionSummary(),
                "artifactCount", m.artifacts().size(), "coverageEntryCount", m.ingestionCoverage().size(),
                "ingestionLimitationCount", m.ingestionLimitations().size(), "processingLimitationCount", m.processingLimitations().size()));
        envelope.put("selection", Map.of("policy", POLICY, "retrievedCount", result.resultCount(),
                "selectedCount", entries.size(), "notShownCount", result.resultCount() - entries.size(),
                "retrievalStrategy", result.strategy(), "retrievalTopK", result.topK(),
                "limitation", (dev.changeassurance.retrieval.KeywordRetrievalService.STRATEGY.equals(result.strategy())
                        ? "Lexical topK search" : "Ranked topK retrieval")
                        + " and bounded context cannot establish feature absence or deployment state."));
        envelope.put("evidence", entries.stream().map(EvidenceContextBuilder::data).toList());
        return JSON.writeValueAsString(envelope);
    }

    private static Map<String, Object> data(Entry entry) {
        var c = entry.evidence();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("rank", entry.rank()); metadata.put("score", entry.score());
        metadata.put("chunkId", c.id()); metadata.put("repositoryId", c.snapshot().repositoryId());
        metadata.put("commitSha", c.snapshot().commitSha()); metadata.put("path", c.filePath());
        metadata.put("startLine", c.startLine()); metadata.put("endLine", c.endLine());
        metadata.put("artifactType", c.artifactType()); metadata.put("sourceFormat", c.sourceFormat());
        var result = new LinkedHashMap<String, Object>();
        result.put("key", entry.key());
        result.put("retrievalAndSourceMetadata", metadata);
        result.put("structuralContext", c.context());
        result.put("originalSourceContent", c.content());
        return result;
    }

    public record Entry(String key, int rank, double score, EvidenceChunk evidence) { }
    public record Selected(String key, int rank, String chunkId) { }
    public record Omission(int rank, String chunkId, String reason) { }
    public record Selection(String policy, List<Selected> selected, List<Omission> omissions, int contextBytes,
                            List<String> limitations) {
        public Selection {
            selected = List.copyOf(selected); omissions = List.copyOf(omissions); limitations = List.copyOf(limitations);
        }
    }

    /** Only the builder can create an allowlist; callers cannot inject additional citation records. */
    public static final class Context {
        private final PublicationSummary publication;
        private final String question;
        private final List<Entry> entries;
        private final Selection selection;
        private final String json;
        private Context(PublicationSummary publication, String question, List<Entry> entries, Selection selection, String json) {
            this.publication = publication; this.question = question; this.entries = List.copyOf(entries);
            this.selection = selection; this.json = json;
        }
        public PublicationSummary publication() { return publication; }
        public String question() { return question; }
        public List<Entry> entries() { return entries; }
        public Selection selection() { return selection; }
        public String json() { return json; }
    }
}
