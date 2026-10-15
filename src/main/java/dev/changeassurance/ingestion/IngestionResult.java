package dev.changeassurance.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Immutable source material and coverage, with no claims about parsed semantics. */
public record IngestionResult(Snapshot snapshot, List<SourceArtifact> artifacts,
                              List<CoverageEntry> coverage, List<String> limitations) {
    public IngestionResult {
        artifacts = List.copyOf(artifacts);
        coverage = List.copyOf(coverage);
        limitations = List.copyOf(limitations);
    }

    public Summary summary() {
        return new Summary(artifacts.size() + coverage.size(), artifacts.size(),
                count(Disposition.EXCLUDED), count(Disposition.UNSUPPORTED), count(Disposition.FAILED));
    }

    private int count(Disposition disposition) {
        return (int) coverage.stream().filter(entry -> entry.disposition() == disposition).count();
    }

    public record Snapshot(String repositoryId, String commitSha, RepositorySelection.Scope scope,
                           String policyVersion) { }

    /** Strict UTF-8 decoding is lossless: contentBytes returns the original blob bytes. */
    public record SourceArtifact(Snapshot snapshot, String path, ArtifactType type, Format format,
                                 String gitBlobId, String sha256, String content) {
        public byte[] contentBytes() {
            return content.getBytes(StandardCharsets.UTF_8);
        }
    }

    public enum ArtifactType { SOURCE, TEST, DOCUMENTATION }
    public enum Format { JAVA, MARKDOWN }
    public enum Disposition { EXCLUDED, UNSUPPORTED, FAILED }
    public enum Reason {
        OUTSIDE_SCOPE, EXPLICIT_EXCLUSION, GENERATED_OR_DEPENDENCY_DIRECTORY,
        SYMLINK, SUBMODULE, FILE_TYPE, FILE_SIZE_LIMIT, TOTAL_CONTENT_LIMIT,
        BINARY_CONTENT, INVALID_UTF8, UNSAFE_PATH, OBJECT_READ_FAILED
    }

    /** All coverage entries refer to the enclosing result's snapshot. */
    public record CoverageEntry(String path, Disposition disposition, Reason reason, String detail) { }
    public record Summary(int discovered, int ingested, int excluded, int unsupported, int failed) { }
}
