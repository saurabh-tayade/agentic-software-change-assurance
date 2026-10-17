package dev.changeassurance.retrieval;

import dev.changeassurance.embedding.EmbeddingGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@Profile("embeddings")
public class VectorRetrievalConfiguration {
    @Bean
    VectorRetrievalService vectorRetrievalService(DataSource dataSource, EmbeddingGateway gateway) {
        return new VectorRetrievalService(dataSource, gateway);
    }
}
