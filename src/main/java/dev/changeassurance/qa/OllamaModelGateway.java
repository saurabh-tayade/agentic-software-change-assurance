package dev.changeassurance.qa;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/** Native Ollama schema output; one bounded synchronous call, no tools or model downloads. */
public final class OllamaModelGateway implements ModelGateway {
    private final GenerationSettings settings;
    private final int connectTimeoutSeconds;
    private final OllamaChatModel model;

    public OllamaModelGateway(String baseUrl, int connectTimeoutSeconds, GenerationSettings settings) {
        URI endpoint;
        try {
            endpoint = URI.create(baseUrl);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid local Ollama URL", e);
        }
        if (!"http".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || !List.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost())
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))
                || endpoint.getPort() < -1 || endpoint.getPort() == 0 || endpoint.getPort() > 65535
                || connectTimeoutSeconds < 1 || connectTimeoutSeconds > 30 || settings == null) {
            throw new IllegalArgumentException("Q&A requires a local Ollama HTTP URL and bounded timeouts");
        }
        this.settings = settings;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(settings.timeoutSeconds()));
        var api = OllamaApi.builder().baseUrl(endpoint.toString())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory)).build();
        this.model = OllamaChatModel.builder().ollamaApi(api).options(options(QaPrompt.SCHEMA))
                .modelManagementOptions(ModelManagementOptions.builder().pullModelStrategy(PullModelStrategy.NEVER).build())
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0))).build();
    }

    @Override
    public GenerationSettings settings() { return settings; }

    @Override
    public Response generate(String systemPrompt, String contextJson, String outputSchema) {
        long started = System.nanoTime();
        ChatResponse response;
        try {
            response = model.call(new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(contextJson)),
                    options(outputSchema)));
        } catch (RuntimeException e) {
            // Spring's JDK factory closes a slow response body at its deadline;
            // parsing that closed stream may lose the original timeout exception.
            QaException.Code code = System.nanoTime() - started >= Duration.ofSeconds(settings.timeoutSeconds()).toNanos()
                    ? QaException.Code.MODEL_TIMEOUT : classify(e);
            if (code == QaException.Code.MALFORMED_MODEL_OUTPUT)
                throw QaException.malformed(QaException.MalformedReason.INVALID_PROVIDER_RESPONSE, e);
            throw new QaException(code, e);
        }
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || !response.getResult().getOutput().getToolCalls().isEmpty()) {
            throw QaException.malformed(QaException.MalformedReason.NO_MODEL_RESPONSE);
        }
        String finishReason = response.getResult().getMetadata().getFinishReason();
        // Spring AI 2.0.1 replaces missing raw usage with zero. Its synchronous Ollama
        // adapter exposes a finish reason only when BOTH raw usage counts were present.
        // Otherwise expose null, never Spring AI's synthesized zeros as measured usage.
        boolean measured = finishReason != null && !finishReason.isBlank();
        var usage = response.getMetadata().getUsage();
        Integer inputTokens = measured && usage != null ? usage.getPromptTokens() : null;
        Integer outputTokens = measured && usage != null ? usage.getCompletionTokens() : null;
        return new Response(response.getResult().getOutput().getText(), new Diagnostics("ollama", settings.model(),
                Duration.ofNanos(System.nanoTime() - started).toMillis(), inputTokens, outputTokens, finishReason,
                response.getMetadata().getModel(), connectTimeoutSeconds, false, "0s"));
    }

    private OllamaChatOptions options(String schema) {
        return OllamaChatOptions.builder().model(settings.model()).temperature(settings.temperature())
                .numCtx(settings.contextTokens()).numPredict(settings.maxOutputTokens()).disableThinking()
                .keepAlive("0s").outputSchema(schema).build();
    }

    private static QaException.Code classify(Throwable failure) {
        // Timeouts can be wrapped by RestClient and Spring's retry infrastructure.
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException) return QaException.Code.MODEL_TIMEOUT;
        }
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof HttpMessageNotReadableException || current instanceof JacksonException) {
                return QaException.Code.MALFORMED_MODEL_OUTPUT;
            }
        }
        return QaException.Code.MODEL_UNAVAILABLE;
    }
}
