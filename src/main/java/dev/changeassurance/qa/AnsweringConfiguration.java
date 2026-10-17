package dev.changeassurance.qa;

public record AnsweringConfiguration(int topK, int maxEvidence, int maxContextBytes, int maxEvidenceBytes) {
    public AnsweringConfiguration {
        if (topK < 1 || topK > 20 || maxEvidence < 1 || maxEvidence > 8 || maxEvidence > topK
                || maxContextBytes < 1500 || maxContextBytes > 8000
                || maxEvidenceBytes < 256 || maxEvidenceBytes > maxContextBytes) {
            throw new QaException(QaException.Code.INVALID_REQUEST);
        }
    }
    public static AnsweringConfiguration defaults() { return new AnsweringConfiguration(12, 6, 6000, 3000); }
}
