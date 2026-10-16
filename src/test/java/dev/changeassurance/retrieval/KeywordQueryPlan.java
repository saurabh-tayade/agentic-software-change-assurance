package dev.changeassurance.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;

/** Test-only access to EXPLAIN the actual production query with identical parameters. */
public final class KeywordQueryPlan {
    private KeywordQueryPlan() { }
    public static List<String> explain(JdbcTemplate jdbc, String publicationId, String query, int topK) {
        return jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + KeywordRetrievalService.SEARCH_SQL,
                String.class, query, query, publicationId, publicationId, topK);
    }
}
