package dev.changeassurance.intelligence;

import dev.changeassurance.persistence.PublicationSummary;
import dev.changeassurance.processing.EvidenceChunk;
import dev.changeassurance.qa.AnsweringConfiguration;
import dev.changeassurance.qa.GenerationSettings;
import dev.changeassurance.qa.GroundedQuestionAnsweringService.AnswerRun;
import dev.changeassurance.qa.ModelGateway;

import java.util.List;

/** Explicit v1 wire records. Persistence/provider objects never cross the HTTP boundary. */
public final class RepositoryIntelligenceDtos {
    private RepositoryIntelligenceDtos() { }
    public static final String VERSION = "repository-intelligence-v1";

    public record AnswerRequest(String question, String retrievalStrategy) { }
    public record AnsweringSettings(int topK, int maxEvidence, int maxContextBytes, int maxEvidenceBytes) {
        static AnsweringSettings from(AnsweringConfiguration c) {
            return new AnsweringSettings(c.topK(), c.maxEvidence(), c.maxContextBytes(), c.maxEvidenceBytes());
        }
    }
    public record ModelSettings(String model, double temperature, int contextTokens,
                                int maxOutputTokens, int timeoutSeconds) {
        static ModelSettings from(GenerationSettings s) {
            return new ModelSettings(s.model(), s.temperature(), s.contextTokens(), s.maxOutputTokens(), s.timeoutSeconds());
        }
    }
    public record CapabilitiesResponse(String apiVersion, List<String> configuredRetrievalStrategies,
                                       String defaultRetrievalStrategy, String embeddingProfileId,
                                       int questionLengthLimit, AnsweringSettings answeringConfiguration,
                                       ModelSettings generationSettings, String availability) { }
    public record Scope(List<String> includes, List<String> excludes) { }
    public record ProcessingConfiguration(String identity, int maxLines, int maxChunks) { }
    public record PublicationReference(String publicationId, String snapshotId, String derivationId,
                                       String repositoryId, String commitSha, Scope scope, String policyVersion,
                                       ProcessingConfiguration processingConfiguration, String processingStatus) {
        static PublicationReference from(PublicationSummary p) {
            var s = p.snapshot();
            var c = p.metadata().configuration();
            return new PublicationReference(p.id(), p.snapshotId(), p.derivationId(), s.repositoryId(), s.commitSha(),
                    new Scope(s.scope().includes(), s.scope().excludes()), s.policyVersion(),
                    new ProcessingConfiguration(c.identity(), c.maxLines(), c.maxChunks()), p.metadata().status().name());
        }
    }
    public record PublicationListResponse(String apiVersion, List<PublicationReference> items, String nextAfter) { }
    public record CoverageSummary(int discovered, int ingested, int excluded, int unsupported, int failed,
                                  int artifacts, int parsed, int completeArtifacts, int partialArtifacts,
                                  int failedArtifacts, int chunks) { }
    public record CoverageEntry(String path, String disposition, String reason, String detail) { }
    public record Limitation(String reason, String detail) { }
    public record ArtifactCoverage(String path, String parser, String status, boolean parsed,
                                   int chunkCount, List<Limitation> limitations) { }
    public record PublicationDetailResponse(String apiVersion, PublicationReference publication,
                                             CoverageSummary coverage, List<CoverageEntry> ingestionCoverage,
                                             List<ArtifactCoverage> artifacts, List<String> ingestionLimitations,
                                             List<String> processingLimitations) {
        static PublicationDetailResponse from(PublicationSummary p) {
            var m = p.metadata();
            var s = m.ingestionSummary();
            var a = m.artifacts();
            return new PublicationDetailResponse(VERSION, PublicationReference.from(p),
                    new CoverageSummary(s.discovered(), s.ingested(), s.excluded(), s.unsupported(), s.failed(),
                            a.size(), (int) a.stream().filter(x -> x.parsed()).count(),
                            (int) a.stream().filter(x -> x.status().name().equals("COMPLETE")).count(),
                            (int) a.stream().filter(x -> x.status().name().equals("PARTIAL")).count(),
                            (int) a.stream().filter(x -> x.status().name().equals("FAILED")).count(),
                            a.stream().mapToInt(x -> x.chunkCount()).sum()),
                    m.ingestionCoverage().stream().map(x -> new CoverageEntry(x.path(), x.disposition().name(),
                            x.reason().name(), x.detail())).toList(),
                    a.stream().map(x -> new ArtifactCoverage(x.path(), x.parser(), x.status().name(), x.parsed(),
                            x.chunkCount(), x.limitations().stream().map(l -> new Limitation(l.reason().name(), l.detail())).toList())).toList(),
                    m.ingestionLimitations(), m.processingLimitations());
        }
    }
    public record EvidenceReference(String publicationId, String snapshotId, String chunkId, String repositoryId,
                                     String commitSha, String path, int startLine, int endLine,
                                     String artifactType, String sourceFormat, String gitBlobId,
                                     String sourceHash, String contentHash, String parser, String chunkingConfiguration) {
        static EvidenceReference from(String publicationId, String snapshotId, EvidenceChunk c) {
            return new EvidenceReference(publicationId, snapshotId, c.id(), c.snapshot().repositoryId(),
                    c.snapshot().commitSha(), c.filePath(), c.startLine(), c.endLine(), c.artifactType(), c.sourceFormat(),
                    c.gitBlobId(), c.sourceHash(), c.contentHash(), c.parser(), c.chunkingConfiguration());
        }
    }
    public record EvidenceResponse(String apiVersion, EvidenceReference evidence, String content,
                                    List<String> structuralContext) { }
    public record Claim(String text, List<String> evidenceKeys) { }
    public record Citation(String key, EvidenceReference evidence) { }
    public record RetrievedEvidence(int rank, double score, EvidenceReference evidence) { }
    public record SelectedEvidence(String key, int rank, String chunkId) { }
    public record Omission(int rank, String chunkId, String reason) { }
    public record ContextSelection(String policy, List<SelectedEvidence> selected, List<Omission> omissions,
                                    int contextBytes, List<String> limitations) { }
    public record Invocation(String provider, String model, long durationMillis, Integer inputTokens,
                             Integer outputTokens, String finishReason, String returnedModel,
                             Integer connectTimeoutSeconds, Boolean thinkingEnabled, String keepAlive) {
        static Invocation from(ModelGateway.Diagnostics d) {
            return d == null ? null : new Invocation(d.provider(), d.model(), d.durationMillis(), d.inputTokens(),
                    d.outputTokens(), d.finishReason(), d.returnedModel(), d.connectTimeoutSeconds(), d.thinkingEnabled(), d.keepAlive());
        }
    }
    public record AnswerResponse(String apiVersion, String answerVersion, PublicationDetailResponse publication,
                                  String question, String answer, String status, List<Claim> claims,
                                  List<Citation> citations, List<String> uncertainties,
                                  String requestedRetrievalStrategy, String embeddingProfileId, String retrievalStrategy,
                                  List<RetrievedEvidence> retrievedEvidence, List<String> retrievalLimitations,
                                  ContextSelection contextSelection, AnsweringSettings configuration,
                                  String promptVersion, String promptHash, String schemaHash,
                                  ModelSettings generationSettings, Invocation invocation, String validation,
                                  long durationMillis) {
        static AnswerResponse from(AnswerRun run, String requestedStrategy, String profileId) {
            var a = run.answer();
            var p = a.publication();
            var s = a.contextSelection();
            return new AnswerResponse(VERSION, a.version(), PublicationDetailResponse.from(p), a.question(), a.answer(),
                    a.status().name(), a.claims().stream().map(c -> new Claim(c.text(), c.evidenceKeys())).toList(),
                    a.citations().stream().map(c -> new Citation(c.key(),
                            EvidenceReference.from(c.publicationId(), p.snapshotId(), c.evidence()))).toList(),
                    a.uncertainties(), requestedStrategy, profileId, a.retrievalStrategy(),
                    run.retrieval().matches().stream().map(m -> new RetrievedEvidence(m.rank(), m.score(),
                            EvidenceReference.from(p.id(), p.snapshotId(), m.evidence()))).toList(), a.retrievalLimitations(),
                    new ContextSelection(s.policy(), s.selected().stream().map(e -> new SelectedEvidence(e.key(), e.rank(), e.chunkId())).toList(),
                            s.omissions().stream().map(o -> new Omission(o.rank(), o.chunkId(), o.reason())).toList(),
                            s.contextBytes(), s.limitations()), AnsweringSettings.from(a.configuration()),
                    a.promptVersion(), a.promptHash(), a.schemaHash(), ModelSettings.from(a.generationSettings()),
                    Invocation.from(a.invocation()), a.validation(), a.durationMillis());
        }
    }
}
