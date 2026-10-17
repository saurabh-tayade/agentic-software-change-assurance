package dev.changeassurance.embedding;


import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Immutable effective input/model specification. A tag alone never identifies a profile. */
public record EmbeddingProfile(String provider, String model, String modelDigest, int dimensions,
                               String documentRecipe, String queryRecipe, Options options, String distanceMetric) {
    public static final String DOCUMENT_RECIPE = "nomic-document-v1";
    public static final String QUERY_RECIPE = "nomic-query-v1";
    public record Options(int contextTokens, int maxInputBytes, boolean truncate, String keepAlive) {
        public Options {
            if (contextTokens != 2048 || maxInputBytes < 32 || maxInputBytes > 8192 || truncate || !"0s".equals(keepAlive))
                throw new IllegalArgumentException("Expected bounded, untruncated Nomic embedding options");
        }
        public static Options defaults() { return new Options(2048, 8192, false, "0s"); }
    }
    public EmbeddingProfile {
        if (!"ollama".equals(provider) || model == null || !model.matches("[A-Za-z0-9._:/-]{1,200}")
                || modelDigest == null || !modelDigest.matches("[0-9a-f]{64}") || dimensions != 768
                || !DOCUMENT_RECIPE.equals(documentRecipe) || !QUERY_RECIPE.equals(queryRecipe)
                || options == null || !"cosine".equals(distanceMetric))
            throw new IllegalArgumentException("Invalid or unsupported embedding profile");
    }
    public String id() {
        // Length-prefixed UTF-8 fields, fixed order, no JSON/map ordering dependency.
        var fields = List.of("embedding-profile-v1", provider, model, modelDigest, Integer.toString(dimensions),
                documentRecipe, queryRecipe, Integer.toString(options.contextTokens()),
                Integer.toString(options.maxInputBytes()), Boolean.toString(options.truncate()), options.keepAlive(), distanceMetric);
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            for (var field : fields) {
                byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
                out.writeInt(encoded.length); out.write(encoded);
            }
            return EmbeddingProfile.hash(bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    public Input document(String source) { return input("search_document: ", source); }
    public Input query(String query) { return input("search_query: ", query); }
    private Input input(String prefix, String source) {
        if (source == null || source.isEmpty() || source.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(source))
            throw new IllegalArgumentException("Embedding input must be nonempty valid Unicode without NUL");
        String text = prefix + source;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > options.maxInputBytes()) throw new EmbeddingException(EmbeddingException.Code.OVERSIZED_INPUT);
        return new Input(text, EmbeddingProfile.hash(bytes), bytes.length);
    }
    static String hash(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public record Input(String text, String hash, int bytes) { }
}
