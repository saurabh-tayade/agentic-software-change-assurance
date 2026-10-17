package dev.changeassurance.qa;

import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.persistence.PublicationMetadata;
import dev.changeassurance.persistence.PublicationSummary;
import dev.changeassurance.processing.ArtifactProcessor;
import dev.changeassurance.processing.ChunkingConfiguration;
import dev.changeassurance.retrieval.RetrievalResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

final class QaFixtures {
    static final String PUBLICATION = "a".repeat(64);
    static final String VALID = """
            {"status":"SUPPORTED","claims":[{"text":"The policy returns 800 cents.","evidenceKeys":["E1"]}],"uncertainties":[]}
            """;

    private QaFixtures() { }

    static RetrievalResult retrieval(String... contents) {
        var snapshot = new IngestionResult.Snapshot("qa-fixture", "b".repeat(40), RepositorySelection.Scope.all(), "git-snapshot-v1");
        var sources = new ArrayList<IngestionResult.SourceArtifact>();
        for (int i = 0; i < contents.length; i++) {
            byte[] bytes = contents[i].getBytes(StandardCharsets.UTF_8);
            try {
                var blob = MessageDigest.getInstance("SHA-1");
                blob.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
                sources.add(new IngestionResult.SourceArtifact(snapshot, "evidence-" + i + ".md",
                        IngestionResult.ArtifactType.DOCUMENTATION, IngestionResult.Format.MARKDOWN,
                        HexFormat.of().formatHex(blob.digest(bytes)),
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), contents[i]));
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        var ingestion = new IngestionResult(snapshot, sources, List.of(), List.of("Committed source only"));
        var processed = new ArtifactProcessor().process(ingestion, new ChunkingConfiguration(80, 10000));
        var metadata = new PublicationMetadata(sources.stream().map(IngestionResult.SourceArtifact::path).toList(),
                processed.configuration(), processed.status(), processed.artifacts(), processed.ingestionCoverage(),
                processed.ingestionLimitations(), processed.ingestionSummary(), processed.processingLimitations());
        // Synthetic publication identities are sufficient at this already-verified retrieval boundary.
        // GroundedQuestionAnsweringIT exercises actual persistence-derived publication identities.
        var publication = new PublicationSummary(PUBLICATION, "c".repeat(64), "d".repeat(64), snapshot, metadata);
        var matches = new ArrayList<RetrievalResult.Match>();
        for (int i = 0; i < processed.chunks().size(); i++) {
            matches.add(new RetrievalResult.Match(i + 1, 1.0 / (i + 1), processed.chunks().get(i)));
        }
        return new RetrievalResult(publication, "What does the policy return?", 12, "postgres-keyword-v1", matches,
                List.of("Keyword matching does not establish support."));
    }

    static RetrievalResult withMatches(RetrievalResult original, List<RetrievalResult.Match> matches) {
        return new RetrievalResult(original.publication(), original.query(), original.topK(), original.strategy(), matches, original.limitations());
    }

    static final class Gateway implements ModelGateway {
        int calls;
        String system;
        String context;
        String schema;
        String output = VALID;
        QaException failure;

        @Override public GenerationSettings settings() { return GenerationSettings.defaults(); }
        @Override public Response generate(String systemPrompt, String contextJson, String outputSchema) {
            calls++;
            system = systemPrompt;
            context = contextJson;
            schema = outputSchema;
            if (failure != null) throw failure;
            return new Response(output, new Diagnostics("ollama", settings().model(), 25, null, null, "stop"));
        }
    }
}
