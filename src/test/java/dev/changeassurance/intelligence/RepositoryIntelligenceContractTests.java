package dev.changeassurance.intelligence;

import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.persistence.PublicationMetadata;
import dev.changeassurance.persistence.PublicationSummary;
import dev.changeassurance.processing.ArtifactProcessor;
import dev.changeassurance.qa.*;
import dev.changeassurance.retrieval.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RepositoryIntelligenceContractTests {
    private static final String ID = "a".repeat(64);
    private static final String ANSWERS = "/api/v1/publications/" + ID + "/answers";
    private MockMvc mvc;
    private int retrievalCalls;
    private int modelCalls;
    private QaException failure;
    private RuntimeException retrievalFailure;
    private boolean empty;
    private String output;

    @BeforeEach void setup() {
        retrievalCalls = 0; modelCalls = 0; failure = null; retrievalFailure = null; empty = false;
        output = """
                {"status":"SUPPORTED","claims":[{"text":"Source says pricing.","evidenceKeys":["E1"]}],"uncertainties":[]}
                """;
        var model = new ModelGateway() {
            @Override public GenerationSettings settings() { return GenerationSettings.defaults(); }
            @Override public Response generate(String prompt, String context, String schema) {
                modelCalls++;
                if (failure != null) throw failure;
                return new Response(output, new Diagnostics("test-double", "test-model", 3, null, null, "stop"));
            }
        };
        var service = new RepositoryIntelligenceService(null, null, (id, query, topK) -> {
            retrievalCalls++;
            if (retrievalFailure != null) throw retrievalFailure;
            return java.util.Optional.of(retrieval(query, topK, empty));
        }, null, null, model, AnsweringConfiguration.defaults(), false);
        var builder = JsonMapper.builder();
        new RepositoryIntelligenceConfiguration().strictApiJson().customize(builder);
        mvc = MockMvcBuilders.standaloneSetup(new RepositoryIntelligenceController(service))
                .setControllerAdvice(new RepositoryIntelligenceExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(builder.build()))
                .addFilters(new LocalApiFilter("http://127.0.0.1:5173"))
                .defaultRequest(get("/").header("Host", "localhost")).build();
    }

    @Test void keywordUsesOneInvocationAndReturnsReferencesOmissionsAndExplicitSemantics() throws Exception {
        mvc.perform(post(ANSWERS).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"pricing\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.apiVersion").value("repository-intelligence-v1"))
                .andExpect(jsonPath("$.publication.publication.publicationId").value(ID))
                .andExpect(jsonPath("$.publication.publication.commitSha").value("b".repeat(40)))
                .andExpect(jsonPath("$.status").value("SUPPORTED"))
                .andExpect(jsonPath("$.requestedRetrievalStrategy").value("KEYWORD"))
                .andExpect(jsonPath("$.embeddingProfileId").isEmpty())
                .andExpect(jsonPath("$.retrievedEvidence.length()").value(8))
                .andExpect(jsonPath("$.retrievedEvidence[0].evidence.content").doesNotExist())
                .andExpect(jsonPath("$.citations[0].key").value("E1"))
                .andExpect(jsonPath("$.citations[0].evidence.publicationId").value(ID))
                .andExpect(jsonPath("$.contextSelection.omissions").isNotEmpty())
                .andExpect(jsonPath("$.uncertainties").isNotEmpty())
                .andExpect(jsonPath("$.invocation.provider").value("test-double"))
                .andExpect(jsonPath("$.invocation.inputTokens").isEmpty())
                .andExpect(jsonPath("$.validation").value("CITATION_IDENTITIES_VALIDATED_SEMANTIC_SUPPORT_NOT_VERIFIED"));
        assertEquals(1, retrievalCalls); assertEquals(1, modelCalls);
    }

    @Test void insufficientEvidenceIsASuccessWithoutModelInvocation() throws Exception {
        empty = true;
        mvc.perform(post(ANSWERS).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"unknown\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.citations").isEmpty()).andExpect(jsonPath("$.invocation").isEmpty());
        assertEquals(1, retrievalCalls); assertEquals(0, modelCalls);
    }

    @Test void modelProposedPartialAndInsufficientStatusesSurviveMapping() throws Exception {
        for (String answerStatus : List.of("PARTIALLY_SUPPORTED", "INSUFFICIENT_EVIDENCE")) {
            output = "{\"status\":\"" + answerStatus + "\",\"claims\":"
                    + (answerStatus.equals("INSUFFICIENT_EVIDENCE") ? "[]" : "[{\"text\":\"pricing\",\"evidenceKeys\":[\"E1\"]}]")
                    + ",\"uncertainties\":[\"Deployment is unknown.\"]}";
            mvc.perform(post(ANSWERS).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"pricing\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(answerStatus))
                    .andExpect(jsonPath("$.uncertainties[0]").value("Deployment is unknown."));
        }
    }

    @Test void invalidQuestionsAndStrictJsonFailBeforeRetrieval() throws Exception {
        var json = new JsonMapper();
        var bodies = new ArrayList<>(List.of("{}", "null", "[]", "{", "{\"question\":null}",
                "{\"question\":1}", "{\"question\":\"pricing\",\"topK\":1}",
                "{\"question\":\"pricing\",\"retrievalStrategy\":\"AUTO\"}",
                "{\"question\":\"pricing\",\"question\":\"other\"}", "{\"question\":\"pricing\"} {}",
                "{\"question\":\"\\ud800\"}"));
        for (String q : List.of("", "  ", "x".repeat(513), "nul\0", "😀".repeat(257)))
            bodies.add(json.writeValueAsString(java.util.Map.of("question", q)));
        for (String body : bodies) mvc.perform(post(ANSWERS).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(result -> assertEquals(400, result.getResponse().getStatus(), body))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertEquals(0, retrievalCalls); assertEquals(0, modelCalls);
    }

    @Test void acceptsTheExactUtf16QuestionLimit() throws Exception {
        empty = true;
        mvc.perform(post(ANSWERS).contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(java.util.Map.of("question", "😀".repeat(256)))))
                .andExpect(status().isOk());
    }

    @Test void configuredStrategiesAreNotReadinessAndUnavailableStrategiesNeverFallback() throws Exception {
        mvc.perform(get("/api/v1/capabilities")).andExpect(status().isOk())
                .andExpect(jsonPath("$.configuredRetrievalStrategies[0]").value("KEYWORD"))
                .andExpect(jsonPath("$.availability").value("CONFIGURED_NOT_RUNTIME_READINESS"));
        for (String strategy : List.of("VECTOR", "HYBRID")) mvc.perform(post(ANSWERS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"pricing\",\"retrievalStrategy\":\"" + strategy + "\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RETRIEVAL_NOT_CONFIGURED"));
        assertEquals(0, retrievalCalls);
    }

    @Test void allQaFailuresHaveStableSafeProblemResponses() throws Exception {
        for (var code : QaException.Code.values()) {
            failure = new QaException(code, new IllegalStateException("SECRET SQL /private/path provider-body"));
            int expected = switch (code) {
                case INVALID_REQUEST -> 400;
                case UNKNOWN_PUBLICATION -> 404;
                case EMBEDDING_PROFILE_MISMATCH, EMBEDDING_INDEX_INCOMPLETE -> 409;
                case CONTEXT_BUDGET_FAILURE, EMBEDDING_INPUT_REJECTED -> 422;
                case MALFORMED_MODEL_OUTPUT, INVALID_CITATION, EMBEDDING_INVALID_VECTOR, EMBEDDING_INVALID_RESPONSE -> 502;
                case RETRIEVAL_FAILURE, RETRIEVAL_NOT_CONFIGURED, MODEL_UNAVAILABLE, EMBEDDING_MODEL_UNAVAILABLE -> 503;
                case MODEL_TIMEOUT, EMBEDDING_MODEL_TIMEOUT -> 504;
                default -> 500;
            };
            var response = mvc.perform(post(ANSWERS).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"pricing\"}"))
                    .andExpect(status().is(expected)).andExpect(jsonPath("$.status").value(expected))
                    .andExpect(jsonPath("$.code").value(code.name())).andExpect(jsonPath("$.answer").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            assertFalse(response.contains("SECRET")); assertFalse(response.contains("provider-body"));
        }
    }

    @Test void generatedInvalidCitationsAndMalformedOutputFailClosed() throws Exception {
        for (String value : List.of("not json", "{\"status\":\"SUPPORTED\",\"claims\":[{\"text\":\"x\",\"evidenceKeys\":[\"E999\"]}],\"uncertainties\":[]}")) {
            output = value;
            mvc.perform(post(ANSWERS).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"pricing\"}"))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.answer").doesNotExist());
        }
    }

    @Test void unexpectedFailuresReturnOnlyASafeProblem() throws Exception {
        retrievalFailure = new IllegalStateException("SECRET SQL /private/path provider-body");
        var body = mvc.perform(post(ANSWERS).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"pricing\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("SECRET")); assertFalse(body.contains("/private/path"));
        assertEquals(0, modelCalls);
    }

    @Test void transportAndBrowserBoundariesFailSafely() throws Exception {
        mvc.perform(post(ANSWERS).contentType(MediaType.TEXT_PLAIN).content("pricing"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(post(ANSWERS).contentType(MediaType.APPLICATION_JSON).content("x".repeat(8193)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
        mvc.perform(delete("/api/v1/publications/" + ID)).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/v1/capabilities").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));
        mvc.perform(get("/api/v1/capabilities").header("Host", "evil.example"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOCAL_ACCESS_REQUIRED"));
        mvc.perform(get("/api/v1/capabilities").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/capabilities").header("Origin", "http://localhost"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/v1/capabilities").header("Origin", "http://127.0.0.1:5173"))
                .andExpect(status().isOk());
        assertEquals(0, retrievalCalls);
    }

    @Test void sizeLimitAlsoAppliesWithoutContentLength() throws Exception {
        var request = new MockHttpServletRequest("POST", "/custom-context" + ANSWERS) {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setLocalPort(80); request.addHeader("Host", "localhost"); request.setContent(new byte[8193]);
        var response = new MockHttpServletResponse();
        new LocalApiFilter("").doFilter(request, response, (r, s) -> fail("Oversized request reached controller"));
        assertEquals(413, response.getStatus());
    }

    private static RetrievalResult retrieval(String query, int topK, boolean empty) {
        var snapshot = new IngestionResult.Snapshot("http-fixture", "b".repeat(40), RepositorySelection.Scope.all(), "git-snapshot-v1");
        var sources = new ArrayList<IngestionResult.SourceArtifact>();
        String content = "# Pricing\n<script>alert('untrusted')</script>\n";
        String hash;
        try {
            hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
        for (int i = 0; i < 8; i++) sources.add(new IngestionResult.SourceArtifact(snapshot, "file-" + i + ".md",
                IngestionResult.ArtifactType.DOCUMENTATION, IngestionResult.Format.MARKDOWN, "c".repeat(40),
                hash, content));
        var input = new IngestionResult(snapshot, sources, List.of(), List.of("Committed only"));
        var p = new ArtifactProcessor().process(input);
        var metadata = new PublicationMetadata(sources.stream().map(IngestionResult.SourceArtifact::path).toList(),
                p.configuration(), p.status(), p.artifacts(), p.ingestionCoverage(), p.ingestionLimitations(),
                p.ingestionSummary(), p.processingLimitations());
        var matches = new ArrayList<RetrievalResult.Match>();
        if (!empty) for (var chunk : p.chunks()) matches.add(new RetrievalResult.Match(matches.size() + 1, 1, chunk));
        return new RetrievalResult(new PublicationSummary(ID, "e".repeat(64), "f".repeat(64), snapshot, metadata),
                query, topK, "postgres-keyword-v1", matches, List.of("Rank is not confidence."));
    }
}
