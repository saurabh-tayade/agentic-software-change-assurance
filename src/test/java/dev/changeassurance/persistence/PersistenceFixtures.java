package dev.changeassurance.persistence;

import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.processing.ArtifactProcessor;
import dev.changeassurance.processing.ChunkingConfiguration;
import dev.changeassurance.processing.EvidenceChunk;
import dev.changeassurance.processing.ProcessingResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static dev.changeassurance.ingestion.IngestionResult.*;

final class PersistenceFixtures {
    static Snapshot snapshot(String commit, RepositorySelection.Scope scope) {
        return new Snapshot("fixture-repository", commit.repeat(40), scope, "git-snapshot-v1");
    }

    static SourceArtifact source(Snapshot snapshot, String path, Format format, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            var blob = MessageDigest.getInstance("SHA-1");
            blob.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return new SourceArtifact(snapshot, path, format == Format.JAVA ? ArtifactType.SOURCE : ArtifactType.DOCUMENTATION,
                    format, HexFormat.of().formatHex(blob.digest(bytes)), EvidenceValidation.hash(bytes), content);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static IngestionResult input(String commit, RepositorySelection.Scope scope) {
        var snapshot = snapshot(commit, scope);
        return new IngestionResult(snapshot, List.of(source(snapshot, "src/A.java", Format.JAVA,
                "package example;\r\n/** café 😀 */\r\nclass A {\r\n    @Deprecated\r\n    int run() {\r\n        return 1;\r\n    }\r\n}\r\n"),
                source(snapshot, "docs/guide.md", Format.MARKDOWN, "\ufeff# Guide\n\n## Details\nOriginal café 😀.\n")),
                List.of(), List.of("Committed only"));
    }

    static ProcessingResult process(IngestionResult input) {
        return new ArtifactProcessor().process(input);
    }

    static ProcessingResult withChunks(ProcessingResult p, List<EvidenceChunk> chunks) {
        return new ProcessingResult(p.snapshot(), p.configuration(), p.status(), p.artifacts(), chunks,
                p.ingestionCoverage(), p.ingestionLimitations(), p.ingestionSummary(), p.processingLimitations());
    }

    static ProcessingResult withParser(ProcessingResult p, String parser) {
        var chunks = p.chunks().stream().map(c -> {
            var changed = new EvidenceChunk(c.id(), c.snapshot(), c.filePath(), c.artifactType(), c.sourceFormat(),
                    c.gitBlobId(), c.sourceHash(), c.startLine(), c.endLine(), c.content(), c.contentHash(), c.context(),
                    parser, c.chunkingConfiguration());
            return new EvidenceChunk(EvidenceValidation.chunkId(changed), changed.snapshot(), changed.filePath(), changed.artifactType(),
                    changed.sourceFormat(), changed.gitBlobId(), changed.sourceHash(), changed.startLine(), changed.endLine(),
                    changed.content(), changed.contentHash(), changed.context(), parser, changed.chunkingConfiguration());
        }).toList();
        var outcomes = p.artifacts().stream().map(a -> new ProcessingResult.ArtifactResult(a.path(), parser, a.status(),
                a.parsed(), a.chunkCount(), a.limitations())).toList();
        return new ProcessingResult(p.snapshot(), p.configuration(), p.status(), outcomes, chunks,
                p.ingestionCoverage(), p.ingestionLimitations(), p.ingestionSummary(), p.processingLimitations());
    }
}
