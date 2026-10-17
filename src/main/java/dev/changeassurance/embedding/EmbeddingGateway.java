package dev.changeassurance.embedding;

/** Explicit model access; no database, publication lookup, downloads, or retrieval. */
public interface EmbeddingGateway {
    EmbeddingProfile resolveProfile();
    Response embed(EmbeddingProfile expected, EmbeddingProfile.Input input);

    record Response(float[] vector, long durationMillis) {
        public Response { vector = vector == null ? null : vector.clone(); }
        @Override public float[] vector() { return vector == null ? null : vector.clone(); }
    }
}
