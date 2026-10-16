package dev.changeassurance.processing;

import dev.changeassurance.ingestion.IngestionResult.SourceArtifact;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

final class EvidenceChunker {
    Output chunk(SourceArtifact artifact, SourceLines lines, ParsedSource parsed,
                 ChunkingConfiguration configuration, String parser, int remaining) {
        var chunks = new ArrayList<EvidenceChunk>();
        var limitations = new ArrayList<ProcessingResult.Limitation>();
        int expected = 1;
        for (var region : parsed.regions()) {
            if (region.start() != expected || region.end() > lines.size()) {
                throw new SourceParsingException(ProcessingResult.Reason.PARSE_FAILURE,
                        "Parsed regions do not partition the original source");
            }
            expected = region.end() + 1;
        }
        if (expected != lines.size() + 1) {
            throw new SourceParsingException(ProcessingResult.Reason.PARSE_FAILURE, "Incomplete source mapping");
        }
        for (var region : parsed.regions()) {
            var boundaries = new TreeSet<>(region.boundaries());
            if (region.end() - region.start() + 1 > configuration.maxLines()) {
                limitations.add(new ProcessingResult.Limitation(ProcessingResult.Reason.SPLIT_ELEMENT,
                        "Region at original lines " + region.start() + "–" + region.end()
                                + " exceeds maxLines; split at block boundaries or physical lines"));
            }
            for (int start = region.start(); start <= region.end();) {
                if (chunks.size() == remaining) {
                    limitations.add(new ProcessingResult.Limitation(ProcessingResult.Reason.RESOURCE_LIMIT,
                            "Snapshot chunk limit reached; remaining original lines were not emitted"));
                    return new Output(chunks, limitations);
                }
                int end = Math.min(region.end(), start + configuration.maxLines() - 1);
                if (end < region.end()) {
                    Integer boundary = boundaries.floor(end);
                    if (boundary != null && boundary >= start) {
                        end = boundary;
                    }
                }
                String content = lines.slice(start, end);
                String hash = hash(content);
                var identity = new ArrayList<>(List.of(artifact.snapshot().repositoryId(),
                        artifact.snapshot().commitSha(), artifact.path(), artifact.type().name(),
                        artifact.format().name(), artifact.sha256(), Integer.toString(start), Integer.toString(end),
                        hash, parser, configuration.identity()));
                identity.addAll(region.context());
                chunks.add(new EvidenceChunk(identity(identity), artifact.snapshot(), artifact.path(),
                        artifact.type().name(), artifact.format().name(), artifact.gitBlobId(), artifact.sha256(),
                        start, end, content, hash, region.context(), parser, configuration.identity()));
                start = end + 1;
            }
        }
        return new Output(chunks, limitations);
    }

    static String hash(String content) {
        return HexFormat.of().formatHex(digest().digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String identity(List<String> fields) {
        MessageDigest digest = digest();
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required JDK digest unavailable", exception);
        }
    }

    record Output(List<EvidenceChunk> chunks, List<ProcessingResult.Limitation> limitations) { }
}
