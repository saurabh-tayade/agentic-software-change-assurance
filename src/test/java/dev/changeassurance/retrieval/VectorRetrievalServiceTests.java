package dev.changeassurance.retrieval;

import dev.changeassurance.embedding.EmbeddingGateway;
import dev.changeassurance.embedding.EmbeddingProfile;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class VectorRetrievalServiceTests {
    private static final EmbeddingProfile PROFILE = new EmbeddingProfile("ollama", "nomic-embed-text:v1.5", "a".repeat(64),
            768, EmbeddingProfile.DOCUMENT_RECIPE, EmbeddingProfile.QUERY_RECIPE, EmbeddingProfile.Options.defaults(), "cosine");
    private final EmbeddingGateway unused = new EmbeddingGateway() {
        public EmbeddingProfile resolveProfile() { throw new AssertionError("Unexpected provider call"); }
        public Response embed(EmbeddingProfile p, EmbeddingProfile.Input input) { throw new AssertionError("Unexpected inference"); }
    };
    @Test void rejectsInvalidRequestsBeforeDatabaseOrProviderAccess() {
        var service = new VectorRetrievalService(new DriverManagerDataSource(), unused);
        for (String id : new String[]{null, "", "latest", "x".repeat(64)})
            assertThrows(IllegalArgumentException.class, () -> service.search(id, "query", 5, PROFILE));
        for (String query : new String[]{null, "", " \n", "x".repeat(513), "\0", "\ud800"})
            assertThrows(IllegalArgumentException.class, () -> service.search("a".repeat(64), query, 5, PROFILE));
        for (int topK : new int[]{-1, 0, 101, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> service.search("a".repeat(64), "query", topK, PROFILE));
        assertThrows(IllegalArgumentException.class, () -> service.search("a".repeat(64), "query", 5, null));
    }
    @Test void databaseFailureIsCategorizedWithoutLeakingDriverDetails() {
        var unavailable = new DriverManagerDataSource() {
            @Override public Connection getConnection() throws SQLException { throw new SQLException("private connection details"); }
        };
        var error = assertThrows(VectorRetrievalException.class, () ->
                new VectorRetrievalService(unavailable, unused).search("a".repeat(64), "query", 5, PROFILE));
        assertEquals(VectorRetrievalException.Code.DATABASE_FAILURE, error.code());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("private"));
    }
}
