package dev.changeassurance.intelligence;

import dev.changeassurance.ChangeAssuranceApplication;
import dev.changeassurance.embedding.EmbeddingCliRunner;
import dev.changeassurance.embedding.EmbeddingIndexingService;
import dev.changeassurance.evaluation.RetrievalEvaluationCliRunner;
import dev.changeassurance.qa.GroundedQuestionAnsweringService;
import dev.changeassurance.qa.ModelGateway;
import dev.changeassurance.qa.QaCliRunner;
import dev.changeassurance.retrieval.VectorRetrievalCliRunner;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryIntelligenceConfigurationTests {
    private ApplicationContextRunner runner(String profiles) {
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ChangeAssuranceApplication.class)
                .withPropertyValues("spring.profiles.active=" + profiles, "intelligence.api.embedding-profile-id=",
                        "qa.base-url=http://127.0.0.1:1", "embeddings.base-url=http://127.0.0.1:1")
                .withBean(DataSource.class, DriverManagerDataSource::new);
    }

    @Test void apiProfileReusesGatewaysWithoutMigrationOrCliBeansRegardlessOfProfileOrder() {
        for (String profiles : new String[]{"intelligence-api", "intelligence-api,embeddings,persistence,qa",
                "persistence,qa,embeddings,intelligence-api"}) {
            runner(profiles).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(ModelGateway.class)
                        .hasSingleBean(RepositoryIntelligenceService.class).doesNotHaveBean(Flyway.class)
                        .doesNotHaveBean(QaCliRunner.class).doesNotHaveBean(EmbeddingCliRunner.class)
                        .doesNotHaveBean(VectorRetrievalCliRunner.class).doesNotHaveBean(RetrievalEvaluationCliRunner.class)
                        .doesNotHaveBean(EmbeddingIndexingService.class).doesNotHaveBean(GroundedQuestionAnsweringService.class);
                assertThat(context.getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("servlet");
                assertThat(context.getBean(RepositoryIntelligenceService.class).capabilities().configuredRetrievalStrategies())
                        .containsExactly("KEYWORD");
            });
        }
    }

    @Test void commandPropertiesFailBeforeAnyDatabaseOrModelOperation() {
        for (String command : new String[]{"qa.command", "embeddings.command", "vector.command", "evaluation.command"}) {
            for (String value : new String[]{"publish", ""}) {
                runner("intelligence-api,embeddings").withPropertyValues(command + "=" + value).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("API startup cannot include CLI command properties");
                });
            }
        }
    }

    @Test void unsafeStartupSettingsAreRejectedBeforeInitializers() {
        for (String setting : new String[]{"spring.flyway.enabled=true", "spring.sql.init.mode=always",
                "spring.datasource.hikari.read-only=false", "server.address=0.0.0.0", "server.address=example.com",
                "server.forward-headers-strategy=framework", "spring.main.web-application-type=none"}) {
            runner("intelligence-api").withPropertyValues(setting).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("API startup requires");
            });
        }
    }

    @Test void invalidProfileAndBrowserOriginConfigurationFailClearly() {
        for (String setting : new String[]{"intelligence.api.embedding-profile-id=../file",
                "intelligence.api.embedding-profile-id=" + "a".repeat(64),
                "intelligence.api.browser-origin=*", "intelligence.api.browser-origin=https://remote.example"}) {
            runner("intelligence-api").withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test void cliProfilesKeepTheirNonWebConfiguration() {
        // Only load config: this test checks original profile semantics without a database dependency.
        for (String profile : new String[]{"qa", "embeddings"}) {
            new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                    .withPropertyValues("spring.profiles.active=" + profile).run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("none");
                    });
        }
    }
}
