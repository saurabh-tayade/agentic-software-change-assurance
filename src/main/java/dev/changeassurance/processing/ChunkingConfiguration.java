package dev.changeassurance.processing;

/** Line-based limits; the ingestion byte limits still bound individual long lines. */
public record ChunkingConfiguration(int maxLines, int maxChunks) {
    public static final String STRATEGY = "source-lines-v1";
    public static final String JAVA_PARSER = "javaparser-core-3.27.1-java21";
    public static final String MARKDOWN_PARSER = "commonmark-0.24.0-core";

    public ChunkingConfiguration {
        if (maxLines < 1 || maxLines > 10_000 || maxChunks < 1 || maxChunks > 100_000) {
            throw new IllegalArgumentException("maxLines must be 1..10000 and maxChunks 1..100000");
        }
    }

    public static ChunkingConfiguration defaults() {
        return new ChunkingConfiguration(80, 10_000);
    }

    public String identity() {
        return STRATEGY + ":lines=" + maxLines + ":chunks=" + maxChunks;
    }
}
