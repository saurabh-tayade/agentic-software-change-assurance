package dev.changeassurance.qa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class QaPrompt {
    public static final String VERSION = "repository-qa-v2";
    public static final String SYSTEM = resource("/qa/repository-qa-v2.txt");
    public static final String SCHEMA = resource("/qa/proposed-answer-v1.json");
    private QaPrompt() { }
    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String resource(String path) {
        try (var stream = QaPrompt.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing versioned Q&A resource");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("Cannot load Q&A resource", e); }
    }
}
