package dev.changeassurance.embedding;

import dev.changeassurance.ChangeAssuranceApplication;
import dev.changeassurance.persistence.EvidencePersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingConfigurationTests {
    @Test void defaultStartupHasNoEmbeddingGateway() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ChangeAssuranceApplication.class).run(c ->
                        assertThat(c).hasNotFailed().doesNotHaveBean(EmbeddingGateway.class));
    }
    @Test void persistenceOnlyHasNoEmbeddingGateway() {
        new ApplicationContextRunner().withUserConfiguration(EmbeddingConfiguration.class)
                .withPropertyValues("spring.profiles.active=persistence").run(c ->
                        assertThat(c).hasNotFailed().doesNotHaveBean(EmbeddingGateway.class));
    }
    @Test void missingPersistenceFailsClearly() {
        new ApplicationContextRunner().withUserConfiguration(EmbeddingConfiguration.class)
                .withPropertyValues("spring.profiles.active=embeddings").run(c -> {
                    assertThat(c).hasFailed();
                    assertThat(c.getStartupFailure()).hasStackTraceContaining("embeddings profile requires persistence");
                });
    }
    @Test void optedInStartupDoesNotContactOllama() {
        enabled().run(c -> assertThat(c).hasNotFailed().hasSingleBean(EmbeddingGateway.class));
    }
    @Test void invalidConfigurationFailsEarly() {
        for (String property : new String[]{"embeddings.max-input-bytes=10000", "embeddings.timeout-seconds=0", "embeddings.model=other:latest"})
            enabled().withPropertyValues(property).run(c -> assertThat(c).hasFailed());
    }
    private ApplicationContextRunner enabled() {
        var ds = new DriverManagerDataSource();
        return new ApplicationContextRunner().withUserConfiguration(EmbeddingConfiguration.class)
                .withPropertyValues("spring.profiles.active=persistence,embeddings", "embeddings.base-url=http://127.0.0.1:1")
                .withBean(DataSource.class, () -> ds).withBean(EvidencePersistenceService.class, () -> new EvidencePersistenceService(ds));
    }
}
