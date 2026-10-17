package dev.changeassurance.qa;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises actual Spring AI request/response mapping against a disposable loopback HTTP double. */
class OllamaModelGatewayTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private HttpServer server;
    private String endpoint;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private int responseStatus = 200;
    private String responseBody;
    private long responseDelayMillis;
    private long bodyDelayMillis;

    @BeforeEach
    void startServer() throws Exception {
        responseBody = envelope(42, 23);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            request.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            try {
                if (responseDelayMillis > 0) Thread.sleep(responseDelayMillis);
                byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, body.length);
                if (bodyDelayMillis > 0) Thread.sleep(bodyDelayMillis);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    @Test
    void sendsNativeSchemaSeparateRolesAndBoundedOptionsWithoutStartupRequests() {
        var gateway = new OllamaModelGateway(endpoint, 2, GenerationSettings.defaults());
        assertThat(requests).hasValue(0);
        ModelGateway.Response response = gateway.generate("governing instruction", "untrusted data", QaPrompt.SCHEMA);
        assertThat(requests).hasValue(1);
        JsonNode sent = request.get();
        assertThat(sent.path("model").asString()).isEqualTo("qwen3:4b-instruct");
        assertThat(sent.path("stream").asBoolean()).isFalse();
        assertThat(sent.path("format").path("type").asString()).isEqualTo("object");
        assertThat(sent.path("format").path("properties").path("claims").path("description").asString())
                .contains("INSUFFICIENT_EVIDENCE this must be empty");
        assertThat(sent.path("format").path("properties").path("uncertainties").path("description").asString())
                .contains("at least one explanation");
        assertThat(sent.path("options").path("temperature").asDouble()).isZero();
        assertThat(sent.path("options").path("num_ctx").asInt()).isEqualTo(8192);
        assertThat(sent.path("options").path("num_predict").asInt()).isEqualTo(768);
        assertThat(sent.path("think").asBoolean()).isFalse();
        assertThat(sent.path("keep_alive").asString()).isEqualTo("0s");
        assertThat(sent.path("tools").isMissingNode() || sent.path("tools").isEmpty()).isTrue();
        assertThat(sent.path("messages").get(0).path("role").asString()).isEqualTo("system");
        assertThat(sent.path("messages").get(1).path("role").asString()).isEqualTo("user");
        assertThat(response.json()).isEqualTo("{\"answer\":\"test\"}");
        assertThat(response.diagnostics().inputTokens()).isEqualTo(42);
        assertThat(response.diagnostics().outputTokens()).isEqualTo(23);
        assertThat(response.diagnostics().durationMillis()).isNotNegative();
        assertThat(response.diagnostics().finishReason()).isEqualTo("stop");
        assertThat(response.diagnostics().returnedModel()).isEqualTo("qwen3:4b-instruct");
        assertThat(response.diagnostics().connectTimeoutSeconds()).isEqualTo(2);
        assertThat(response.diagnostics().thinkingEnabled()).isFalse();
        assertThat(response.diagnostics().keepAlive()).isEqualTo("0s");
    }

    @Test
    void missingProviderUsageRemainsUnavailable() {
        responseBody = envelope(null, null);
        var response = new OllamaModelGateway(endpoint, 2, GenerationSettings.defaults())
                .generate("instructions", "data", QaPrompt.SCHEMA);
        assertThat(response.diagnostics().inputTokens()).isNull();
        assertThat(response.diagnostics().outputTokens()).isNull();
    }

    @Test
    void partiallyMissingProviderUsageRemainsUnavailable() {
        responseBody = envelope(42, null);
        var response = new OllamaModelGateway(endpoint, 2, GenerationSettings.defaults())
                .generate("instructions", "data", QaPrompt.SCHEMA);
        assertThat(response.diagnostics().inputTokens()).isNull();
        assertThat(response.diagnostics().outputTokens()).isNull();
    }

    @Test
    void unavailableProviderFailsOnceWithoutRetry() {
        responseStatus = 503;
        responseBody = "{\"error\":\"private provider details\"}";
        assertFailure(QaException.Code.MODEL_UNAVAILABLE, GenerationSettings.defaults());
        assertThat(requests).hasValue(1);
    }

    @Test
    void readTimeoutIsDistinctAndNotRetried() {
        responseDelayMillis = 1300;
        assertFailure(QaException.Code.MODEL_TIMEOUT, new GenerationSettings("qwen3:4b-instruct", 0, 8192, 768, 1));
        assertThat(requests).hasValue(1);
    }

    @Test
    void responseBodyIsAlsoBoundedByTheRequestTimeout() {
        bodyDelayMillis = 1300;
        long start = System.nanoTime();
        assertFailure(QaException.Code.MODEL_TIMEOUT, new GenerationSettings("qwen3:4b-instruct", 0, 8192, 768, 1));
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(3000);
        assertThat(requests).hasValue(1);
    }

    @Test
    void malformedProviderEnvelopeIsExplicit() {
        responseBody = "not JSON";
        assertFailure(QaException.Code.MALFORMED_MODEL_OUTPUT, GenerationSettings.defaults());
    }

    @Test
    void rejectsRemoteEndpointsCredentialsRedirectPathsAndInvalidTimeouts() {
        for (String url : new String[]{"https://example.com", "http://example.com", "http://localhost.evil.test",
                "http://user:password@localhost", "http://localhost/remote", "http://localhost?token=secret",
                "http://localhost:0", "file:///tmp/socket", "http:/bad"}) {
            assertThatThrownBy(() -> new OllamaModelGateway(url, 2, GenerationSettings.defaults()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new OllamaModelGateway(endpoint, 0, GenerationSettings.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).hasValue(0);
    }

    private void assertFailure(QaException.Code code, GenerationSettings settings) {
        assertThatThrownBy(() -> new OllamaModelGateway(endpoint, 2, settings).generate("instructions", "data", QaPrompt.SCHEMA))
                .isInstanceOfSatisfying(QaException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(code);
                    assertThat(failure.getMessage()).doesNotContain("private provider details");
                });
    }

    private static String envelope(Integer promptTokens, Integer generatedTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "qwen3:4b-instruct");
        body.put("created_at", "2026-10-16T12:00:00Z");
        body.put("message", Map.of("role", "assistant", "content", "{\"answer\":\"test\"}"));
        body.put("done", true);
        body.put("done_reason", "stop");
        if (promptTokens != null) body.put("prompt_eval_count", promptTokens);
        if (generatedTokens != null) body.put("eval_count", generatedTokens);
        return JSON.writeValueAsString(body);
    }
}
