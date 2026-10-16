package dev.changeassurance.persistence;

import dev.changeassurance.ChangeAssuranceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ChangeAssuranceApplication.class);

    @Test
    void defaultApplicationDoesNotCreateDatabaseBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(DataSource.class)
                    .doesNotHaveBean(EvidencePersistenceService.class);
        });
    }

    @Test
    void persistenceProfileFailsClearlyWithMissingConfiguration() {
        runner.withPropertyValues("spring.profiles.active=persistence", "EVIDENCE_DB_URL=",
                "EVIDENCE_DB_USER=", "EVIDENCE_DB_PASSWORD=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("Failed to determine a suitable driver class");
        });
    }
}
