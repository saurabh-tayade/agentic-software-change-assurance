package dev.changeassurance.persistence;

import dev.changeassurance.ingestion.IngestionResult;
import dev.changeassurance.ingestion.IngestionResult.SourceArtifact;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.processing.EvidenceChunk;
import dev.changeassurance.processing.ProcessingResult;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static dev.changeassurance.processing.ProcessingResult.Status.*;

/** Validates existing contracts without invoking Git, parsers, or the chunker. */
final class EvidenceValidation {
    private EvidenceValidation() { }

    static void validate(IngestionResult ingestion, ProcessingResult result) {
        Objects.requireNonNull(ingestion, "ingestion");
        Objects.requireNonNull(result, "processing");
        var snapshot = Objects.requireNonNull(ingestion.snapshot(), "snapshot");
        require(snapshot.equals(result.snapshot()), "Processing snapshot mismatch");
        require(snapshot.repositoryId() != null && !snapshot.repositoryId().isBlank(), "Missing repository identity");
        require(snapshot.commitSha() != null && snapshot.commitSha().matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid commit SHA");
        require(snapshot.policyVersion() != null && !snapshot.policyVersion().isBlank(), "Missing ingestion policy");
        Objects.requireNonNull(snapshot.scope(), "scope");
        require(ingestion.artifacts().size() + ingestion.coverage().size() <= 10_000, "Inventory limit exceeded");
        require(result.chunks().size() <= result.configuration().maxChunks(), "Chunk budget exceeded");
        require(ingestion.coverage().equals(result.ingestionCoverage())
                && ingestion.limitations().equals(result.ingestionLimitations())
                && ingestion.summary().equals(result.ingestionSummary()), "Ingestion coverage mismatch");
        Map<String, SourceArtifact> sources = new HashMap<>();
        Map<String, int[]> offsets = new HashMap<>();
        var inventory = new HashSet<String>();
        int total = 0;
        for (var source : ingestion.artifacts()) {
            require(snapshot.equals(source.snapshot()), "Artifact snapshot mismatch");
            // Reuse M1.2's literal safe-path validation, without accessing a repository.
            new RepositorySelection.Scope(List.of(source.path()), List.of());
            require((snapshot.scope().includes().isEmpty() || snapshot.scope().includes().stream()
                    .anyMatch(prefix -> matches(source.path(), prefix))) && snapshot.scope().excludes().stream()
                    .noneMatch(prefix -> matches(source.path(), prefix)), "Source is outside selected scope");
            require(inventory.add(source.path()), "Duplicate source path");
            Objects.requireNonNull(source.type(), "artifact type");
            Objects.requireNonNull(source.format(), "source format");
            require(source.content().length() <= 1024 * 1024, "File limit exceeded");
            byte[] bytes = source.contentBytes();
            require(bytes.length <= 1024 * 1024 && (total += bytes.length) <= 16 * 1024 * 1024, "Source byte limit exceeded");
            require(source.content().equals(new String(bytes, StandardCharsets.UTF_8)) && source.content().indexOf(0) < 0,
                    "Source is not lossless PostgreSQL UTF-8 text");
            require(hash(bytes).equals(source.sha256()), "Source SHA-256 mismatch");
            require(source.gitBlobId() != null && source.gitBlobId().matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid Git blob ID");
            require(source.gitBlobId().length() == snapshot.commitSha().length(), "Git object formats disagree");
            MessageDigest blob = digest(source.gitBlobId().length() == 40 ? "SHA-1" : "SHA-256");
            blob.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            require(HexFormat.of().formatHex(blob.digest(bytes)).equals(source.gitBlobId()), "Git blob digest mismatch");
            sources.put(source.path(), source);
            offsets.put(source.path(), lineOffsets(source.content()));
        }
        for (var coverage : ingestion.coverage()) {
            // Unsafe paths may legitimately occur in failed ingestion coverage.
            require(coverage.path() != null && inventory.add(coverage.path()), "Duplicate/missing coverage path");
            Objects.requireNonNull(coverage.disposition(), "coverage disposition");
            Objects.requireNonNull(coverage.reason(), "coverage reason");
            Objects.requireNonNull(coverage.detail(), "coverage detail");
        }
        Map<String, ProcessingResult.ArtifactResult> outcomes = new HashMap<>();
        for (var outcome : result.artifacts()) {
            require(sources.containsKey(outcome.path()) && outcomes.put(outcome.path(), outcome) == null,
                    "Unknown or duplicate processing outcome");
            require(outcome.parser() != null && !outcome.parser().isBlank(), "Missing parser identity");
            require(outcome.chunkCount() >= 0, "Negative chunk count");
            require(outcome.status() != null, "Missing artifact status");
            for (var limitation : outcome.limitations()) {
                require(limitation.reason() != null && limitation.detail() != null, "Missing typed processing diagnostic");
            }
            if (outcome.status() == FAILED) {
                require(!outcome.parsed() && outcome.chunkCount() == 0 && !outcome.limitations().isEmpty(), "Invalid failed outcome");
            } else {
                require(outcome.parsed(), "Successful outcome must be parsed");
                require((outcome.status() == COMPLETE) == outcome.limitations().isEmpty(), "Outcome limitations/status mismatch");
            }
        }
        require(outcomes.size() == sources.size(), "Missing processing outcomes");
        var ids = new HashSet<String>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> nextLine = new HashMap<>();
        String previousPath = "";
        long chunkMetadataBytes = 0;
        for (var chunk : result.chunks()) {
            chunkMetadataBytes += PersistenceJson.write(chunk.context()).getBytes(StandardCharsets.UTF_8).length;
            require(chunkMetadataBytes <= 16 * 1024 * 1024, "Chunk context metadata exceeds 16 MiB");
            var source = sources.get(chunk.filePath());
            require(source != null, "Chunk references unknown artifact");
            require(ids.add(chunk.id()), "Duplicate chunk membership");
            require(chunk.filePath().compareTo(previousPath) >= 0, "Chunks must be in deterministic source order");
            previousPath = chunk.filePath();
            require(snapshot.equals(chunk.snapshot()) && source.type().name().equals(chunk.artifactType())
                    && source.format().name().equals(chunk.sourceFormat()) && source.gitBlobId().equals(chunk.gitBlobId())
                    && source.sha256().equals(chunk.sourceHash()), "Chunk provenance mismatch");
            require(chunk.chunkingConfiguration().equals(result.configuration().identity())
                    && chunk.parser().equals(outcomes.get(chunk.filePath()).parser()), "Chunk derivation mismatch");
            int[] starts = offsets.get(chunk.filePath());
            require(chunk.startLine() == nextLine.getOrDefault(chunk.filePath(), 1)
                    && chunk.endLine() < starts.length && chunk.endLine() - chunk.startLine() + 1 <= result.configuration().maxLines(),
                    "Invalid, overlapping, missing, or oversized source span");
            require(source.content().substring(starts[chunk.startLine() - 1], starts[chunk.endLine()]).equals(chunk.content()),
                    "Quoted content does not match original source span");
            require(hash(chunk.content().getBytes(StandardCharsets.UTF_8)).equals(chunk.contentHash()), "Chunk digest mismatch");
            require(chunkId(chunk).equals(chunk.id()), "Chunk deterministic identity mismatch");
            nextLine.put(chunk.filePath(), chunk.endLine() + 1);
            counts.merge(chunk.filePath(), 1, Integer::sum);
        }
        for (var outcome : result.artifacts()) {
            require(outcome.chunkCount() == counts.getOrDefault(outcome.path(), 0), "Outcome chunk count mismatch");
            if (outcome.parsed()) {
                boolean omitted = nextLine.getOrDefault(outcome.path(), 1) != offsets.get(outcome.path()).length;
                require(!omitted || outcome.limitations().stream().anyMatch(l -> l.reason() == ProcessingResult.Reason.RESOURCE_LIMIT),
                        "Missing source requires resource limitation");
            }
        }
        boolean gap = result.artifacts().stream().anyMatch(a -> a.status() != COMPLETE)
                || ingestion.coverage().stream().anyMatch(c -> c.disposition() != IngestionResult.Disposition.EXCLUDED
                || c.reason() == IngestionResult.Reason.FILE_SIZE_LIMIT || c.reason() == IngestionResult.Reason.TOTAL_CONTENT_LIMIT);
        var expected = !gap ? COMPLETE : result.artifacts().stream().anyMatch(ProcessingResult.ArtifactResult::parsed) ? PARTIAL : FAILED;
        require(result.status() == expected, "Overall processing status mismatch");
        require(PersistenceJson.write(PublicationMetadata.from(ingestion, result)).getBytes(StandardCharsets.UTF_8).length <= 16 * 1024 * 1024,
                "Publication metadata exceeds 16 MiB");
    }

    static String snapshotId(IngestionResult.Snapshot snapshot) {
        return hash(PersistenceJson.write(List.of("snapshot-v1", snapshot.repositoryId(), snapshot.commitSha(),
                snapshot.scope().includes(), snapshot.scope().excludes(), snapshot.policyVersion())).getBytes(StandardCharsets.UTF_8));
    }

    static String derivationId(ProcessingResult result) {
        return hash(PersistenceJson.write(List.of("processing-contract-v1",
                result.artifacts().stream().map(ProcessingResult.ArtifactResult::parser).distinct().sorted().toList()))
                .getBytes(StandardCharsets.UTF_8));
    }

    static String publicationId(String snapshot, String derivation, ProcessingResult result) {
        return hash(PersistenceJson.write(List.of("publication-v1", snapshot, result.configuration().identity(), derivation))
                .getBytes(StandardCharsets.UTF_8));
    }

    // The documented M1.3 identity wire format; no parsing/chunking implementation is repeated here.
    static String chunkId(EvidenceChunk chunk) {
        var fields = new ArrayList<>(List.of(chunk.snapshot().repositoryId(), chunk.snapshot().commitSha(),
                chunk.filePath(), chunk.artifactType(), chunk.sourceFormat(), chunk.sourceHash(),
                Integer.toString(chunk.startLine()), Integer.toString(chunk.endLine()), chunk.contentHash(),
                chunk.parser(), chunk.chunkingConfiguration()));
        fields.addAll(chunk.context());
        var digest = digest("SHA-256");
        for (var field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static int[] lineOffsets(String content) {
        var offsets = new ArrayList<Integer>();
        offsets.add(0);
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                i++;
            }
            if ((c == '\r' || c == '\n') && i + 1 < content.length()) {
                offsets.add(i + 1);
            }
        }
        if (!content.isEmpty()) {
            offsets.add(content.length());
        }
        return offsets.stream().mapToInt(Integer::intValue).toArray();
    }

    static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(digest("SHA-256").digest(bytes));
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required digest unavailable", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean matches(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
