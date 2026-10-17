package dev.changeassurance.retrieval;

import dev.changeassurance.embedding.EmbeddingException;
import dev.changeassurance.embedding.EmbeddingGateway;
import dev.changeassurance.embedding.EmbeddingProfile;
import dev.changeassurance.embedding.EmbeddingStore;
import dev.changeassurance.embedding.EmbeddingVector;
import dev.changeassurance.persistence.PublicationIntegrity;
import dev.changeassurance.persistence.PublicationIntegrityException;
import dev.changeassurance.persistence.PublicationSummary;
import dev.changeassurance.processing.EvidenceChunk;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Exact, explicit-publication vector search. Never indexes, repairs, or falls back. */
public final class VectorRetrievalService {
    public static final String STRATEGY = "postgres-vector-v1";
    private static final JsonMapper JSON = new JsonMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate read;
    private final EmbeddingGateway gateway;
    private static final String EVIDENCE_COLUMNS = """
            c.chunk_id, c.start_line, c.end_line, c.content, c.content_hash,
            c.context, c.parser, c.configuration,
            a.path, a.artifact_type, a.source_format, a.git_blob_id, a.source_hash
            """;
    private static final String MEMBERSHIP = """
            FROM publication_chunk m
            JOIN evidence_chunk c ON c.snapshot_id = m.snapshot_id AND c.chunk_id = m.chunk_id
            JOIN source_artifact a ON a.snapshot_id = c.snapshot_id AND a.path_key = c.path_key
            JOIN chunk_embedding e ON e.snapshot_id = m.snapshot_id AND e.chunk_id = m.chunk_id
            WHERE m.publication_id = ? AND e.profile_id = ?
            """;
    static final String SEARCH_SQL = "SELECT " + EVIDENCE_COLUMNS + ", (e.embedding <=> ?::vector) AS distance\n"
            + MEMBERSHIP + " ORDER BY distance ASC, a.path COLLATE \"C\", c.start_line, c.chunk_id COLLATE \"C\" LIMIT ?";
    private static final String VERIFY_SQL = "SELECT " + EVIDENCE_COLUMNS
            + ", e.input_hash, e.vector_hash, e.embedding::text AS vector\n" + MEMBERSHIP + " LIMIT 100001";

    public VectorRetrievalService(DataSource dataSource, EmbeddingGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(30);
        jdbc.setFetchSize(128);
        read = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setReadOnly(true);
        read.setTimeout(30);
    }

    /** Empty Optional means unknown publication; a known empty publication needs no model call. */
    public Optional<RetrievalResult> search(String publicationId, String query, int topK, EmbeddingProfile profile) {
        PublicationIntegrity.validateId(publicationId);
        if (profile == null || query == null || query.isBlank() || query.length() > KeywordRetrievalService.MAX_QUERY_LENGTH
                || query.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(query))
            throw new IllegalArgumentException("Expected a profile and nonblank valid Unicode query of at most 512 UTF-16 characters without NUL");
        if (topK < 1 || topK > KeywordRetrievalService.MAX_TOP_K)
            throw new IllegalArgumentException("topK must be between 1 and 100");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Vector retrieval must run outside a caller transaction");
        var before = database(() -> read.execute(status -> inspect(publicationId, profile)));
        if (before.isEmpty()) return Optional.empty();
        var publication = before.orElseThrow();
        if (total(publication) == 0) return Optional.of(result(publication, query, topK, profile, List.of()));

        // Both identity resolution and inference occur after the preflight transaction has ended.
        if (!profile.equals(gateway.resolveProfile())) throw new EmbeddingException(EmbeddingException.Code.PROFILE_MISMATCH);
        var response = gateway.embed(profile, profile.query(query));
        if (response == null) throw new EmbeddingException(EmbeddingException.Code.INVALID_PROVIDER_RESPONSE);
        float[] vector = response.vector();
        EmbeddingVector.validate(vector, profile.dimensions());

        return database(() -> read.execute(status -> {
            var current = inspect(publicationId, profile).orElseThrow(PublicationIntegrityException::new);
            if (!publication.equals(current)) throw new PublicationIntegrityException();
            // Verify every candidate, including rows that might otherwise corrupt the ranking but miss topK.
            verifyIndex(current, profile);
            var matches = jdbc.query(SEARCH_SQL, (row, index) -> {
                var chunk = chunk(row, current);
                double distance = row.getDouble("distance");
                if (row.wasNull() || !Double.isFinite(distance) || distance < 0 || distance > 2)
                    throw new EmbeddingException(EmbeddingException.Code.INDEX_INTEGRITY_FAILURE);
                return new RetrievalResult.Match(index + 1, 1.0 - distance, chunk);
            }, EmbeddingVector.sql(vector), publicationId, profile.id(), topK);
            if (matches.size() != Math.min(topK, total(current))) throw new PublicationIntegrityException();
            return Optional.of(result(current, query, topK, profile, matches));
        }));
    }

    private Optional<PublicationSummary> inspect(String id, EmbeddingProfile profile) {
        return PublicationIntegrity.inspect(jdbc, id).map(publication -> {
            // An empty evidence publication needs neither a stored index nor provider availability.
            if (total(publication) == 0) return publication;
            var stored = EmbeddingStore.readProfile(jdbc, profile.id());
            if (stored.isPresent() && !profile.equals(stored.get()))
                throw new EmbeddingException(EmbeddingException.Code.PROFILE_MISMATCH);
            var coverage = jdbc.queryForObject("""
                    SELECT count(*) AS total, count(e.chunk_id) AS indexed
                    FROM publication_chunk m LEFT JOIN chunk_embedding e
                    ON e.snapshot_id = m.snapshot_id AND e.chunk_id = m.chunk_id AND e.profile_id = ?
                    WHERE m.publication_id = ?
                    """, (r, n) -> new VectorRetrievalException.Coverage(id, profile.id(), r.getInt("total"),
                            r.getInt("indexed"), r.getInt("total") - r.getInt("indexed")), profile.id(), id);
            if (coverage == null || coverage.totalChunks() != total(publication)) throw new PublicationIntegrityException();
            if (coverage.missingChunks() != 0)
                throw new VectorRetrievalException(VectorRetrievalException.Code.INDEX_INCOMPLETE, coverage);
            if (stored.isEmpty()) throw new EmbeddingException(EmbeddingException.Code.PROFILE_MISMATCH);
            return publication;
        });
    }

    private void verifyIndex(PublicationSummary publication, EmbeddingProfile profile) {
        int[] verified = {0};
        jdbc.query(VERIFY_SQL, row -> {
            var evidence = chunk(row, publication);
            try {
                if (!profile.document(evidence.content()).hash().equals(row.getString("input_hash")))
                    throw new EmbeddingException(EmbeddingException.Code.INDEX_INTEGRITY_FAILURE);
                EmbeddingVector.verifyStored(row.getString("vector"), row.getString("vector_hash"), profile.dimensions());
            } catch (EmbeddingException e) { throw new EmbeddingException(EmbeddingException.Code.INDEX_INTEGRITY_FAILURE); }
            verified[0]++;
        }, publication.id(), profile.id());
        if (verified[0] != total(publication)) throw new PublicationIntegrityException();
    }
    private static EvidenceChunk chunk(ResultSet row, PublicationSummary publication) throws SQLException {
        try {
            var chunk = new EvidenceChunk(row.getString("chunk_id"), publication.snapshot(), row.getString("path"),
                    row.getString("artifact_type"), row.getString("source_format"), row.getString("git_blob_id"),
                    row.getString("source_hash"), row.getInt("start_line"), row.getInt("end_line"),
                    row.getString("content"), row.getString("content_hash"),
                    JSON.readValue(row.getString("context"), new TypeReference<List<String>>() { }),
                    row.getString("parser"), row.getString("configuration"));
            PublicationIntegrity.verifyChunk(chunk);
            return chunk;
        } catch (IllegalArgumentException | NullPointerException | tools.jackson.core.JacksonException e) {
            throw new PublicationIntegrityException();
        }
    }
    private static int total(PublicationSummary publication) {
        return publication.metadata().artifacts().stream().mapToInt(a -> a.chunkCount()).sum();
    }
    private static RetrievalResult result(PublicationSummary publication, String query, int topK,
                                          EmbeddingProfile profile, List<RetrievalResult.Match> matches) {
        return new RetrievalResult(publication, query, topK, STRATEGY + ":profile=" + profile.id(), matches, List.of(
                "Score is cosine similarity (1 - distance), not evidence relevance or answer confidence.",
                "Exact nearest neighbors may be irrelevant; no relevance threshold or feature-absence inference is applied.",
                "Search covers only the selected publication's available chunks; processing limitations still apply.",
                "Embedding input is complete source content with the versioned document prefix; parser context and paths were not embedded."));
    }
    private static <T> T database(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException | TransactionException e) {
            throw new VectorRetrievalException(VectorRetrievalException.Code.DATABASE_FAILURE, null);
        }
    }
}
