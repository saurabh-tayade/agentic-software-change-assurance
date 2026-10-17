package dev.changeassurance.retrieval;

import dev.changeassurance.ChangeAssuranceApplication;
import dev.changeassurance.embedding.EmbeddingConfiguration;
import dev.changeassurance.embedding.EmbeddingGateway;
import dev.changeassurance.persistence.EvidencePersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

class VectorRetrievalConfigurationTests {
    @Test void defaultStartupHasNoVectorRetrievalOrModelGateway() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ChangeAssuranceApplication.class).run(c -> assertThat(c).hasNotFailed()
                        .doesNotHaveBean(VectorRetrievalService.class).doesNotHaveBean(EmbeddingGateway.class)
                        .doesNotHaveBean(VectorRetrievalCliRunner.class));
    }
    @Test void persistenceOnlyDoesNotActivateVectorRetrieval() {
        new ApplicationContextRunner().withUserConfiguration(VectorRetrievalConfiguration.class, EmbeddingConfiguration.class,
                        VectorRetrievalCliRunner.class)
                .withPropertyValues("spring.profiles.active=persistence").run(c -> assertThat(c).hasNotFailed()
                        .doesNotHaveBean(VectorRetrievalService.class).doesNotHaveBean(VectorRetrievalCliRunner.class));
    }
    @Test void optedInWiringNeedsNoDatabaseConnectionOrOllamaUntilInvoked() {
        var ds = new DriverManagerDataSource();
        new ApplicationContextRunner().withUserConfiguration(VectorRetrievalConfiguration.class, EmbeddingConfiguration.class,
                        VectorRetrievalCliRunner.class)
                .withPropertyValues("spring.profiles.active=persistence,embeddings", "embeddings.base-url=http://127.0.0.1:1",
                        "vector.command=search")
                .withBean(DataSource.class, () -> ds).withBean(EvidencePersistenceService.class, () -> new EvidencePersistenceService(ds))
                .run(c -> assertThat(c).hasNotFailed().hasSingleBean(VectorRetrievalService.class)
                        .hasSingleBean(VectorRetrievalCliRunner.class));
    }
}
