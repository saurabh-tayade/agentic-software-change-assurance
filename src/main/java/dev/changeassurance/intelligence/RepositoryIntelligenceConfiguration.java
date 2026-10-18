package dev.changeassurance.intelligence;

import dev.changeassurance.embedding.EmbeddingProfile;
import dev.changeassurance.embedding.EmbeddingStore;
import dev.changeassurance.persistence.EvidencePersistenceService;
import dev.changeassurance.persistence.PublicationIntegrity;
import dev.changeassurance.persistence.PublicationReadService;
import dev.changeassurance.qa.AnsweringConfiguration;
import dev.changeassurance.qa.ModelGateway;
import dev.changeassurance.retrieval.KeywordRetrievalService;
import dev.changeassurance.retrieval.VectorRetrievalService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

import javax.sql.DataSource;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@Profile("intelligence-api")
public class RepositoryIntelligenceConfiguration {
    /** Runs before ordinary beans (including datasource initializers) or ApplicationRunners. */
    @Bean
    static BeanFactoryPostProcessor apiStartupGuard(Environment env) {
        return beanFactory -> {
            if (!env.acceptsProfiles(Profiles.of("persistence & qa")))
                throw new IllegalStateException("The intelligence-api profile requires persistence and qa");
            for (String key : List.of("qa.command", "embeddings.command", "vector.command", "evaluation.command")) {
                if (env.containsProperty(key)) throw new IllegalStateException("API startup cannot include CLI command properties");
            }
            if (env.getProperty("spring.flyway.enabled", Boolean.class, true)
                    || !"never".equals(env.getProperty("spring.sql.init.mode"))
                    || !env.getProperty("spring.datasource.hikari.read-only", Boolean.class, false))
                throw new IllegalStateException("API startup requires disabled schema initialization and read-only connections");
            if (!"servlet".equals(env.getProperty("spring.main.web-application-type"))
                    || !List.of("127.0.0.1", "::1").contains(env.getProperty("server.address", ""))
                    || !"none".equals(env.getProperty("server.forward-headers-strategy")))
                throw new IllegalStateException("API startup requires a loopback servlet server without forwarded headers");
        };
    }

    @Bean
    PublicationReadService publicationReadService(DataSource dataSource) { return new PublicationReadService(dataSource); }

    @Bean
    RepositoryIntelligenceService repositoryIntelligenceService(PublicationReadService catalog,
            EvidencePersistenceService publications, KeywordRetrievalService keyword,
            ObjectProvider<VectorRetrievalService> vectors, DataSource dataSource, ModelGateway model, Environment env) {
        String profileId = env.getProperty("intelligence.api.embedding-profile-id", "");
        EmbeddingProfile profile = null;
        VectorRetrievalService vector = null;
        if (!profileId.isEmpty()) {
            PublicationIntegrity.validateId(profileId);
            vector = vectors.getIfAvailable();
            if (vector == null) throw new IllegalStateException("API embedding profile requires the embeddings profile");
            profile = new EmbeddingStore(dataSource).findProfile(profileId)
                    .orElseThrow(() -> new IllegalStateException("API embedding profile must already exist"));
        }
        var defaults = AnsweringConfiguration.defaults();
        var configuration = new AnsweringConfiguration(env.getProperty("qa.top-k", Integer.class, defaults.topK()),
                env.getProperty("qa.max-evidence", Integer.class, defaults.maxEvidence()),
                env.getProperty("qa.max-context-bytes", Integer.class, defaults.maxContextBytes()),
                env.getProperty("qa.max-evidence-bytes", Integer.class, defaults.maxEvidenceBytes()));
        return new RepositoryIntelligenceService(catalog, publications, keyword, vector, profile, model, configuration,
                env.getProperty("qa.log-malformed-model-response", Boolean.class, false));
    }

    @Bean
    JsonMapperBuilderCustomizer strictApiJson() {
        return builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .withCoercionConfig(LogicalType.Textual, coercion -> coercion
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }

    @Bean
    LocalApiFilter localApiFilter(Environment env) {
        return new LocalApiFilter(env.getProperty("intelligence.api.browser-origin", ""));
    }
}
