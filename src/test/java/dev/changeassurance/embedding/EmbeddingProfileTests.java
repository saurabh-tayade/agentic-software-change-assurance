package dev.changeassurance.embedding;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingProfileTests {
    static EmbeddingProfile profile() {
        return new EmbeddingProfile("ollama", "nomic-embed-text:v1.5", "a".repeat(64), 768,
                EmbeddingProfile.DOCUMENT_RECIPE, EmbeddingProfile.QUERY_RECIPE, EmbeddingProfile.Options.defaults(), "cosine");
    }
    @Test void identityIsStableAndIncludesDigestModelAndOptions() {
        var p = profile();
        assertEquals(p.id(), profile().id());
        assertTrue(p.id().matches("[0-9a-f]{64}"));
        assertNotEquals(p.id(), new EmbeddingProfile(p.provider(), p.model(), "b".repeat(64), 768,
                p.documentRecipe(), p.queryRecipe(), p.options(), p.distanceMetric()).id());
        assertNotEquals(p.id(), new EmbeddingProfile(p.provider(), "nomic-embed-text:latest", p.modelDigest(), 768,
                p.documentRecipe(), p.queryRecipe(), p.options(), p.distanceMetric()).id());
        assertNotEquals(p.id(), new EmbeddingProfile(p.provider(), p.model(), p.modelDigest(), 768,
                p.documentRecipe(), p.queryRecipe(), new EmbeddingProfile.Options(2048, 100, false, "0s"), p.distanceMetric()).id());
    }
    @Test void exactUtf8IncludingPrefixCrLfAndUnicodeIsHashed() throws Exception {
        String source = " café 😀\r\n";
        var input = profile().document(source);
        byte[] bytes = ("search_document: " + source).getBytes(StandardCharsets.UTF_8);
        assertEquals("search_document: " + source, input.text());
        assertEquals(bytes.length, input.bytes());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), input.hash());
        assertNotEquals(input.hash(), profile().document(source.replace("\r\n", "\n")).hash());
    }
    @Test void rejectsInvalidProfilesTruncationAndUnsupportedRecipes() {
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingProfile("ollama", "nomic-embed-text:v1.5", "tag-only", 768,
                EmbeddingProfile.DOCUMENT_RECIPE, EmbeddingProfile.QUERY_RECIPE, EmbeddingProfile.Options.defaults(), "cosine"));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingProfile("ollama", "nomic-embed-text:v1.5", "a".repeat(64), 3,
                EmbeddingProfile.DOCUMENT_RECIPE, EmbeddingProfile.QUERY_RECIPE, EmbeddingProfile.Options.defaults(), "cosine"));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingProfile.Options(2048, 8192, true, "0s"));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingProfile("ollama", "nomic-embed-text:v1.5", "a".repeat(64), 768,
                "unknown", EmbeddingProfile.QUERY_RECIPE, EmbeddingProfile.Options.defaults(), "cosine"));
    }
    @Test void queryRecipePreservesExactTextAndHashesItsOwnTaskPrefix() throws Exception {
        String query = " Where is café 😀?\r\n";
        var input = profile().query(query);
        byte[] bytes = ("search_query: " + query).getBytes(StandardCharsets.UTF_8);
        assertEquals("search_query: " + query, input.text());
        assertEquals(bytes.length, input.bytes());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), input.hash());
        assertNotEquals(profile().document(query).hash(), input.hash());
        assertThrows(IllegalArgumentException.class, () -> profile().query("\ud800"));
        assertEquals(EmbeddingException.Code.OVERSIZED_INPUT,
                assertThrows(EmbeddingException.class, () -> profile().query("x".repeat(8192))).code());
    }
    @Test void inputLimitCountsUtf8BytesAndDoesNotTruncate() {
        assertEquals(8192, profile().document("a".repeat(8175)).bytes());
        assertEquals(EmbeddingException.Code.OVERSIZED_INPUT,
                assertThrows(EmbeddingException.class, () -> profile().document("a".repeat(8176))).code());
        assertThrows(EmbeddingException.class, () -> profile().document("😀".repeat(2044)));
        assertThrows(IllegalArgumentException.class, () -> profile().document("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> profile().document("\0"));
    }
    @Test void vectorsRequireExactDimensionsFiniteValuesAndNonzeroNorm() {
        float[] vector = new float[768]; vector[0] = 1;
        assertDoesNotThrow(() -> EmbeddingVector.validate(vector, 768));
        assertThrows(EmbeddingException.class, () -> EmbeddingVector.validate(new float[3], 768));
        assertThrows(EmbeddingException.class, () -> EmbeddingVector.validate(new float[768], 768));
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            vector[1] = invalid;
            assertThrows(EmbeddingException.class, () -> EmbeddingVector.validate(vector, 768));
        }
    }
    @Test void gatewayResponseOwnsItsVector() {
        float[] vector = {1, 2};
        var response = new EmbeddingGateway.Response(vector, 1);
        vector[0] = 10; response.vector()[1] = 20;
        assertArrayEquals(new float[]{1, 2}, response.vector());
    }
}
