package dev.changeassurance.evaluation;

import dev.changeassurance.embedding.*;
import dev.changeassurance.persistence.*;
import dev.changeassurance.qa.*;
import dev.changeassurance.retrieval.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static dev.changeassurance.evaluation.RetrievalComparison.*;

/** Capture-only decorators; the production Q&A service still owns generation and validation. */
public final class QaEvaluationRunner {
    private final Function<String, Optional<PersistedPublication>> publications;
    private final Function<RetrievalStrategy, EvidenceRetriever> retrievers;
    private final ModelGateway model;
    public QaEvaluationRunner(Function<String, Optional<PersistedPublication>> publications,
                              Function<RetrievalStrategy, EvidenceRetriever> retrievers, ModelGateway model) {
        this.publications = publications; this.retrievers = retrievers; this.model = model;
    }
    public QaEvaluationRun execute(QaComparison d, List<String> caseIds, String publicationId, String profileId,
                                    EmbeddingProfile profile, RetrievalEvaluationRun.Application application,
                                    String mode, boolean captureRaw) {
        PublicationIntegrity.validateId(publicationId); if (profileId != null) PublicationIntegrity.validateId(profileId);
        require(Set.of("LIVE", "TEST_DOUBLE").contains(mode) && !caseIds.isEmpty() && new HashSet<>(caseIds).size() == caseIds.size());
        require(caseIds.stream().allMatch(id -> d.scenarios().stream().anyMatch(s -> s.id().equals(id))));
        require(d.promptVersion().equals(QaPrompt.VERSION) && d.answerVersion().equals(GroundedQuestionAnsweringService.VERSION)
                && d.contextPolicy().equals(EvidenceContextBuilder.POLICY));
        String started = Instant.now().toString(); var settings = model.settings();
        PersistedPublication publication = null; QaEvaluationRun.Failure preflight = null;
        try {
            publication = publications.apply(publicationId).orElseThrow(() -> new QaException(QaException.Code.UNKNOWN_PUBLICATION));
            require(publication.id().equals(publicationId));
            RetrievalEvaluationArtifacts.validatePublication(d.retrievalDefinition(), d.retrievalCase(d.scenarios().getFirst()), publication);
        } catch (RuntimeException e) { preflight = failure(e); }
        var executions = new ArrayList<QaEvaluationRun.Execution>();
        for (var strategy : d.strategies()) for (String id : caseIds) {
            var scenario = d.scenarios().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
            var capture = new Capture(model, captureRaw); GroundedAnswer answer = null;
            QaEvaluationRun.Failure error = preflight; long start = System.nanoTime();
            if (error == null) try {
                if (strategy != RetrievalStrategy.KEYWORD && (profile == null || !profile.id().equals(profileId)))
                    throw new QaException(QaException.Code.EMBEDDING_PROFILE_MISMATCH);
                var selected = retrievers.apply(strategy);
                if (selected == null) throw new QaException(QaException.Code.RETRIEVAL_NOT_CONFIGURED);
                EvidenceRetriever tracing = (p, q, k) -> {
                    long began = System.nanoTime();
                    try { var result = selected.search(p, q, k); capture.retrieval = result.orElse(null); return result; }
                    finally { capture.retrievalMs = elapsed(began); }
                };
                // The same method used by the CLI: no second retrieval, response repair, or retry.
                var actual = new GroundedQuestionAnsweringService(tracing, capture).answerWithRetrieval(publicationId, scenario.question(), d.answering());
                require(actual.retrieval().equals(capture.retrieval)); answer = actual.answer();
            } catch (RuntimeException e) { error = failure(e); }
            executions.add(new QaEvaluationRun.Execution(strategy + ":" + id, id, strategy, capture.retrieval, capture.input,
                    capture.input != null, capture.diagnostics, capture.raw, answer, capture.retrievalMs, capture.modelMs, elapsed(start), error));
        }
        return new QaEvaluationRun(1, "grounded-qa-execution", UUID.randomUUID().toString(), started,
                configurationId(d, caseIds, publicationId, profileId, settings, mode, captureRaw), d, caseIds, publicationId,
                publication, profileId, profile, application, mode, settings, null,
                "Chat gateway does not expose a resolved model digest; the configured tag is not immutable identity.", captureRaw, executions);
    }
    static String configurationId(QaComparison d, List<String> cases, String p, String profile, GenerationSettings g, String mode, boolean raw) {
        var fields = new LinkedHashMap<String, Object>(); fields.put("definition", d); fields.put("cases", cases);
        fields.put("publication", p); fields.put("profile", profile); fields.put("generation", g); fields.put("mode", mode); fields.put("captureRaw", raw);
        return hash(JSON.writeValueAsBytes(fields));
    }
    private static QaEvaluationRun.Failure failure(RuntimeException e) {
        if (e instanceof QaException q) return new QaEvaluationRun.Failure(q.code().name(), q.malformedReason() == null ? null : q.malformedReason().name(),
                q.getCause() instanceof VectorRetrievalException v ? v.coverage() : null);
        if (e instanceof RetrievalEvaluationRunner.EvaluationFailure f) return new QaEvaluationRun.Failure(f.code, null, null);
        String code = e instanceof PublicationIntegrityException ? "PUBLICATION_INTEGRITY_FAILURE"
                : e instanceof EvidencePersistenceException ? "PUBLICATION_READ_FAILURE" : "UNEXPECTED_EXECUTION_FAILURE";
        return new QaEvaluationRun.Failure(code, null, null); // Never retain nested SQL/provider messages.
    }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private static final class Capture implements ModelGateway {
        final ModelGateway delegate; final boolean enabled;
        RetrievalResult retrieval; Double retrievalMs, modelMs;
        QaEvaluationRun.ModelInput input; Diagnostics diagnostics;
        QaEvaluationRun.RawResponse raw = new QaEvaluationRun.RawResponse(false, null, null, null, false);
        Capture(ModelGateway delegate, boolean enabled) { this.delegate = delegate; this.enabled = enabled; }
        public GenerationSettings settings() { return delegate.settings(); }
        public Response generate(String prompt, String context, String schema) {
            input = new QaEvaluationRun.ModelInput(prompt, context, schema); long start = System.nanoTime();
            try {
                var response = delegate.generate(prompt, context, schema);
                if (response != null) { diagnostics = response.diagnostics(); raw = capture(response.json(), enabled); }
                return response;
            } finally { modelMs = elapsed(start); }
        }
    }
    static QaEvaluationRun.RawResponse capture(String response, boolean enabled) {
        if (response == null) return new QaEvaluationRun.RawResponse(false, null, null, null, false);
        int bytes = response.getBytes(StandardCharsets.UTF_8).length;
        String prefix = null; boolean truncated = false;
        if (enabled) {
            int end = 0, size = 0;
            while (end < response.length()) {
                int next = end + Character.charCount(response.codePointAt(end));
                int length = response.substring(end, next).getBytes(StandardCharsets.UTF_8).length;
                if (size + length > 8192) break;
                size += length; end = next;
            }
            prefix = response.substring(0, end); truncated = end != response.length();
        }
        return new QaEvaluationRun.RawResponse(true, response.length(), bytes, prefix, truncated);
    }
}
