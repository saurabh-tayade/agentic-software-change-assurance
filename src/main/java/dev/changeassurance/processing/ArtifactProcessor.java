package dev.changeassurance.processing;

import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.processing.ProcessingResult.Reason;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import static dev.changeassurance.ingestion.IngestionResult.*;
import static dev.changeassurance.processing.ProcessingResult.*;

/** In-memory processing of immutable ingestion output; never opens repository files. */
@Component
public final class ArtifactProcessor {
    private static final List<String> LIMITATIONS = List.of(
            "Java 21 syntax only; no compilation, symbol resolution, or test execution. Unicode escape candidates rejected.",
            "CommonMark core only; extension syntax has core semantics. Only document-level headings define sections.",
            "Line-based chunks may contain a long line up to the ingestion file-byte limit; no token-size guarantee.",
            "Declaration, heading, and parser diagnostics are untrusted metadata, not quoted source or instructions.");

    public ProcessingResult process(IngestionResult ingestion) {
        return process(ingestion, ChunkingConfiguration.defaults());
    }

    public ProcessingResult process(IngestionResult ingestion, ChunkingConfiguration configuration) {
        if (ingestion.artifacts().size() + ingestion.coverage().size() > 10_000) {
            throw new IllegalArgumentException("Input exceeds git-snapshot-v1 inventory limit");
        }
        var paths = new HashSet<String>();
        for (var artifact : ingestion.artifacts()) {
            if (!paths.add(artifact.path())) {
                throw new IllegalArgumentException("Duplicate artifact path: " + artifact.path());
            }
        }
        var outcomes = new ArrayList<ArtifactResult>();
        var chunks = new ArrayList<EvidenceChunk>();
        int retainedBytes = 0;
        for (var artifact : ingestion.artifacts().stream().sorted(Comparator.comparing(SourceArtifact::path)).toList()) {
            String parser = switch (artifact.format()) {
                case JAVA -> ChunkingConfiguration.JAVA_PARSER;
                case MARKDOWN -> ChunkingConfiguration.MARKDOWN_PARSER;
            };
            try {
                if (artifact.content().length() > 1024 * 1024) {
                    throw new SourceParsingException(Reason.RESOURCE_LIMIT, "Input exceeds git-snapshot-v1 file limit");
                }
                int size = artifact.contentBytes().length;
                if (size > 1024 * 1024 || size > 16 * 1024 * 1024 - retainedBytes) {
                    throw new SourceParsingException(Reason.RESOURCE_LIMIT, "Input exceeds git-snapshot-v1 byte limits");
                }
                retainedBytes += size;
                if (!artifact.snapshot().equals(ingestion.snapshot())
                        || !EvidenceChunker.hash(artifact.content()).equals(artifact.sha256())) {
                    throw new SourceParsingException(Reason.INTEGRITY_FAILURE, "Snapshot or source digest mismatch");
                }
                if (chunks.size() == configuration.maxChunks() && !artifact.content().isEmpty()) {
                    throw new SourceParsingException(Reason.RESOURCE_LIMIT, "Snapshot chunk limit reached before parsing");
                }
                var lines = new SourceLines(artifact.content());
                ParsedSource parsed = switch (artifact.format()) {
                    case JAVA -> new JavaSourceParser().parse(artifact.content(), lines);
                    case MARKDOWN -> new MarkdownSourceParser().parse(artifact.content(), lines);
                };
                var output = new EvidenceChunker().chunk(artifact, lines, parsed, configuration,
                        parser, configuration.maxChunks() - chunks.size());
                chunks.addAll(output.chunks());
                outcomes.add(new ArtifactResult(artifact.path(), parser,
                        output.limitations().isEmpty() ? Status.COMPLETE : Status.PARTIAL,
                        true, output.chunks().size(), output.limitations()));
            } catch (SourceParsingException exception) {
                outcomes.add(failure(artifact, parser, exception.reason(), exception.getMessage()));
            } catch (StackOverflowError error) {
                outcomes.add(failure(artifact, parser, Reason.RESOURCE_LIMIT, "Parser nesting exceeded JVM stack capacity"));
            } catch (RuntimeException exception) {
                // Do not let a third-party parser crash turn other artifacts into invisible coverage gaps.
                outcomes.add(failure(artifact, parser, Reason.PARSE_FAILURE,
                        "Processing failed: " + exception.getClass().getSimpleName()));
            }
        }
        boolean gap = outcomes.stream().anyMatch(a -> a.status() != Status.COMPLETE)
                || ingestion.coverage().stream().anyMatch(entry -> entry.disposition() != Disposition.EXCLUDED
                || entry.reason() == IngestionResult.Reason.FILE_SIZE_LIMIT
                || entry.reason() == IngestionResult.Reason.TOTAL_CONTENT_LIMIT);
        Status status = !gap ? Status.COMPLETE
                : outcomes.stream().anyMatch(ArtifactResult::parsed) ? Status.PARTIAL : Status.FAILED;
        return new ProcessingResult(ingestion.snapshot(), configuration, status, outcomes, chunks,
                ingestion.coverage(), ingestion.limitations(), ingestion.summary(), LIMITATIONS);
    }

    private ArtifactResult failure(SourceArtifact artifact, String parser, ProcessingResult.Reason reason, String detail) {
        return new ArtifactResult(artifact.path(), parser, Status.FAILED, false, 0,
                List.of(new Limitation(reason, detail)));
    }
}
