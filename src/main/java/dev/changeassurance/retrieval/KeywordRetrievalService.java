package dev.changeassurance.retrieval;

import dev.changeassurance.persistence.PublicationIntegrity;
import dev.changeassurance.persistence.PublicationIntegrityException;
import dev.changeassurance.processing.EvidenceChunk;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/** Explicit-publication, database-side search. No filesystem, parsers, or answer generation. */
public final class KeywordRetrievalService implements EvidenceRetriever {
    public static final String STRATEGY = "postgres-keyword-v1";
    public static final int MAX_QUERY_LENGTH = 512;
    public static final int MAX_TOP_K = 100;
    private static final JsonMapper JSON = new JsonMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate read;

    // All user values are parameters. Both candidate matching and LIMIT execute in PostgreSQL.
    static final String SEARCH_SQL = """
            WITH q AS MATERIALIZED (SELECT keyword_query_v1(?) AS terms,
                    plainto_tsquery('simple', ?) AS exact_terms),
            candidates AS (
                SELECT m.snapshot_id, m.chunk_id
                FROM publication_chunk m
                JOIN evidence_chunk c ON c.snapshot_id = m.snapshot_id AND c.chunk_id = m.chunk_id
                CROSS JOIN q
                WHERE m.publication_id = ? AND c.search_document @@ q.terms
                UNION
                SELECT m.snapshot_id, m.chunk_id
                FROM publication_chunk m
                JOIN evidence_chunk c ON c.snapshot_id = m.snapshot_id AND c.chunk_id = m.chunk_id
                JOIN source_artifact a ON a.snapshot_id = c.snapshot_id AND a.path_key = c.path_key
                CROSS JOIN q
                WHERE m.publication_id = ? AND a.search_path @@ q.terms
            )
            SELECT c.chunk_id, c.start_line, c.end_line, c.content, c.content_hash,
                   c.context, c.parser, c.configuration,
                   a.path, a.artifact_type, a.source_format, a.git_blob_id, a.source_hash,
                   (ts_rank(c.search_document, q.terms, 32) + ts_rank(a.search_path, q.terms, 32)
                    + CASE WHEN to_tsvector('simple', left(c.context::text, 65536)) @@ q.exact_terms
                           THEN 1.0 ELSE 0.0 END)::double precision AS score
            FROM candidates m
            JOIN evidence_chunk c ON c.snapshot_id = m.snapshot_id AND c.chunk_id = m.chunk_id
            JOIN source_artifact a ON a.snapshot_id = c.snapshot_id AND a.path_key = c.path_key
            CROSS JOIN q
            ORDER BY score DESC, a.path COLLATE "C", c.start_line, c.chunk_id COLLATE "C"
            LIMIT ?
            """;

    public KeywordRetrievalService(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
        read = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setReadOnly(true);
        read.setTimeout(30);
    }

    /** Empty Optional means unknown publication; a present empty matches list means no matches. */
    public Optional<RetrievalResult> search(String publicationId, String query, int topK) {
        PublicationIntegrity.validateId(publicationId);
        if (query == null || query.isBlank() || query.length() > MAX_QUERY_LENGTH
                || query.indexOf('\0') >= 0 || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(query)) {
            throw new IllegalArgumentException("Query must be valid Unicode, nonblank, and at most 512 UTF-16 characters without NUL");
        }
        if (topK < 1 || topK > MAX_TOP_K) throw new IllegalArgumentException("topK must be between 1 and 100");
        try {
            return read.execute(status -> PublicationIntegrity.inspect(jdbc, publicationId).map(publication -> {
                var matches = jdbc.query(SEARCH_SQL, (row, index) -> {
                    try {
                        var chunk = new EvidenceChunk(row.getString("chunk_id"), publication.snapshot(), row.getString("path"),
                            row.getString("artifact_type"), row.getString("source_format"), row.getString("git_blob_id"),
                            row.getString("source_hash"), row.getInt("start_line"), row.getInt("end_line"),
                            row.getString("content"), row.getString("content_hash"),
                            JSON.readValue(row.getString("context"), new TypeReference<List<String>>() { }),
                            row.getString("parser"), row.getString("configuration"));
                        PublicationIntegrity.verifyChunk(chunk);
                        return new RetrievalResult.Match(index + 1, row.getDouble("score"), chunk);
                    } catch (IllegalArgumentException | NullPointerException | tools.jackson.core.JacksonException exception) {
                        throw new PublicationIntegrityException();
                    }
                }, query, query, publicationId, publicationId, topK);
                return new RetrievalResult(publication, query, topK, STRATEGY, matches, List.of(
                        "Keyword matches do not establish feature availability or answer support.",
                        "Search indexes the first 65536 characters of each chunk content and serialized context; citations retain full original content.",
                        "Simple lexical matching has no stemming, synonyms, fuzzy matching, or query operators."));
            }));
        } catch (DataAccessException | TransactionException exception) {
            throw new RetrievalException(exception);
        }
    }
}
