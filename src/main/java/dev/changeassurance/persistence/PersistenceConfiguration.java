package dev.changeassurance.persistence;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@Profile("persistence")
public class PersistenceConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    EvidencePersistenceService evidencePersistenceService(DataSource dataSource) {
        return new EvidencePersistenceService(dataSource);
    }
}
