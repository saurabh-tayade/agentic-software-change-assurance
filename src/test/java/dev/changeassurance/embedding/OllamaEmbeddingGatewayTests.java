package dev.changeassurance.embedding;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OllamaEmbeddingGatewayTests {
    private HttpServer server;
    private String endpoint;
    private final JsonMapper json = new JsonMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private String digest = "a".repeat(64);
    private String embedBody;
    private int embedStatus = 200;
    private boolean changeDigest;
    private long delay;
    private JsonNode sent;
    @BeforeEach void start() throws Exception {
        float[] vector = new float[768]; vector[0] = 1;
        embedBody = json.writeValueAsString(Map.of("model", "nomic-embed-text:v1.5", "embeddings", List.of(vector)));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            boolean tags = exchange.getRequestURI().getPath().equals("/api/tags");
            String body;
            if (tags) body = json.writeValueAsString(Map.of("models", List.of(Map.of("name", "nomic-embed-text:v1.5", "digest", digest))));
            else {
                sent = json.readTree(exchange.getRequestBody().readAllBytes());
                body = embedBody;
                if (changeDigest) digest = "b".repeat(64);
                try { if (delay > 0) Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            try {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(tags ? 200 : embedStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start(); endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }
    private OllamaEmbeddingGateway gateway(int timeout) {
        return new OllamaEmbeddingGateway(endpoint, "nomic-embed-text:v1.5", EmbeddingProfile.Options.defaults(), 1, timeout);
    }
    @Test void noStartupRequestsAndExactInputOptionsAreSentThroughSpringAi() {
        var gateway = gateway(5);
        assertEquals(0, calls.get());
        var p = gateway.resolveProfile();
        var input = p.document("source café\r\n");
        var result = gateway.embed(p, input);
        assertEquals(768, result.vector().length);
        assertEquals(input.text(), sent.path("input").get(0).asString());
        assertFalse(sent.path("truncate").asBoolean());
        assertEquals(2048, sent.path("options").path("num_ctx").asInt());
        assertEquals("0s", sent.path("keep_alive").asString());
        assertEquals(4, calls.get()); // resolve, pre-check, embed, post-check; no pull or retry
    }
    @Test void changedDigestBeforeCallFailsWithoutEmbedding() {
        var p = gateway(5).resolveProfile(); digest = "b".repeat(64);
        assertEquals(EmbeddingException.Code.PROFILE_MISMATCH,
                assertThrows(EmbeddingException.class, () -> gateway(5).embed(p, p.document("x"))).code());
        assertNull(sent);
    }
    @Test void queryInputIsSentUnchangedWithTheSameCompatibilityChecks() {
        var gateway = gateway(5);
        var p = gateway.resolveProfile();
        var input = p.query(" Where is café 😀?\r\n");
        assertEquals(768, gateway.embed(p, input).vector().length);
        assertEquals(input.text(), sent.path("input").get(0).asString());
        assertFalse(sent.path("truncate").asBoolean());
        assertEquals(4, calls.get());
        changeDigest = true;
        assertEquals(EmbeddingException.Code.PROFILE_MISMATCH,
                assertThrows(EmbeddingException.class, () -> gateway.embed(p, input)).code());
    }
    @Test void forgedQueryEnvelopeIsRejectedBeforeInference() {
        var p = EmbeddingProfileTests.profile();
        var input = p.query("question");
        for (var forged : List.of(new EmbeddingProfile.Input(input.text(), "0".repeat(64), input.bytes()),
                new EmbeddingProfile.Input(input.text(), input.hash(), input.bytes() + 1),
                new EmbeddingProfile.Input("unsupported: question", input.hash(), input.bytes()))) {
            assertEquals(EmbeddingException.Code.PROFILE_MISMATCH,
                    assertThrows(EmbeddingException.class, () -> gateway(5).embed(p, forged)).code());
        }
        assertNull(sent);
    }
    @Test void changedDigestDuringCallDiscardsOutput() {
        changeDigest = true; var p = EmbeddingProfileTests.profile();
        assertEquals(EmbeddingException.Code.PROFILE_MISMATCH,
                assertThrows(EmbeddingException.class, () -> gateway(5).embed(p, p.document("x"))).code());
    }
    @Test void missingDigestIsNotAcceptedAsModelIdentity() {
        digest = "";
        assertEquals(EmbeddingException.Code.INVALID_PROVIDER_RESPONSE, assertThrows(EmbeddingException.class, () -> gateway(5).resolveProfile()).code());
    }
    @Test void wrongDimensionsFail() {
        embedBody = "{\"model\":\"nomic-embed-text:v1.5\",\"embeddings\":[[1,2,3]]}";
        var p = EmbeddingProfileTests.profile();
        assertEquals(EmbeddingException.Code.INVALID_VECTOR, assertThrows(EmbeddingException.class, () -> gateway(5).embed(p, p.document("x"))).code());
    }
    @Test void inputRejectionIsExplicitAndDoesNotRetry() {
        embedStatus = 400; embedBody = "{\"error\":\"input exceeds context length\"}";
        var p = EmbeddingProfileTests.profile();
        assertEquals(EmbeddingException.Code.MODEL_INPUT_REJECTED, assertThrows(EmbeddingException.class, () -> gateway(5).embed(p, p.document("x"))).code());
        assertEquals(2, calls.get());
    }
    @Test void unavailableServerIsExplicit() {
        server.stop(0);
        assertEquals(EmbeddingException.Code.MODEL_UNAVAILABLE, assertThrows(EmbeddingException.class, () -> gateway(1).resolveProfile()).code());
    }
    @Test void timeoutIsExplicit() {
        delay = 1500; var p = EmbeddingProfileTests.profile();
        assertEquals(EmbeddingException.Code.MODEL_TIMEOUT, assertThrows(EmbeddingException.class, () -> gateway(1).embed(p, p.document("x"))).code());
    }
    @Test void malformedProviderOutputAndWrongModelFailExplicitly() {
        var p = EmbeddingProfileTests.profile();
        for (String body : List.of("not json", "{\"model\":\"nomic-embed-text:v1.5\",\"embeddings\":[]}",
                "{\"model\":\"other\",\"embeddings\":[[1]]}")) {
            embedBody = body;
            assertEquals(EmbeddingException.Code.INVALID_PROVIDER_RESPONSE,
                    assertThrows(EmbeddingException.class, () -> gateway(5).embed(p, p.document("x"))).code());
        }
    }
    @Test void forgedInputHashFailsBeforeGeneration() {
        var p = EmbeddingProfileTests.profile();
        var input = p.document("x");
        assertEquals(EmbeddingException.Code.PROFILE_MISMATCH,
                assertThrows(EmbeddingException.class, () -> gateway(5).embed(p,
                        new EmbeddingProfile.Input(input.text(), "0".repeat(64), input.bytes()))).code());
        assertNull(sent);
    }
    @Test void remoteEndpointsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new OllamaEmbeddingGateway("https://remote.example", "nomic-embed-text:v1.5", EmbeddingProfile.Options.defaults(), 1, 5));
    }
}
