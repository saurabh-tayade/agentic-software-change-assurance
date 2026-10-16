package dev.changeassurance.persistence;

import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.processing.EvidenceChunk;
import dev.changeassurance.processing.ProcessingResult;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static dev.changeassurance.ingestion.IngestionResult.*;

/** Atomic, immutable publication and exact-identity reads. No source filesystem or AST access. */
public final class EvidencePersistenceService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public EvidencePersistenceService(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(120);
    }

    public PersistedPublication publish(IngestionResult ingestion, ProcessingResult processing) {
        EvidenceValidation.validate(ingestion, processing);
        String snapshotId = EvidenceValidation.snapshotId(ingestion.snapshot());
        String derivation = EvidenceValidation.derivationId(processing);
        String id = EvidenceValidation.publicationId(snapshotId, derivation, processing);
        var expected = new PersistedPublication(id, snapshotId, derivation, ingestion, processing);
        try {
            return transaction.execute(status -> {
                var snapshot = ingestion.snapshot();
                jdbc.update("""
                        INSERT INTO repository_snapshot VALUES (?, ?, ?, ?::jsonb, ?)
                        ON CONFLICT (snapshot_id) DO NOTHING
                        """, snapshotId, snapshot.repositoryId(), snapshot.commitSha(),
                        PersistenceJson.write(snapshot.scope()), snapshot.policyVersion());
                // Serializes publications for one selected snapshot, including concurrent first writers.
                var stored = jdbc.queryForObject("SELECT * FROM repository_snapshot WHERE snapshot_id = ? FOR UPDATE",
                        (row, index) -> snapshot(row), snapshotId);
                equivalent(snapshot, stored, "snapshot");
                var existing = read(id);
                if (existing.isPresent()) {
                    equivalent(expected, existing.get(), "publication");
                    return existing.get();
                }
                jdbc.batchUpdate("""
                        INSERT INTO source_artifact (snapshot_id, path, path_key, artifact_type, source_format,
                            git_blob_id, source_hash, content) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (snapshot_id, path_key) DO NOTHING
                        """, ingestion.artifacts(), 256, (statement, source) -> {
                    statement.setString(1, snapshotId);
                    statement.setString(2, source.path());
                    statement.setString(3, pathKey(source.path()));
                    statement.setString(4, source.type().name());
                    statement.setString(5, source.format().name());
                    statement.setString(6, source.gitBlobId());
                    statement.setString(7, source.sha256());
                    statement.setString(8, source.content());
                });
                jdbc.batchUpdate("""
                        INSERT INTO evidence_chunk VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        ON CONFLICT (snapshot_id, chunk_id) DO NOTHING
                        """, processing.chunks(), 256, (statement, chunk) -> {
                    statement.setString(1, snapshotId);
                    statement.setString(2, chunk.id());
                    statement.setString(3, pathKey(chunk.filePath()));
                    statement.setInt(4, chunk.startLine());
                    statement.setInt(5, chunk.endLine());
                    statement.setString(6, chunk.content());
                    statement.setString(7, chunk.contentHash());
                    statement.setString(8, chunk.parser());
                    statement.setString(9, chunk.chunkingConfiguration());
                    statement.setString(10, PersistenceJson.write(chunk.context()));
                });
                jdbc.update("INSERT INTO processing_publication VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                        id, snapshotId, derivation, processing.configuration().identity(), processing.status().name(),
                        PersistenceJson.write(PublicationMetadata.from(ingestion, processing)));
                var ordinals = java.util.stream.IntStream.range(0, processing.chunks().size()).boxed().toList();
                jdbc.batchUpdate("INSERT INTO publication_chunk VALUES (?, ?, ?, ?)", ordinals, 256, (statement, index) -> {
                    statement.setString(1, id);
                    statement.setString(2, snapshotId);
                    statement.setString(3, processing.chunks().get(index).id());
                    statement.setInt(4, index);
                });
                // Compare actual persisted fields, not just hashes or ON CONFLICT row counts.
                var actual = read(id).orElseThrow();
                equivalent(expected, actual, "immutable evidence");
                return actual;
            });
        } catch (DataAccessException | TransactionException exception) {
            throw new EvidencePersistenceException("Evidence publication transaction failed", exception);
        }
    }

    public Optional<PersistedPublication> findPublication(String publicationId) {
        if (publicationId == null || !publicationId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected a full publication SHA-256 identity");
        }
        try {
            return read(publicationId);
        } catch (DataAccessException exception) {
            throw new EvidencePersistenceException("Could not read evidence publication", exception);
        }
    }

    /** An unknown publication differs from a known publication with zero chunks. */
    public Optional<List<EvidenceChunk>> findChunks(String publicationId) {
        return findPublication(publicationId).map(publication -> publication.processing().chunks());
    }

    private Optional<PersistedPublication> read(String id) {
        var rows = jdbc.query("SELECT * FROM processing_publication WHERE publication_id = ?", (row, index) ->
                new Header(row.getString("snapshot_id"), row.getString("derivation_id"),
                        PersistenceJson.read(row.getString("metadata"), PublicationMetadata.class)), id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var header = rows.getFirst();
        var snapshot = jdbc.queryForObject("SELECT * FROM repository_snapshot WHERE snapshot_id = ?",
                (row, index) -> snapshot(row), header.snapshotId());
        var metadata = header.metadata();
        var sources = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT * FROM source_artifact WHERE snapshot_id = ? AND path_key = ANY (?)");
            statement.setString(1, header.snapshotId());
            statement.setArray(2, connection.createArrayOf("text", metadata.sourcePaths().stream()
                    .map(EvidencePersistenceService::pathKey).toArray(String[]::new)));
            return statement;
        }, (row, index) -> new SourceArtifact(snapshot, row.getString("path"), ArtifactType.valueOf(row.getString("artifact_type")),
                Format.valueOf(row.getString("source_format")), row.getString("git_blob_id"),
                row.getString("source_hash"), row.getString("content")));
        var byPath = new HashMap<String, SourceArtifact>();
        sources.forEach(source -> byPath.put(source.path(), source));
        var ordered = metadata.sourcePaths().stream().map(path -> {
            var source = byPath.get(path);
            if (source == null) {
                throw new EvidencePersistenceException("Stored publication has a missing source artifact");
            }
            return source;
        }).toList();
        var chunks = jdbc.query("""
                SELECT c.*, a.path, a.artifact_type, a.source_format, a.git_blob_id, a.source_hash
                FROM publication_chunk m
                JOIN evidence_chunk c ON c.snapshot_id = m.snapshot_id AND c.chunk_id = m.chunk_id
                JOIN source_artifact a ON a.snapshot_id = c.snapshot_id AND a.path_key = c.path_key
                WHERE m.publication_id = ? ORDER BY m.ordinal
                """, (row, index) -> new EvidenceChunk(row.getString("chunk_id"), snapshot, row.getString("path"),
                row.getString("artifact_type"), row.getString("source_format"), row.getString("git_blob_id"),
                row.getString("source_hash"), row.getInt("start_line"), row.getInt("end_line"),
                row.getString("content"), row.getString("content_hash"),
                PersistenceJson.read(row.getString("context"), new TypeReference<List<String>>() { }),
                row.getString("parser"), row.getString("configuration")), id);
        var ingestion = new IngestionResult(snapshot, ordered, metadata.ingestionCoverage(), metadata.ingestionLimitations());
        var processing = new ProcessingResult(snapshot, metadata.configuration(), metadata.status(), metadata.artifacts(), chunks,
                metadata.ingestionCoverage(), metadata.ingestionLimitations(), metadata.ingestionSummary(), metadata.processingLimitations());
        return Optional.of(new PersistedPublication(id, header.snapshotId(), header.derivationId(), ingestion, processing));
    }

    private static Snapshot snapshot(ResultSet row) throws SQLException {
        return new Snapshot(row.getString("repository_id"), row.getString("commit_sha"),
                PersistenceJson.read(row.getString("scope"), RepositorySelection.Scope.class), row.getString("policy_version"));
    }

    private static String pathKey(String path) {
        return EvidenceValidation.hash(path.getBytes(StandardCharsets.UTF_8));
    }

    private static void equivalent(Object expected, Object actual, String kind) {
        if (!expected.equals(actual)) {
            throw new EvidencePersistenceException("Conflicting payload for existing " + kind + " identity");
        }
    }

    private record Header(String snapshotId, String derivationId, PublicationMetadata metadata) { }
}
