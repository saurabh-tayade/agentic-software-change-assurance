package dev.changeassurance.intelligence;

import dev.changeassurance.embedding.EmbeddingProfile;
import dev.changeassurance.persistence.EvidencePersistenceService;
import dev.changeassurance.persistence.PublicationIntegrity;
import dev.changeassurance.persistence.PublicationReadService;
import dev.changeassurance.qa.AnsweringConfiguration;
import dev.changeassurance.qa.GroundedQuestionAnsweringService;
import dev.changeassurance.qa.ModelGateway;
import dev.changeassurance.qa.QaException;
import dev.changeassurance.retrieval.EvidenceRetriever;
import dev.changeassurance.retrieval.EvidenceRetrievers;
import dev.changeassurance.retrieval.KeywordRetrievalService;
import dev.changeassurance.retrieval.RetrievalStrategy;
import dev.changeassurance.retrieval.VectorRetrievalService;

import java.util.EnumMap;
import java.util.Map;

import static dev.changeassurance.intelligence.RepositoryIntelligenceDtos.*;

/** HTTP use cases only; all retrieval, context selection and generation remain in existing services. */
public final class RepositoryIntelligenceService {
    private final PublicationReadService catalog;
    private final EvidencePersistenceService publications;
    private final Map<RetrievalStrategy, GroundedQuestionAnsweringService> answering;
    private final AnsweringConfiguration configuration;
    private final CapabilitiesResponse capabilities;

    public RepositoryIntelligenceService(PublicationReadService catalog, EvidencePersistenceService publications,
                                          EvidenceRetriever keyword, VectorRetrievalService vector, EmbeddingProfile profile,
                                          ModelGateway model, AnsweringConfiguration configuration, boolean logMalformed) {
        this.catalog = catalog;
        this.publications = publications;
        this.configuration = configuration;
        var services = new EnumMap<RetrievalStrategy, GroundedQuestionAnsweringService>(RetrievalStrategy.class);
        for (var strategy : RetrievalStrategy.values()) {
            if (strategy != RetrievalStrategy.KEYWORD && (vector == null || profile == null)) continue;
            var retriever = EvidenceRetrievers.select(strategy, keyword, vector,
                    strategy == RetrievalStrategy.KEYWORD ? null : profile);
            services.put(strategy, new GroundedQuestionAnsweringService(retriever, model, logMalformed));
        }
        answering = Map.copyOf(services);
        capabilities = new CapabilitiesResponse(VERSION, services.keySet().stream().map(Enum::name).toList(),
                "KEYWORD", profile == null ? null : profile.id(), KeywordRetrievalService.MAX_QUERY_LENGTH,
                AnsweringSettings.from(configuration), ModelSettings.from(model.settings()), "CONFIGURED_NOT_RUNTIME_READINESS");
    }

    public CapabilitiesResponse capabilities() { return capabilities; }

    public PublicationListResponse publications(String repositoryId, String after, int limit) {
        var page = catalog.list(repositoryId, after, limit);
        return new PublicationListResponse(VERSION, page.items().stream().map(PublicationReference::from).toList(), page.nextAfter());
    }

    public PublicationDetailResponse publication(String id) {
        return PublicationDetailResponse.from(catalog.find(id).orElseThrow(() -> new QaException(QaException.Code.UNKNOWN_PUBLICATION)));
    }

    public EvidenceResponse evidence(String publicationId, String chunkId) {
        PublicationIntegrity.validateId(publicationId);
        PublicationIntegrity.validateId(chunkId);
        var publication = publications.findPublication(publicationId)
                .orElseThrow(() -> new QaException(QaException.Code.UNKNOWN_PUBLICATION));
        var chunk = publication.processing().chunks().stream().filter(c -> c.id().equals(chunkId)).findFirst()
                .orElseThrow(() -> new ApiException(404, "UNKNOWN_EVIDENCE", "Evidence is not present in the selected publication."));
        PublicationIntegrity.verifyChunk(chunk);
        return new EvidenceResponse(VERSION, EvidenceReference.from(publicationId, publication.snapshotId(), chunk),
                chunk.content(), chunk.context());
    }

    public AnswerResponse answer(String publicationId, AnswerRequest request) {
        PublicationIntegrity.validateId(publicationId);
        if (request == null || request.question() == null || request.question().isBlank()
                || request.question().length() > KeywordRetrievalService.MAX_QUERY_LENGTH
                || request.question().indexOf('\0') >= 0
                || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(request.question()))
            throw new QaException(QaException.Code.INVALID_REQUEST);
        var strategy = request.retrievalStrategy() == null ? RetrievalStrategy.KEYWORD
                : RetrievalStrategy.valueOf(request.retrievalStrategy());
        var service = answering.get(strategy);
        if (service == null) throw new QaException(QaException.Code.RETRIEVAL_NOT_CONFIGURED);
        var run = service.answerWithRetrieval(publicationId, request.question(), configuration);
        return AnswerResponse.from(run, strategy.name(), strategy == RetrievalStrategy.KEYWORD ? null : capabilities.embeddingProfileId());
    }
}
