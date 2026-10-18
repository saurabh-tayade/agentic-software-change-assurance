package dev.changeassurance.embedding;

import dev.changeassurance.persistence.EvidencePersistenceException;
import dev.changeassurance.persistence.PublicationIntegrityException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Explicit developer CLI; a partial result is printed but produces a nonzero process exit. */
@Component
@Profile("embeddings & !intelligence-api")
@ConditionalOnProperty(name = "embeddings.command")
public final class EmbeddingCliRunner implements ApplicationRunner {
    private final Environment environment;
    private final EmbeddingGateway gateway;
    private final EmbeddingIndexingService indexing;
    private final ConfigurableApplicationContext application;
    public EmbeddingCliRunner(Environment environment, EmbeddingGateway gateway, EmbeddingIndexingService indexing,
                              ConfigurableApplicationContext application) {
        this.environment = environment; this.gateway = gateway; this.indexing = indexing; this.application = application;
    }
    @Override
    public void run(ApplicationArguments args) {
        var json = new JsonMapper().writerWithDefaultPrettyPrinter();
        try {
            String command = required("embeddings.command");
            if (!command.equals("profile") && !command.equals("index")) throw new IllegalArgumentException("Unknown embeddings command");
            String publication = command.equals("index") ? required("embeddings.publication-id") : null;
            String expected = command.equals("index") ? required("embeddings.expected-digest") : null;
            if (expected != null && !expected.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected full model digest");
            var profile = gateway.resolveProfile();
            if (command.equals("profile")) {
                System.out.println(json.writeValueAsString(Map.of("profileId", profile.id(), "profile", profile)));
            } else {
                if (!profile.modelDigest().equals(expected)) throw new EmbeddingException(EmbeddingException.Code.PROFILE_MISMATCH);
                var report = indexing.index(publication, profile);
                System.out.println(json.writeValueAsString(report));
                if (report.status() != EmbeddingIndexingService.Status.COMPLETE)
                    throw new IllegalStateException("Embedding index incomplete; inspect the report and rerun after recovery");
            }
        } catch (EmbeddingException e) {
            System.err.println(json.writeValueAsString(Map.of("failure", e.code())));
            throw new EmbeddingException(e.code());
        } catch (PublicationIntegrityException e) {
            throw new IllegalStateException("Publication integrity verification failed");
        } catch (EvidencePersistenceException e) {
            throw new IllegalStateException("Evidence publication read failed");
        } finally { application.close(); }
    }
    private String required(String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}
