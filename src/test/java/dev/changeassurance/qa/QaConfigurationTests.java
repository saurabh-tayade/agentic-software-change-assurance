package dev.changeassurance.qa;

import dev.changeassurance.ChangeAssuranceApplication;
import dev.changeassurance.retrieval.KeywordRetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class QaConfigurationTests {
    @Test
    void defaultApplicationRemainsModelFree() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ChangeAssuranceApplication.class).run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(ModelGateway.class)
                            .doesNotHaveBean(OllamaChatModel.class).doesNotHaveBean(GroundedQuestionAnsweringService.class);
                });
    }

    @Test
    void persistenceOnlyDoesNotCreateAModel() {
        new ApplicationContextRunner().withUserConfiguration(QaConfiguration.class)
                .withPropertyValues("spring.profiles.active=persistence").run(context ->
                        assertThat(context).hasNotFailed().doesNotHaveBean(ModelGateway.class));
    }

    @Test
    void qaWithoutPersistenceFailsClearly() {
        new ApplicationContextRunner().withUserConfiguration(QaConfiguration.class)
                .withPropertyValues("spring.profiles.active=qa").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("qa profile requires the persistence profile");
                });
    }

    @Test
    void optedInConfigurationCreatesGatewayWithoutConnectingToOllama() {
        enabled().run(context -> assertThat(context).hasNotFailed().hasSingleBean(ModelGateway.class)
                .hasSingleBean(GroundedQuestionAnsweringService.class));
    }

    @Test
    void strategySelectionRejectsMissingProfilesUnknownStrategiesAndMissingVectorWiring() {
        for (String strategy : new String[]{"VECTOR", "HYBRID", "unknown"})
            enabled().withPropertyValues("qa.retrieval-strategy=" + strategy).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("INVALID_REQUEST");
            });
        for (String strategy : new String[]{"VECTOR", "HYBRID"})
            enabled().withPropertyValues("qa.retrieval-strategy=" + strategy, "qa.embedding-profile-id=" + "a".repeat(64))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasStackTraceContaining("RETRIEVAL_NOT_CONFIGURED");
                    });
        enabled().withPropertyValues("qa.embedding-profile-id=" + "a".repeat(64)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("INVALID_REQUEST");
        });
    }

    @Test
    void invalidModelConfigurationFailsDuringStartup() {
        for (String property : new String[]{"qa.model=", "qa.temperature=0.9", "qa.max-output-tokens=-1",
                "qa.context-tokens=512", "qa.timeout-seconds=0", "qa.base-url=https://remote.example"}) {
            enabled().withPropertyValues(property).run(context -> assertThat(context).hasFailed());
        }
    }

    private ApplicationContextRunner enabled() {
        return new ApplicationContextRunner().withUserConfiguration(QaConfiguration.class)
                .withPropertyValues("spring.profiles.active=qa,persistence", "qa.base-url=http://127.0.0.1:1")
                .withBean(KeywordRetrievalService.class, () -> new KeywordRetrievalService(new DriverManagerDataSource()));
    }
}
