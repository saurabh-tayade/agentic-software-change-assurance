package dev.changeassurance.intelligence;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import static dev.changeassurance.intelligence.RepositoryIntelligenceDtos.*;

@RestController
@Profile("intelligence-api")
@RequestMapping(value = "/api/v1", produces = "application/json")
public final class RepositoryIntelligenceController {
    private final RepositoryIntelligenceService service;
    public RepositoryIntelligenceController(RepositoryIntelligenceService service) { this.service = service; }

    @GetMapping("/capabilities")
    public CapabilitiesResponse capabilities() { return service.capabilities(); }

    @GetMapping("/publications")
    public PublicationListResponse publications(@RequestParam(required = false) String repositoryId,
                                                @RequestParam(required = false) String after,
                                                @RequestParam(required = false) String limit) {
        return service.publications(repositoryId, after, limit == null ? 20 : Integer.parseInt(limit));
    }

    @GetMapping("/publications/{publicationId}")
    public PublicationDetailResponse publication(@PathVariable String publicationId) { return service.publication(publicationId); }

    @PostMapping(value = "/publications/{publicationId}/answers", consumes = "application/json")
    public AnswerResponse answer(@PathVariable String publicationId, @RequestBody AnswerRequest request) {
        return service.answer(publicationId, request);
    }

    @GetMapping("/publications/{publicationId}/evidence/{chunkId}")
    public EvidenceResponse evidence(@PathVariable String publicationId, @PathVariable String chunkId) {
        return service.evidence(publicationId, chunkId);
    }
}
