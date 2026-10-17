package dev.changeassurance.embedding;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Validated float32 values; no implicit normalization or dimensionality reduction. */
public final class EmbeddingVector {
    private EmbeddingVector() { }
    public static void validate(float[] vector, int dimensions) {
        if (vector == null || vector.length != dimensions) fail();
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) fail();
            norm += (double) value * value;
        }
        if (norm == 0 || !Double.isFinite(norm)) fail();
    }
    public static String hash(float[] vector) {
        var bytes = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float value : vector) bytes.putFloat(value == 0 ? 0 : value);
        return EmbeddingProfile.hash(bytes.array());
    }
    public static String sql(float[] vector) { return Arrays.toString(vector); }
    public static void verifyStored(String text, String checksum, int dimensions) {
        try {
            float[] vector = parse(text);
            validate(vector, dimensions);
            if (!hash(vector).equals(checksum)) fail();
        } catch (EmbeddingException e) { throw new EmbeddingException(EmbeddingException.Code.INDEX_INTEGRITY_FAILURE); }
    }
    static float[] parse(String text) {
        try {
            if (text == null || !text.startsWith("[") || !text.endsWith("]")) fail();
            String[] values = text.substring(1, text.length() - 1).split(",");
            var vector = new float[values.length];
            for (int i = 0; i < values.length; i++) vector[i] = Float.parseFloat(values[i]);
            return vector;
        } catch (NumberFormatException e) { throw new EmbeddingException(EmbeddingException.Code.INVALID_VECTOR); }
    }
    private static void fail() { throw new EmbeddingException(EmbeddingException.Code.INVALID_VECTOR); }
}
