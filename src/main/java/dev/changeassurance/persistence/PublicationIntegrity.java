package dev.changeassurance.persistence;

import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.processing.EvidenceChunk;
import dev.changeassurance.processing.ProcessingResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** Bounded metadata-only verification. Call within the same read transaction as evidence selection. */
public final class PublicationIntegrity {
    private PublicationIntegrity() { }

    public static Optional<PublicationSummary> inspect(JdbcTemplate jdbc, String id) {
        validateId(id);
        try {
            return inspectStored(jdbc, id);
        } catch (IllegalArgumentException | NullPointerException | tools.jackson.core.JacksonException exception) {
            throw new PublicationIntegrityException();
        }
    }

    public static void validateId(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected a full publication SHA-256 identity");
        }
    }

    private static Optional<PublicationSummary> inspectStored(JdbcTemplate jdbc, String id) {
        var headers = jdbc.query("""
                SELECT p.*, s.repository_id, s.commit_sha, s.scope, s.policy_version
                FROM processing_publication p LEFT JOIN repository_snapshot s USING (snapshot_id)
                WHERE publication_id = ?
                """, (r, n) -> {
            var snapshot = new IngestionResult.Snapshot(r.getString("repository_id"), r.getString("commit_sha"),
                    PersistenceJson.read(r.getString("scope"), RepositorySelection.Scope.class), r.getString("policy_version"));
            var metadata = PersistenceJson.read(r.getString("metadata"), PublicationMetadata.class);
            check(metadata.status().name().equals(r.getString("status"))
                    && metadata.configuration().identity().equals(r.getString("configuration")));
            return new PublicationSummary(id, r.getString("snapshot_id"), r.getString("derivation_id"), snapshot, metadata);
        }, id);
        if (headers.isEmpty()) return Optional.empty();
        var summary = headers.getFirst();
        var m = summary.metadata();
        check(m.sourcePaths().size() <= 10_000 && m.ingestionCoverage().size() <= 10_000
                && m.sourcePaths().size() + m.ingestionCoverage().size() <= 10_000);
        var skeleton = new ProcessingResult(summary.snapshot(), m.configuration(), m.status(), m.artifacts(), List.of(),
                m.ingestionCoverage(), m.ingestionLimitations(), m.ingestionSummary(), m.processingLimitations());
        check(EvidenceValidation.snapshotId(summary.snapshot()).equals(summary.snapshotId()));
        check(EvidenceValidation.derivationId(skeleton).equals(summary.derivationId()));
        check(EvidenceValidation.publicationId(summary.snapshotId(), summary.derivationId(), skeleton).equals(id));
        var outcomes = new HashMap<String, ProcessingResult.ArtifactResult>();
        for (var a : m.artifacts()) {
            check(outcomes.put(a.path(), a) == null && a.chunkCount() >= 0);
            check(a.status() == ProcessingResult.Status.FAILED
                    ? !a.parsed() && a.chunkCount() == 0 && !a.limitations().isEmpty()
                    : a.parsed() && (a.status() == ProcessingResult.Status.COMPLETE) == a.limitations().isEmpty());
        }
        check(new HashSet<>(m.sourcePaths()).size() == m.sourcePaths().size()
                && outcomes.keySet().equals(new HashSet<>(m.sourcePaths())));
        var storedPaths = jdbc.queryForList("SELECT path FROM source_artifact WHERE snapshot_id = ? LIMIT 10001",
                String.class, summary.snapshotId());
        check(storedPaths.size() <= 10_000 && new HashSet<>(storedPaths).containsAll(m.sourcePaths()));
        var coveragePaths = new HashSet<>(m.sourcePaths());
        for (var c : m.ingestionCoverage()) check(coveragePaths.add(c.path()));
        var counts = m.ingestionSummary();
        check(counts.ingested() == m.sourcePaths().size() && counts.discovered() == coveragePaths.size()
                && counts.excluded() == m.ingestionCoverage().stream().filter(c -> c.disposition() == IngestionResult.Disposition.EXCLUDED).count()
                && counts.unsupported() == m.ingestionCoverage().stream().filter(c -> c.disposition() == IngestionResult.Disposition.UNSUPPORTED).count()
                && counts.failed() == m.ingestionCoverage().stream().filter(c -> c.disposition() == IngestionResult.Disposition.FAILED).count());
        boolean gap = m.artifacts().stream().anyMatch(a -> a.status() != ProcessingResult.Status.COMPLETE)
                || m.ingestionCoverage().stream().anyMatch(c -> c.disposition() != IngestionResult.Disposition.EXCLUDED
                || c.reason() == IngestionResult.Reason.FILE_SIZE_LIMIT || c.reason() == IngestionResult.Reason.TOTAL_CONTENT_LIMIT);
        check(m.status() == (!gap ? ProcessingResult.Status.COMPLETE : m.artifacts().stream().anyMatch(ProcessingResult.ArtifactResult::parsed)
                ? ProcessingResult.Status.PARTIAL : ProcessingResult.Status.FAILED));
        // Stream narrow membership rows: no source/context/content payloads and no N+1 queries.
        var seen = new HashSet<String>();
        var perPath = new HashMap<String, Integer>();
        String[] previousPath = {""};
        int[] nextLine = {1};
        int[] ordinal = {0};
        jdbc.query("""
                SELECT m.ordinal, m.snapshot_id, m.chunk_id, c.start_line, c.end_line,
                       c.parser, c.configuration, a.path
                FROM publication_chunk m
                LEFT JOIN evidence_chunk c ON c.snapshot_id = m.snapshot_id AND c.chunk_id = m.chunk_id
                LEFT JOIN source_artifact a ON a.snapshot_id = c.snapshot_id AND a.path_key = c.path_key
                WHERE m.publication_id = ? ORDER BY m.ordinal LIMIT 100001
                """, row -> {
            String path = row.getString("path");
            check(path != null && outcomes.containsKey(path) && path.compareTo(previousPath[0]) >= 0);
            if (!path.equals(previousPath[0])) nextLine[0] = 1;
            check(row.getInt("ordinal") == ordinal[0]++ && summary.snapshotId().equals(row.getString("snapshot_id"))
                    && seen.add(row.getString("chunk_id")) && row.getInt("start_line") == nextLine[0]
                    && row.getInt("end_line") >= nextLine[0]
                    && row.getInt("end_line") - nextLine[0] < m.configuration().maxLines()
                    && m.configuration().identity().equals(row.getString("configuration"))
                    && outcomes.get(path).parser().equals(row.getString("parser")));
            previousPath[0] = path;
            nextLine[0] = row.getInt("end_line") + 1;
            perPath.merge(path, 1, Integer::sum);
        }, id);
        check(ordinal[0] <= m.configuration().maxChunks());
        for (var a : m.artifacts()) check(a.chunkCount() == perPath.getOrDefault(a.path(), 0));
        return Optional.of(summary);
    }

    /** Rehash returned evidence only; administrators remain trusted for unselected source bytes. */
    public static void verifyChunk(EvidenceChunk chunk) {
        check(EvidenceValidation.hash(chunk.content().getBytes(StandardCharsets.UTF_8)).equals(chunk.contentHash())
                && EvidenceValidation.chunkId(chunk).equals(chunk.id()));
    }

    private static void check(boolean condition) {
        if (!condition) throw new PublicationIntegrityException();
    }
}
