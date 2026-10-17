package dev.changeassurance.qa;

import dev.changeassurance.retrieval.KeywordRetrievalService;
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
    GroundedQuestionAnsweringService groundedQuestionAnsweringService(ModelGateway gateway,
                                                                      ObjectProvider<KeywordRetrievalService> retrieval,
                                                                      Environment environment) {
        KeywordRetrievalService service = retrieval.getIfAvailable();
        if (service == null) throw new IllegalStateException("Q&A requires the persistence keyword retrieval service");
        return new GroundedQuestionAnsweringService(service, gateway,
                environment.getProperty("qa.log-malformed-model-response", Boolean.class, false));
    }
}
