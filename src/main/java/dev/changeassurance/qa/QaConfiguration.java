package dev.changeassurance.qa;

import dev.changeassurance.retrieval.KeywordRetrievalService;
import dev.changeassurance.retrieval.EvidenceRetrievers;
import dev.changeassurance.retrieval.RetrievalStrategy;
import dev.changeassurance.retrieval.VectorRetrievalService;
import dev.changeassurance.embedding.EmbeddingException;
import dev.changeassurance.embedding.EmbeddingProfile;
import dev.changeassurance.embedding.EmbeddingStore;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
@Profile("qa")
public class QaConfiguration {
    @Bean
    ModelGateway modelGateway(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("persistence"))) {
            throw new IllegalStateException("The qa profile requires the persistence profile and an existing publication");
        }
        var defaults = GenerationSettings.defaults();
        var settings = new GenerationSettings(environment.getProperty("qa.model", defaults.model()),
                environment.getProperty("qa.temperature", Double.class, defaults.temperature()),
                environment.getProperty("qa.context-tokens", Integer.class, defaults.contextTokens()),
                environment.getProperty("qa.max-output-tokens", Integer.class, defaults.maxOutputTokens()),
                environment.getProperty("qa.timeout-seconds", Integer.class, defaults.timeoutSeconds()));
        return new OllamaModelGateway(environment.getProperty("qa.base-url", "http://localhost:11434"),
                environment.getProperty("qa.connect-timeout-seconds", Integer.class, 5), settings);
    }

    @Bean
    @Profile("!intelligence-api")
    GroundedQuestionAnsweringService groundedQuestionAnsweringService(ModelGateway gateway,
                                                                      ObjectProvider<KeywordRetrievalService> retrieval,
                                                                      ObjectProvider<VectorRetrievalService> vector,
                                                                      ObjectProvider<DataSource> dataSource,
                                                                      Environment environment) {
        KeywordRetrievalService service = retrieval.getIfAvailable();
        if (service == null) throw new IllegalStateException("Q&A requires the persistence keyword retrieval service");
        var strategy = strategy(environment);
        String profileId = environment.getProperty("qa.embedding-profile-id", "");
        EmbeddingProfile profile = null;
        VectorRetrievalService vectorService = null;
        if (strategy == RetrievalStrategy.KEYWORD) {
            if (!profileId.isBlank()) throw new QaException(QaException.Code.INVALID_REQUEST);
        } else {
            if (!profileId.matches("[0-9a-f]{64}")) throw new QaException(QaException.Code.INVALID_REQUEST);
            vectorService = vector.getIfAvailable();
            if (vectorService == null || dataSource.getIfAvailable() == null)
                throw new QaException(QaException.Code.RETRIEVAL_NOT_CONFIGURED);
            try {
                profile = new EmbeddingStore(dataSource.getObject()).findProfile(profileId)
                        .orElseThrow(() -> new QaException(QaException.Code.EMBEDDING_PROFILE_MISMATCH));
            } catch (EmbeddingException failure) { throw QaException.embeddingFailure(failure); }
        }
        return new GroundedQuestionAnsweringService(EvidenceRetrievers.select(strategy, service, vectorService, profile), gateway,
                environment.getProperty("qa.log-malformed-model-response", Boolean.class, false));
    }
    static RetrievalStrategy strategy(Environment environment) {
        try { return RetrievalStrategy.valueOf(environment.getProperty("qa.retrieval-strategy", "KEYWORD")); }
        catch (IllegalArgumentException failure) { throw new QaException(QaException.Code.INVALID_REQUEST); }
    }
}
