package dev.changeassurance.retrieval;

import dev.changeassurance.embedding.EmbeddingException;
import dev.changeassurance.embedding.EmbeddingStore;
import dev.changeassurance.persistence.PublicationIntegrity;
import dev.changeassurance.persistence.PublicationIntegrityException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.Map;

/** Opt-in developer retrieval; no chat invocation, indexing, or public endpoint. */
@Component
@Profile("embeddings & !intelligence-api")
@ConditionalOnProperty(name = "vector.command")
public final class VectorRetrievalCliRunner implements ApplicationRunner {
    private final Environment environment;
    private final EmbeddingStore profiles;
    private final VectorRetrievalService retrieval;
    private final ConfigurableApplicationContext application;
    public VectorRetrievalCliRunner(Environment environment, DataSource dataSource, VectorRetrievalService retrieval,
                                    ConfigurableApplicationContext application) {
        this.environment = environment; this.profiles = new EmbeddingStore(dataSource);
        this.retrieval = retrieval; this.application = application;
    }
    @Override
    public void run(ApplicationArguments args) {
        var json = new JsonMapper().writerWithDefaultPrettyPrinter();
        try {
            if (!"search".equals(required("vector.command")) || environment.containsProperty("embeddings.command")
                    || environment.containsProperty("qa.command"))
                throw new IllegalArgumentException("Use one vector search command per invocation");
            String publicationId = required("vector.publication-id");
            String profileId = required("vector.profile-id");
            PublicationIntegrity.validateId(publicationId);
            if (!profileId.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected a full profile identity");
            String query = required("vector.query");
            int topK = environment.getProperty("vector.top-k", Integer.class, 5);
            var profile = profiles.findProfile(profileId)
                    .orElseThrow(() -> new EmbeddingException(EmbeddingException.Code.PROFILE_MISMATCH));
            var result = retrieval.search(publicationId, query, topK, profile);
            if (result.isEmpty()) fail("UNKNOWN_PUBLICATION", null);
            System.out.println(json.writeValueAsString(Map.of("embeddingProfileId", profile.id(),
                    "embeddingProfile", profile, "metric", "cosine", "result", result.orElseThrow())));
        } catch (VectorRetrievalException e) {
            fail(e.code().name(), e.coverage());
        } catch (EmbeddingException e) {
            fail(e.code().name(), null);
        } catch (PublicationIntegrityException e) {
            fail("PUBLICATION_INTEGRITY_FAILURE", null);
        } catch (IllegalArgumentException e) {
            fail("INVALID_REQUEST", null);
        } finally { application.close(); }
    }
    private static void fail(String code, VectorRetrievalException.Coverage coverage) {
        Object error = coverage == null ? Map.of("failure", code) : Map.of("failure", code, "coverage", coverage);
        System.err.println(new JsonMapper().writeValueAsString(error));
        // Keep source content, provider bodies, and nested SQL details out of failure output.
        throw new IllegalStateException("Vector retrieval failed: " + code);
    }
    private String required(String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}
