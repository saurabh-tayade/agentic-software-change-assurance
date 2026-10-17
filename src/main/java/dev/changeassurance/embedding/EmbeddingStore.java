package dev.changeassurance.embedding;

import dev.changeassurance.persistence.PersistedPublication;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Insert-only derived records. Every write is a short independent transaction. */
public final class EmbeddingStore {
    private static final JsonMapper JSON = new JsonMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    public EmbeddingStore(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(30);
        jdbc.setFetchSize(128);
        write = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write.setTimeout(30);
        read = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
        read.setTimeout(30);
    }
    public void register(EmbeddingProfile profile) {
        database(() -> write.execute(status -> {
            jdbc.update("""
                    INSERT INTO embedding_profile VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (profile_id) DO NOTHING
                    """, profile.id(), profile.provider(), profile.model(), profile.modelDigest(), profile.dimensions(),
                    profile.documentRecipe(), profile.queryRecipe(), JSON.writeValueAsString(profile.options()), profile.distanceMetric());
            var stored = readProfile(jdbc, profile.id()).orElseThrow();
            if (!profile.equals(stored)) throw new EmbeddingException(EmbeddingException.Code.PROFILE_MISMATCH);
            return null;
        }));
    }
    /** Read-only lookup for explicit CLI profile selection. Never registers or repairs a profile. */
    public java.util.Optional<EmbeddingProfile> findProfile(String profileId) {
        return database(() -> read.execute(status -> readProfile(jdbc, profileId)));
    }
    /** Caller supplies the transaction so retrieval verifies profile and candidates in one snapshot. */
    public static java.util.Optional<EmbeddingProfile> readProfile(JdbcTemplate jdbc, String profileId) {
        if (profileId == null || !profileId.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Expected a full embedding profile SHA-256 identity");
        try {
            var profiles = jdbc.query("SELECT * FROM embedding_profile WHERE profile_id = ?", (r, n) ->
                    new EmbeddingProfile(r.getString("provider"), r.getString("model"), r.getString("model_digest"),
                            r.getInt("dimensions"), r.getString("document_recipe"), r.getString("query_recipe"),
                            JSON.readValue(r.getString("options"), EmbeddingProfile.Options.class), r.getString("distance_metric")), profileId);
            if (profiles.isEmpty()) return java.util.Optional.empty();
            var profile = profiles.getFirst();
            if (!profileId.equals(profile.id())) throw new EmbeddingException(EmbeddingException.Code.PROFILE_MISMATCH);
            return java.util.Optional.of(profile);
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException e) {
            throw new EmbeddingException(EmbeddingException.Code.PROFILE_MISMATCH);
        }
    }
    /** Validate vectors while streaming; retain only chunk IDs and input hashes in memory. */
    public Map<String, String> existing(PersistedPublication publication, EmbeddingProfile profile) {
        return database(() -> read.execute(status -> {
            var result = new HashMap<String, String>();
            jdbc.query("""
                    SELECT e.chunk_id, e.input_hash, e.vector_hash, e.embedding::text AS vector
                    FROM chunk_embedding e JOIN publication_chunk m
                    ON m.snapshot_id = e.snapshot_id AND m.chunk_id = e.chunk_id
                    WHERE m.publication_id = ? AND e.profile_id = ?
                    """, row -> {
                verifyVector(row.getString("vector"), row.getString("vector_hash"), profile);
                result.put(row.getString("chunk_id"), row.getString("input_hash"));
            }, publication.id(), profile.id());
            return result;
        }));
    }
    public boolean insert(PersistedPublication publication, String chunkId, EmbeddingProfile profile,
                          EmbeddingProfile.Input input, float[] vector) {
        EmbeddingVector.validate(vector, profile.dimensions());
        return database(() -> write.execute(status -> {
            int added = jdbc.update("""
                    INSERT INTO chunk_embedding (snapshot_id, chunk_id, profile_id, input_hash, vector_hash, embedding)
                    SELECT snapshot_id, chunk_id, ?, ?, ?, ?::vector FROM publication_chunk
                    WHERE publication_id = ? AND chunk_id = ?
                    ON CONFLICT (snapshot_id, chunk_id, profile_id) DO NOTHING
                    """, profile.id(), input.hash(), EmbeddingVector.hash(vector), EmbeddingVector.sql(vector), publication.id(), chunkId);
            var hashes = jdbc.query("""
                    SELECT input_hash, vector_hash, embedding::text AS vector FROM chunk_embedding
                    WHERE snapshot_id = ? AND chunk_id = ? AND profile_id = ?
                    """, (row, n) -> {
                verifyVector(row.getString("vector"), row.getString("vector_hash"), profile);
                return row.getString("input_hash");
            }, publication.snapshotId(), chunkId, profile.id());
            if (hashes.size() != 1 || !input.hash().equals(hashes.getFirst()))
                throw new EmbeddingException(EmbeddingException.Code.INDEX_INTEGRITY_FAILURE);
            return added == 1;
        }));
    }
    private static void verifyVector(String text, String hash, EmbeddingProfile profile) {
        EmbeddingVector.verifyStored(text, hash, profile.dimensions());
    }

    private static <T> T database(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException | TransactionException e) { throw new EmbeddingException(EmbeddingException.Code.PERSISTENCE_FAILURE); }
        catch (IllegalArgumentException | tools.jackson.core.JacksonException e) {
            throw new EmbeddingException(EmbeddingException.Code.INDEX_INTEGRITY_FAILURE);
        }
    }
}
