package dev.changeassurance.persistence;

import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.processing.EvidenceChunk;
import dev.changeassurance.processing.ProcessingResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.changeassurance.persistence.PersistenceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceValidationTests {
    @Test
    void acceptsActualContractsAndPreservesTheExistingChunkIdentityAlgorithm() {
        var input = input("a", RepositorySelection.Scope.all());
        var result = process(input);
        assertDoesNotThrow(() -> EvidenceValidation.validate(input, result));
        result.chunks().forEach(c -> assertEquals(c.id(), EvidenceValidation.chunkId(c)));
        assertEquals(EvidenceValidation.snapshotId(input.snapshot()), EvidenceValidation.snapshotId(input.snapshot()));
        assertNotEquals(EvidenceValidation.snapshotId(input.snapshot()), EvidenceValidation.snapshotId(
                snapshot("a", new RepositorySelection.Scope(List.of("src"), List.of()))));
    }

    @Test
    void rejectsSourceOutsideSnapshotSelection() {
        var input = input("a", new RepositorySelection.Scope(List.of("src"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> EvidenceValidation.validate(input, process(input)));
    }

    @Test
    void rejectsSnapshotDigestAssociationSpanConfigurationAndCountCorruption() {
        var input = input("a", RepositorySelection.Scope.all());
        var p = process(input);
        assertThrows(IllegalArgumentException.class, () -> EvidenceValidation.validate(input("b", RepositorySelection.Scope.all()), p));
        var source = input.artifacts().getFirst();
        var badSource = new IngestionResult.SourceArtifact(source.snapshot(), source.path(), source.type(), source.format(),
                source.gitBlobId(), "0".repeat(64), source.content());
        assertThrows(IllegalArgumentException.class, () -> EvidenceValidation.validate(
                new IngestionResult(input.snapshot(), List.of(badSource, input.artifacts().getLast()), input.coverage(), input.limitations()), p));
        var c = p.chunks().getFirst();
        for (String defect : List.of("digest", "path", "span", "config", "content", "id")) {
            var bad = new EvidenceChunk(defect.equals("id") ? "0".repeat(64) : c.id(), c.snapshot(),
                    defect.equals("path") ? "missing.md" : c.filePath(), c.artifactType(), c.sourceFormat(), c.gitBlobId(), c.sourceHash(),
                    c.startLine(), defect.equals("span") ? 999 : c.endLine(), defect.equals("content") ? "wrong" : c.content(),
                    defect.equals("digest") ? "0".repeat(64) : c.contentHash(), c.context(), c.parser(),
                    defect.equals("config") ? "other" : c.chunkingConfiguration());
            var chunks = new ArrayList<>(p.chunks());
            chunks.set(0, bad);
            assertThrows(IllegalArgumentException.class, () -> EvidenceValidation.validate(input, withChunks(p, chunks)), defect);
        }
        assertThrows(IllegalArgumentException.class, () -> EvidenceValidation.validate(input, withChunks(p, List.of())));
        var wrong = new ProcessingResult(p.snapshot(), p.configuration(), ProcessingResult.Status.FAILED, p.artifacts(), p.chunks(),
                p.ingestionCoverage(), p.ingestionLimitations(), p.ingestionSummary(), p.processingLimitations());
        assertThrows(IllegalArgumentException.class, () -> EvidenceValidation.validate(input, wrong));
    }
}
