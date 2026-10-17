package dev.changeassurance.embedding;

import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

/** Spring AI adapter with explicit identity checks; construction never contacts Ollama. */
public final class OllamaEmbeddingGateway implements EmbeddingGateway {
    private final OllamaApi api;
    private final OllamaEmbeddingModel embedding;
    private final String model;
    private final EmbeddingProfile.Options options;
    private final int timeoutSeconds;

    public OllamaEmbeddingGateway(String baseUrl, String model, EmbeddingProfile.Options options,
                                  int connectTimeoutSeconds, int timeoutSeconds) {
        var uri = URI.create(baseUrl);
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null || !List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())) || uri.getPort() == 0 || uri.getPort() > 65535
                || model == null || !model.matches("nomic-embed-text:[A-Za-z0-9._-]+") || options == null
                || connectTimeoutSeconds < 1 || connectTimeoutSeconds > 30 || timeoutSeconds < 1 || timeoutSeconds > 300)
            throw new IllegalArgumentException("Expected local Ollama, a Nomic tag, and bounded timeouts");
        this.model = model; this.options = options; this.timeoutSeconds = timeoutSeconds;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        api = OllamaApi.builder().baseUrl(uri.toString())
                .responseErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler()).restClientBuilder(RestClient.builder().requestFactory(factory)).build();
        embedding = OllamaEmbeddingModel.builder().ollamaApi(api)
                .options(OllamaEmbeddingOptions.builder().model(model).numCtx(options.contextTokens())
                        .truncate(false).keepAlive(options.keepAlive()).build())
                .modelManagementOptions(ModelManagementOptions.builder().pullModelStrategy(PullModelStrategy.NEVER).build()).build();
    }

    @Override
    public EmbeddingProfile resolveProfile() {
        long start = System.nanoTime();
        try {
            var response = api.listModels();
            if (response == null || response.models() == null) throw failure(EmbeddingException.Code.INVALID_PROVIDER_RESPONSE);
            var matches = response.models().stream().filter(m -> model.equals(m.name())).toList();
            if (matches.isEmpty()) throw failure(EmbeddingException.Code.MODEL_UNAVAILABLE);
            if (matches.size() != 1) throw failure(EmbeddingException.Code.INVALID_PROVIDER_RESPONSE);
            String digest = matches.getFirst().digest();
            if (digest != null && digest.startsWith("sha256:")) digest = digest.substring(7);
            if (digest == null || !digest.matches("[0-9a-f]{64}")) throw failure(EmbeddingException.Code.INVALID_PROVIDER_RESPONSE);
            return new EmbeddingProfile("ollama", model, digest, 768, EmbeddingProfile.DOCUMENT_RECIPE,
                    EmbeddingProfile.QUERY_RECIPE, options, "cosine");
        } catch (EmbeddingException e) { throw e; }
        catch (RuntimeException e) { throw classify(e, start); }
    }

    @Override
    public Response embed(EmbeddingProfile expected, EmbeddingProfile.Input input) {
        if (!resolveProfile().equals(expected)) throw failure(EmbeddingException.Code.PROFILE_MISMATCH);
        // Recompute the input envelope; callers cannot bypass the byte bound/hash contract.
        if (input == null || !input.text().startsWith("search_document: ")
                || !expected.document(input.text().substring("search_document: ".length())).equals(input))
            throw failure(EmbeddingException.Code.PROFILE_MISMATCH);
        long start = System.nanoTime();
        try {
            var response = embedding.call(new EmbeddingRequest(List.of(input.text()), null));
            if (response == null || response.getResults().size() != 1
                    || !model.equals(response.getMetadata().getModel())) throw failure(EmbeddingException.Code.INVALID_PROVIDER_RESPONSE);
            float[] vector = response.getResult().getOutput();
            EmbeddingVector.validate(vector, expected.dimensions());
            // Do not persist a response if the mutable tag changed during the request.
            if (!resolveProfile().equals(expected)) throw failure(EmbeddingException.Code.PROFILE_MISMATCH);
            return new Response(vector, (System.nanoTime() - start) / 1_000_000);
        } catch (EmbeddingException e) { throw e; }
        catch (RuntimeException e) { throw classify(e, start); }
    }

    private EmbeddingException classify(RuntimeException e, long start) {
        if (System.nanoTime() - start >= Duration.ofSeconds(timeoutSeconds).toNanos()) return failure(EmbeddingException.Code.MODEL_TIMEOUT);
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException)
                return failure(EmbeddingException.Code.MODEL_TIMEOUT);
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException response && response.getStatusCode().value() == 400)
                return failure(EmbeddingException.Code.MODEL_INPUT_REJECTED);
            if (cause instanceof org.springframework.http.converter.HttpMessageNotReadableException
                    || cause instanceof tools.jackson.core.JacksonException || cause instanceof NullPointerException)
                return failure(EmbeddingException.Code.INVALID_PROVIDER_RESPONSE);
        }
        return failure(EmbeddingException.Code.MODEL_UNAVAILABLE);
    }
    private static EmbeddingException failure(EmbeddingException.Code code) { return new EmbeddingException(code); }
}
