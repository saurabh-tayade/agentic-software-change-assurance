package dev.changeassurance.persistence;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/** Bounded catalog/metadata reads. No source bodies, filesystem access, or writes. */
public final class PublicationReadService {
    public static final int MAX_PAGE_SIZE = 50;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate read;

    public PublicationReadService(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(30);
        read = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setReadOnly(true);
        read.setTimeout(30);
    }

    public record Page(List<PublicationSummary> items, String nextAfter) {
        public Page { items = List.copyOf(items); }
    }

    public Page list(String repositoryId, String after, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) throw new IllegalArgumentException("Invalid page limit");
        if (after != null) PublicationIntegrity.validateId(after);
        if (repositoryId != null && (repositoryId.isBlank() || repositoryId.indexOf('\0') >= 0
                || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(repositoryId)))
            throw new IllegalArgumentException("Invalid repository identity");
        try {
            return read.execute(status -> {
                // IDs are lowercase fixed-width hex. C collation gives an explicit stable ordering.
                var ids = jdbc.queryForList("""
                        SELECT p.publication_id FROM processing_publication p
                        JOIN repository_snapshot s USING (snapshot_id)
                        WHERE (?::text IS NULL OR s.repository_id = ?)
                          AND (?::text IS NULL OR p.publication_id COLLATE "C" > ?)
                        ORDER BY p.publication_id COLLATE "C" LIMIT ?
                        """, String.class, repositoryId, repositoryId, after, after, limit + 1);
                var selected = ids.stream().limit(limit).map(id -> PublicationIntegrity.inspect(jdbc, id)
                        .orElseThrow(PublicationIntegrityException::new)).toList();
                return new Page(selected, ids.size() > limit ? selected.getLast().id() : null);
            });
        } catch (DataAccessException | TransactionException failure) {
            throw new EvidencePersistenceException("Could not read publication catalog", failure);
        }
    }

    public Optional<PublicationSummary> find(String publicationId) {
        PublicationIntegrity.validateId(publicationId);
        try {
            return read.execute(status -> PublicationIntegrity.inspect(jdbc, publicationId));
        } catch (DataAccessException | TransactionException failure) {
            throw new EvidencePersistenceException("Could not read publication metadata", failure);
        }
    }
}
