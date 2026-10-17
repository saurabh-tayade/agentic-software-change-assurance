package dev.changeassurance.embedding;

import dev.changeassurance.persistence.EvidencePersistenceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
@Profile("embeddings")
public class EmbeddingConfiguration {
    @Bean
    EmbeddingGateway embeddingGateway(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("persistence")))
            throw new IllegalStateException("The embeddings profile requires persistence");
        return new OllamaEmbeddingGateway(environment.getProperty("embeddings.base-url", "http://localhost:11434"),
                environment.getProperty("embeddings.model", "nomic-embed-text:v1.5"),
                new EmbeddingProfile.Options(2048, environment.getProperty("embeddings.max-input-bytes", Integer.class, 8192), false, "0s"),
                environment.getProperty("embeddings.connect-timeout-seconds", Integer.class, 5),
                environment.getProperty("embeddings.timeout-seconds", Integer.class, 60));
    }
    @Bean
    EmbeddingIndexingService embeddingIndexingService(EvidencePersistenceService publications,
                                                     javax.sql.DataSource dataSource, EmbeddingGateway gateway) {
        return new EmbeddingIndexingService(publications, new EmbeddingStore(dataSource), gateway);
    }
}
